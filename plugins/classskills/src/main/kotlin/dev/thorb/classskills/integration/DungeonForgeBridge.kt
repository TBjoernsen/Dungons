package dev.thorb.classskills.integration

import dev.thorb.classskills.ClassSkillsPlugin
import dev.thorb.classskills.model.ClassType
import java.lang.reflect.Method
import java.util.OptionalInt
import java.util.Optional
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor

/**
 * DungeonForge API v5 integration.
 *
 * DungeonForge is optional, so this bridge deliberately resolves its API classes at
 * runtime and never packages them into ClassSkills. Event classes are registered with
 * Bukkit directly after resolving DungeonForge's single public API package.
 */
class DungeonForgeBridge(private val plugin: ClassSkillsPlugin) : Listener {
    private var api: Any? = null
    private var isInDungeon: Method? = null
    private var isDungeonMob: Method? = null
    private var getDifficulty: Method? = null
    private var apiVersion = 0
    private val deniedEntries = mutableSetOf<UUID>()
    private val lastSafeLocations = mutableMapOf<UUID, Location>()

    fun connect() {
        val apiClass = try {
            Class.forName("nl.riddernix.dungeonforge.api.DungeonForgeApi")
        } catch (_: ClassNotFoundException) {
            plugin.logger.warning("DungeonForge API was not found; ClassSkills will run without dungeon integration.")
            return
        }
        @Suppress("UNCHECKED_CAST")
        val service = plugin.server.servicesManager.load(apiClass as Class<Any>)
        if (service == null) {
            plugin.logger.warning("DungeonForge API service is unavailable; ClassSkills will run without dungeon integration.")
            return
        }
        val apiVersion = (call(service, "getApiVersion") as? Number)?.toInt() ?: 0
        if (apiVersion < 5) {
            plugin.logger.warning("DungeonForge API v5 or newer is required (found v$apiVersion); dungeon integration is disabled.")
            return
        }
        api = service
        this.apiVersion = apiVersion
        isInDungeon = apiClass.getMethod("isInDungeon", Player::class.java)
        isDungeonMob = apiClass.getMethod("isDungeonMob", Entity::class.java)
        getDifficulty = apiClass.getMethod("getDifficulty", Player::class.java)
        subscribe("DungeonStartEvent", EventPriority.HIGH, ::onDungeonStart)
        subscribe("DungeonPlayerEnterEvent", EventPriority.MONITOR, ::onDungeonPlayerEnter)
        subscribe("DungeonStartEvent", EventPriority.MONITOR, ::onDungeonPartyStarted)
        subscribe("DungeonPlayerLeaveEvent", EventPriority.MONITOR, ::onDungeonPlayerLeave)
        subscribe("DungeonEndEvent", EventPriority.MONITOR, ::onDungeonEnd)
        // The unlock event is pre-commit and cancellable, so it is exclusively a
        // class and difficulty veto hook. Node effects refresh from post-commit events below.
        subscribe("DungeonSkillNodeUnlockEvent", EventPriority.HIGH, ::onSkillNodeUnlock)
        subscribe("DungeonSkillNodesGainedEvent", EventPriority.MONITOR, ::onNodesChanged)
        subscribe("DungeonSkillNodesRevokedEvent", EventPriority.MONITOR, ::onNodesChanged)
        plugin.logger.info("Connected to DungeonForge API v$apiVersion with lifecycle event integration.")
    }

    fun rememberSafeLocation(player: Player) {
        lastSafeLocations[player.uniqueId] = player.location.clone()
    }

    /** Kept for compatibility with the public ClassSkills service; rewards use DungeonEndEvent. */
    fun recordDungeonMobKill(@Suppress("UNUSED_PARAMETER") player: Player, @Suppress("UNUSED_PARAMETER") difficulty: Int) = Unit

    /** A voluntary leave never awards rewards; DungeonEndEvent only pays COMPLETED runs. */
    fun abandonRun(player: Player) {
        plugin.dungeonKits.updateDungeonState(player, false)
    }

    fun isInDungeon(player: Player): Boolean =
        (api?.let { service -> isInDungeon?.invoke(service, player) } as? Boolean) ?: false

    fun isDungeonMob(entity: Entity): Boolean =
        (api?.let { service -> isDungeonMob?.invoke(service, entity) } as? Boolean) ?: false

