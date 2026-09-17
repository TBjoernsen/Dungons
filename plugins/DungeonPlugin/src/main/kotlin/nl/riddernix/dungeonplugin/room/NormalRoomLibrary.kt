package nl.riddernix.dungeonplugin.room

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.extent.transform.BlockTransformExtent
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.math.transform.AffineTransform
import com.sk89q.worldedit.world.block.BlockState
import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.generation.BlockListOperation
import nl.riddernix.dungeonplugin.generation.Bounds
import nl.riddernix.dungeonplugin.generation.BuildOperation
import nl.riddernix.dungeonplugin.generation.CatalogueRoom
import nl.riddernix.dungeonplugin.generation.DungeonLayout
import nl.riddernix.dungeonplugin.generation.PlaceholderShell
import nl.riddernix.dungeonplugin.generation.PlaceholderPlacement
import nl.riddernix.dungeonplugin.generation.RoomPlacement
import nl.riddernix.dungeonplugin.generation.TemplateGeometry
import nl.riddernix.dungeonplugin.generation.TemplatePlan
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.sign.Side
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.util.BlockVector
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.EnumMap
import java.util.Locale
import java.util.regex.Pattern
import net.kyori.adventure.text.Component

/**
 * Loads room prefabs through WorldEdit without ever using a WorldEdit edit
 * session; the plugin's own cursor builder places every block. Files belong
 * to role pools by filename prefix (spawn, link, combat, rest, great_hall,
 * boss, parkour, key), and each pool's files must exactly match the size
 * class the template assigns to that pool.
 *
 * The old door-pattern matching (normal_straight, branch_corner_l, ...) is
 * gone: rooms are selected by pool during template planning and connected on
 * their own door markers. Legacy normal_/branch_ files still load into the
 * combat pool - their pattern suffix just stops meaning anything.
 */
class NormalRoomLibrary(private val plugin: DungeonPlugin) {

    private val folder = File(plugin.dataFolder, "rooms")
    private var prefabs: List<Prefab> = emptyList()
    private var inspections: List<Inspection> = emptyList()
    private var worldEditAvailable = false

    /** Reloads the folder, preserving a report for every room file including failures. */
    fun reload() {
        if (!folder.isDirectory && !folder.mkdirs()) {
            plugin.logger.severe("Could not create room folder: ${folder.absolutePath}")
        }
        val worldEdit = plugin.server.pluginManager.getPlugin("WorldEdit")
        worldEditAvailable = worldEdit != null && worldEdit.isEnabled
        if (!worldEditAvailable) {
            plugin.logger.severe("WorldEdit is missing or disabled. Room prefabs are unavailable; " +
                "every slot will receive a placeholder shell.")
            prefabs = emptyList()
            inspections = listOf(Inspection("(WorldEdit unavailable)", 0, 0, 0, "none", "none", emptyList(),
                false, "unknown", null, "not parsed", null, emptyMap(), emptyList(), emptyList(),
                listOf("WorldEdit is missing or disabled.")))
            return
        }

        val files = folder.listFiles { file: File -> file.isFile }
        if (files == null || files.isEmpty()) {
            plugin.logger.info("No room schematics found in ${folder.absolutePath}" +
                "; placeholder shells will stand in for every slot.")
            prefabs = emptyList()
            inspections = emptyList()
            return
        }
        val poolClasses = plugin.templates.poolClasses()
        val ordered = files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val loaded = ArrayList<Prefab>()
        val report = ArrayList<Inspection>()
        for (file in ordered) {
            val result = load(file, poolClasses)
            report.add(result.inspection)
            if (result.prefab != null) {
                loaded.add(result.prefab)
                if (result.inspection.problems.isNotEmpty()) {
                    plugin.logger.warning("Room prefab ${file.name} loaded, but ${result.inspection.displayProblems()}")
                }
            } else {
                plugin.logger.warning("Rejected room prefab ${file.name}: ${result.inspection.displayProblems()}")
            }
            result.inspection.renameHint?.let(plugin.logger::info)
        }
        prefabs = loaded.toList()
        inspections = report.toList()
        plugin.logger.info("Loaded ${prefabs.size} of ${files.size} room schematic(s) from ${folder.absolutePath}.")
    }

    fun folder(): File = folder

    fun inspections(): List<Inspection> = inspections

    /** The planner's view of one pool: geometry only, deterministically ordered. */
    fun catalogue(pool: String): List<CatalogueRoom> {
        val wanted = pool.lowercase(Locale.ROOT)
        return prefabs.filter { it.pool == wanted }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
            .map { prefab ->
                val doors = prefab.doorways.map { doorway ->
                    TemplateGeometry.LocalDoor(direction(doorway.facing), doorway.marker.cross(),
                        doorway.marker.y, doorway.opening.minY,
                        crossSize(doorway.opening, doorway.facing), doorway.opening.sizeY(),
                        doorway.marker.width)
                }
                CatalogueRoom(prefab.fileName, prefab.pool, prefab.width, prefab.height, prefab.depth, doors,
                    prefab.doorways.indexOfFirst { it.entrance }.takeIf { it >= 0 })
            }
    }

