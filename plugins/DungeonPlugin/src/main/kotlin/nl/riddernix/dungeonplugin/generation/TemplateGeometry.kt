package nl.riddernix.dungeonplugin.generation

/**
 * Pure placement mathematics for the template layout: door-to-door alignment,
 * rotation, and the bounding boxes that the overlap check runs on.
 *
 * Deliberately free of Bukkit types so the alignment rules - especially the
 * floor-transition case, which is the one most likely to be off by one - can
 * be proven by a plain JVM test instead of a server run.
 */
object TemplateGeometry {

    /** A cardinal heading, mirroring BlockFace without depending on it. */
    enum class Dir(val stepX: Int, val stepZ: Int) {
        NORTH(0, -1),
        SOUTH(0, 1),
        EAST(1, 0),
        WEST(-1, 0);

        fun opposite(): Dir = when (this) {
            NORTH -> SOUTH
            SOUTH -> NORTH
            EAST -> WEST
            WEST -> EAST
        }

        fun left(): Dir = when (this) {
            NORTH -> WEST
            SOUTH -> EAST
            EAST -> NORTH
            WEST -> SOUTH
        }

        fun right(): Dir = left().opposite()

        fun rotated(rotation: Int): Dir = when (Math.floorMod(rotation, 360)) {
            0 -> this
            90 -> when (this) {
                NORTH -> EAST
                EAST -> SOUTH
                SOUTH -> WEST
                WEST -> NORTH
            }
            180 -> opposite()
            270 -> when (this) {
                NORTH -> WEST
                EAST -> NORTH
                SOUTH -> EAST
                WEST -> SOUTH
            }
            else -> throw IllegalArgumentException("Only right-angle rotations are supported.")
        }
    }

    /**
     * A door in a room file, in the file's own local coordinates.
     *
     * [markerCross] is the marker strip's centre along the wall (an x on
     * north/south walls, a z on east/west walls). [openingFloor] is the local
     * y of the walkable passage floor - the bottom of the detected air
     * opening. Doors on one room may sit at different [openingFloor] heights;
     * that is exactly what makes a stairs room a stairs room.
     */
    data class LocalDoor(
        val face: Dir,
        val markerCross: Int,
        val markerY: Int,
        val openingFloor: Int,
        val openingWidth: Int,
        val openingHeight: Int,
        val markerWidth: Int
    )

    /** A door of an already-placed room, resolved into world coordinates. */
    data class WorldDoor(
        val face: Dir,
        /** The wall plane the door sits in: an x for EAST/WEST, a z for NORTH/SOUTH. */
        val wallPlane: Int,
        /** Marker centre along the wall: a z for EAST/WEST, an x for NORTH/SOUTH. */
        val cross: Int,
        val markerY: Int,
        /** World y of the walkable passage floor. */
        val floor: Int,
        val openingWidth: Int,
        val openingHeight: Int,
        val markerWidth: Int
    ) {
        fun translated(x: Int, y: Int, z: Int): WorldDoor {
            val alongX = face == Dir.EAST || face == Dir.WEST
            return copy(wallPlane = wallPlane + (if (alongX) x else z),
                cross = cross + (if (alongX) z else x),
                markerY = markerY + y, floor = floor + y)
        }
    }

    data class Rotated(val sizeX: Int, val sizeZ: Int, val x: Int, val z: Int)

    /** Rotates a local point inside a width x depth footprint. */
    fun rotate(x: Int, z: Int, width: Int, depth: Int, rotation: Int): Rotated = when (Math.floorMod(rotation, 360)) {
        0 -> Rotated(width, depth, x, z)
        90 -> Rotated(depth, width, depth - 1 - z, x)
        180 -> Rotated(width, depth, width - 1 - x, depth - 1 - z)
        270 -> Rotated(depth, width, z, width - 1 - x)
        else -> throw IllegalArgumentException("Only right-angle rotations are supported.")
    }

    fun rotatedSize(width: Int, depth: Int, rotation: Int): Rotated =
        if (Math.floorMod(rotation, 180) == 0) Rotated(width, depth, 0, 0) else Rotated(depth, width, 0, 0)