    fun difficulty(player: Player): Int? {
        val optional = api?.let { service -> getDifficulty?.invoke(service, player) } as? OptionalInt ?: return null
        if (!optional.isPresent) return null
        return optional.asInt.takeIf { it in 1..9 }
    }

    /** Returns DungeonForge's live skill-point balance when its skill API is available. */
    fun skillPoints(player: Player): Int? =
        (api?.let { service -> callWithArguments(service, "getSkillPoints", player) } as? Number)?.toInt()?.coerceAtLeast(0)

    /** Grants DungeonForge-owned points and returns its resulting balance, if available. */
    fun grantSkillPoints(player: Player, amount: Int): Int? {
        if (amount <= 0) return skillPoints(player)
        return (api?.let { service -> callWithArguments(service, "grantSkillPoints", player, amount) } as? Number)
            ?.toInt()?.coerceAtLeast(0)
    }

    /** Removes the entire DungeonForge-owned point balance for administrative resets. */
    fun clearSkillPoints(player: Player): Boolean? {
        val current = skillPoints(player) ?: return null
        if (current == 0) return true
        api?.let { service -> callWithArguments(service, "withdrawSkillPoints", player, current) } ?: return null
        return skillPoints(player) == 0
    }

    /** Selects DungeonForge's active class for the profile currently in use. */
    fun setActiveClass(player: Player, classId: String): Boolean? {
        if (apiVersion < 5) return null
        val result = api?.let { service -> callWithArguments(service, "setActiveClass", player, classId) } ?: return null
        return (call(result, "isSuccess") as? Boolean) ?: false
    }

    fun activeClass(player: Player): String? {
        val result = api?.let { service -> callWithArguments(service, "getActiveClass", player) } ?: return null
        return (result as? Optional<*>)?.orElse(null) as? String
    }

    fun syncActiveClass(player: Player, classId: String): Boolean? {
        val current = activeClass(player) ?: return setActiveClass(player, classId)
        return if (current.equals(classId, ignoreCase = true)) true else setActiveClass(player, classId)
    }

    /**
     * Queries DungeonForge's authoritative unlocked nodes for the active class and
     * maps them to ClassSkills catalogue IDs. A null result means the v5 query was
     * unavailable; an empty set is a valid class with no unlocked nodes.
     */
    fun unlockedClassSkillsNodeIds(player: Player): Set<String>? {
        val classType = plugin.store.get(player.uniqueId).classType ?: return emptySet()
        @Suppress("UNCHECKED_CAST")
        val unlocked = api?.let { service ->
            callWithArguments(service, "getUnlockedSkillNodes", player)
        } as? Map<*,*> ?: return null
        return unlocked.keys.mapNotNull { externalNodeId(classType, it as? String ?: return@mapNotNull null) }.toSet()
    }

    /** Refreshes the local, non-persistent effect projection from DungeonForge. */
    fun refreshNodeEffects(player: Player): Boolean {
        val nodeIds = unlockedClassSkillsNodeIds(player) ?: return false
        plugin.progression.applyNodeEffects(player, nodeIds)
        return true
    }

    /** Sets DungeonForge's live point ledger to the selected class profile's balance. */
    fun syncSkillPoints(player: Player, desired: Int): Int? {
        val current = skillPoints(player) ?: return null
        val target = desired.coerceAtLeast(0)
        when {
            target > current -> grantSkillPoints(player, target - current) ?: return null
            target < current -> api?.let { service ->
                callWithArguments(service, "withdrawSkillPoints", player, current - target)
            } ?: return null
        }
        return skillPoints(player)
    }

    /** Fallback state sync for reloads and unusual DungeonForge recovery paths. */
    fun syncOnlinePlayers() {
        if (api == null) return
        plugin.server.onlinePlayers.forEach { player ->
            if (!isInDungeon(player)) {
                lastSafeLocations[player.uniqueId] = player.location.clone()
                deniedEntries.remove(player.uniqueId)
                plugin.dungeonKits.updateDungeonState(player, false)
                return@forEach
            }
            val difficulty = difficulty(player) ?: return@forEach
            if (!plugin.progression.canEnterDungeonDifficulty(player, difficulty)) {
                denyEnteredPlayer(player, difficulty)
                return@forEach
            }
            deniedEntries.remove(player.uniqueId)
            plugin.dungeonKits.updateDungeonState(player, true)
            plugin.progression.syncDifficultyToLevel(player)
        }
    }