    /**
     * Turns a planned template into build operations and room metadata: the
     * chosen prefab per placed slot, a placeholder shell per empty slot, and
     * a fill for every unused doorway so a three-door room serving a two-door
     * slot does not open into the void.
     */
    fun buildPlan(template: TemplatePlan): RoomPlan {
        val operations = ArrayList<BuildOperation>()
        val prefabRooms = HashSet<String>()
        val markers = HashMap<String, List<DungeonMarker>>()
        val doorways = HashMap<String, List<DungeonDoorway>>()
        val doorwayMarkers = HashMap<String, List<DungeonDoorMarker>>()
        val playableBounds = HashMap<String, Bounds>()
        val playerSpawns = HashMap<String, DungeonSpecialMarker>()
        val bossSpawns = HashMap<String, DungeonSpecialMarker>()
        val traps = HashMap<String, DungeonTrap>()
        val prefabFiles = HashMap<String, String>()
        val signs = ArrayList<PlaceholderShell.Sign>()

        val byFileName = prefabs.associateBy { it.fileName }
        for ((roomId, placement) in template.placements) {
            val prefab = byFileName[placement.fileName]
            if (prefab == null) {
                // The library reloaded between planning and building; the
                // planner works from this catalogue, so this is a real bug.
                plugin.logger.severe("Planned prefab ${placement.fileName} for room $roomId" +
                    " is no longer loaded; the room will be missing entirely.")
                continue
            }
            val origin = PlacementOrigin(placement.origin.x, placement.origin.y, placement.origin.z)
            prefabRooms.add(roomId)
            prefabFiles[roomId] = prefab.fileName
            operations.add(blocks(prefab, placement.rotation, origin))
            val placedSize = rotatedDimensions(prefab.width, prefab.depth, placement.rotation)
            playableBounds[roomId] = Bounds(origin.x, origin.y, origin.z,
                origin.x + placedSize.x - 1, origin.y + prefab.height - 1, origin.z + placedSize.z - 1)

            markers[roomId] = prefab.markers.map { marker ->
                val point = rotate(marker.x, marker.z, prefab.width, prefab.depth, placement.rotation)
                DungeonMarker(marker.category, origin.x + point.x, origin.y + marker.y, origin.z + point.z)
            }

            val roomDoors = ArrayList<DungeonDoorway>()
            val roomDoorMarkers = ArrayList<DungeonDoorMarker>()
            for (index in prefab.doorways.indices) {
                val doorway = prefab.doorways[index]
                if (index in placement.sealedDoorIndexes) {
                    operations.add(sealDoorway(prefab, doorway, placement.rotation, origin))
                    continue
                }
                val point = rotate(doorway.opening.centreX(), doorway.opening.centreZ(),
                    prefab.width, prefab.depth, placement.rotation)
                val face = rotate(doorway.facing, placement.rotation)
                roomDoors.add(DungeonDoorway(origin.x + point.x, origin.y + doorway.opening.minY,
                    origin.z + point.z, face, face))
                val markerPoint = rotate(doorway.marker.centreX, doorway.marker.centreZ,
                    prefab.width, prefab.depth, placement.rotation)
                roomDoorMarkers.add(DungeonDoorMarker(origin.x + markerPoint.x, origin.y + doorway.marker.y,
                    origin.z + markerPoint.z, rotate(doorway.marker.facing, placement.rotation),
                    doorway.marker.width))
            }
            doorways[roomId] = roomDoors.toList()
            doorwayMarkers[roomId] = roomDoorMarkers.toList()

            for (marker in prefab.specialMarkers) {
                val point = rotate(marker.x, marker.z, prefab.width, prefab.depth, placement.rotation)
                val placed = DungeonSpecialMarker(marker.kind,
                    origin.x + point.x, origin.y + marker.y, origin.z + point.z)
                if (marker.kind == SpecialMarkerKind.PLAYER_SPAWN) playerSpawns[roomId] = placed
                if (marker.kind == SpecialMarkerKind.BOSS_SPAWN) bossSpawns[roomId] = placed
            }

            if (prefab.trapColumns.isNotEmpty() && prefab.pressurePlates.isNotEmpty()) {
                val columns = prefab.trapColumns.map { column ->
                    val point = rotate(column.x, column.z, prefab.width, prefab.depth, placement.rotation)
                    DungeonTrap.Column(origin.x + point.x, origin.y + column.y, origin.z + point.z)
                }
                val plates = prefab.pressurePlates.mapTo(HashSet()) { plate ->
                    val point = rotate(plate.x, plate.z, prefab.width, prefab.depth, placement.rotation)
                    BlockVector(origin.x + point.x, origin.y + plate.y, origin.z + point.z)
                }
                traps[roomId] = DungeonTrap(roomId, columns, plates)
            }
        }

        val shellMaterials = shellMaterials()
        for ((roomId, placeholder) in template.placeholders) {
            prefabRooms.add(roomId)
            prefabFiles[roomId] = "placeholder"
            val shell = PlaceholderShell.build(placeholder.sizeX, placeholder.sizeY, placeholder.sizeZ,
                placeholder.origin, placeholder.doors, shellOpeningWidth(), shellOpeningHeight(),
                shellMaterials, "${placeholder.role} (${placeholder.pool})", placeholder.highFace)
            operations.add(shell.operation)
            signs.addAll(shell.signs)
            playableBounds[roomId] = Bounds(placeholder.origin.x, placeholder.origin.y, placeholder.origin.z,
                placeholder.origin.x + placeholder.sizeX - 1,
                placeholder.origin.y + placeholder.sizeY - 1,
                placeholder.origin.z + placeholder.sizeZ - 1)
            doorways[roomId] = placeholder.doorMarkers.map { door ->
                val face = blockFace(door.face)
                DungeonDoorway(doorwayCentreX(door), door.floor, doorwayCentreZ(door), face, face)
            }
            doorwayMarkers[roomId] = placeholder.doorMarkers.map { door ->
                DungeonDoorMarker(doorwayCentreX(door), door.markerY, doorwayCentreZ(door),
                    blockFace(door.face), door.markerWidth)
            }
            if (placeholder.playerSpawn) {
                val box = playableBounds.getValue(roomId)
                val floor = placeholder.doorMarkers.firstOrNull()?.floor ?: (box.minY + 1)
                playerSpawns[roomId] = DungeonSpecialMarker(SpecialMarkerKind.PLAYER_SPAWN,
                    box.centreX(), floor, box.centreZ())
            }
        }

        for (line in template.summary) {
            plugin.logger.info("Template: $line")
        }
        val real = template.placements.size
        val stand = template.placeholders.size
        plugin.logger.info("Template rooms for seed ${template.layout.seed}: $real real, $stand placeholder(s)." +
            (if (stand > 0) " Placeholder slots: " + template.placeholders.entries
                .joinToString(", ") { "${it.key}=${it.value.role}/${it.value.pool}" } else ""))

        return RoomPlan(prefabRooms.toSet(), operations.toList(), immutable(markers), immutable(doorways),
            immutable(doorwayMarkers), playableBounds.toMap(), playerSpawns.toMap(), bossSpawns.toMap(),
            traps.toMap(), prefabFiles.toMap(), signs.toList())
    }

    /** Writes the role labels onto the placeholder signs once the blocks exist. */
    fun applyPlaceholderSigns(world: World, plan: RoomPlan) {
        for (sign in plan.placeholderSigns) {
            val state = world.getBlockAt(sign.x, sign.y, sign.z).state
            if (state is org.bukkit.block.Sign) {
                val side = state.getSide(Side.FRONT)
                side.line(0, Component.text("v-- becomes --v"))
                side.line(1, Component.text(sign.text))
                state.isWaxed = true
                state.update(true, false)
            }
        }
    }

    /** Every pool and class the template needs, with what the folder offers - for /dungeon rooms. */
    fun coverageReport(): List<String> {
        val lines = ArrayList<String>()
        for ((role, pool, sizeClass) in plugin.templates.requirements()) {
            val matching = prefabs.filter { it.pool == pool }
            val exact = matching.filter { it.width == sizeClass.x && it.height == sizeClass.y && it.depth == sizeClass.z }
            val status = when {
                exact.isNotEmpty() -> "${exact.size} file(s): " + exact.joinToString(", ") { it.fileName }
                matching.isNotEmpty() -> "PLACEHOLDER - ${matching.size} file(s) exist but none is " +
                    sizeClass.dimensions() + ": " + matching.joinToString(", ") { "${it.fileName} ${it.width}x${it.height}x${it.depth}" }
                else -> "PLACEHOLDER - no ${pool}_* file"
            }
            lines.add("$role (pool $pool, class ${sizeClass.name} ${sizeClass.dimensions()}): $status")
        }
        return lines.toList()
    }

    /**
     * Audits the finished world, not just the plan. This catches a malformed
     * schematic that physically overwrote a passage after validation.
     */
    fun verifyGenerated(world: World, layout: DungeonLayout, plan: RoomPlan) {
        val roomsById = layout.rooms.associateBy { it.id }

        for (room in layout.rooms) {
            val portals = portals(room, layout.tunnels)
            if (room.id in plan.prefabRoomIds) {
                val prefabDoors = plan.doorways[room.id] ?: emptyList()
                for (portal in portals) {
                    val declared = prefabDoors.any { it.facing == portal.facing }
                    if (!declared) {
                        plugin.logger.severe("Dungeon doorway audit: room ${room.id} at " +
                            position(portal.doorway.centreX(), portal.doorway.minY, portal.doorway.centreZ()) +
                            " facing ${portal.facing} has a connection but the placed room has no doorway.")
                    }
                }
            }
            for (portal in portals) auditPortalBlocks(world, room, portal)
        }

        for (tunnel in layout.tunnels) {
            if (tunnel.firstRoomId !in roomsById || tunnel.secondRoomId !in roomsById) {
                plugin.logger.severe("Dungeon corridor audit: tunnel ${tunnel.firstRoomId}-" +
                    "${tunnel.secondRoomId} refers to a room that does not exist.")
            }
        }
    }

