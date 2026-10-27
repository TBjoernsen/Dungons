package nl.riddernix.dungeonplugin.classes

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.BlockDisplay
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
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** Two-stage, display-entity class selection: class cards followed by a detail card. */
class HolographicClassSelection(private val plugin: DungeonPlugin) : Listener {

    private val sessions = HashMap<UUID, Session>()
    private val actionKey = NamespacedKey(plugin, "class-hologram-action")
    private val sessionKey = NamespacedKey(plugin, "class-hologram-session")
    private val reachKey = NamespacedKey(plugin, "class-hologram-reach")

    fun open(player: Player) = openCards(player)

    fun close(player: Player) {
        sessions.remove(player.uniqueId)?.let { session ->
            remove(session)
            player.walkSpeed = session.walkSpeed
            player.flySpeed = session.flySpeed
        }
        restoreIfStuck(player)
        removeTagged(player.uniqueId)
        releaseReach(player)
    }

    /** Restores all active viewers before plugin reload/disable can discard their saved speeds. */
    fun shutdown() {
        sessions.values.toList().forEach { session ->
            plugin.server.getPlayer(session.owner)?.let { player ->
                remove(session)
                player.walkSpeed = session.walkSpeed
                player.flySpeed = session.flySpeed
                releaseReach(player)
            }
        }
        sessions.clear()
    }

    private fun openCards(
        player: Player,
        anchoredOrigin: Location? = null,
        anchoredDirection: Vector? = null,
        anchoredYaw: Float? = null,
        startIndex: Int? = null
    ) {
        close(player)
        val origin = anchoredOrigin?.clone() ?: origin(player)
        val direction = anchoredDirection?.clone() ?: player.location.direction.clone()
        val session = lock(player, origin, direction, anchoredYaw ?: player.location.yaw)
        val count = ClassType.entries.size
        // Open on the player's current class so the wheel starts on "you".
        session.index = Math.floorMod(
            startIndex ?: plugin.classes.activeClass(player.uniqueId)?.let { ClassType.entries.indexOf(it) } ?: 0,
            count
        )
        sessions[player.uniqueId] = session

        ClassType.entries.forEachIndexed { i, type ->
            val card = spawnCard(player, session, type, pose(i - session.index, count))
            session.cards += card
            session.entities += card.cube
            session.entities += card.label
        }

        session.entities += text(player, place(origin, direction, 0.0, 3.15), Component.text("Select Your Class", NamedTextColor.YELLOW), 2.2f)

        // Wheel arrows: each rotates the wheel one card.
        val left = place(origin, direction, -ARROW_OFFSET, CARD_Y)
        val right = place(origin, direction, ARROW_OFFSET, CARD_Y)
        session.entities += text(player, left, Component.text("<", NamedTextColor.YELLOW), 5f)
        session.entities += text(player, right, Component.text(">", NamedTextColor.YELLOW), 5f)
        session.entities += hitbox(player, left, "prev", 1.3f, 1.7f)
        session.entities += hitbox(player, right, "next", 1.3f, 1.7f)

        // Raised to just under the front cube and pushed back, so its hitbox
        // sits clear of (and behind) Info and Close rather than over them.
        val pick = place(origin, direction, 0.0, 0.25)
            .add(direction.clone().setY(0.0).normalize().multiply(SELECT_DEPTH))
        session.entities += text(player, pick, Component.text("Select", NamedTextColor.YELLOW), 1.6f)
        session.entities += hitbox(player, pick, "pick", 2.0f, .5f)

        val info = place(origin, direction, -1.3, -0.6)
        session.entities += text(player, info, Component.text("[ INFO ]", NamedTextColor.GRAY))
        session.entities += hitbox(player, info, "info", 1.5f, .6f)
        val close = place(origin, direction, 1.3, -0.6)
        session.entities += text(player, close, Component.text("[ CLOSE ]", NamedTextColor.RED))
        session.entities += hitbox(player, close, "close", 1.5f, .6f)
    }

    /** Where one card sits on the wheel: [slot] 0 is front and centre, then round the circle. */
    private fun pose(slot: Int, count: Int): Pose {
        val theta = Math.PI * 2 * slot / count
        val facing = cos(theta)
        val scale = if (facing < -0.5) HIDDEN_SCALE else (0.6 + 0.4 * (1 + facing) / 2).toFloat()
        return Pose(WHEEL_LATERAL * sin(theta), WHEEL_DEPTH * (1 - facing), scale)
    }

