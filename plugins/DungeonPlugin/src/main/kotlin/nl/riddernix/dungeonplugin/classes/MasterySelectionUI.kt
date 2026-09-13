package nl.riddernix.dungeonplugin.classes

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.util.UUID

/**
 * The one-stage mastery/subclass picker, opened by `/skills mastery` once a
 * player is eligible (see [ClassProgressionService.isMasteryEligible]).
 *
 * Deliberately plainer than [HolographicClassSelection]'s two-stage class
 * picker: one card per [SubclassOption] (name, description, a SELECT
 * button), no separate detail stage - there is no per-subclass kit yet to
 * show off. Same display-entity/session recipe otherwise, so a later polish
 * pass can bring it up to the class picker's fidelity without a rewrite.
 */
class MasterySelectionUI(private val plugin: DungeonPlugin) : Listener {

    private val sessions = HashMap<UUID, Session>()
    private val actionKey = NamespacedKey(plugin, "mastery-hologram-action")
    private val sessionKey = NamespacedKey(plugin, "mastery-hologram-session")

    fun open(player: Player, classType: ClassType, options: List<SubclassOption>) {
        if (options.isEmpty()) return
        close(player)
        val origin = origin(player)
        val direction = player.location.direction.clone()
        val session = lock(player, origin, direction, player.location.yaw)
        sessions[player.uniqueId] = session
        val spacing = 3.6
        val start = -spacing * (options.size - 1) / 2.0
        options.forEachIndexed { index, option ->
            val card = place(origin, direction, start + index * spacing, 0.0)
            session.entities += text(player, card.clone().add(0.0, 1.55, 0.0),
                Component.text(option.name.uppercase(), NamedTextColor.LIGHT_PURPLE))
            session.entities += text(player, card.clone().add(0.0, 1.0, 0.0),
                Component.text(option.description, NamedTextColor.GRAY))
            session.entities += text(player, card.clone().add(0.0, 0.25, 0.0),
                Component.text("[ SELECT ]", NamedTextColor.GREEN))
            session.entities += hitbox(player, card.clone().add(0.0, 0.25, 0.0), "select:${option.id}", 1.55f, .55f)
        }
        session.entities += text(player, origin.clone().add(0.0, 2.25, 0.0),
            Component.text("CHOOSE YOUR MASTERY", NamedTextColor.GOLD))
        session.entities += text(player, origin.clone().add(0.0, 1.85, 0.0),
            Component.text("${classType.displayName} - this cannot be undone for free", NamedTextColor.GRAY))
        val close = place(origin, direction, 0.0, -0.75)
        session.entities += text(player, close, Component.text("[ CLOSE MENU ]", NamedTextColor.RED))
        session.entities += hitbox(player, close, "close", 2.1f, .65f)
    }

    fun close(player: Player) {
        sessions.remove(player.uniqueId)?.let { session ->
            remove(session)
            player.walkSpeed = session.walkSpeed
            player.flySpeed = session.flySpeed
        }
        restoreIfStuck(player)
        removeTagged(player.uniqueId)
    }

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

    @EventHandler
    fun click(event: PlayerInteractAtEntityEvent) {
        val interaction = event.rightClicked as? Interaction ?: return
        val action = interaction.persistentDataContainer.get(actionKey, PersistentDataType.STRING) ?: return
        event.isCancelled = true
        val player = event.player
        if (player.uniqueId !in sessions) return
        when {
            action == "close" -> close(player)
            action.startsWith("select:") -> select(player, action.substringAfter(':'))
        }
    }

    @EventHandler
    fun quit(event: PlayerQuitEvent) = close(event.player)

    @EventHandler
    fun join(event: PlayerJoinEvent) {
        sessions.values.forEach { it.entities.forEach { entity -> event.player.hideEntity(plugin, entity) } }
    }

    private fun select(player: Player, subclassId: String) {
        when (plugin.classes.chooseSubclass(player, subclassId)) {
            SubclassResult.SUCCESS -> {
                val classType = plugin.classes.activeClass(player.uniqueId)
                val name = classType?.let { plugin.classesConfig.subclassOption(it.id, subclassId)?.name } ?: subclassId
                player.sendMessage(Component.text("You are now a $name.", NamedTextColor.LIGHT_PURPLE))
                close(player)
            }
            SubclassResult.ALREADY_CHOSEN -> {
                player.sendMessage(Component.text("You already chose your mastery.", NamedTextColor.GRAY))
                close(player)
            }
            SubclassResult.TOO_LOW_LEVEL -> {
                player.sendMessage(Component.text("You are not a high enough level yet.", NamedTextColor.RED))
                close(player)
            }
            SubclassResult.NO_CLASS, SubclassResult.UNKNOWN_SUBCLASS, SubclassResult.NOT_CHOSEN_YET,
            SubclassResult.NEEDS_SOUL_SHARDS -> close(player)
        }
    }

    private fun origin(player: Player): Location {
        val forward = player.location.direction.clone().setY(0.0).normalize()
        return player.location.clone().add(forward.multiply(3.0)).add(0.0, 1.0, 0.0)
    }

    private fun place(origin: Location, direction: Vector, x: Double, y: Double): Location {
        val flat = direction.clone().setY(0.0).normalize()
        val right = Vector(-flat.z, 0.0, flat.x)
        return origin.clone().add(right.multiply(x)).add(0.0, y, 0.0)
    }

    private fun text(player: Player, loc: Location, component: Component) =
        player.world.spawn(loc, TextDisplay::class.java) {
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

    private fun private(player: Player, entity: Entity) {
        entity.persistentDataContainer.set(sessionKey, PersistentDataType.STRING, player.uniqueId.toString())
        plugin.server.onlinePlayers.filter { it != player }.forEach { it.hideEntity(plugin, entity) }
    }

    private fun lock(player: Player, origin: Location, direction: Vector, yaw: Float): Session {
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

    private fun removeTagged(owner: UUID) = plugin.server.worlds.forEach { world ->
        world.entities.filter {
            it.persistentDataContainer.get(sessionKey, PersistentDataType.STRING) == owner.toString()
        }.forEach(Entity::remove)
    }

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
