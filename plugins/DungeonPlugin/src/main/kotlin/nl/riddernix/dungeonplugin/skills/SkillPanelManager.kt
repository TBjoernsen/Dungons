package nl.riddernix.dungeonplugin.skills

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.skills.SkillTreeLibrary.SkillBranch
import nl.riddernix.dungeonplugin.skills.SkillTreeLibrary.SkillClassTree
import nl.riddernix.dungeonplugin.skills.SkillTreeLibrary.SkillNode
import nl.riddernix.dungeonplugin.util.Messages
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Fixed in-world skill tree panels.
 *
 * The furniture is shared: every viewer sees the same structure, drawn
 * entirely in the locked (dimmest) style. What a particular player has
 * unlocked, what is available to them and what they have selected is a
 * per-viewer overlay - entities spawned invisible-by-default and shown only
 * to their owner, exactly like the difficulty panel's number row.
 *
 * Every node has a click surface, but a click is re-resolved server-side to
 * the node nearest the player's line of aim, so neighbouring hitboxes can
 * never fight over it: whichever box catches the click, the node being aimed
 * at wins.
 */
class SkillPanelManager(private val plugin: DungeonPlugin) {

    private val storageFile = File(plugin.dataFolder, STORAGE)
    private val miniMessage = MiniMessage.miniMessage()

    /** Panel id to its placement; the location's yaw is the panel's facing. */
    private val panels = LinkedHashMap<String, PlacedSkillPanel>()
    private val spawned = HashMap<String, List<UUID>>()

    /**
     * Panel to node to its shared entities. A player with their own coloured
     * plates for a node has the shared one hidden instead of covered, so no
     * locked plate can peek out from behind theirs at an angle.
     */
    private val sharedNodes = HashMap<String, Map<String, List<UUID>>>()

    /** Per player, per panel: their unlocked/available overlay entities. */
    private val stateOverlays = HashMap<UUID, HashMap<String, List<UUID>>>()

    /** Per player, per panel: their selection ring and detail panel. */
    private val selectionOverlays = HashMap<UUID, HashMap<String, List<UUID>>>()

    /** Per player, per panel: the node they last clicked. */
    private val selections = HashMap<UUID, HashMap<String, String>>()

    /**
     * Per player, per class: nodes treated as unlocked. Deliberately
     * in-memory and command-driven: this is the render test bed, kept as a
     * free overlay on top of real progression.
     */
    private val testUnlocked = HashMap<UUID, HashMap<String, MutableSet<String>>>()

    /** Per player, per panel: which carousel position they are viewing. */
    private val carouselIndex = HashMap<UUID, HashMap<String, Int>>()

    /** Per player, per panel: their carousel label entities. */
    private val carouselOverlays = HashMap<UUID, HashMap<String, List<UUID>>>()

    /** Per player, per panel: the open class-info panel, if any. */
    private val infoOverlays = HashMap<UUID, HashMap<String, List<UUID>>>()

    /** Per player, per panel: their own point readout where Confirm used to be. */
    private val pointsOverlays = HashMap<UUID, HashMap<String, List<UUID>>>()

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    fun load() {
        panels.clear()
        val storage = YamlConfiguration.loadConfiguration(storageFile)
        val section = storage.getConfigurationSection("panels")
        if (section != null) {
            for (id in section.getKeys(false)) {
                val world = Bukkit.getWorld(section.getString("$id.world", "")!!)
                if (world == null) {
                    plugin.logger.warning("Ignoring skill panel '$id': its world is not loaded.")
                    continue
                }
                panels[id] = PlacedSkillPanel(
                    Location(world, section.getDouble("$id.x"), section.getDouble("$id.y"),
                        section.getDouble("$id.z"), section.getDouble("$id.yaw").toFloat(), 0.0F),
                    section.getString("$id.class", "warrior")!!,
                    SkillPanelGeometry.Variant.parse(section.getString("$id.variant")))
            }
        }
        renderLoadedPanels()
        sweepOrphans()
    }

    fun reload() {
        renderLoadedPanels()
    }

    fun despawnAll() {
        for (player in Bukkit.getOnlinePlayers()) {
            updateClickReach(player, false)
        }
        for (overlays in stateOverlays.values) {
            overlays.values.forEach(::removeEntities)
        }
        for (overlays in selectionOverlays.values) {
            overlays.values.forEach(::removeEntities)
        }
        stateOverlays.clear()
        selectionOverlays.clear()
        for ((id, panel) in panels) {
            clearEntities(id, panel.base)
        }
    }

