package nl.riddernix.dungeonforge.room;

/** The detected topological shape of a normal prefab's doorway markers. */
public enum NormalRoomShape {
    STRAIGHT,
    CORNER,
    TJUNCTION,
    CROSS,
    DEAD_END,
    UNKNOWN;

    public String configName() {
        return switch (this) {
            case STRAIGHT -> "straight";
            case CORNER -> "corner";
            case TJUNCTION -> "tjunction";
            case CROSS -> "cross";
            case DEAD_END -> "dead_end";
            case UNKNOWN -> "unknown";
        };
    }
}
