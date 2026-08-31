package nl.riddernix.dungeonforge.api;

import org.bukkit.event.HandlerList;

/**
 * The party obtained the key of a composed dungeon's sealed door.
 * Notification only.
 *
 * <p>Fires once per dungeon, the moment its key guardian dies. The key is
 * party state, not an item: it cannot be dropped, lost on death, or carried
 * out of the dungeon. {@link DungeonDoorOpenedEvent} follows immediately.</p>
 */
public final class DungeonKeyObtainedEvent extends DungeonEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String guardianRoomId;

    public DungeonKeyObtainedEvent(DungeonInfo dungeon, String guardianRoomId) {
        super(dungeon);
        this.guardianRoomId = guardianRoomId;
    }

    /** The room whose guardian held the key. */
    public String getGuardianRoomId() {
        return guardianRoomId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
