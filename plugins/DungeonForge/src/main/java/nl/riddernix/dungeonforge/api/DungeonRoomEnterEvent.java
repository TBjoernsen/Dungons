package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * A player has walked into a room. Notification only.
 *
 * <p>Fired after DungeonForge has registered the room as occupied, and before
 * its mobs are spawned, so a listener can prepare something for the fight that
 * is about to start.</p>
 */
public final class DungeonRoomEnterEvent extends DungeonPlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final DungeonRoomInfo room;
    private final boolean firstVisit;

    public DungeonRoomEnterEvent(DungeonInfo dungeon, Player player, DungeonRoomInfo room, boolean firstVisit) {
        super(dungeon, player);
        this.room = room;
        this.firstVisit = firstVisit;
    }

    public DungeonRoomInfo getRoom() {
        return room;
    }

    /** False when anyone has been in this room before, so backtracking is easy to ignore. */
    public boolean isFirstVisit() {
        return firstVisit;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
