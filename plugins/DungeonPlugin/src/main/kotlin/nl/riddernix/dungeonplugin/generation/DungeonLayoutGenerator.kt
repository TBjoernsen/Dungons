package nl.riddernix.dungeonplugin.generation

import nl.riddernix.dungeonplugin.room.DungeonMarker
import nl.riddernix.dungeonplugin.room.NormalRoomLibrary
import org.bukkit.configuration.file.FileConfiguration
import java.util.EnumSet
import java.util.Locale
import java.util.Random

/**
 * Plans a connected, non-overlapping dungeon tree.
 *
 * The critical path leaves the fixed entrance through its configured cardinal
 * face, then grows outward to the boss. Optional rooms branch from that
 * spine. This iteration keeps every room on one level; the critical path
 * itself may turn left or right while continuing to move outward from the
 * entrance. Planning rejects candidates whose room or tunnel would intersect
 * anything except the two rooms it is intentionally joining.
 */
class DungeonLayoutGenerator @JvmOverloads constructor(config: FileConfiguration, roomLibrary: NormalRoomLibrary? = null) {

    private val settings = Settings(config, roomLibrary)

    @Throws(GenerationException::class)
    fun generate(difficulty: Int, seed: Long): DungeonLayout {
        if (difficulty < 1 || difficulty > 9) {
            throw GenerationException("Difficulty must be between 1 and 9.")
        }
        val random = Random(seed)
        val composition = settings.composition(difficulty)
        // A composed difficulty derives its room count from the authored path;
        // the rooms-per-difficulty entry is deliberately ignored for it.
        val targetRooms = composition?.let { it.pathRoles.size + 2 } ?: settings.roomsForDifficulty(difficulty)

        val entranceBounds = Bounds(settings.originX, settings.originY, settings.originZ,
            settings.originX + settings.entranceWidth - 1,
            settings.originY + settings.entranceHeight - 1,
            settings.originZ + settings.entranceDepth - 1)
        var heading = settings.spawnExitDirection
        val entrance = RoomNode(DungeonLayout.Room("1", DungeonLayout.RoomType.SPAWN, entranceBounds, 0, DungeonLayout.RoomVariant.PLAIN),
            0, false, heading)
        val nodes = ArrayList<RoomNode>()
        nodes.add(entrance)
        val tunnels = ArrayList<DungeonLayout.Tunnel>()
        var longBranchUsed = false

        val criticalRoomCount = if (composition == null) settings.criticalRoomCount(targetRooms) else targetRooms
        val spine = ArrayList<RoomNode>()
        spine.add(entrance)
        var roomsSinceTurn = 0
        var roomsBeforeTurn = settings.randomRoomsBeforeTurn(random)
        // Composed paths run straight by default, so every role slot needs
        // exactly one authored prefab shape rather than a shape per turn case.
        val allowTurns = composition == null || composition.allowTurns

        for (index in 1 until criticalRoomCount) {
            val type = if (index == criticalRoomCount - 1)
                DungeonLayout.RoomType.BOSS else DungeonLayout.RoomType.NORMAL
            // Roles are stamped at placement time so a role pool with its own
            // footprint reserves its own size.
            val role = if (composition != null && type == DungeonLayout.RoomType.NORMAL)
                composition.pathRoles[index - 1] else null
            val parent = spine.last()
            var nextHeading = heading
            var placement: Placement? = null

            if (allowTurns && roomsSinceTurn >= roomsBeforeTurn) {
                val firstTurn = if (random.nextBoolean()) heading.left() else heading.right()
                placement = findPlacement(parent, nodes, tunnels, (index + 1).toString(), type, role, index, firstTurn, random, true)
                if (placement == null) {
                    val secondTurn = if (firstTurn == heading.left()) heading.right() else heading.left()
                    placement = findPlacement(parent, nodes, tunnels, (index + 1).toString(), type, role, index, secondTurn, random, true)
                }
                if (placement != null) {
                    nextHeading = placement.direction
                    roomsSinceTurn = 0
                    roomsBeforeTurn = settings.randomRoomsBeforeTurn(random)
                }
            }
            if (placement == null) {
                placement = findPlacement(parent, nodes, tunnels, (index + 1).toString(), type, role, index, heading, random, true)
            }
            if (placement == null) {
                throw GenerationException("Could not place critical-path room ${index + 1} of $criticalRoomCount" +
                    ". Increase generation.max-placement-attempts, comparison spacing, or reduce room sizes.")
            }
            placement.parent.usedFaces.add(placement.direction)
            placement.node.usedFaces.add(placement.direction.opposite())
            nodes.add(placement.node)
            spine.add(placement.node)
            tunnels.add(placement.tunnel)
            heading = nextHeading
            roomsSinceTurn++
        }

        for (index in criticalRoomCount until targetRooms) {
            val placement = findSidePlacement(spine, nodes, tunnels, random, longBranchUsed)
                ?: throw GenerationException("Could not place side room ${index - criticalRoomCount + 1}" +
                    " of ${targetRooms - criticalRoomCount}" +
                    ". Reduce generation.branching.branch-frequency or room sizes.")
            placement.parent.usedFaces.add(placement.direction)
            placement.node.usedFaces.add(placement.direction.opposite())
            nodes.add(placement.node)
            tunnels.add(placement.tunnel)
            if (placement.parent in spine && placement.node.longBranch) {
                longBranchUsed = true
            }
        }

        var keyGate: DungeonLayout.KeyGate? = null
        if (composition?.keyBranch != null) {
            val keyBranch = composition.keyBranch
            val fromIndex = keyBranch.from.coerceIn(1, composition.pathRoles.size)
            val junction = spine[fromIndex]
            val side = if (random.nextBoolean()) junction.heading!!.left() else junction.heading!!.right()
            var branchNodes = placeKeyBranch(junction, side, keyBranch.roles, nodes, tunnels, random)
            if (branchNodes == null) {
                branchNodes = placeKeyBranch(junction, side.opposite(), keyBranch.roles, nodes, tunnels, random)
            }
            if (branchNodes == null) {
                // The branch holds the key, so a dungeon without it would
                // be sealed shut. Refusal beats an unfinishable run.
                throw GenerationException("Could not place the key branch off path room $fromIndex" +
                    ". Increase generation.max-placement-attempts or reduce room sizes.")
            }
            val doorAfter = keyBranch.doorAfter.coerceIn(1, composition.pathRoles.size)
            keyGate = DungeonLayout.KeyGate(
                spine[doorAfter].room.id + "-" + spine[doorAfter + 1].room.id,
                branchNodes.last().room.id)
        }

        val variants = Random(seed xor 0x5041524b4f55524cL)
        val variantRooms = nodes.map { node ->
            val connections = tunnels.count { it.firstRoomId == node.room.id || it.secondRoomId == node.room.id }
            val canBeParkour = composition == null && (node.room.type == DungeonLayout.RoomType.BRANCH ||
                (node.room.type == DungeonLayout.RoomType.NORMAL && settings.allowNormalParkour))
            val variant = if (canBeParkour && connections == 2 && settings.parkourVariant(variants))
                DungeonLayout.RoomVariant.PARKOUR else DungeonLayout.RoomVariant.PLAIN
            DungeonLayout.Room(node.room.id, node.room.type, node.room.bounds, node.room.depth, variant,
                node.room.role, emptyList())
        }
        val markers = Random(seed xor 0x4d41524b455253L)
        val rooms = variantRooms.map { room ->
            DungeonLayout.Room(room.id, room.type, room.bounds, room.depth, room.variant, room.role,
                generatedMarkers(room, tunnels, difficulty, markers))
        }
        var bounds = entranceBounds
        for (room in rooms) {
            bounds = Bounds.union(bounds, room.bounds)
        }
        for (tunnel in tunnels) {
            for (box in tunnel.occupied()) {
                bounds = Bounds.union(bounds, box)
            }
        }

        return DungeonLayout(seed, difficulty, rooms, tunnels, bounds,
            entranceBounds.centreX(), entranceBounds.minY + 2, entranceBounds.centreZ(), keyGate)
    }

