package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * A player has died inside a dungeon. Notification only.
 *
 * <p>Fired from Bukkit's own death event, which is still available here for
 * drops, experience and the death message. Change those on
 * {@link #getSource()}; this event exists to tell you which dungeon and room
 * it happened in without you having to work it out.</p>
 */
public final class DungeonPlayerDeathEvent extends DungeonPlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PlayerDeathEvent source;
    private final DungeonRoomInfo room;

    public DungeonPlayerDeathEvent(DungeonInfo dungeon, Player player, PlayerDeathEvent source, DungeonRoomInfo room) {
        super(dungeon, player);
        this.source = source;
        this.room = room;
    }

    /** The underlying Bukkit event, for drops, experience and the message. */
    public PlayerDeathEvent getSource() {
        return source;
    }

    /** The room they died in, or null if they were in a corridor. */
    public DungeonRoomInfo getRoom() {
        return room;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
