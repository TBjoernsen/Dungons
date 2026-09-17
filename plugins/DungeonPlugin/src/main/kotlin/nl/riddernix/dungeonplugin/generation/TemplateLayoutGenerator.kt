package nl.riddernix.dungeonplugin.generation

import nl.riddernix.dungeonplugin.generation.TemplateGeometry.Dir
import nl.riddernix.dungeonplugin.generation.TemplateGeometry.LocalDoor
import nl.riddernix.dungeonplugin.generation.TemplateGeometry.Origin
import nl.riddernix.dungeonplugin.generation.TemplateGeometry.WorldDoor
import org.bukkit.configuration.file.FileConfiguration
import java.util.Locale
import java.util.Random
import java.util.logging.Logger

/** One loaded room file as the planner sees it: pure geometry, no blocks. */
class CatalogueRoom(
    val fileName: String,
    val pool: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    doors: List<LocalDoor>,
    /** Index of a green-pinned entrance door, or null when free to rotate. */
    val pinnedEntrance: Int?
) {
    val doors: List<LocalDoor> = doors.toList()
}

/** A chosen real room: which file, how it is turned, where it stands, which doors serve. */
class RoomPlacement(
    val fileName: String,
    val rotation: Int,
    val origin: Origin,
    usedDoorIndexes: Set<Int>,
    sealedDoorIndexes: Set<Int>
) {
    val usedDoorIndexes: Set<Int> = usedDoorIndexes.toSet()
    val sealedDoorIndexes: Set<Int> = sealedDoorIndexes.toSet()
}

/** A generated stand-in for a slot whose pool has no usable file. */
class PlaceholderPlacement(
    val role: String,
    val pool: String,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val origin: Origin,
    doors: List<PlaceholderShell.ShellDoor>,
    val highFace: Dir?,
    val playerSpawn: Boolean,
    doorMarkers: List<WorldDoor>
) {
    val doors: List<PlaceholderShell.ShellDoor> = doors.toList()
    /** The same doors resolved to world space, for corridors and audits. */
    val doorMarkers: List<WorldDoor> = doorMarkers.toList()
}

/** The planned dungeon plus everything the room library needs to build it. */
class TemplatePlan(
    val layout: DungeonLayout,
    placements: Map<String, RoomPlacement>,
    placeholders: Map<String, PlaceholderPlacement>,
    summary: List<String>
) {
    val placements: Map<String, RoomPlacement> = placements.toMap()
    val placeholders: Map<String, PlaceholderPlacement> = placeholders.toMap()
    val summary: List<String> = summary.toList()

    /** Moves the whole plan, for grid previews; room ids stay stable. */
    fun translate(x: Int, y: Int, z: Int): TemplatePlan {
        val movedPlacements = placements.mapValues { (_, placement) ->
            RoomPlacement(placement.fileName, placement.rotation,
                Origin(placement.origin.x + x, placement.origin.y + y, placement.origin.z + z),
                placement.usedDoorIndexes, placement.sealedDoorIndexes)
        }
        val movedPlaceholders = placeholders.mapValues { (_, placeholder) ->
            PlaceholderPlacement(placeholder.role, placeholder.pool,
                placeholder.sizeX, placeholder.sizeY, placeholder.sizeZ,
                Origin(placeholder.origin.x + x, placeholder.origin.y + y, placeholder.origin.z + z),
                placeholder.doors, placeholder.highFace, placeholder.playerSpawn,
                placeholder.doorMarkers.map { it.translated(x, y, z) })
        }
        return TemplatePlan(layout.translate(x, y, z), movedPlacements, movedPlaceholders, summary)
    }
}

/**
 * Plans a dungeon from the configured template: an ordered chain of slots,
 * each placed flush against the previous room's chosen exit door, plus one
 * key branch per required key. Rooms are selected during planning, so every
 * slot reserves exactly the footprint of the room that will stand there.
 *
 * There is no silent fallback for placement: when a slot cannot be placed
 * the whole generation fails with a report naming the slot and every reason,
 * because the template is hand-designed and a failure is a real
 * inconsistency. An empty pool is not a failure - it becomes a placeholder
 * shell - but a collision is.
 */
