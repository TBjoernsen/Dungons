package nl.riddernix.dungeonforge.api;

import org.bukkit.event.Event;

/**
 * Base class for everything DungeonForge fires.
 *
 * <p>Every event carries the dungeon it belongs to. All of them are fired on
 * the main thread, at a point where DungeonForge's own state is already
 * consistent - a listener never sees a half-updated dungeon.</p>
 *
 * <p>Only {@link DungeonStartEvent} is cancellable. Everything else is a
 * notification: the thing has already happened and cancelling it could leave a
 * dungeon stuck half-built or half-cleared.</p>
 */
public abstract class DungeonEvent extends Event {

    private final DungeonInfo dungeon;

    protected DungeonEvent(DungeonInfo dungeon) {
        this.dungeon = dungeon;
    }

    /** The dungeon this event belongs to, as it was when the event fired. */
    public DungeonInfo getDungeon() {
        return dungeon;
    }
}
