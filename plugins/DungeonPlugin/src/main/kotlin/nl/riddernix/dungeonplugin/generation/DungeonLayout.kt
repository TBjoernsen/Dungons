package nl.riddernix.dungeonplugin.generation

import nl.riddernix.dungeonplugin.room.DungeonMarker

/** A fully planned dungeon before any blocks have been changed. */
class DungeonLayout(
    val seed: Long,
    val difficulty: Int,
    rooms: List<Room>,
    tunnels: List<Tunnel>,
    val bounds: Bounds,
    val spawnX: Int,
    val spawnY: Int,
    val spawnZ: Int,
    val keyGate: KeyGate? = null
) {

    val rooms: List<Room> = rooms.toList()
    val tunnels: List<Tunnel> = tunnels.toList()

    fun translate(x: Int, y: Int, z: Int): DungeonLayout {
        val translatedRooms = rooms.map { it.translate(x, y, z) }
        val translatedTunnels = tunnels.map { it.translate(x, y, z) }
        return DungeonLayout(seed, difficulty, translatedRooms, translatedTunnels,
            bounds.translate(x, y, z), spawnX + x, spawnY + y, spawnZ + z, keyGate)
    }

    /**
     * Volumes are deliberately ordered: fill all solids before carving air.
     *
     * Builds procedural rooms while reserving selected normal-room slots for
     * their later tick-spread schematic operations. Schematic corridors still
     * receive the generic air carve, so procedural room shells open at their
     * doorway, but their platform and safety lips are replaced by the
     * schematic block operations supplied afterwards.
     */
    @JvmOverloads
    fun buildVolumes(style: GenerationStyle, prefabRoomIds: Set<String> = emptySet(),
                     schematicTunnelIds: Set<String> = emptySet()): List<BuildVolume> {
        val solids = ArrayList<BuildVolume>()
        val air = ArrayList<BuildVolume>()
        val markers = ArrayList<BuildVolume>()

        for (room in rooms) {
            if (room.id in prefabRoomIds) continue
            solids.add(BuildVolume(room.bounds, style.material(room.type)))
        }
        for (tunnel in tunnels) {
            if (tunnel.id() in schematicTunnelIds) continue
            for (floor in tunnel.floors) solids.add(BuildVolume(floor, style.corridorFloorMaterial()))
            for (lip in tunnel.lips) solids.add(BuildVolume(lip, style.corridorLipMaterial()))
        }

        for (room in rooms) {
            if (room.id in prefabRoomIds) continue
            val box = room.bounds
            air.add(BuildVolume(Bounds(box.minX + 1, box.minY + 1, box.minZ + 1,
                box.maxX - 1, box.maxY - 1, box.maxZ - 1), style.air()))
        }
        for (tunnel in tunnels) {
            for (hollow in tunnel.air) {
                air.add(BuildVolume(hollow, style.air()))
            }
        }

        for (room in rooms) {
            if (room.id in prefabRoomIds) continue
            for (marker in room.markers) {
                markers.add(BuildVolume(Bounds(marker.x, marker.y, marker.z, marker.x, marker.y, marker.z),
                    style.marker(marker.category)))
            }
        }

        solids.addAll(air)
        solids.addAll(markers)
        return solids.toList()
    }

    enum class RoomType {
        SPAWN,
        NORMAL,
        BRANCH,
        BOSS
    }

    enum class RoomVariant { PLAIN, PARKOUR }

    /**
     * The mandatory detour of a composed difficulty: the named tunnel stays
     * sealed until the guardian room's guardian dies. Both values are stable
     * identifiers, so translation never touches them.
     */
    data class KeyGate(val lockedTunnelId: String, val guardianRoomId: String)

    /** [role] names a composed room-role recipe, or null outside compositions. */
    class Room(
        val id: String,
        val type: RoomType,
        val bounds: Bounds,
        val depth: Int,
        val variant: RoomVariant,
        val role: String? = null,
        markers: List<DungeonMarker> = emptyList()
    ) {
        constructor(id: String, type: RoomType, bounds: Bounds, depth: Int, variant: RoomVariant,
                    markers: List<DungeonMarker>) : this(id, type, bounds, depth, variant, null, markers)

        val markers: List<DungeonMarker> = markers.toList()

        fun translate(x: Int, y: Int, z: Int): Room = Room(id, type, bounds.translate(x, y, z), depth, variant, role,
            markers.map { DungeonMarker(it.category, it.x + x, it.y + y, it.z + z) })
    }

    /** A flat corridor platform with optional safety lips and its walkable volume. */
    class Tunnel(
        val firstRoomId: String,
        val secondRoomId: String,
        val firstDoorway: Bounds,
        val secondDoorway: Bounds,
        floors: List<Bounds>,
        lips: List<Bounds>,
        air: List<Bounds>
    ) {
        val floors: List<Bounds> = floors.toList()
        val lips: List<Bounds> = lips.toList()
        val air: List<Bounds> = air.toList()

        fun translate(x: Int, y: Int, z: Int): Tunnel = Tunnel(firstRoomId, secondRoomId,
            firstDoorway.translate(x, y, z), secondDoorway.translate(x, y, z),
            floors.map { it.translate(x, y, z) },
            lips.map { it.translate(x, y, z) },
            air.map { it.translate(x, y, z) })

        fun id(): String = "$firstRoomId-$secondRoomId"

        /** All volume reserved by this corridor for layout collision checks. */
        fun occupied(): List<Bounds> {
            val result = ArrayList<Bounds>(floors.size + lips.size + air.size)
            result.addAll(floors)
            result.addAll(lips)
            result.addAll(air)
            return result.toList()
        }
    }
}