    /**
     * Grows the mandatory key detour room by room off its junction, straight
     * out to one side. Failure rolls every step back so the other side starts
     * from a clean junction.
     */
    private fun placeKeyBranch(junction: RoomNode, side: Direction, roles: List<String>,
                               nodes: MutableList<RoomNode>, tunnels: MutableList<DungeonLayout.Tunnel>,
                               random: Random): List<RoomNode>? {
        // A turning spine may already occupy this face; rollback must not
        // strip a face this attempt never claimed.
        val faceAlreadyUsed = side in junction.usedFaces
        val added = ArrayList<RoomNode>()
        val addedTunnels = ArrayList<DungeonLayout.Tunnel>()
        var parent = junction
        for (index in roles.indices) {
            val placement = findPlacement(parent, nodes, tunnels, (nodes.size + 1).toString(),
                DungeonLayout.RoomType.BRANCH, roles[index], parent.room.depth + 1, side, random, false)
            if (placement == null) {
                nodes.removeAll(added)
                tunnels.removeAll(addedTunnels)
                if (!faceAlreadyUsed) junction.usedFaces.remove(side)
                return null
            }
            placement.parent.usedFaces.add(placement.direction)
            placement.node.usedFaces.add(placement.direction.opposite())
            nodes.add(placement.node)
            tunnels.add(placement.tunnel)
            added.add(placement.node)
            addedTunnels.add(placement.tunnel)
            parent = placement.node
        }
        return added
    }

    /** Generates placeholder markers only; prefab markers are discovered by the same scanner later. */
    private fun generatedMarkers(room: DungeonLayout.Room, tunnels: List<DungeonLayout.Tunnel>,
                                 difficulty: Int, random: Random): List<DungeonMarker> {
        // Roled rooms spawn from their role recipe at runtime, not from the
        // per-difficulty marker mix, so they get no physical markers at all.
        if ((room.type != DungeonLayout.RoomType.NORMAL && room.type != DungeonLayout.RoomType.BRANCH) ||
            room.variant == DungeonLayout.RoomVariant.PARKOUR || room.role != null) {
            return emptyList()
        }
        val markers = ArrayList<DungeonMarker>()
        for (category in listOf("swarm", "pack", "champion")) {
            val groups = settings.markerGroups(difficulty, category)
            for (group in 0 until groups) {
                val marker = placeMarker(room, tunnels, category, markers, random)
                if (marker != null) markers.add(marker)
            }
        }
        return markers.toList()
    }

