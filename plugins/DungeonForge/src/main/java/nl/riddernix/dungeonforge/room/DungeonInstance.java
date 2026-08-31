package nl.riddernix.dungeonforge.room;

import nl.riddernix.dungeonforge.generation.Bounds;
import nl.riddernix.dungeonforge.generation.DungeonLayout;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Runtime-only registry entry for a generated party dungeon. */
public final class DungeonInstance {

    private final String id = UUID.randomUUID().toString();
    private final World world;
    private final int difficulty;
    private final long seed;
    private final Map<String, DungeonRoom> rooms;
    private final List<DungeonLayout.Tunnel> tunnels;
    private final List<DungeonCorridor> corridors;
    private final Map<String, DungeonSpecialMarker> playerSpawns;
    private final Map<String, DungeonSpecialMarker> bossSpawns;
    private final Map<String, DungeonTrap> traps;
    private final Map<String, String> prefabFiles;
    private final DungeonLayout.KeyGate keyGate;
    private boolean completed;
    private boolean keyObtained;
    private int mobKillCount;
    /**
     * How many players the mob numbers are balanced for, fixed when the
     * dungeon registers.
     *
     * <p>Deliberately locked rather than read live. Rooms populate ahead of
     * the party, so a live reading would mean each room was balanced for
     * whoever happened to be in the party when that room was built - invisible
     * to players, and open to having someone log out before the boss room
     * fills. One number for the whole run is the thing a party can reason
     * about.</p>
     */
    private int partySize = 1;

    public DungeonInstance(World world, DungeonLayout layout, Map<String, List<DungeonMarker>> scannedMarkers,
                           Map<String, List<DungeonDoorway>> prefabDoorways) {
        this(world, layout, scannedMarkers, prefabDoorways, Map.of());
    }

    public DungeonInstance(World world, DungeonLayout layout, Map<String, List<DungeonMarker>> scannedMarkers,
                           Map<String, List<DungeonDoorway>> prefabDoorways, Map<String, Bounds> playableBounds) {
        this(world, layout, scannedMarkers, prefabDoorways, playableBounds, Map.of(), Map.of(), Map.of(), Map.of());
    }

    public DungeonInstance(World world, DungeonLayout layout, Map<String, List<DungeonMarker>> scannedMarkers,
                           Map<String, List<DungeonDoorway>> prefabDoorways, Map<String, Bounds> playableBounds,
                           Map<String, DungeonSpecialMarker> playerSpawns, Map<String, DungeonSpecialMarker> bossSpawns,
                           Map<String, DungeonTrap> traps, Map<String, String> prefabFiles) {
        this.world = world;
        this.difficulty = layout.difficulty();
        this.seed = layout.seed();
        Map<String, DungeonRoom> indexedRooms = new LinkedHashMap<>();
        for (DungeonLayout.Room room : layout.rooms()) {
            List<DungeonMarker> markers = scannedMarkers.containsKey(room.id()) ? scannedMarkers.get(room.id()) : room.markers();
            List<DungeonDoorway> doorways = prefabDoorways.getOrDefault(room.id(), List.of());
            Bounds bounds = playableBounds.getOrDefault(room.id(), room.bounds());
            indexedRooms.put(room.id(), new DungeonRoom(room.id(), room.type(), bounds, room.bounds().minY() + 1,
                    room.depth(), room.variant(), room.role(), markers, doorways, id));
        }
        this.rooms = Map.copyOf(indexedRooms);
        this.tunnels = List.copyOf(layout.tunnels());
        this.corridors = layout.tunnels().stream().map(tunnel -> new DungeonCorridor(
                tunnel.firstRoomId() + "-" + tunnel.secondRoomId(), tunnel.firstRoomId(), tunnel.secondRoomId(), tunnel.air())).toList();
        this.playerSpawns = Map.copyOf(playerSpawns);
        this.bossSpawns = Map.copyOf(bossSpawns);
        this.traps = Map.copyOf(traps);
        this.prefabFiles = Map.copyOf(prefabFiles);
        this.keyGate = layout.keyGate();
    }

    public String id() {
        return id;
    }

    public World world() {
        return world;
    }

    public int difficulty() {
        return difficulty;
    }
    public long seed() { return seed; }

    /** The party size every mob in this run is scaled for; never below 1. */
    public int partySize() {
        return partySize;
    }

    /** Called once at registration. Later joins and leaves do not move it. */
    public void lockPartySize(int size) {
        this.partySize = Math.max(1, size);
    }

    public List<DungeonRoom> rooms() {
        return List.copyOf(rooms.values());
    }

    public DungeonRoom room(String roomId) {
        return rooms.get(roomId);
    }

    public List<DungeonLayout.Tunnel> tunnels() {
        return tunnels;
    }

    public List<DungeonCorridor> corridors() { return corridors; }

    /** The consumed marker block itself becomes the player's feet location. */
    public Optional<Location> playerSpawnLocation() {
        DungeonRoom spawnRoom = rooms.values().stream().filter(room -> room.type() == DungeonLayout.RoomType.SPAWN).findFirst().orElse(null);
        if (spawnRoom == null) return Optional.empty();
        DungeonSpecialMarker marker = playerSpawns.get(spawnRoom.id());
        if (marker == null) return Optional.empty();
        clearMarker(marker);
        return Optional.of(new Location(world, marker.x() + 0.5, marker.y(), marker.z() + 0.5, spawnYaw(spawnRoom), 0.0F));
    }

    /** The consumed marker block itself becomes the boss's feet location. */
    public Optional<Location> bossSpawnLocation() {
        DungeonRoom bossRoom = rooms.values().stream().filter(room -> room.type() == DungeonLayout.RoomType.BOSS).findFirst().orElse(null);
        if (bossRoom == null) return Optional.empty();
        DungeonSpecialMarker marker = bossSpawns.get(bossRoom.id());
        if (marker == null) return Optional.empty();
        clearMarker(marker);
        return Optional.of(new Location(world, marker.x() + 0.5, marker.y(), marker.z() + 0.5));
    }

    /** Idempotent safety guard: marker removal always precedes a teleport or spawn. */
    private void clearMarker(DungeonSpecialMarker marker) {
        world.getBlockAt(marker.x(), marker.y(), marker.z()).setType(org.bukkit.Material.AIR, false);
    }

    private static float spawnYaw(DungeonRoom room) {
        BlockFace exit = room.doorways().stream().map(DungeonDoorway::facing).findFirst().orElse(BlockFace.SOUTH);
        return switch (exit) {
            case NORTH -> 180.0F;
            case EAST -> -90.0F;
            case WEST -> 90.0F;
            default -> 0.0F;
        };
    }

    /** The schematic a room was built from, or null when it fell back to procedural stone. */
    public String prefabFile(String roomId) {
        return prefabFiles.get(roomId);
    }

    /** Authored trap floors by room id, already in world coordinates. */
    public Map<String, DungeonTrap> traps() {
        return traps;
    }

    /** The sealed-corridor contract of a composed layout, or null without one. */
    public DungeonLayout.KeyGate keyGate() {
        return keyGate;
    }

    /** Grants the party's key once; returns false when it was already held. */
    public boolean obtainKey() {
        if (keyObtained) return false;
        keyObtained = true;
        return true;
    }

    public boolean isKeyObtained() {
        return keyObtained;
    }

    /** Marks this disposable instance complete once; returns false on duplicates. */
    public boolean complete() {
        if (completed) return false;
        completed = true;
        return true;
    }

    public boolean isCompleted() { return completed; }

    public void recordMobKill() { mobKillCount++; }

    public int mobKillCount() { return mobKillCount; }
}
