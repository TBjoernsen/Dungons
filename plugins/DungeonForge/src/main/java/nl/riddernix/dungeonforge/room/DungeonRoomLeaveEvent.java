package nl.riddernix.dungeonforge.room;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after periodic room detection finds a player leaving a dungeon room. */
public final class DungeonRoomLeaveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final DungeonInstance dungeon;
    private final DungeonRoom room;

    public DungeonRoomLeaveEvent(Player player, DungeonInstance dungeon, DungeonRoom room) {
        this.player = player;
        this.dungeon = dungeon;
        this.room = room;
    }

    public Player getPlayer() { return player; }
    public DungeonInstance getDungeon() { return dungeon; }
    public DungeonRoom getRoom() { return room; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
