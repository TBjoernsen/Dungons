package nl.riddernix.dungeonplugin.skills

import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.event.DungeonSkillNodesGainedEvent
import nl.riddernix.dungeonplugin.event.DungeonSkillPointsChangeEvent
import nl.riddernix.dungeonplugin.event.SkillWriteResult
import nl.riddernix.dungeonplugin.event.SkillWriteStatus
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * The authoritative skill progression state: active class, unlocked node
 * levels per class, and the point balance.
 *
 * Everything lives in plain in-memory maps keyed by UUID, so the queries the
 * combat layer calls on every hit - [hasNode] above all - are hash lookups,
 * never rebuilds. The file (`skill-progress.yml`) is only touched when
 * something changes.
 *
 * What a node does is deliberately absent here. To this class a node is a
 * stable id from skills.yml, a level, and what was paid for it; the effect is
 * read from the tree by the class layer.
 *
 * **The invariant every write protects:** a held node's prerequisites are
 * always held too. Granting refuses to break it and revoking cascades to
 * preserve it, because a tree where a node hangs off nothing renders wrong
 * and can never be reasoned about again.
 */
class SkillProgressManager(private val plugin: DungeonPlugin) {

    enum class UnlockResult { UNLOCKED, ALREADY_UNLOCKED, LOCKED, DIFFICULTY_LOCKED, NOT_ENOUGH_POINTS,
        UNKNOWN_NODE, REFUSED }

    private val file = File(plugin.dataFolder, FILE_NAME)
    private val byPlayer = HashMap<UUID, Progress>()
    private val writeDepth = HashMap<UUID, Int>()
    private var warnedAboutGrants = false

    init {
        load()
    }

    // ------------------------------------------------------------------
    //  Queries - all O(1) or a copy of one small map
    // ------------------------------------------------------------------

    /**
     * The one authority on a player's class since the merge. The class layer
     * reads it from here; there is no second copy to sync any more.
     */
    fun activeClass(playerId: UUID): String? = byPlayer[playerId]?.activeClass

    /** The level of one node in one class's tree; 0 when locked. */
    fun nodeLevel(playerId: UUID, classId: String, nodeId: String): Int =
        held(playerId, classId, nodeId)?.level ?: 0

    /** The hot-path check: active class, one node, at least this level. */
    fun hasNode(playerId: UUID, nodeId: String, minimumLevel: Int): Boolean {
        val classId = activeClass(playerId) ?: return false
        return nodeLevel(playerId, classId, nodeId) >= maxOf(1, minimumLevel)
    }

    /** A copy of the active class's unlocks, node id to level. */
    fun unlockedNodes(playerId: UUID): Map<String, Int> =
        activeClass(playerId)?.let { unlockedNodes(playerId, it) } ?: emptyMap()

    /** One specific class's unlocks, for panels showing a class that is not active. */
    fun unlockedNodes(playerId: UUID, classId: String): Map<String, Int> =
        heldNodes(playerId, classId).mapValues { it.value.level }

    /**
     * Available points, derived rather than stored.
     *
     * With the class layer enabled the pool is **entirely** its level-derived
     * budget: `budget - spent`. Points are earned by levelling, so nothing
     * else hands any out - see [grantPoints]. With classes disabled the
     * plugin falls back to what was granted directly, `granted - spent`, so a
     * dungeons-only server still works.
     *
     * Deriving instead of storing is what stops the two sides drifting: a
     * purchase moves `spent` and the balance follows by arithmetic, with no
     * second entry that could disagree.
     */
    fun points(playerId: UUID): Int {
        val progress = byPlayer[playerId]
        val spent = progress?.spent ?: 0
        if (plugin.classes.enabled) {
            return maxOf(0, budget(playerId) - spent)
        }
        return maxOf(0, (progress?.granted ?: 0) - spent)
    }

    /** The class layer's budget for this player, or 0 when classes are disabled. */
    fun budget(playerId: UUID): Int {
        val player = Bukkit.getPlayer(playerId) ?: return 0
        return plugin.classes.budget(player)
    }

    fun spentPoints(playerId: UUID): Int = byPlayer[playerId]?.spent ?: 0

    // ------------------------------------------------------------------
    //  Point balance
    // ------------------------------------------------------------------

