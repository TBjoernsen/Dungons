package nl.riddernix.dungeonplugin.room

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.extent.transform.BlockTransformExtent
import com.sk89q.worldedit.math.transform.AffineTransform
import com.sk89q.worldedit.world.block.BlockState
import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.generation.BlockListOperation
import nl.riddernix.dungeonplugin.generation.Bounds
import nl.riddernix.dungeonplugin.generation.BuildOperation
import nl.riddernix.dungeonplugin.generation.DungeonLayout
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.configuration.file.FileConfiguration
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.Random

/**
 * Loads repeatable corridor segments. WorldEdit is used only as a schematic
 * reader; every transformed block is still written by the plugin's cursor
 * builder.
 */
class CorridorLibrary(private val plugin: DungeonPlugin) {

    private val folder = File(plugin.dataFolder, "corridors")
    private var prefabs: List<Prefab> = emptyList()
    private var inspections: List<Inspection> = emptyList()
    private var worldEditAvailable = false
    private var unavailableReason: String? = null

    /** Reloads corridor segments and preserves an inspection record for every file. */
    fun reload() {
        if (!folder.isDirectory && !folder.mkdirs()) {
            plugin.logger.severe("Could not create corridor folder: ${folder.absolutePath}")
        }
        val worldEdit = plugin.server.pluginManager.getPlugin("WorldEdit")
        worldEditAvailable = worldEdit != null && worldEdit.isEnabled
        if (!worldEditAvailable) {
            plugin.logger.severe("WorldEdit is missing or disabled. Schematic corridors are unavailable; procedural corridors remain active.")
            prefabs = emptyList()
            inspections = listOf(Inspection("(WorldEdit unavailable)", "none", "none", "none", "none", emptyList(), false,
                listOf("WorldEdit is missing or disabled.")))
            unavailableReason = "WorldEdit is missing or disabled"
            return
        }

        val files = folder.listFiles { file: File -> file.isFile }
        if (files == null || files.isEmpty()) {
            plugin.logger.info("No corridor schematics found in ${folder.absolutePath}" +
                "; procedural corridors remain active.")
            prefabs = emptyList()
            inspections = emptyList()
            unavailableReason = "the corridor folder is empty"
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
            } else {
                plugin.logger.warning("Rejected corridor ${file.name}: ${result.inspection.displayProblems()}")
            }
        }
        prefabs = loaded.toList()
        inspections = report.toList()
        if (prefabs.isEmpty()) {
            plugin.logger.warning("No valid corridor schematics loaded from ${folder.absolutePath}" +
                "; procedural corridors remain active.")
            unavailableReason = "no valid corridor schematic is loaded: " + report.joinToString("; ") {
                "${it.fileName} (${it.displayProblems()})"
            }.ifEmpty { "no files found" }
        } else {
            plugin.logger.info("Loaded ${prefabs.size} corridor schematic(s) from ${folder.absolutePath}.")
            unavailableReason = null
        }
    }

    fun folder(): File = folder
    fun inspections(): List<Inspection> = inspections

    /** Plans tile operations only for tunnels whose two connectors can be aligned exactly. */
    fun plan(layout: DungeonLayout, rooms: NormalRoomLibrary.RoomPlan): CorridorPlan {
        if (layout.tunnels.isEmpty()) return CorridorPlan.empty()
        if (!worldEditAvailable || prefabs.isEmpty()) {
            logFallback(layout, listOf(unavailableReason ?: "no valid corridor schematic is loaded"))
            return CorridorPlan.empty()
        }
        val roomsById = layout.rooms.associateBy { it.id }

        val operations = ArrayList<BuildOperation>()
        val schematicTunnelIds = HashSet<String>()
        val fallbackReasons = ArrayList<String>()
        for (tunnel in layout.tunnels) {
            val firstRoom = roomsById[tunnel.firstRoomId] ?: continue
            val secondRoom = roomsById[tunnel.secondRoomId] ?: continue
            val first = endpoint(firstRoom, tunnel.firstDoorway, rooms.doorwayMarkers)
            val second = endpoint(secondRoom, tunnel.secondDoorway, rooms.doorwayMarkers)
            val search = candidates(tunnel, first, second)
            if (search.candidates.isEmpty()) {
                // The room boxes are printed too: a marker mismatch is almost
                // always a room placed at the wrong distance, not a bad tile.
                fallbackReasons.add("tunnel ${tunnel.id()}: ${search.reasons.joinToString(", ")}" +
                    " [${endpointDescription("first", first)} in room ${firstRoom.id}" +
                    " ${box(firstRoom.bounds)}; ${endpointDescription("second", second)}" +
                    " in room ${secondRoom.id} ${box(secondRoom.bounds)}]")
                continue
            }
            val candidate = search.candidates[Random(layout.seed xor tunnel.id().hashCode() * -0x61c8864680b583ebL)
                .nextInt(search.candidates.size)]
            operations.addAll(tileOperations(candidate))
            schematicTunnelIds.add(tunnel.id())
        }
        if (fallbackReasons.isNotEmpty()) {
            logFallback(layout, fallbackReasons)
        }
        return CorridorPlan(schematicTunnelIds.toSet(), operations.toList())
    }

    /** One high-signal entry per dungeon lists every schematic corridor rejection. */
    private fun logFallback(layout: DungeonLayout, reasons: List<String>) {
        plugin.logger.severe("Schematic corridor fallback for dungeon seed ${layout.seed}: " +
            reasons.joinToString(" | ") + ". Procedural corridors will be used for the listed tunnel(s).")
    }

    private fun endpoint(room: DungeonLayout.Room, doorway: Bounds,
                         prefabMarkers: Map<String, List<DungeonDoorMarker>>): Endpoint {
        val face = face(room.bounds, doorway)
        var marker = (prefabMarkers[room.id] ?: emptyList()).firstOrNull { it.facing == face }
        if (marker == null) {
            val heightAboveFloor = maxOf(1, plugin.config.getInt(
                "generation.corridor.schematic.virtual-door-marker.height-above-floor", 30))
            marker = DungeonDoorMarker(doorway.centreX(), doorway.minY - 1 + heightAboveFloor,
                doorway.centreZ(), face, maxOf(1, plugin.config.getInt(
                "generation.corridor.schematic.virtual-door-marker.width", 3)) or 1)
        }
        return Endpoint(marker)
    }

    /** Marker centres, widths, faces, and the tile stride are the only placement constraints. */
    private fun candidates(tunnel: DungeonLayout.Tunnel, first: Endpoint, second: Endpoint): CandidateSearch {
        val result = ArrayList<Candidate>()
        val reasons = ArrayList<String>()
        for (prefab in prefabs) {
            var faceMatched = false
            var widthMatched = false
            var distanceMatched = false
            for (rotation in intArrayOf(0, 90, 180, 270)) {
                for (from in prefab.connectors) {
                    val to = prefab.other(from)
                    if (rotate(from.face, rotation) != opposite(first.marker.facing) ||
                        rotate(to.face, rotation) != opposite(second.marker.facing)) continue
                    faceMatched = true
                    if (from.width != first.marker.width || to.width != second.marker.width) continue
                    widthMatched = true
                    val fromPoint = rotate(from.x, from.z, prefab.width, prefab.depth, rotation)
                    val toPoint = rotate(to.x, to.z, prefab.width, prefab.depth, rotation)
                    val originX = first.marker.x - fromPoint.x
                    val originY = first.marker.y - from.y
                    val originZ = first.marker.z - fromPoint.z
                    val stepX = toPoint.x - fromPoint.x
                    val stepZ = toPoint.z - fromPoint.z
                    val tiles = tilesToJoin(second.marker.x - first.marker.x, second.marker.z - first.marker.z, stepX, stepZ)
                    if (tiles < 1 || first.marker.y != second.marker.y) continue
                    distanceMatched = true
                    val lastOriginX = second.marker.x - toPoint.x
                    val lastOriginZ = second.marker.z - toPoint.z
                    result.add(Candidate(prefab, rotation, originX, originY, originZ, stepX, stepZ, tiles,
                        lastOriginX, lastOriginZ))
                }
            }
            if (!faceMatched) reasons.add("${prefab.fileName} has no rotation with connector faces matching the room doorways")
            else if (!widthMatched) reasons.add("${prefab.fileName} connector width does not match the room doorway marker width")
            else if (!distanceMatched) {
                if (first.marker.y != second.marker.y) {
                    reasons.add("${prefab.fileName} cannot join doorway markers at different world heights (" +
                        "${first.marker.y} and ${second.marker.y}) in a flat corridor")
                } else {
                    reasons.add("${prefab.fileName} does not lie on the corridor axis or is too close to join")
                }
            }
        }
        result.sortWith(compareBy<Candidate, String>(String.CASE_INSENSITIVE_ORDER) { it.prefab.fileName }
            .thenBy { it.rotation })
        if (reasons.isEmpty()) reasons.add("no corridor candidate passed exact connector alignment")
        return CandidateSearch(result.toList(), reasons.toList())
    }

    private fun tileOperations(candidate: Candidate): List<BuildOperation> {
        val transform = AffineTransform().rotateY(-candidate.rotation.toDouble())
        val result = ArrayList<BuildOperation>()
        for (tile in 0 until candidate.tiles) {
            val entries = ArrayList<BlockListOperation.Entry>(candidate.prefab.blocks.size)
            val offsetX = if (tile == candidate.tiles - 1) candidate.lastOriginX else candidate.originX + candidate.stepX * tile
            val offsetZ = if (tile == candidate.tiles - 1) candidate.lastOriginZ else candidate.originZ + candidate.stepZ * tile
            for (block in candidate.prefab.blocks) {
                val point = rotate(block.x, block.z, candidate.prefab.width, candidate.prefab.depth, candidate.rotation)
                val data = if (block.replacement == null)
                    BukkitAdapter.adapt(BlockTransformExtent.transform(block.state, transform))
                else block.replacement.createBlockData()
                entries.add(BlockListOperation.Entry(offsetX + point.x, candidate.originY + block.y, offsetZ + point.z, data))
            }
            result.add(BlockListOperation(entries))
        }
        return result.toList()
    }

    private fun load(file: File): LoadResult {
        val problems = ArrayList<String>()
        val format = ClipboardFormats.findByFile(file)
        if (format == null) {
            problems.add("WorldEdit could not detect a supported clipboard format.")
            return result(file, null, null, null, emptyList(), emptyList(), false, problems, null)
        }
        try {
            format.getReader(FileInputStream(file)).use { reader ->
                val clipboard = reader.read()
                val minimum = clipboard.region.minimumPoint
                val dimensions = clipboard.dimensions
                val width = dimensions.x()
                val height = dimensions.y()
                val depth = dimensions.z()
                val materials = Materials.read(plugin.config)
                val source = ArrayList<SourceBlock>()
                val connectorMarkers = ArrayList<MarkerPoint>()
                val guideMarkers = ArrayList<MarkerPoint>()
                var content: LocalBounds? = null
                var structural: LocalBounds? = null
                val unknownWool = HashSet<Material>()
                for (position in clipboard.region) {
                    val x = position.x() - minimum.x()
                    val y = position.y() - minimum.y()
                    val z = position.z() - minimum.z()
                    val state = clipboard.getBlock(position)
                    val material = BukkitAdapter.adapt(state).material
                    if (material.isAir) continue
                    content = include(content, x, y, z)
                    var replacement: Material? = null
                    when (material) {
                        materials.connector -> {
                            connectorMarkers.add(MarkerPoint(x, y, z))
                            replacement = materials.connectorReplacement
                        }
                        materials.guide -> {
                            guideMarkers.add(MarkerPoint(x, y, z))
                            replacement = materials.guideReplacement
                        }
                        else -> {
                            structural = include(structural, x, y, z)
                            if (material.name.endsWith("_WOOL")) unknownWool.add(material)
                        }
                    }
                    source.add(SourceBlock(x, y, z, state, replacement))
                }
                if (content == null || structural == null) {
                    problems.add("The schematic has no structural non-air content after marker blocks are removed.")
                }
                if (unknownWool.isNotEmpty()) {
                    problems.add("Unmapped wool will be placed as decoration: " +
                        unknownWool.map(Material::name).sorted().joinToString(", ") + ".")
                }
                val connectors = parseConnectors(connectorMarkers, width, depth, problems)
                reportBounds(width, height, depth, content, structural, connectorMarkers, guideMarkers, problems)
                val markerOffsets = verticalOffsets(connectors, structural)
                val valid = structural != null && connectors.size == 2 && connectors.first().face == opposite(connectors.last().face) &&
                    connectors.first().width == connectors.last().width && connectors.first().y == connectors.last().y
                if (connectors.size == 2 && (connectors.first().face != opposite(connectors.last().face) ||
                        connectors.first().width != connectors.last().width || connectors.first().y != connectors.last().y)) {
                    problems.add("The two purple connector groups must have equal odd widths, one height, and opposite faces.")
                }
                val prefab = if (valid) Prefab(file.name, width, height, depth, source, connectors) else null
                return result(file, LocalBounds(0, 0, 0, width - 1, height - 1, depth - 1), content, structural,
                    connectors, markerOffsets, valid, problems, prefab)
            }
        } catch (exception: IOException) {
            problems.add("Could not read schematic: ${exception.message}")
            plugin.logger.warning("Could not load corridor ${file.name}: ${exception.message}")
            return result(file, null, null, null, emptyList(), emptyList(), false, problems, null)
        } catch (exception: RuntimeException) {
            problems.add("Could not read schematic: ${exception.message}")
            plugin.logger.warning("Could not load corridor ${file.name}: ${exception.message}")
            return result(file, null, null, null, emptyList(), emptyList(), false, problems, null)
        }
    }

    private class Materials(val connector: Material, val guide: Material,
                            val connectorReplacement: Material, val guideReplacement: Material) {
        companion object {
            fun read(config: FileConfiguration): Materials = Materials(
                material(config, "generation.corridor.schematic.markers.connector.material", Material.PURPLE_WOOL),
                material(config, "generation.corridor.schematic.markers.guide.material", Material.CYAN_WOOL),
                material(config, "generation.corridor.schematic.markers.replacements.connector", Material.AIR),
                material(config, "generation.corridor.schematic.markers.replacements.guide", Material.AIR))
        }
    }

    private class Prefab(val fileName: String, val width: Int, val height: Int, val depth: Int,
                         blocks: List<SourceBlock>, connectors: List<Connector>) {
        val blocks: List<SourceBlock> = blocks.toList()
        val connectors: List<Connector> = connectors.toList()
        fun other(connector: Connector): Connector =
            if (connectors.first() == connector) connectors.last() else connectors.first()
    }

    private class SourceBlock(val x: Int, val y: Int, val z: Int, val state: BlockState, val replacement: Material?)

    private data class MarkerPoint(val x: Int, val y: Int, val z: Int) {
        fun adjacent(other: MarkerPoint): Boolean =
            Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z) == 1
    }

    private data class Connector(val x: Int, val y: Int, val z: Int, val face: BlockFace, val width: Int) {
        fun description(): String = "$face centre=$x,$y,$z width=$width"
    }

    private class Endpoint(val marker: DungeonDoorMarker)

    private class Candidate(val prefab: Prefab, val rotation: Int, val originX: Int, val originY: Int, val originZ: Int,
                            val stepX: Int, val stepZ: Int, val tiles: Int, val lastOriginX: Int, val lastOriginZ: Int)

    private data class Point(val x: Int, val z: Int)

    private class LoadResult(val prefab: Prefab?, val inspection: Inspection)

    private class CandidateSearch(val candidates: List<Candidate>, val reasons: List<String>)

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

    /** Immutable data used by /dungeon corridors. */
    class Inspection(val fileName: String, val statedDimensions: String, val actualDimensions: String,
                     val trimmedDimensions: String, val connectors: String,
                     markerVerticalOffsets: List<Int>, val valid: Boolean, problems: List<String>) {
        val markerVerticalOffsets: List<Int> = markerVerticalOffsets.toList()
        val problems: List<String> = problems.toList()

        fun markerOffsets(): String =
            if (markerVerticalOffsets.isEmpty()) "none"
            else markerVerticalOffsets.joinToString(", ") { "y" + (if (it >= 0) "+" else "") + it }

        fun displayProblems(): String = if (problems.isEmpty()) "none" else problems.joinToString(" | ")
    }

    /** Tick-spread operations plus the tunnels that should not receive a procedural floor or lip. */
    class CorridorPlan(schematicTunnelIds: Set<String>, operations: List<BuildOperation>) {
        val schematicTunnelIds: Set<String> = schematicTunnelIds.toSet()
        val operations: List<BuildOperation> = operations.toList()

        companion object {
            fun empty(): CorridorPlan = CorridorPlan(emptySet(), emptyList())
        }
    }

    companion object {
        private val CARDINAL = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

        /**
         * Exact connector placement matters more than an exact multiple of
         * the tile stride. The final repeat may overlap the preceding tile
         * slightly, which is safe for a deliberately tileable middle segment
         * and lets room schematics use their natural dimensions.
         */
        private fun tilesToJoin(deltaX: Int, deltaZ: Int, stepX: Int, stepZ: Int): Int {
            val tilesX = axisTiles(deltaX, stepX)
            val tilesZ = axisTiles(deltaZ, stepZ)
            if (tilesX != null && tilesX < 1 || tilesZ != null && tilesZ < 1) return -1
            if (tilesX != null && tilesZ != null && tilesX != tilesZ) return -1
            return tilesX ?: tilesZ ?: -1
        }

        /** A null result means this axis is stationary and therefore imposes no tile count. */
        private fun axisTiles(delta: Int, step: Int): Int? {
            if (step == 0) return if (delta == 0) null else -1
            if (Integer.signum(delta) != Integer.signum(step)) return -1
            val distance = Math.abs(delta)
            val stride = Math.abs(step)
            if (distance < stride) return 2
            return (distance + stride - 1) / stride
        }

        private fun parseConnectors(markers: List<MarkerPoint>, width: Int, depth: Int,
                                    problems: MutableList<String>): List<Connector> {
            val unvisited = HashSet(markers)
            val connectors = ArrayList<Connector>()
            while (unvisited.isNotEmpty()) {
                val seed = unvisited.first()
                val group = ArrayList<MarkerPoint>()
                val pending = ArrayDeque<MarkerPoint>()
                pending.add(seed)
                unvisited.remove(seed)
                while (pending.isNotEmpty()) {
                    val point = pending.removeFirst()
                    group.add(point)
                    val neighbours = unvisited.filter { point.adjacent(it) }
                    for (neighbour in neighbours) {
                        unvisited.remove(neighbour)
                        pending.add(neighbour)
                    }
                }
                val connector = connector(group, width, depth)
                if (connector == null) {
                    problems.add("Purple connector markers must form one contiguous odd-width line on one outer side wall.")
                } else {
                    connectors.add(connector)
                }
            }
            connectors.sortWith(compareBy { it.face.name })
            if (connectors.size != 2) {
                problems.add("Expected exactly two purple connector groups, but found ${connectors.size}.")
            }
            return connectors.toList()
        }

        private fun connector(group: List<MarkerPoint>, width: Int, depth: Int): Connector? {
            val y = group.first().y
            if (group.any { it.y != y }) return null
            val face = wall(group.first(), width, depth)
            if (face == null || group.any { wall(it, width, depth) != face }) return null
            val alongX = face == BlockFace.NORTH || face == BlockFace.SOUTH
            val min = group.minOf { if (alongX) it.x else it.z }
            val max = group.maxOf { if (alongX) it.x else it.z }
            if (group.size != max - min + 1 || (group.size and 1) == 0) return null
            val centre = (min + max) / 2
            val x = if (alongX) centre else if (face == BlockFace.WEST) 0 else width - 1
            val z = if (alongX) (if (face == BlockFace.NORTH) 0 else depth - 1) else centre
            return Connector(x, y, z, face, group.size)
        }

        private fun reportBounds(width: Int, height: Int, depth: Int, content: LocalBounds?, structural: LocalBounds?,
                                 connectors: List<MarkerPoint>, guides: List<MarkerPoint>, problems: MutableList<String>) {
            if (content != null && (content.sizeX() != width || content.sizeY() != height || content.sizeZ() != depth)) {
                problems.add("Empty outer padding will be trimmed: content is ${content.dimensions()} inside the stated " +
                    "${width}x${height}x$depth selection.")
            }
            // Marker blocks deliberately may sit in a separate authoring layer.
            // Their relative offset is reported, but does not affect validity.
        }

        private fun verticalOffsets(connectors: List<Connector>, structural: LocalBounds?): List<Int> {
            if (structural == null) return emptyList()
            return connectors.map { it.y - structural.maxY }.distinct().sorted()
        }

        private fun include(bounds: LocalBounds?, x: Int, y: Int, z: Int): LocalBounds =
            bounds?.include(x, y, z) ?: LocalBounds(x, y, z, x, y, z)

        private fun wall(point: MarkerPoint, width: Int, depth: Int): BlockFace? {
            val walls = (if (point.x == 0) 1 else 0) + (if (point.x == width - 1) 1 else 0) +
                (if (point.z == 0) 1 else 0) + (if (point.z == depth - 1) 1 else 0)
            if (walls != 1) return null
            if (point.x == 0) return BlockFace.WEST
            if (point.x == width - 1) return BlockFace.EAST
            return if (point.z == 0) BlockFace.NORTH else BlockFace.SOUTH
        }

        private fun face(room: Bounds, doorway: Bounds): BlockFace {
            if (doorway.minX == room.minX) return BlockFace.WEST
            if (doorway.maxX == room.maxX) return BlockFace.EAST
            if (doorway.minZ == room.minZ) return BlockFace.NORTH
            if (doorway.maxZ == room.maxZ) return BlockFace.SOUTH
            throw IllegalArgumentException("Doorway does not lie on its room boundary.")
        }

        private fun opposite(face: BlockFace): BlockFace = when (face) {
            BlockFace.NORTH -> BlockFace.SOUTH
            BlockFace.EAST -> BlockFace.WEST
            BlockFace.SOUTH -> BlockFace.NORTH
            BlockFace.WEST -> BlockFace.EAST
            else -> throw IllegalArgumentException("Only cardinal connector faces are supported.")
        }

        private fun rotate(face: BlockFace, rotation: Int): BlockFace = when (Math.floorMod(rotation, 360)) {
            0 -> face
            90 -> when (face) {
                BlockFace.NORTH -> BlockFace.EAST
                BlockFace.EAST -> BlockFace.SOUTH
                BlockFace.SOUTH -> BlockFace.WEST
                BlockFace.WEST -> BlockFace.NORTH
                else -> face
            }
            180 -> opposite(face)
            270 -> when (face) {
                BlockFace.NORTH -> BlockFace.WEST
                BlockFace.EAST -> BlockFace.NORTH
                BlockFace.SOUTH -> BlockFace.EAST
                BlockFace.WEST -> BlockFace.SOUTH
                else -> face
            }
            else -> throw IllegalArgumentException("Only right-angle corridor rotations are supported.")
        }

        private fun rotate(x: Int, z: Int, width: Int, depth: Int, rotation: Int): Point = when (Math.floorMod(rotation, 360)) {
            0 -> Point(x, z)
            90 -> Point(depth - 1 - z, x)
            180 -> Point(width - 1 - x, depth - 1 - z)
            270 -> Point(z, width - 1 - x)
            else -> throw IllegalArgumentException("Only right-angle corridor rotations are supported.")
        }

        private fun box(bounds: Bounds): String =
            "${bounds.minX}..${bounds.maxX} x ${bounds.minZ}..${bounds.maxZ}" +
                " (${bounds.sizeX()}x${bounds.sizeZ()})"

        private fun endpointDescription(label: String, endpoint: Endpoint): String =
            "$label red marker ${endpoint.marker.x},${endpoint.marker.y},${endpoint.marker.z}" +
                " facing ${endpoint.marker.facing} width ${endpoint.marker.width}"

        private fun material(config: FileConfiguration, path: String, fallback: Material): Material {
            val raw = config.getString(path, fallback.name)
            val material = raw?.let { Material.matchMaterial(it.uppercase(Locale.ROOT)) }
            return if (material == null || !material.isBlock) fallback else material
        }

        private fun result(file: File, stated: LocalBounds?, content: LocalBounds?, structural: LocalBounds?,
                           connectors: List<Connector>, markerOffsets: List<Int>, valid: Boolean,
                           problems: List<String>, prefab: Prefab?): LoadResult {
            return LoadResult(prefab, Inspection(file.name, stated?.dimensions() ?: "none",
                content?.dimensions() ?: "none",
                if (structural == null || stated == null) "none"
                else structural.trimDescription(stated.sizeX(), stated.sizeY(), stated.sizeZ()),
                connectors.joinToString("; ") { it.description() }.ifEmpty { "none" },
                markerOffsets.toList(), valid, problems.toList()))
        }
    }
}
