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
import nl.riddernix.dungeonplugin.generation.DungeonLayout
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.util.BlockVector
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.EnumMap
import java.util.EnumSet
import java.util.Locale
import java.util.Random
import java.util.TreeMap
import java.util.regex.Pattern

/**
 * Loads regular-room prefabs through WorldEdit without ever using a WorldEdit
 * edit session. The resulting block data is placed by the plugin's own cursor
 * builder. Filename prefixes keep normal and branch room pools deliberately
 * separate.
 */
class NormalRoomLibrary(private val plugin: DungeonPlugin) {

    private val folder = File(plugin.dataFolder, "rooms")
    private var prefabs: List<Prefab> = emptyList()
    private var inspections: List<Inspection> = emptyList()
    private var worldEditAvailable = false
    private var unavailableReason: String? = "no valid room schematic is loaded"

    /** Reloads the folder, preserving a report for every room file including failures. */
    fun reload() {
        if (!folder.isDirectory && !folder.mkdirs()) {
            plugin.logger.severe("Could not create room folder: ${folder.absolutePath}")
        }
        val worldEdit = plugin.server.pluginManager.getPlugin("WorldEdit")
        worldEditAvailable = worldEdit != null && worldEdit.isEnabled
        if (!worldEditAvailable) {
            plugin.logger.severe("WorldEdit is missing or disabled. Room prefabs are unavailable; " +
                "procedural stone rooms will be used.")
            prefabs = emptyList()
            inspections = listOf(Inspection("(WorldEdit unavailable)", 0, 0, 0, "none", "none", emptyList(), false,
                PrefabType.UNKNOWN, null, NormalRoomShape.UNKNOWN, "not parsed", emptyMap(), emptyList(), emptyList(),
                listOf("WorldEdit is missing or disabled.")))
            unavailableReason = "WorldEdit is missing or disabled"
            return
        }

        val files = folder.listFiles { file: File -> file.isFile }
        if (files == null || files.isEmpty()) {
            plugin.logger.info("No room schematics found in ${folder.absolutePath}" +
                "; procedural stone rooms remain active.")
            prefabs = emptyList()
            inspections = emptyList()
            unavailableReason = "the room folder is empty"
            return
        }
        val ordered = files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val loaded = ArrayList<Prefab>()
        val report = ArrayList<Inspection>()
        for (file in ordered) {
            val result = load(file)
            report.add(result.inspection)
            if (result.prefab != null) {
                loaded.add(result.prefab)
                if ("matches" != result.inspection.filenameMatch) {
                    plugin.logger.warning("Room prefab ${file.name} loaded, but ${result.inspection.displayProblems()}")
                }
            } else {
                plugin.logger.warning("Rejected room prefab ${file.name}: ${result.inspection.displayProblems()}")
            }
        }
        prefabs = loaded.toList()
        inspections = report.toList()
        if (prefabs.isEmpty()) {
            plugin.logger.warning("No valid room schematics loaded from ${folder.absolutePath}" +
                "; procedural stone rooms remain active.")
            unavailableReason = "no valid room schematic is loaded: " + report.joinToString("; ") {
                "${it.fileName} (${it.displayProblems()})"
            }.ifEmpty { "no files found" }
        } else {
            plugin.logger.info("Loaded ${prefabs.size} room schematic(s) from ${folder.absolutePath}.")
            unavailableReason = null
        }
        reportPoolFootprints()
    }

    /**
     * The planner reserves one envelope per pool - the largest loaded member -
     * so a pool whose files differ in size leaves every smaller room standing
     * inside an oversized reservation: the corridor stops at the reservation
     * edge while the room's wall is a block further in. That gap is invisible
     * in the files and obvious in game, so it is called out by name here.
     */
    private fun reportPoolFootprints() {
        val pools = HashMap<String, MutableList<Prefab>>()
        for (prefab in prefabs) {
            if (prefab.type != PrefabType.NORMAL && prefab.type != PrefabType.BRANCH) continue
            pools.getOrPut(prefab.type.configName() + (prefab.role?.let { " $it" } ?: "")) { ArrayList() }.add(prefab)
        }
        for ((key, value) in TreeMap(pools)) {
            val sizes = value.map { "${it.width}x${it.height}x${it.depth}" }.distinct()
            if (sizes.size < 2) continue
            plugin.logger.severe("Room pool '$key' mixes ${sizes.size}" +
                " footprints, so every room smaller than the largest will leave a gap between its wall and its" +
                " corridors. Resize them to one size. Loaded: " + value
                .map { "${it.fileName} ${it.width}x${it.height}x${it.depth}" }
                .sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(", "))
        }
    }

    fun folder(): File = folder

    fun inspections(): List<Inspection> = inspections

    /**
     * Largest loaded footprint for collision-safe layout planning. This is
     * not a validation limit: every selected schematic still uses its own
     * size.
     *
     * The envelope a slot reserves covers every prefab that slot could
     * receive. A roled slot prefers its own pool but falls back to the
     * generic one, so the reservation spans both. Reserving only the role's
     * size is what makes an oversized fallback catastrophic rather than
     * merely untidy: a prefab wider than its reservation overhangs into the
     * corridor space on both sides, and once the overhang reaches the
     * corridor length the two rooms end up sharing a wall with no gap left to
     * build a corridor in.
     */
    @JvmOverloads
    fun planningDimensions(type: PrefabType, role: String? = null): PlanningDimensions {
        val pool = prefabs
            .filter { it.type == type }
            .filter { it.role == null || (role != null && role == it.role) }
        val fallbackPath = when (type) {
            PrefabType.SPAWN -> "generation.entrance.size"
            PrefabType.BOSS -> "generation.boss-room.max-size"
            PrefabType.NORMAL, PrefabType.BRANCH, PrefabType.UNKNOWN -> "generation.prefab-room.size"
        }
        val fallbackWidth = maxOf(1, plugin.config.getInt("$fallbackPath.x", 67))
        val fallbackHeight = maxOf(1, plugin.config.getInt("$fallbackPath.y", 34))
        val fallbackDepth = maxOf(1, plugin.config.getInt("$fallbackPath.z", 67))
        return PlanningDimensions(
            pool.maxOfOrNull { it.width } ?: fallbackWidth,
            pool.maxOfOrNull { it.height } ?: fallbackHeight,
            pool.maxOfOrNull { it.depth } ?: fallbackDepth)
    }