    /** @return the previous class, null on a first confirmation */
    fun setActiveClassInternal(player: Player, classId: String): String? {
        val progress = progress(player.uniqueId)
        val previous = progress.activeClass
        progress.activeClass = classId.lowercase(Locale.ROOT)
        save()
        if (progress.activeClass != previous) {
            plugin.events.fireSkillClassChange(player, previous, progress.activeClass!!)
        }
        return previous
    }

    /**
     * Hands out points on the plugin's own ledger.
     *
     * **Inert while the class layer is enabled:** the pool is its
     * level-derived budget, so points are earned by levelling and cannot be
     * minted here. The call returns the unchanged balance and says so once in
     * the log rather than pretending to have worked.
     *
     * @return the new balance
     */
    fun grantPoints(player: Player, amount: Int): Int =
        changePoints(player, maxOf(0, amount), DungeonSkillPointsChangeEvent.Reason.GRANTED)

    /** Takes up to [amount]; the balance never goes negative. @return the new balance */
    fun withdrawPoints(player: Player, amount: Int): Int =
        changePoints(player, -minOf(maxOf(0, amount), points(player.uniqueId)),
            DungeonSkillPointsChangeEvent.Reason.WITHDRAWN)

    private fun changePoints(player: Player, delta: Int, reason: DungeonSkillPointsChangeEvent.Reason): Int {
        val progress = progress(player.uniqueId)
        val previous = points(player.uniqueId)
        if (delta == 0) return previous
        if (plugin.classes.enabled) {
            if (!warnedAboutGrants) {
                warnedAboutGrants = true
                plugin.logger.warning("Something tried to change ${player.name}'s skill points, but " +
                    "the class layer owns the budget: points come from levelling and cannot be granted or " +
                    "taken here. The balance was left alone.")
            }
            return previous
        }
        progress.granted += delta
        save()
        // A balance shown on an open panel has to move with it. Without this
        // a reward granted through the API only appeared after walking away
        // and back, which reads as the reward never arriving.
        refreshPanels(player)
        val now = points(player.uniqueId)
        plugin.events.fireSkillPointsChange(player, previous, now, reason)
        return now
    }

    // ------------------------------------------------------------------
    //  The panel's unlock path
    // ------------------------------------------------------------------

    /**
     * The one path to a paid unlock, used by the panel and by nothing else.
     * The checks run in the order a player would want to hear about them, and
     * the cancellable event fires only for an attempt that would otherwise
     * succeed - a veto listener never has to re-validate prerequisites.
     */
    fun unlock(player: Player, classId: String, nodeId: String): UnlockResult {
        val tree = plugin.skillTrees.tree(classId)
        val node = tree?.nodes?.get(nodeId) ?: return UnlockResult.UNKNOWN_NODE
        val playerId = player.uniqueId
        val key = classId.lowercase(Locale.ROOT)
        if (nodeLevel(playerId, key, nodeId) >= MAX_LEVEL) return UnlockResult.ALREADY_UNLOCKED
        if (!reachable(node, heldNodes(playerId, key).keys)) return UnlockResult.LOCKED
        if (!difficultyMet(player, node)) return UnlockResult.DIFFICULTY_LOCKED
        val progress = progress(playerId)
        val previousPoints = points(playerId)
        if (previousPoints < node.cost) return UnlockResult.NOT_ENOUGH_POINTS
        if (!plugin.events.fireSkillNodeUnlock(player, key, nodeId, 1, node.cost, previousPoints)) {
            return UnlockResult.REFUSED
        }
        progress.spent += node.cost
        progress.unlocked.getOrPut(key) { HashMap() }[nodeId] = Held(1, node.cost)
        save()
        // Nodes before points, the same order the revoke path uses, so a
        // listener handling both sees the tree change and then its price.
        plugin.events.fireSkillNodesGained(player, key, setOf(nodeId), node.cost,
            DungeonSkillNodesGainedEvent.Source.PURCHASED)
        plugin.events.fireSkillPointsChange(player, previousPoints, points(playerId),
            DungeonSkillPointsChangeEvent.Reason.SPENT)
        return UnlockResult.UNLOCKED
    }

    // ------------------------------------------------------------------
    //  The write API
    // ------------------------------------------------------------------