    private fun wheelLocation(session: Session, pose: Pose, y: Double): Location {
        val flat = session.direction.clone().setY(0.0).normalize()
        val loc = place(session.origin, session.direction, pose.lateral, y).add(flat.multiply(pose.depth))
        // Teleports (the rotation animation) must keep the card facing the viewer.
        loc.yaw = session.yaw + 180f
        loc.pitch = 0f
        return loc
    }

    /**
     * One card per class: the resource pack's card model for players who have
     * it, otherwise a plain coloured cube. Both are placeholders for the real
     * card art.
     */
    private fun spawnCard(player: Player, session: Session, type: ClassType, pose: Pose): WheelCard {
        val size = CUBE_SIZE * pose.scale
        val packArt = plugin.resourcePack.useCardModels(player)
        val cube: Display = if (packArt) {
            player.world.spawn(wheelLocation(session, pose, CARD_Y), ItemDisplay::class.java) {
                it.setItemStack(cardItem(type))
                it.billboard = Display.Billboard.FIXED
                it.interpolationDelay = 0
                it.interpolationDuration = ROTATE_TICKS
                it.teleportDuration = ROTATE_TICKS
                it.transformation = scaleTransformation(size)
                private(player, it)
            }
        } else {
            player.world.spawn(wheelLocation(session, pose, CARD_Y), BlockDisplay::class.java) {
                it.block = Bukkit.createBlockData(placeholderBlock(type))
                it.billboard = Display.Billboard.FIXED
                it.interpolationDelay = 0
                it.interpolationDuration = ROTATE_TICKS
                it.teleportDuration = ROTATE_TICKS
                it.transformation = cubeTransformation(size)
                private(player, it)
            }
        }
        val label = player.world.spawn(wheelLocation(session, pose, labelY(size, pose)), TextDisplay::class.java) {
            it.billboard = Display.Billboard.FIXED
            it.setRotation(session.yaw + 180f, 0f)
            it.alignment = TextDisplay.TextAlignment.CENTER
            it.text(Component.text(type.displayName, color(type)))
            it.lineWidth = 170
            it.isSeeThrough = true
            it.interpolationDelay = 0
            it.interpolationDuration = ROTATE_TICKS
            it.teleportDuration = ROTATE_TICKS
            it.transformation = scaleTransformation(pose.scale * LABEL_SCALE)
            private(player, it)
        }
        return WheelCard(type, cube, label)
    }

    /** The pack's `dungeonplugin:class_<id>` item model on a plain paper item. */
    private fun cardItem(type: ClassType) = ItemStack(Material.PAPER).also { stack ->
        stack.editMeta { it.itemModel = NamespacedKey.fromString("dungeonplugin:class_${type.id}") }
    }

    private fun labelY(size: Float, pose: Pose) = CARD_Y + size / 2.0 + 0.25 * pose.scale

    private fun applyPose(session: Session, card: WheelCard, pose: Pose) {
        val size = CUBE_SIZE * pose.scale
        // An item model is already centred on the entity; a block's corner is not.
        card.cube.transformation = if (card.cube is ItemDisplay) scaleTransformation(size) else cubeTransformation(size)
        card.cube.teleport(wheelLocation(session, pose, CARD_Y))
        card.label.transformation = scaleTransformation(pose.scale * LABEL_SCALE)
        card.label.teleport(wheelLocation(session, pose, labelY(size, pose)))
    }

