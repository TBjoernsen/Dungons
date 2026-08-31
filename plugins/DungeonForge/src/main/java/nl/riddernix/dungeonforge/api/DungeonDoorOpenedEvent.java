package nl.riddernix.dungeonforge.api;

import org.bukkit.event.HandlerList;

/**
 * The sealed door of a composed dungeon opened. Notification only.
 *
 * <p>Fires once per dungeon: normally right after
 * {@link DungeonKeyObtainedEvent}, or on its own when the door was forced -
 * by an admin command, or automatically after the key guardian stopped
 * existing and could not be revived.</p>
 */
public final class DungeonDoorOpenedEvent extends DungeonEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean forced;

    public DungeonDoorOpenedEvent(DungeonInfo dungeon, boolean forced) {
        super(dungeon);
        this.forced = forced;
    }

    /** True when the door was opened without the guardian's key. */
    public boolean isForced() {
        return forced;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
