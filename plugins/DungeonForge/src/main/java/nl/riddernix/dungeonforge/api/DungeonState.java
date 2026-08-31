package nl.riddernix.dungeonforge.api;

/** Where a dungeon is in its life. */
public enum DungeonState {
    /** Requested and being built; no player is inside yet. */
    GENERATING,
    /** Built and playable. */
    ACTIVE,
    /** The boss is down and the return sequence is running. */
    COMPLETED,
    /** Being torn down; its world is about to disappear. */
    ENDING
}
