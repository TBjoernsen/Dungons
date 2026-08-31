package nl.riddernix.dungeonforge.fx;

/**
 * A scripted entrance played while a boss's summoning sequence runs.
 *
 * <p>The sequence owns the clock, the boss's AI and its invulnerability. An
 * animation only draws: it is handed the current tick and decides what the
 * world should look like at that moment.</p>
 */
public interface SpawnAnimation {

    /** Called once, before the first {@link #tick(int, int)}. */
    void begin();

    /**
     * @param ticks         ticks elapsed since {@link #begin()}
     * @param durationTicks total length of the summoning sequence
     */
    void tick(int ticks, int durationTicks);

    /** Plays the outro and cleans up afterwards. */
    void finish();

    /** Removes everything immediately, for a dungeon that is being torn down. */
    void abort();

    /**
     * One line naming what this animation actually created and where.
     *
     * <p>Display entities are invisible when a transformation is wrong, so
     * "nothing happened" and "nothing was created" look identical in game.
     * This is what tells those two apart.</p>
     */
    String describe();
}
