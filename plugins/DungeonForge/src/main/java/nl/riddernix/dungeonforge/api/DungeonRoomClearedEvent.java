package nl.riddernix.dungeonforge.api;

import org.bukkit.event.HandlerList;

import java.util.List;
import java.util.UUID;

/**
 * Every mob in a room has been killed. Notification only.
 *
 * <p>Fires once per room per dungeon, after the last mob's death has been
 * counted, so {@link #getDungeon()} already includes this room in its cleared
 * total. Rooms that never had mobs never fire it.</p>
 */
public final class DungeonRoomClearedEvent extends DungeonEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final DungeonRoomInfo room;
    private final List<UUID> playersInside;

    public DungeonRoomClearedEvent(DungeonInfo dungeon, DungeonRoomInfo room, List<UUID> playersInside) {
        super(dungeon);
        this.room = room;
        this.playersInside = List.copyOf(playersInside);
    }

    public DungeonRoomInfo getRoom() {
        return room;
    }

    /** Who was standing in the dungeon when it cleared, for handing out rewards. */
    public List<UUID> getPlayersInside() {
        return playersInside;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