    private fun renderLoadedPanels() {
        for ((id, panel) in panels) {
            render(id, panel)
        }
    }

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
            plugin.logger.info("Removed $removed orphaned skill panel entit(ies).")
        }
    }

    /** Respawns a panel when its chunk comes back, one tick later. */
    fun handleChunkLoad(chunk: Chunk) {
        for ((id, panel) in panels) {
            val base = panel.base
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

    /** @return the panel id, or null when the class has no tree */
    fun place(where: Location, classId: String, variant: SkillPanelGeometry.Variant): String? {
        if (plugin.skillTrees.tree(classId) == null) {
            return null
        }
        val id = UUID.randomUUID().toString().substring(0, 8)
        val base = where.clone()
        base.pitch = 0.0F
        val panel = PlacedSkillPanel(base, classId.lowercase(Locale.ROOT), variant)
        panels[id] = panel
        render(id, panel)
        save()
        return id
    }

    /**
     * Moves the panel nearest [from] to [to], keeping its id, class and
     * variant.
     *
     * Rebuilt rather than nudged: the tree is dozens of display entities
     * whose offsets are all computed from the base, so recreating them at the
     * new spot is both simpler and the only way a rotation lands right.
     * Everyone's per-viewer overlays are dropped with it and come back on the
     * next proximity sweep.
     *
     * @return the panel's new location, null when nothing was near enough
     */
    fun moveNearest(from: Location, to: Location): Location? {
        val radius = maxOf(1.0, plugin.config.getDouble("skill-panel.remove-radius", 5.0))
        val nearest = nearestPanel(from, radius * radius) ?: return null
        val existing = panels[nearest]!!
        clearEntities(nearest, existing.base)
        val base = to.clone()
        base.pitch = 0.0F
        val moved = PlacedSkillPanel(base, existing.classId, existing.variant)
        panels[nearest] = moved
        render(nearest, moved)
        save()
        return base
    }

    fun removeNearest(from: Location): Boolean {
        val radius = maxOf(1.0, plugin.config.getDouble("skill-panel.remove-radius", 5.0))
        val nearest = nearestPanel(from, radius * radius) ?: return false
        val panel = panels.remove(nearest)!!
        clearEntities(nearest, panel.base)
        save()
        return true
    }

    fun removeAll(): RemovalReport {
        val locations = ArrayList<Location>()
        for ((id, panel) in panels.toMap()) {
            clearEntities(id, panel.base)
            locations.add(panel.base.clone())
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

    fun list(): List<PanelInfo> = panels.map { (id, panel) ->
        PanelInfo(id, panel.classId, panel.variant.configName(), panel.base.clone())
    }

    fun isPanelEntity(entity: Entity): Boolean = panelId(entity) != null

    private fun nearestPanel(from: Location, maximumDistanceSquared: Double): String? {
        var nearest: String? = null
        var nearestDistanceSquared = maximumDistanceSquared
        for ((id, panel) in panels) {
            val base = panel.base
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

    // ------------------------------------------------------------------
    //  Interaction
    // ------------------------------------------------------------------

    /**
     * A click on any node hitbox. The hitbox that caught it only tells us the
     * player clicked at the tree; the node is chosen by their line of aim.
     */
    fun handleClick(player: Player, clicked: Entity) {
        val panelId = panelId(clicked)
        val role = clicked.persistentDataContainer.get(plugin.skillPanelRoleKey, PersistentDataType.STRING)
        if (panelId == null || role == null || !role.startsWith("hit-")) {
            return
        }
        val panel = panels[panelId] ?: return
        when (role) {
            "hit-class-left" -> {
                shiftClass(player, panelId, panel, -1)
                return
            }
            "hit-class-right" -> {
                shiftClass(player, panelId, panel, 1)
                return
            }
            else -> {
                // Falls through to node handling below.
            }
        }
        val tree = plugin.skillTrees.tree(viewedClass(player, panelId, panel)) ?: return
        val nodeId = resolveByAim(player, panel, tree)
            ?: role.substring("hit-node-".length)
        if (nodeId !in tree.nodes) {
            return
        }
        val previousSelection = selections[player.uniqueId]?.get(panelId)
        selections.getOrPut(player.uniqueId) { HashMap() }[panelId] = nodeId
        if (nodeId == previousSelection) {
            // The second click on the same node is the commitment; the first
            // one only selected it, so browsing can never spend a point.
            attemptUnlock(player, panelId, panel, tree, nodeId)
            return
        }
        playSound(player, "select")
        // Selecting is not unlocking: only the ring and the detail panel
        // move.
        refreshSelectionOverlay(player, panelId, panel, tree)
    }

    private fun attemptUnlock(player: Player, panelId: String, panel: PlacedSkillPanel,
                              tree: SkillClassTree, nodeId: String) {
        // Points are always spent in the player's own class, never in one
        // they happen to be looking at. Nothing on this panel decides which
        // class that is any more - that lives elsewhere entirely.
        val classId = plugin.skillProgress.activeClass(player.uniqueId)
        if (classId == null) {
            plugin.messages.send(player, "skills-no-active-class")
            return
        }
        if (classId != viewedClass(player, panelId, panel)) {
            plugin.messages.send(player, "skills-not-your-class", Messages.ph("class", classId))
            return
        }
        when (plugin.skillProgress.unlock(player, classId, nodeId)) {
            SkillProgressManager.UnlockResult.UNLOCKED -> {
                playSound(player, "select")
                plugin.messages.send(player, "skills-unlocked", Messages.ph("node", nodeId),
                    Messages.ph("points", plugin.skillProgress.points(player.uniqueId)))
                rebuildStateOverlay(player, panelId, panel, tree)
                refreshSelectionOverlay(player, panelId, panel, tree)
                rebuildPointsDisplay(player, panelId, panel)
            }
            SkillProgressManager.UnlockResult.ALREADY_UNLOCKED ->
                plugin.messages.send(player, "skills-unlock-already", Messages.ph("node", nodeId))
            SkillProgressManager.UnlockResult.LOCKED ->
                plugin.messages.send(player, "skills-unlock-locked", Messages.ph("node", nodeId))
            SkillProgressManager.UnlockResult.DIFFICULTY_LOCKED ->
                plugin.messages.send(player, "skills-unlock-difficulty",
                    Messages.ph("node", nodeId),
                    Messages.ph("difficulty", tree.nodes[nodeId]!!.requiresDifficulty))
            SkillProgressManager.UnlockResult.NOT_ENOUGH_POINTS ->
                plugin.messages.send(player, "skills-unlock-no-points",
                    Messages.ph("node", nodeId), Messages.ph("points", plugin.skillProgress.points(player.uniqueId)))
            SkillProgressManager.UnlockResult.REFUSED ->
                plugin.messages.send(player, "skills-unlock-refused", Messages.ph("node", nodeId))
            SkillProgressManager.UnlockResult.UNKNOWN_NODE -> {
                // The aim resolver only offers nodes from this tree, so this
                // cannot normally happen; stay silent rather than confuse.
            }
        }
    }

    /** The node whose centre lies nearest the player's aim line. */
    private fun resolveByAim(player: Player, panel: PlacedSkillPanel, tree: SkillClassTree): String? {
        val geometry = geometry(panel)
        val placement = placement(panel)
        val rootHeight = geometry.rootHeight
        val step = geometry.radiusStep
        val origin = player.eyeLocation.toVector()
        val direction = player.eyeLocation.direction.normalize()

        var best: String? = null
        var bestOffset = geometry.aimTolerance
        for (node in tree.nodes.values) {
            val at = point(node, rootHeight, step)
            val centre = placement.base.clone()
                .add(placement.rightward.clone().multiply(at[0]))
                .add(0.0, at[1], 0.0)
                .add(placement.facing.clone().multiply(geometry.zNode))
                .toVector()
            val toNode = centre.subtract(origin)
            val along = toNode.dot(direction)
            if (along <= 0.0) {
                continue
            }
            val offset = toNode.subtract(direction.clone().multiply(along)).length()
            if (offset < bestOffset) {
                best = node.id
                bestOffset = offset
            }
        }
        return best
    }

    /**
     * The proximity sweep: overlays follow players in and out of range, and
     * overlays of players who left disappear rather than lingering unseen.
     */
    fun tick() {
        val radius = maxOf(4.0, plugin.config.getDouble("skill-panel.activation-radius", 30.0))
        val radiusSquared = radius * radius
        for (player in Bukkit.getOnlinePlayers()) {
            var nearAnyPanel = false
            for ((panelKey, panel) in panels) {
                val base = panel.base
                val inRange = base.world == player.world &&
                    base.distanceSquared(player.location) <= radiusSquared
                if (inRange) {
                    nearAnyPanel = true
                    ensureOverlays(player, panelKey, panel)
                } else {
                    removeOverlays(player.uniqueId, panelKey, player)
                }
            }
            updateClickReach(player, nearAnyPanel)
        }
        for (playerId in stateOverlays.keys.toList()) {
            if (Bukkit.getPlayer(playerId) == null) {
                clearPlayer(playerId)
            }
        }
    }

    /**
     * The vanilla client only sends a click on an entity that is inside the
     * player's entity-interaction-range attribute - about three blocks. That
     * is the hard limit on clicking, and it cannot be worked around: it can
     * only be moved, because since 1.20.5 the attribute is synchronised and
     * the client honours a raised value. Raising it also extends melee reach
     * on mobs, which is why the boost exists only while the player stands
     * inside a skill panel's activation radius, and is removed the moment
     * they leave it.
     */
    private fun updateClickReach(player: Player, nearPanel: Boolean) {
        val attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE) ?: return
        val key = NamespacedKey(plugin, "skill_panel_reach")
        val existing = attribute.getModifier(key)
        val target = plugin.config.getDouble("skill-panel.click-range", 20.0)
        val wanted = nearPanel && target > attribute.baseValue
        if (!wanted) {
            if (existing != null) {
                attribute.removeModifier(existing)
            }
            return
        }
        val amount = target - attribute.baseValue
        if (existing != null) {
            if (abs(existing.amount - amount) < 0.001) {
                return
            }
            attribute.removeModifier(existing)
        }
        // Transient: never written to the player file, so a crash cannot
        // leave anyone with permanently extended reach.
        attribute.addTransientModifier(AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER))
    }

    /** Logout, world change or death: the rendered overlays go; state stays. */
    fun clearOverlaysFor(player: Player) {
        for (panelId in (stateOverlays[player.uniqueId]?.keys ?: emptySet()).toList()) {
            removeOverlays(player.uniqueId, panelId, player)
        }
        for (panelId in (selectionOverlays[player.uniqueId]?.keys ?: emptySet()).toList()) {
            removeOverlays(player.uniqueId, panelId, player)
        }
    }

    /** Logout proper: the test state and selection are session things. */
    fun handleQuit(player: Player) {
        updateClickReach(player, false)
        clearPlayer(player.uniqueId)
    }

    private fun clearPlayer(playerId: UUID) {
        stateOverlays.remove(playerId)?.values?.forEach(::removeEntities)
        selectionOverlays.remove(playerId)?.values?.forEach(::removeEntities)
        carouselOverlays.remove(playerId)?.values?.forEach(::removeEntities)
        infoOverlays.remove(playerId)?.values?.forEach(::removeEntities)
        pointsOverlays.remove(playerId)?.values?.forEach(::removeEntities)
        selections.remove(playerId)
        testUnlocked.remove(playerId)
        carouselIndex.remove(playerId)
    }

    // ------------------------------------------------------------------
    //  Class carousel and Info (big variant)
    // ------------------------------------------------------------------

    /** Which class this player's carousel is pointing at on this panel. */
    private fun viewedClass(player: Player, panelId: String, panel: PlacedSkillPanel): String {
        if (geometry(panel).controls == null) {
            return panel.classId
        }
        val ids = plugin.skillTrees.classIds()
        if (ids.isEmpty()) {
            return panel.classId
        }
        return ids[carouselIndexFor(player, panelId, panel, ids)]
    }

    /** Starts on the player's active class, falling back to the placed one. */
    private fun carouselIndexFor(player: Player, panelId: String, panel: PlacedSkillPanel, ids: List<String>): Int {
        val byPanel = carouselIndex.getOrPut(player.uniqueId) { HashMap() }
        var index = byPanel[panelId]
        if (index == null) {
            val start = plugin.skillProgress.activeClass(player.uniqueId) ?: panel.classId
            index = maxOf(0, ids.indexOf(start))
            byPanel[panelId] = index
        }
        return index.coerceIn(0, maxOf(0, ids.size - 1))
    }

    /** One carousel step. Clamps at the ends, like the difficulty panel. */
    private fun shiftClass(player: Player, panelId: String, panel: PlacedSkillPanel, direction: Int) {
        val geometry = geometry(panel)
        if (geometry.controls == null) {
            return
        }
        val ids = plugin.skillTrees.classIds()
        val current = carouselIndexFor(player, panelId, panel, ids)
        val next = (current + direction).coerceIn(0, maxOf(0, ids.size - 1))
        if (next == current) {
            playSound(player, "select")
            return
        }
        carouselIndex[player.uniqueId]!![panelId] = next
        playSound(player, "select")
        rebuildCarousel(player, panelId, panel, geometry, true)
        // The tree structure is shared, but everything personal - unlocked
        // overlay, selection detail, open info panel - is per class.
        val tree = plugin.skillTrees.tree(ids[next])
        if (tree != null) {
            rebuildStateOverlay(player, panelId, panel, tree)
            refreshSelectionOverlay(player, panelId, panel, tree)
        }
    }

    /**
     * The standing Info area under the tree's left side. Not a button: it is
     * always there, showing the selected node's details, or - when nothing is
     * selected - the class the carousel points at, whether it is the player's
     * active one and how many of its skills they have unlocked.
     */
    private fun rebuildInfoPanel(player: Player, panelId: String, panel: PlacedSkillPanel, tree: SkillClassTree) {
        val geometry = geometry(panel)
        val controls = geometry.controls ?: return
        val byPanel = infoOverlays.getOrPut(player.uniqueId) { HashMap() }
        byPanel.remove(panelId)?.let(::removeEntities)
        val world = panel.base.world
        if (world == null || world != player.world) {
            return
        }
        val classId = viewedClass(player, panelId, panel)
        val unlocked = unlockedFor(player.uniqueId, classId)
        val nodeId = selections[player.uniqueId]?.get(panelId)
        val node = nodeId?.let { tree.nodes[it] }
        val content = if (node != null)
            nodeDetailContent(node, unlocked)
        else miniMessage.deserialize(
            plugin.config.getString("skill-panel.info.format",
                "<name><newline><color:#b3a577><description><newline>" +
                    "<color:#c9a227>Active class: <active><newline>" +
                    "<color:#c9a227>Unlocked here: <unlocked>")!!,
            Messages.ph("name", plugin.skillTrees.displayName(classId) ?: classId),
            Messages.ph("description", plugin.skillTrees.description(classId)),
            Messages.ph("active", if (classId == plugin.skillProgress.activeClass(player.uniqueId)) "yes" else "no"),
            Messages.ph("unlocked", unlocked.size.toString()))
        byPanel[panelId] = listOf(spawnInfoText(placement(panel), geometry, controls, panelId, content, player))
    }

    /**
     * The player's own point balance, standing where Confirm used to be.
     *
     * Per viewer rather than shared furniture, for the obvious reason: a
     * balance belongs to one player. Rebuilt from the same places that redraw
     * the tree, so spending a point, being granted one, or having a tree
     * reset all move the number the moment they happen.
     */
    private fun rebuildPointsDisplay(player: Player, panelId: String, panel: PlacedSkillPanel) {
        val geometry = geometry(panel)
        val controls = geometry.controls ?: return
        val byPanel = pointsOverlays.getOrPut(player.uniqueId) { HashMap() }
        byPanel.remove(panelId)?.let(::removeEntities)
        val world = panel.base.world
        if (world == null || world != player.world) {
            return
        }
        // Zero is shown as plainly as any other number: a blank where a
        // figure used to be reads as broken rather than as empty.
        val content = miniMessage.deserialize(
            plugin.config.getString("skill-panel.points.format",
                "<color:#b3a577>Skill Points<newline><gradient:#c9a227:#f2e39b><bold><points>")!!,
            Messages.ph("points", plugin.skillProgress.points(player.uniqueId)),
            Messages.ph("spent", plugin.skillProgress.spentPoints(player.uniqueId)),
            Messages.ph("budget", plugin.skillProgress.budget(player.uniqueId)))
        byPanel[panelId] = listOf(spawnPointsText(placement(panel), controls, panelId, content, player))
    }

    private fun spawnPointsText(placement: Placement, controls: SkillPanelGeometry.Controls,
                                panelId: String, content: Component, owner: Player): UUID {
        val config = plugin.config
        val scale = config.getDouble("skill-panel.points.scale", 1.4).toFloat()
        val at = placement.base.clone()
            .add(placement.rightward.clone().multiply(controls.confirmX))
            .add(0.0, controls.buttonsY, 0.0)
            .add(placement.facing.clone().multiply(0.30))
        at.yaw = placement.yaw
        at.pitch = 0.0F
        val display = placement.world.spawn(at, TextDisplay::class.java) { text ->
            text.text(content)
            text.billboard = Display.Billboard.FIXED
            text.alignment = TextDisplay.TextAlignment.CENTER
            text.transformation = Transformation(Vector3f(), Quaternionf(),
                Vector3f(scale, scale, scale), Quaternionf())
            text.isShadowed = false
            text.isSeeThrough = false
            text.backgroundColor = argb(config.getString("skill-panel.points.background", "00000000"))
            text.brightness = Display.Brightness(placement.brightness, placement.brightness)
            text.viewRange = config.getDouble("skill-panel.view-range", 2.0).toFloat()
            text.isPersistent = false
            text.isInvulnerable = true
            text.isVisibleByDefault = false
            tag(text, panelId, "personal-points")
        }
        owner.showEntity(plugin, display)
        return display.uniqueId
    }

    /** The node details, shared by the Info area and the standard detail panel. */
    private fun nodeDetailContent(node: SkillNode, unlocked: Set<String>): Component =
        miniMessage.deserialize(
            plugin.config.getString("skill-panel.detail.format",
                "<gradient:#c9a227:#f2e39b><bold><name></bold></gradient><newline>" +
                    "<color:#c9a227>Cost: <cost> point(s)<newline>" +
                    "<color:#b3a577><description><newline>" +
                    "<color:#c9a227>Level <level>/1")!!,
            Messages.ph("name", node.name),
            Messages.ph("cost", node.cost.toString()),
            Messages.ph("description", node.description),
            Messages.ph("level", if (node.id in unlocked) "1" else "0"))

    /** The Info area's text display, anchored where the mockup puts it. */
    private fun spawnInfoText(placement: Placement, geometry: SkillPanelGeometry,
                              controls: SkillPanelGeometry.Controls, panelId: String,
                              content: Component, owner: Player): UUID {
        val config = plugin.config
        val scale = controls.infoScale
        val at = placement.base.clone()
            .add(placement.rightward.clone().multiply(controls.infoX))
            .add(0.0, controls.infoY, 0.0)
            .add(placement.facing.clone().multiply(0.30))
        at.yaw = placement.yaw
        at.pitch = 0.0F
        val display = placement.world.spawn(at, TextDisplay::class.java) { text ->
            text.text(content)
            text.billboard = Display.Billboard.FIXED
            text.alignment = TextDisplay.TextAlignment.LEFT
            text.lineWidth = controls.infoLineWidth
            text.transformation = Transformation(Vector3f(), Quaternionf(),
                Vector3f(scale, scale, scale), Quaternionf())
            text.isShadowed = false
            text.isSeeThrough = false
            text.backgroundColor = argb(config.getString("skill-panel.detail.background", "A0140F05"))
            text.brightness = Display.Brightness(placement.brightness, placement.brightness)
            text.viewRange = config.getDouble("skill-panel.view-range", 2.0).toFloat()
            text.isPersistent = false
            text.isInvulnerable = true
            text.isVisibleByDefault = false
            tag(text, panelId, "personal-info")
        }
        owner.showEntity(plugin, display)
        return display.uniqueId
    }

    /** The shared furniture below the tree: just the carousel arrows now. */
    private fun spawnControls(placement: Placement, geometry: SkillPanelGeometry,
                              controls: SkillPanelGeometry.Controls, id: String): List<UUID> {
        val config = plugin.config
        val ids = ArrayList<UUID>()
        ids.add(spawnText(placement, id, "carousel-arrow-left",
            miniMessage.deserialize(config.getString("difficulty-panel.arrows.left", "<color:#c9a227><bold><")!!),
            -controls.arrowX, controls.carouselY, geometry.zRing, controls.arrowScale, 255.toByte(), null, null))
        ids.add(spawnText(placement, id, "carousel-arrow-right",
            miniMessage.deserialize(config.getString("difficulty-panel.arrows.right", "<color:#c9a227><bold>>")!!),
            controls.arrowX, controls.carouselY, geometry.zRing, controls.arrowScale, 255.toByte(), null, null))
        ids.add(spawnHitboxAt(placement, id, "hit-class-left",
            -controls.arrowX, controls.carouselY, 1.8, 1.6, geometry.zHitbox))
        ids.add(spawnHitboxAt(placement, id, "hit-class-right",
            controls.arrowX, controls.carouselY, 1.8, 1.6, geometry.zHitbox))

        // Where Confirm used to stand there is now a points readout, and it
        // is not furniture: a balance belongs to one player, so it is built
        // per viewer by rebuildPointsDisplay alongside the Info area.
        return ids
    }

    /**
     * The per-player class row: every class name spawned once, positioned by
     * its distance from the viewed one - the difficulty carousel's recipe.
     * Sliding teleports each label to its new spot and lets teleport and
     * transformation interpolation carry the motion.
     */
    private fun rebuildCarousel(player: Player, panelId: String, panel: PlacedSkillPanel,
                                geometry: SkillPanelGeometry, slide: Boolean) {
        val controls = geometry.controls ?: return
        val classIds = plugin.skillTrees.classIds()
        if (classIds.isEmpty()) {
            return
        }
        val selected = carouselIndexFor(player, panelId, panel, classIds)
        val placement = placement(panel)
        val byPanel = carouselOverlays.getOrPut(player.uniqueId) { HashMap() }
        val existing = byPanel[panelId]

        if (slide && existing != null && existing.size == classIds.size &&
            Bukkit.getEntity(existing.first()) != null) {
            for (index in classIds.indices) {
                val label = Bukkit.getEntity(existing[index]) as? TextDisplay ?: continue
                val pose = carouselPose(controls, index - selected)
                label.teleport(carouselLocation(placement, controls, pose[0], pose[1].toFloat()))
                label.interpolationDelay = 0
                label.interpolationDuration = controls.slideTicks
                label.transformation = Transformation(Vector3f(), Quaternionf(),
                    Vector3f(pose[1].toFloat(), pose[1].toFloat(), pose[1].toFloat()), Quaternionf())
                label.textOpacity = pose[2].toInt().toByte()
            }
            return
        }

        existing?.let(::removeEntities)
        val row = ArrayList<UUID>()
        for (index in classIds.indices) {
            val pose = carouselPose(controls, index - selected)
            val at = carouselLocation(placement, controls, pose[0], pose[1].toFloat())
            val scale = pose[1].toFloat()
            val opacity = pose[2].toInt().toByte()
            val name = miniMessage.deserialize(plugin.skillTrees.displayName(classIds[index]) ?: classIds[index])
            val display = placement.world.spawn(at, TextDisplay::class.java) { text ->
                text.text(name)
                text.billboard = Display.Billboard.FIXED
                text.transformation = Transformation(Vector3f(), Quaternionf(),
                    Vector3f(scale, scale, scale), Quaternionf())
                text.textOpacity = opacity
                text.isShadowed = false
                text.isSeeThrough = false
                text.lineWidth = 400
                text.backgroundColor = Color.fromARGB(0)
                text.brightness = Display.Brightness(placement.brightness, placement.brightness)
                text.viewRange = plugin.config.getDouble("skill-panel.view-range", 2.0).toFloat()
                text.teleportDuration = controls.slideTicks
                text.isPersistent = false
                text.isInvulnerable = true
                text.isVisibleByDefault = false
                tag(text, panelId, "personal-class")
            }
            player.showEntity(plugin, display)
            row.add(display.uniqueId)
        }
        byPanel[panelId] = row
    }

    private fun carouselLocation(placement: Placement, controls: SkillPanelGeometry.Controls,
                                 x: Double, scale: Float): Location {
        val at = placement.base.clone()
            .add(placement.rightward.clone().multiply(x))
            .add(0.0, controls.carouselY - GLYPH_CENTRE_PIXELS * scale / 40.0, 0.0)
            .add(placement.facing.clone().multiply(0.30))
        at.yaw = placement.yaw
        at.pitch = 0.0F
        return at
    }

    // ------------------------------------------------------------------
    //  Test state (a free render-check overlay on top of real progression)
    // ------------------------------------------------------------------

    /**
     * Redraws one player's own overlays on every panel they can see.
     *
     * Called after a write from outside the panel, so a revoked node goes
     * dark on screen at once rather than on the next reload. Only this
     * player's entities are touched - the tree itself is shared furniture and
     * everyone's progress is a per-viewer overlay on top of it, so two people
     * at one panel never see each other's change.
     */
    fun refreshFor(player: Player) {
        for ((panelKey, panel) in panels) {
            if (panelKey !in (stateOverlays[player.uniqueId] ?: emptyMap())) {
                continue
            }
            val tree = plugin.skillTrees.tree(viewedClass(player, panelKey, panel)) ?: continue
            rebuildStateOverlay(player, panelKey, panel, tree)
            refreshSelectionOverlay(player, panelKey, panel, tree)
            rebuildInfoPanel(player, panelKey, panel, tree)
            rebuildPointsDisplay(player, panelKey, panel)
        }
    }

    fun testUnlock(player: Player, nodeId: String): TestResult {
        val radius = maxOf(4.0, plugin.config.getDouble("skill-panel.activation-radius", 30.0))
        val panelId = nearestPanel(player.location, radius * radius)
        val panel = panelId?.let { panels[it] }
        val classId = panel?.let { viewedClass(player, panelId, it) }
        val tree = classId?.let { plugin.skillTrees.tree(it) } ?: return TestResult.NO_PANEL
        if (nodeId !in tree.nodes) {
            return TestResult.UNKNOWN_NODE
        }
        // Deliberately ungated: the point is checking how all three states
        // render, including combinations real progression would forbid.
        testUnlocked.getOrPut(player.uniqueId) { HashMap() }
            .getOrPut(classId) { HashSet() }.add(nodeId)
        rebuildStateOverlay(player, panelId, panel, tree)
        refreshSelectionOverlay(player, panelId, panel, tree)
        return TestResult.UNLOCKED
    }

    fun testClear(player: Player) {
        testUnlocked.remove(player.uniqueId)
        for ((panelKey, panel) in panels) {
            val tree = plugin.skillTrees.tree(panel.classId)
            if (tree != null && panelKey in (stateOverlays[player.uniqueId] ?: emptyMap())) {
                rebuildStateOverlay(player, panelKey, panel, tree)
                refreshSelectionOverlay(player, panelKey, panel, tree)
            }
        }
    }

    /** Every node id across every tree, for tab completion. */
    fun allNodeIds(): List<String> {
        val ids = HashSet<String>()
        for (classId in plugin.skillTrees.classIds()) {
            plugin.skillTrees.tree(classId)?.let { ids.addAll(it.nodes.keys) }
        }
        return ids.sorted()
    }

    // ------------------------------------------------------------------
    //  Node and line states
    // ------------------------------------------------------------------

    private enum class NodeState {
        LOCKED, AVAILABLE, UNLOCKED
    }

    private fun unlockedFor(playerId: UUID, classId: String): Set<String> {
        // Real progression first; the test harness remains as a render-check
        // overlay on top of it, deliberately free and ungated.
        val combined = HashSet(plugin.skillProgress.unlockedNodes(playerId, classId).keys)
        combined.addAll(testUnlocked[playerId]?.get(classId) ?: emptySet())
        return combined
    }

    private fun stateOf(player: Player, node: SkillNode, unlocked: Set<String>): NodeState {
        if (node.id in unlocked) {
            return NodeState.UNLOCKED
        }
        // any-of nodes are where paths rejoin: one unlocked route suffices.
        val reachable = if (node.anyOf) node.requires.any { it in unlocked }
        else node.requires.all { it in unlocked }
        // A node whose difficulty gate is not met reads as locked rather than
        // available: offering it and then refusing the click is worse than
        // showing it as out of reach, which is what it is.
        return if (reachable && plugin.skillProgress.difficultyMet(player, node))
            NodeState.AVAILABLE else NodeState.LOCKED
    }

    // ------------------------------------------------------------------
    //  Rendering: shared furniture
    // ------------------------------------------------------------------

    private fun render(id: String, panel: PlacedSkillPanel) {
        val world = panel.base.world
        if (world == null || !world.isChunkLoaded(panel.base.blockX shr 4, panel.base.blockZ shr 4)) {
            return
        }
        val tree = plugin.skillTrees.tree(panel.classId)
        if (tree == null) {
            plugin.logger.warning("Skill panel '$id' refers to class '${panel.classId}'" +
                ", which skills.yml no longer defines. The panel stays empty until it does.")
            clearEntities(id, panel.base)
            return
        }
        clearEntities(id, panel.base)
        val geometry = geometry(panel)
        val placement = placement(panel)
        val rootHeight = geometry.rootHeight
        val step = geometry.radiusStep
        val ids = ArrayList<UUID>()

        val byNode = HashMap<String, List<UUID>>()
        // The shared base draws everything locked; brighter states are each
        // viewer's personal overlay on top of it.
        for (node in tree.nodes.values) {
            for (requiredId in node.requires) {
                val required = tree.nodes[requiredId]
                if (required != null) {
                    ids.addAll(spawnEdge(placement, geometry, id, required, node, rootHeight, step,
                        NodeState.LOCKED, false, null))
                }
            }
        }
        for (node in tree.nodes.values) {
            val at = point(node, rootHeight, step)
            val nodeIds = spawnNodeVisual(placement, geometry, id, node, at, NodeState.LOCKED, null)
            ids.addAll(nodeIds)
            byNode[node.id] = nodeIds
            ids.add(spawnNodeHitbox(placement, geometry, id, node, at))
        }

        for (branch in tree.branches.values) {
            val at = doubleArrayOf(branch.unitX * step, rootHeight + branch.unitY * step)
            ids.add(spawnText(placement, id, "branch-${branch.id}",
                miniMessage.deserialize(branch.label), at[0], at[1], geometry.zNode,
                geometry.branchLabelScale, 255.toByte(), null, null))
        }

        if (geometry.controls == null) {
            val top = tree.nodes.values.maxOfOrNull { it.unitY } ?: 4.0
            ids.add(spawnText(placement, id, "heading", miniMessage.deserialize(tree.displayName),
                0.0, rootHeight + top * step + geometry.headingHeightExtra, geometry.zNode,
                geometry.headingScale, 255.toByte(), null, null))
        } else {
            // The mockup's lower third: class carousel, then Info and points.
            // The class name lives in the carousel, so there is no heading.
            ids.addAll(spawnControls(placement, geometry, geometry.controls, id))
        }

        spawned[id] = ids
        sharedNodes[id] = byNode
        // Re-rendering swept every tagged entity, personal overlays included;
        // the proximity tick rebuilds them for players still in range.
        for (overlays in stateOverlays.values) {
            overlays.remove(id)
        }
        for (overlays in selectionOverlays.values) {
            overlays.remove(id)
        }
    }

    // ------------------------------------------------------------------
    //  Rendering: per-player overlays
    // ------------------------------------------------------------------

    private fun ensureOverlays(player: Player, panelId: String, panel: PlacedSkillPanel) {
        val tree = plugin.skillTrees.tree(viewedClass(player, panelId, panel)) ?: return
        val geometry = geometry(panel)
        if (geometry.controls != null) {
            val row = carouselOverlays[player.uniqueId]?.get(panelId)
            if (row.isNullOrEmpty() || Bukkit.getEntity(row.first()) == null) {
                rebuildCarousel(player, panelId, panel, geometry, false)
            }
            val info = infoOverlays[player.uniqueId]?.get(panelId)
            if (info.isNullOrEmpty() || Bukkit.getEntity(info.first()) == null) {
                rebuildInfoPanel(player, panelId, panel, tree)
            }
        }
        val state = stateOverlays[player.uniqueId]?.get(panelId)
        if (state == null || (state.isNotEmpty() && Bukkit.getEntity(state.first()) == null)) {
            rebuildStateOverlay(player, panelId, panel, tree)
        }
        val selected = selections[player.uniqueId]?.get(panelId)
        val selection = selectionOverlays[player.uniqueId]?.get(panelId)
        if (selected != null && (selection.isNullOrEmpty() ||
                Bukkit.getEntity(selection.first()) == null)) {
            refreshSelectionOverlay(player, panelId, panel, tree)
        }
    }

    /** Everything brighter than locked, drawn just in front of the base. */
    private fun rebuildStateOverlay(player: Player, panelId: String, panel: PlacedSkillPanel, tree: SkillClassTree) {
        val byPanel = stateOverlays.getOrPut(player.uniqueId) { HashMap() }
        byPanel.remove(panelId)?.let(::removeEntities)
        // Every shared node comes back before the new overlay hides its own.
        showAllSharedNodes(player, panelId)
        val world = panel.base.world
        if (world == null || world != player.world ||
            !world.isChunkLoaded(panel.base.blockX shr 4, panel.base.blockZ shr 4)) {
            return
        }
        val geometry = geometry(panel)
        val placement = placement(panel)
        val rootHeight = geometry.rootHeight
        val step = geometry.radiusStep
        val unlocked = unlockedFor(player.uniqueId, viewedClass(player, panelId, panel))

        val ids = ArrayList<UUID>()
        for (node in tree.nodes.values) {
            val nodeState = stateOf(player, node, unlocked)
            for (requiredId in node.requires) {
                val required = tree.nodes[requiredId]
                if (required == null || requiredId !in unlocked) {
                    continue // prerequisite still locked: the base line stands
                }
                val lineState = if (nodeState == NodeState.UNLOCKED) NodeState.UNLOCKED else NodeState.AVAILABLE
                ids.addAll(spawnEdge(placement, geometry, panelId, required, node, rootHeight, step,
                    lineState, true, player))
            }
            if (nodeState != NodeState.LOCKED) {
                ids.addAll(spawnNodeVisual(placement, geometry, panelId, node,
                    point(node, rootHeight, step), nodeState, player))
                setSharedNodeHidden(player, panelId, node.id, true)
            }
        }
        byPanel[panelId] = ids
    }

    /** The ring on the selected node plus the detail panel beside the tree. */
    private fun refreshSelectionOverlay(player: Player, panelId: String, panel: PlacedSkillPanel, tree: SkillClassTree) {
        val byPanel = selectionOverlays.getOrPut(player.uniqueId) { HashMap() }
        byPanel.remove(panelId)?.let(::removeEntities)
        val nodeId = selections[player.uniqueId]?.get(panelId)
        val node = nodeId?.let { tree.nodes[it] }
        val world = panel.base.world
        if (node == null || world == null || world != player.world) {
            if (geometry(panel).controls != null) {
                rebuildInfoPanel(player, panelId, panel, tree)
            }
            return
        }
        val config = plugin.config
        val geometry = geometry(panel)
        val placement = placement(panel)
        val at = point(node, geometry.rootHeight, geometry.radiusStep)
        val unlocked = unlockedFor(player.uniqueId, viewedClass(player, panelId, panel))

        val ids = ArrayList<UUID>()
        val plates = geometry.plates
        if (plates == null) {
            ids.add(spawnText(placement, panelId, "personal-ring",
                miniMessage.deserialize(config.getString("skill-panel.nodes.ring-format", "<color:#f2e39b>○")!!),
                at[0], at[1], geometry.zRing, geometry.ringScale, 255.toByte(), player, null))
        } else {
            // A glyph ring would land on top of a solid plate and read as
            // noise. The selection is a larger plate sitting behind the node
            // instead, showing as a bright border all the way around it.
            ids.add(spawnPlate(placement, panelId, "personal-ring", at,
                plates.selectionWidth, plates.selectionWidth, plates.selectionDepth, plates.zSelection,
                material(config.getString("skill-panel.nodes.plates.selection.block"), Material.GOLD_BLOCK),
                config.getInt("skill-panel.nodes.plates.selection.brightness", 15), player))
        }

        if (geometry.controls == null) {
            // The standard variant keeps its detail panel beside the tree;
            // the big variant's Info area takes that role instead.
            ids.add(spawnDetail(placement, geometry, panelId, nodeDetailContent(node, unlocked), player))
        }
        byPanel[panelId] = ids
        if (geometry.controls != null) {
            rebuildInfoPanel(player, panelId, panel, tree)
        }
    }

    private fun removeOverlays(playerId: UUID, panelId: String, player: Player?) {
        carouselOverlays[playerId]?.remove(panelId)?.let(::removeEntities)
        infoOverlays[playerId]?.remove(panelId)?.let(::removeEntities)
        val state = stateOverlays[playerId]
        if (state != null) {
            val ids = state.remove(panelId)
            if (ids != null) {
                removeEntities(ids)
                if (player != null) {
                    showAllSharedNodes(player, panelId)
                }
            }
        }
        selectionOverlays[playerId]?.remove(panelId)?.let(::removeEntities)
        pointsOverlays[playerId]?.remove(panelId)?.let(::removeEntities)
    }

    // ------------------------------------------------------------------
    //  Spawning
    // ------------------------------------------------------------------

    private fun geometry(panel: PlacedSkillPanel): SkillPanelGeometry =
        SkillPanelGeometry.of(panel.variant, plugin.config)

    private fun placement(panel: PlacedSkillPanel): Placement {
        val yaw = panel.base.yaw +
            (if (plugin.config.getBoolean("skill-panel.flip-facing", false)) 180.0F else 0.0F)
        val radians = Math.toRadians(yaw.toDouble())
        val facing = Vector(-sin(radians), 0.0, cos(radians))
        val rightward = Vector(facing.z, 0.0, -facing.x)
        return Placement(panel.base.world!!, panel.base, yaw, facing, rightward,
            plugin.config.getInt("skill-panel.brightness", 15).coerceIn(0, 15))
    }

    /**
     * One node, in whichever style the variant uses: a single font glyph, or
     * a raised plate built from a wide base and a smaller, thicker top.
     */
    private fun spawnNodeVisual(placement: Placement, geometry: SkillPanelGeometry, id: String,
                                node: SkillNode, at: DoubleArray, state: NodeState, owner: Player?): List<UUID> {
        val plates = geometry.plates
            ?: return listOf(spawnNode(placement, geometry, id, node, at, state, owner))
        val config = plugin.config
        val path = "skill-panel.nodes.plates.${state.name.lowercase(Locale.ROOT)}."
        val prefix = if (owner == null) "node-" else "personal-node-"
        // Base first and furthest back: it is the rim showing around the top.
        return listOf(
            spawnPlate(placement, id, prefix + "base-" + node.id, at,
                plates.baseWidth, plates.baseWidth, plates.baseDepth, plates.zBase,
                material(config.getString(path + "base-block"), defaultPlateBlock(state, true)),
                config.getInt(path + "base-brightness", defaultPlateBrightness(state, true)), owner),
            spawnPlate(placement, id, prefix + "top-" + node.id, at,
                plates.topWidth, plates.topWidth, plates.topDepth, plates.zTop,
                material(config.getString(path + "top-block"), defaultPlateBlock(state, false)),
                config.getInt(path + "top-brightness", defaultPlateBrightness(state, false)), owner))
    }

    /** A flat, upright slab centred on a node position, facing the viewer. */
    private fun spawnPlate(placement: Placement, id: String, role: String, at: DoubleArray,
                           width: Double, height: Double, depth: Double, z: Double,
                           material: Material, brightness: Int, owner: Player?): UUID {
        val location = placement.base.clone()
            .add(placement.rightward.clone().multiply(at[0]))
            .add(0.0, at[1], 0.0)
            .add(placement.facing.clone().multiply(z))
        location.yaw = placement.yaw
        location.pitch = 0.0F
        val light = brightness.coerceIn(0, 15)
        val viewRange = plugin.config.getDouble("skill-panel.view-range", 2.0).toFloat()
        val display = placement.world.spawn(location, BlockDisplay::class.java) { block ->
            block.block = material.createBlockData()
            // A block display fills the unit cube from its corner; the
            // translation pulls it back by half its size so the node position
            // is its centre.
            block.transformation = Transformation(
                Vector3f((-width / 2.0).toFloat(), (-height / 2.0).toFloat(), (-depth / 2.0).toFloat()),
                Quaternionf(),
                Vector3f(width.toFloat(), height.toFloat(), depth.toFloat()),
                Quaternionf())
            block.brightness = Display.Brightness(light, light)
            block.viewRange = viewRange
            block.isPersistent = false
            block.isInvulnerable = true
            if (owner != null) {
                block.isVisibleByDefault = false
            }
            tag(block, id, role)
        }
        if (owner != null) {
            owner.showEntity(plugin, display)
        }
        return display.uniqueId
    }

    /** Hides or restores one shared node for a single player. */
    private fun setSharedNodeHidden(player: Player, panelId: String, nodeId: String, hidden: Boolean) {
        for (id in (sharedNodes[panelId]?.get(nodeId) ?: emptyList())) {
            val entity = Bukkit.getEntity(id) ?: continue
            if (hidden) {
                player.hideEntity(plugin, entity)
            } else {
                player.showEntity(plugin, entity)
            }
        }
    }

    private fun showAllSharedNodes(player: Player, panelId: String) {
        for (nodeId in (sharedNodes[panelId]?.keys ?: emptySet())) {
            setSharedNodeHidden(player, panelId, nodeId, false)
        }
    }

    private fun spawnNode(placement: Placement, geometry: SkillPanelGeometry, id: String, node: SkillNode,
                          at: DoubleArray, state: NodeState, owner: Player?): UUID {
        val config = plugin.config
        val path = "skill-panel.nodes." + state.name.lowercase(Locale.ROOT)
        val fallback = when (state) {
            NodeState.UNLOCKED -> "<color:#f2e39b>●"
            NodeState.AVAILABLE -> "<color:#c9a227>●"
            NodeState.LOCKED -> "<color:#6b6353>●"
        }
        val fallbackOpacity = when (state) {
            NodeState.UNLOCKED -> 255
            NodeState.AVAILABLE -> 230
            NodeState.LOCKED -> 140
        }
        val opacity = config.getInt("$path-opacity", fallbackOpacity).coerceIn(26, 255).toByte()
        val z = if (owner == null) geometry.zNode else geometry.zOverlayNode
        return spawnText(placement, id, if (owner == null) "node-${node.id}" else "personal-node-${node.id}",
            miniMessage.deserialize(config.getString("$path-format", fallback)!!),
            at[0], at[1], z, geometry.nodeScale, opacity, owner, null)
    }

    /**
     * The click surface over one node, comfortably larger than the dot it
     * covers. Overlap with a neighbour is harmless: the click is re-resolved
     * to the node nearest the player's aim anyway.
     */
    private fun spawnNodeHitbox(placement: Placement, geometry: SkillPanelGeometry, id: String,
                                node: SkillNode, at: DoubleArray): UUID =
        spawnHitboxAt(placement, id, "hit-node-${node.id}", at[0], at[1],
            geometry.hitboxWidth, geometry.hitboxHeight, geometry.zHitbox)

    /** An invisible clickable box centred on a panel-plane position. */
    private fun spawnHitboxAt(placement: Placement, id: String, role: String, x: Double, y: Double,
                              width: Double, height: Double, z: Double): UUID {
        val location = placement.base.clone()
            .add(placement.rightward.clone().multiply(x))
            .add(0.0, y - height / 2.0, 0.0)
            .add(placement.facing.clone().multiply(z))
        location.yaw = placement.yaw
        location.pitch = 0.0F
        val hitbox = placement.world.spawn(location, Interaction::class.java) { interaction ->
            interaction.interactionWidth = width.toFloat()
            interaction.interactionHeight = height.toFloat()
            interaction.isResponsive = true
            interaction.isPersistent = false
            interaction.isInvulnerable = true
            tag(interaction, id, role)
        }
        return hitbox.uniqueId
    }

    /**
     * One connection, possibly bent: the required node, any route corner
     * points, then the dependent node, drawn as one bar per leg. Only the
     * outermost legs are trimmed back to a plate rim - corners meet fully.
     */
    private fun spawnEdge(placement: Placement, geometry: SkillPanelGeometry, id: String,
                          from: SkillNode, to: SkillNode, rootHeight: Double, step: Double,
                          state: NodeState, overlay: Boolean, owner: Player?): List<UUID> {
        val points = ArrayList<DoubleArray>()
        points.add(point(from, rootHeight, step))
        for (corner in (to.routes[from.id] ?: emptyList())) {
            points.add(doubleArrayOf(corner[0] * step, rootHeight + corner[1] * step))
        }
        points.add(point(to, rootHeight, step))
        val ids = ArrayList<UUID>()
        for (leg in 0 until points.size - 1) {
            ids.add(spawnLine(placement, geometry, id, points[leg], points[leg + 1], state, overlay, owner,
                leg == 0, leg == points.size - 2))
        }
        return ids
    }

    /**
     * One connection bar. Overlay bars are slightly thicker than the base bar
     * they cover, so their faces sit outside it instead of z-fighting.
     */
    private fun spawnLine(placement: Placement, geometry: SkillPanelGeometry, id: String,
                          fromIn: DoubleArray, toIn: DoubleArray, state: NodeState, overlay: Boolean,
                          owner: Player?, trimStart: Boolean, trimEnd: Boolean): UUID {
        val config = plugin.config
        val path = "skill-panel.lines." + state.name.lowercase(Locale.ROOT)
        val fallback = when (state) {
            NodeState.UNLOCKED -> Material.GOLD_BLOCK
            NodeState.AVAILABLE -> Material.SMOOTH_QUARTZ
            NodeState.LOCKED -> Material.GRAY_CONCRETE
        }
        val fallbackBrightness = when (state) {
            NodeState.UNLOCKED -> 15
            NodeState.AVAILABLE -> 11
            NodeState.LOCKED -> 5
        }
        val material = material(config.getString("$path.block"), fallback)
        val brightness = config.getInt("$path.brightness", fallbackBrightness).coerceIn(0, 15)

        var from = fromIn
        var to = toIn
        var dx = to[0] - from[0]
        var dy = to[1] - from[1]
        var length = hypot(dx, dy)
        val plates = geometry.plates
        if (plates != null && length > 0.0 && (trimStart || trimEnd)) {
            // Plates stand in front of the lines, so a bar reaching a plate
            // centre would vanish under it. Only plate ends are trimmed; a
            // route corner is not a plate and meets its neighbour fully.
            val unitX = dx / length
            val unitY = dy / length
            val inset = minOf(plates.edgeInset(unitX, unitY), length / 2.0 - 0.02)
            if (inset > 0.0) {
                if (trimStart) {
                    from = doubleArrayOf(from[0] + unitX * inset, from[1] + unitY * inset)
                }
                if (trimEnd) {
                    to = doubleArrayOf(to[0] - unitX * inset, to[1] - unitY * inset)
                }
                dx = to[0] - from[0]
                dy = to[1] - from[1]
                length = hypot(dx, dy)
            }
        }
        // Fixed after any trimming, so the lambda below can close over it.
        val barLength = length
        val angle = atan2(dy, dx) * (if (config.getBoolean("skill-panel.lines.mirror", false)) -1.0 else 1.0)
        val thickness = (geometry.lineThickness * (if (overlay) geometry.overlayThicknessFactor else 1.0)).toFloat()
        val depth = geometry.lineDepth.toFloat()
        val z = if (overlay) geometry.zOverlayLine else geometry.zBaseLine

        val at = placement.base.clone()
            .add(placement.rightward.clone().multiply((from[0] + to[0]) / 2.0))
            .add(0.0, (from[1] + to[1]) / 2.0, 0.0)
            .add(placement.facing.clone().multiply(z))
        at.yaw = placement.yaw
        at.pitch = 0.0F
        val rotation = Quaternionf().rotateZ(angle.toFloat())
        val pivot = rotation.transform(Vector3f((barLength / 2.0).toFloat(), thickness / 2.0F, depth / 2.0F))
        val viewRange = config.getDouble("skill-panel.view-range", 2.0).toFloat()
        val display = placement.world.spawn(at, BlockDisplay::class.java) { block ->
            block.block = material.createBlockData()
            block.transformation = Transformation(Vector3f(-pivot.x, -pivot.y, -pivot.z), rotation,
                Vector3f(barLength.toFloat(), thickness, depth), Quaternionf())
            block.brightness = Display.Brightness(brightness, brightness)
            block.viewRange = viewRange
            block.isPersistent = false
            block.isInvulnerable = true
            if (owner != null) {
                block.isVisibleByDefault = false
            }
            tag(block, id, if (owner == null) "line" else "personal-line")
        }
        if (owner != null) {
            owner.showEntity(plugin, display)
        }
        return display.uniqueId
    }

    /** The detail panel: name, cost, description and level beside the tree. */
    private fun spawnDetail(placement: Placement, geometry: SkillPanelGeometry, panelId: String,
                            content: Component, owner: Player): UUID =
        spawnPanelText(placement, geometry, panelId, content, owner, geometry.detailX, "personal-detail")

    /** A wrapped, backed text panel beside the tree, shown to one player. */
    private fun spawnPanelText(placement: Placement, geometry: SkillPanelGeometry, panelId: String,
                               content: Component, owner: Player, x: Double, role: String): UUID {
        val config = plugin.config
        val scale = geometry.detailScale
        val at = placement.base.clone()
            .add(placement.rightward.clone().multiply(x))
            .add(0.0, geometry.detailHeight, 0.0)
            .add(placement.facing.clone().multiply(geometry.zNode))
        at.yaw = placement.yaw
        at.pitch = 0.0F
        val display = placement.world.spawn(at, TextDisplay::class.java) { text ->
            text.text(content)
            text.billboard = Display.Billboard.FIXED
            text.alignment = TextDisplay.TextAlignment.LEFT
            text.lineWidth = geometry.detailLineWidth
            text.transformation = Transformation(Vector3f(), Quaternionf(),
                Vector3f(scale, scale, scale), Quaternionf())
            text.isShadowed = false
            text.isSeeThrough = false
            text.backgroundColor = argb(config.getString("skill-panel.detail.background", "A0140F05"))
            text.brightness = Display.Brightness(placement.brightness, placement.brightness)
            text.viewRange = config.getDouble("skill-panel.view-range", 2.0).toFloat()
            text.isPersistent = false
            text.isInvulnerable = true
            text.isVisibleByDefault = false
            tag(text, panelId, role)
        }
        owner.showEntity(plugin, display)
        return display.uniqueId
    }

    private fun spawnText(placement: Placement, id: String, role: String, content: Component,
                          x: Double, y: Double, z: Double, scale: Float, opacity: Byte, owner: Player?,
                          background: Color?): UUID {
        val at = placement.base.clone()
            .add(placement.rightward.clone().multiply(x))
            // The same half-line correction the difficulty panel needed: a
            // text display draws its line upwards from its position.
            .add(0.0, y - GLYPH_CENTRE_PIXELS * scale / 40.0, 0.0)
            .add(placement.facing.clone().multiply(z))
        at.yaw = placement.yaw
        at.pitch = 0.0F
        val display = placement.world.spawn(at, TextDisplay::class.java) { text ->
            text.text(content)
            text.billboard = Display.Billboard.FIXED
            text.transformation = Transformation(Vector3f(), Quaternionf(),
                Vector3f(scale, scale, scale), Quaternionf())
            text.textOpacity = opacity
            text.isShadowed = false
            text.isSeeThrough = false
            text.lineWidth = 400
            text.backgroundColor = background ?: Color.fromARGB(0)
            text.brightness = Display.Brightness(placement.brightness, placement.brightness)
            text.viewRange = plugin.config.getDouble("skill-panel.view-range", 2.0).toFloat()
            text.isPersistent = false
            text.isInvulnerable = true
            if (owner != null) {
                text.isVisibleByDefault = false
            }
            tag(text, id, role)
        }
        if (owner != null) {
            owner.showEntity(plugin, display)
        }
        return display.uniqueId
    }

    private fun playSound(player: Player, key: String) {
        val raw = plugin.config.getString("skill-panel.sounds.$key", "UI_BUTTON_CLICK")
        val sound = if (raw.isNullOrBlank()) null
            else Registry.SOUNDS.get(NamespacedKey.minecraft(raw.lowercase(Locale.ROOT).replace('_', '.')))
        if (sound != null) {
            player.playSound(player.location, sound, 0.6F, 1.3F)
        }
    }

    private fun tag(entity: Entity, panelId: String, role: String) {
        entity.persistentDataContainer.set(plugin.skillPanelIdKey, PersistentDataType.STRING, panelId)
        entity.persistentDataContainer.set(plugin.skillPanelRoleKey, PersistentDataType.STRING, role)
    }

    private fun clearEntities(id: String, base: Location) {
        spawned.remove(id)?.let(::removeEntities)
        val world = base.world ?: return
        val reach = maxOf(8.0, plugin.config.getDouble("skill-panel.cleanup-radius", 14.0))
        for (chunkX in ((base.x - reach).toInt() shr 4)..((base.x + reach).toInt() shr 4)) {
            for (chunkZ in ((base.z - reach).toInt() shr 4)..((base.z + reach).toInt() shr 4)) {
                world.getChunkAt(chunkX, chunkZ)
            }
        }
        for (entity in world.getNearbyEntities(base, reach, reach, reach)) {
            val entityPanel = panelId(entity)
            if (entityPanel != null && (entityPanel == id || entityPanel !in panels)) {
                entity.remove()
            }
        }
    }

    private fun panelId(entity: Entity): String? =
        entity.persistentDataContainer.get(plugin.skillPanelIdKey, PersistentDataType.STRING)

    private fun save() {
        val storage = YamlConfiguration()
        for ((id, panel) in panels) {
            val path = "panels.$id"
            val base = panel.base
            storage.set("$path.world", base.world!!.name)
            storage.set("$path.x", base.x)
            storage.set("$path.y", base.y)
            storage.set("$path.z", base.z)
            storage.set("$path.yaw", base.yaw)
            storage.set("$path.class", panel.classId)
            storage.set("$path.variant", panel.variant.configName())
        }
        try {
            storage.save(storageFile)
        } catch (exception: IOException) {
            plugin.logger.severe("Could not save $STORAGE: ${exception.message}")
        }
    }

    // ------------------------------------------------------------------

    private data class PlacedSkillPanel(val base: Location, val classId: String, val variant: SkillPanelGeometry.Variant)

    private data class Placement(val world: World, val base: Location, val yaw: Float,
                                 val facing: Vector, val rightward: Vector, val brightness: Int)

    data class PanelInfo(val id: String, val classId: String, val variant: String, val location: Location)

    data class RemovalReport(val count: Int, val locations: List<Location>)

    enum class TestResult {
        UNLOCKED, NO_PANEL, UNKNOWN_NODE
    }

    companion object {
        private const val STORAGE = "skill-panels.yml"

        /** The vertical centring correction measured on the difficulty panel. */
        private const val GLYPH_CENTRE_PIXELS = 4.5

        /** x offset, scale and opacity for one carousel step from the centre. */
        private fun carouselPose(controls: SkillPanelGeometry.Controls, step: Int): DoubleArray {
            val magnitude = abs(step)
            if (magnitude == 0) {
                return doubleArrayOf(0.0, controls.centreScale.toDouble(), 255.0)
            }
            if (magnitude == 1) {
                return doubleArrayOf(step * controls.carouselSpacing, controls.sideScale.toDouble(),
                    controls.sideOpacity.toDouble())
            }
            // Off the edge: shrunk away rather than removed, so it can slide
            // back.
            return doubleArrayOf(step * controls.carouselSpacing, 0.05, 26.0)
        }

        /** Grid units to panel-plane blocks: x rightward, y up from the root. */
        private fun point(node: SkillNode, rootHeight: Double, step: Double): DoubleArray =
            doubleArrayOf(node.unitX * step, rootHeight + node.unitY * step)

        /**
         * The rim is a duller relative of the face rather than a different
         * hue, so a plate reads as one object with a border. Unlocked and
         * available share their blocks and differ only in brightness, which
         * is the same ladder the rest of the panel uses: hue separates locked
         * from the rest, lightness separates unlocked from available.
         */
        private fun defaultPlateBlock(state: NodeState, base: Boolean): Material = when (state) {
            NodeState.UNLOCKED, NodeState.AVAILABLE -> if (base) Material.RAW_GOLD_BLOCK else Material.GOLD_BLOCK
            NodeState.LOCKED -> if (base) Material.DEEPSLATE_TILES else Material.GRAY_CONCRETE
        }

        private fun defaultPlateBrightness(state: NodeState, base: Boolean): Int = when (state) {
            NodeState.UNLOCKED -> if (base) 11 else 15
            NodeState.AVAILABLE -> if (base) 5 else 8
            NodeState.LOCKED -> if (base) 3 else 6
        }

        private fun removeEntities(ids: List<UUID>) {
            for (id in ids) {
                Bukkit.getEntity(id)?.remove()
            }
        }

        private fun material(raw: String?, fallback: Material): Material {
            val material = raw?.let { Material.matchMaterial(it.uppercase(Locale.ROOT)) }
            return if (material == null || !material.isBlock) fallback else material
        }

        private fun argb(raw: String?): Color = try {
            val hex = (raw ?: "").replace("#", "").trim()
            val value = hex.toLong(16)
            if (hex.length > 6) Color.fromARGB(value.toInt()) else Color.fromRGB(value.toInt())
        } catch (ignored: NumberFormatException) {
            Color.fromARGB(0xA0140F05.toInt())
        }
    }
}
