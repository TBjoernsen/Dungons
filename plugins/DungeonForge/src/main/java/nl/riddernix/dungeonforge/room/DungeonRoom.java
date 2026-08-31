package nl.riddernix.dungeonforge.room;

import nl.riddernix.dungeonforge.generation.Bounds;
import nl.riddernix.dungeonforge.generation.DungeonLayout;

/**
 * Immutable room metadata retained for one running dungeon instance.
 *
 * <p>{@code floorY} is the feet height of the room's walkable floor, taken
 * from the layout rather than the placed geometry. A prefab may stand on a
 * thick decorative foundation, so its own lower bound is buried inside solid
 * blocks; every corridor still enters one block above the layout floor, which
 * makes this the one height where a mob or a boss can actually stand.</p>
 */
public record DungeonRoom(String id, DungeonLayout.RoomType type, Bounds bounds, int floorY, int depth,
                          DungeonLayout.RoomVariant variant, String role, java.util.List<DungeonMarker> markers,
                          java.util.List<DungeonDoorway> doorways, String dungeonId) {
    public DungeonRoom {
        markers = java.util.List.copyOf(markers);
        doorways = java.util.List.copyOf(doorways);
    }
}
