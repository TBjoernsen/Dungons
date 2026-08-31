package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * A player has arrived inside a dungeon world. Notification only.
 *
 * <p>Fired after the teleport has landed, so the player is already standing in
 * the dungeon when a listener sees it.</p>
 */
public final class DungeonPlayerEnterEvent extends DungeonPlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public DungeonPlayerEnterEvent(DungeonInfo dungeon, Player player) {
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
