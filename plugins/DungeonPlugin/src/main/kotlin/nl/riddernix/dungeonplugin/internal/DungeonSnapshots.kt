package nl.riddernix.dungeonplugin.internal

import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.event.DungeonInfo
import nl.riddernix.dungeonplugin.event.DungeonRoomInfo
import nl.riddernix.dungeonplugin.event.DungeonRoomType
import nl.riddernix.dungeonplugin.event.DungeonState
import nl.riddernix.dungeonplugin.room.DungeonInstance
import nl.riddernix.dungeonplugin.room.DungeonRoom
import org.bukkit.World
import java.time.Duration
import java.util.UUID

/**
 * Turns the plugin's live state into the immutable records events carry.
 * The one place internals are translated, so no internal type ever reaches a
 * listener.
 */
class DungeonSnapshots(private val plugin: DungeonPlugin) {

    /** A snapshot of a live dungeon, taken at the moment of the call. */
    fun of(dungeon: DungeonInstance): DungeonInfo {
        val party = plugin.parties.partyForWorld(dungeon.world.name)
        val members = party?.members?.toList() ?: playerIds(dungeon.world)
        val duration = party?.let { plugin.parties.runDuration(it) } ?: Duration.ZERO
        val rooms = dungeon.rooms
        return DungeonInfo(dungeon.id, dungeon.world.name, dungeon.difficulty, dungeon.seed,
            if (dungeon.isCompleted) DungeonState.COMPLETED else DungeonState.ACTIVE,
            rooms.size, plugin.mobs.clearedRoomCount(dungeon.id), deepestDepth(dungeon),
            dungeon.mobKillCount, members, duration)
    }

    /**
     * The dungeon a run is about to build. Nothing exists yet, so the room
     * counts are zero and the world name is only reserved.
     */
    fun pending(worldName: String, difficulty: Int, seed: Long, partyMembers: List<UUID>): DungeonInfo =
        DungeonInfo(worldName, worldName, difficulty, seed, DungeonState.GENERATING,
            0, 0, 0, 0, partyMembers, Duration.ZERO)

    /** The same dungeon, restated as ending. Used by the end event. */
    fun ending(dungeon: DungeonInstance, completed: Boolean): DungeonInfo {
        val live = of(dungeon)
        return DungeonInfo(live.id, live.worldName, live.difficulty, live.seed,
            if (completed) DungeonState.COMPLETED else DungeonState.ENDING,
            live.roomsTotal, live.roomsCleared, live.deepestRoomDepth, live.mobsKilled,
            live.partyMembers, live.runDuration)
    }

    fun of(room: DungeonRoom): DungeonRoomInfo = DungeonRoomInfo(room.id, roomType(room), room.depth)

    /** How far from the entrance anyone has reached, by room depth. */
    private fun deepestDepth(dungeon: DungeonInstance): Int {
        var deepest = 0
        for (room in dungeon.rooms) {
            if (plugin.mobs.isRoomVisited(dungeon.id, room.id)) {
                deepest = maxOf(deepest, room.depth)
            }
        }
        return deepest
    }

    companion object {
        /** Internal room types map one to one, but never assume it: fall back cleanly. */
        private fun roomType(room: DungeonRoom): DungeonRoomType = try {
            DungeonRoomType.valueOf(room.type.name)
        } catch (exception: IllegalArgumentException) {
            DungeonRoomType.NORMAL
        }

        private fun playerIds(world: World): List<UUID> = world.players.map { it.uniqueId }
    }
}
