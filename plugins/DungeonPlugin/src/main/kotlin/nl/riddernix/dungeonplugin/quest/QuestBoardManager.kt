package nl.riddernix.dungeonplugin.quest

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * The in-world "Quest Board": a free-standing notice board of parchment notes,
 * one per active quest, built from display entities.
 *
 * Same model as [nl.riddernix.dungeonplugin.panel.DifficultyPanelManager] -
 * furniture placed once by an admin, entities spawned non-persistent from
 * `quest-boards.yml` so a restart cannot duplicate them, per-viewer content on
 * top of shared framing.
 *
 * Two pages: page 0 is Daily (left column) and Weekly (right column), page 1
 * is General. The page arrows and the eight note hitboxes are shared; the
 * clicking player's own page decides what a hitbox does. Everything with
 * progress or state on it - the notes, the XP-multiplier strip, the visible
 * arrows - is per viewer and proximity-gated.
 */
class QuestBoardManager(private val plugin: DungeonPlugin) {

    private val mini = MiniMessage.miniMessage()
    private val storageFile = File(plugin.dataFolder, STORAGE)
    private val quests get() = plugin.quests
    private val yaml get() = plugin.questConfig.yaml

    /** Board id -> base location (its yaw is the facing). */
    private val boards = LinkedHashMap<String, Location>()

    /** Shared entities per board, so a re-render can clean first. */
    private val shared = HashMap<String, List<UUID>>()

    /** Per player, per board: their overlay entities (notes, strip, arrow visuals). */
    private val overlays = HashMap<UUID, HashMap<String, List<UUID>>>()

    /** Per player: which page they are viewing (0 = daily/weekly, 1 = general). */
    private val page = HashMap<UUID, Int>()

    /** Counts [tick] calls, so overlays can be rebuilt on a slow cadence to keep the refresh timers moving. */
    private var ticks = 0

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    fun load() {
        boards.clear()
        val storage = YamlConfiguration.loadConfiguration(storageFile)
        val section = storage.getConfigurationSection("boards")
        if (section != null) {
            for (id in section.getKeys(false)) {
                val world = Bukkit.getWorld(section.getString("$id.world", "") ?: "")
                if (world == null) {
                    plugin.logger.warning("Ignoring quest board '$id': its world is not loaded.")
                    continue
                }
                boards[id] = snap(Location(world, section.getDouble("$id.x"), section.getDouble("$id.y"),
                    section.getDouble("$id.z"), section.getDouble("$id.yaw").toFloat(), 0.0f))
            }
        }
        for ((id, base) in boards) render(id, base)
        sweepOrphans()
    }

    fun reload() {
        for ((id, base) in boards) render(id, base)
    }

    fun despawnAll() {
        for (byBoard in overlays.values) byBoard.values.forEach(::removeEntities)
        overlays.clear()
        for ((id, base) in boards) clearEntities(id, base)
    }