    /**
     * Gives a node without charging for it.
     *
     * Free on purpose: points are the progression's currency. Prerequisites
     * are still required, because handing out a node whose parents are
     * missing is the one thing that corrupts a tree. Nothing paid means
     * nothing refunded if it is revoked later, so a grant/revoke cycle cannot
     * mint points.
     *
     * A `requires-difficulty` gate is *not* enforced here. It is a
     * progression rule rather than a structural one, and a grant is a
     * deliberate act by whatever asked for it - the same reason cost is
     * waived.
     */
    fun grantNode(player: Player, nodeId: String): SkillWriteResult {
        return write(player) { points ->
            val classId = activeClass(player.uniqueId)
                ?: return@write SkillWriteResult.failed(SkillWriteStatus.NO_ACTIVE_CLASS, "", points)
            val tree = plugin.skillTrees.tree(classId)
            val node = tree?.nodes?.get(nodeId)
                ?: return@write SkillWriteResult.failed(SkillWriteStatus.NO_SUCH_NODE, classId, points)
            val held = heldNodes(player.uniqueId, classId)
            if (nodeId in held) {
                return@write SkillWriteResult.failed(SkillWriteStatus.UNCHANGED, classId, points)
            }
            if (!reachable(node, held.keys)) {
                return@write SkillWriteResult.failed(SkillWriteStatus.LOCKED, classId, points)
            }
            if (!plugin.events.fireSkillNodeUnlock(player, classId, nodeId, 1, 0, points)) {
                return@write SkillWriteResult.failed(SkillWriteStatus.REFUSED, classId, points)
            }
            progress(player.uniqueId).unlocked
                .getOrPut(classId) { HashMap() }[nodeId] = Held(1, 0)
            save()
            refreshPanels(player)
            // A grant costs nothing, so no points event carries it. Without
            // this a listener deriving effects from the tree would never hear
            // about the one node that is handed out rather than bought.
            plugin.events.fireSkillNodesGained(player, classId, setOf(nodeId), 0,
                DungeonSkillNodesGainedEvent.Source.GRANTED)
            SkillWriteResult(SkillWriteStatus.SUCCESS, classId, setOf(nodeId), points, points)
        }
    }

    /**
     * Takes a node away, and with it anything that was only reachable
     * through it, refunding whatever was paid for all of them.
     */
    fun revokeNode(player: Player, nodeId: String): SkillWriteResult {
        return write(player) { points ->
            val classId = activeClass(player.uniqueId)
                ?: return@write SkillWriteResult.failed(SkillWriteStatus.NO_ACTIVE_CLASS, "", points)
            val tree = plugin.skillTrees.tree(classId)
            if (tree == null || nodeId !in tree.nodes) {
                return@write SkillWriteResult.failed(SkillWriteStatus.NO_SUCH_NODE, classId, points)
            }
            val held = heldNodes(player.uniqueId, classId)
            if (nodeId !in held) {
                return@write SkillWriteResult.failed(SkillWriteStatus.UNCHANGED, classId, points)
            }
            remove(player, classId, tree, cascade(tree, held, nodeId), points)
        }
    }

    /** Clears one class's tree completely, refunding everything paid into it. */
    fun resetTree(player: Player, classId: String?): SkillWriteResult {
        return write(player) { points ->
            val key = classId?.lowercase(Locale.ROOT)
            if (key == null || plugin.skillTrees.tree(key) == null) {
                return@write SkillWriteResult.failed(SkillWriteStatus.NO_SUCH_CLASS, key ?: "", points)
            }
            val held = heldNodes(player.uniqueId, key)
            if (held.isEmpty()) return@write SkillWriteResult.failed(SkillWriteStatus.UNCHANGED, key, points)
            remove(player, key, plugin.skillTrees.tree(key)!!, HashSet(held.keys), points)
        }
    }

    /**
     * Switches the player's class. A class they have never touched is fine -
     * its tree simply starts empty - and unlocks are kept per class, so
     * switching away and back loses nothing.
     */
    fun setActiveClass(player: Player, classId: String?): SkillWriteResult {
        return write(player) { points ->
            val key = classId?.lowercase(Locale.ROOT)
            if (key == null || plugin.skillTrees.tree(key) == null) {
                return@write SkillWriteResult.failed(SkillWriteStatus.NO_SUCH_CLASS, key ?: "", points)
            }
            if (key == activeClass(player.uniqueId)) {
                return@write SkillWriteResult.failed(SkillWriteStatus.UNCHANGED, key, points)
            }
            setActiveClassInternal(player, key)
            refreshPanels(player)
            SkillWriteResult(SkillWriteStatus.SUCCESS, key, emptySet(), points, points)
        }
    }