    private fun placeMarker(room: DungeonLayout.Room, tunnels: List<DungeonLayout.Tunnel>, category: String,
                            existing: List<DungeonMarker>, random: Random): DungeonMarker? {
        val bounds = room.bounds
        val edge = maxOf(2, settings.markerEdgeClearance)
        val minX = bounds.minX + edge
        val maxX = bounds.maxX - edge
        val minZ = bounds.minZ + edge
        val maxZ = bounds.maxZ - edge
        if (minX > maxX || minZ > maxZ) return null
        val y = bounds.minY + 1
        val minimumDistance = settings.markerMinimumDistance
        for (attempt in 0 until settings.markerPlacementAttempts) {
            val x = random.nextInt(maxX - minX + 1) + minX
            val z = random.nextInt(maxZ - minZ + 1) + minZ
            if (nearDoorway(room, tunnels, x, y, z, settings.markerDoorwayClearance)) continue
            val tooClose = existing.any { marker ->
                val offsetX = marker.x - x
                val offsetZ = marker.z - z
                offsetX * offsetX + offsetZ * offsetZ < minimumDistance * minimumDistance
            }
            if (!tooClose) return DungeonMarker(category, x, y, z)
        }
        return null
    }

    private fun findPlacement(parent: RoomNode, nodes: List<RoomNode>, tunnels: List<DungeonLayout.Tunnel>,
                              roomId: String, type: DungeonLayout.RoomType, role: String?, depth: Int,
                              direction: Direction, random: Random, mustMoveOutward: Boolean): Placement? {
        if (direction in parent.usedFaces) {
            return null
        }
        for (attempt in 0 until settings.maxPlacementAttempts) {
            val dimensions = randomDimensions(random, type, role)
            val childBounds = childBounds(parent.room.bounds, direction, dimensions, 0)
            if (mustMoveOutward && !movesFurtherFromEntrance(parent.room.bounds, childBounds)) {
                continue
            }
            val child = RoomNode(DungeonLayout.Room(roomId, type, childBounds, depth,
                DungeonLayout.RoomVariant.PLAIN, role, emptyList()), 0, false, direction)
            val tunnel = tunnel(parent.room, child.room, direction)

            if (collides(child.room.bounds, parent, nodes, tunnels, tunnel)) {
                continue
            }
            return Placement(parent, child, direction, tunnel)
        }
        return null
    }

    private fun findSidePlacement(spine: List<RoomNode>, nodes: List<RoomNode>,
                                  tunnels: List<DungeonLayout.Tunnel>, random: Random,
                                  longBranchUsed: Boolean): Placement? {
        for (attempt in 0 until settings.maxPlacementAttempts) {
            val candidates = nodes
                .filter { it.room.type != DungeonLayout.RoomType.BOSS }
                .filter { it.usedFaces.size < Direction.entries.size }
                .filter { it.branchLength < settings.maximumBranchLength(it.longBranch) }
                // A branch may extend away from the main path, but it never forks.
                // This keeps the configured one-room branches as real dead ends.
                .filter { it.branchLength == 0 || !hasGeneratedChild(it, tunnels) }
                .filter { !isProtectedSpineNode(it, spine) }
            if (candidates.isEmpty()) {
                return null
            }
            val branchParents = candidates.filter { it.branchLength > 0 }
            val parent = if (branchParents.isNotEmpty() && random.nextDouble() < 0.40)
                branchParents[random.nextInt(branchParents.size)]
            else candidates[random.nextInt(candidates.size)]
            val direction = (if (parent in spine)
                Direction.randomPerpendicularUnused(parent.usedFaces, parent.heading!!, random)
            else Direction.randomForwardOrTurnUnused(parent.usedFaces, parent.heading, random))
                ?: continue
            val dimensions = randomDimensions(random, DungeonLayout.RoomType.BRANCH, null)
            val childBounds = childBounds(parent.room.bounds, direction, dimensions, 0)
            if (!movesFurtherFromEntrance(parent.room.bounds, childBounds)) {
                continue
            }
            val parentIsSpine = parent in spine
            val longBranch = if (parentIsSpine) !longBranchUsed else parent.longBranch
            val child = RoomNode(DungeonLayout.Room((nodes.size + 1).toString(), DungeonLayout.RoomType.BRANCH,
                childBounds, parent.room.depth + 1, DungeonLayout.RoomVariant.PLAIN),
                parent.branchLength + 1, longBranch, direction)
            val tunnel = tunnel(parent.room, child.room, direction)
            if (!collides(child.room.bounds, parent, nodes, tunnels, tunnel)) {
                return Placement(parent, child, direction, tunnel)
            }
        }
        return null
    }

