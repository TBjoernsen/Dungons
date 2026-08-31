package nl.riddernix.dungeonforge.room;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.generation.Bounds;
import nl.riddernix.dungeonforge.generation.DungeonLayout;
import nl.riddernix.dungeonforge.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Owns live dungeon room metadata and periodic player-to-room detection. */
public final class DungeonRoomRegistry {

    private final DungeonForgePlugin plugin;
    private final Map<String, DungeonInstance> byWorld = new HashMap<>();
    private final Map<UUID, PlayerArea> playerAreas = new HashMap<>();

    public DungeonRoomRegistry(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    public DungeonInstance register(World world, DungeonLayout layout, Map<String, List<DungeonMarker>> markers) {
        return register(world, layout, markers, Map.of(), Map.of());
    }

    public DungeonInstance register(World world, DungeonLayout layout, Map<String, List<DungeonMarker>> markers,
                                    Map<String, List<DungeonDoorway>> doorways) {
        return register(world, layout, markers, doorways, Map.of());
    }

    public DungeonInstance register(World world, DungeonLayout layout, Map<String, List<DungeonMarker>> markers,
                                    Map<String, List<DungeonDoorway>> doorways, Map<String, Bounds> playableBounds) {
        return register(world, layout, markers, doorways, playableBounds, Map.of(), Map.of(), Map.of(), Map.of());
    }

    public DungeonInstance register(World world, DungeonLayout layout, Map<String, List<DungeonMarker>> markers,
                                    Map<String, List<DungeonDoorway>> doorways, Map<String, Bounds> playableBounds,
                                    Map<String, DungeonSpecialMarker> playerSpawns,
                                    Map<String, DungeonSpecialMarker> bossSpawns,
                                    Map<String, DungeonTrap> traps, Map<String, String> prefabFiles) {
        DungeonInstance instance = new DungeonInstance(world, layout, markers, doorways, playableBounds,
                playerSpawns, bossSpawns, traps, prefabFiles);
        // Fixed here because this runs after the world is built and before any
        // room is populated: the one moment the party is known and no mob has
        // been scaled yet. A solo run has no party, which is the 1 default.
        plugin.parties().partyForWorld(world.getName())
                .ifPresent(party -> instance.lockPartySize(party.members().size()));
        byWorld.put(world.getName(), instance);
        // The world is fully built by the time an instance registers, so the
        // key gate's barrier and the trap snapshots can go straight in.
        plugin.doors().install(instance);
        plugin.traps().install(instance);
        return instance;
    }

    public Optional<DungeonInstance> dungeon(World world) {
        return Optional.ofNullable(world == null ? null : byWorld.get(world.getName()));
    }

    public Optional<DungeonInstance> dungeon(String worldName) {
        return Optional.ofNullable(byWorld.get(worldName));
    }

    public Optional<DungeonRoom> room(Player player) {
        PlayerArea current = playerAreas.get(player.getUniqueId());
        if (current == null || current.roomId() == null) {
            return Optional.empty();
        }
        return dungeon(current.worldName()).map(instance -> instance.room(current.roomId()));
    }

    /** The schematic a room was built from, so a wrong room names its own file. */
    private net.kyori.adventure.text.Component prefabTag(Player player, String prefab) {
        return net.kyori.adventure.text.Component.text(prefab == null ? "procedural" : prefab);
    }

    public void remove(String worldName) {
        byWorld.remove(worldName);
        playerAreas.entrySet().removeIf(entry -> entry.getValue().worldName().equals(worldName));
    }

    public void forget(Player player) {
        playerAreas.remove(player.getUniqueId());
    }

    /** Checks every online player; called by a configurable repeating task. */
    public void scanPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    /** Immediately checks a player after a teleport or rejoin. */
    public void refresh(Player player) {
        DungeonInstance instance = byWorld.get(player.getWorld().getName());
        PlayerArea previous = playerAreas.get(player.getUniqueId());
        DungeonRoom next = instance == null ? null : findRoom(instance, player);
        DungeonCorridor nextCorridor = next == null && instance != null ? findCorridor(instance, player) : null;
        if (previous != null && previous.roomId() != null && (instance == null || !previous.worldName().equals(instance.world().getName())
                || next == null || !previous.roomId().equals(next.id()))) {
            DungeonInstance oldDungeon = byWorld.get(previous.worldName());
            if (oldDungeon != null) {
                DungeonRoom oldRoom = oldDungeon.room(previous.roomId());
                if (oldRoom != null) {
                    Bukkit.getPluginManager().callEvent(new DungeonRoomLeaveEvent(player, oldDungeon, oldRoom));
                }
            }
        }
        boolean changedDungeon = previous == null || instance == null || !previous.worldName().equals(instance.world().getName());
        if (instance != null && nextCorridor != null && (changedDungeon || !nextCorridor.id().equals(previous.corridorId()))) {
            String fromId = previous == null ? null : previous.lastRoomId();
            DungeonRoom comingFrom = fromId == null ? null : instance.room(fromId);
            Bukkit.getPluginManager().callEvent(new DungeonCorridorEnterEvent(player, instance, nextCorridor, comingFrom));
        }
        if (instance != null && next != null && (changedDungeon || !next.id().equals(previous.roomId()))) {
            // The API event goes first, while "has anyone been here" is still
            // answerable: the internal event below is what spawns the mobs.
            plugin.events().fireRoomEnter(plugin.snapshots().of(instance), player, plugin.snapshots().of(next),
                    !plugin.mobs().isRoomVisited(instance.id(), next.id()));
            Bukkit.getPluginManager().callEvent(new DungeonRoomEnterEvent(player, instance, next));
            // After the internal event: the mob manager has queued this room's
            // spawns by then, so the gate sees a live count to decide on.
            plugin.gates().onRoomEntered(instance, next, player);
            // A composed room reads as its role, because "branch" says nothing
            // about whether you walked into the parkour run or the guardian's
            // lair - and the role is what the layout actually promised.
            String type = next.type().name().toLowerCase(java.util.Locale.ROOT);
            String prefab = instance.prefabFile(next.id());
            plugin.messages().send(player, "room-entered", Messages.ph("id", next.id()),
                    Messages.ph("type", next.role() == null ? type : type + "/" + next.role()),
                    Messages.ph("role", next.role() == null ? "none" : next.role()),
                    Messages.ph("prefab", prefabTag(player, prefab)),
                    Messages.ph("depth", next.depth()));
        }
        if (instance == null) {
            playerAreas.remove(player.getUniqueId());
        } else {
            String lastRoom = next != null ? next.id() : previous == null ? null : previous.lastRoomId();
            playerAreas.put(player.getUniqueId(), new PlayerArea(instance.world().getName(),
                    next == null ? null : next.id(), nextCorridor == null ? null : nextCorridor.id(), lastRoom));
        }
    }

    private static DungeonRoom findRoom(DungeonInstance instance, Player player) {
        int x = player.getLocation().getBlockX();
        int y = player.getLocation().getBlockY();
        int z = player.getLocation().getBlockZ();
        List<DungeonRoom> matches = instance.rooms().stream()
                .filter(room -> room.bounds().contains(x, y, z))
                .sorted(java.util.Comparator.comparingInt(DungeonRoom::depth).reversed())
                .toList();
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static DungeonCorridor findCorridor(DungeonInstance instance, Player player) {
        int x = player.getLocation().getBlockX();
        int y = player.getLocation().getBlockY();
        int z = player.getLocation().getBlockZ();
        return instance.corridors().stream().filter(corridor -> corridor.contains(x, y, z)).findFirst().orElse(null);
    }

    private record PlayerArea(String worldName, String roomId, String corridorId, String lastRoomId) {
    }
}
