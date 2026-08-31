package nl.riddernix.dungeonforge.generation;

import java.util.ArrayList;
import java.util.List;
import nl.riddernix.dungeonforge.room.DungeonMarker;

/** A fully planned dungeon before any blocks have been changed. */
public record DungeonLayout(
        long seed,
        int difficulty,
        List<Room> rooms,
        List<Tunnel> tunnels,
        Bounds bounds,
        int spawnX,
        int spawnY,
        int spawnZ,
        KeyGate keyGate
) {

    public DungeonLayout {
        rooms = List.copyOf(rooms);
        tunnels = List.copyOf(tunnels);
    }

    public DungeonLayout(long seed, int difficulty, List<Room> rooms, List<Tunnel> tunnels, Bounds bounds,
                         int spawnX, int spawnY, int spawnZ) {
        this(seed, difficulty, rooms, tunnels, bounds, spawnX, spawnY, spawnZ, null);
    }

    public DungeonLayout translate(int x, int y, int z) {
        List<Room> translatedRooms = rooms.stream().map(room -> room.translate(x, y, z)).toList();
        List<Tunnel> translatedTunnels = tunnels.stream().map(tunnel -> tunnel.translate(x, y, z)).toList();
        return new DungeonLayout(seed, difficulty, translatedRooms, translatedTunnels,
                bounds.translate(x, y, z), spawnX + x, spawnY + y, spawnZ + z, keyGate);
    }

    /** Volumes are deliberately ordered: fill all solids before carving air. */
    public List<BuildVolume> buildVolumes(GenerationStyle style) {
        return buildVolumes(style, java.util.Set.of());
    }

    /**
     * Builds procedural rooms while reserving selected normal-room slots for
     * their later tick-spread schematic operations.
     */
    public List<BuildVolume> buildVolumes(GenerationStyle style, java.util.Set<String> prefabRoomIds) {
        return buildVolumes(style, prefabRoomIds, java.util.Set.of());
    }

    /**
     * Schematic corridors still receive the generic air carve, so procedural
     * room shells open at their doorway, but their platform and safety lips are
     * replaced by the schematic block operations supplied afterwards.
     */
    public List<BuildVolume> buildVolumes(GenerationStyle style, java.util.Set<String> prefabRoomIds,
                                          java.util.Set<String> schematicTunnelIds) {
        List<BuildVolume> solids = new ArrayList<>();
        List<BuildVolume> air = new ArrayList<>();
        List<BuildVolume> markers = new ArrayList<>();

        for (Room room : rooms) {
            if (prefabRoomIds.contains(room.id())) continue;
            solids.add(new BuildVolume(room.bounds(), style.material(room.type())));
        }
        for (Tunnel tunnel : tunnels) {
            if (schematicTunnelIds.contains(tunnel.id())) continue;
            for (Bounds floor : tunnel.floors()) solids.add(new BuildVolume(floor, style.corridorFloorMaterial()));
            for (Bounds lip : tunnel.lips()) solids.add(new BuildVolume(lip, style.corridorLipMaterial()));
        }

        for (Room room : rooms) {
            if (prefabRoomIds.contains(room.id())) continue;
            Bounds box = room.bounds();
            air.add(new BuildVolume(new Bounds(box.minX() + 1, box.minY() + 1, box.minZ() + 1,
                    box.maxX() - 1, box.maxY() - 1, box.maxZ() - 1), style.air()));
        }
        for (Tunnel tunnel : tunnels) {
            for (Bounds hollow : tunnel.air()) {
                air.add(new BuildVolume(hollow, style.air()));
            }
        }

        for (Room room : rooms) {
            if (prefabRoomIds.contains(room.id())) continue;
            for (DungeonMarker marker : room.markers()) {
                markers.add(new BuildVolume(new Bounds(marker.x(), marker.y(), marker.z(), marker.x(), marker.y(), marker.z()),
                        style.marker(marker.category())));
            }
        }

        solids.addAll(air);
        solids.addAll(markers);
        return List.copyOf(solids);
    }

    public enum RoomType {
        SPAWN,
        NORMAL,
        BRANCH,
        BOSS
    }

    public enum RoomVariant { PLAIN, PARKOUR }

    /**
     * The mandatory detour of a composed difficulty: the named tunnel stays
     * sealed until the guardian room's guardian dies. Both values are stable
     * identifiers, so translation never touches them.
     */
    public record KeyGate(String lockedTunnelId, String guardianRoomId) { }

    /** {@code role} names a composed room-role recipe, or null outside compositions. */
    public record Room(String id, RoomType type, Bounds bounds, int depth, RoomVariant variant, String role,
                       List<DungeonMarker> markers) {
        public Room(String id, RoomType type, Bounds bounds, int depth, RoomVariant variant) {
            this(id, type, bounds, depth, variant, null, List.of());
        }

        public Room(String id, RoomType type, Bounds bounds, int depth, RoomVariant variant, List<DungeonMarker> markers) {
            this(id, type, bounds, depth, variant, null, markers);
        }

        public Room {
            markers = List.copyOf(markers);
        }

        public Room translate(int x, int y, int z) {
            return new Room(id, type, bounds.translate(x, y, z), depth, variant, role,
                    markers.stream().map(marker -> new DungeonMarker(marker.category(), marker.x() + x, marker.y() + y, marker.z() + z)).toList());
        }
    }

    /** A flat corridor platform with optional safety lips and its walkable volume. */
    public record Tunnel(String firstRoomId, String secondRoomId, Bounds firstDoorway, Bounds secondDoorway,
                         List<Bounds> floors, List<Bounds> lips, List<Bounds> air) {
        public Tunnel {
            floors = List.copyOf(floors);
            lips = List.copyOf(lips);
            air = List.copyOf(air);
        }

        public Tunnel translate(int x, int y, int z) {
            return new Tunnel(firstRoomId, secondRoomId, firstDoorway.translate(x, y, z), secondDoorway.translate(x, y, z),
                    floors.stream().map(box -> box.translate(x, y, z)).toList(),
                    lips.stream().map(box -> box.translate(x, y, z)).toList(),
                    air.stream().map(box -> box.translate(x, y, z)).toList());
        }

        public String id() {
            return firstRoomId + "-" + secondRoomId;
        }

        /** All volume reserved by this corridor for layout collision checks. */
        public List<Bounds> occupied() {
            List<Bounds> result = new ArrayList<>(floors.size() + lips.size() + air.size());
            result.addAll(floors);
            result.addAll(lips);
            result.addAll(air);
            return List.copyOf(result);
        }
    }
}