    private fun collides(child: Bounds, parent: RoomNode, nodes: List<RoomNode>,
                         tunnels: List<DungeonLayout.Tunnel>, proposed: DungeonLayout.Tunnel): Boolean {
        for (node in nodes) {
            if (child.intersects(node.room.bounds.expand(settings.roomPadding))) {
                return true
            }
            if (node !== parent && intersectsAny(node.room.bounds, proposed.occupied())) {
                return true
            }
        }
        for (existing in tunnels) {
            if (intersectsAny(child.expand(settings.roomPadding), existing.occupied()) ||
                tunnelsIntersect(proposed, existing)) {
                return true
            }
        }
        return false
    }

    private fun randomDimensions(random: Random, type: DungeonLayout.RoomType, role: String?): Dimensions {
        val minWidth: Int
        val maxWidth: Int
        val minHeight: Int
        val maxHeight: Int
        val minDepth: Int
        val maxDepth: Int
        if (type == DungeonLayout.RoomType.BOSS) {
            minWidth = settings.bossMinWidth
            maxWidth = settings.bossMaxWidth
            minHeight = settings.bossMinHeight
            maxHeight = settings.bossMaxHeight
            minDepth = settings.bossMinDepth
            maxDepth = settings.bossMaxDepth
        } else {
            val prefab = settings.prefabDimensions(type, role)
            minWidth = prefab.width; maxWidth = prefab.width
            minHeight = prefab.height; maxHeight = prefab.height
            minDepth = prefab.depth; maxDepth = prefab.depth
        }
        return Dimensions(
            randomOdd(random, minWidth, maxWidth),
            random.nextInt(maxHeight - minHeight + 1) + minHeight,
            randomOdd(random, minDepth, maxDepth)
        )
    }

    private fun childBounds(parent: Bounds, direction: Direction, size: Dimensions, yOffset: Int): Bounds {
        val minY = parent.minY + yOffset
        val maxY = minY + size.height - 1
        return when (direction) {
            Direction.EAST -> {
                val minX = parent.maxX + settings.corridorLength
                val minZ = parent.centreZ() - size.depth / 2
                Bounds(minX, minY, minZ, minX + size.width - 1, maxY, minZ + size.depth - 1)
            }
            Direction.WEST -> {
                val maxX = parent.minX - settings.corridorLength
                val minZ = parent.centreZ() - size.depth / 2
                Bounds(maxX - size.width + 1, minY, minZ, maxX, maxY, minZ + size.depth - 1)
            }
            Direction.SOUTH -> {
                val minZ = parent.maxZ + settings.corridorLength
                val minX = parent.centreX() - size.width / 2
                Bounds(minX, minY, minZ, minX + size.width - 1, maxY, minZ + size.depth - 1)
            }
            Direction.NORTH -> {
                val maxZ = parent.minZ - settings.corridorLength
                val minX = parent.centreX() - size.width / 2
                Bounds(minX, minY, maxZ - size.depth + 1, minX + size.width - 1, maxY, maxZ)
            }
        }
    }

    private fun movesFurtherFromEntrance(parent: Bounds, child: Bounds): Boolean {
        val entranceX = settings.originX + (settings.entranceWidth - 1L) / 2L
        val entranceZ = settings.originZ + (settings.entranceDepth - 1L) / 2L
        val parentX = parent.centreX() - entranceX
        val parentZ = parent.centreZ() - entranceZ
        val childX = child.centreX() - entranceX
        val childZ = child.centreZ() - entranceZ
        return childX * childX + childZ * childZ > parentX * parentX + parentZ * parentZ
    }

    private fun tunnel(parentRoom: DungeonLayout.Room, childRoom: DungeonLayout.Room, direction: Direction): DungeonLayout.Tunnel {
        val parent = parentRoom.bounds
        val child = childRoom.bounds
        val parentDoorY = parent.minY + 1
        val childDoorY = child.minY + 1
        val half = settings.corridorInnerWidth / 2
        val floors = ArrayList<Bounds>()
        val lips = ArrayList<Bounds>()
        val air = ArrayList<Bounds>()

        val parentDoorway: Bounds
        val childDoorway: Bounds
        if (direction.isX()) {
            val parentX = if (direction == Direction.EAST) parent.maxX else parent.minX
            val childX = if (direction == Direction.EAST) child.minX else child.maxX
            val centreZ = parent.centreZ()
            parentDoorway = Bounds(parentX, parentDoorY, centreZ - half,
                parentX, parentDoorY + settings.corridorInnerHeight - 1, centreZ + half)
            childDoorway = Bounds(childX, childDoorY, centreZ - half,
                childX, childDoorY + settings.corridorInnerHeight - 1, centreZ + half)
            if (parentDoorY == childDoorY) {
                addHorizontalX(floors, lips, air, parentX, childX, parentDoorY, centreZ, half)
            } else {
                val middle = (parentX + childX) / 2
                addHorizontalX(floors, lips, air, parentX, middle, parentDoorY, centreZ, half)
                addVertical(floors, lips, air, middle, centreZ, parentDoorY, childDoorY, half)
                addHorizontalX(floors, lips, air, middle, childX, childDoorY, centreZ, half)
            }
        } else {
            val parentZ = if (direction == Direction.SOUTH) parent.maxZ else parent.minZ
            val childZ = if (direction == Direction.SOUTH) child.minZ else child.maxZ
            val centreX = parent.centreX()
            parentDoorway = Bounds(centreX - half, parentDoorY, parentZ,
                centreX + half, parentDoorY + settings.corridorInnerHeight - 1, parentZ)
            childDoorway = Bounds(centreX - half, childDoorY, childZ,
                centreX + half, childDoorY + settings.corridorInnerHeight - 1, childZ)
            if (parentDoorY == childDoorY) {
                addHorizontalZ(floors, lips, air, parentZ, childZ, parentDoorY, centreX, half)
            } else {
                val middle = (parentZ + childZ) / 2
                addHorizontalZ(floors, lips, air, parentZ, middle, parentDoorY, centreX, half)
                addVertical(floors, lips, air, centreX, middle, parentDoorY, childDoorY, half)
                addHorizontalZ(floors, lips, air, middle, childZ, childDoorY, centreX, half)
            }
        }
        return DungeonLayout.Tunnel(parentRoom.id, childRoom.id, parentDoorway, childDoorway, floors, lips, air)
    }

