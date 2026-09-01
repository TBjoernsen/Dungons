package nl.riddernix.dungeonplugin.fx

/**
 * A scripted entrance played while a boss's summoning sequence runs.
 *
 * The sequence owns the clock, the boss's AI and its invulnerability. An
 * animation only draws: it is handed the current tick and decides what the
 * world should look like at that moment.
 */
interface SpawnAnimation {

    /** Called once, before the first [tick]. */
    fun begin()

    /**
     * @param ticks         ticks elapsed since [begin]
     * @param durationTicks total length of the summoning sequence
     */
    fun tick(ticks: Int, durationTicks: Int)

    /** Plays the outro and cleans up afterwards. */
    fun finish()

    /** Removes everything immediately, for a dungeon that is being torn down. */
    fun abort()

    /**
     * One line naming what this animation actually created and where.
     *
     * Display entities are invisible when a transformation is wrong, so
     * "nothing happened" and "nothing was created" look identical in game.
     * This is what tells those two apart.
     */
    fun describe(): String
}
