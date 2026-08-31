package nl.riddernix.dungeonforge.room;

import nl.riddernix.dungeonforge.generation.Bounds;

import java.util.List;

/** Runtime connection between exactly two rooms, including its walkable volumes. */
public record DungeonCorridor(String id, String firstRoomId, String secondRoomId, List<Bounds> walkableBounds) {
    public DungeonCorridor { walkableBounds = List.copyOf(walkableBounds); }
    public boolean contains(int x, int y, int z) { return walkableBounds.stream().anyMatch(bounds -> bounds.contains(x, y, z)); }
    public String otherRoom(String roomId) {
        return firstRoomId.equals(roomId) ? secondRoomId : secondRoomId.equals(roomId) ? firstRoomId : null;
    }
}
