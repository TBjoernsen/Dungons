package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.HandlerList;

/**
 * A dungeon mob has been spawned and set up. Notification only.
 *
 * <p>Fired after DungeonForge has applied its own attributes, equipment and
 * name, and before the mob is handed to the world. Anything you change here
 * sticks, so this is the place to buff a mob, re-equip it or attach your own
 * metadata.</p>
 *
 * <p>Also fires for the tagged test mobs from {@code /dungeon summon}, which
 * belong to no dungeon. For those {@link #getDungeon()} is null - check it
 * before using it, or check {@link #isTestMob()}.</p>
 */
public final class DungeonMobSpawnEvent extends DungeonEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LivingEntity entity;
    private final DungeonMobInfo mob;

    public DungeonMobSpawnEvent(DungeonInfo dungeon, LivingEntity entity, DungeonMobInfo mob) {
        super(dungeon);
        this.entity = entity;
        this.mob = mob;
    }

    public LivingEntity getEntity() {
        return entity;
    }

    /** Tier, difficulty, category and theme of the mob being spawned. */
    public DungeonMobInfo getMob() {
        return mob;
    }

    /** True for {@code /dungeon summon} test mobs, which have no dungeon. */
    public boolean isTestMob() {
        return getDungeon() == null;
    }

    public int getDifficulty() {
        return mob.difficulty();
    }

    public int getTier() {
        return mob.tier();
    }

    public boolean isBoss() {
        return mob.boss();
    }

    /** The theme this mob was rolled from, for example {@code crypt}. */
    public String getTheme() {
        return mob.theme();
    }

    /** {@code swarm}, {@code pack}, {@code champion} or {@code boss}. */
    public String getCategory() {
        return mob.category();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
