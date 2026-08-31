package nl.riddernix.dungeonforge.api;

/** Why a dungeon stopped existing. */
public enum DungeonEndReason {
    /** The boss was defeated. */
    COMPLETED,
    /** Everyone left, or the party leader ended the run. */
    ABANDONED,
    /** Removed administratively, on shutdown, or as leftover from a crash. */
    CLEANED_UP
}
