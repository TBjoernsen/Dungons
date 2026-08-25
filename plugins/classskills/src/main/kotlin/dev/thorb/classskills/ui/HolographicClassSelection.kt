package dev.thorb.classskills.ui

import dev.thorb.classskills.ClassSkillsPlugin
import dev.thorb.classskills.model.ClassType
import dev.thorb.classskills.model.StatType
import dev.thorb.classskills.service.SelectionResult
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Color
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector

/** Two-stage, display-entity class selection: class cards followed by an ornate-style detail card. */
class HolographicClassSelection(private val plugin: ClassSkillsPlugin) : Listener {
    private val sessions = mutableMapOf<UUID, Session>()
    private val actionKey = NamespacedKey(plugin, "class-hologram-action")
    private val sessionKey = NamespacedKey(plugin, "class-hologram-session")

    fun open(player: Player) = openCards(player)
    fun close(player: Player) {
        sessions.remove(player.uniqueId)?.let { session ->
            remove(session)
            player.walkSpeed = session.walkSpeed
            player.flySpeed = session.flySpeed
        }
        restoreIfStuck(player)
        removeTagged(player.uniqueId)
    }

    /** Restores all active viewers before plugin reload/disable can discard their saved speeds. */
    fun shutdown() {
        sessions.values.toList().forEach { session ->
            plugin.server.getPlayer(session.owner)?.let { player ->
                remove(session)
                player.walkSpeed = session.walkSpeed
                player.flySpeed = session.flySpeed
            }
        }
        sessions.clear()
    }

    private fun openCards(
        player: Player,
        anchoredOrigin: Location? = null,
        anchoredDirection: Vector? = null,
        anchoredYaw: Float? = null
    ) {
        close(player)
        val origin = anchoredOrigin?.clone() ?: origin(player)
        val direction = anchoredDirection?.clone() ?: player.location.direction.clone()
        val session = lock(player, origin, direction, anchoredYaw ?: player.location.yaw)
        sessions[player.uniqueId] = session
        ClassType.entries.forEachIndexed { index, type ->
            val card = place(origin, direction, -3.9 + index * 2.6, 0.0)
            session.entities += text(player, card.clone().add(0.0, 2.1, 0.0), Component.text(type.displayName, color(type)))
            session.entities += item(player, card.clone().add(0.0, 1.15, 0.0), type.icon)
            session.entities += text(player, card.clone().add(0.0, 0.35, 0.0), Component.text("[ SELECT ]", NamedTextColor.GREEN))
            // Only the visible Select label is interactive; inspecting a class is intentional.
            session.entities += hitbox(player, card.clone().add(0.0, 0.35, 0.0), "detail:${type.name}", 1.55f, .55f)
        }
        session.entities += text(player, origin.clone().add(0.0, 3.1, 0.0), Component.text("CHOOSE YOUR CLASS", NamedTextColor.GOLD))
        session.entities += text(player, origin.clone().add(0.0, 2.55, 0.0), Component.text("Right-click a class Select button to inspect it", NamedTextColor.GRAY))
        val close = place(origin, direction, 0.0, -0.8)
        session.entities += text(player, close, Component.text("[ CLOSE MENU ]", NamedTextColor.RED))
        session.entities += hitbox(player, close, "close", 2.1f, .65f)
    }

