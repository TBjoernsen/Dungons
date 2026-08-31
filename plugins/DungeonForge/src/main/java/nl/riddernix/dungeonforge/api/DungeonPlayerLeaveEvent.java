package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * A player has left a dungeon world. Notification only.
 *
 * <p>Covers walking out, being evacuated, disconnecting and dying out of the
 * instance, so it is a reliable place to clean up anything held per player.
 * The dungeon itself may well carry on without them.</p>
 */
public final class DungeonPlayerLeaveEvent extends DungeonPlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public DungeonPlayerLeaveEvent(DungeonInfo dungeon, Player player) {
        super(dungeon, player);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
