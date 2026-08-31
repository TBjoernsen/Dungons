package nl.riddernix.dungeonforge.room;

/** Explicit marker roles which are not combat-group spawn positions. */
public enum SpecialMarkerKind {
    PLAYER_SPAWN("player-spawn"),
    BOSS_SPAWN("boss-spawn");

    private final String configName;

    SpecialMarkerKind(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }
}
