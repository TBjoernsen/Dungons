package nl.riddernix.dungeonplugin.room

import nl.riddernix.dungeonplugin.generation.Bounds
import nl.riddernix.dungeonplugin.generation.DungeonLayout
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import java.util.UUID

/** Runtime-only registry entry for a generated party dungeon. */
class DungeonInstance @JvmOverloads constructor(
    val world: World,
    layout: DungeonLayout,
    scannedMarkers: Map<String, List<DungeonMarker>>,
    prefabDoorways: Map<String, List<DungeonDoorway>>,
    playableBounds: Map<String, Bounds> = emptyMap(),
    playerSpawns: Map<String, DungeonSpecialMarker> = emptyMap(),
    bossSpawns: Map<String, DungeonSpecialMarker> = emptyMap(),
    traps: Map<String, DungeonTrap> = emptyMap(),
    prefabFiles: Map<String, String> = emptyMap()
) {

    val id: String = UUID.randomUUID().toString()
    val difficulty: Int = layout.difficulty
    val seed: Long = layout.seed

    private val roomsById: Map<String, DungeonRoom>
    val tunnels: List<DungeonLayout.Tunnel>
    val corridors: List<DungeonCorridor>
    private val playerSpawns: Map<String, DungeonSpecialMarker>
    private val bossSpawns: Map<String, DungeonSpecialMarker>

    /** Authored trap floors by room id, already in world coordinates. */
    val traps: Map<String, DungeonTrap>

    private val prefabFiles: Map<String, String>

    /** The sealed-corridor contract of a composed layout, or null without one. */
    val keyGate: DungeonLayout.KeyGate?

    var isCompleted: Boolean = false
        private set
    var isKeyObtained: Boolean = false
        private set
    var mobKillCount: Int = 0
        private set

    /**
     * How many players the mob numbers are balanced for, fixed when the
     * dungeon registers.
     *
     * Deliberately locked rather than read live. Rooms populate ahead of the
     * party, so a live reading would mean each room was balanced for whoever
     * happened to be in the party when that room was built - invisible to
     * players, and open to having someone log out before the boss room fills.
     * One number for the whole run is the thing a party can reason about.
     *
     * The party size every mob in this run is scaled for; never below 1.
     */
    var partySize: Int = 1
        private set

    init {
        val indexedRooms = LinkedHashMap<String, DungeonRoom>()
        for (room in layout.rooms) {
            val markers = scannedMarkers[room.id] ?: room.markers
            val doorways = prefabDoorways[room.id] ?: emptyList()
            val bounds = playableBounds[room.id] ?: room.bounds
            indexedRooms[room.id] = DungeonRoom(room.id, room.type, bounds, room.bounds.minY + 1,
                room.depth, room.variant, room.role, markers, doorways, id)
        }
        this.roomsById = indexedRooms.toMap()
        this.tunnels = layout.tunnels.toList()
        this.corridors = layout.tunnels.map { tunnel ->
            DungeonCorridor(tunnel.firstRoomId + "-" + tunnel.secondRoomId,
                tunnel.firstRoomId, tunnel.secondRoomId, tunnel.air)
        }
        this.playerSpawns = playerSpawns.toMap()
        this.bossSpawns = bossSpawns.toMap()
        this.traps = traps.toMap()
        this.prefabFiles = prefabFiles.toMap()
        this.keyGate = layout.keyGate
    }

    /** Called once at registration. Later joins and leaves do not move it. */
    fun lockPartySize(size: Int) {
        partySize = maxOf(1, size)
    }

    val rooms: List<DungeonRoom>
        get() = roomsById.values.toList()

    fun room(roomId: String): DungeonRoom? = roomsById[roomId]

    /** The consumed marker block itself becomes the player's feet location. */
    fun playerSpawnLocation(): Location? {
        val spawnRoom = roomsById.values.firstOrNull { it.type == DungeonLayout.RoomType.SPAWN } ?: return null
        val marker = playerSpawns[spawnRoom.id] ?: return null
        clearMarker(marker)
        return Location(world, marker.x + 0.5, marker.y.toDouble(), marker.z + 0.5, spawnYaw(spawnRoom), 0.0F)
    }

    /** The consumed marker block itself becomes the boss's feet location. */
    fun bossSpawnLocation(): Location? {
        val bossRoom = roomsById.values.firstOrNull { it.type == DungeonLayout.RoomType.BOSS } ?: return null
        val marker = bossSpawns[bossRoom.id] ?: return null
        clearMarker(marker)
        return Location(world, marker.x + 0.5, marker.y.toDouble(), marker.z + 0.5)
    }

    /** Idempotent safety guard: marker removal always precedes a teleport or spawn. */
    private fun clearMarker(marker: DungeonSpecialMarker) {
        world.getBlockAt(marker.x, marker.y, marker.z).setType(Material.AIR, false)
    }

    /** The schematic a room was built from, or null when it fell back to procedural stone. */
    fun prefabFile(roomId: String): String? = prefabFiles[roomId]

    /** Grants the party's key once; returns false when it was already held. */
    fun obtainKey(): Boolean {
        if (isKeyObtained) return false
        isKeyObtained = true
        return true
    }

    /** Marks this disposable instance complete once; returns false on duplicates. */
    fun complete(): Boolean {
        if (isCompleted) return false
        isCompleted = true
        return true
    }

    fun recordMobKill() {
        mobKillCount++
    }

    companion object {
        private fun spawnYaw(room: DungeonRoom): Float {
            val exit = room.doorways.map(DungeonDoorway::facing).firstOrNull() ?: BlockFace.SOUTH
            return when (exit) {
                BlockFace.NORTH -> 180.0F
                BlockFace.EAST -> -90.0F
                BlockFace.WEST -> 90.0F
                else -> 0.0F
            }
        }
    }
}