class TemplateLayoutGenerator(
    private val config: FileConfiguration,
    private val templates: TemplateConfig,
    private val catalogue: (String) -> List<CatalogueRoom>,
    private val logger: Logger
) {

    private val originX = config.getInt("generation.entrance.origin.x", 0)
    private val originY = config.getInt("generation.entrance.origin.y", 64)
    private val originZ = config.getInt("generation.entrance.origin.z", 0)
    private val exitDirection = direction(config.getString("generation.entrance.exit-direction", "SOUTH"))
    private val corridorLength = maxOf(3, config.getInt("generation.corridor.length", 18))
    private val platformWidth = oddAtLeast(config.getInt("generation.corridor.platform-width", 7), 3)
    private val corridorHeight = maxOf(3, config.getInt("generation.corridor.inner-height", 8))
    private val safetyLips = config.getBoolean("generation.corridor.safety-lips.enabled", true)
    private val safetyLipHeight = maxOf(1, config.getInt("generation.corridor.safety-lips.height", 1))
    private val openingWidth = oddAtLeast(config.getInt("generation.rooms.markers.doorway.minimum-opening-width", 3), 3)
    private val openingHeight = maxOf(3, config.getInt("generation.rooms.markers.doorway.minimum-opening-height", 3))
    private val markerWidth = oddAtLeast(config.getInt("generation.template.placeholder.door-marker-width", 3), 1)
    private val markerHeightAboveFloor = maxOf(1,
        config.getInt("generation.corridor.schematic.virtual-door-marker.height-above-floor", 30))
    private val stairsDrop = maxOf(1, config.getInt("generation.template.placeholder.stairs-drop", 6))
    private val greatHallDrop = maxOf(0, config.getInt("generation.template.placeholder.great-hall-drop", 12))
    private val maxAttempts = maxOf(200, config.getInt("generation.max-placement-attempts", 600))

    @Throws(GenerationException::class)
    fun generate(difficulty: Int, seed: Long): TemplatePlan {
        if (difficulty < 1 || difficulty > 9) {
            throw GenerationException("Difficulty must be between 1 and 9.")
        }
        val expansion = try {
            templates.expand(difficulty)
        } catch (exception: TemplateConfig.TemplateException) {
            throw GenerationException(exception.message ?: "The template configuration is invalid.")
        }
        // Branches are known before the main path is placed, so a combat room
        // that must carry a branch door is selected with that door required.
        val branchesByCombat = HashMap<Int, Int>()
        for (branch in expansion.branches) {
            branchesByCombat.merge(branch.attachCombat, 1, Int::plus)
        }

        val state = PlannerState(seed)
        placeMainPath(expansion, branchesByCombat, state)
        placeBranches(expansion, state)

        val rooms = state.rooms.map { it.toLayoutRoom() }
        var bounds = rooms.first().bounds
        for (room in rooms) bounds = Bounds.union(bounds, room.bounds)
        for (tunnel in state.tunnels) for (box in tunnel.occupied()) bounds = Bounds.union(bounds, box)

        val keyGate = keyGate(expansion, state)
        val spawn = state.rooms.first()
        val layout = DungeonLayout(seed, difficulty, rooms, state.tunnels, bounds,
            spawn.box.centreX(), spawn.floorY, spawn.box.centreZ(), keyGate)
        val summary = state.rooms.map { placed ->
            "slot ${placed.id} ${placed.slot.role}/${placed.slot.sizeClass.name}" +
                (placed.slot.mobs?.let { " mobs=$it" } ?: "") +
                (if (placed.slot.miniboss) " miniboss" else "") +
                ": " + (placed.placement?.let { "${it.fileName} rot${it.rotation}" } ?: "PLACEHOLDER") +
                " at ${placed.box.minX},${placed.box.minY},${placed.box.minZ} floor y=${placed.floorY}"
        }
        return TemplatePlan(layout,
            state.rooms.mapNotNull { placed -> placed.placement?.let { placed.id to it } }.toMap(),
            state.rooms.mapNotNull { placed -> placed.placeholder?.let { placed.id to it } }.toMap(),
            summary)
    }

    // ------------------------------------------------------------------
    // Main path
    // ------------------------------------------------------------------

    /**
     * Depth-first placement with backtracking: each slot keeps its ordered
     * candidate list, and a slot that cannot be placed rewinds the one before
     * it to its next candidate. The attempt budget caps the whole search.
     */
    private fun placeMainPath(expansion: TemplateConfig.Expansion, branchesByCombat: Map<Int, Int>,
                              state: PlannerState) {
        val slots = expansion.mainPath
        val stacks = ArrayList<Iterator<Candidate>>()
        val failures = HashMap<Int, MutableList<String>>()
        var attempts = 0
        var index = 0
        var combatSeen = 0
        val combatNumberAt = IntArray(slots.size)
        for (slotIndex in slots.indices) {
            if (slots[slotIndex].role == "combat") combatSeen++
            combatNumberAt[slotIndex] = combatSeen
        }

        while (index < slots.size) {
            if (attempts++ > maxAttempts) {
                throw GenerationException(failureReport(slots, index, failures,
                    "the placement attempt budget (generation.max-placement-attempts) ran out"))
            }
            if (stacks.size <= index) {
                val slot = slots[index]
                val branchDoors = if (slot.role == "combat") branchesByCombat[combatNumberAt[index]] ?: 0 else 0
                val entry = if (index == 0) null else state.rooms[index - 1].exitDoor
                    ?: throw GenerationException("Slot $index has no exit door on the previous room; " +
                        "this is a planner bug.")
                stacks.add(candidates(slot, index, slots.size, entry, branchDoors, state,
                    failures.getOrPut(index) { ArrayList() }).iterator())
            }
            val iterator = stacks[index]
            var placed: PlacedRoom? = null
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                placed = tryPlace(candidate, state, failures.getValue(index))
                if (placed != null) break
            }
            if (placed == null) {
                stacks.removeAt(index)
                failures.getValue(index).add("every candidate was tried")
                if (index == 0) {
                    throw GenerationException(failureReport(slots, index, failures, null))
                }
                index--
                state.removeLast()
                continue
            }
            state.add(placed)
            index++
        }
    }

    private fun failureReport(slots: List<TemplateConfig.Slot>, index: Int,
                              failures: Map<Int, List<String>>, extra: String?): String {
        val slot = slots[minOf(index, slots.size - 1)]
        val reasons = ArrayList<String>()
        if (extra != null) reasons.add(extra)
        reasons.addAll((failures[index] ?: emptyList()).distinct().take(12))
        return "Could not place slot ${index + 1} of ${slots.size} " +
            "('${slot.role}', class ${slot.sizeClass.name}, pool '${slot.pool}'): " +
            reasons.joinToString(" | ").ifEmpty { "no candidate room fits" } +
            ". The template is hand-designed, so this points at a real inconsistency - a room pool " +
            "whose doors cannot serve this slot, or a footprint that cannot avoid the rooms already placed."
    }

    // ------------------------------------------------------------------
    // Candidates
    // ------------------------------------------------------------------

    private class Candidate(
        val slot: TemplateConfig.Slot,
        val slotIndex: Int,
        val room: CatalogueRoom?,
        val rotation: Int,
        val entryIndex: Int?,
        val exitIndex: Int?,
        val branchIndexes: List<Int>,
        val entryDoor: WorldDoor?,
        val sealedCount: Int,
        val placeholderExit: Dir?,
        val branchDoorCount: Int
    )

    /**
     * Enumerates every way this slot can stand against [entry]: each pool
     * file, rotation, entry-door and exit-door choice - preferring rooms
     * whose door count matches exactly, then a straight run over a turn.
     * The placeholder is a real candidate only when the pool has nothing
     * usable, never a silent fallback from a collision.
     */
    private fun candidates(slot: TemplateConfig.Slot, slotIndex: Int, slotCount: Int, entry: WorldDoor?,
                           branchDoors: Int, state: PlannerState, failures: MutableList<String>): List<Candidate> {
        val needsExit = slotIndex < slotCount - 1 && !slot.deadEnd
        val pool = catalogue(slot.pool).filter { it.width == slot.sizeClass.x &&
            it.height == slot.sizeClass.y && it.depth == slot.sizeClass.z }
        val result = ArrayList<Candidate>()
        for (room in pool) {
            for (rotation in intArrayOf(0, 90, 180, 270)) {
                val entryChoices: List<Int?> = if (entry == null) listOf(null)
                    else room.doors.indices.filter { room.doors[it].face.rotated(rotation) == entry.face.opposite() }
                for (entryIndex in entryChoices) {
                    if (room.pinnedEntrance != null && entryIndex != null && entryIndex != room.pinnedEntrance) continue
                    val remaining = room.doors.indices.filter { it != entryIndex }
                    val exitChoices: List<Int?> = if (!needsExit) listOf(null) else remaining.filter { exit ->
                        val exitFace = room.doors[exit].face.rotated(rotation)
                        entry == null || exitFace != entry.face.opposite()
                    }
                    for (exitIndex in exitChoices) {
                        if (needsExit && exitIndex == null) continue
                        if (entry == null && exitIndex != null &&
                            room.doors[exitIndex].face.rotated(rotation) != exitDirection) continue
                        val entryFloor = entryIndex?.let { room.doors[it].openingFloor }
                        val exitFloor = exitIndex?.let { room.doors[it].openingFloor }
                        if (entryFloor != null && exitFloor != null) {
                            if (slot.stairsDown && entryFloor <= exitFloor) continue
                            if (!slot.stairsDown && slot.pool != "great_hall" && entryFloor != exitFloor) continue
                            if (slot.pool == "great_hall" && exitFloor > entryFloor) continue
                        }
                        val branchPool = remaining.filter { it != exitIndex }
                        if (branchPool.size < branchDoors) continue
                        val branchIndexes = branchPool.take(branchDoors)
                        val sealed = room.doors.size - (if (entryIndex == null) 0 else 1) -
                            (if (exitIndex == null) 0 else 1) - branchIndexes.size
                        result.add(Candidate(slot, slotIndex, room, rotation, entryIndex, exitIndex,
                            branchIndexes, entry, sealed, null, branchDoors))
                    }
                }
            }
        }
        if (pool.isEmpty() && catalogue(slot.pool).isNotEmpty()) {
            failures.add("pool '${slot.pool}' has ${catalogue(slot.pool).size} file(s) but none matches class " +
                "${slot.sizeClass.name} (${slot.sizeClass.dimensions()})")
        }
        if (result.isEmpty() && pool.isNotEmpty()) {
            logger.severe("Template slot '${slot.role}' (pool '${slot.pool}'): none of the ${pool.size} valid " +
                "file(s) offers the doors this slot needs" +
                (if (slot.stairsDown) " (a stairs room: two doors on different floors)" else "") +
                (if (branchDoors > 0) " ($branchDoors spare branch door(s) required)" else "") +
                "; a placeholder shell will stand in.")
        }
        if (result.isEmpty()) {
            // The placeholder chooses its own exit heading; try straight
            // first, then both turns, so a blocked heading still resolves.
            // The spawn shell keeps the configured exit direction exactly.
            val headings: List<Dir?> = when {
                !needsExit -> listOf(null)
                entry == null -> listOf(exitDirection)
                else -> listOf(entry.face, entry.face.left(), entry.face.right())
            }
            for (heading in headings) {
                result.add(Candidate(slot, slotIndex, null, 0, null, null, emptyList(), entry, 0, heading,
                    branchDoors))
            }
            return result
        }
        val random = Random(state.seed xor 0x534c4f54u.toLong() xor (slotIndex.toLong() shl 17))
        // Deterministic: exact door counts first, straight before turns, and
        // a seeded shuffle inside each equivalence group.
        val grouped = result.groupBy { candidate ->
            val straight = if (entry == null || candidate.exitIndex == null) 0
                else if (candidate.room!!.doors[candidate.exitIndex].face.rotated(candidate.rotation) == entry.face) 0
                else 1
            candidate.sealedCount * 2 + straight
        }
        val ordered = ArrayList<Candidate>()
        for (key in grouped.keys.sorted()) {
            val group = ArrayList(grouped.getValue(key)
                .sortedWith(compareBy<Candidate, String>(String.CASE_INSENSITIVE_ORDER) { it.room!!.fileName }
                    .thenBy { it.rotation }))
            // Fisher-Yates with the seeded random keeps selection reproducible.
            for (i in group.size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val swap = group[i]; group[i] = group[j]; group[j] = swap
            }
            ordered.addAll(group)
        }
        return ordered
    }

    // ------------------------------------------------------------------
    // Placement
    // ------------------------------------------------------------------

    private fun tryPlace(candidate: Candidate, state: PlannerState, failures: MutableList<String>,
                         parentId: String? = null): PlacedRoom? {
        val slot = candidate.slot
        val id = (state.rooms.size + 1).toString()
        val connectedTo = parentId ?: state.rooms.lastOrNull()?.id
        if (candidate.room != null) {
            val room = candidate.room
            val origin = if (candidate.entryDoor == null || candidate.entryIndex == null) {
                Origin(originX, originY, originZ)
            } else {
                TemplateGeometry.alignFlush(candidate.entryDoor, room.doors[candidate.entryIndex],
                    room.width, room.depth, candidate.rotation)
            }
            val size = TemplateGeometry.rotatedSize(room.width, room.depth, candidate.rotation)
            val box = Bounds(origin.x, origin.y, origin.z,
                origin.x + size.sizeX - 1, origin.y + room.height - 1, origin.z + size.sizeZ - 1)
            val blocking = TemplateGeometry.collides(box, state.reserved)
            if (blocking != null) {
                failures.add("${room.fileName} rot${candidate.rotation} would overlap " +
                    "${state.owner(blocking)} at ${describe(blocking)}")
                return null
            }
            val doors = room.doors.mapIndexed { doorIndex, door ->
                TemplateGeometry.worldDoor(door, room.width, room.depth, candidate.rotation, origin)
            }
            val used = HashSet<Int>()
            candidate.entryIndex?.let(used::add)
            candidate.exitIndex?.let(used::add)
            used.addAll(candidate.branchIndexes)
            val sealed = room.doors.indices.filterNot { it in used }.toSet()
            val entryWorld = candidate.entryIndex?.let { doors[it] }
            val floor = entryWorld?.floor ?: candidate.exitIndex?.let { doors[it].floor } ?: (origin.y + 1)
            return PlacedRoom(id, slot, box, floor,
                entryWorld, candidate.exitIndex?.let { doors[it] },
                candidate.branchIndexes.map { doors[it] },
                RoomPlacement(room.fileName, candidate.rotation, origin, used, sealed), null,
                flushTunnel(connectedTo, candidate.entryDoor, entryWorld, id))
        }

        // Placeholder shell: doors are synthesised exactly where the layout
        // needs them, on wall centres, with the class's own footprint.
        val sizeX = slot.sizeClass.x
        val sizeY = slot.sizeClass.y
        val sizeZ = slot.sizeClass.z
        val drop = when {
            slot.stairsDown -> minOf(stairsDrop, sizeY - openingHeight - 3)
            slot.pool == "great_hall" -> minOf(greatHallDrop, sizeY - openingHeight - 3)
            else -> 0
        }.coerceAtLeast(0)
        val entryFace = candidate.entryDoor?.face?.opposite()
        val lowFloor = 1
        val highFloor = lowFloor + drop
        val doors = ArrayList<PlaceholderShell.ShellDoor>()
        val entryLocal = entryFace?.let { face ->
            PlaceholderShell.ShellDoor(face, wallCentre(face, sizeX, sizeZ), highFloor)
        }
        entryLocal?.let(doors::add)
        val exitFace = candidate.placeholderExit
        if (exitFace != null) {
            doors.add(PlaceholderShell.ShellDoor(exitFace, wallCentre(exitFace, sizeX, sizeZ), lowFloor))
        }
        val branchFaces = ArrayList<Dir>()
        if (candidate.slot.role == "combat") {
            val taken = HashSet<Dir>()
            entryFace?.let(taken::add)
            exitFace?.let(taken::add)
            val wanted = candidate.branchDoorCount
            for (face in Dir.entries) {
                if (branchFaces.size >= wanted) break
                if (face in taken) continue
                branchFaces.add(face)
                doors.add(PlaceholderShell.ShellDoor(face, wallCentre(face, sizeX, sizeZ), highFloor))
            }
        }
        val origin = if (candidate.entryDoor == null || entryLocal == null) {
            Origin(originX, originY, originZ)
        } else {
            TemplateGeometry.alignFlush(candidate.entryDoor,
                shellLocalDoor(entryLocal), sizeX, sizeZ, 0)
        }
        val box = Bounds(origin.x, origin.y, origin.z,
            origin.x + sizeX - 1, origin.y + sizeY - 1, origin.z + sizeZ - 1)
        val blocking = TemplateGeometry.collides(box, state.reserved)
        if (blocking != null) {
            failures.add("the ${slot.sizeClass.name} placeholder shell would overlap " +
                "${state.owner(blocking)} at ${describe(blocking)}")
            return null
        }
        val worldDoors = doors.map { door ->
            TemplateGeometry.worldDoor(shellLocalDoor(door), sizeX, sizeZ, 0, origin)
        }
        val entryWorld = entryLocal?.let { worldDoors[doors.indexOf(it)] }
        val exitWorld = exitFace?.let { face -> worldDoors[doors.indexOfFirst { it.face == face && it !== entryLocal }] }
        val branchWorld = branchFaces.map { face -> worldDoors[doors.indexOfFirst { it.face == face }] }
        return PlacedRoom(id, slot, box, origin.y + highFloor, entryWorld, exitWorld, branchWorld, null,
            PlaceholderPlacement(slot.role, slot.pool, sizeX, sizeY, sizeZ, origin, doors,
                entryFace ?: exitFace, slot.pool == "spawn", worldDoors),
            flushTunnel(connectedTo, candidate.entryDoor, entryWorld, id))
    }

    /** Placeholder doors share the virtual marker convention real prefabs fall back to. */
    private fun shellLocalDoor(door: PlaceholderShell.ShellDoor): LocalDoor =
        LocalDoor(door.face, door.cross, door.floor - 1 + markerHeightAboveFloor, door.floor,
            openingWidth, openingHeight, markerWidth)

    private fun wallCentre(face: Dir, sizeX: Int, sizeZ: Int): Int =
        if (face == Dir.NORTH || face == Dir.SOUTH) (sizeX - 1) / 2 else (sizeZ - 1) / 2

    /** The zero-length connection: two adjacent wall planes, one shared passage. */
    private fun flushTunnel(parentId: String?, exit: WorldDoor?, entry: WorldDoor?, childId: String):
        DungeonLayout.Tunnel? {
        if (parentId == null || exit == null || entry == null) return null
        val exitOpening = TemplateGeometry.doorOpening(exit)
        val entryOpening = TemplateGeometry.doorOpening(entry)
        return DungeonLayout.Tunnel(parentId, childId, exitOpening, entryOpening,
            emptyList(), emptyList(), listOf(exitOpening, entryOpening))
    }

    // ------------------------------------------------------------------
    // Key branches
    // ------------------------------------------------------------------

    private fun placeBranches(expansion: TemplateConfig.Expansion, state: PlannerState) {
        for (branch in expansion.branches) {
            val combat = state.combatRoom(branch.attachCombat)
                ?: throw GenerationException("Key branch wants combat room ${branch.attachCombat}" +
                    " but the main path only placed ${state.combatCount()} combat room(s).")
            val door = combat.takeBranchDoor()
                ?: throw GenerationException("Key branch could not attach to combat room ${branch.attachCombat}" +
                    " (room ${combat.id}): its placed room has no spare branch door left. " +
                    "Give the combat pool a room with more doorways or lower the keys count.")
            var previousDoor = door
            var previousRoom = combat
            for (index in branch.rooms.indices) {
                val slot = branch.rooms[index]
                val corridor = index == 0 && slot.connection == TemplateConfig.Connection.CORRIDOR
                val needsExit = index < branch.rooms.size - 1
                val placed = placeBranchRoom(slot, previousDoor, previousRoom, corridor, needsExit, state)
                state.add(placed)
                previousRoom = placed
                previousDoor = placed.exitDoor ?: previousDoor
            }
        }
    }

    private fun placeBranchRoom(slot: TemplateConfig.Slot, from: WorldDoor, parent: PlacedRoom,
                                corridor: Boolean, needsExit: Boolean, state: PlannerState): PlacedRoom {
        val failures = ArrayList<String>()
        val slotIndex = state.rooms.size
        val slotCount = if (needsExit) slotIndex + 2 else slotIndex + 1
        for (candidate in candidates(slot, slotIndex, slotCount, from, 0, state, failures)) {
            val placed = tryPlaceBranch(candidate, from, parent, corridor, state, failures)
            if (placed != null) return placed
        }
        throw GenerationException("Could not place key-branch room '${slot.role}' (pool '${slot.pool}', class " +
            "${slot.sizeClass.name}) off room ${parent.id}: " +
            failures.distinct().take(12).joinToString(" | ").ifEmpty { "no candidate fits" })
    }

    private fun tryPlaceBranch(candidate: Candidate, from: WorldDoor, parent: PlacedRoom, corridor: Boolean,
                               state: PlannerState, failures: MutableList<String>): PlacedRoom? {
        if (!corridor) {
            val placed = tryPlace(candidate, state, failures, parent.id) ?: return null
            return placed.copyWithDepth(parent.depth + 1)
        }
        val id = (state.rooms.size + 1).toString()
        val gap = corridorLength
        val room = candidate.room
        val origin: Origin
        val entryWorld: WorldDoor?
        val exitWorld: WorldDoor?
        val branchWorld: List<WorldDoor>
        val box: Bounds
        var placement: RoomPlacement? = null
        var placeholder: PlaceholderPlacement? = null
        if (room != null && candidate.entryIndex != null) {
            origin = TemplateGeometry.alignAcrossGap(from, room.doors[candidate.entryIndex],
                room.width, room.depth, candidate.rotation, gap)
            val size = TemplateGeometry.rotatedSize(room.width, room.depth, candidate.rotation)
            box = Bounds(origin.x, origin.y, origin.z,
                origin.x + size.sizeX - 1, origin.y + room.height - 1, origin.z + size.sizeZ - 1)
            val doors = room.doors.map { TemplateGeometry.worldDoor(it, room.width, room.depth, candidate.rotation, origin) }
            entryWorld = doors[candidate.entryIndex]
            exitWorld = candidate.exitIndex?.let { doors[it] }
            branchWorld = emptyList()
            val used = HashSet<Int>()
            used.add(candidate.entryIndex)
            candidate.exitIndex?.let(used::add)
            placement = RoomPlacement(room.fileName, candidate.rotation, origin, used,
                room.doors.indices.filterNot { it in used }.toSet())
        } else {
            val sizeX = candidate.slot.sizeClass.x
            val sizeY = candidate.slot.sizeClass.y
            val sizeZ = candidate.slot.sizeClass.z
            val entryFace = from.face.opposite()
            val doors = ArrayList<PlaceholderShell.ShellDoor>()
            doors.add(PlaceholderShell.ShellDoor(entryFace, wallCentre(entryFace, sizeX, sizeZ), 1))
            val exitFace = candidate.placeholderExit
            if (exitFace != null) doors.add(PlaceholderShell.ShellDoor(exitFace, wallCentre(exitFace, sizeX, sizeZ), 1))
            origin = TemplateGeometry.alignAcrossGap(from, shellLocalDoor(doors.first()), sizeX, sizeZ, 0, gap)
            box = Bounds(origin.x, origin.y, origin.z,
                origin.x + sizeX - 1, origin.y + sizeY - 1, origin.z + sizeZ - 1)
            val worldDoors = doors.map { TemplateGeometry.worldDoor(shellLocalDoor(it), sizeX, sizeZ, 0, origin) }
            entryWorld = worldDoors.first()
            exitWorld = if (exitFace != null) worldDoors[1] else null
            branchWorld = emptyList()
            placeholder = PlaceholderPlacement(candidate.slot.role, candidate.slot.pool, sizeX, sizeY, sizeZ,
                origin, doors, entryFace, false, worldDoors)
        }
        val blocking = TemplateGeometry.collides(box, state.reserved)
        if (blocking != null) {
            failures.add((room?.fileName ?: "the placeholder shell") + " across the corridor would overlap " +
                "${state.owner(blocking)} at ${describe(blocking)}")
            return null
        }
        val tunnel = corridorTunnel(parent.id, id, from, entryWorld)
        for (volume in tunnel.occupied()) {
            val corridorBlock = TemplateGeometry.collides(volume, state.reserved)
            if (corridorBlock != null) {
                failures.add("the branch corridor would overlap ${state.owner(corridorBlock)} at " +
                    describe(corridorBlock))
                return null
            }
        }
        val floor = entryWorld.floor
        return PlacedRoom(id, candidate.slot, box, floor, entryWorld, exitWorld, branchWorld,
            placement, placeholder, tunnel).copyWithDepth(parent.depth + 1)
    }

    /**
     * The procedural corridor volumes between two facing doors on one floor:
     * a platform one below the passage floor, optional safety lips, and the
     * open walking volume. Both endpoint doorway planes stay as the tunnel's
     * doorway bounds, which is what the door and gate systems seal against.
     */
    private fun corridorTunnel(parentId: String, childId: String, from: WorldDoor, to: WorldDoor):
        DungeonLayout.Tunnel {
        val half = platformWidth / 2
        val floors = ArrayList<Bounds>()
        val lips = ArrayList<Bounds>()
        val air = ArrayList<Bounds>()
        val floorY = from.floor
        if (from.face == Dir.EAST || from.face == Dir.WEST) {
            val fromX = from.wallPlane + from.face.stepX
            val toX = to.wallPlane - from.face.stepX
            val minX = minOf(fromX, toX)
            val maxX = maxOf(fromX, toX)
            floors.add(Bounds(minX, floorY - 1, from.cross - half, maxX, floorY - 1, from.cross + half))
            if (safetyLips) {
                lips.add(Bounds(minX, floorY, from.cross - half - 1, maxX, floorY + safetyLipHeight - 1, from.cross - half - 1))
                lips.add(Bounds(minX, floorY, from.cross + half + 1, maxX, floorY + safetyLipHeight - 1, from.cross + half + 1))
            }
            air.add(Bounds(minX, floorY, from.cross - half, maxX, floorY + corridorHeight - 1, from.cross + half))
        } else {
            val fromZ = from.wallPlane + from.face.stepZ
            val toZ = to.wallPlane - from.face.stepZ
            val minZ = minOf(fromZ, toZ)
            val maxZ = maxOf(fromZ, toZ)
            floors.add(Bounds(from.cross - half, floorY - 1, minZ, from.cross + half, floorY - 1, maxZ))
            if (safetyLips) {
                lips.add(Bounds(from.cross - half - 1, floorY, minZ, from.cross - half - 1, floorY + safetyLipHeight - 1, maxZ))
                lips.add(Bounds(from.cross + half + 1, floorY, minZ, from.cross + half + 1, floorY + safetyLipHeight - 1, maxZ))
            }
            air.add(Bounds(from.cross - half, floorY, minZ, from.cross + half, floorY + corridorHeight - 1, maxZ))
        }
        return DungeonLayout.Tunnel(parentId, childId,
            TemplateGeometry.doorOpening(from), TemplateGeometry.doorOpening(to), floors, lips, air)
    }

    private fun keyGate(expansion: TemplateConfig.Expansion, state: PlannerState): DungeonLayout.KeyGate? {
        val lockedIndex = expansion.mainPath.indexOfFirst { it.lockedDoor }
        if (lockedIndex < 0 || expansion.branches.isEmpty()) return null
        val lockedRoom = state.rooms[lockedIndex]
        val tunnel = state.tunnels.firstOrNull { it.secondRoomId == lockedRoom.id }
        if (tunnel == null) {
            logger.severe("The locked-door slot '${lockedRoom.slot.role}' has no incoming connection; " +
                "the door cannot be sealed.")
            return null
        }
        val guardians = state.rooms.filter { it.slot.mobs == "guardian" || it.slot.role == "key" }
            .map { it.id }
        if (guardians.isEmpty()) {
            logger.severe("The template declares a locked door but no key room; the door cannot be sealed.")
            return null
        }
        return DungeonLayout.KeyGate(tunnel.id(), guardians)
    }

    // ------------------------------------------------------------------
    // Planner state
    // ------------------------------------------------------------------

    private inner class PlacedRoom(
        val id: String,
        val slot: TemplateConfig.Slot,
        val box: Bounds,
        val floorY: Int,
        val entryDoorWorld: WorldDoor?,
        var exitDoor: WorldDoor?,
        branchDoors: List<WorldDoor>,
        val placement: RoomPlacement?,
        val placeholder: PlaceholderPlacement?,
        val incomingTunnel: DungeonLayout.Tunnel?
    ) {
        var depth: Int = 0
        private val spareBranchDoors = ArrayList(branchDoors)

        fun takeBranchDoor(): WorldDoor? = spareBranchDoors.removeFirstOrNull()

        fun copyWithDepth(newDepth: Int): PlacedRoom {
            depth = newDepth
            return this
        }

        fun toLayoutRoom(): DungeonLayout.Room {
            val type = when (slot.pool) {
                "spawn" -> DungeonLayout.RoomType.SPAWN
                "boss" -> DungeonLayout.RoomType.BOSS
                "parkour", "key" -> DungeonLayout.RoomType.BRANCH
                else -> DungeonLayout.RoomType.NORMAL
            }
            return DungeonLayout.Room(id, type, box, depth, DungeonLayout.RoomVariant.PLAIN, slot.mobs,
                emptyList(), floorY, slot.sizeClass.name, slot.miniboss)
        }
    }

    private inner class PlannerState(val seed: Long) {
        val rooms = ArrayList<PlacedRoom>()
        val tunnels = ArrayList<DungeonLayout.Tunnel>()
        val reserved = ArrayList<Bounds>()
        private val owners = HashMap<Bounds, String>()

        fun add(placed: PlacedRoom) {
            if (placed.depth == 0) placed.depth = rooms.size
            rooms.add(placed)
            reserve(placed.box, "room ${placed.id} (${placed.slot.role})")
            val tunnel = placed.incomingTunnel
            if (tunnel != null) {
                tunnels.add(tunnel)
                for (volume in tunnel.occupied()) {
                    if (volume.sizeX() > 1 || volume.sizeZ() > 1) {
                        reserve(volume, "corridor ${tunnel.id()}")
                    }
                }
            }
        }

        fun removeLast() {
            val placed = rooms.removeAt(rooms.size - 1)
            reserved.remove(placed.box)
            owners.remove(placed.box)
            val tunnel = placed.incomingTunnel
            if (tunnel != null && tunnels.isNotEmpty() && tunnels.last() === tunnel) {
                tunnels.removeAt(tunnels.size - 1)
                for (volume in tunnel.occupied()) {
                    reserved.remove(volume)
                    owners.remove(volume)
                }
            }
        }

        private fun reserve(box: Bounds, owner: String) {
            reserved.add(box)
            owners[box] = owner
        }

        fun owner(box: Bounds): String = owners[box] ?: "an existing volume"

        fun combatRoom(number: Int): PlacedRoom? =
            rooms.filter { it.slot.role == "combat" }.getOrNull(number - 1)

        fun combatCount(): Int = rooms.count { it.slot.role == "combat" }

    }

    class GenerationException(message: String) : Exception(message)

    companion object {
        private fun describe(box: Bounds): String =
            "${box.minX}..${box.maxX}, ${box.minY}..${box.maxY}, ${box.minZ}..${box.maxZ}"

        private fun direction(value: String?): Dir = try {
            Dir.valueOf((value ?: "SOUTH").trim().uppercase(Locale.ROOT))
        } catch (exception: IllegalArgumentException) {
            Dir.SOUTH
        }

        private fun oddAtLeast(value: Int, minimum: Int): Int {
            val result = maxOf(value, minimum)
            return if ((result and 1) == 0) result + 1 else result
        }
    }
}
