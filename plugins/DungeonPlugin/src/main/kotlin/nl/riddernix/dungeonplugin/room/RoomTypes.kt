package nl.riddernix.dungeonplugin.room

import nl.riddernix.dungeonplugin.generation.Bounds
import nl.riddernix.dungeonplugin.generation.DungeonLayout
import org.bukkit.block.BlockFace
import org.bukkit.util.BlockVector
import java.util.function.IntPredicate

/** A consumed structure marker retained as dungeon metadata. */
data class DungeonMarker(val category: String, val x: Int, val y: Int, val z: Int)

/** Explicit marker roles which are not combat-group spawn positions. */
enum class SpecialMarkerKind(val configName: String) {
    PLAYER_SPAWN("player-spawn"),
    BOSS_SPAWN("boss-spawn")
}

/** A non-combat prefab marker kept as runtime placement metadata. */
data class DungeonSpecialMarker(val kind: SpecialMarkerKind, val x: Int, val y: Int, val z: Int)

/** A prefab doorway marker resolved into a world position and its outward face. */
data class DungeonDoorway(val x: Int, val y: Int, val z: Int, val wall: BlockFace, val facing: BlockFace)

/**
 * The centre line of a doorway marker group. This is separate from the air
 * opening below it: prefabs use the marker group as their exact connector
 * reference while the opening remains the player-sized passage.
 */
data class DungeonDoorMarker(val x: Int, val y: Int, val z: Int, val facing: BlockFace, val width: Int) {
    init {
        require(width >= 1 && (width and 1) == 1) {
            "Doorway marker width must be a positive odd number."
        }
    }
}

/** Runtime connection between exactly two rooms, including its walkable volumes. */
class DungeonCorridor(val id: String, val firstRoomId: String, val secondRoomId: String, walkableBounds: List<Bounds>) {
    val walkableBounds: List<Bounds> = walkableBounds.toList()

    fun contains(x: Int, y: Int, z: Int): Boolean = walkableBounds.any { it.contains(x, y, z) }

    fun otherRoom(roomId: String): String? = when (roomId) {
        firstRoomId -> secondRoomId
        secondRoomId -> firstRoomId
        else -> null
    }
}

/** The detected topological shape of a normal prefab's doorway markers. */
enum class NormalRoomShape {
    STRAIGHT,
    CORNER,
    TJUNCTION,
    CROSS,
    DEAD_END,
    UNKNOWN;

    fun configName(): String = when (this) {
        STRAIGHT -> "straight"
        CORNER -> "corner"
        TJUNCTION -> "tjunction"
        CROSS -> "cross"
        DEAD_END -> "dead_end"
        UNKNOWN -> "unknown"
    }
}

/**
 * Immutable room metadata retained for one running dungeon instance.
 *
 * [floorY] is the feet height of the room's walkable floor, taken from the
 * layout rather than the placed geometry. A prefab may stand on a thick
 * decorative foundation, so its own lower bound is buried inside solid
 * blocks; every corridor still enters one block above the layout floor, which
 * makes this the one height where a mob or a boss can actually stand.
 */
class DungeonRoom(
    val id: String,
    val type: DungeonLayout.RoomType,
    val bounds: Bounds,
    val floorY: Int,
    val depth: Int,
    val variant: DungeonLayout.RoomVariant,
    val role: String?,
    markers: List<DungeonMarker>,
    doorways: List<DungeonDoorway>,
    val dungeonId: String
) {
    val markers: List<DungeonMarker> = markers.toList()
    val doorways: List<DungeonDoorway> = doorways.toList()
}

/**
 * One room's authored trap floor, in world coordinates after placement.
 *
 * Each column's [Column.topY] is the visible walking-floor block that
 * vanishes; the drop carves everything from there down to the bottom of the
 * room, so the hole opens into the void. Every pressure plate recorded for
 * the room triggers the same single trap.
 */
class DungeonTrap(val roomId: String, columns: List<Column>, plates: Set<BlockVector>) {

    val columns: List<Column> = columns.toList()
    val plates: Set<BlockVector> = plates.toSet()

    data class Column(val x: Int, val topY: Int, val z: Int)

    companion object {
        /**
         * How far the collapse climbs above one marked floor block.
         *
         * The single definition of the rule, so the count reported by
         * `/dungeon rooms` and the blocks actually removed at runtime can
         * never drift apart. Straight up from the marker, through anything
         * that is not air, and stopping at the first gap: whatever stands on
         * the floor goes down with it, while a platform floating above the
         * trap keeps its gap and stays put. [maximumRise] is the ceiling on
         * how much of a tall pillar can be taken along.
         *
         * @param solidAbove tests whether the block at that height is not air
         * @return the number of blocks above [topY] that fall
         */
        @JvmStatic
        fun rise(solidAbove: IntPredicate, topY: Int, maximumRise: Int): Int {
            var rise = 0
            while (rise < maximumRise && solidAbove.test(topY + rise + 1)) {
                rise++
            }
            return rise
        }
    }
}
