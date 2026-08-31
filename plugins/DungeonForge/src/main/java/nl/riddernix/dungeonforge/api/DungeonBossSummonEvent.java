package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.HandlerList;

/**
 * The boss's summoning sequence has begun. Notification only.
 *
 * <p>Fires when the first player commits to the arena, at the start of the
 * entrance animation and before the boss's minions arrive. The boss entity
 * already exists but has its AI switched off for the duration, so this is the
 * moment to start music, a title, or a scripted effect of your own.</p>
 *
 * <p>Not cancellable: the sequence owns the boss's AI and invulnerability and
 * has to run to its end to hand them back, so stopping it halfway would leave
 * an inert, invulnerable boss standing in the arena.</p>
 */
public final class DungeonBossSummonEvent extends DungeonEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LivingEntity boss;
    private final String theme;
    private final int durationTicks;

    public DungeonBossSummonEvent(DungeonInfo dungeon, LivingEntity boss, String theme, int durationTicks) {
        super(dungeon);
        this.boss = boss;
        this.theme = theme;
        this.durationTicks = durationTicks;
    }

    public LivingEntity getBoss() {
        return boss;
    }

    /** The theme this boss belongs to, for example {@code rift}. */
    public String getTheme() {
        return theme;
    }

    /** How long the sequence runs, so effects can be timed against it. */
    public int getDurationTicks() {
        return durationTicks;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