    private fun addHorizontalX(floors: MutableList<Bounds>, lips: MutableList<Bounds>, air: MutableList<Bounds>,
                               firstX: Int, secondX: Int, doorY: Int, centreZ: Int, half: Int) {
        val minX = minOf(firstX, secondX)
        val maxX = maxOf(firstX, secondX)
        floors.add(Bounds(minX, doorY - 1, centreZ - half,
            maxX, doorY - 1, centreZ + half))
        addLipsX(lips, minX, maxX, doorY, centreZ, half)
        air.add(Bounds(minX, doorY, centreZ - half,
            maxX, doorY + settings.corridorInnerHeight - 1, centreZ + half))
    }

    private fun addHorizontalZ(floors: MutableList<Bounds>, lips: MutableList<Bounds>, air: MutableList<Bounds>,
                               firstZ: Int, secondZ: Int, doorY: Int, centreX: Int, half: Int) {
        val minZ = minOf(firstZ, secondZ)
        val maxZ = maxOf(firstZ, secondZ)
        floors.add(Bounds(centreX - half, doorY - 1, minZ,
            centreX + half, doorY - 1, maxZ))
        addLipsZ(lips, minZ, maxZ, doorY, centreX, half)
        air.add(Bounds(centreX - half, doorY, minZ,
            centreX + half, doorY + settings.corridorInnerHeight - 1, maxZ))
    }

    private fun addVertical(floors: MutableList<Bounds>, lips: MutableList<Bounds>, air: MutableList<Bounds>,
                            centreX: Int, centreZ: Int, firstDoorY: Int, secondDoorY: Int, half: Int) {
        val minDoorY = minOf(firstDoorY, secondDoorY)
        val maxDoorY = maxOf(firstDoorY, secondDoorY)
        floors.add(Bounds(centreX - half, minDoorY - 1, centreZ - half,
            centreX + half, minDoorY - 1, centreZ + half))
        air.add(Bounds(centreX - half, minDoorY, centreZ - half,
            centreX + half, maxDoorY + settings.corridorInnerHeight - 1, centreZ + half))
    }

    /** Low edge lips stop ordinary walking and mobs from falling into the void. */
    private fun addLipsX(lips: MutableList<Bounds>, minX: Int, maxX: Int, doorY: Int, centreZ: Int, half: Int) {
        if (!settings.corridorSafetyLips) return
        lips.add(Bounds(minX, doorY, centreZ - half - 1, maxX, doorY + settings.corridorSafetyLipHeight - 1, centreZ - half - 1))
        lips.add(Bounds(minX, doorY, centreZ + half + 1, maxX, doorY + settings.corridorSafetyLipHeight - 1, centreZ + half + 1))
    }

    private fun addLipsZ(lips: MutableList<Bounds>, minZ: Int, maxZ: Int, doorY: Int, centreX: Int, half: Int) {
        if (!settings.corridorSafetyLips) return
        lips.add(Bounds(centreX - half - 1, doorY, minZ, centreX - half - 1, doorY + settings.corridorSafetyLipHeight - 1, maxZ))
        lips.add(Bounds(centreX + half + 1, doorY, minZ, centreX + half + 1, doorY + settings.corridorSafetyLipHeight - 1, maxZ))
    }

    enum class Direction {
        NORTH, SOUTH, EAST, WEST;

        fun isX(): Boolean = this == EAST || this == WEST

        fun opposite(): Direction = when (this) {
            NORTH -> SOUTH
            SOUTH -> NORTH
            EAST -> WEST
            WEST -> EAST
        }

        fun left(): Direction = when (this) {
            NORTH -> WEST
            SOUTH -> EAST
            EAST -> NORTH
            WEST -> SOUTH
        }

        fun right(): Direction = left().opposite()