    private fun sweepOrphans() {
        var removed = 0
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities.toList()) {
                val id = boardId(entity)
                if (id != null && id !in boards) {
                    entity.remove(); removed++
                }
            }
        }
        if (removed > 0) plugin.logger.info("Removed $removed orphaned quest board entit(ies).")
    }

    fun handleChunkLoad(chunk: Chunk) {
        for ((id, base) in boards) {
            if (chunk.world != base.world ||
                base.blockX shr 4 != chunk.x || base.blockZ shr 4 != chunk.z) continue
            Bukkit.getScheduler().runTask(plugin, Runnable {
                boards[id]?.let { render(id, it) }
            })
        }
    }

    // ------------------------------------------------------------------
    //  Placement
    // ------------------------------------------------------------------

    fun place(where: Location): String {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val base = snap(where)
        boards[id] = base
        render(id, base)
        save()
        return id
    }

    /**
     * Snaps a placement onto the block grid so the board's axes stay
     * axis-aligned: X/Z to the block centre, Y to the block floor, yaw to the
     * nearest 90° (a cardinal facing). Off-grid placement rotates every offset
     * in the layout and reads as misaligned. `board.snap-to-grid: false`
     * keeps the raw location.
     */
    private fun snap(where: Location): Location {
        if (!yaml.getBoolean("board.snap-to-grid", true)) {
            return where.clone().apply { pitch = 0.0f }
        }
        val yaw = ((Math.round(where.yaw / 90.0f) * 90) % 360 + 360) % 360
        return Location(where.world,
            Math.floor(where.x) + 0.5,
            Math.floor(where.y),
            Math.floor(where.z) + 0.5,
            yaw.toFloat(), 0.0f)
    }

    fun removeNearest(from: Location): Boolean {
        val nearest = nearest(from) ?: return false
        val base = boards.remove(nearest)
        clearEntities(nearest, base!!)
        for (byBoard in overlays.values) byBoard.remove(nearest)?.let(::removeEntities)
        save()
        return true
    }

    fun list(): List<BoardInfo> = boards.map { (id, base) -> BoardInfo(id, base.clone()) }

    private fun nearest(from: Location): String? {
        val radius = maxOf(1.0, yaml.getDouble("board.remove-radius", 5.0))
        var nearest: String? = null
        var best = radius * radius
        for ((id, base) in boards) {
            if (base.world != from.world) continue
            val d = base.distanceSquared(from)
            if (d <= best) { nearest = id; best = d }
        }
        return nearest
    }

    fun isBoardEntity(entity: Entity): Boolean = boardId(entity) != null

    // ------------------------------------------------------------------
    //  Interaction
    // ------------------------------------------------------------------

    fun handleClick(player: Player, clicked: Entity) {
        val id = boardId(clicked) ?: return
        if (id !in boards) return
        val role = clicked.persistentDataContainer.get(plugin.questBoardRoleKey, PersistentDataType.STRING) ?: return
        val current = page.getOrDefault(player.uniqueId, 0)
        when {
            role == "hit-arrow-next" -> flipPage(player, id, current, (current + 1).coerceAtMost(1))
            role == "hit-arrow-prev" -> flipPage(player, id, current, (current - 1).coerceAtLeast(0))
            role.startsWith("hit-note-") -> {
                val (category, slot) = resolveNote(role) ?: return
                when (quests.claim(player, category, slot)) {
                    QuestManager.ClaimResult.CLAIMED -> refreshViewer(player)
                    QuestManager.ClaimResult.NOT_COMPLETE ->
                        sound(player, "deny", 1.0f)
                    QuestManager.ClaimResult.ALREADY_CLAIMED ->
                        sound(player, "deny", 1.3f)
                    QuestManager.ClaimResult.MISSING -> {}
                }
            }
        }
    }

    /** Note hitbox role ("hit-note-general-2") -> the quest it stands for. */
    private fun resolveNote(role: String): Pair<QuestCategory, Int>? {
        val tag = role.removePrefix("hit-note-")
        val category = QuestCategory.fromId(tag.substringBeforeLast('-')) ?: return null
        val slot = tag.substringAfterLast('-').toIntOrNull() ?: return null
        if (slot !in 0 until QuestCategory.SLOTS) return null
        return category to slot
    }

    private fun flipPage(player: Player, boardId: String, from: Int, to: Int) {
        if (from == to) { sound(player, "deny", 1.0f); return }
        page[player.uniqueId] = to
        sound(player, "page", 1.0f)
        val base = boards[boardId] ?: return
        removeOverlays(player.uniqueId, boardId)
        ensureOverlays(player, boardId, base)
    }

    // ------------------------------------------------------------------
    //  Proximity sweep
    // ------------------------------------------------------------------

    fun tick() {
        val radius = maxOf(4.0, yaml.getDouble("board.activation-radius", 24.0))
        val radiusSq = radius * radius
        // Rebuild standing overlays every so often so the "Refreshes in ..."
        // timers keep counting down. tick() runs every 10 game ticks.
        val rebuildEvery = maxOf(1, (yaml.getInt("board.overlay-refresh-seconds", 30) * 2))
        val forceRebuild = (++ticks % rebuildEvery) == 0
        for (player in Bukkit.getOnlinePlayers()) {
            var near = false
            for ((id, base) in boards) {
                val inRange = base.world == player.world && base.distanceSquared(player.location) <= radiusSq
                if (inRange) {
                    near = true
                    if (forceRebuild) removeOverlays(player.uniqueId, id)
                    ensureOverlays(player, id, base)
                } else removeOverlays(player.uniqueId, id)
            }
            updateClickReach(player, near)
        }
        overlays.entries.removeIf { entry ->
            if (Bukkit.getPlayer(entry.key) != null) return@removeIf false
            entry.value.values.forEach(::removeEntities)
            true
        }
    }

    fun handleQuit(player: Player) {
        updateClickReach(player, false)
        overlays.remove(player.uniqueId)?.values?.forEach(::removeEntities)
        page.remove(player.uniqueId)
    }

    fun clearOverlaysFor(player: Player) {
        overlays.remove(player.uniqueId)?.values?.forEach(::removeEntities)
    }

    /** Redraws a player's overlays on every board they are currently near. */
    fun refreshViewer(player: Player) {
        val byBoard = overlays[player.uniqueId] ?: return
        for (id in byBoard.keys.toList()) {
            val base = boards[id] ?: continue
            removeOverlays(player.uniqueId, id)
            ensureOverlays(player, id, base)
        }
    }

    /** Redraws every online viewer - used when a category refreshes. */
    fun refreshAllViewers() {
        for (player in Bukkit.getOnlinePlayers()) refreshViewer(player)
    }

    private fun updateClickReach(player: Player, near: Boolean) {
        val attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE) ?: return
        val key = NamespacedKey(plugin, "quest_board_reach")
        val existing = attribute.getModifier(key)
        val target = yaml.getDouble("board.click-range", 8.0)
        if (!(near && target > attribute.baseValue)) {
            if (existing != null) attribute.removeModifier(existing)
            return
        }
        val amount = target - attribute.baseValue
        if (existing != null) {
            if (abs(existing.amount - amount) < 0.001) return
            attribute.removeModifier(existing)
        }
        attribute.addTransientModifier(AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER))
    }

    // ------------------------------------------------------------------
    //  Shared rendering
    // ------------------------------------------------------------------

    private fun render(id: String, base: Location) {
        val world = base.world ?: return
        if (!world.isChunkLoaded(base.blockX shr 4, base.blockZ shr 4)) return
        clearEntities(id, base)
        for (byBoard in overlays.values) byBoard.remove(id)?.let(::removeEntities)

        val placement = placement(base)
        val ids = ArrayList<UUID>()

        // Backing slab + a thin frame around it.
        val bw = yaml.getDouble("board.backing.width", 8.0)
        val bh = yaml.getDouble("board.backing.height", 6.2)
        val bd = yaml.getDouble("board.backing.depth", 0.3)
        val centreY = yaml.getDouble("board.backing.centre-y", 3.1)
        ids.add(spawnBox(placement, id, "backing", 0.0, centreY, 0.0, bw, bh, bd,
            block(yaml.getString("board.backing.block"), Material.STRIPPED_SPRUCE_WOOD)))
        val frameBlock = block(yaml.getString("board.backing.frame-block"), Material.STRIPPED_DARK_OAK_LOG)
        val ft = yaml.getDouble("board.backing.frame-thickness", 0.35)
        val fz = bd / 2.0 + 0.02
        ids.add(spawnBox(placement, id, "frame-top", 0.0, centreY + bh / 2.0, fz, bw + ft, ft, ft, frameBlock))
        ids.add(spawnBox(placement, id, "frame-bottom", 0.0, centreY - bh / 2.0, fz, bw + ft, ft, ft, frameBlock))
        ids.add(spawnBox(placement, id, "frame-left", -bw / 2.0, centreY, fz, ft, bh, ft, frameBlock))
        ids.add(spawnBox(placement, id, "frame-right", bw / 2.0, centreY, fz, ft, bh, ft, frameBlock))

        // "Quest Board" title - shared, everyone sees it. Notes, hitboxes and
        // arrows are per viewer (they depend on the page you are on), built in
        // ensureOverlays.
        val titleScale = yaml.getDouble("board.title.scale", 1.6).toFloat()
        ids.add(spawnText(placement, id, "title", 0.0,
            yaml.getDouble("board.title.height", 5.4), frontZ(),
            line(yaml.getString("board.title.text") ?: "<gradient:#e8c56a:#a5761f><bold>Quest Board</bold></gradient>"),
            titleScale, TextDisplay.TextAlignment.CENTER, null, perViewer = false))

        shared[id] = ids
    }

    /** The (category, slot, x-offset) each note slot maps to on a given page. */
    private fun pageSlots(currentPage: Int): List<Triple<QuestCategory, Int, Double>> = buildList {
        for (slot in 0 until QuestCategory.SLOTS) {
            if (currentPage == 0) {
                add(Triple(QuestCategory.DAILY, slot, -columnX()))
                add(Triple(QuestCategory.WEEKLY, slot, columnX()))
            } else {
                add(Triple(QuestCategory.GENERAL, slot, 0.0))
            }
        }
    }

    // ------------------------------------------------------------------
    //  Per-viewer overlays
    // ------------------------------------------------------------------

    private fun ensureOverlays(player: Player, boardId: String, base: Location) {
        val byBoard = overlays.getOrPut(player.uniqueId) { HashMap() }
        val existing = byBoard[boardId]
        if (!existing.isNullOrEmpty() && Bukkit.getEntity(existing.first()) != null) return
        val world = base.world
        if (world == null || world != player.world ||
            !world.isChunkLoaded(base.blockX shr 4, base.blockZ shr 4)) return

        val placement = placement(base)
        val currentPage = page.getOrDefault(player.uniqueId, 0)
        val ids = ArrayList<UUID>()

        // XP-multiplier strip, under the title, above the columns.
        val multiplier = quests.xpMultiplier(player.uniqueId)
        val percent = (((multiplier - 1.0) * 100.0).coerceAtLeast(0.0)).toInt()
        val stripText = (yaml.getString("board.multiplier.format")
            ?: "<color:#c9a227>Dungeon XP Multiplier: <white>×<value> <gray>(+<percent>%)")
            .replace("<value>", QuestManager.format(multiplier))
            .replace("<percent>", percent.toString())
        ids.add(spawnText(placement, boardId, "ov-multiplier", 0.0,
            yaml.getDouble("board.multiplier.height", 4.9), frontZ(),
            line(stripText), yaml.getDouble("board.multiplier.scale", 0.6).toFloat(),
            TextDisplay.TextAlignment.CENTER, null, perViewer = true))

        // Notes + their claim hitboxes for the current page, positioned so the
        // hitbox always lines up with its note whichever page you are on.
        val noteW = yaml.getDouble("board.hitboxes.note-width", 2.2)
        val noteH = yaml.getDouble("board.hitboxes.note-height", 0.9)
        for ((category, slot, x) in pageSlots(currentPage)) {
            ids.add(noteDisplay(player, placement, boardId, category, slot, x))
            ids.add(spawnHitbox(placement, boardId, "hit-note-${category.id}-$slot",
                x, noteTopY(slot) - noteH / 2.0, noteW, noteH, perViewer = true))
        }

        // One page arrow: ">" on page 0 (to General), "<" on page 1 (back).
        val arrowScale = yaml.getDouble("board.arrows.scale", 1.8).toFloat()
        val arrowH = yaml.getDouble("board.arrows.height", 2.6)
        val edgeX = yaml.getDouble("board.arrows.edge-x", 3.5)
        val aw = yaml.getDouble("board.hitboxes.arrow-width", 1.0)
        val ah = yaml.getDouble("board.hitboxes.arrow-height", 1.4)
        if (currentPage == 0) {
            ids.add(spawnArrow(placement, boardId, "ov-arrow-next", edgeX, arrowH,
                yaml.getString("board.arrows.next") ?: ">", arrowScale))
            ids.add(spawnHitbox(placement, boardId, "hit-arrow-next", edgeX, arrowH, aw, ah, perViewer = true))
        } else {
            ids.add(spawnArrow(placement, boardId, "ov-arrow-prev", -edgeX, arrowH,
                yaml.getString("board.arrows.prev") ?: "<", arrowScale))
            ids.add(spawnHitbox(placement, boardId, "hit-arrow-prev", -edgeX, arrowH, aw, ah, perViewer = true))
        }

        for (entityId in ids) {
            Bukkit.getEntity(entityId)?.let { player.showEntity(plugin, it) }
        }
        byBoard[boardId] = ids
    }

    private fun removeOverlays(playerId: UUID, boardId: String) {
        overlays[playerId]?.remove(boardId)?.let(::removeEntities)
    }

    /** One parchment note = one per-viewer TextDisplay with a paper background. */
    private fun noteDisplay(player: Player, placement: Placement, boardId: String,
                            category: QuestCategory, slot: Int, x: Double): UUID {
        val definition = quests.definition(category, slot)
        val state = quests.state(player.uniqueId, category, slot)
        val paper = if (state == QuestManager.QuestState.COMPLETE_UNCLAIMED)
            argb(yaml.getString("board.notes.paper-complete"), 0xE6D9A441.toInt())
        else argb(yaml.getString("board.notes.paper"), 0xD8C89A6B.toInt())

        val content = if (definition == null) line("<color:#7a2d2d>(no quest)")
        else noteContent(player, category, slot, definition, state)

        return spawnText(placement, boardId, "ov-note-${category.id}-$slot", x, noteTopY(slot), frontZ(),
            content, yaml.getDouble("board.notes.text-scale", 0.44).toFloat(),
            TextDisplay.TextAlignment.LEFT, Color.fromARGB(paper), perViewer = true)
    }

    private fun noteContent(player: Player, category: QuestCategory, slot: Int,
                            definition: QuestDefinition, state: QuestManager.QuestState): Component {
        val cfg = { path: String, fallback: String -> yaml.getString("board.card.$path") ?: fallback }
        val counter = quests.counter(player.uniqueId, category, slot)
        val shown = definition.clamp(counter)

        val lines = ArrayList<String>()
        lines.add(cfg("title-format", "<color:#3b2a12><bold><category> Quest: <title>")
            .replace("<category>", category.displayName).replace("<title>", definition.title))

        val nextRefresh = quests.nextRefreshMillis(category)
        lines.add(if (nextRefresh != null)
            cfg("timer-format", "<color:#6b5836>Refreshes in <time>").replace("<time>", until(nextRefresh))
        else cfg("timer-none", "<color:#6b5836>No timed refresh"))

        lines.add(cfg("description-format", "<color:#3b2a12><desc>").replace("<desc>", definition.description))

        val segments = yaml.getInt("board.card.bar-segments", 10).coerceIn(4, 40)
        val filled = if (definition.required <= 0) segments else (shown * segments) / definition.required
        val bar = (yaml.getString("board.card.bar-fill") ?: "<color:#c9a227>▰").repeat(filled.coerceIn(0, segments)) +
            (yaml.getString("board.card.bar-empty") ?: "<color:#5b4a2e>▰").repeat((segments - filled).coerceIn(0, segments))
        lines.add(cfg("bar-format", "<bar> <color:#3b2a12><current>/<required>")
            .replace("<bar>", bar).replace("<current>", shown.toString())
            .replace("<required>", definition.required.toString()))

        lines.add(when (state) {
            QuestManager.QuestState.COMPLETE_UNCLAIMED -> cfg("state-complete", "<color:#2e7d32><bold>✔ Completed!")
            QuestManager.QuestState.CLAIMED -> cfg("state-claimed", "<color:#5b4a2e>✔ Claimed")
            else -> cfg("state-incomplete", "<color:#7a2d2d>✖ Not Completed")
        })
        return line(lines.joinToString("<newline>"))
    }

    // ------------------------------------------------------------------
    //  Geometry helpers
    // ------------------------------------------------------------------

    private fun placement(base: Location): Placement {
        val yaw = base.yaw + (if (yaml.getBoolean("board.flip-facing", false)) 180.0f else 0.0f)
        val radians = Math.toRadians(yaw.toDouble())
        val facing = Vector(-sin(radians), 0.0, cos(radians))
        val rightward = Vector(facing.z, 0.0, -facing.x)
        return Placement(base.world!!, base, yaw, facing, rightward,
            yaml.getInt("board.brightness", 15).coerceIn(0, 15))
    }

    private fun columnX(): Double = yaml.getDouble("board.notes.column-x", 1.95)
    private fun frontZ(): Double = yaml.getDouble("board.notes.front-z", 0.06) + yaml.getDouble("board.backing.depth", 0.3) / 2.0
    private fun noteTopY(slot: Int): Double =
        yaml.getDouble("board.notes.top-y", 3.9) - slot * yaml.getDouble("board.notes.row-gap", 0.95)

    private fun viewRange(): Float = yaml.getDouble("board.view-range", 4.0).toFloat()

    // ------------------------------------------------------------------
    //  Entity spawns
    // ------------------------------------------------------------------

    private fun spawnText(placement: Placement, boardId: String, role: String, x: Double, y: Double, z: Double,
                          content: Component, scale: Float, alignment: TextDisplay.TextAlignment,
                          background: Color?, perViewer: Boolean): UUID {
        val at = placement.base.clone()
            .add(placement.rightward.clone().multiply(x))
            .add(0.0, y, 0.0)
            .add(placement.facing.clone().multiply(z))
        at.yaw = placement.yaw
        at.pitch = 0.0f
        val display = placement.world.spawn(at, TextDisplay::class.java) { text ->
            text.text(content)
            text.billboard = Display.Billboard.FIXED
            text.alignment = alignment
            text.transformation = Transformation(Vector3f(), Quaternionf(),
                Vector3f(scale, scale, scale), Quaternionf())
            text.isShadowed = false
            text.isSeeThrough = false
            text.lineWidth = yaml.getInt("board.notes.line-width", 210)
            text.backgroundColor = background ?: Color.fromARGB(0)
            text.brightness = Display.Brightness(placement.brightness, placement.brightness)
            text.viewRange = viewRange()
            text.isPersistent = false
            text.isInvulnerable = true
            // Per-viewer entities are hidden from the world and shown only to
            // their owner by the caller; shared furniture stays visible to all.
            if (perViewer) text.isVisibleByDefault = false
            tag(text, boardId, role)
        }
        return display.uniqueId
    }

    /** A page arrow ("<" / ">") - literal glyphs, so no MiniMessage escaping. */
    private fun spawnArrow(placement: Placement, boardId: String, role: String, x: Double, y: Double,
                           glyph: String, scale: Float): UUID {
        val colour = net.kyori.adventure.text.format.TextColor.fromHexString(
            yaml.getString("board.arrows.color", "#c9a227") ?: "#c9a227")
            ?: net.kyori.adventure.text.format.NamedTextColor.GOLD
        val component = Component.text(glyph, colour)
            .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true)
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
        return spawnText(placement, boardId, role, x, y, frontZ(), component, scale,
            TextDisplay.TextAlignment.CENTER, null, perViewer = true)
    }

    private fun spawnBox(placement: Placement, boardId: String, role: String, x: Double, y: Double, z: Double,
                         width: Double, height: Double, depth: Double, material: Material): UUID {
        val at = placement.base.clone()
            .add(placement.rightward.clone().multiply(x))
            .add(0.0, y, 0.0)
            .add(placement.facing.clone().multiply(z))
        at.yaw = placement.yaw
        at.pitch = 0.0f
        val display = placement.world.spawn(at, BlockDisplay::class.java) { block ->
            block.block = material.createBlockData()
            block.transformation = Transformation(
                Vector3f((-width / 2.0).toFloat(), (-height / 2.0).toFloat(), (-depth / 2.0).toFloat()),
                Quaternionf(),
                Vector3f(width.toFloat(), height.toFloat(), depth.toFloat()),
                Quaternionf())
            block.brightness = Display.Brightness(placement.brightness, placement.brightness)
            block.viewRange = viewRange()
            block.isPersistent = false
            block.isInvulnerable = true
            tag(block, boardId, role)
        }
        return display.uniqueId
    }

    private fun spawnHitbox(placement: Placement, boardId: String, role: String, x: Double, y: Double,
                            width: Double, height: Double, perViewer: Boolean): UUID {
        val at = placement.base.clone()
            .add(placement.rightward.clone().multiply(x))
            .add(0.0, y - height / 2.0, 0.0)
            .add(placement.facing.clone().multiply(frontZ() + 0.05))
        val hitbox = placement.world.spawn(at, Interaction::class.java) { interaction ->
            interaction.interactionWidth = width.toFloat()
            interaction.interactionHeight = height.toFloat()
            interaction.isResponsive = true
            interaction.isPersistent = false
            interaction.isInvulnerable = true
            // A per-viewer hitbox that a player has not been shown is not sent
            // to their client, so only its owner can click it.
            if (perViewer) interaction.isVisibleByDefault = false
            tag(interaction, boardId, role)
        }
        return hitbox.uniqueId
    }

    private fun tag(entity: Entity, boardId: String, role: String) {
        entity.persistentDataContainer.set(plugin.questBoardIdKey, PersistentDataType.STRING, boardId)
        entity.persistentDataContainer.set(plugin.questBoardRoleKey, PersistentDataType.STRING, role)
    }

    private fun clearEntities(id: String, base: Location) {
        shared.remove(id)?.forEach { Bukkit.getEntity(it)?.remove() }
        val world = base.world ?: return
        val reach = maxOf(10.0, yaml.getDouble("board.cleanup-radius", 16.0))
        for (cx in ((base.x - reach).toInt() shr 4)..((base.x + reach).toInt() shr 4)) {
            for (cz in ((base.z - reach).toInt() shr 4)..((base.z + reach).toInt() shr 4)) {
                world.getChunkAt(cx, cz)
            }
        }
        for (entity in world.getNearbyEntities(base, reach, reach, reach)) {
            val owner = boardId(entity)
            if (owner != null && (owner == id || owner !in boards)) entity.remove()
        }
    }

    private fun boardId(entity: Entity): String? =
        entity.persistentDataContainer.get(plugin.questBoardIdKey, PersistentDataType.STRING)

    private fun sound(player: Player, key: String, pitch: Float) {
        val raw = yaml.getString("board.sounds.$key", "UI_BUTTON_CLICK")
        val sound = if (raw.isNullOrBlank()) null
        else Registry.SOUNDS.get(NamespacedKey.minecraft(raw.lowercase(Locale.ROOT).replace('_', '.')))
        if (sound != null) player.playSound(player.location, sound, 0.7f, pitch)
    }

    private fun line(raw: String): Component = mini.deserialize("<!italic>$raw")

    private fun until(epochMillis: Long): String {
        val minutes = (epochMillis - System.currentTimeMillis()) / 60_000L
        if (minutes <= 0) return "any moment"
        val days = minutes / 1_440L
        val hours = (minutes % 1_440L) / 60L
        val mins = minutes % 60L
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${mins}m"
            mins > 0 -> "${mins}m"
            else -> "under 1m"
        }
    }

    private fun argb(raw: String?, fallback: Int): Int =
        raw?.trim()?.removePrefix("#")?.let { runCatching { it.toLong(16).toInt() }.getOrNull() } ?: fallback

    private fun block(raw: String?, fallback: Material): Material {
        val material = raw?.let { Material.matchMaterial(it.uppercase(Locale.ROOT)) }
        return if (material == null || !material.isBlock) fallback else material
    }

    private fun save() {
        val storage = YamlConfiguration()
        for ((id, base) in boards) {
            val path = "boards.$id"
            storage.set("$path.world", base.world!!.name)
            storage.set("$path.x", base.x)
            storage.set("$path.y", base.y)
            storage.set("$path.z", base.z)
            storage.set("$path.yaw", base.yaw)
        }
        try {
            storage.save(storageFile)
        } catch (ex: IOException) {
            plugin.logger.severe("Could not save $STORAGE: ${ex.message}")
        }
    }

    private data class Placement(val world: World, val base: Location, val yaw: Float,
                                 val facing: Vector, val rightward: Vector, val brightness: Int)

    data class BoardInfo(val id: String, val location: Location)

    companion object {
        private const val STORAGE = "quest-boards.yml"

        private fun removeEntities(ids: List<UUID>) {
            for (id in ids) Bukkit.getEntity(id)?.remove()
        }
    }
}
