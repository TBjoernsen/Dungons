package nl.riddernix.dungeonplugin.panel

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.util.Messages
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.cos

/**
 * Fixed in-world difficulty selectors built from display entities.
 *
 * A panel is furniture, not a summoned menu: placed once by an admin, always
 * standing there, usable by anyone who walks up. The world save never
 * contains its entities - they are spawned non-persistent from panels.yml
 * whenever their chunk loads, which makes duplication impossible and leaves
 * nothing behind after a crash.
 *
 * This is part one: the shared, static furniture, rendered for everyone on
 * difficulty 1. The per-player number row and the clickable regions build on
 * top of [renderNumberRow], which already draws the carousel for an arbitrary
 * selection.
 */
class DifficultyPanelManager(private val plugin: DungeonPlugin) {

    private val storageFile = File(plugin.dataFolder, STORAGE)
    private val miniMessage = MiniMessage.miniMessage()

    /** Panel id to its base location; the location's yaw is the panel's facing. */
    private val panels = LinkedHashMap<String, Location>()

    /** Entities currently spawned per panel, so re-rendering can clean first. */
    private val spawned = HashMap<String, List<UUID>>()

    /** The shared default row per panel, hidden per player once they engage. */
    private val sharedNumbers = HashMap<String, List<UUID>>()

    /** The Enter button's fill strips per panel, for the pressed-flash feedback. */
    private val buttons = HashMap<String, List<UUID>>()

    /**
     * Each player's chosen difficulty. This is the session state: it survives
     * walking away from the panel and is dropped only on logout.
     */
    private val selections = HashMap<UUID, Int>()

    /** Per player, per panel: their personal number-row entities. */
    private val personalRows = HashMap<UUID, HashMap<String, List<UUID>>>()

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    /** Loads saved panels, renders the reachable ones, and sweeps for orphans. */
    fun load() {
        panels.clear()
        val storage = YamlConfiguration.loadConfiguration(storageFile)
        val section = storage.getConfigurationSection("panels")
        if (section != null) {
            for (id in section.getKeys(false)) {
                val world = Bukkit.getWorld(section.getString("$id.world", "")!!)
                if (world == null) {
                    plugin.logger.warning("Ignoring difficulty panel '$id': its world is not loaded.")
                    continue
                }
                panels[id] = Location(world, section.getDouble("$id.x"), section.getDouble("$id.y"),
                    section.getDouble("$id.z"), section.getDouble("$id.yaw").toFloat(), 0.0F)
            }
        }
        renderLoadedPanels()
        sweepOrphans()
    }

    /** Re-renders every reachable panel with fresh styling after a reload. */
    fun reload() {
        renderLoadedPanels()
    }

    /** Removes every spawned entity; panels return on the next enable. */
    fun despawnAll() {
        for (rows in personalRows.values) {
            for (ids in rows.values) {
                removeEntities(ids)
            }
        }
        personalRows.clear()
        for ((id, base) in panels) {
            clearEntities(id, base)
        }
    }

    private fun renderLoadedPanels() {
        for ((id, base) in panels) {
            render(id, base)
        }
    }

