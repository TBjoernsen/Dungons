package nl.riddernix.dungeonforge.room;

/** A non-combat prefab marker kept as runtime placement metadata. */
public record DungeonSpecialMarker(SpecialMarkerKind kind, int x, int y, int z) {
}