    /** Lists eligible shapes with no valid file for one explicit layout room type. */
    fun missingUsableShapes(type: PrefabType): List<NormalRoomShape> {
        // Roled prefabs cannot serve generic slots, so they cannot satisfy a
        // generic shape either.
        return usableShapes(type).filter { shape ->
            prefabs.none { it.type == type && it.role == null && it.shape == shape }
        }
    }

    /** Valid files that the present layout rules can never choose. */
    fun unusablePrefabs(): List<String> {
        // Roled prefabs answer to their composition, not to the legacy branch
        // rules, so those limits say nothing about their usability.
        return prefabs
            .filter { it.role == null &&
                (it.type == PrefabType.NORMAL || it.type == PrefabType.BRANCH) &&
                it.shape !in usableShapes(it.type) }
            .map { "${it.fileName} (${it.type.configName()} ${it.shape.configName()})" }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** Plans the deterministic prefab selection and all tick-spread placement operations for one layout. */
    fun plan(layout: DungeonLayout): RoomPlan {
        val requestedRooms = layout.rooms.filter(::usesPrefabSlot)
        if (requestedRooms.isEmpty()) return RoomPlan.empty()
        if (!worldEditAvailable || prefabs.isEmpty()) {
            logFallback(layout, listOf(unavailableReason ?: "no valid room schematic is loaded"))
            val requiredFailures = requestedRooms
                .filter { it.type == DungeonLayout.RoomType.SPAWN || it.type == DungeonLayout.RoomType.BOSS }
                .map { room ->
                    "room ${room.id} (${room.type.name.lowercase(Locale.ROOT)}) requires a valid schematic: " +
                        (unavailableReason ?: "no valid room schematic is loaded")
                }
            return RoomPlan.withRequiredFailures(requiredFailures)
        }
        val operations = ArrayList<BuildOperation>()
        val prefabRooms = HashSet<String>()
        val markers = HashMap<String, List<DungeonMarker>>()
        val doorways = HashMap<String, List<DungeonDoorway>>()
        val doorwayMarkers = HashMap<String, List<DungeonDoorMarker>>()
        val playableBounds = HashMap<String, Bounds>()
        val playerSpawns = HashMap<String, DungeonSpecialMarker>()
        val bossSpawns = HashMap<String, DungeonSpecialMarker>()
        val traps = HashMap<String, DungeonTrap>()
        val chosen = HashMap<String, String>()
        val prefabFiles = HashMap<String, String>()
        val fallbackReasons = ArrayList<String>()
        val roleFallbacks = ArrayList<String>()
        val requiredPrefabFailures = ArrayList<String>()

        for (room in layout.rooms) {
            if (!usesPrefabSlot(room)) continue
            val required = requiredFaces(room, layout.tunnels)
            val arrivalFace = arrivalFace(room, layout.tunnels)
            val wantedType = PrefabType.from(room.type)
            var candidates: List<Candidate> = emptyList()
            if (room.role != null) {
                candidates = candidates(wantedType, room.role, required, arrivalFace)
                if (candidates.isEmpty()) {
                    // A composed room silently wearing a generic prefab is the
                    // kind of thing nobody notices until the run feels wrong.
                    roleFallbacks.add("room ${room.id} wants role '${room.role}' but no " +
                        "${wantedType.configName()}_${room.role}* prefab has exactly the doorways " +
                        faces(required) + (arrivalFace?.let { " (entered from $it)" } ?: "") +
                        "; loaded for that role: " + rolePoolDescription(wantedType, room.role))
                }
            }
            if (candidates.isEmpty()) candidates = candidates(wantedType, null, required, arrivalFace)
            if (candidates.isEmpty()) {
                val reason = "room ${room.id} requires ${faces(required)}" +
                    (arrivalFace?.let { " entering from $it" } ?: "") + "; " +
                    candidateDiagnostics(room, required)
                if (room.type == DungeonLayout.RoomType.SPAWN || room.type == DungeonLayout.RoomType.BOSS) {
                    requiredPrefabFailures.add(reason)
                } else {
                    fallbackReasons.add(reason)
                }
                continue
            }
            val candidate = candidates[Random(layout.seed xor 0x524f4f4d53454544L
                xor (room.id.hashCode().toLong() shl 32)).nextInt(candidates.size)]
            val origin = origin(room, candidate)
            val placedSize = rotatedDimensions(candidate.prefab.width, candidate.prefab.depth, candidate.rotation)
            prefabFiles[room.id] = candidate.prefab.fileName
            chosen[room.id] = "${candidate.prefab.fileName} rot${candidate.rotation}" +
                " origin ${origin.x},${origin.z} size ${placedSize.x}x${placedSize.z}"
            prefabRooms.add(room.id)
            operations.add(blocks(candidate, origin))
            playableBounds[room.id] = playableBounds(candidate, origin)

            val roomMarkers = ArrayList<DungeonMarker>()
            for (marker in candidate.prefab.markers) {
                val point = rotate(marker.x, marker.z, candidate.prefab.width, candidate.prefab.depth, candidate.rotation)
                roomMarkers.add(DungeonMarker(marker.category, origin.x + point.x,
                    origin.y + marker.y, origin.z + point.z))
            }
            markers[room.id] = roomMarkers.toList()

            val roomDoors = ArrayList<DungeonDoorway>()
            val roomDoorMarkers = ArrayList<DungeonDoorMarker>()
            for (doorway in candidate.prefab.doorways) {
                val point = rotate(doorway.opening.centreX(), doorway.opening.centreZ(),
                    candidate.prefab.width, candidate.prefab.depth, candidate.rotation)
                val face = rotate(doorway.facing, candidate.rotation)
                val placed = DungeonDoorway(origin.x + point.x,
                    origin.y + doorway.opening.minY, origin.z + point.z, face, face)
                roomDoors.add(placed)

                val markerPoint = rotate(doorway.marker.centreX, doorway.marker.centreZ,
                    candidate.prefab.width, candidate.prefab.depth, candidate.rotation)
                val markerFace = rotate(doorway.marker.facing, candidate.rotation)
                roomDoorMarkers.add(DungeonDoorMarker(origin.x + markerPoint.x,
                    origin.y + doorway.marker.y, origin.z + markerPoint.z, markerFace, doorway.marker.width))
            }
            doorways[room.id] = roomDoors.toList()
            doorwayMarkers[room.id] = roomDoorMarkers.toList()

            for (marker in candidate.prefab.specialMarkers) {
                val point = rotate(marker.x, marker.z, candidate.prefab.width, candidate.prefab.depth, candidate.rotation)
                val placed = DungeonSpecialMarker(marker.kind,
                    origin.x + point.x, origin.y + marker.y, origin.z + point.z)
                if (marker.kind == SpecialMarkerKind.PLAYER_SPAWN) playerSpawns[room.id] = placed
                if (marker.kind == SpecialMarkerKind.BOSS_SPAWN) bossSpawns[room.id] = placed
            }

            if (candidate.prefab.trapColumns.isNotEmpty() && candidate.prefab.pressurePlates.isNotEmpty()) {
                val columns = ArrayList<DungeonTrap.Column>()
                for (column in candidate.prefab.trapColumns) {
                    val point = rotate(column.x, column.z, candidate.prefab.width, candidate.prefab.depth, candidate.rotation)
                    columns.add(DungeonTrap.Column(origin.x + point.x, origin.y + column.y, origin.z + point.z))
                }
                val plates = HashSet<BlockVector>()
                for (plate in candidate.prefab.pressurePlates) {
                    val point = rotate(plate.x, plate.z, candidate.prefab.width, candidate.prefab.depth, candidate.rotation)
                    plates.add(BlockVector(origin.x + point.x, origin.y + plate.y, origin.z + point.z))
                }
                traps[room.id] = DungeonTrap(room.id, columns, plates)
            }
        }
        if (roleFallbacks.isNotEmpty()) {
            plugin.logger.severe("Composed room role fallback for dungeon seed ${layout.seed}: " +
                roleFallbacks.joinToString(" | ") + ". A generic room of the right shape was used instead.")
        }
        if (fallbackReasons.isNotEmpty()) {
            logFallback(layout, fallbackReasons)
        }
        logLayout(layout, chosen)
        return RoomPlan(prefabRooms.toSet(), operations.toList(), immutable(markers), immutable(doorways),
            immutable(doorwayMarkers), playableBounds.toMap(), playerSpawns.toMap(), bossSpawns.toMap(),
            traps.toMap(), prefabFiles.toMap(), requiredPrefabFailures.toList())
    }

    /**
     * One line per room and per corridor gap. A layout fault - a room at the
     * wrong distance, a prefab that does not fill its reservation - is
     * invisible in the world but obvious side by side here.
     */
    private fun logLayout(layout: DungeonLayout, chosen: Map<String, String>) {
        val lines = ArrayList<String>()
        for (room in layout.rooms) {
            val bounds = room.bounds
            lines.add("room ${room.id} ${room.type.name.lowercase(Locale.ROOT)}" +
                (room.role?.let { "/$it" } ?: "") +
                " x ${bounds.minX}..${bounds.maxX} z ${bounds.minZ}..${bounds.maxZ}" +
                " (${bounds.sizeX()}x${bounds.sizeZ()}) " +
                chosen.getOrDefault(room.id, "procedural"))
        }
        for (tunnel in layout.tunnels) {
            val first = tunnel.firstDoorway
            val second = tunnel.secondDoorway
            val gap = maxOf(Math.abs(second.minX - first.minX), Math.abs(second.minZ - first.minZ))
            lines.add("tunnel ${tunnel.id()} doorways ${first.minX},${first.minZ}" +
                " -> ${second.minX},${second.minZ} wall gap $gap")
        }
        plugin.logger.info("Layout for seed ${layout.seed}: ${lines.joinToString(" | ")}")
    }

    /** One high-signal line per generated dungeon avoids hiding a bad prefab in console noise. */
    private fun logFallback(layout: DungeonLayout, reasons: List<String>) {
        plugin.logger.severe("Schematic room fallback for dungeon seed ${layout.seed}: " +
            reasons.joinToString(" | ") + ". Procedural stone rooms will be used for the listed slot(s).")
    }

    /**
     * Branches are deliberately linear: a side room can extend a branch, but
     * cannot split into another side branch. A two-door branch schematic is
     * therefore useful only if either configured branch-length limit exceeds
     * one.
     */
    private fun usableShapes(type: PrefabType): List<NormalRoomShape> = when (type) {
        PrefabType.NORMAL -> listOf(NormalRoomShape.STRAIGHT, NormalRoomShape.CORNER,
            NormalRoomShape.TJUNCTION, NormalRoomShape.CROSS)
        PrefabType.BRANCH -> {
            val shortLimit = maxOf(1, plugin.config.getInt("generation.branching.short-branch-max-length", 1))
            val longLimit = maxOf(shortLimit,
                plugin.config.getInt("generation.branching.long-branch-max-length", 2))
            if (maxOf(shortLimit, longLimit) > 1) {
                listOf(NormalRoomShape.STRAIGHT, NormalRoomShape.CORNER, NormalRoomShape.DEAD_END)
            } else {
                listOf(NormalRoomShape.DEAD_END)
            }
        }
        PrefabType.SPAWN, PrefabType.BOSS, PrefabType.UNKNOWN -> emptyList()
    }

    /**
     * Audits the finished world, not just the plan. This catches a malformed
     * schematic that physically overwrote a corridor opening after validation.
     */
    fun verifyGenerated(world: World, layout: DungeonLayout, plan: RoomPlan) {
        val roomsById = layout.rooms.associateBy { it.id }

        for (room in layout.rooms) {
            val portals = portals(room, layout.tunnels)
            if (room.id in plan.prefabRoomIds) {
                val prefabDoors = plan.doorways[room.id] ?: emptyList()
                for (doorway in prefabDoors) {
                    val connected = portals.any { it.facing == doorway.facing }
                    if (!connected) {
                        plugin.logger.severe("Dungeon doorway audit: room ${room.id} at " +
                            position(doorway.x, doorway.y, doorway.z) + " facing ${doorway.facing}" +
                            " has no connected corridor.")
                    }
                }
                for (portal in portals) {
                    val declared = prefabDoors.any { it.facing == portal.facing }
                    if (!declared) {
                        plugin.logger.severe("Dungeon doorway audit: room ${room.id} at " +
                            position(portal.doorway.centreX(), portal.doorway.minY, portal.doorway.centreZ()) +
                            " facing ${portal.facing} has a corridor but the placed prefab has no doorway.")
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
                        plugin.logger.severe("Dungeon corridor audit: room ${room.id} at " +
                            position(x, y, z) + " facing ${portal.facing} is blocked by $material" +
                            "; the corridor has no usable doorway.")
                        return
                    }
                }
            }
        }
    }

    private fun load(file: File): LoadResult {
        val problems = ArrayList<String>()
        val declaration = declaration(file.name)
        if (!declaration.parsed) {
            problems.add("Filename must start with normal_, branch_, spawn, or boss. After the prefix you may name a" +
                " composed role, a shape, or both: branch_parkour, branch_parkour_straight, normal_cross.")
        }
        val format = ClipboardFormats.findByFile(file)
        if (format == null) {
            problems.add("WorldEdit could not detect a supported clipboard format.")
            return result(file, 0, 0, 0, null, null, emptyList(), false, declaration.type, declaration.role,
                NormalRoomShape.UNKNOWN, false, emptyMap(), problems, null)
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
                        } else if (material == Material.LIGHT_GRAY_WOOL && declaration.type == PrefabType.SPAWN) {
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
                reportBounds(width, height, depth, contentBounds, structuralBounds, doorwayMarkers, problems)
                val markerOffsets = verticalOffsets(doorwayMarkers, structuralBounds)
                if (contentBounds == null) {
                    return result(file, width, height, depth, contentBounds, structuralBounds, markerOffsets, false,
                        declaration.type, declaration.role, NormalRoomShape.UNKNOWN, false, markerCounts, problems, null)
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
                val placementYOffset = placementYOffset(doors, problems)
                val faces = HashSet<BlockFace>()
                for (door in doors) {
                    if (!faces.add(door.facing)) problems.add("Doorway markers produce more than one doorway on the ${door.facing} wall.")
                }
                val shape = shape(faces)
                val nameMatches = declaration.parsed && (!declaration.shapeDeclared || declaration.shape == shape)
                if (declaration.parsed && declaration.shapeDeclared && !nameMatches) problems.add("Filename declares ${declaration.label}" +
                    " but the markers detect ${shape.configName()}.")
                validateSpecialMarkers(declaration.type, specialMarkers, clipboard, contentMinimum, problems)
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
                val valid = declaration.parsed && doors.isNotEmpty() && doors.size == faces.size &&
                    !specialMarkerError && problems.none { it.startsWith("Doorway") }
                val prefab = if (valid) Prefab(file.name, prefabWidth, prefabHeight, prefabDepth, placementYOffset,
                    blocks, markers, specialMarkers, doors, trapColumns, pressurePlates,
                    declaration.type, declaration.role, shape) else null
                return result(file, width, height, depth, contentBounds, structuralBounds, markerOffsets, valid,
                    declaration.type, declaration.role, shape, nameMatches, markerCounts, doorwayGroups,
                    specialMarkers, problems, prefab)
            }
        } catch (exception: IOException) {
            problems.add("Could not read schematic: ${exception.message}")
            plugin.logger.warning("Could not load room prefab ${file.name}: ${exception.message}")
            return result(file, 0, 0, 0, null, null, emptyList(), false, declaration.type, declaration.role,
                NormalRoomShape.UNKNOWN, false, emptyMap(), problems, null)
        } catch (exception: RuntimeException) {
            problems.add("Could not read schematic: ${exception.message}")
            plugin.logger.warning("Could not load room prefab ${file.name}: ${exception.message}")
            return result(file, 0, 0, 0, null, null, emptyList(), false, declaration.type, declaration.role,
                NormalRoomShape.UNKNOWN, false, emptyMap(), problems, null)
        }
    }

    /**
     * Treats one red block or a contiguous red strip as one doorway
     * declaration. The marker need only identify the wall: the actual air
     * opening is found below it. That accepts both a traditional marker
     * directly above the opening and a marker embedded in a ceiling band,
     * without ever guessing a passage that is not present.
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
            val expectedCentre = expectedCentre(face, width, depth)
            if (!sameRow || !contiguous || minimumCross + maximumCross != expectedCentre * 2) {
                val issues = ArrayList<String>()
                if (!sameRow) issues.add("they are on more than one height")
                if (!contiguous) issues.add("there is a gap in the strip")
                if (minimumCross + maximumCross != expectedCentre * 2) {
                    issues.add("the strip is off-centre (centre ${(minimumCross + maximumCross) / 2}" +
                        ", expected $expectedCentre)")
                }
                problems.add("Doorway markers on the $face wall are invalid: ${issues.joinToString(", ")}.")
                continue
            }
            val opening = findOpening(clipboard, minimum, width, height, depth, face, expectedCentre, markerY,
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
     * A roled slot prefers prefabs carrying its role token and falls back to
     * the generic pool; roled prefabs never serve any other slot, so an
     * authored guardian lair cannot appear as an ordinary side room.
     */
    private fun candidates(room: DungeonLayout.Room, required: Set<BlockFace>, arrivalFace: BlockFace?): List<Candidate> {
        val wantedType = PrefabType.from(room.type)
        if (room.role != null) {
            val roled = candidates(wantedType, room.role, required, arrivalFace)
            if (roled.isNotEmpty()) return roled
        }
        return candidates(wantedType, null, required, arrivalFace)
    }

    private fun candidates(wantedType: PrefabType, role: String?, required: Set<BlockFace>,
                           arrivalFace: BlockFace?): List<Candidate> {
        val candidates = ArrayList<Candidate>()
        for (prefab in prefabs) {
            if (prefab.type != wantedType || prefab.role != role) continue
            val pinnedEntrance = prefab.entranceFace()
            for (rotation in intArrayOf(0, 90, 180, 270)) {
                val faces = HashSet<BlockFace>()
                for (doorway in prefab.doorways) faces.add(rotate(doorway.facing, rotation))
                if (faces != required) continue
                // A green entrance restricts the exact-match rotations further:
                // players must arrive through that specific opening.
                if (pinnedEntrance != null && arrivalFace != null &&
                    rotate(pinnedEntrance, rotation) != arrivalFace) continue
                candidates.add(Candidate(prefab, rotation))
            }
        }
        candidates.sortWith(compareBy<Candidate, String>(String.CASE_INSENSITIVE_ORDER) { it.prefab.fileName }
            .thenBy { it.rotation })
        return candidates
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

    /** Every loaded file for one role with the doorways each rotation offers. */
    private fun rolePoolDescription(type: PrefabType, role: String): String {
        val pool = prefabs.filter { it.type == type && role == it.role }
        if (pool.isEmpty()) return "no file with that role is loaded (check /dungeon rooms for a rejected one)"
        val descriptions = ArrayList<String>()
        for (prefab in pool) {
            val rotations = ArrayList<String>()
            for (rotation in intArrayOf(0, 90, 180, 270)) {
                val rotated = HashSet<BlockFace>()
                for (doorway in prefab.doorways) rotated.add(rotate(doorway.facing, rotation))
                rotations.add("$rotation° ${faces(rotated)}")
            }
            descriptions.add("${prefab.fileName} [${rotations.joinToString(", ")}]")
        }
        return descriptions.joinToString("; ")
    }

    /** Detailed, deterministic report emitted only when an exact door match is missing. */
    private fun candidateDiagnostics(room: DungeonLayout.Room, required: Set<BlockFace>): String {
        val wantedType = PrefabType.from(room.type)
        val matchingType = prefabs.filter { it.type == wantedType }
        if (matchingType.isEmpty()) return "no valid ${wantedType.configName()} prefabs are loaded"
        val descriptions = ArrayList<String>()
        for (prefab in matchingType) {
            val rotations = ArrayList<String>()
            for (rotation in intArrayOf(0, 90, 180, 270)) {
                val rotatedFaces = HashSet<BlockFace>()
                for (doorway in prefab.doorways) rotatedFaces.add(rotate(doorway.facing, rotation))
                val entrance = prefab.entranceFace()
                rotations.add("$rotation° doors ${faces(rotatedFaces)}" +
                    (entrance?.let { " entrance ${rotate(it, rotation)}" } ?: ""))
            }
            descriptions.add("${prefab.fileName} [${rotations.joinToString(", ")}]")
        }
        return descriptions.joinToString("; ")
    }

    private fun blocks(candidate: Candidate, origin: PlacementOrigin): BuildOperation {
        val transform = AffineTransform().rotateY(-candidate.rotation.toDouble())
        val entries = ArrayList<BlockListOperation.Entry>(candidate.prefab.blocks.size)
        for (block in candidate.prefab.blocks) {
            val point = rotate(block.x, block.z, candidate.prefab.width, candidate.prefab.depth, candidate.rotation)
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

    private class Prefab(
        val fileName: String, val width: Int, val height: Int, val depth: Int, val placementYOffset: Int,
        blocks: List<PrefabBlock>, markers: List<PrefabMarker>, specialMarkers: List<PrefabSpecialMarker>,
        doorways: List<PrefabDoorway>, trapColumns: List<PrefabPoint>, pressurePlates: List<PrefabPoint>,
        val type: PrefabType, val role: String?, val shape: NormalRoomShape
    ) {
        val blocks: List<PrefabBlock> = blocks.toList()
        val markers: List<PrefabMarker> = markers.toList()
        val specialMarkers: List<PrefabSpecialMarker> = specialMarkers.toList()
        val doorways: List<PrefabDoorway> = doorways.toList()
        val trapColumns: List<PrefabPoint> = trapColumns.toList()
        val pressurePlates: List<PrefabPoint> = pressurePlates.toList()

        /** The green-pinned entrance face, or null when the room is free to rotate. */
        fun entranceFace(): BlockFace? = doorways.firstOrNull { it.entrance }?.facing
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

    private class Candidate(val prefab: Prefab, val rotation: Int)

    private class PlacementOrigin(val x: Int, val y: Int, val z: Int)

    private data class Point(val x: Int, val z: Int)

    private class NameDeclaration(val parsed: Boolean, val type: PrefabType, val role: String?,
                                  val shape: NormalRoomShape, val label: String, val shapeDeclared: Boolean)

    private class LoadResult(val prefab: Prefab?, val inspection: Inspection)

    private class MarkerMaterials(
        val doorway: Material, val wallMatchDoorway: Boolean, val doorwayReplacement: Material,
        val entrance: Material, val wallMatchEntrance: Boolean, val entranceReplacement: Material,
        val trapFloor: Material, val wallMatchTrapFloor: Boolean, val trapFloorReplacement: Material,
        val spawnReplacement: Material, val legacyPurpleReplacement: Material,
        val spawnCategories: Map<Material, String>, val specialMarkers: Map<Material, SpecialMarkerKind>,
        val specialReplacements: Map<SpecialMarkerKind, Material>
    ) {
        fun specialReplacement(kind: SpecialMarkerKind): Material =
            specialReplacements.getOrDefault(kind, Material.AIR)

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
                val specialReplacements = EnumMap<SpecialMarkerKind, Material>(SpecialMarkerKind::class.java)
                for (kind in SpecialMarkerKind.entries) {
                    val marker = material(config, "generation.rooms.markers.${kind.configName}.material",
                        if (kind == SpecialMarkerKind.PLAYER_SPAWN) Material.GRAY_WOOL else Material.LIGHT_BLUE_WOOL)
                    specialMarkers[marker] = kind
                    specialReplacements[kind] = material(config, "generation.rooms.markers.replacements.${kind.configName}", Material.AIR)
                }
                specialMarkers.remove(doorway)
                specialMarkers.remove(entrance)
                specialMarkers.remove(trapFloor)
                specialMarkers.remove(Material.PURPLE_WOOL)
                categories.keys.removeAll(specialMarkers.keys)
                return MarkerMaterials(doorway, wallMatchDoorway, doorwayReplacement,
                    entrance, wallMatchEntrance, entranceReplacement,
                    trapFloor, wallMatchTrapFloor, trapFloorReplacement, spawnReplacement,
                    legacyPurpleReplacement, categories.toMap(), specialMarkers.toMap(), specialReplacements.toMap())
            }
        }
    }

    /** The filename-declared pool a prefab may serve. */
    enum class PrefabType(private val configName: String) {
        NORMAL("normal"),
        BRANCH("branch"),
        SPAWN("spawn"),
        BOSS("boss"),
        UNKNOWN("unknown");

        fun configName(): String = configName

        companion object {
            fun fromPrefix(prefix: String): PrefabType = when (prefix.lowercase(Locale.ROOT)) {
                "normal" -> NORMAL
                "branch" -> BRANCH
                "spawn" -> SPAWN
                "boss" -> BOSS
                else -> UNKNOWN
            }

            fun from(type: DungeonLayout.RoomType): PrefabType = when (type) {
                DungeonLayout.RoomType.NORMAL -> NORMAL
                DungeonLayout.RoomType.BRANCH -> BRANCH
                DungeonLayout.RoomType.SPAWN -> SPAWN
                DungeonLayout.RoomType.BOSS -> BOSS
            }
        }
    }

    /** Maximum envelope used only while the generator keeps rooms apart. */
    data class PlanningDimensions(val width: Int, val height: Int, val depth: Int)

    /** Immutable data used by /dungeon rooms. */
    class Inspection(
        val fileName: String, val width: Int, val height: Int, val depth: Int, val actualDimensions: String,
        val trimmedDimensions: String, markerVerticalOffsets: List<Int>, val valid: Boolean, val type: PrefabType,
        val role: String?, val shape: NormalRoomShape, val filenameMatch: String, markerCounts: Map<String, Int>,
        doorwayGroups: List<String>, specialMarkers: List<String>, problems: List<String>
    ) {
        val markerVerticalOffsets: List<Int> = markerVerticalOffsets.toList()
        val markerCounts: Map<String, Int> = markerCounts.toMap()
        val doorwayGroups: List<String> = doorwayGroups.toList()
        val specialMarkers: List<String> = specialMarkers.toList()
        val problems: List<String> = problems.toList()

        fun dimensions(): String = "${width}x${height}x$depth"

        /** The pool label shown by /dungeon rooms: the type plus any role token. */
        fun displayType(): String = if (role == null) type.configName() else "${type.configName()} $role"

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

    /** Tick-spread blocks plus marker metadata for selected prefab rooms. */
    class RoomPlan(
        prefabRoomIds: Set<String>, operations: List<BuildOperation>,
        markers: Map<String, List<DungeonMarker>>, doorways: Map<String, List<DungeonDoorway>>,
        doorwayMarkers: Map<String, List<DungeonDoorMarker>>, playableBounds: Map<String, Bounds>,
        playerSpawns: Map<String, DungeonSpecialMarker>, bossSpawns: Map<String, DungeonSpecialMarker>,
        traps: Map<String, DungeonTrap>, prefabFiles: Map<String, String>,
        requiredPrefabFailures: List<String>
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
        val requiredPrefabFailures: List<String> = requiredPrefabFailures.toList()

        fun hasRequiredPrefabFailures(): Boolean = requiredPrefabFailures.isNotEmpty()

        companion object {
            fun empty(): RoomPlan = RoomPlan(emptySet(), emptyList(), emptyMap(), emptyMap(), emptyMap(),
                emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())

            fun withRequiredFailures(failures: List<String>): RoomPlan =
                RoomPlan(emptySet(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
                    emptyMap(), emptyMap(), emptyMap(), failures.toList())
        }
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

        private fun usesPrefabSlot(room: DungeonLayout.Room): Boolean =
            PrefabType.from(room.type) != PrefabType.UNKNOWN && room.variant != DungeonLayout.RoomVariant.PARKOUR

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

        private fun validateSpecialMarkers(type: PrefabType, markers: List<PrefabSpecialMarker>, clipboard: Clipboard,
                                           minimum: BlockVector3, problems: MutableList<String>) {
            val playerSpawns = markers.count { it.kind == SpecialMarkerKind.PLAYER_SPAWN }
            val bossSpawns = markers.count { it.kind == SpecialMarkerKind.BOSS_SPAWN }
            if (type == PrefabType.SPAWN) {
                if (playerSpawns == 0) problems.add("Spawn room requires exactly one player-spawn marker, but found none.")
                if (playerSpawns > 1) problems.add("Spawn room requires exactly one player-spawn marker, but found $playerSpawns.")
            }
            if (type == PrefabType.BOSS) {
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
                val expectedCentre = expectedCentre(face, width, depth)
                val centred = minimumCross + maximumCross == expectedCentre * 2
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
                status.add(if (centred) "centred" else "off-centre (expected $expectedCentre)")
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

        private fun expectedCentre(face: BlockFace, width: Int, depth: Int): Int =
            if (face == BlockFace.NORTH || face == BlockFace.SOUTH) (width - 1) / 2 else (depth - 1) / 2

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
        private fun reportBounds(width: Int, height: Int, depth: Int, content: LocalBounds?, structural: LocalBounds?,
                                 markerBlocks: List<DoorMarker>, problems: MutableList<String>) {
            if (content == null) {
                problems.add("The schematic contains no non-air blocks.")
                return
            }
            // Doorway markers may deliberately live in a separate authoring
            // layer. Their vertical convention is reported separately and
            // never makes a room invalid.
            if (content.sizeX() != width || content.sizeY() != height || content.sizeZ() != depth) {
                problems.add("Empty outer padding will be trimmed: content is ${content.dimensions()}" +
                    " inside the stated ${width}x${height}x$depth selection.")
            }
        }

        private fun verticalOffsets(markers: List<DoorMarker>, structural: LocalBounds?): List<Int> {
            if (structural == null) return emptyList()
            return markers.map { it.y - structural.maxY }.distinct().sorted()
        }

        private fun placementYOffset(doors: List<PrefabDoorway>, problems: MutableList<String>): Int {
            if (doors.isEmpty()) return 0
            val openingBottom = doors.first().opening.minY
            if (doors.any { it.opening.minY != openingBottom }) {
                problems.add("Doorway openings do not share one floor height, so a flat dungeon corridor cannot align them.")
            }
            // DungeonLayout corridors enter at one block above a room's layout floor.
            return 1 - openingBottom
        }

        private fun packed(x: Int, y: Int, z: Int): Long =
            (x.toLong() and 0xFFFFF) shl 40 or ((y.toLong() and 0xFFFFF) shl 20) or (z.toLong() and 0xFFFFF)

        /** The face this room is entered through: the doorway towards its parent. */
        private fun arrivalFace(room: DungeonLayout.Room, tunnels: List<DungeonLayout.Tunnel>): BlockFace? {
            for (tunnel in tunnels) {
                if (tunnel.secondRoomId == room.id) {
                    return face(room.bounds, tunnel.secondDoorway)
                }
            }
            return null
        }

        private fun origin(room: DungeonLayout.Room, candidate: Candidate): PlacementOrigin {
            val dimensions = rotatedDimensions(candidate.prefab.width, candidate.prefab.depth, candidate.rotation)
            return PlacementOrigin(room.bounds.centreX() - (dimensions.x - 1) / 2,
                room.bounds.minY + candidate.prefab.placementYOffset,
                room.bounds.centreZ() - (dimensions.z - 1) / 2)
        }

        /**
         * Runtime containment begins at the logical corridor floor, not at a
         * decorative foundation below it. Its ceiling is the prefab's real
         * ceiling after the doorway-alignment offset, so constrained endermen
         * cannot teleport into the empty space above a raised prefab roof.
         */
        private fun playableBounds(candidate: Candidate, origin: PlacementOrigin): Bounds {
            val dimensions = rotatedDimensions(candidate.prefab.width, candidate.prefab.depth, candidate.rotation)
            val physicalMinimumY = origin.y
            val physicalMaximumY = physicalMinimumY + candidate.prefab.height - 1
            return Bounds(origin.x, physicalMinimumY, origin.z,
                origin.x + dimensions.x - 1, physicalMaximumY, origin.z + dimensions.z - 1)
        }

        private fun requiredFaces(room: DungeonLayout.Room, tunnels: List<DungeonLayout.Tunnel>): EnumSet<BlockFace> {
            val faces = EnumSet.noneOf(BlockFace::class.java)
            for (tunnel in tunnels) {
                if (tunnel.firstRoomId == room.id) faces.add(face(room.bounds, tunnel.firstDoorway))
                if (tunnel.secondRoomId == room.id) faces.add(face(room.bounds, tunnel.secondDoorway))
            }
            return faces
        }

        private fun face(room: Bounds, doorway: Bounds): BlockFace {
            if (doorway.minX == room.minX) return BlockFace.WEST
            if (doorway.maxX == room.maxX) return BlockFace.EAST
            if (doorway.minZ == room.minZ) return BlockFace.NORTH
            if (doorway.maxZ == room.maxZ) return BlockFace.SOUTH
            throw IllegalArgumentException("Doorway does not lie on its room boundary.")
        }

        private fun shape(faces: Set<BlockFace>): NormalRoomShape = when (faces.size) {
            1 -> NormalRoomShape.DEAD_END
            2 -> if (opposite(faces)) NormalRoomShape.STRAIGHT else NormalRoomShape.CORNER
            3 -> NormalRoomShape.TJUNCTION
            4 -> NormalRoomShape.CROSS
            else -> NormalRoomShape.UNKNOWN
        }

        private fun opposite(faces: Set<BlockFace>): Boolean =
            (BlockFace.NORTH in faces && BlockFace.SOUTH in faces) ||
                (BlockFace.EAST in faces && BlockFace.WEST in faces)

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

        private fun faces(faces: Set<BlockFace>): String =
            faces.map { it.name }.sorted().joinToString(", ").ifEmpty { "none" }

        private fun material(config: FileConfiguration, path: String, fallback: Material): Material {
            val raw = config.getString(path, fallback.name)
            val material = raw?.let { Material.matchMaterial(it.uppercase(Locale.ROOT)) }
            return if (material == null || !material.isBlock) fallback else material
        }

        private fun <T> immutable(input: Map<String, List<T>>): Map<String, List<T>> {
            val result = HashMap<String, List<T>>()
            input.forEach { (key, value) -> result[key] = value.toList() }
            return result.toMap()
        }

        /**
         * Reads `(normal|branch)[_role][_shape][_number]`.
         *
         * Both middle parts are optional, so `branch_straight` keeps its old
         * meaning while `branch_parkour` and `branch_parkour_straight` both
         * bind to the parkour role. The shape is only ever a declaration: it
         * is checked against the doorways actually found, and a file that
         * omits it is validated purely on its markers.
         */
        private fun declaration(fileName: String): NameDeclaration {
            val extension = fileName.lastIndexOf('.')
            val stem = if (extension < 0) fileName else fileName.substring(0, extension)
            val special = SPECIAL_FILE_NAME.matcher(stem)
            if (special.matches()) {
                return NameDeclaration(true, PrefabType.fromPrefix(special.group(1)), null,
                    NormalRoomShape.UNKNOWN, special.group(1).lowercase(Locale.ROOT), false)
            }
            val tokens = stem.lowercase(Locale.ROOT).split("_").toMutableList()
            val type = if (tokens.isEmpty()) PrefabType.UNKNOWN else PrefabType.fromPrefix(tokens.removeFirst())
            if (type != PrefabType.NORMAL && type != PrefabType.BRANCH) {
                return NameDeclaration(false, PrefabType.UNKNOWN, null, NormalRoomShape.UNKNOWN, "unparsed", false)
            }
            if (tokens.isNotEmpty() && tokens.last().all { it.isDigit() }) {
                tokens.removeLast()
            }
            var shape = NormalRoomShape.UNKNOWN
            // Two shape words are spelled with an underscore, so they are
            // matched before the single-token ones.
            if (tokens.size >= 2) {
                val paired = shape(tokens[tokens.size - 2] + "_" + tokens.last())
                if (paired != NormalRoomShape.UNKNOWN) {
                    shape = paired
                    tokens.removeLast()
                    tokens.removeLast()
                }
            }
            if (shape == NormalRoomShape.UNKNOWN && tokens.isNotEmpty()) {
                val single = shape(tokens.last())
                if (single != NormalRoomShape.UNKNOWN) {
                    shape = single
                    tokens.removeLast()
                }
            }
            val role = if (tokens.isEmpty()) null else tokens.joinToString("_")
            val label = (role ?: "") + (if (shape == NormalRoomShape.UNKNOWN) "" else
                (if (role == null) "" else "_") + shape.configName())
            return NameDeclaration(true, type, role, shape, label.ifEmpty { type.configName() },
                shape != NormalRoomShape.UNKNOWN)
        }

        private fun shape(declared: String): NormalRoomShape = when (declared) {
            "straight" -> NormalRoomShape.STRAIGHT
            "corner_r", "corner_l", "corner" -> NormalRoomShape.CORNER
            "tjunction" -> NormalRoomShape.TJUNCTION
            "cross" -> NormalRoomShape.CROSS
            "dead_end" -> NormalRoomShape.DEAD_END
            else -> NormalRoomShape.UNKNOWN
        }

        private fun result(file: File, width: Int, height: Int, depth: Int, content: LocalBounds?, structural: LocalBounds?,
                           markerOffsets: List<Int>, valid: Boolean, type: PrefabType, role: String?, shape: NormalRoomShape,
                           nameMatches: Boolean, markers: Map<String, Int>, problems: List<String>, prefab: Prefab?): LoadResult =
            result(file, width, height, depth, content, structural, markerOffsets, valid, type, role, shape, nameMatches,
                markers, emptyList(), emptyList(), problems, prefab)

        private fun result(file: File, width: Int, height: Int, depth: Int, content: LocalBounds?, structural: LocalBounds?,
                           markerOffsets: List<Int>, valid: Boolean, type: PrefabType, role: String?, shape: NormalRoomShape,
                           nameMatches: Boolean, markers: Map<String, Int>, doorwayGroups: List<String>,
                           specialMarkers: List<PrefabSpecialMarker>, problems: List<String>, prefab: Prefab?): LoadResult {
            val actual = content?.dimensions() ?: "none"
            val trimmed = structural?.trimDescription(width, height, depth) ?: "none"
            val filenameMatch = if (type == PrefabType.SPAWN || type == PrefabType.BOSS)
                "not checked" else if (nameMatches) "matches" else "does not match"
            val reportedProblems = ArrayList(problems)
            if (!valid && reportedProblems.isEmpty()) {
                reportedProblems.add("Rejected without a recorded validation reason; this is a reporting bug.")
            }
            return LoadResult(prefab, Inspection(file.name, width, height, depth, actual, trimmed,
                markerOffsets.toList(), valid, type, role, shape, filenameMatch, markers.toMap(),
                doorwayGroups.toList(), specialMarkerPositions(specialMarkers), reportedProblems.toList()))
        }

        private fun specialMarkerPositions(markers: List<PrefabSpecialMarker>): List<String> =
            markers.map { "${it.kind.configName}=${it.x},${it.y},${it.z}" }
    }
}