    /** Removes a resolved set of nodes, refunds it, fires both events, redraws. */
    private fun remove(player: Player, classId: String, tree: SkillTreeLibrary.SkillClassTree,
                       removing: Set<String>, points: Int): SkillWriteResult {
        val progress = progress(player.uniqueId)
        val held = progress.unlocked[classId] ?: mutableMapOf()
        var refund = 0
        for (nodeId in removing) {
            val entry = held[nodeId]
            if (entry != null) refund += entry.paid
        }
        held.keys.removeAll(removing)
        // Only spent moves: the balance is derived from it, so adding the
        // refund to a stored total as well would hand it over twice.
        progress.spent = maxOf(0, progress.spent - refund)
        save()
        refreshPanels(player)
        plugin.events.fireSkillNodesRevoked(player, classId, removing, refund)
        val now = points(player.uniqueId)
        if (refund > 0) {
            plugin.events.fireSkillPointsChange(player, points, now,
                DungeonSkillPointsChangeEvent.Reason.REFUNDED)
        }
        return SkillWriteResult(SkillWriteStatus.SUCCESS, classId, removing, points, now)
    }

    /**
     * Everything that must go when one node does.
     *
     * A fixpoint rather than a walk down the children: with `any-of` nodes a
     * path can rejoin, so a node with a second route still stands. Repeating
     * until nothing more falls is the only way to get that right.
     */
    private fun cascade(tree: SkillTreeLibrary.SkillClassTree, held: Map<String, Held>, nodeId: String): Set<String> {
        val removing = HashSet<String>()
        removing.add(nodeId)
        var changed = true
        while (changed) {
            changed = false
            for (candidate in held.keys.toList()) {
                if (candidate in removing) continue
                val node = tree.nodes[candidate]
                if (node == null || node.requires.isEmpty()) continue
                val surviving = HashSet(held.keys)
                surviving.removeAll(removing)
                if (!reachable(node, surviving)) {
                    removing.add(candidate)
                    changed = true
                }
            }
        }
        return removing
    }

    /**
     * Whether the player has cleared the difficulty a node asks for.
     *
     * The requirement is tree data; the answer comes from the class layer,
     * which owns what a player has cleared. With classes disabled every gate
     * passes, so a dungeons-only server sees the tree it always saw.
     */
    fun difficultyMet(player: Player, node: SkillTreeLibrary.SkillNode): Boolean =
        node.requiresDifficulty <= 0 ||
            plugin.classes.hasUnlockedDifficulty(player, node.requiresDifficulty)

    /**
     * Runs one write with the re-entrancy guard around it.
     *
     * Every mutation fires events, and a listener may call back in. One or
     * two levels of that is a listener reacting sensibly; past
     * [MAX_WRITE_DEPTH] it is a loop, and the caller is told so rather than
     * the server stalling.
     */
    private fun write(player: Player, body: (Int) -> SkillWriteResult): SkillWriteResult {
        val playerId = player.uniqueId
        val depth = writeDepth.getOrDefault(playerId, 0)
        val points = points(playerId)
        if (depth >= MAX_WRITE_DEPTH) {
            plugin.logger.warning("Skill write for ${player.name} nested " +
                "$depth deep and was refused; a listener is answering its own event.")
            return SkillWriteResult.failed(SkillWriteStatus.REENTRANT,
                activeClass(playerId) ?: "", points)
        }
        writeDepth[playerId] = depth + 1
        try {
            return body(points)
        } finally {
            if (depth == 0) writeDepth.remove(playerId) else writeDepth[playerId] = depth
        }
    }

    /** Redraws this player's own overlays, so a change is on screen at once. */
    private fun refreshPanels(player: Player) {
        if (player.isOnline) {
            plugin.skillPanels.refreshFor(player)
        }
    }

    /** The render-test backdoor: a free unlock, same storage, no cost and no veto. */
    fun unlockWithoutCost(player: Player, classId: String, nodeId: String) {
        progress(player.uniqueId).unlocked
            .getOrPut(classId.lowercase(Locale.ROOT)) { HashMap() }[nodeId] = Held(1, 0)
        save()
    }

    /** Wipes one player's unlocks in every class; points are kept. */
    fun clearUnlocks(playerId: UUID) {
        val progress = byPlayer[playerId] ?: return
        progress.unlocked.clear()
        save()
    }

