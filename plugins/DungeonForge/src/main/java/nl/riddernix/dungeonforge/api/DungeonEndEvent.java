package nl.riddernix.dungeonforge.api;

import org.bukkit.event.HandlerList;

/**
 * A dungeon has ended. Notification only.
 *
 * <p>Fired while the world still exists and before it is deleted, so the
 * dungeon can still be inspected. It fires exactly once per dungeon whatever
 * the reason, so it is the right place to release anything a listener has been
 * tracking per run.</p>
 */
public final class DungeonEndEvent extends DungeonEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final DungeonEndReason reason;

    public DungeonEndEvent(DungeonInfo dungeon, DungeonEndReason reason) {
        super(dungeon);
        this.reason = reason;
    }

    public DungeonEndReason getReason() {
        return reason;
    }

    /** Convenience for the common "did they win" check. */
    public boolean isCompleted() {
        return reason == DungeonEndReason.COMPLETED;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
