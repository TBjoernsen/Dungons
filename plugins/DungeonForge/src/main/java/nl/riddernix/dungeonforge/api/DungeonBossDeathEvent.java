package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * The dungeon boss has been defeated. Notification only.
 *
 * <p>Fires alongside {@link DungeonMobDeathEvent} for the same entity, before
 * the completion sequence starts. The dungeon is still {@link
 * DungeonState#ACTIVE} here; {@link DungeonEndEvent} follows immediately after
 * with {@link DungeonEndReason#COMPLETED}. Reward on the end event rather than
 * this one if you care about the run's final numbers.</p>
 */
public final class DungeonBossDeathEvent extends DungeonEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LivingEntity boss;
    private final EntityDeathEvent source;
    private final String theme;

    public DungeonBossDeathEvent(DungeonInfo dungeon, LivingEntity boss, EntityDeathEvent source, String theme) {
        super(dungeon);
        this.boss = boss;
        this.source = source;
        this.theme = theme;
    }

    public LivingEntity getBoss() {
        return boss;
    }

    public EntityDeathEvent getSource() {
        return source;
    }

    /** The theme this boss belonged to, for example {@code rift}. */
    public String getTheme() {
        return theme;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