        companion object {
            /** Reads a cardinal config value, defaulting safely to the south face. */
            fun fromConfig(value: String?): Direction = try {
                if (value == null) SOUTH else valueOf(value.trim().uppercase(Locale.ROOT))
            } catch (exception: IllegalArgumentException) {
                SOUTH
            }

            fun randomUnused(used: EnumSet<Direction>, random: Random): Direction {
                val available = entries.filter { it !in used }
                return available[random.nextInt(available.size)]
            }

            fun randomPerpendicularUnused(used: EnumSet<Direction>, spineDirection: Direction, random: Random): Direction? {
                val available = entries.filter { it !in used && it != spineDirection && it != spineDirection.opposite() }
                return if (available.isEmpty()) null else available[random.nextInt(available.size)]
            }

            fun randomForwardOrTurnUnused(used: EnumSet<Direction>, heading: Direction?, random: Random): Direction? {
                val available = entries.filter { it !in used && (heading == null || it != heading.opposite()) }
                return if (available.isEmpty()) null else available[random.nextInt(available.size)]
            }
        }
    }

    private class RoomNode(
        val room: DungeonLayout.Room,
        /** Zero is the critical spine; positive values are distance along a side branch. */
        val branchLength: Int,
        /** Exactly one branch root may receive the longer configured allowance. */
        val longBranch: Boolean,
        /** The direction this node was reached from its parent. */
        val heading: Direction?
    ) {
        val usedFaces: EnumSet<Direction> = EnumSet.noneOf(Direction::class.java)
    }

    private data class Dimensions(val width: Int, val height: Int, val depth: Int)

    private class Placement(val parent: RoomNode, val node: RoomNode, val direction: Direction,
                            val tunnel: DungeonLayout.Tunnel)

    /** An authored difficulty: role per path room plus the optional key detour. */
    private class Composition(val pathRoles: List<String>, val allowTurns: Boolean, val keyBranch: KeyBranch?)

    /** [from] and [doorAfter] are one-based indexes into the path. */
    private class KeyBranch(val from: Int, val doorAfter: Int, val roles: List<String>)

    class GenerationException(message: String) : Exception(message)

    private class Settings(private val config: FileConfiguration, private val roomLibrary: NormalRoomLibrary?) {
        private val roleDimensions = HashMap<String, Dimensions>()
        val originX: Int = config.getInt("generation.entrance.origin.x", 0)
        val originY: Int = config.getInt("generation.entrance.origin.y", 64)
        val originZ: Int = config.getInt("generation.entrance.origin.z", 0)
        val entranceWidth: Int
        val entranceHeight: Int
        val entranceDepth: Int
        val spawnExitDirection: Direction
        private val prefabNormalWidth: Int
        private val prefabNormalHeight: Int
        private val prefabNormalDepth: Int
        private val prefabBranchWidth: Int
        private val prefabBranchHeight: Int
        private val prefabBranchDepth: Int
        val bossMinWidth: Int
        val bossMaxWidth: Int
        val bossMinHeight: Int
        val bossMaxHeight: Int
        val bossMinDepth: Int
        val bossMaxDepth: Int
        val corridorLength: Int
        val corridorInnerWidth: Int
        val corridorInnerHeight: Int
        val corridorSafetyLips: Boolean
        val corridorSafetyLipHeight: Int
        val roomPadding: Int
        val maxPlacementAttempts: Int
        private val branchFrequency: Double
        private val shortBranchMaximumLength: Int
        private val longBranchMaximumLength: Int
        private val minimumCriticalPathRooms: Int
        private val minimumRoomsBeforeTurn: Int
        private val maximumRoomsBeforeTurn: Int
        private val roomsPerDifficulty: Map<Int, Int>
        private val compositions: Map<Int, Composition>
        val markerMinimumDistance: Int
        val markerDoorwayClearance: Int
        val markerEdgeClearance: Int
        val markerPlacementAttempts: Int
        val allowNormalParkour: Boolean