    private fun onDungeonStart(event: Event) {
        val leader = call(event, "getLeader") as? Player ?: return
        val difficulty = dungeonDifficulty(event) ?: return
        if (plugin.progression.canEnterDungeonDifficulty(leader, difficulty)) return
        (event as? Cancellable)?.isCancelled = true
        val allowed = plugin.progression.maximumDungeonDifficultyForLevel(plugin.store.get(leader.uniqueId).level)
        leader.sendMessage("§cDifficulty $difficulty is locked. §7Reach Level ${(difficulty - 1) * 10 + 1} (currently allowed: Difficulty $allowed).")
    }

    /**
     * Reassert every party member's kit after the start commits. Party launches do
     * not reliably expose a usable per-player entry event for every member.
     */
    private fun onDungeonPartyStarted(event: Event) {
        if ((event as? Cancellable)?.isCancelled == true) return
        val difficulty = dungeonDifficulty(event) ?: return
        val dungeon = call(event, "getDungeon") ?: return
        val members = call(dungeon, "partyMembers", "getPartyMembers") as? Collection<*> ?: return
        val memberIds = members.filterIsInstance<UUID>()
        if (memberIds.isEmpty()) return
        plugin.server.scheduler.runTask(plugin, Runnable {
            memberIds.forEach { memberId ->
                val player = Bukkit.getPlayer(memberId)?.takeIf { it.isOnline } ?: return@forEach
                if (!plugin.progression.canEnterDungeonDifficulty(player, difficulty)) {
                    denyEnteredPlayer(player, difficulty)
                    return@forEach
                }
                deniedEntries.remove(player.uniqueId)
                plugin.dungeonKits.updateDungeonState(player, true)
                plugin.progression.syncDifficultyToLevel(player)
            }
        })
    }

    private fun onDungeonPlayerEnter(event: Event) {
        val player = call(event, "getPlayer") as? Player ?: return
        val difficulty = dungeonDifficulty(event) ?: return
        if (!plugin.progression.canEnterDungeonDifficulty(player, difficulty)) {
            denyEnteredPlayer(player, difficulty)
            return
        }
        deniedEntries.remove(player.uniqueId)
        plugin.dungeonKits.updateDungeonState(player, true)
        plugin.progression.syncDifficultyToLevel(player)
    }

    private fun onDungeonPlayerLeave(event: Event) {
        val player = call(event, "getPlayer") as? Player ?: return
        plugin.dungeonKits.updateDungeonState(player, false)
        lastSafeLocations[player.uniqueId] = player.location.clone()
    }

    /** DungeonForge v3 guarantees this fires once per run; only COMPLETED runs pay out. */
    private fun onDungeonEnd(event: Event) {
        if (call(event, "isCompleted") != true) return
        val dungeon = call(event, "getDungeon") ?: return
        val difficulty = (call(dungeon, "difficulty", "getDifficulty") as? Number)?.toInt()?.takeIf { it in 1..9 } ?: return
        val mobKills = (call(dungeon, "mobsKilled", "getMobsKilled") as? Number)?.toInt() ?: 0
        @Suppress("UNCHECKED_CAST")
        val members = call(dungeon, "partyMembers", "getPartyMembers") as? List<UUID> ?: emptyList()
        members.forEach { memberId ->
            Bukkit.getPlayer(memberId)?.takeIf { it.isOnline }?.let { player ->
                plugin.progression.syncDifficultyToLevel(player)
                plugin.progression.awardDungeonCompletion(player, difficulty, mobKills)
            }
        }
    }

    /** Pre-commit veto only; do not read or refresh node effects from this event. */
    private fun onSkillNodeUnlock(event: Event) {
        val player = call(event, "getPlayer") as? Player ?: return
        val eventClassId = call(event, "getClassId") as? String ?: return
        val activeClassId = activeClass(player) ?: return
        if (!activeClassId.equals(eventClassId, ignoreCase = true)) {
            (event as? Cancellable)?.isCancelled = true
            player.sendMessage("§cYou can only upgrade the skill tree for your current class ($activeClassId).")
            return
        }

        val requestedExternalNodeId = call(event, "getNodeId") as? String ?: return
        val classType = ClassType.fromInput(activeClassId) ?: return
        val node = externalNodeId(classType, requestedExternalNodeId)
            ?.let(plugin.catalog.byId::get)
            ?: return
        val unlockedDifficulty = plugin.store.get(player.uniqueId).unlockedDifficulty
        if (unlockedDifficulty < node.tier) {
            (event as? Cancellable)?.isCancelled = true
            player.sendMessage("§cThis node requires Dungeon Difficulty ${node.tier}. §7Your current unlock is Difficulty $unlockedDifficulty.")
            return
        }
        // DungeonForge does not publish a rejected-purchase event. This pre-commit hook is
        // safe for the insufficient-points cue because the balance and authoritative unlocked
        // set still describe the state before this attempted purchase.
        val alreadyUnlocked = unlockedClassSkillsNodeIds(player)?.contains(node.id) == true
        if (!alreadyUnlocked && skillPoints(player)?.let { it < node.cost } == true) {
            plugin.feedback.nodePurchaseDenied(player)
        }
    }