    private fun openDetail(player: Player, type: ClassType) {
        // Replace the card layout at its original world location, regardless of where
        // the player looked while choosing a class.
        val previous = sessions[player.uniqueId]
        val direction = previous?.direction?.clone() ?: player.location.direction.clone()
        // Replace the picker at precisely its original anchor. Do not use the
        // player's current look direction or the selected card/button position.
        val origin = previous?.origin?.clone() ?: origin(player)
        val yaw = previous?.yaw ?: player.location.yaw
        close(player)
        val session = lock(player, origin, direction, yaw)
        sessions[player.uniqueId] = session
        // The detail card is wider on its passive side than the four-card picker.
        // Offset its contents left and slightly up so its visual centre replaces the
        // picker centre instead of appearing down and to the right of it.
        fun at(x: Double, y: Double) = place(origin, direction, x - 1.10, y + 0.65)
        // Left panel: title, class icon, and the four requested core stats.
        session.entities += text(player, at(0.0, 2.8), Component.text("${type.displayName.uppercase()} • CLASS DETAILS", color(type)))
        session.entities += text(player, at(-1.45, 2.15), Component.text("CORE STATS", NamedTextColor.GOLD))
        session.entities += item(player, at(-2.25, .8), type.icon)
        session.entities += text(player, at(-.65, 1.05), Component.text(statText(player, type), NamedTextColor.WHITE))
        // Right panel deliberately gives the passive the room the omitted Wield panel used to occupy.
        session.entities += text(player, at(2.6, 2.15), Component.text("PASSIVE • ${type.passiveName}", color(type)))
        session.entities += text(player, at(2.6, .95), Component.text(passiveDescription(type), NamedTextColor.WHITE))
        session.entities += text(player, at(-1.55, -.85), Component.text("[ BACK ]", NamedTextColor.GRAY))
        session.entities += text(player, at(1.05, -.85), Component.text("[ SELECT ${type.displayName.uppercase()} ]", NamedTextColor.GREEN))
        session.entities += text(player, at(3.85, -.85), Component.text("[ CLOSE ]", NamedTextColor.RED))
        session.entities += hitbox(player, at(-1.55, -.85), "back", 1.6f, .65f)
        session.entities += hitbox(player, at(1.05, -.85), "select:${type.name}", 2.5f, .65f)
        session.entities += hitbox(player, at(3.85, -.85), "close", 1.4f, .65f)
    }

    @EventHandler fun click(event: PlayerInteractAtEntityEvent) {
        val interaction = event.rightClicked as? Interaction ?: return
        val action = interaction.persistentDataContainer.get(actionKey, PersistentDataType.STRING) ?: return
        event.isCancelled = true
        if (event.player.uniqueId !in sessions) return
        when {
            action == "back" -> {
                val session = sessions[event.player.uniqueId]
                openCards(event.player, session?.origin, session?.direction, session?.yaw)
            }
            action == "close" -> close(event.player)
            action.startsWith("detail:") -> ClassType.fromInput(action.substringAfter(':'))?.let { openDetail(event.player, it) }
            action.startsWith("select:") -> ClassType.fromInput(action.substringAfter(':'))?.let { select(event.player, it) }
        }
    }
    @EventHandler fun quit(event: PlayerQuitEvent) = close(event.player)
    @EventHandler fun join(event: PlayerJoinEvent) { sessions.values.forEach { it.entities.forEach { entity -> event.player.hideEntity(plugin, entity) } } }

    private fun select(player: Player, type: ClassType) {
        when (plugin.progression.selectClass(player, type)) {
            SelectionResult.SUCCESS -> { player.sendMessage(Component.text("You are now a ${type.displayName}.", color(type))); close(player) }
            SelectionResult.ALREADY_SELECTED -> player.sendMessage(Component.text("Already your class.", NamedTextColor.GRAY))
            SelectionResult.NEEDS_SOUL_SHARD -> player.sendMessage(Component.text("You need a Soul Shard to change your locked class.", NamedTextColor.RED))
            SelectionResult.LOCKED -> player.sendMessage(Component.text("That class is locked.", NamedTextColor.RED))
        }
    }

    private fun statText(player: Player, type: ClassType): String {
        val data = plugin.store.get(player.uniqueId)
        val own = data.classType == type
        val health = if (own) player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0 else 20.0
        val attack = if (own) player.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 1.0 else 1.0
        val armor = if (own) player.getAttribute(Attribute.ARMOR)?.value ?: 0.0 else 0.0
        val speed = player.getAttribute(Attribute.MOVEMENT_SPEED)?.value ?: 0.1
        return "♥ Health   ${trim(health)}\n⚔ Attack   ${trim(attack)}\n✦ Speed    ${trim(speed)}\n♜ Armor    ${trim(armor)}"
    }
    private fun passiveDescription(type: ClassType) = when (type) {
        ClassType.WARRIOR -> "Rage builds in combat for a damage and speed burst.\nUpgrades unlock at Difficulty 3."
        ClassType.ARCHER -> "Focus rewards consecutive hits with stronger, faster shots.\nUpgrades unlock at Difficulty 3."
        ClassType.PALADIN -> "Taunt charges through damage to draw mobs and gain protection.\nUpgrades unlock at Difficulty 3."
        ClassType.MAGE -> "Arcane Charge restores Mana over time for Arcane Bolt attacks.\nUpgrades unlock at Difficulty 3."
    }

