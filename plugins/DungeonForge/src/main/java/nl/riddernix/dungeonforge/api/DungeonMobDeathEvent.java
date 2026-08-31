package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * A dungeon mob has died. Notification only.
 *
 * <p>Fired after DungeonForge has counted the kill, so the dungeon's mob total
 * already includes it. DungeonForge clears vanilla drops and sets its own
 * experience on the underlying event; if you want drops of your own, add them
 * to {@link #getSource()} from a listener at {@code MONITOR} priority, which
 * runs after DungeonForge is finished with it.</p>
 *
 * <p>Bosses fire {@link DungeonBossDeathEvent} as well as this one.</p>
 */
public final class DungeonMobDeathEvent extends DungeonEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LivingEntity entity;
    private final EntityDeathEvent source;
    private final DungeonMobInfo mob;
    private final DungeonRoomInfo room;

    public DungeonMobDeathEvent(DungeonInfo dungeon, LivingEntity entity, EntityDeathEvent source,
                                DungeonMobInfo mob, DungeonRoomInfo room) {
        super(dungeon);
        this.entity = entity;
        this.source = source;
        this.mob = mob;
        this.room = room;
    }

    public LivingEntity getEntity() {
        return entity;
    }

    /** The underlying Bukkit event, for drops and experience. */
    public EntityDeathEvent getSource() {
        return source;
    }

    /** Tier, difficulty, category and theme of the mob that died. */
    public DungeonMobInfo getMob() {
        return mob;
    }

    /** The room it was spawned for, or null if that room is already gone. */
    public DungeonRoomInfo getRoom() {
        return room;
    }

    public boolean isBoss() {
        return mob.boss();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
