package dev.thorb.classskills.ui

import dev.thorb.classskills.ClassSkillsPlugin
import dev.thorb.classskills.model.ClassType
import dev.thorb.classskills.model.NodeKind
import dev.thorb.classskills.model.PlayerSkillData
import dev.thorb.classskills.model.SkillNode
import dev.thorb.classskills.model.StatType
import dev.thorb.classskills.service.PurchaseResult
import java.util.UUID
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/** A temporary, player-private floating skill tree made entirely from vanilla display entities. */
class HolographicSkillTree(private val plugin: ClassSkillsPlugin) : Listener {
    private val sessions = mutableMapOf<UUID, Session>()
    private val nodeKey = NamespacedKey(plugin, "hologram-skill-node")
    private val sessionKey = NamespacedKey(plugin, "hologram-skill-session")

    fun open(player: Player) {
        close(player)
        val data = plugin.store.get(player.uniqueId)
        val classType = data.classType ?: run {
            player.sendMessage(Component.text("Choose a class before opening its skill tree.", NamedTextColor.RED))
            plugin.classScreen.open(player)
            return
        }
        val origin = panelOrigin(player)
        val session = Session(player.uniqueId, player.world.name, origin)
        val groups = groupedNodes(classType)
        groups.forEach { group ->
            val location = panelLocation(origin, player.location.direction, group.branch, group.tier)
            val item = spawnItem(player, location)
            val text = spawnText(player, location.clone().add(0.0, -0.46, 0.0))
            val interaction = spawnInteraction(player, location)
            interaction.persistentDataContainer.set(nodeKey, PersistentDataType.STRING, group.id)
            session.nodes[group.id] = NodeVisual(group, item, text, interaction)
        }
        Branch.entries.forEach { branch ->
            val firstTier = if (branch == Branch.SIGNATURE) 4 else 1
            val lastTier = if (branch == Branch.SIGNATURE) 8 else 9
            (firstTier until lastTier).forEach { tier ->
                val from = "$branch:$tier"
                val to = "$branch:${tier + 1}"
                val a = panelLocation(origin, player.location.direction, branch, tier)
                val b = panelLocation(origin, player.location.direction, branch, tier + 1)
                val middle = a.clone().add(b).multiply(0.5).add(0.0, 0.08, 0.0)
                session.lines += LineVisual(from, to, spawnText(player, middle))
            }
        }
        sessions[player.uniqueId] = session
        refresh(player, session, data)
        session.cleanup = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val owner = plugin.server.getPlayer(session.owner)
            if (owner == null || !owner.isOnline || owner.isDead || owner.world.name != session.world || owner.location.distanceSquared(session.origin) > 196.0) {
                owner?.let(::close)
                if (owner == null) remove(session)
            }
        }, 20L, 20L)
        player.sendMessage(Component.text("Skill tree opened. Right-click a node to invest. Use /skills close to dismiss it.", NamedTextColor.GRAY))
    }

    /** Removes the player's live session and any tagged/legacy entities left after a plugin reload. */
    fun close(player: Player) {
        sessions.remove(player.uniqueId)?.let(::remove)
        removeTagged(player.uniqueId)
        removeLegacyNear(player)
    }

    /** Admin recovery command: removes every hologram session, including stale entities. */
    fun purge() {
        sessions.values.toList().forEach(::remove)
        sessions.clear()
        plugin.server.worlds.forEach { world ->
            val legacyInteractions = world.entities.filterIsInstance<Interaction>().filter {
                it.persistentDataContainer.has(nodeKey, PersistentDataType.STRING)
            }
            legacyInteractions.forEach(::removeLegacyCluster)
            world.entities.filter { it.persistentDataContainer.has(sessionKey, PersistentDataType.STRING) }.forEach(Entity::remove)
        }
    }

    @EventHandler fun onInteract(event: PlayerInteractAtEntityEvent) {
        val interaction = event.rightClicked as? Interaction ?: return
        val id = interaction.persistentDataContainer.get(nodeKey, PersistentDataType.STRING) ?: return
        event.isCancelled = true
        val session = sessions[event.player.uniqueId] ?: return
        val visual = session.nodes[id] ?: return
        val result = plugin.progression.purchase(event.player, visual.group.next(event.player))
        when (result) {
            PurchaseResult.SUCCESS -> event.player.sendMessage(Component.text("Unlocked ${visual.group.title}.", NamedTextColor.GREEN))
            PurchaseResult.DIFFICULTY_LOCKED -> event.player.sendMessage(Component.text("Unlock Dungeon Difficulty ${visual.group.tier} first.", NamedTextColor.RED))
            PurchaseResult.PREREQUISITE_LOCKED -> event.player.sendMessage(Component.text("Unlock the connected node above first.", NamedTextColor.RED))
            PurchaseResult.INSUFFICIENT_POINTS -> event.player.sendMessage(Component.text("You need more Skill Points.", NamedTextColor.RED))
            PurchaseResult.ALREADY_PURCHASED -> event.player.sendMessage(Component.text("That node is already complete.", NamedTextColor.GRAY))
            else -> event.player.sendMessage(Component.text("That node cannot be unlocked.", NamedTextColor.RED))
        }
        refresh(event.player, session, plugin.store.get(event.player.uniqueId))
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) { close(event.player) }
    @EventHandler fun onDeath(event: PlayerDeathEvent) { close(event.entity) }
    @EventHandler fun onJoin(event: PlayerJoinEvent) {
        sessions.values.forEach { session -> session.entities().forEach { event.player.hideEntity(plugin, it) } }
    }

    private fun refresh(player: Player, session: Session, data: PlayerSkillData) {
        session.nodes.values.forEach { visual ->
            val group = visual.group
            val rank = group.nodes.count { it.id in data.derivedNodeIds }
            val next = group.nodes.firstOrNull { it.id !in data.derivedNodeIds }
            val state = when {
                rank == group.nodes.size -> State.COMPLETE
                next == null || next.tier > data.unlockedDifficulty || !plugin.catalog.hasPrerequisites(data, next) -> State.LOCKED
                else -> State.AVAILABLE
            }
            visual.item.setItemStack(nodeItem(group, state))
            visual.text.text(Component.text("${group.title}\n$rank/${group.nodes.size}  •  ${next?.cost ?: 0} points\n${state.message}", state.color))
            visual.interaction.setResponsive(state != State.COMPLETE)
        }
        session.lines.forEach { line ->
            val from = session.nodes.getValue(line.from).group.nodes.any { it.id in data.derivedNodeIds }
            val to = session.nodes.getValue(line.to).group.nodes.any { it.id in data.derivedNodeIds }
            line.display.text(Component.text("┃", if (from && to) classColor(data.classType!!) else NamedTextColor.DARK_GRAY))
        }
    }

    private fun NodeGroup.next(player: Player): String = nodes.firstOrNull { it.id !in plugin.store.get(player.uniqueId).derivedNodeIds }?.id ?: nodes.last().id

    private fun nodeItem(group: NodeGroup, state: State): ItemStack {
        val material = when (state) {
            State.LOCKED -> Material.BARRIER
            State.AVAILABLE, State.COMPLETE -> when (group.branch) {
                Branch.ATTACK -> Material.IRON_SWORD
                Branch.VITALITY -> Material.GOLDEN_APPLE
                Branch.ARMOR -> Material.IRON_CHESTPLATE
                Branch.SIGNATURE -> group.nodes.first().classType.icon
            }
        }
        return ItemStack(material).apply {
            if (state == State.COMPLETE) {
                addUnsafeEnchantment(Enchantment.UNBREAKING, 1)
                itemMeta = itemMeta.apply { addItemFlags(ItemFlag.HIDE_ENCHANTS) }
            }
        }
    }

    private fun spawnItem(player: Player, location: Location): ItemDisplay = player.world.spawn(location, ItemDisplay::class.java) { display ->
        display.billboard = Display.Billboard.CENTER
        display.viewRange = 0.65f
        privateTo(player, display)
    }
    private fun spawnText(player: Player, location: Location): TextDisplay = player.world.spawn(location, TextDisplay::class.java) { display ->
        display.billboard = Display.Billboard.CENTER
        display.alignment = TextDisplay.TextAlignment.CENTER
        display.viewRange = 0.65f
        display.lineWidth = 180
        display.isSeeThrough = true
        privateTo(player, display)
    }
    private fun spawnInteraction(player: Player, location: Location): Interaction = player.world.spawn(location, Interaction::class.java) { interaction ->
        interaction.interactionWidth = 0.65f
        interaction.interactionHeight = 0.65f
        interaction.isResponsive = true
        privateTo(player, interaction)
    }
    private fun privateTo(owner: Player, entity: Entity) {
        entity.persistentDataContainer.set(sessionKey, PersistentDataType.STRING, owner.uniqueId.toString())
        plugin.server.onlinePlayers.filter { it.uniqueId != owner.uniqueId }.forEach { it.hideEntity(plugin, entity) }
    }

    private fun removeTagged(owner: UUID) {
        plugin.server.worlds.forEach { world ->
            world.entities.filter {
                it.persistentDataContainer.get(sessionKey, PersistentDataType.STRING) == owner.toString()
            }.forEach(Entity::remove)
        }
    }

    /** Version 0.9.0 did not tag item/text displays. Its Interaction entities are tagged, so use them as anchors. */
    private fun removeLegacyNear(player: Player) {
        player.world.entities.filterIsInstance<Interaction>().filter {
            it.persistentDataContainer.has(nodeKey, PersistentDataType.STRING) && it.location.distanceSquared(player.location) < 625.0
        }.forEach(::removeLegacyCluster)
    }

    private fun removeLegacyCluster(interaction: Interaction) {
        val world = interaction.world
        val anchor = interaction.location
        world.entities.filter {
            it is Display && it.location.distanceSquared(anchor) <= 1.0
        }.forEach(Entity::remove)
        interaction.remove()
    }

    private fun panelOrigin(player: Player): Location = player.eyeLocation.clone().add(player.location.direction.normalize().multiply(3.3)).add(0.0, 1.3, 0.0)
    private fun panelLocation(origin: Location, direction: Vector, branch: Branch, tier: Int): Location {
        val flat = direction.clone().setY(0.0).normalize()
        val right = Vector(-flat.z, 0.0, flat.x)
        val x = when (branch) { Branch.ATTACK -> -2.4; Branch.VITALITY -> -0.8; Branch.ARMOR -> 0.8; Branch.SIGNATURE -> 2.4 }
        return origin.clone().add(right.multiply(x)).add(0.0, 3.0 - (tier - 1) * 0.75, 0.0)
    }

    private fun groupedNodes(classType: ClassType): List<NodeGroup> = buildList {
        val stats = listOf(Branch.ATTACK to StatType.ATTACK, Branch.VITALITY to StatType.HEALTH, Branch.ARMOR to StatType.ARMOR)
        stats.forEach { (branch, stat) ->
            (1..9).forEach { tier -> add(NodeGroup(branch, tier, plugin.catalog.forClassAndTier(classType, tier).filter { it.statType == stat }, stat.displayName)) }
        }
        plugin.catalog.nodes.filter { it.classType == classType && it.kind == NodeKind.SIGNATURE_UPGRADE }.forEach { node ->
            add(NodeGroup(Branch.SIGNATURE, node.tier, listOf(node), node.title))
        }
    }

    private fun remove(session: Session) { session.cleanup?.cancel(); session.entities().forEach { if (it.isValid) it.remove() } }
    private fun classColor(type: ClassType): NamedTextColor = when (type) { ClassType.WARRIOR -> NamedTextColor.RED; ClassType.ARCHER -> NamedTextColor.GREEN; ClassType.PALADIN -> NamedTextColor.GOLD; ClassType.MAGE -> NamedTextColor.LIGHT_PURPLE }

    private enum class Branch { ATTACK, VITALITY, ARMOR, SIGNATURE }
    private enum class State(val color: NamedTextColor, val message: String) { LOCKED(NamedTextColor.DARK_GRAY, "Locked"), AVAILABLE(NamedTextColor.YELLOW, "Available"), COMPLETE(NamedTextColor.GREEN, "Complete") }
    private data class NodeGroup(val branch: Branch, val tier: Int, val nodes: List<SkillNode>, val title: String) { val id get() = "$branch:$tier" }
    private data class NodeVisual(val group: NodeGroup, val item: ItemDisplay, val text: TextDisplay, val interaction: Interaction)
    private data class LineVisual(val from: String, val to: String, val display: TextDisplay)
    private data class Session(val owner: UUID, val world: String, val origin: Location, val nodes: MutableMap<String, NodeVisual> = mutableMapOf(), val lines: MutableList<LineVisual> = mutableListOf(), var cleanup: BukkitTask? = null) {
        fun entities(): List<Entity> = nodes.values.flatMap { listOf(it.item, it.text, it.interaction) } + lines.map { it.display }
    }
}