    /** Entities whose panel no longer exists, left by an older session. */
    private fun sweepOrphans() {
        var removed = 0
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities.toList()) {
                val id = panelId(entity)
                if (id != null && id !in panels) {
                    entity.remove()
                    removed++
                }
            }
        }
        if (removed > 0) {
            plugin.logger.info("Removed $removed orphaned difficulty panel entit(ies).")
        }
    }

    /** Respawns a panel when its chunk comes back, one tick later to stay clear of the load itself. */
    fun handleChunkLoad(chunk: Chunk) {
        for ((id, base) in panels) {
            if (chunk.world != base.world ||
                base.blockX shr 4 != chunk.x || base.blockZ shr 4 != chunk.z) {
                continue
            }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val current = panels[id]
                if (current != null) {
                    render(id, current)
                }
            })
        }
    }

    // ------------------------------------------------------------------
    //  Placement and removal
    // ------------------------------------------------------------------

    /** Places a panel at this exact spot, facing the way the location faces. */
    fun place(where: Location): String {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val base = where.clone()
        base.pitch = 0.0F
        panels[id] = base
        render(id, base)
        save()
        return id
    }

    /**
     * Moves the closest panel to a new spot, keeping its id.
     *
     * Rebuilt rather than nudged: every part of a panel sits at an offset
     * computed from its base, so recreating it there is both simpler and the
     * only way a new facing lands right.
     *
     * @return the new location, null when nothing was near enough
     */
    fun moveNearest(from: Location, to: Location): Location? {
        val nearest = nearestPanel(from) ?: return null
        clearEntities(nearest, panels[nearest]!!)
        val base = to.clone()
        base.pitch = 0.0F
        panels[nearest] = base
        render(nearest, base)
        save()
        return base
    }

    /** Removes the closest panel within the configured radius. */
    fun removeNearest(from: Location): Boolean {
        val nearest = nearestPanel(from) ?: return false
        val base = panels.remove(nearest)
        clearEntities(nearest, base!!)
        save()
        return true
    }

    private fun nearestPanel(from: Location): String? {
        val radius = maxOf(1.0, plugin.config.getDouble("difficulty-panel.remove-radius", 5.0))
        var nearest: String? = null
        var nearestDistanceSquared = radius * radius
        for ((id, base) in panels) {
            if (base.world != from.world) {
                continue
            }
            val distanceSquared = base.distanceSquared(from)
            if (distanceSquared <= nearestDistanceSquared) {
                nearest = id
                nearestDistanceSquared = distanceSquared
            }
        }
        return nearest
    }

    /** Removes every panel, loading their chunks first, plus any tagged stray. */
    fun removeAll(): RemovalReport {
        val locations = ArrayList<Location>()
        for ((id, base) in panels.toMap()) {
            clearEntities(id, base)
            locations.add(base.clone())
        }
        panels.clear()
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities.toList()) {
                if (panelId(entity) != null) {
                    locations.add(entity.location.clone())
                    entity.remove()
                }
            }
        }
        save()
        return RemovalReport(locations.size, locations.toList())
    }

    fun list(): List<PanelInfo> = panels.map { (id, base) -> PanelInfo(id, base.clone()) }

    /** Whether an entity is part of any difficulty panel. */
    fun isPanelEntity(entity: Entity): Boolean = panelId(entity) != null

    /** Points a player at the nearest panel, replacing the retired chest menu. */
    fun sendLocator(player: Player) {
        var nearest: Location? = null
        var nearestDistanceSquared = Double.MAX_VALUE
        for (base in panels.values) {
            if (base.world != player.world) {
                continue
            }
            val distanceSquared = base.distanceSquared(player.location)
            if (distanceSquared < nearestDistanceSquared) {
                nearest = base
                nearestDistanceSquared = distanceSquared
            }
        }
        if (nearest == null && panels.isNotEmpty()) {
            nearest = panels.values.first()
        }
        if (nearest == null) {
            plugin.messages.send(player, "menu-replaced-none")
            return
        }
        plugin.messages.send(player, "menu-replaced",
            Messages.ph("world", nearest.world!!.name),
            Messages.ph("x", nearest.blockX),
            Messages.ph("y", nearest.blockY),
            Messages.ph("z", nearest.blockZ))
    }

    // ------------------------------------------------------------------
    //  Interaction
    // ------------------------------------------------------------------

    /**
     * One click from one player. All state involved is keyed by that player's
     * UUID, so two people clicking in the same tick cannot interfere: each
     * click reads and writes only its own entry.
     */
    fun handleClick(player: Player, clicked: Entity) {
        val panelId = panelId(clicked)
        val role = clicked.persistentDataContainer.get(plugin.panelRoleKey, PersistentDataType.STRING)
        if (panelId == null || role == null || panelId !in panels) {
            return
        }
        when (role) {
            "hit-arrow-left" -> shift(player, panelId, -1)
            "hit-arrow-right" -> shift(player, panelId, 1)
            "hit-button" -> enter(player, panelId)
            else -> {
                // Text displays have no hitbox; nothing else is clickable.
            }
        }
    }

    private fun shift(player: Player, panelId: String, direction: Int) {
        val current = selections.getOrDefault(player.uniqueId, 1)
        // The row stops at 1 and 9. Wrapping would make 9-to-1 a one-click
        // accident, and the carousel fade already reads as a real edge.
        val next = (current + direction).coerceIn(1, 9)
        if (next == current) {
            playSound(player, "deny", 0.6F)
            return
        }
        selections[player.uniqueId] = next
        playSound(player, "click", 1.0F + next * 0.05F)
        val base = panels[panelId] ?: return
        if (ensurePersonalRow(player, panelId, base, current)) {
            // A freshly spawned row already stands at the old selection, so
            // this slide is visible for the very first click too.
            animateRow(player, panelId, base, next)
        }
    }

    private fun enter(player: Player, panelId: String) {
        playSound(player, "enter", 1.2F)
        // The flash is on the shared button, so everyone at the panel sees it
        // being pressed - it is a physical button on a physical installation.
        flashButton(panelId)
        plugin.command.startFromPanel(player, selections.getOrDefault(player.uniqueId, 1))
    }

    /** Hover cannot be detected server-side; being used is shown instead. */
    private fun flashButton(panelId: String) {
        val strips = buttons.getOrDefault(panelId, emptyList())
        if (strips.isEmpty()) {
            return
        }
        val config = plugin.config
        // Block displays hold a block, not a colour, so a press swaps the
        // fill bars for a brighter block and swaps them back.
        val normal = blockMaterial(config.getString("difficulty-panel.button.plate.fill-block"),
            Material.BLACK_CONCRETE)
        val pressed = blockMaterial(config.getString("difficulty-panel.button.plate.flash-block"),
            Material.BROWN_CONCRETE)
        val ticks = config.getInt("difficulty-panel.button.flash-ticks", 3).coerceIn(1, 20)
        setBlocks(strips, pressed)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { setBlocks(strips, normal) }, ticks.toLong())
    }

    /**
     * The per-viewer half of the panel. The furniture is one shared set of
     * entities; only the number row exists once per engaged player, spawned
     * invisible-by-default and shown exclusively to its owner, while the
     * shared default row is hidden for that one player. Cost: nine text
     * displays per engaged player standing near the panel.
     */
    private fun ensurePersonalRow(player: Player, panelId: String, base: Location, selection: Int): Boolean {
        val rows = personalRows.getOrPut(player.uniqueId) { HashMap() }
        val existing = rows[panelId]
        if (!existing.isNullOrEmpty() && Bukkit.getEntity(existing.first()) != null) {
            return true
        }
        val world = base.world
        if (world == null || world != player.world ||
            !world.isChunkLoaded(base.blockX shr 4, base.blockZ shr 4)) {
            return false
        }
        val config = plugin.config
        val yaw = base.yaw + (if (config.getBoolean("difficulty-panel.flip-facing", false)) 180.0F else 0.0F)
        val radians = Math.toRadians(yaw.toDouble())
        val facing = Vector(-sin(radians), 0.0, cos(radians))
        val rightward = Vector(facing.z, 0.0, -facing.x)
        val placement = Placement(world, base, yaw, facing, rightward,
            config.getInt("difficulty-panel.brightness", 15).coerceIn(0, 15))
        val slideTicks = config.getInt("difficulty-panel.slide-ticks", 4).coerceIn(0, 40)
        val format = config.getString("difficulty-panel.numbers.format", "<color:#f2e39b><bold><n>")!!

        val ids = ArrayList<UUID>()
        for (number in 1..9) {
            val pose = numberPose(number, selection)
            val at = numberLocation(placement, pose)
            val display = world.spawn(at, TextDisplay::class.java) { text ->
                text.text(miniMessage.deserialize(format, Messages.ph("n", pose.number.toString())))
                text.billboard = Display.Billboard.FIXED
                text.transformation = Transformation(Vector3f(), Quaternionf(),
                    Vector3f(pose.scale, pose.scale, pose.scale), Quaternionf())
                text.textOpacity = pose.opacity
                text.isShadowed = false
                text.isSeeThrough = false
                text.lineWidth = 400
                text.backgroundColor = Color.fromARGB(0)
                text.brightness = Display.Brightness(placement.brightness, placement.brightness)
                text.teleportDuration = slideTicks
                text.viewRange = config.getDouble("difficulty-panel.view-range", 2.0).toFloat()
                text.isPersistent = false
                text.isInvulnerable = true
                // Invisible to the world; only the owner is shown this row.
                text.isVisibleByDefault = false
                text.persistentDataContainer.set(plugin.panelIdKey, PersistentDataType.STRING, panelId)
                text.persistentDataContainer.set(plugin.panelRoleKey, PersistentDataType.STRING, "personal-number")
            }
            player.showEntity(plugin, display)
            ids.add(display.uniqueId)
        }
        rows[panelId] = ids
        setSharedRowHidden(player, panelId, true)
        return true
    }

    /** Slides the player's row: teleport interpolation moves, scale interpolates. */
    private fun animateRow(player: Player, panelId: String, base: Location, selection: Int) {
        val ids = personalRows[player.uniqueId]?.get(panelId) ?: return
        val config = plugin.config
        val yaw = base.yaw + (if (config.getBoolean("difficulty-panel.flip-facing", false)) 180.0F else 0.0F)
        val radians = Math.toRadians(yaw.toDouble())
        val facing = Vector(-sin(radians), 0.0, cos(radians))
        val rightward = Vector(facing.z, 0.0, -facing.x)
        val placement = Placement(base.world!!, base, yaw, facing, rightward, 15)
        val slideTicks = config.getInt("difficulty-panel.slide-ticks", 4).coerceIn(0, 40)
        for (index in 0 until minOf(ids.size, 9)) {
            val display = Bukkit.getEntity(ids[index]) as? TextDisplay ?: continue
            val pose = numberPose(index + 1, selection)
            display.teleport(numberLocation(placement, pose))
            display.interpolationDelay = 0
            display.interpolationDuration = slideTicks
            display.transformation = Transformation(Vector3f(), Quaternionf(),
                Vector3f(pose.scale, pose.scale, pose.scale), Quaternionf())
            display.textOpacity = pose.opacity
        }
    }

    /** Where number [number] sits and how it looks for a given selection. */
    private fun numberPose(number: Int, selection: Int): NumberPose {
        val config = plugin.config
        val visible = config.getInt("difficulty-panel.numbers.visible-each-side", 2).coerceIn(1, 4)
        val spacing = config.getDouble("difficulty-panel.numbers.spacing", 0.55)
        val centreExtra = config.getDouble("difficulty-panel.numbers.centre-extra", 0.18)
        val scales = withFallback(config.getDoubleList("difficulty-panel.numbers.scales"),
            listOf(2.8, 1.6, 1.0))
        val opacities = withFallback(config.getIntegerList("difficulty-panel.numbers.opacities"),
            listOf(255, 165, 85))
        val step = number - selection
        val magnitude = abs(step)
        val x = step * spacing + sign(step.toDouble()) * centreExtra
        if (magnitude > visible) {
            // Off the carousel's edge: shrunk away rather than removed, so it
            // can grow back in with the same interpolated slide.
            return NumberPose(number, x, 0.05F, MINIMUM_OPACITY)
        }
        val scale = scales[minOf(magnitude, scales.size - 1)].toFloat()
        val opacity = opacities[minOf(magnitude, opacities.size - 1)].coerceIn(MINIMUM_OPACITY.toInt(), 255).toByte()
        return NumberPose(number, x, scale, opacity)
    }

    private fun numberLocation(placement: Placement, pose: NumberPose): Location {
        val at = placement.base.clone()
            .add(placement.rightward.clone().multiply(pose.x))
            .add(0.0, plugin.config.getDouble("difficulty-panel.numbers.height", 1.72), 0.0)
            .add(placement.facing.clone().multiply(0.05))
        at.yaw = placement.yaw
        at.pitch = 0.0F
        return at
    }

    /** Hides or restores the shared default row for one player. */
    private fun setSharedRowHidden(player: Player, panelId: String, hidden: Boolean) {
        for (id in sharedNumbers.getOrDefault(panelId, emptyList())) {
            val entity = Bukkit.getEntity(id) ?: continue
            if (hidden) {
                player.hideEntity(plugin, entity)
            } else {
                player.showEntity(plugin, entity)
            }
        }
    }

    /**
     * The proximity sweep, run a few times per second. It gives engaged
     * players their row back when they return to a panel, and takes rows away
     * from players who wandered off, changed world, or logged out.
     */
    fun tick() {
        val radius = maxOf(4.0, plugin.config.getDouble("difficulty-panel.activation-radius", 24.0))
        val radiusSquared = radius * radius
        for (player in Bukkit.getOnlinePlayers()) {
            val engaged = player.uniqueId in selections
            val rows = personalRows[player.uniqueId]
            for ((panelKey, base) in panels) {
                val inRange = base.world == player.world &&
                    base.distanceSquared(player.location) <= radiusSquared
                val hasRow = rows != null && panelKey in rows
                if (engaged && inRange) {
                    ensurePersonalRow(player, panelKey, base,
                        selections.getOrDefault(player.uniqueId, 1))
                } else if (hasRow) {
                    removePersonalRow(player.uniqueId, panelKey)
                }
            }
        }
        // Rows whose owner is gone would otherwise stand invisible forever.
        personalRows.entries.removeIf { entry ->
            if (Bukkit.getPlayer(entry.key) != null) {
                return@removeIf false
            }
            for (ids in entry.value.values) {
                removeEntities(ids)
            }
            true
        }
    }

    /** Logout: the session is over, so both the row and the selection go. */
    fun handleQuit(player: Player) {
        clearPersonalRows(player)
        selections.remove(player.uniqueId)
    }

    /** Death or a world change: the row goes, the session selection stays. */
    fun clearPersonalRows(player: Player) {
        val rows = personalRows.remove(player.uniqueId) ?: return
        for ((panelId, ids) in rows) {
            removeEntities(ids)
            if (player.isOnline) {
                setSharedRowHidden(player, panelId, false)
            }
        }
    }

    private fun removePersonalRow(playerId: UUID, panelId: String) {
        val rows = personalRows[playerId] ?: return
        val ids = rows.remove(panelId)
        if (ids != null) {
            removeEntities(ids)
        }
        val player = Bukkit.getPlayer(playerId)
        if (player != null) {
            setSharedRowHidden(player, panelId, false)
        }
    }

    private fun playSound(player: Player, key: String, pitch: Float) {
        val raw = plugin.config.getString("difficulty-panel.sounds.$key", "UI_BUTTON_CLICK")
        val sound = if (raw.isNullOrBlank()) null
            else Registry.SOUNDS.get(NamespacedKey.minecraft(raw.lowercase(Locale.ROOT).replace('_', '.')))
        if (sound != null) {
            // Only the clicking player hears it; the panel is used by several
            // people at once and their clicks are not each other's business.
            player.playSound(player.location, sound, 0.7F, pitch)
        }
    }

    // ------------------------------------------------------------------
    //  Rendering
    // ------------------------------------------------------------------

    /**
     * Rebuilds a panel's entities from scratch. Always cleans first, so
     * calling it twice can never leave two panels standing on top of each
     * other.
     */
    private fun render(id: String, base: Location) {
        val world = base.world
        if (world == null || !world.isChunkLoaded(base.blockX shr 4, base.blockZ shr 4)) {
            return
        }
        clearEntities(id, base)
        val config = plugin.config
        // The panel can be turned around in place when a placement reads
        // mirrored, without editing panels.yml.
        val yaw = base.yaw + (if (config.getBoolean("difficulty-panel.flip-facing", false)) 180.0F else 0.0F)
        val radians = Math.toRadians(yaw.toDouble())
        val facing = Vector(-sin(radians), 0.0, cos(radians))
        // The viewer stands on the facing side looking back, so their right
        // hand points along this vector; numbers and ">" grow that way.
        val rightward = Vector(facing.z, 0.0, -facing.x)
        val placement = Placement(world, base, yaw, facing, rightward,
            config.getInt("difficulty-panel.brightness", 15).coerceIn(0, 15))
        val ids = ArrayList<UUID>()

        val headingScale = config.getDouble("difficulty-panel.heading.scale", 1.5).toFloat()
        ids.add(spawnText(placement, id, "heading", 0.0,
            config.getDouble("difficulty-panel.heading.height", 2.7), 0.03,
            miniMessage.deserialize(config.getString("difficulty-panel.heading.text",
                "<gradient:#c9a227:#f2e39b><bold>Select Difficulty")!!),
            headingScale, headingScale, 255.toByte(), null))

        // The shared default row: what a player sees before their first
        // click. Once they engage, this row is hidden for them personally and
        // their own carousel takes its place.
        val numberIds = renderNumberRow(placement, id, 1)
        ids.addAll(numberIds)
        sharedNumbers[id] = numberIds.toList()
        // Re-rendering removed every tagged entity, including personal rows;
        // the proximity tick rebuilds those for engaged players still nearby.
        for (rows in personalRows.values) {
            rows.remove(id)
        }

        val arrowScale = config.getDouble("difficulty-panel.arrows.scale", 2.4).toFloat()
        val arrowOffset = config.getDouble("difficulty-panel.arrows.offset", 2.2)
        val arrowHeight = config.getDouble("difficulty-panel.arrows.height", 1.75)
        ids.add(spawnText(placement, id, "arrow-left", -arrowOffset, arrowHeight, 0.03,
            miniMessage.deserialize(config.getString("difficulty-panel.arrows.left", "<color:#c9a227><bold><")!!),
            arrowScale, arrowScale, 255.toByte(), null))
        ids.add(spawnText(placement, id, "arrow-right", arrowOffset, arrowHeight, 0.03,
            miniMessage.deserialize(config.getString("difficulty-panel.arrows.right", "<color:#c9a227><bold>>")!!),
            arrowScale, arrowScale, 255.toByte(), null))

        // The button: the gradient lives in the label, exactly like the chat
        // prefix, over a dark fill inside a gold border.
        val buttonScale = config.getDouble("difficulty-panel.button.scale", 1.4).toFloat()
        val buttonHeight = config.getDouble("difficulty-panel.button.height", 1.0)
        val label = miniMessage.deserialize(config.getString("difficulty-panel.button.text",
            "<gradient:#c9a227:#f2e39b><bold>Enter Dungeon")!!)
        buttons[id] = spawnButtonPlate(placement, id, ids, buttonHeight, buttonScale, label)
        // A text display draws its line upwards from its own position while
        // the plate is centred on it, so the label has to come down by half a
        // line to sit in the middle. Sideways it moves half a pixel right,
        // cancelling the trailing gap that a centred line includes.
        val labelUnit = buttonScale /
            maxOf(1.0, config.getDouble("difficulty-panel.button.plate.pixels-per-block", 40.0))
        val labelX = config.getDouble("difficulty-panel.button.plate.label-x-pixels", 0.5) * labelUnit
        val labelY = buttonHeight + config.getDouble("difficulty-panel.button.plate.y-offset", 0.0) -
            config.getDouble("difficulty-panel.button.plate.label-y-pixels", 4.5) * labelUnit
        // Derived, never a fixed number: the label has to clear the front
        // face of the fill bar, which is a solid block that writes depth.
        // Sitting inside it, the label was hidden up close and only broke
        // through at range, where depth precision falls apart.
        val labelZ = config.getDouble("difficulty-panel.button.plate.fill-z", 0.031) +
            config.getDouble("difficulty-panel.button.plate.depth", 0.02) / 2.0 +
            config.getDouble("difficulty-panel.button.plate.label-clearance", 0.015)
        ids.add(spawnText(placement, id, "button", labelX, labelY, labelZ, label,
            buttonScale, buttonScale, 255.toByte(), null))

        // Clickable regions, deliberately larger than the text they cover: a
        // crosshair is less precise than a mouse pointer.
        val arrowHitWidth = config.getDouble("difficulty-panel.hitboxes.arrow-width", 1.3)
        val arrowHitHeight = config.getDouble("difficulty-panel.hitboxes.arrow-height", 1.3)
        ids.add(spawnHitbox(placement, id, "hit-arrow-left", -arrowOffset, arrowHeight, arrowHitWidth, arrowHitHeight))
        ids.add(spawnHitbox(placement, id, "hit-arrow-right", arrowOffset, arrowHeight, arrowHitWidth, arrowHitHeight))
        ids.add(spawnHitbox(placement, id, "hit-button", 0.0, buttonHeight,
            config.getDouble("difficulty-panel.hitboxes.button-width", 2.6),
            config.getDouble("difficulty-panel.hitboxes.button-height", 1.0)))

        spawned[id] = ids
    }

    /** The carousel: the selection dead centre, neighbours shrinking and fading. */
    private fun renderNumberRow(placement: Placement, id: String, selection: Int): List<UUID> {
        val config = plugin.config
        val visible = config.getInt("difficulty-panel.numbers.visible-each-side", 2).coerceIn(1, 4)
        val spacing = config.getDouble("difficulty-panel.numbers.spacing", 0.55)
        val centreExtra = config.getDouble("difficulty-panel.numbers.centre-extra", 0.18)
        val height = config.getDouble("difficulty-panel.numbers.height", 1.72)
        val format = config.getString("difficulty-panel.numbers.format", "<color:#f2e39b><bold><n>")!!
        val scales = withFallback(config.getDoubleList("difficulty-panel.numbers.scales"),
            listOf(2.8, 1.6, 1.0))
        val opacities = withFallback(config.getIntegerList("difficulty-panel.numbers.opacities"),
            listOf(255, 165, 85))

        val ids = ArrayList<UUID>()
        for (step in -visible..visible) {
            val number = selection + step
            // The row stops at the real ends: nothing is drawn beyond 1 and
            // 9, which is also what makes "stops, does not wrap" visible.
            if (number < 1 || number > 9) {
                continue
            }
            val magnitude = abs(step)
            val scale = scales[minOf(magnitude, scales.size - 1)].toFloat()
            val opacity = opacities[minOf(magnitude, opacities.size - 1)].coerceIn(MINIMUM_OPACITY.toInt(), 255).toByte()
            val x = step * spacing + sign(step.toDouble()) * centreExtra
            ids.add(spawnText(placement, id, "number-$number", x, height, 0.03,
                miniMessage.deserialize(format, Messages.ph("n", number.toString())),
                scale, scale, opacity, null))
        }
        return ids
    }

    /**
     * The plate behind the Enter label: a stepped-corner shape in block
     * displays, a gold border layer behind a dark fill layer.
     *
     * Block displays carry no text, which is the point. The previous plates
     * were text displays rendering the label dyed to their own background
     * colour as a width reference, and that was only invisible by
     * coincidence: at range the enlarged border plate's gold copy of the
     * label showed through and doubled the text. Nothing here can do that.
     *
     * Sizes are computed from the label's measured pixel width, so the shape
     * tracks whatever the label is set to in config, and the border is an
     * exact even ring around the fill by construction.
     *
     * @return the fill bars, which the pressed-flash swaps to another block
     */
    private fun spawnButtonPlate(placement: Placement, id: String, ids: MutableList<UUID>,
                                 buttonHeight: Double, labelScale: Float, label: Component): List<UUID> {
        val config = plugin.config
        val path = "difficulty-panel.button.plate."
        val fillBlock = blockMaterial(config.getString(path + "fill-block"), Material.BLACK_CONCRETE)
        val borderBlock = blockMaterial(config.getString(path + "border-block"), Material.YELLOW_CONCRETE)
        val plain = PlainTextComponentSerializer.plainText().serialize(label)

        // One font pixel, in blocks, at the label's own scale. Everything is
        // measured in these so the shape stays proportional to the text.
        val unit = labelScale / maxOf(1.0, config.getDouble(path + "pixels-per-block", 40.0))
        val labelWidth = labelPixelWidth(plain, true) * unit
        val labelHeight = config.getDouble(path + "line-height-pixels", 11.0) * unit
        val padding = config.getDouble(path + "padding-pixels", 4.0) * unit
        val thickness = config.getDouble(path + "border-pixels", 2.0) * unit
        val step = config.getDouble(path + "corner-step-pixels", 2.0) * unit
        val depth = config.getDouble(path + "depth", 0.02)
        val y = buttonHeight + config.getDouble(path + "y-offset", 0.0)

        val fillWidth = labelWidth + 2.0 * padding
        val fillHeight = labelHeight + 2.0 * padding
        ids.addAll(steppedPlate(placement, id, "button-border", y,
            config.getDouble(path + "border-z", 0.010), depth,
            fillWidth + 2.0 * thickness, fillHeight + 2.0 * thickness, step, borderBlock))
        val fillBars = steppedPlate(placement, id, "button-fill", y,
            config.getDouble(path + "fill-z", 0.031), depth, fillWidth, fillHeight, step, fillBlock)
        ids.addAll(fillBars)
        return fillBars
    }

    /**
     * Three overlapping bars forming a rectangle whose corners are stepped in
     * twice: full width but shortest, then one step in and one step taller,
     * then narrowest and full height.
     */
    private fun steppedPlate(placement: Placement, id: String, role: String, y: Double, z: Double,
                             depth: Double, width: Double, height: Double, step: Double, material: Material): List<UUID> {
        val bars = arrayOf(
            doubleArrayOf(width, height - 4.0 * step),
            doubleArrayOf(width - 2.0 * step, height - 2.0 * step),
            doubleArrayOf(width - 4.0 * step, height)
        )
        val ids = ArrayList<UUID>()
        for (index in bars.indices) {
            ids.add(spawnBar(placement, id, "$role-$index", y, z, depth,
                maxOf(0.01, bars[index][0]), maxOf(0.01, bars[index][1]), material))
        }
        return ids
    }

    /** One bar of the plate, centred on the panel's vertical middle line. */
    private fun spawnBar(placement: Placement, id: String, role: String, y: Double, z: Double, depth: Double,
                         width: Double, height: Double, material: Material): UUID {
        val at = placement.base.clone()
            .add(0.0, y, 0.0)
            .add(placement.facing.clone().multiply(z))
        at.yaw = placement.yaw
        at.pitch = 0.0F
        val viewRange = plugin.config.getDouble("difficulty-panel.view-range", 2.0).toFloat()
        val display = placement.world.spawn(at, BlockDisplay::class.java) { block ->
            block.block = material.createBlockData()
            // A block display fills the unit cube from its own corner, so the
            // translation pulls it back by half its size to centre it. The
            // entity's yaw already puts local x along the panel's width.
            block.transformation = Transformation(
                Vector3f((-width / 2.0).toFloat(), (-height / 2.0).toFloat(), (-depth / 2.0).toFloat()),
                Quaternionf(),
                Vector3f(width.toFloat(), height.toFloat(), depth.toFloat()),
                Quaternionf())
            block.brightness = Display.Brightness(placement.brightness, placement.brightness)
            block.viewRange = viewRange
            block.isPersistent = false
            block.isInvulnerable = true
            block.persistentDataContainer.set(plugin.panelIdKey, PersistentDataType.STRING, id)
            block.persistentDataContainer.set(plugin.panelRoleKey, PersistentDataType.STRING, role)
        }
        return display.uniqueId
    }

    /** An invisible clickable box, vertically centred on the text it covers. */
    private fun spawnHitbox(placement: Placement, panelId: String, role: String, x: Double, y: Double,
                            width: Double, height: Double): UUID {
        val at = placement.base.clone()
            .add(placement.rightward.clone().multiply(x))
            // Interaction entities anchor at their feet; the visual glyph
            // centre sits a little above the element's base height.
            .add(0.0, y + 0.15 - height / 2.0, 0.0)
            .add(placement.facing.clone().multiply(0.05))
        val hitbox = placement.world.spawn(at, Interaction::class.java) { interaction ->
            interaction.interactionWidth = width.toFloat()
            interaction.interactionHeight = height.toFloat()
            interaction.isResponsive = true
            interaction.isPersistent = false
            interaction.isInvulnerable = true
            interaction.persistentDataContainer.set(plugin.panelIdKey, PersistentDataType.STRING, panelId)
            interaction.persistentDataContainer.set(plugin.panelRoleKey, PersistentDataType.STRING, role)
        }
        return hitbox.uniqueId
    }

    private fun spawnText(placement: Placement, panelId: String, role: String, x: Double, y: Double, z: Double,
                          content: Component, scaleX: Float, scaleY: Float, opacity: Byte, background: Color?): UUID {
        val at = placement.base.clone()
            .add(placement.rightward.clone().multiply(x))
            .add(0.0, y, 0.0)
            .add(placement.facing.clone().multiply(z))
        at.yaw = placement.yaw
        at.pitch = 0.0F
        val display = placement.world.spawn(at, TextDisplay::class.java) { text ->
            text.text(content)
            text.billboard = Display.Billboard.FIXED
            text.transformation = Transformation(Vector3f(), Quaternionf(),
                Vector3f(scaleX, scaleY, scaleX), Quaternionf())
            text.textOpacity = opacity
            text.isShadowed = false
            text.isSeeThrough = false
            text.lineWidth = 400
            text.backgroundColor = background ?: Color.fromARGB(0)
            text.brightness = Display.Brightness(placement.brightness, placement.brightness)
            text.viewRange = plugin.config.getDouble("difficulty-panel.view-range", 2.0).toFloat()
            // Never written to the world save: panels.yml is the only truth,
            // so a restart cannot duplicate what the chunk already had.
            text.isPersistent = false
            text.isInvulnerable = true
            text.persistentDataContainer.set(plugin.panelIdKey, PersistentDataType.STRING, panelId)
            text.persistentDataContainer.set(plugin.panelRoleKey, PersistentDataType.STRING, role)
        }
        return display.uniqueId
    }

    /** Removes this panel's entities: the tracked ones plus any tagged stray nearby. */
    private fun clearEntities(id: String, base: Location) {
        buttons.remove(id)
        val ids = spawned.remove(id)
        if (ids != null) {
            for (entityId in ids) {
                Bukkit.getEntity(entityId)?.remove()
            }
        }
        val world = base.world ?: return
        // Load every chunk the panel can reach before sweeping: an entity in
        // an unloaded neighbour is invisible to getNearbyEntities and would
        // survive the rebuild to stand alongside its replacement.
        val reach = maxOf(8.0, plugin.config.getDouble("difficulty-panel.cleanup-radius", 12.0))
        for (chunkX in ((base.x - reach).toInt() shr 4)..((base.x + reach).toInt() shr 4)) {
            for (chunkZ in ((base.z - reach).toInt() shr 4)..((base.z + reach).toInt() shr 4)) {
                world.getChunkAt(chunkX, chunkZ)
            }
        }
        for (entity in world.getNearbyEntities(base, reach, reach, reach)) {
            val entityPanel = panelId(entity)
            // This panel's own entities, and strays whose panel is long gone
            // - including any left by an older version of the panel layout.
            if (entityPanel != null && (id == entityPanel || entityPanel !in panels)) {
                entity.remove()
            }
        }
    }

    private fun panelId(entity: Entity): String? =
        entity.persistentDataContainer.get(plugin.panelIdKey, PersistentDataType.STRING)

    // ------------------------------------------------------------------

    private fun save() {
        val storage = YamlConfiguration()
        for ((id, base) in panels) {
            val path = "panels.$id"
            storage.set("$path.world", base.world!!.name)
            storage.set("$path.x", base.x)
            storage.set("$path.y", base.y)
            storage.set("$path.z", base.z)
            storage.set("$path.yaw", base.yaw)
        }
        try {
            storage.save(storageFile)
        } catch (exception: IOException) {
            plugin.logger.severe("Could not save $STORAGE: ${exception.message}")
        }
    }

    private data class Placement(val world: World, val base: Location, val yaw: Float,
                                 val facing: Vector, val rightward: Vector, val brightness: Int)

    /** One number's spot on the carousel for a particular selection. */
    private data class NumberPose(val number: Int, val x: Double, val scale: Float, val opacity: Byte)

    data class PanelInfo(val id: String, val location: Location)

    /** Summary used by the administrative remove-all command. */
    data class RemovalReport(val count: Int, val locations: List<Location>)

    companion object {
        private const val STORAGE = "panels.yml"
        private const val MINIMUM_OPACITY: Byte = 26

        private fun setBlocks(ids: List<UUID>, material: Material) {
            val data: BlockData = material.createBlockData()
            for (id in ids) {
                (Bukkit.getEntity(id) as? BlockDisplay)?.block = data
            }
        }

        private fun blockMaterial(raw: String?, fallback: Material): Material {
            val material = raw?.let { Material.matchMaterial(it.uppercase(Locale.ROOT)) }
            return if (material == null || !material.isBlock) fallback else material
        }

        private fun removeEntities(ids: List<UUID>) {
            for (id in ids) {
                Bukkit.getEntity(id)?.remove()
            }
        }

        /**
         * Width of a string in font pixels, including the one pixel of
         * spacing that follows every glyph.
         *
         * That trailing pixel is also why a centred label looks a touch right
         * of centre: the line is centred including the gap after its last
         * glyph, so the visible text sits half a pixel to the left. [render]
         * nudges the label back by that half pixel.
         */
        private fun labelPixelWidth(text: String, bold: Boolean): Int {
            var width = 0
            for (character in text.toCharArray()) {
                width += glyphWidth(character) + (if (bold) 1 else 0) + 1
            }
            return width
        }

        /** Advance widths of Minecraft's default font. */
        private fun glyphWidth(character: Char): Int = when (character) {
            'i', '!', ',', '.', ':', ';', '|', '\'' -> 1
            'l', '`' -> 2
            ' ', 't', 'I', '[', ']', '{', '}', '(', ')', '"', '*' -> 3
            'f', 'k', '<', '>' -> 4
            '@', '~' -> 6
            else -> 5
        }

        private fun <T> withFallback(configured: List<T>?, fallback: List<T>): List<T> =
            if (configured.isNullOrEmpty()) fallback else configured
    }
}