        init {
            val spawnDimensions = roomLibrary?.planningDimensions(NormalRoomLibrary.PrefabType.SPAWN)
                ?: configuredDimensions(config, "generation.entrance.size")
            entranceWidth = oddAtLeast(spawnDimensions.width, 7)
            entranceHeight = maxOf(6, spawnDimensions.height)
            entranceDepth = oddAtLeast(spawnDimensions.depth, 7)
            spawnExitDirection = Direction.fromConfig(config.getString("generation.entrance.exit-direction", "SOUTH"))
            corridorInnerWidth = oddAtLeast(config.getInt("generation.corridor.platform-width",
                config.getInt("generation.corridor.inner-width", 3)), 1)
            corridorInnerHeight = maxOf(2, config.getInt("generation.corridor.inner-height", 4))
            corridorSafetyLips = config.getBoolean("generation.corridor.safety-lips.enabled", true)
            corridorSafetyLipHeight = maxOf(1, config.getInt("generation.corridor.safety-lips.height", 1))
            val normalDimensions = roomLibrary?.planningDimensions(NormalRoomLibrary.PrefabType.NORMAL)
                ?: configuredPrefabDimensions(config)
            val branchDimensions = roomLibrary?.planningDimensions(NormalRoomLibrary.PrefabType.BRANCH)
                ?: configuredPrefabDimensions(config)
            prefabNormalWidth = oddAtLeast(normalDimensions.width, corridorInnerWidth + 4)
            prefabNormalHeight = maxOf(corridorInnerHeight + 3, normalDimensions.height)
            prefabNormalDepth = oddAtLeast(normalDimensions.depth, corridorInnerWidth + 4)
            prefabBranchWidth = oddAtLeast(branchDimensions.width, corridorInnerWidth + 4)
            prefabBranchHeight = maxOf(corridorInnerHeight + 3, branchDimensions.height)
            prefabBranchDepth = oddAtLeast(branchDimensions.depth, corridorInnerWidth + 4)
            var largestBossScale = 1.0
            for (difficulty in 1..9) {
                largestBossScale = maxOf(largestBossScale, config.getDouble("mobs.difficulties.$difficulty.boss.scale", 1.0))
                largestBossScale = maxOf(largestBossScale, config.getDouble("mobs.difficulties.$difficulty.boss.minions.scale", 1.0))
            }
            val bossDimensions = roomLibrary?.planningDimensions(NormalRoomLibrary.PrefabType.BOSS)
                ?: configuredDimensions(config, "generation.boss-room.max-size")
            bossMinWidth = oddAtLeast(bossDimensions.width, corridorInnerWidth + 4); bossMaxWidth = bossMinWidth
            bossMinHeight = maxOf(corridorInnerHeight + 3, bossDimensions.height); bossMaxHeight = bossMinHeight
            bossMinDepth = oddAtLeast(bossDimensions.depth, corridorInnerWidth + 4); bossMaxDepth = bossMinDepth
            corridorLength = maxOf(7, config.getInt("generation.corridor.length", 9))
            roomPadding = maxOf(0, config.getInt("generation.room-padding", 2))
            maxPlacementAttempts = maxOf(50, config.getInt("generation.max-placement-attempts", 600))
            branchFrequency = config.getDouble("generation.branching.branch-frequency", 0.55).coerceIn(0.0, 1.0)
            shortBranchMaximumLength = maxOf(1,
                config.getInt("generation.branching.short-branch-max-length", 1))
            longBranchMaximumLength = maxOf(shortBranchMaximumLength,
                config.getInt("generation.branching.long-branch-max-length", 2))
            minimumCriticalPathRooms = maxOf(2, config.getInt("generation.branching.minimum-critical-path-rooms", 4))
            minimumRoomsBeforeTurn = maxOf(1, config.getInt("generation.critical-path.min-rooms-before-turn", 2))
            maximumRoomsBeforeTurn = maxOf(minimumRoomsBeforeTurn,
                config.getInt("generation.critical-path.max-rooms-before-turn", 4))
            val rooms = HashMap<Int, Int>()
            for (difficulty in 1..9) {
                rooms[difficulty] = maxOf(2,
                    config.getInt("generation.rooms-per-difficulty.$difficulty", 4 + difficulty * 3))
            }
            roomsPerDifficulty = rooms
            compositions = readCompositions(config)
            markerMinimumDistance = maxOf(1, config.getInt("mobs.markers.generated.minimum-distance", 8))
            markerDoorwayClearance = maxOf(0, config.getInt("mobs.markers.generated.doorway-clearance", 5))
            markerEdgeClearance = maxOf(2, config.getInt("mobs.markers.generated.edge-clearance", 3))
            markerPlacementAttempts = maxOf(1, config.getInt("mobs.markers.generated.placement-attempts", 100))
            allowNormalParkour = config.getBoolean("generation.room-variants.allow-normal-parkour", false)
        }

        fun roomsForDifficulty(difficulty: Int): Int = roomsPerDifficulty.getValue(difficulty)

        fun composition(difficulty: Int): Composition? = compositions[difficulty]

        /**
         * Role pools reserve their own footprint - one oversized parkour room
         * must not stretch every ordinary reservation, nor be squeezed into
         * one. Falls back to the generic envelope when the pool is empty,
         * matching how prefab selection falls back.
         */
        fun prefabDimensions(type: DungeonLayout.RoomType, role: String?): Dimensions {
            val branch = type == DungeonLayout.RoomType.BRANCH
            if (role == null || roomLibrary == null) {
                return rotatable(if (branch) prefabBranchWidth else prefabNormalWidth,
                    if (branch) prefabBranchHeight else prefabNormalHeight,
                    if (branch) prefabBranchDepth else prefabNormalDepth)
            }
            return roleDimensions.getOrPut((if (branch) "branch:" else "normal:") + role) {
                val dimensions = roomLibrary.planningDimensions(
                    if (branch) NormalRoomLibrary.PrefabType.BRANCH else NormalRoomLibrary.PrefabType.NORMAL, role)
                rotatable(oddAtLeast(dimensions.width, corridorInnerWidth + 4),
                    maxOf(corridorInnerHeight + 3, dimensions.height),
                    oddAtLeast(dimensions.depth, corridorInnerWidth + 4))
            }
        }

        fun criticalRoomCount(totalRooms: Int): Int {
            val protectedSpineMinimum = if (totalRooms > 5) 5 else totalRooms
            val minimum = minOf(totalRooms, maxOf(minimumCriticalPathRooms, protectedSpineMinimum))
            val optionalRooms = totalRooms - minimum
            val sideRooms = Math.round(optionalRooms * branchFrequency).toInt()
            return totalRooms - sideRooms
        }

