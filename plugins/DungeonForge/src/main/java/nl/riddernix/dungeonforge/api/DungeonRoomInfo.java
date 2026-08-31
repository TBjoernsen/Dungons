package nl.riddernix.dungeonforge.api;

/** Immutable public description of a room currently occupied by a player. */
public record DungeonRoomInfo(String id, DungeonRoomType type, int depth) {
}
