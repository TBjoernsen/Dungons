package nl.riddernix.dungeonforge.internal;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.api.DungeonInfo;
import nl.riddernix.dungeonforge.api.DungeonRoomInfo;
import nl.riddernix.dungeonforge.api.DungeonRoomType;
import nl.riddernix.dungeonforge.api.DungeonState;
import nl.riddernix.dungeonforge.party.DungeonParty;
import nl.riddernix.dungeonforge.room.DungeonInstance;
import nl.riddernix.dungeonforge.room.DungeonRoom;
import org.bukkit.World;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns DungeonForge's live state into the immutable records the API hands
 * out. The one place internals are translated, so no internal type ever
 * reaches a listener.
 */
public final class DungeonSnapshots {

    private final DungeonForgePlugin plugin;

    public DungeonSnapshots(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    /** A snapshot of a live dungeon, taken at the moment of the call. */
    public DungeonInfo of(DungeonInstance dungeon) {
        DungeonParty party = plugin.parties().partyForWorld(dungeon.world().getName()).orElse(null);
        List<UUID> members = party == null ? playerIds(dungeon.world()) : List.copyOf(party.members());
        Duration duration = party == null ? Duration.ZERO : plugin.parties().runDuration(party);
        List<DungeonRoom> rooms = dungeon.rooms();
        return new DungeonInfo(dungeon.id(), dungeon.world().getName(), dungeon.difficulty(), dungeon.seed(),
                dungeon.isCompleted() ? DungeonState.COMPLETED : DungeonState.ACTIVE,
                rooms.size(), plugin.mobs().clearedRoomCount(dungeon.id()), deepestDepth(dungeon),
                dungeon.mobKillCount(), members, duration);
    }

    /**
     * The dungeon a run is about to build. Nothing exists yet, so the room
     * counts are zero and the world name is only reserved.
     */
    public DungeonInfo pending(String worldName, int difficulty, long seed, List<UUID> partyMembers) {
        return new DungeonInfo(worldName, worldName, difficulty, seed, DungeonState.GENERATING,
                0, 0, 0, 0, partyMembers, Duration.ZERO);
    }

    /** The same dungeon, restated as ending. Used by the end event. */
    public DungeonInfo ending(DungeonInstance dungeon, boolean completed) {
        DungeonInfo live = of(dungeon);
        return new DungeonInfo(live.id(), live.worldName(), live.difficulty(), live.seed(),
                completed ? DungeonState.COMPLETED : DungeonState.ENDING,
                live.roomsTotal(), live.roomsCleared(), live.deepestRoomDepth(), live.mobsKilled(),
                live.partyMembers(), live.runDuration());
    }

    public DungeonRoomInfo of(DungeonRoom room) {
        return new DungeonRoomInfo(room.id(), roomType(room), room.depth());
    }

    /** Internal room types map one to one, but never assume it: fall back cleanly. */
    private static DungeonRoomType roomType(DungeonRoom room) {
        try {
            return DungeonRoomType.valueOf(room.type().name());
        } catch (IllegalArgumentException exception) {
            return DungeonRoomType.NORMAL;
        }
    }

    /** How far from the entrance anyone has reached, by room depth. */
    private int deepestDepth(DungeonInstance dungeon) {
        int deepest = 0;
        for (DungeonRoom room : dungeon.rooms()) {
            if (plugin.mobs().isRoomVisited(dungeon.id(), room.id())) {
                deepest = Math.max(deepest, room.depth());
            }
        }
        return deepest;
    }

    private static List<UUID> playerIds(World world) {
        List<UUID> ids = new ArrayList<>();
        world.getPlayers().forEach(player -> ids.add(player.getUniqueId()));
        return ids;
    }
}