    private fun rotate(player: Player, step: Int) {
        val session = sessions[player.uniqueId] ?: return
        val count = session.cards.size
        if (count == 0) return
        session.index = Math.floorMod(session.index + step, count)
        session.cards.forEachIndexed { i, card -> applyPose(session, card, pose(i - session.index, count)) }
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.3f)
    }

    private fun placeholderBlock(type: ClassType) = when (type) {
        ClassType.WARRIOR -> Material.RED_CONCRETE
        ClassType.ARCHER -> Material.LIME_CONCRETE
        ClassType.PALADIN -> Material.GOLD_BLOCK
        ClassType.MAGE -> Material.PURPLE_CONCRETE
    }

    /** A block display's origin is its corner, so the translation pulls the cube's centre onto the entity. */
    private fun cubeTransformation(size: Float) =
        Transformation(Vector3f(-size / 2f, -size / 2f, -size / 2f), AxisAngle4f(), Vector3f(size, size, size), AxisAngle4f())

    private fun scaleTransformation(scale: Float) =
        Transformation(Vector3f(), AxisAngle4f(), Vector3f(scale, scale, scale), AxisAngle4f())

    private fun openDetail(player: Player, type: ClassType) {
        // Replace the picker at precisely its original anchor. Do not use the
        // player's current look direction or the selected card/button
        // position.
        val previous = sessions[player.uniqueId]
        val direction = previous?.direction?.clone() ?: player.location.direction.clone()
        val origin = previous?.origin?.clone() ?: origin(player)
        val yaw = previous?.yaw ?: player.location.yaw
        close(player)
        val session = lock(player, origin, direction, yaw)
        // Remember which card the wheel was on, so Back returns to it.
        session.index = previous?.index ?: 0
        sessions[player.uniqueId] = session
        // The detail card is wider on its passive side than the four-card
        // picker. Offset its contents left and slightly up so its visual
        // centre replaces the picker centre instead of appearing down and to
        // the right of it.
        fun at(x: Double, y: Double) = place(origin, direction, x - 1.10, y + 0.65)
        // Left panel: title, class icon, and the four core stats.
        session.entities += text(player, at(0.0, 2.8), Component.text("${type.displayName.uppercase()} • CLASS DETAILS", color(type)))
        session.entities += text(player, at(-1.45, 2.15), Component.text("CORE STATS", NamedTextColor.GOLD))
        session.entities += item(player, at(-2.25, .8), type.icon)
        session.entities += text(player, at(-.65, 1.05), Component.text(statText(player, type), NamedTextColor.WHITE))
        // Right panel deliberately gives the passive the room the omitted
        // Wield panel used to occupy.
        session.entities += text(player, at(2.6, 2.15), Component.text("PASSIVE • ${type.passiveName}", color(type)))
        session.entities += text(player, at(2.6, .95), Component.text(passiveDescription(type), NamedTextColor.WHITE))
        // Between the panels and the buttons: what the class can specialise
        // into, so it can be seen before committing to the class.
        session.entities += text(player, at(1.0, -.15), masteryText(type), .75f, lineWidth = 380)
        session.entities += text(player, at(-1.55, -.85), Component.text("[ BACK ]", NamedTextColor.GRAY))
        session.entities += text(player, at(1.05, -.85), Component.text("[ SELECT ${type.displayName.uppercase()} ]", NamedTextColor.GREEN))
        session.entities += text(player, at(3.85, -.85), Component.text("[ CLOSE ]", NamedTextColor.RED))
        session.entities += hitbox(player, at(-1.55, -.85), "back", 1.6f, .65f)
        session.entities += hitbox(player, at(1.05, -.85), "select:${type.name}", 2.5f, .65f)
        session.entities += hitbox(player, at(3.85, -.85), "close", 1.4f, .65f)
    }

    /** Header plus one short line per mastery subclass of [type]; a class with none yet says so. */
    private fun masteryText(type: ClassType): Component {
        val cfg = plugin.classesConfig
        val options = cfg.subclassOptions(type.id)
        var out = Component.text("MASTERY", NamedTextColor.GOLD)
        if (options.isEmpty()) return out.append(Component.text(" • coming soon", NamedTextColor.GRAY))
        out = out.append(Component.text(" • unlocks at level ${cfg.subclassUnlockLevel(type.id)}", NamedTextColor.GRAY))
        for (option in options) {
            out = out.append(Component.newline())
                .append(Component.text(option.name, color(type)))
                .append(Component.text(" – ${option.summary}", NamedTextColor.WHITE))
        }
        return out
    }

    @EventHandler
    fun click(event: PlayerInteractAtEntityEvent) {
        val interaction = event.rightClicked as? Interaction ?: return
        val action = interaction.persistentDataContainer.get(actionKey, PersistentDataType.STRING) ?: return
        event.isCancelled = true
        if (event.player.uniqueId !in sessions) return
        val session = sessions[event.player.uniqueId]
        when {
            action == "prev" -> rotate(event.player, -1)
            action == "next" -> rotate(event.player, 1)
            action == "pick" -> session?.let { select(event.player, ClassType.entries[it.index]) }
            action == "info" -> session?.let { openDetail(event.player, ClassType.entries[it.index]) }
            action == "back" -> openCards(event.player, session?.origin, session?.direction, session?.yaw, session?.index)
            action == "close" -> close(event.player)
            action.startsWith("detail:") -> ClassType.fromInput(action.substringAfter(':'))?.let { openDetail(event.player, it) }
            action.startsWith("select:") -> ClassType.fromInput(action.substringAfter(':'))?.let { select(event.player, it) }
        }
    }

    @EventHandler
    fun quit(event: PlayerQuitEvent) = close(event.player)

    @EventHandler
    fun join(event: PlayerJoinEvent) {
        sessions.values.forEach { it.entities.forEach { entity -> event.player.hideEntity(plugin, entity) } }
    }

    private fun select(player: Player, type: ClassType) {
        when (plugin.classes.selectClass(player, type)) {
            SelectionResult.SUCCESS -> {
                player.sendMessage(Component.text("You are now a ${type.displayName}.", color(type)))
                close(player)
            }
            SelectionResult.ALREADY_SELECTED -> player.sendMessage(Component.text("Already your class.", NamedTextColor.GRAY))
            SelectionResult.NEEDS_SOUL_SHARD -> player.sendMessage(Component.text("You need a Soul Shard to change your locked class.", NamedTextColor.RED))
            SelectionResult.LOCKED -> player.sendMessage(Component.text("That class is locked.", NamedTextColor.RED))
        }
    }

    private fun statText(player: Player, type: ClassType): String {
        val own = plugin.classes.activeClass(player.uniqueId) == type
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
        return player.location.clone().add(forward.multiply(WHEEL_DISTANCE)).add(0.0, 1.0, 0.0)
    }

    private fun place(origin: Location, direction: Vector, x: Double, y: Double): Location {
        val flat = direction.clone().setY(0.0).normalize()
        val right = Vector(-flat.z, 0.0, flat.x)
        return origin.clone().add(right.multiply(x)).add(0.0, y, 0.0)
    }

    private fun item(player: Player, loc: Location, material: Material) =
        player.world.spawn(loc, ItemDisplay::class.java) {
            it.billboard = Display.Billboard.FIXED
            it.setRotation((sessions[player.uniqueId]?.yaw ?: player.location.yaw) + 180f, 0f)
            it.setItemStack(ItemStack(material))
            private(player, it)
        }

    private fun text(player: Player, loc: Location, component: Component, scale: Float = 1f, lineWidth: Int = 170) =
        player.world.spawn(loc, TextDisplay::class.java) {
            it.billboard = Display.Billboard.FIXED
            it.setRotation((sessions[player.uniqueId]?.yaw ?: player.location.yaw) + 180f, 0f)
            it.alignment = TextDisplay.TextAlignment.CENTER
            it.text(component)
            it.lineWidth = lineWidth
            it.isSeeThrough = true
            if (scale != 1f) it.transformation = scaleTransformation(scale)
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
        // A server reload can discard an old session while leaving a zero
        // speed behind.
        restoreIfStuck(player)
        val session = Session(player.uniqueId, player.walkSpeed, player.flySpeed, origin, direction, yaw)
        player.walkSpeed = 0f
        player.flySpeed = 0f
        player.velocity = Vector()
        extendReach(player)
        return session
    }

    /** The wheel sits further out than the default entity reach, so its buttons need a temporary reach bonus (transient: never saved with the player). */
    private fun extendReach(player: Player) {
        val reach = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE) ?: return
        reach.removeModifier(reachKey)
        reach.addTransientModifier(AttributeModifier(reachKey, EXTRA_REACH, AttributeModifier.Operation.ADD_NUMBER))
    }

    private fun releaseReach(player: Player) {
        player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE)?.removeModifier(reachKey)
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

    private fun color(type: ClassType) = when (type) {
        ClassType.WARRIOR -> NamedTextColor.RED
        ClassType.ARCHER -> NamedTextColor.GREEN
        ClassType.PALADIN -> NamedTextColor.GOLD
        ClassType.MAGE -> NamedTextColor.LIGHT_PURPLE
    }

    private fun trim(value: Double) =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)

    private data class Session(
        val owner: UUID,
        val walkSpeed: Float,
        val flySpeed: Float,
        val origin: Location,
        val direction: Vector,
        val yaw: Float,
        val entities: MutableList<Entity> = mutableListOf()
    ) {
        /** Which class is at the front of the wheel (an index into [ClassType.entries]). */
        var index: Int = 0
        val cards: MutableList<WheelCard> = mutableListOf()
    }

    private class WheelCard(val type: ClassType, val cube: Display, val label: TextDisplay)

    /** [lateral] is right of the wheel's centre, [depth] is away from the viewer, [scale] shrinks cards as they turn round the back. */
    private class Pose(val lateral: Double, val depth: Double, val scale: Float)

    private companion object {
        /** How far in front of the player the wheel sits, and the extra entity reach that keeps its buttons clickable from there. */
        const val WHEEL_DISTANCE = 5.5
        const val EXTRA_REACH = 4.0
        const val SELECT_DEPTH = 0.4
        const val CARD_Y = 1.15
        const val CUBE_SIZE = 1.4f
        const val LABEL_SCALE = 1.5f
        const val WHEEL_LATERAL = 2.4
        const val WHEEL_DEPTH = 1.0
        const val ARROW_OFFSET = 4.6
        const val ROTATE_TICKS = 8
        const val HIDDEN_SCALE = 0.0001f
    }
}
