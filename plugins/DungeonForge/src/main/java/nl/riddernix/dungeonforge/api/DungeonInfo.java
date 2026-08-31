package nl.riddernix.dungeonforge.api;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * An immutable snapshot of one dungeon.
 *
 * <p>Every event carries one of these, so a listener never has to track
 * dungeon state itself. It is a snapshot, not a live view: read what you need
 * during the event rather than holding on to it.</p>
 *
 * @param id                 stable for the life of the dungeon
 * @param worldName          the world this dungeon owns; it is deleted when the run ends
 * @param difficulty         1 to 9
 * @param seed               the layout seed, so a run can be reproduced
 * @param state              where the dungeon is in its life
 * @param roomsTotal         rooms in the layout, corridors excluded
 * @param roomsCleared       rooms whose mobs have all been killed
 * @param deepestRoomDepth   how far from the entrance the party has reached
 * @param mobsKilled         dungeon mobs killed so far
 * @param partyMembers       everyone in the party, whether or not they are inside
 * @param runDuration        time since the party entered; zero before it starts
 */
public record DungeonInfo(String id, String worldName, int difficulty, long seed, DungeonState state,
                          int roomsTotal, int roomsCleared, int deepestRoomDepth, int mobsKilled,
                          List<UUID> partyMembers, Duration runDuration) {

    public DungeonInfo {
        partyMembers = List.copyOf(partyMembers);
    }

    /** Rooms cleared as a fraction of the layout, 0.0 to 1.0. */
    public double progress() {
        return roomsTotal <= 0 ? 0.0 : Math.min(1.0, (double) roomsCleared / roomsTotal);
    }
}
