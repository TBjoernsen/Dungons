package nl.riddernix.dungeonforge.api;

/**
 * Immutable public metadata attached to a DungeonForge dungeon mob.
 *
 * @param tier       power band, rising with difficulty
 * @param difficulty the dungeon difficulty this mob was rolled for, 1 to 9
 * @param category   {@code swarm}, {@code pack}, {@code champion} or {@code boss}
 * @param theme      the theme it came from, for example {@code crypt}; may be empty
 * @param boss       whether this is the dungeon's boss
 */
public record DungeonMobInfo(int tier, int difficulty, String category, String theme, boolean boss) {

    public DungeonMobInfo {
        category = category == null ? "" : category;
        theme = theme == null ? "" : theme;
    }
}