    private fun origin(player: Player): Location {
        val forward = player.location.direction.clone().setY(0.0).normalize()
        return player.location.clone().add(forward.multiply(3.0)).add(0.0, 1.0, 0.0)
    }
    private fun place(origin: Location, direction: Vector, x: Double, y: Double): Location {
        val flat = direction.clone().setY(0.0).normalize(); val right = Vector(-flat.z, 0.0, flat.x)
        return origin.clone().add(right.multiply(x)).add(0.0, y, 0.0)
    }
    private fun item(player: Player, loc: Location, material: Material) = player.world.spawn(loc, ItemDisplay::class.java) {
        it.billboard = Display.Billboard.FIXED
        it.setRotation((sessions[player.uniqueId]?.yaw ?: player.location.yaw) + 180f, 0f)
        it.setItemStack(ItemStack(material))
        private(player, it)
    }
    private fun text(player: Player, loc: Location, component: Component) = player.world.spawn(loc, TextDisplay::class.java) {
        it.billboard = Display.Billboard.FIXED
        it.setRotation((sessions[player.uniqueId]?.yaw ?: player.location.yaw) + 180f, 0f)
        it.alignment = TextDisplay.TextAlignment.CENTER
        it.text(component)
        it.lineWidth = 170
        it.isSeeThrough = true
        private(player, it)
    }
    /** Interaction locations anchor at their lower edge; offset them so they centre on the text label. */
    private fun hitbox(player: Player, loc: Location, action: String, width: Float, height: Float) =
        player.world.spawn(loc.clone().add(0.0, -(height.toDouble() / 2.0), 0.0), Interaction::class.java) {
            it.interactionWidth = width
            it.interactionHeight = height
            it.persistentDataContainer.set(actionKey, PersistentDataType.STRING, action)
            private(player, it)
        }
    private fun private(player: Player, entity: Entity) { entity.persistentDataContainer.set(sessionKey, PersistentDataType.STRING, player.uniqueId.toString()); plugin.server.onlinePlayers.filter { it != player }.forEach { it.hideEntity(plugin, entity) } }
    private fun lock(player: Player, origin: Location, direction: Vector, yaw: Float): Session {
        // A server reload can discard an old session while leaving a zero speed behind.
        restoreIfStuck(player)
        val session = Session(player.uniqueId, player.walkSpeed, player.flySpeed, origin, direction, yaw)
        player.walkSpeed = 0f
        player.flySpeed = 0f
        player.velocity = Vector()
        return session
    }
    private fun restoreIfStuck(player: Player) {
        if (player.walkSpeed == 0f && player.flySpeed == 0f) {
            player.walkSpeed = 0.2f
            player.flySpeed = 0.1f
        }
    }
    private fun remove(session: Session) = session.entities.forEach { if (it.isValid) it.remove() }
    private fun removeTagged(owner: UUID) = plugin.server.worlds.forEach { world -> world.entities.filter { it.persistentDataContainer.get(sessionKey, PersistentDataType.STRING) == owner.toString() }.forEach(Entity::remove) }
    private fun color(type: ClassType) = when (type) { ClassType.WARRIOR -> NamedTextColor.RED; ClassType.ARCHER -> NamedTextColor.GREEN; ClassType.PALADIN -> NamedTextColor.GOLD; ClassType.MAGE -> NamedTextColor.LIGHT_PURPLE }
    private fun trim(value: Double) = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)
    private data class Session(
        val owner: UUID,
        val walkSpeed: Float,
        val flySpeed: Float,
        val origin: Location,
        val direction: Vector,
        val yaw: Float,
        val entities: MutableList<Entity> = mutableListOf()
    )
}