    // ------------------------------------------------------------------
    //  Storage
    // ------------------------------------------------------------------

    private fun progress(playerId: UUID): Progress = byPlayer.getOrPut(playerId) { Progress() }

    private fun heldNodes(playerId: UUID, classId: String?): Map<String, Held> {
        val progress = byPlayer[playerId]
        if (progress == null || classId == null) return emptyMap()
        return progress.unlocked[classId.lowercase(Locale.ROOT)] ?: emptyMap()
    }

    private fun held(playerId: UUID, classId: String, nodeId: String): Held? =
        heldNodes(playerId, classId)[nodeId]

    private fun load() {
        byPlayer.clear()
        if (!file.isFile) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        for (rawId in yaml.getKeys(false)) {
            val playerId = try {
                UUID.fromString(rawId)
            } catch (exception: IllegalArgumentException) {
                plugin.logger.warning("Ignoring invalid entry '$rawId' in $FILE_NAME.")
                continue
            }
            val progress = Progress()
            progress.activeClass = yaml.getString("$rawId.class")
            progress.spent = maxOf(0, yaml.getInt("$rawId.spent", 0))
            // Files written before points became derived stored the remaining
            // balance; remaining plus spent is what was granted in total.
            progress.granted = if (yaml.contains("$rawId.granted"))
                yaml.getInt("$rawId.granted")
            else yaml.getInt("$rawId.points", 0) + progress.spent
            val classes = yaml.getConfigurationSection("$rawId.unlocked")
            if (classes != null) {
                for (classId in classes.getKeys(false)) {
                    val nodes = classes.getConfigurationSection(classId) ?: continue
                    val levels = HashMap<String, Held>()
                    for (nodeId in nodes.getKeys(false)) {
                        val entry = nodes.getConfigurationSection(nodeId)
                        if (entry == null) {
                            // Written before paid amounts were tracked: it can
                            // only have come from the panel, which always pays.
                            levels[nodeId] = Held(maxOf(1, nodes.getInt(nodeId, 1)),
                                configuredCost(classId, nodeId))
                        } else {
                            levels[nodeId] = Held(maxOf(1, entry.getInt("level", 1)),
                                maxOf(0, entry.getInt("paid", 0)))
                        }
                    }
                    progress.unlocked[classId.lowercase(Locale.ROOT)] = levels
                }
            }
            byPlayer[playerId] = progress
        }
    }

    private fun configuredCost(classId: String, nodeId: String): Int =
        plugin.skillTrees.tree(classId)?.nodes?.get(nodeId)?.cost ?: 0

    fun save() {
        val yaml = YamlConfiguration()
        for ((playerId, progress) in byPlayer) {
            val base = playerId.toString()
            if (progress.activeClass != null) yaml.set("$base.class", progress.activeClass)
            yaml.set("$base.granted", progress.granted)
            yaml.set("$base.spent", progress.spent)
            for ((treeId, nodes) in progress.unlocked) {
                for ((nodeId, held) in nodes) {
                    val path = "$base.unlocked.$treeId.$nodeId"
                    yaml.set("$path.level", held.level)
                    yaml.set("$path.paid", held.paid)
                }
            }
        }
        try {
            yaml.save(file)
        } catch (exception: IOException) {
            plugin.logger.severe("Could not save $FILE_NAME: ${exception.message}")
        }
    }

    /** One held node: its level, and what was actually paid - a gift refunds nothing. */
    private data class Held(val level: Int, val paid: Int)

    private class Progress {
        var activeClass: String? = null

        /** Points handed out directly, used only while classes are disabled. */
        var granted = 0
        var spent = 0
        val unlocked = HashMap<String, HashMap<String, Held>>()
    }

    companion object {
        private const val FILE_NAME = "skill-progress.yml"

        /** Until levelled skills exist, every node caps at one. */
        private const val MAX_LEVEL = 1

        /**
         * How deep writes may nest. One level lets a listener answer a change
         * with a related change; a listener that answers its own event by
         * calling straight back in runs out of room instead of looping
         * forever.
         */
        private const val MAX_WRITE_DEPTH = 4

        private fun reachable(node: SkillTreeLibrary.SkillNode, held: Set<String>): Boolean {
            if (node.requires.isEmpty()) return true
            return if (node.anyOf) node.requires.any { it in held }
            else node.requires.all { it in held }
        }
    }
}