    private fun auditPortalBlocks(world: World, room: DungeonLayout.Room, portal: RoomPortal) {
        val doorway = portal.doorway
        for (y in doorway.minY..doorway.maxY) {
            for (z in doorway.minZ..doorway.maxZ) {
                for (x in doorway.minX..doorway.maxX) {
                    val material = world.getBlockAt(x, y, z).type
                    if (!world.getBlockAt(x, y, z).isPassable) {
                        plugin.logger.severe("Dungeon doorway audit: room ${room.id} at " +
                            position(x, y, z) + " facing ${portal.facing} is blocked by $material" +
                            "; the passage is not usable.")
                        return
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private fun load(file: File, poolClasses: Map<String, nl.riddernix.dungeonplugin.generation.TemplateConfig.SizeClass>): LoadResult {
        val problems = ArrayList<String>()
        val declaration = declaration(file.name, poolClasses.keys)
        if (!declaration.parsed) {
            problems.add("Filename must start with a pool name (" +
                (poolClasses.keys + setOf("spawn", "boss")).sorted().joinToString(", ") +
                ") or a legacy normal_/branch_ prefix.")
        }
        val format = ClipboardFormats.findByFile(file)
        if (format == null) {
            problems.add("WorldEdit could not detect a supported clipboard format.")
            return result(file, 0, 0, 0, null, null, emptyList(), false, declaration, null,
                emptyMap(), problems, null)
        }
        try {
            format.getReader(FileInputStream(file)).use { reader ->
                val clipboard = reader.read()
                val minimum = clipboard.region.minimumPoint
                val dimensions = clipboard.dimensions
                val width = dimensions.x()
                val height = dimensions.y()
                val depth = dimensions.z()
                val config = plugin.config
                val markerMaterials = MarkerMaterials.read(config)
                var blocks = ArrayList<PrefabBlock>()
                var markers = ArrayList<PrefabMarker>()
                var specialMarkers = ArrayList<PrefabSpecialMarker>()
                var doorwayMarkers = ArrayList<DoorMarker>()
                var trapColumns = ArrayList<PrefabPoint>()
                var pressurePlates = ArrayList<PrefabPoint>()
                val markerCounts = HashMap<String, Int>()
                var contentBounds: LocalBounds? = null
                var structuralBounds: LocalBounds? = null
                var legacyPurpleFound = false
                for (position in clipboard.region) {
                    val x = position.x() - minimum.x()
                    val y = position.y() - minimum.y()
                    val z = position.z() - minimum.z()
                    val state = clipboard.getBlock(position)
                    val data = BukkitAdapter.adapt(state)
                    val material = data.material
                    // Cyan is a temporary authoring guide. It is neither a
                    // marker nor structure content, and is never placed.
                    if (material == Material.CYAN_WOOL) {
                        markerCounts.merge("ignored-cyan", 1, Int::plus)
                        continue
                    }
                    if (!material.isAir) {
                        contentBounds = include(contentBounds, x, y, z)
                    }
                    var replacementState: BlockState? = null
                    var replacementMaterial: Material? = null
                    if (material == markerMaterials.doorway) {
                        markerCounts.merge("doorway", 1, Int::plus)
                        doorwayMarkers.add(DoorMarker(x, y, z, false))
                        if (markerMaterials.wallMatchDoorway) {
                            replacementState = sampleWallMaterial(clipboard, minimum, width, height, depth, x, y, z, material)
                            if (replacementState == null) {
                                problems.add("Doorway marker at $x,$y,$z" +
                                    " has no non-air neighbouring wall block to copy.")
                                replacementMaterial = Material.AIR
                            }
                        } else {
                            replacementMaterial = markerMaterials.doorwayReplacement
                        }
                    } else if (material == markerMaterials.entrance) {
                        // Green follows the red convention exactly, but pins
                        // the room's rotation: players must come in through it.
                        markerCounts.merge("entrance-doorway", 1, Int::plus)
                        doorwayMarkers.add(DoorMarker(x, y, z, true))
                        if (markerMaterials.wallMatchEntrance) {
                            replacementState = sampleWallMaterial(clipboard, minimum, width, height, depth, x, y, z, material)
                            if (replacementState == null) {
                                problems.add("Doorway marker (entrance) at $x,$y,$z" +
                                    " has no non-air neighbouring wall block to copy.")
                                replacementMaterial = Material.AIR
                            }
                        } else {
                            replacementMaterial = markerMaterials.entranceReplacement
                        }
                    } else if (material == markerMaterials.trapFloor) {
                        // The wool is the visible floor block that drops; it is
                        // swapped for a copy of the floor around it, so the trap
                        // cannot be read from inside the room.
                        markerCounts.merge("trap-floor", 1, Int::plus)
                        trapColumns.add(PrefabPoint(x, y, z))
                        if (markerMaterials.wallMatchTrapFloor) {
                            replacementState = sampleWallMaterial(clipboard, minimum, width, height, depth, x, y, z, material)
                            if (replacementState == null) {
                                problems.add("Trap-floor marker at $x,$y,$z" +
                                    " has no non-air neighbouring floor block to copy.")
                                replacementMaterial = Material.AIR
                            }
                        } else {
                            replacementMaterial = markerMaterials.trapFloorReplacement
                        }
                    } else if (material == Material.PURPLE_WOOL) {
                        markerCounts.merge("legacy-purple", 1, Int::plus)
                        replacementMaterial = markerMaterials.legacyPurpleReplacement
                        legacyPurpleFound = true
                    } else {
                        val special = markerMaterials.specialMarkers[material]
                        val category = markerMaterials.spawnCategories[material]
                        if (special != null) {
                            markerCounts.merge(special.configName, 1, Int::plus)
                            specialMarkers.add(PrefabSpecialMarker(special, x, y, z))
                            // Special positions become entity feet locations, so
                            // they must never remain visible or solid after build.
                            replacementMaterial = Material.AIR
                        } else if (category != null) {
                            markerCounts.merge(category, 1, Int::plus)
                            markers.add(PrefabMarker(category, x, y, z))
                            replacementMaterial = markerMaterials.spawnReplacement
                        } else if (material == Material.LIGHT_GRAY_WOOL && declaration.pool == "spawn") {
                            markerCounts.merge("incorrect-player-spawn-light-gray", 1, Int::plus)
                            problems.add("Player-spawn marker at $x,$y,$z" +
                                " uses LIGHT_GRAY_WOOL; use GRAY_WOOL instead.")
                        } else if (material.name.endsWith("_WOOL")) {
                            markerCounts.merge("unmapped-" + material.name.lowercase(Locale.ROOT), 1, Int::plus)
                            problems.add("Unmapped wool marker $material at $x,$y,$z.")
                        } else if (Tag.PRESSURE_PLATES.isTagged(material)) {
                            // Recorded for the trap system; the plate itself
                            // stays in the build exactly as authored.
                            markerCounts.merge("pressure-plate", 1, Int::plus)
                            pressurePlates.add(PrefabPoint(x, y, z))
                        }
                    }
                    if (!material.isAir && material != markerMaterials.doorway && material != markerMaterials.entrance &&
                        material != markerMaterials.trapFloor && material != Material.PURPLE_WOOL &&
                        material !in markerMaterials.spawnCategories &&
                        material !in markerMaterials.specialMarkers) {
                        structuralBounds = include(structuralBounds, x, y, z)
                    }
                    if (!material.isAir) {
                        blocks.add(PrefabBlock(x, y, z, state, replacementState, replacementMaterial))
                    }
                }
                if (legacyPurpleFound) {
                    problems.add("Found purple wool left from an older doorway convention; it is ignored as a spawn marker. Resave this room with red doorway markers.")
                }
                reportBounds(width, height, depth, contentBounds, problems)
                val markerOffsets = verticalOffsets(doorwayMarkers, structuralBounds)
                if (contentBounds == null) {
                    return result(file, width, height, depth, contentBounds, structuralBounds, markerOffsets,
                        false, declaration, null, markerCounts, problems, null)
                }

                // WorldEdit selections often include an empty border. Rebase
                // every non-air block and marker to its real extent before
                // validating or placing it, so authoring padding never changes
                // a room's footprint.
                val content = contentBounds
                val contentMinimum = minimum.add(content.minX, content.minY, content.minZ)
                val prefabWidth = content.sizeX()
                val prefabHeight = content.sizeY()
                val prefabDepth = content.sizeZ()
                blocks = blocks.mapTo(ArrayList()) { block ->
                    PrefabBlock(block.x - content.minX, block.y - content.minY, block.z - content.minZ,
                        block.state, block.replacementState, block.replacementMaterial)
                }
                markers = markers.mapTo(ArrayList()) { marker ->
                    PrefabMarker(marker.category, marker.x - content.minX, marker.y - content.minY, marker.z - content.minZ)
                }
                specialMarkers = specialMarkers.mapTo(ArrayList()) { marker ->
                    PrefabSpecialMarker(marker.kind, marker.x - content.minX, marker.y - content.minY, marker.z - content.minZ)
                }
                doorwayMarkers = doorwayMarkers.mapTo(ArrayList()) { marker ->
                    DoorMarker(marker.x - content.minX, marker.y - content.minY, marker.z - content.minZ, marker.entrance)
                }
                trapColumns = trapColumns.mapTo(ArrayList()) { point ->
                    PrefabPoint(point.x - content.minX, point.y - content.minY, point.z - content.minZ)
                }
                pressurePlates = pressurePlates.mapTo(ArrayList()) { point ->
                    PrefabPoint(point.x - content.minX, point.y - content.minY, point.z - content.minZ)
                }

                // Validation is per size class now: a room file must exactly
                // match its pool's class dimensions, loudly otherwise.
                val sizeClass = declaration.pool?.let { poolClasses[it] }
                if (declaration.parsed && sizeClass == null && declaration.pool !in setOf("spawn", "boss")) {
                    problems.add("Pool '${declaration.pool}' is not used by the generation template" +
                        "; the file can never be selected.")
                }
                if (sizeClass != null &&
                    (prefabWidth != sizeClass.x || prefabHeight != sizeClass.y || prefabDepth != sizeClass.z)) {
                    problems.add("Size ${prefabWidth}x${prefabHeight}x$prefabDepth does not match class " +
                        "${sizeClass.name} (${sizeClass.dimensions()}) required for pool '${declaration.pool}'.")
                }

                val doorwayGroups = doorwayGroupDescriptions(doorwayMarkers, prefabWidth, prefabDepth)
                if (doorwayMarkers.isEmpty()) {
                    problems.add("No red doorway marker blocks were found.")
                }
                val doors = parseDoorways(clipboard, contentMinimum, prefabWidth, prefabHeight, prefabDepth,
                    doorwayMarkers, config, problems)
                if (doorwayMarkers.isNotEmpty() && doors.isEmpty() &&
                    problems.none { it.startsWith("Doorway") }) {
                    problems.add("No valid doorway marker group could be created from the red doorway markers.")
                }
                val faces = HashSet<BlockFace>()
                for (door in doors) {
                    if (!faces.add(door.facing)) problems.add("Doorway markers produce more than one doorway on the ${door.facing} wall.")
                }
                validateSpecialMarkers(declaration.pool, specialMarkers, clipboard, contentMinimum, problems)
                if (trapColumns.isNotEmpty()) {
                    // Counted with the runtime's own rule, so the number here
                    // is what will actually vanish - the point is spotting a
                    // column that grabbed more than intended before testing in
                    // game.
                    markerCounts["trap-blocks"] = trapBlockCount(trapColumns, blocks)
                }
                if (trapColumns.isNotEmpty() && pressurePlates.isEmpty()) {
                    // Deliberately non-fatal: the room still places, the trap
                    // simply never arms, and this line says why.
                    problems.add("Trap-floor markers found but no pressure plate; the trap can never fire.")
                }
                val specialMarkerError = problems.any {
                    it.startsWith("Spawn room") || it.startsWith("Boss room") ||
                        it.startsWith("Player-spawn") || it.startsWith("Boss-spawn")
                }
                val sizeError = problems.any { it.startsWith("Size ") || it.startsWith("Pool '") }
                val valid = declaration.parsed && doors.isNotEmpty() && doors.size == faces.size &&
                    !specialMarkerError && !sizeError && problems.none { it.startsWith("Doorway") }
                val floors = doors.map { it.opening.minY }.distinct()
                val prefab = if (valid) Prefab(file.name, declaration.pool!!, declaration.variant,
                    prefabWidth, prefabHeight, prefabDepth, blocks, markers, specialMarkers, doors,
                    trapColumns, pressurePlates) else null
                if (valid && declaration.variant == "stairs" && floors.size < 2) {
                    problems.add("Filename declares a stairs variant but every doorway floor sits at one height.")
                }
                return result(file, prefabWidth, prefabHeight, prefabDepth, contentBounds, structuralBounds,
                    markerOffsets, valid, declaration, sizeClass?.name, markerCounts, problems, prefab,
                    doorwayGroups, specialMarkers)
            }
        } catch (exception: IOException) {
            problems.add("Could not read schematic: ${exception.message}")
            plugin.logger.warning("Could not load room prefab ${file.name}: ${exception.message}")
            return result(file, 0, 0, 0, null, null, emptyList(), false, declaration, null,
                emptyMap(), problems, null)
        } catch (exception: RuntimeException) {
            problems.add("Could not read schematic: ${exception.message}")
            plugin.logger.warning("Could not load room prefab ${file.name}: ${exception.message}")
            return result(file, 0, 0, 0, null, null, emptyList(), false, declaration, null,
                emptyMap(), problems, null)
        }
    }

    /**
     * Treats one red block or a contiguous red strip as one doorway
     * declaration. The marker identifies the wall and the position along it;
     * the actual air opening is found below the strip's own centre. Strips no
     * longer need to be centred on their wall - doors align on their own
     * markers now - and doorways on one room may sit at different floor
     * heights, which is exactly what a stairs room does.
     */
    private fun parseDoorways(clipboard: Clipboard, minimum: BlockVector3, width: Int, height: Int, depth: Int,
                              markerBlocks: List<DoorMarker>, config: FileConfiguration,
                              problems: MutableList<String>): List<PrefabDoorway> {
        val byFace = EnumMap<BlockFace, MutableList<DoorMarker>>(BlockFace::class.java)
        for (marker in markerBlocks) {
            val face = wall(marker, width, depth)
            if (face == null) {
                problems.add("Doorway marker at ${marker.x},${marker.y},${marker.z}" +
                    " must be on exactly one outer wall.")
                continue
            }
            byFace.getOrPut(face) { ArrayList() }.add(marker)
        }

        val minimumOpeningWidth = maxOf(1, config.getInt("generation.rooms.markers.doorway.minimum-opening-width", 3))
        val minimumOpeningHeight = maxOf(2, config.getInt("generation.rooms.markers.doorway.minimum-opening-height", 3))
        val doors = ArrayList<PrefabDoorway>()
        for (face in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
            val group = byFace[face]
            if (group.isNullOrEmpty()) continue
            val entrance = group.first().entrance
            if (group.any { it.entrance != entrance }) {
                problems.add("Doorway markers on the $face wall mix red and green;" +
                    " a wall is either the entrance or an exit.")
                continue
            }
            group.sortBy { cross(it, face) }
            val markerY = group.first().y
            val minimumCross = cross(group.first(), face)
            val maximumCross = cross(group.last(), face)
            val sameRow = group.all { it.y == markerY }
            val contiguous = group.size == maximumCross - minimumCross + 1
            if (!sameRow || !contiguous) {
                val issues = ArrayList<String>()
                if (!sameRow) issues.add("they are on more than one height")
                if (!contiguous) issues.add("there is a gap in the strip")
                problems.add("Doorway markers on the $face wall are invalid: ${issues.joinToString(", ")}.")
                continue
            }
            val centre = (minimumCross + maximumCross) / 2
            val opening = findOpening(clipboard, minimum, width, height, depth, face, centre, markerY,
                minimumOpeningWidth, minimumOpeningHeight)
            if (opening == null) {
                problems.add("Doorway markers on the $face wall have no air opening below them that is at least " +
                    "$minimumOpeningWidth blocks wide and $minimumOpeningHeight blocks high.")
                continue
            }
            doors.add(PrefabDoorway(opening, face, entrance,
                DoorMarkerGroup.of(face, markerY, minimumCross, maximumCross, width, depth)))
        }
        val entranceCount = doors.count { it.entrance }
        if (entranceCount > 1) {
            problems.add("Doorway markers declare $entranceCount green entrances; at most one is allowed.")
        }
        if (entranceCount > 0 && entranceCount == doors.size) {
            problems.add("Doorway markers declare a green entrance but no red exit doorway.")
        }
        return doors.toList()
    }

    /**
     * How many blocks a trap floor will take with it: everything below each
     * marked block down to the room's foundation, plus whatever stands on it.
     * Only non-air positions count, because only those are ever removed.
     */
    private fun trapBlockCount(columns: List<PrefabPoint>, blocks: List<PrefabBlock>): Int {
        val maximumRise = maxOf(0, plugin.config.getInt("trap.max-column-height", 8))
        val filled = HashSet<Long>()
        val wanted = HashSet<Long>()
        for (column in columns) {
            wanted.add((column.x.toLong() shl 32) or (column.z.toLong() and 0xFFFFFFFFL))
        }
        for (block in blocks) {
            if ((block.x.toLong() shl 32) or (block.z.toLong() and 0xFFFFFFFFL) in wanted) {
                filled.add(packed(block.x, block.y, block.z))
            }
        }
        var total = 0
        for (column in columns) {
            for (y in column.y downTo 0) {
                if (packed(column.x, y, column.z) in filled) total++
            }
            total += DungeonTrap.rise({ y -> packed(column.x, y, column.z) in filled },
                column.y, maximumRise)
        }
        return total
    }

    private fun blocks(prefab: Prefab, rotation: Int, origin: PlacementOrigin): BuildOperation {
        val transform = AffineTransform().rotateY(-rotation.toDouble())
        val entries = ArrayList<BlockListOperation.Entry>(prefab.blocks.size)
        for (block in prefab.blocks) {
            val point = rotate(block.x, block.z, prefab.width, prefab.depth, rotation)
            val data = when {
                block.replacementState != null ->
                    BukkitAdapter.adapt(BlockTransformExtent.transform(block.replacementState, transform))
                block.replacementMaterial != null -> block.replacementMaterial.createBlockData()
                else -> BukkitAdapter.adapt(BlockTransformExtent.transform(block.state, transform))
            }
            entries.add(BlockListOperation.Entry(origin.x + point.x,
                origin.y + block.y, origin.z + point.z, data))
        }
        return BlockListOperation(entries)
    }

    /**
     * Fills an unused doorway with the wall block its marker sampled, so a
     * room with more doors than its slot needs presents a plain wall instead
     * of an opening into the void.
     */
    private fun sealDoorway(prefab: Prefab, doorway: PrefabDoorway, rotation: Int,
                            origin: PlacementOrigin): BuildOperation {
        val sample = prefab.blocks.firstOrNull {
            it.replacementState != null && doorway.marker.contains(it.x, it.y, it.z)
        }?.replacementState
        val transform = AffineTransform().rotateY(-rotation.toDouble())
        val data = if (sample != null) BukkitAdapter.adapt(BlockTransformExtent.transform(sample, transform))
            else Material.STONE_BRICKS.createBlockData()
        val entries = ArrayList<BlockListOperation.Entry>()
        val opening = doorway.opening
        for (y in opening.minY..opening.maxY) {
            for (z in opening.minZ..opening.maxZ) {
                for (x in opening.minX..opening.maxX) {
                    val point = rotate(x, z, prefab.width, prefab.depth, rotation)
                    entries.add(BlockListOperation.Entry(origin.x + point.x, origin.y + y,
                        origin.z + point.z, data))
                }
            }
        }
        return BlockListOperation(entries)
    }

    private fun shellMaterials(): List<Material> {
        val configured = plugin.config.getStringList("generation.template.placeholder.materials")
            .mapNotNull { Material.matchMaterial(it.trim().uppercase(Locale.ROOT)) }
            .filter { it.isBlock }
        return configured.ifEmpty { listOf(Material.MAGENTA_CONCRETE, Material.BLACK_CONCRETE) }
    }

    private fun shellOpeningWidth(): Int {
        val value = maxOf(3, plugin.config.getInt("generation.rooms.markers.doorway.minimum-opening-width", 3))
        return if ((value and 1) == 0) value + 1 else value
    }

    private fun shellOpeningHeight(): Int =
        maxOf(3, plugin.config.getInt("generation.rooms.markers.doorway.minimum-opening-height", 3))

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private class Prefab(
        val fileName: String, val pool: String, val variant: String?,
        val width: Int, val height: Int, val depth: Int,
        blocks: List<PrefabBlock>, markers: List<PrefabMarker>, specialMarkers: List<PrefabSpecialMarker>,
        doorways: List<PrefabDoorway>, trapColumns: List<PrefabPoint>, pressurePlates: List<PrefabPoint>
    ) {
        val blocks: List<PrefabBlock> = blocks.toList()
        val markers: List<PrefabMarker> = markers.toList()
        val specialMarkers: List<PrefabSpecialMarker> = specialMarkers.toList()
        val doorways: List<PrefabDoorway> = doorways.toList()
        val trapColumns: List<PrefabPoint> = trapColumns.toList()
        val pressurePlates: List<PrefabPoint> = pressurePlates.toList()
    }

    private class PrefabBlock(val x: Int, val y: Int, val z: Int, val state: BlockState,
                              val replacementState: BlockState?, val replacementMaterial: Material?)

    private class PrefabMarker(val category: String, val x: Int, val y: Int, val z: Int)

    private class PrefabSpecialMarker(val kind: SpecialMarkerKind, val x: Int, val y: Int, val z: Int)

    private class PrefabPoint(val x: Int, val y: Int, val z: Int)

    private class DoorMarker(val x: Int, val y: Int, val z: Int, val entrance: Boolean)

    private data class Span(val minimum: Int, val maximum: Int) {
        fun width(): Int = maximum - minimum + 1
    }

    private class OpeningRun(val bottom: Int, val top: Int, val base: Span) {
        fun height(): Int = top - bottom + 1
    }

    private data class DoorMarkerGroup(val centreX: Int, val y: Int, val centreZ: Int, val facing: BlockFace, val width: Int) {
        /** The marker centre along its wall: an x on north/south walls, a z on east/west. */
        fun cross(): Int = if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) centreX else centreZ

        fun contains(x: Int, y: Int, z: Int): Boolean {
            if (y != this.y) return false
            val half = (width - 1) / 2
            return if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH)
                z == centreZ && x in centreX - half..centreX + half
            else x == centreX && z in centreZ - half..centreZ + half
        }

        companion object {
            fun of(face: BlockFace, y: Int, minimumCross: Int, maximumCross: Int, width: Int, depth: Int): DoorMarkerGroup {
                val centre = (minimumCross + maximumCross) / 2
                return when (face) {
                    BlockFace.NORTH -> DoorMarkerGroup(centre, y, 0, face, maximumCross - minimumCross + 1)
                    BlockFace.SOUTH -> DoorMarkerGroup(centre, y, depth - 1, face, maximumCross - minimumCross + 1)
                    BlockFace.WEST -> DoorMarkerGroup(0, y, centre, face, maximumCross - minimumCross + 1)
                    BlockFace.EAST -> DoorMarkerGroup(width - 1, y, centre, face, maximumCross - minimumCross + 1)
                    else -> throw IllegalArgumentException("Doorway markers must be cardinal.")
                }
            }
        }
    }

    private class PrefabDoorway(val opening: Bounds, val facing: BlockFace, val entrance: Boolean, val marker: DoorMarkerGroup)

    private class RoomPortal(val doorway: Bounds, val facing: BlockFace)

    private class PlacementOrigin(val x: Int, val y: Int, val z: Int)

    private data class Point(val x: Int, val z: Int)

    private class NameDeclaration(val parsed: Boolean, val pool: String?, val variant: String?,
                                  val renameHint: String?)

    private class LoadResult(val prefab: Prefab?, val inspection: Inspection)

    private class MarkerMaterials(
        val doorway: Material, val wallMatchDoorway: Boolean, val doorwayReplacement: Material,
        val entrance: Material, val wallMatchEntrance: Boolean, val entranceReplacement: Material,
        val trapFloor: Material, val wallMatchTrapFloor: Boolean, val trapFloorReplacement: Material,
        val spawnReplacement: Material, val legacyPurpleReplacement: Material,
        val spawnCategories: Map<Material, String>, val specialMarkers: Map<Material, SpecialMarkerKind>
    ) {
        companion object {
            private fun wallMatching(config: FileConfiguration, path: String): Boolean {
                val raw = config.getString(path, "WALL_MATCHING")
                return raw != null && raw.equals("WALL_MATCHING", ignoreCase = true)
            }

            fun read(config: FileConfiguration): MarkerMaterials {
                val doorway = material(config, "generation.rooms.markers.doorway.material", Material.RED_WOOL)
                val wallMatchDoorway = wallMatching(config, "generation.rooms.markers.replacements.doorway")
                val doorwayReplacement = if (wallMatchDoorway) Material.AIR else material(config,
                    "generation.rooms.markers.replacements.doorway", Material.AIR)
                val entrance = material(config, "generation.rooms.markers.entrance.material", Material.GREEN_WOOL)
                val wallMatchEntrance = wallMatching(config, "generation.rooms.markers.replacements.entrance")
                val entranceReplacement = if (wallMatchEntrance) Material.AIR else material(config,
                    "generation.rooms.markers.replacements.entrance", Material.AIR)
                val trapFloor = material(config, "generation.rooms.markers.trap-floor.material", Material.YELLOW_WOOL)
                val wallMatchTrapFloor = wallMatching(config, "generation.rooms.markers.replacements.trap-floor")
                val trapFloorReplacement = if (wallMatchTrapFloor) Material.AIR else material(config,
                    "generation.rooms.markers.replacements.trap-floor", Material.AIR)
                val spawnReplacement = material(config, "generation.rooms.markers.replacements.spawn", Material.AIR)
                val legacyPurpleReplacement = material(config, "generation.rooms.markers.replacements.legacy-purple", Material.AIR)
                val categories = HashMap<Material, String>()
                val section = config.getConfigurationSection("mobs.markers.materials")
                if (section != null) for (category in section.getKeys(false)) {
                    val marker = material(config, "mobs.markers.materials.$category", Material.WHITE_WOOL)
                    categories[marker] = category.lowercase(Locale.ROOT)
                }
                categories.remove(doorway)
                categories.remove(entrance)
                categories.remove(trapFloor)
                categories.remove(Material.PURPLE_WOOL)
                val specialMarkers = HashMap<Material, SpecialMarkerKind>()
                for (kind in SpecialMarkerKind.entries) {
                    val marker = material(config, "generation.rooms.markers.${kind.configName}.material",
                        if (kind == SpecialMarkerKind.PLAYER_SPAWN) Material.GRAY_WOOL else Material.LIGHT_BLUE_WOOL)
                    specialMarkers[marker] = kind
                }
                specialMarkers.remove(doorway)
                specialMarkers.remove(entrance)
                specialMarkers.remove(trapFloor)
                specialMarkers.remove(Material.PURPLE_WOOL)
                categories.keys.removeAll(specialMarkers.keys)
                return MarkerMaterials(doorway, wallMatchDoorway, doorwayReplacement,
                    entrance, wallMatchEntrance, entranceReplacement,
                    trapFloor, wallMatchTrapFloor, trapFloorReplacement, spawnReplacement,
                    legacyPurpleReplacement, categories.toMap(), specialMarkers.toMap())
            }
        }
    }

    /** Immutable data used by /dungeon rooms. */
    class Inspection(
        val fileName: String, val width: Int, val height: Int, val depth: Int, val actualDimensions: String,
        val trimmedDimensions: String, markerVerticalOffsets: List<Int>, val valid: Boolean, val pool: String,
        val variant: String?, val sizeClassName: String?, val renameHint: String?, markerCounts: Map<String, Int>,
        doorwayGroups: List<String>, specialMarkers: List<String>, problems: List<String>
    ) {
        val markerVerticalOffsets: List<Int> = markerVerticalOffsets.toList()
        val markerCounts: Map<String, Int> = markerCounts.toMap()
        val doorwayGroups: List<String> = doorwayGroups.toList()
        val specialMarkers: List<String> = specialMarkers.toList()
        val problems: List<String> = problems.toList()

        fun dimensions(): String = "${width}x${height}x$depth"

        /** The pool label shown by /dungeon rooms, with any variant token. */
        fun displayType(): String = pool + (variant?.let { " $it" } ?: "") +
            (sizeClassName?.let { " [class $it]" } ?: "")

        fun markers(): String = markerCounts.entries.sortedBy { it.key }
            .joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "none" }

        fun markerOffsets(): String =
            if (markerVerticalOffsets.isEmpty()) "none"
            else markerVerticalOffsets.joinToString(", ") { "y" + (if (it >= 0) "+" else "") + it }

        fun displayDoorwayGroups(): String = if (doorwayGroups.isEmpty()) "none" else doorwayGroups.joinToString("; ")

        fun corridorOffsetCompatibility(corridorOffsets: List<Int>): String {
            if (markerVerticalOffsets.isEmpty()) return "cannot compare: no room doorway-marker offset"
            if (corridorOffsets.isEmpty()) return "cannot compare: no valid corridor marker offset is loaded"
            return if (markerVerticalOffsets == corridorOffsets)
                "matches the reported structural-top convention"
            else "different structural-top convention (informational; exact red-to-purple marker matching controls placement)"
        }

        fun displaySpecialMarkers(): String = if (specialMarkers.isEmpty()) "none" else specialMarkers.joinToString(", ")

        fun displayProblems(): String = if (problems.isEmpty()) "none" else problems.joinToString(" | ")
    }

    /** Tick-spread blocks plus marker metadata for the planned rooms. */
    class RoomPlan(
        prefabRoomIds: Set<String>, operations: List<BuildOperation>,
        markers: Map<String, List<DungeonMarker>>, doorways: Map<String, List<DungeonDoorway>>,
        doorwayMarkers: Map<String, List<DungeonDoorMarker>>, playableBounds: Map<String, Bounds>,
        playerSpawns: Map<String, DungeonSpecialMarker>, bossSpawns: Map<String, DungeonSpecialMarker>,
        traps: Map<String, DungeonTrap>, prefabFiles: Map<String, String>,
        placeholderSigns: List<PlaceholderShell.Sign>
    ) {
        val prefabRoomIds: Set<String> = prefabRoomIds.toSet()
        val operations: List<BuildOperation> = operations.toList()
        val markers: Map<String, List<DungeonMarker>> = markers.toMap()
        val doorways: Map<String, List<DungeonDoorway>> = doorways.toMap()
        val doorwayMarkers: Map<String, List<DungeonDoorMarker>> = doorwayMarkers.toMap()
        val playableBounds: Map<String, Bounds> = playableBounds.toMap()
        val playerSpawns: Map<String, DungeonSpecialMarker> = playerSpawns.toMap()
        val bossSpawns: Map<String, DungeonSpecialMarker> = bossSpawns.toMap()
        val traps: Map<String, DungeonTrap> = traps.toMap()
        val prefabFiles: Map<String, String> = prefabFiles.toMap()
        val placeholderSigns: List<PlaceholderShell.Sign> = placeholderSigns.toList()
    }

    private data class LocalBounds(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {
        fun include(x: Int, y: Int, z: Int): LocalBounds = LocalBounds(minOf(minX, x), minOf(minY, y), minOf(minZ, z),
            maxOf(maxX, x), maxOf(maxY, y), maxOf(maxZ, z))
        fun sizeX(): Int = maxX - minX + 1
        fun sizeY(): Int = maxY - minY + 1
        fun sizeZ(): Int = maxZ - minZ + 1
        fun dimensions(): String = "${sizeX()}x${sizeY()}x${sizeZ()}"
        fun trimDescription(statedWidth: Int, statedHeight: Int, statedDepth: Int): String =
            dimensions() + " (margins x $minX/${statedWidth - 1 - maxX}" +
                ", y $minY/${statedHeight - 1 - maxY}" +
                ", z $minZ/${statedDepth - 1 - maxZ})"
    }

    companion object {
        private val SPECIAL_FILE_NAME = Pattern.compile("^(spawn|boss)(?:_\\d+)?$", Pattern.CASE_INSENSITIVE)

        private fun portals(room: DungeonLayout.Room, tunnels: List<DungeonLayout.Tunnel>): List<RoomPortal> {
            val portals = ArrayList<RoomPortal>()
            for (tunnel in tunnels) {
                if (tunnel.firstRoomId == room.id) {
                    portals.add(RoomPortal(tunnel.firstDoorway, face(room.bounds, tunnel.firstDoorway)))
                } else if (tunnel.secondRoomId == room.id) {
                    portals.add(RoomPortal(tunnel.secondDoorway, face(room.bounds, tunnel.secondDoorway)))
                }
            }
            return portals.toList()
        }

        private fun position(x: Int, y: Int, z: Int): String = "$x,$y,$z"

        private fun validateSpecialMarkers(pool: String?, markers: List<PrefabSpecialMarker>, clipboard: Clipboard,
                                           minimum: BlockVector3, problems: MutableList<String>) {
            val playerSpawns = markers.count { it.kind == SpecialMarkerKind.PLAYER_SPAWN }
            val bossSpawns = markers.count { it.kind == SpecialMarkerKind.BOSS_SPAWN }
            if (pool == "spawn") {
                if (playerSpawns == 0) problems.add("Spawn room requires exactly one player-spawn marker, but found none.")
                if (playerSpawns > 1) problems.add("Spawn room requires exactly one player-spawn marker, but found $playerSpawns.")
            }
            if (pool == "boss") {
                if (bossSpawns > 1) problems.add("Boss room may contain at most one optional boss-spawn marker, but found $bossSpawns.")
            }
            for (marker in markers) {
                val solidBelow = marker.y > 0 && BukkitAdapter.adapt(clipboard.getBlock(minimum.add(marker.x, marker.y - 1, marker.z)))
                    .material.isSolid
                if (!solidBelow) {
                    val problem = if (marker.kind == SpecialMarkerKind.PLAYER_SPAWN)
                        "Player-spawn marker at ${marker.x},${marker.y},${marker.z} needs solid ground directly below it."
                    else
                        "Boss-spawn marker at ${marker.x},${marker.y},${marker.z} needs solid ground directly below it."
                    problems.add(problem)
                }
            }
        }

        /** A human-readable summary keeps /dungeon rooms useful even when validation rejects a strip. */
        private fun doorwayGroupDescriptions(markers: List<DoorMarker>, width: Int, depth: Int): List<String> {
            if (markers.isEmpty()) return emptyList()
            val byFace = EnumMap<BlockFace, MutableList<DoorMarker>>(BlockFace::class.java)
            val descriptions = ArrayList<String>()
            for (marker in markers) {
                val face = wall(marker, width, depth)
                if (face == null) {
                    descriptions.add("unattached marker=${marker.x},${marker.y},${marker.z}" +
                        " (not on exactly one outer wall)")
                    continue
                }
                byFace.getOrPut(face) { ArrayList() }.add(marker)
            }
            for (face in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
                val group = byFace[face]
                if (group.isNullOrEmpty()) continue
                group.sortBy { cross(it, face) }
                val markerY = group.first().y
                val minimumCross = cross(group.first(), face)
                val maximumCross = cross(group.last(), face)
                val sameRow = group.all { it.y == markerY }
                val contiguous = group.size == maximumCross - minimumCross + 1
                val centre = (minimumCross + maximumCross) / 2
                val centreX = if (face == BlockFace.NORTH || face == BlockFace.SOUTH) centre
                    else if (face == BlockFace.WEST) 0 else width - 1
                val centreZ = if (face == BlockFace.NORTH) 0 else if (face == BlockFace.SOUTH) depth - 1 else centre
                val status = ArrayList<String>()
                val greens = group.count { it.entrance }
                if (greens == group.size) status.add("entrance")
                else if (greens > 0) status.add("MIXED red/green")
                status.add(if (sameRow) "one height" else "mixed heights")
                status.add(if (contiguous) "contiguous" else "gap in strip")
                descriptions.add("$face width=${group.size} centre=$centreX," +
                    (if (sameRow) markerY.toString() else "mixed") + ",$centreZ" +
                    " facing=$face (${status.joinToString(", ")})")
            }
            return descriptions.toList()
        }

        private fun wall(marker: DoorMarker, width: Int, depth: Int): BlockFace? {
            val walls = (if (marker.x == 0) 1 else 0) + (if (marker.x == width - 1) 1 else 0) +
                (if (marker.z == 0) 1 else 0) + (if (marker.z == depth - 1) 1 else 0)
            if (walls != 1) return null
            if (marker.x == 0) return BlockFace.WEST
            if (marker.x == width - 1) return BlockFace.EAST
            return if (marker.z == 0) BlockFace.NORTH else BlockFace.SOUTH
        }

        private fun cross(marker: DoorMarker, face: BlockFace): Int =
            if (face == BlockFace.NORTH || face == BlockFace.SOUTH) marker.x else marker.z

        private fun findOpening(clipboard: Clipboard, minimum: BlockVector3, width: Int, height: Int, depth: Int,
                                face: BlockFace, centre: Int, markerY: Int, minimumWidth: Int, minimumHeight: Int): Bounds? {
            var best: OpeningRun? = null
            var y = 0
            while (y < minOf(markerY, height)) {
                while (y < minOf(markerY, height) && !isAir(clipboard, minimum, width, depth, face, centre, y)) y++
                val bottom = y
                while (y < minOf(markerY, height) && isAir(clipboard, minimum, width, depth, face, centre, y)) y++
                val top = y - 1
                if (top < bottom || top - bottom + 1 < minimumHeight) continue
                val base = airSpan(clipboard, minimum, width, depth, face, centre, bottom)
                if (base == null || base.width() < minimumWidth) continue
                val candidate = OpeningRun(bottom, top, base)
                if (best == null || candidate.height() > best.height() ||
                    (candidate.height() == best.height() && candidate.bottom < best.bottom)) {
                    best = candidate
                }
            }
            if (best == null) return null

            var minimumCross = best.base.minimum
            var maximumCross = best.base.maximum
            for (openingY in best.bottom..best.top) {
                val span = airSpan(clipboard, minimum, width, depth, face, centre, openingY) ?: continue
                minimumCross = minOf(minimumCross, span.minimum)
                maximumCross = maxOf(maximumCross, span.maximum)
            }
            return when (face) {
                BlockFace.NORTH -> Bounds(minimumCross, best.bottom, 0, maximumCross, best.top, 0)
                BlockFace.SOUTH -> Bounds(minimumCross, best.bottom, depth - 1, maximumCross, best.top, depth - 1)
                BlockFace.WEST -> Bounds(0, best.bottom, minimumCross, 0, best.top, maximumCross)
                BlockFace.EAST -> Bounds(width - 1, best.bottom, minimumCross, width - 1, best.top, maximumCross)
                else -> throw IllegalArgumentException("Doorways must face a cardinal direction.")
            }
        }

        private fun airSpan(clipboard: Clipboard, minimum: BlockVector3, width: Int, depth: Int,
                            face: BlockFace, centre: Int, y: Int): Span? {
            if (!isAir(clipboard, minimum, width, depth, face, centre, y)) return null
            val limit = if (face == BlockFace.NORTH || face == BlockFace.SOUTH) width else depth
            var low = centre
            var high = centre
            while (low > 0 && isAir(clipboard, minimum, width, depth, face, low - 1, y)) low--
            while (high < limit - 1 && isAir(clipboard, minimum, width, depth, face, high + 1, y)) high++
            return Span(low, high)
        }

        private fun isAir(clipboard: Clipboard, minimum: BlockVector3, width: Int, depth: Int,
                          face: BlockFace, cross: Int, y: Int): Boolean {
            val position = when (face) {
                BlockFace.NORTH -> minimum.add(cross, y, 0)
                BlockFace.SOUTH -> minimum.add(cross, y, depth - 1)
                BlockFace.WEST -> minimum.add(0, y, cross)
                BlockFace.EAST -> minimum.add(width - 1, y, cross)
                else -> throw IllegalArgumentException("Doorways must face a cardinal direction.")
            }
            return BukkitAdapter.adapt(clipboard.getBlock(position)).material.isAir
        }

        /**
         * A doorway marker replaces itself with a neighbouring wall block.
         * Looking along the wall first avoids copying the air of the actual
         * doorway below it.
         */
        private fun sampleWallMaterial(clipboard: Clipboard, minimum: BlockVector3, width: Int, height: Int, depth: Int,
                                       x: Int, y: Int, z: Int, marker: Material): BlockState? {
            val candidates = listOf(
                BlockVector3.at(x - 1, y, z), BlockVector3.at(x + 1, y, z),
                BlockVector3.at(x, y - 1, z), BlockVector3.at(x, y + 1, z),
                BlockVector3.at(x, y, z - 1), BlockVector3.at(x, y, z + 1))
            val counts = HashMap<BlockState, Int>()
            for (local in candidates) {
                if (local.x() < 0 || local.x() >= width || local.y() < 0 || local.y() >= height ||
                    local.z() < 0 || local.z() >= depth) continue
                val state = clipboard.getBlock(minimum.add(local))
                val material = BukkitAdapter.adapt(state).material
                if (!material.isAir && material != marker) {
                    counts.merge(state, 1, Int::plus)
                }
            }
            return counts.entries.maxByOrNull { it.value }?.key
        }

        private fun include(bounds: LocalBounds?, x: Int, y: Int, z: Int): LocalBounds =
            bounds?.include(x, y, z) ?: LocalBounds(x, y, z, x, y, z)

        /** Adds validation information without rejecting a room prefab merely for harmless padding. */
        private fun reportBounds(width: Int, height: Int, depth: Int, content: LocalBounds?,
                                 problems: MutableList<String>) {
            if (content == null) {
                problems.add("The schematic contains no non-air blocks.")
                return
            }
            if (content.sizeX() != width || content.sizeY() != height || content.sizeZ() != depth) {
                problems.add("Empty outer padding will be trimmed: content is ${content.dimensions()}" +
                    " inside the stated ${width}x${height}x$depth selection.")
            }
        }

        private fun verticalOffsets(markers: List<DoorMarker>, structural: LocalBounds?): List<Int> {
            if (structural == null) return emptyList()
            return markers.map { it.y - structural.maxY }.distinct().sorted()
        }

        private fun packed(x: Int, y: Int, z: Int): Long =
            (x.toLong() and 0xFFFFF) shl 40 or ((y.toLong() and 0xFFFFF) shl 20) or (z.toLong() and 0xFFFFF)

        private fun face(room: Bounds, doorway: Bounds): BlockFace {
            if (doorway.minX == room.minX) return BlockFace.WEST
            if (doorway.maxX == room.maxX) return BlockFace.EAST
            if (doorway.minZ == room.minZ) return BlockFace.NORTH
            if (doorway.maxZ == room.maxZ) return BlockFace.SOUTH
            throw IllegalArgumentException("Doorway does not lie on its room boundary.")
        }

        private fun direction(face: BlockFace): TemplateGeometry.Dir = when (face) {
            BlockFace.NORTH -> TemplateGeometry.Dir.NORTH
            BlockFace.SOUTH -> TemplateGeometry.Dir.SOUTH
            BlockFace.EAST -> TemplateGeometry.Dir.EAST
            BlockFace.WEST -> TemplateGeometry.Dir.WEST
            else -> throw IllegalArgumentException("Doorways must face a cardinal direction.")
        }

        private fun blockFace(direction: TemplateGeometry.Dir): BlockFace = when (direction) {
            TemplateGeometry.Dir.NORTH -> BlockFace.NORTH
            TemplateGeometry.Dir.SOUTH -> BlockFace.SOUTH
            TemplateGeometry.Dir.EAST -> BlockFace.EAST
            TemplateGeometry.Dir.WEST -> BlockFace.WEST
        }

        private fun doorwayCentreX(door: TemplateGeometry.WorldDoor): Int =
            if (door.face == TemplateGeometry.Dir.EAST || door.face == TemplateGeometry.Dir.WEST)
                door.wallPlane else door.cross

        private fun doorwayCentreZ(door: TemplateGeometry.WorldDoor): Int =
            if (door.face == TemplateGeometry.Dir.EAST || door.face == TemplateGeometry.Dir.WEST)
                door.cross else door.wallPlane

        private fun crossSize(opening: Bounds, face: BlockFace): Int =
            if (face == BlockFace.NORTH || face == BlockFace.SOUTH) opening.sizeX() else opening.sizeZ()

        private fun rotate(face: BlockFace, rotation: Int): BlockFace = when (Math.floorMod(rotation, 360)) {
            0 -> face
            90 -> when (face) {
                BlockFace.NORTH -> BlockFace.EAST
                BlockFace.EAST -> BlockFace.SOUTH
                BlockFace.SOUTH -> BlockFace.WEST
                BlockFace.WEST -> BlockFace.NORTH
                else -> face
            }
            180 -> when (face) {
                BlockFace.NORTH -> BlockFace.SOUTH
                BlockFace.EAST -> BlockFace.WEST
                BlockFace.SOUTH -> BlockFace.NORTH
                BlockFace.WEST -> BlockFace.EAST
                else -> face
            }
            270 -> when (face) {
                BlockFace.NORTH -> BlockFace.WEST
                BlockFace.EAST -> BlockFace.NORTH
                BlockFace.SOUTH -> BlockFace.EAST
                BlockFace.WEST -> BlockFace.SOUTH
                else -> face
            }
            else -> throw IllegalArgumentException("Only right-angle room rotations are supported.")
        }

        private fun rotate(x: Int, z: Int, width: Int, depth: Int, rotation: Int): Point = when (Math.floorMod(rotation, 360)) {
            0 -> Point(x, z)
            90 -> Point(depth - 1 - z, x)
            180 -> Point(width - 1 - x, depth - 1 - z)
            270 -> Point(z, width - 1 - x)
            else -> throw IllegalArgumentException("Only right-angle room rotations are supported.")
        }

        private fun rotatedDimensions(width: Int, depth: Int, rotation: Int): Point =
            if (Math.floorMod(rotation, 180) == 0) Point(width, depth) else Point(depth, width)

        private fun <T> immutable(input: Map<String, List<T>>): Map<String, List<T>> {
            val result = HashMap<String, List<T>>()
            input.forEach { (key, value) -> result[key] = value.toList() }
            return result.toMap()
        }

        /**
         * Reads `<pool>[_<variant>][_<number>]`.
         *
         * Pool names come from the template (spawn and boss always count).
         * Legacy names keep working: `normal_*` and generic `branch_*` files
         * land in the combat pool and `branch_parkour*` in the parkour pool,
         * with a rename hint logged - the old shape suffix means nothing now.
         */
        private fun declaration(fileName: String, templatePools: Set<String>): NameDeclaration {
            val extension = fileName.lastIndexOf('.')
            val stem = (if (extension < 0) fileName else fileName.substring(0, extension)).lowercase(Locale.ROOT)
            val special = SPECIAL_FILE_NAME.matcher(stem)
            if (special.matches()) {
                return NameDeclaration(true, special.group(1).lowercase(Locale.ROOT), null, null)
            }
            val tokens = stem.split("_").toMutableList()
            if (tokens.isEmpty()) return NameDeclaration(false, null, null, null)

            // Legacy prefixes map onto the new pools; the file keeps working.
            if (tokens.first() == "normal" || tokens.first() == "branch") {
                val legacy = tokens.removeFirst()
                val pool = if (legacy == "branch" && tokens.firstOrNull() == "parkour") {
                    tokens.removeFirst()
                    "parkour"
                } else "combat"
                val variant = variantOf(tokens)
                val hint = "Room prefab $fileName uses the legacy ${legacy}_ naming; it now serves the " +
                    "$pool pool and its shape suffix no longer means anything. Consider renaming it to " +
                    "$pool${variant?.let { "_$it" } ?: ""}.schem."
                return NameDeclaration(true, pool, variant, hint)
            }

            val pools = templatePools + setOf("spawn", "boss")
            // Multi-word pool names (great_hall) match greedily, longest first.
            for (take in minOf(3, tokens.size) downTo 1) {
                val name = tokens.subList(0, take).joinToString("_")
                if (name in pools) {
                    val rest = tokens.subList(take, tokens.size).toMutableList()
                    return NameDeclaration(true, name, variantOf(rest), null)
                }
            }
            return NameDeclaration(false, null, null, null)
        }

        private fun variantOf(tokens: MutableList<String>): String? {
            if (tokens.isNotEmpty() && tokens.last().all { it.isDigit() }) {
                tokens.removeAt(tokens.size - 1)
            }
            return tokens.joinToString("_").ifEmpty { null }
        }

        private fun result(file: File, width: Int, height: Int, depth: Int, content: LocalBounds?,
                           structural: LocalBounds?, markerOffsets: List<Int>, valid: Boolean,
                           declaration: NameDeclaration, sizeClassName: String?, markers: Map<String, Int>,
                           problems: List<String>, prefab: Prefab?,
                           doorwayGroups: List<String> = emptyList(),
                           specialMarkers: List<PrefabSpecialMarker> = emptyList()): LoadResult {
            val actual = content?.dimensions() ?: "none"
            val trimmed = structural?.trimDescription(width, height, depth) ?: "none"
            val reportedProblems = ArrayList(problems)
            if (!valid && reportedProblems.isEmpty()) {
                reportedProblems.add("Rejected without a recorded validation reason; this is a reporting bug.")
            }
            return LoadResult(prefab, Inspection(file.name, width, height, depth, actual, trimmed,
                markerOffsets.toList(), valid, declaration.pool ?: "unknown", declaration.variant, sizeClassName,
                declaration.renameHint, markers.toMap(), doorwayGroups.toList(),
                specialMarkerPositions(specialMarkers), reportedProblems.toList()))
        }

        private fun specialMarkerPositions(markers: List<PrefabSpecialMarker>): List<String> =
            markers.map { "${it.kind.configName}=${it.x},${it.y},${it.z}" }

        private fun material(config: FileConfiguration, path: String, fallback: Material): Material {
            val raw = config.getString(path, fallback.name)
            val material = raw?.let { Material.matchMaterial(it.uppercase(Locale.ROOT)) }
            return if (material == null || !material.isBlock) fallback else material
        }
    }
}