    /**
     * The local marker block position of a door, before rotation. Doors sit
     * on an outer wall, so one axis is pinned by the face.
     */
    fun localMarkerPosition(door: LocalDoor, width: Int, depth: Int): Pair<Int, Int> = when (door.face) {
        Dir.NORTH -> door.markerCross to 0
        Dir.SOUTH -> door.markerCross to depth - 1
        Dir.WEST -> 0 to door.markerCross
        Dir.EAST -> width - 1 to door.markerCross
    }

    /**
     * Places a room flush against an already-placed door.
     *
     * The rule of the whole template system, stated once: the two wall planes
     * are adjacent (a corridor of length zero), the door aligns horizontally
     * on its own marker centre, and vertically on its own opening floor.
     * Every door on a room carries its own floor height, which is why a
     * stairs room needs no special casing here - its second door simply
     * reports a different [LocalDoor.openingFloor], and whatever connects to
     * that door aligns to it.
     *
     * @param exit the placed room's door being connected to, in world space
     * @param entry the new room's door, in the file's local space
     * @param width the new room's unrotated width
     * @param depth the new room's unrotated depth
     * @param rotation the rotation under which [entry] faces [exit]
     * @return the world position of the new room's minimum corner
     */
    fun alignFlush(exit: WorldDoor, entry: LocalDoor, width: Int, depth: Int, rotation: Int): Origin {
        require(entry.face.rotated(rotation) == exit.face.opposite()) {
            "Entry door must face back towards the exit door."
        }
        val (localX, localZ) = localMarkerPosition(entry, width, depth)
        val rotated = rotate(localX, localZ, width, depth, rotation)
        // The entry marker's world cell sits one block past the exit marker's
        // wall plane, on the same cross coordinate.
        val markerWorldX: Int
        val markerWorldZ: Int
        when (exit.face) {
            Dir.EAST, Dir.WEST -> {
                markerWorldX = exit.wallPlane + exit.face.stepX
                markerWorldZ = exit.cross
            }
            Dir.NORTH, Dir.SOUTH -> {
                markerWorldX = exit.cross
                markerWorldZ = exit.wallPlane + exit.face.stepZ
            }
        }
        return Origin(
            markerWorldX - rotated.x,
            exit.floor - entry.openingFloor,
            markerWorldZ - rotated.z
        )
    }

    /**
     * Places a room across a corridor gap: like [alignFlush], but with
     * [gap] blocks of open space between the two wall planes.
     */
    fun alignAcrossGap(exit: WorldDoor, entry: LocalDoor, width: Int, depth: Int, rotation: Int, gap: Int): Origin {
        val flush = alignFlush(exit, entry, width, depth, rotation)
        return Origin(flush.x + exit.face.stepX * gap, flush.y, flush.z + exit.face.stepZ * gap)
    }

    /** Resolves a local door of a room placed at [origin] with [rotation] into world space. */
    fun worldDoor(door: LocalDoor, width: Int, depth: Int, rotation: Int, origin: Origin): WorldDoor {
        val (localX, localZ) = localMarkerPosition(door, width, depth)
        val rotated = rotate(localX, localZ, width, depth, rotation)
        val face = door.face.rotated(rotation)
        val worldX = origin.x + rotated.x
        val worldZ = origin.z + rotated.z
        return WorldDoor(
            face,
            if (face == Dir.EAST || face == Dir.WEST) worldX else worldZ,
            if (face == Dir.EAST || face == Dir.WEST) worldZ else worldX,
            origin.y + door.markerY,
            origin.y + door.openingFloor,
            door.openingWidth,
            door.openingHeight,
            door.markerWidth
        )
    }

    /** The world-space passage opening of a door: the air cells in its wall plane. */
    fun doorOpening(door: WorldDoor): Bounds {
        val half = (door.openingWidth - 1) / 2
        return when (door.face) {
            Dir.EAST, Dir.WEST -> Bounds(door.wallPlane, door.floor, door.cross - half,
                door.wallPlane, door.floor + door.openingHeight - 1, door.cross + half)
            Dir.NORTH, Dir.SOUTH -> Bounds(door.cross - half, door.floor, door.wallPlane,
                door.cross + half, door.floor + door.openingHeight - 1, door.wallPlane)
        }
    }

    data class Origin(val x: Int, val y: Int, val z: Int)

    /** True when [candidate] overlaps any already-reserved box. Touching faces are allowed. */
    fun collides(candidate: Bounds, reserved: List<Bounds>): Bounds? = reserved.firstOrNull(candidate::intersects)
}