        fun maximumBranchLength(longBranch: Boolean): Int =
            if (longBranch) longBranchMaximumLength else shortBranchMaximumLength

        fun randomRoomsBeforeTurn(random: Random): Int =
            random.nextInt(maximumRoomsBeforeTurn - minimumRoomsBeforeTurn + 1) + minimumRoomsBeforeTurn

        fun parkourVariant(random: Random): Boolean {
            val plain = maxOf(0, config.getInt("generation.room-variants.plain.weight", 1))
            val parkour = maxOf(0, config.getInt("generation.room-variants.parkour.weight", 0))
            return parkour > 0 && random.nextInt(maxOf(1, plain + parkour)) >= plain
        }

        fun markerGroups(difficulty: Int, category: String): Int =
            maxOf(0, config.getInt("mobs.markers.generated.per-difficulty.$difficulty.$category", 0))

        companion object {
            private fun configuredPrefabDimensions(config: FileConfiguration): NormalRoomLibrary.PlanningDimensions =
                NormalRoomLibrary.PlanningDimensions(
                    config.getInt("generation.prefab-room.size.x", 67),
                    config.getInt("generation.prefab-room.size.y", 34),
                    config.getInt("generation.prefab-room.size.z", 67))

            private fun configuredDimensions(config: FileConfiguration, path: String): NormalRoomLibrary.PlanningDimensions =
                NormalRoomLibrary.PlanningDimensions(
                    config.getInt("$path.x", 67), config.getInt("$path.y", 34), config.getInt("$path.z", 67))

            /**
             * A square reservation, because the rotation is chosen long after
             * the space is booked. A 31x111 room turned sideways needs 111
             * where only 31 was reserved, and a prefab wider than its own box
             * overhangs into the corridor until the two rooms share a wall.
             */
            private fun rotatable(width: Int, height: Int, depth: Int): Dimensions {
                val side = maxOf(width, depth)
                return Dimensions(side, height, side)
            }

            private fun readCompositions(config: FileConfiguration): Map<Int, Composition> {
                val result = HashMap<Int, Composition>()
                val section = config.getConfigurationSection("generation.composition") ?: return result
                for (key in section.getKeys(false)) {
                    val difficulty = key.trim().toIntOrNull() ?: continue
                    if (difficulty < 1 || difficulty > 9) continue
                    val base = "generation.composition.$key."
                    val path = normalisedRoles(config.getStringList(base + "path"))
                    if (path.isEmpty()) continue
                    val allowTurns = config.getBoolean(base + "allow-turns", false)
                    var keyBranch: KeyBranch? = null
                    if (config.getBoolean(base + "key-branch.enabled", false)) {
                        val branchRoles = normalisedRoles(config.getStringList(base + "key-branch.rooms"))
                        if (branchRoles.isNotEmpty()) {
                            keyBranch = KeyBranch(config.getInt(base + "key-branch.from", path.size),
                                config.getInt(base + "key-branch.door-after", path.size), branchRoles)
                        }
                    }
                    result[difficulty] = Composition(path, allowTurns, keyBranch)
                }
                return result
            }

            private fun normalisedRoles(raw: List<String?>): List<String> =
                raw.filterNotNull().filter { it.isNotBlank() }.map { it.trim().lowercase(Locale.ROOT) }

            private fun oddAtLeast(value: Int, minimum: Int): Int {
                val result = maxOf(value, minimum)
                return if ((result and 1) == 0) result + 1 else result
            }
        }
    }

    companion object {
        private fun hasGeneratedChild(parent: RoomNode, tunnels: List<DungeonLayout.Tunnel>): Boolean =
            tunnels.any { it.firstRoomId == parent.room.id }

        /** Keeps the entrance, its first successor, and the boss approach branch-free. */
        private fun isProtectedSpineNode(node: RoomNode, spine: List<RoomNode>): Boolean {
            val index = spine.indexOf(node)
            return index >= 0 && (index <= 1 || index >= spine.size - 2)
        }

        private fun intersectsAny(room: Bounds, boxes: List<Bounds>): Boolean = boxes.any(room::intersects)

        private fun nearDoorway(room: DungeonLayout.Room, tunnels: List<DungeonLayout.Tunnel>,
                                x: Int, y: Int, z: Int, clearance: Int): Boolean =
            tunnels.filter { it.firstRoomId == room.id || it.secondRoomId == room.id }
                .map { if (it.firstRoomId == room.id) it.firstDoorway else it.secondDoorway }
                .any { it.expand(clearance).contains(x, y, z) }

        private fun tunnelsIntersect(first: DungeonLayout.Tunnel, second: DungeonLayout.Tunnel): Boolean {
            for (left in first.occupied()) {
                if (intersectsAny(left, second.occupied())) {
                    return true
                }
            }
            return false
        }

        private fun randomOdd(random: Random, min: Int, max: Int): Int {
            val first = if ((min and 1) == 0) min + 1 else min
            val last = if ((max and 1) == 0) max - 1 else max
            val choices = maxOf(1, (last - first) / 2 + 1)
            return first + random.nextInt(choices) * 2
        }
    }
}