    /** Applies the authoritative post-commit node state for both gains and revocations. */
    private fun onNodesChanged(event: Event) {
        val player = call(event, "getPlayer") as? Player ?: return
        if (event.javaClass.simpleName == "DungeonSkillNodesGainedEvent") {
            when (call(event, "getSource")?.toString()) {
                "PURCHASED" -> plugin.feedback.nodePurchased(player)
                "GRANTED" -> Unit
                else -> plugin.logger.warning("DungeonForge reported an unknown node-gain source; refreshing node effects anyway.")
            }
        }
        if (refreshNodeEffects(player)) plugin.refreshPlayer(player)
    }

    /** Maps the stationary three-branch panel's node IDs to ClassSkills' 40-node catalogue. */
    private fun externalNodeId(classType: ClassType, externalNode: String): String? {
        if (plugin.catalog.byId[externalNode]?.classType == classType) return externalNode
        val match = Regex("([was])(\\d{1,2})").matchEntire(externalNode.lowercase()) ?: return null
        val branch = when (match.groupValues[1]) { "w" -> "weapon"; "a" -> "ability"; else -> "survival" }
        val maximum = when (branch) { "weapon" -> 11; else -> 14 }
        val index = match.groupValues[2].toIntOrNull() ?: return null
        if (branch == "ability" && index == 0) return "${classType.name.lowercase()}_ability_00"
        if (index !in 1..maximum) return null
        return "${classType.name.lowercase()}_${branch}_${index.toString().padStart(2, '0')}"
    }

    private fun denyEnteredPlayer(player: Player, difficulty: Int) {
        plugin.dungeonKits.updateDungeonState(player, false)
        if (!deniedEntries.add(player.uniqueId)) return
        val allowed = plugin.progression.maximumDungeonDifficultyForLevel(plugin.store.get(player.uniqueId).level)
        player.sendMessage("§cDifficulty $difficulty is locked. §7Reach Level ${(difficulty - 1) * 10 + 1} (currently allowed: Difficulty $allowed).")
        if (!player.performCommand("dungeon leave")) {
            lastSafeLocations[player.uniqueId]?.let(player::teleport)
        }
    }

    private fun dungeonDifficulty(event: Event): Int? {
        val dungeon = call(event, "getDungeon") ?: return null
        return (call(dungeon, "difficulty", "getDifficulty") as? Number)?.toInt()?.takeIf { it in 1..9 }
    }

    @Suppress("UNCHECKED_CAST")
    private fun subscribe(simpleName: String, priority: EventPriority, handler: (Event) -> Unit) {
        val eventClass = try {
            Class.forName("nl.riddernix.dungeonforge.api.$simpleName") as Class<out Event>
        } catch (exception: ClassNotFoundException) {
            plugin.logger.severe("DungeonForge API v5 is missing $simpleName; lifecycle integration is incomplete.")
            return
        }
        plugin.server.pluginManager.registerEvent(
            eventClass,
            this,
            priority,
            EventExecutor { _, event -> handler(event) },
            plugin,
            false
        )
    }

    private fun call(target: Any, vararg names: String): Any? = call(target, names.toList(), emptyArray())

    private fun callWithArguments(target: Any, name: String, vararg arguments: Any): Any? = call(target, listOf(name), arguments)

    private fun call(target: Any, names: List<String>, arguments: Array<out Any>): Any? {
        val method = target.javaClass.methods.firstOrNull {
            it.name in names && it.parameterCount == arguments.size &&
                it.parameterTypes.zip(arguments).all { (parameter, argument) ->
                    (if (parameter.isPrimitive) boxed(parameter) else parameter).isAssignableFrom(argument.javaClass)
                }
        } ?: return null
        return runCatching { method.invoke(target, *arguments) }.getOrNull()
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }
}
