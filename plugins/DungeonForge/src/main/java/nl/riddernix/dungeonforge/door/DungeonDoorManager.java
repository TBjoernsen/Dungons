package nl.riddernix.dungeonforge.door;

import net.kyori.adventure.text.minimessage.MiniMessage;
import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.generation.Bounds;
import nl.riddernix.dungeonforge.generation.DungeonLayout;
import nl.riddernix.dungeonforge.room.DungeonInstance;
import nl.riddernix.dungeonforge.room.DungeonRoom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds and owns the sealed corridor of composed dungeons.
 *
 * <p>The barrier fills every passable block of the locked corridor's mouth,
 * two planes deep, so an authored corridor tile is sealed whatever the shape
 * of its passage - walls are never replaced. The key is state on the dungeon
 * instance, granted the moment the key guardian dies, so it survives player
 * death, logout and party changes by construction.</p>
 *
 * <p>Because the door blocks the critical path rather than a side room, a
 * vanished guardian would strand the whole run. The watchdog therefore
 * revives a guardian that stopped existing without granting the key, and
 * after {@code door.watchdog.max-revivals} failed revivals it opens the door
 * by itself and says so in the log.</p>
 */
public final class DungeonDoorManager implements Listener {

    private final DungeonForgePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    /** One door per dungeon world; worlds are disposable and never share runs. */
    private final Map<String, DoorState> doors = new HashMap<>();

    public DungeonDoorManager(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Seals the key gate of a freshly registered dungeon, if it declares one. */
    public void install(DungeonInstance dungeon) {
        DungeonLayout.KeyGate gate = dungeon.keyGate();
        if (gate == null) {
            doors.remove(dungeon.world().getName());
            return;
        }
        DungeonLayout.Tunnel tunnel = dungeon.tunnels().stream()
                .filter(candidate -> candidate.id().equals(gate.lockedTunnelId())).findFirst().orElse(null);
        if (tunnel == null) {
            plugin.getLogger().severe("Dungeon " + dungeon.id() + " declares locked corridor " + gate.lockedTunnelId()
                    + " but no such corridor exists; the door was not built.");
            return;
        }

        World world = dungeon.world();
        Bounds doorway = tunnel.firstDoorway();
        boolean axisX = doorway.minX() == doorway.maxX();
        int step = axisX ? Integer.signum(tunnel.secondDoorway().minX() - doorway.minX())
                : Integer.signum(tunnel.secondDoorway().minZ() - doorway.minZ());
        Material material = barrierMaterial();

        // The doorway bounds are the planned opening; the cross-section is
        // widened a little so a hand-built opening that is slightly larger is
        // still sealed. Only passable blocks are filled, so authored walls
        // and ceilings are left exactly as built.
        Set<BlockVector> blocks = new HashSet<>();
        for (int plane = 0; plane <= 1; plane++) {
            if (axisX) {
                int x = doorway.minX() + plane * step;
                for (int y = doorway.minY(); y <= doorway.maxY() + 2; y++) {
                    for (int z = doorway.minZ() - 2; z <= doorway.maxZ() + 2; z++) {
                        seal(world, x, y, z, material, blocks);
                    }
                }
            } else {
                int z = doorway.minZ() + plane * step;
                for (int y = doorway.minY(); y <= doorway.maxY() + 2; y++) {
                    for (int x = doorway.minX() - 2; x <= doorway.maxX() + 2; x++) {
                        seal(world, x, y, z, material, blocks);
                    }
                }
            }
        }
        if (blocks.isEmpty()) {
            plugin.getLogger().severe("Locked corridor " + gate.lockedTunnelId() + " of dungeon " + dungeon.id()
                    + " had no passable blocks to seal; the door is effectively open.");
        }

        double centreY = (doorway.minY() + doorway.maxY()) / 2.0 + 0.5;
        Location centre = axisX
                ? new Location(world, doorway.minX() + 0.5, centreY, doorway.centreZ() + 0.5)
                : new Location(world, doorway.centreX() + 0.5, centreY, doorway.minZ() + 0.5);
        UUID displayId = spawnLabel(world, centre, axisX, step);
        doors.put(world.getName(), new DoorState(dungeon.id(), world.getName(), gate.guardianRoomId(),
                blocks, displayId, centre, material));
    }

    private static void seal(World world, int x, int y, int z, Material material, Set<BlockVector> blocks) {
        Block block = world.getBlockAt(x, y, z);
        if (!block.isPassable()) return;
        // Physics on, so a connecting block such as iron bars joins up into
        // one gate instead of standing as loose posts.
        block.setType(material, true);
        blocks.add(new BlockVector(x, y, z));
    }

    private UUID spawnLabel(World world, Location centre, boolean axisX, int step) {
        String text = plugin.getConfig().getString("door.label.text", "");
        if (text == null || text.isBlank()) return null;
        double offset = plugin.getConfig().getDouble("door.label.offset", 0.6);
        int brightness = Math.clamp(plugin.getConfig().getInt("door.label.brightness", 15), 0, 15);
        float scale = (float) plugin.getConfig().getDouble("door.label.scale", 1.1);
        Location location = centre.clone().add(axisX ? -step * offset : 0.0, 0.0, axisX ? 0.0 : -step * offset);
        // The label faces back down the corridor mouth, towards the room the
        // approaching party arrives from.
        float yaw = axisX ? (step > 0 ? 90.0F : -90.0F) : (step > 0 ? 180.0F : 0.0F);
        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            entity.text(miniMessage.deserialize(text));
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setRotation(yaw, 0.0F);
            entity.setPersistent(false);
            entity.setBrightness(new Display.Brightness(brightness, brightness));
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });
        return display.getUniqueId();
    }

    /** The key moment: grant, announce, and open - in that order. */
    public void onGuardianDeath(DungeonInstance dungeon, Location guardianLocation) {
        if (!dungeon.obtainKey()) return;
        DungeonLayout.KeyGate gate = dungeon.keyGate();
        plugin.events().fireKeyObtained(plugin.snapshots().of(dungeon), gate == null ? "" : gate.guardianRoomId());
        for (Player player : dungeon.world().getPlayers()) {
            plugin.messages().send(player, "door-key-obtained");
        }
        playKeyVisual(dungeon.world(), guardianLocation);
        DoorState state = doors.get(dungeon.world().getName());
        if (state != null && state.dungeonId.equals(dungeon.id())) {
            open(dungeon, state, false);
        }
    }

    public enum ForceResult { OPENED, ALREADY_OPEN, NO_DOOR }

    /** Admin or watchdog escape hatch: opens without the guardian's key. */
    public ForceResult forceOpen(DungeonInstance dungeon) {
        DoorState state = doors.get(dungeon.world().getName());
        if (state == null || !state.dungeonId.equals(dungeon.id())) return ForceResult.NO_DOOR;
        if (state.open) return ForceResult.ALREADY_OPEN;
        open(dungeon, state, true);
        return ForceResult.OPENED;
    }

    private void open(DungeonInstance dungeon, DoorState state, boolean forced) {
        if (state.open) return;
        state.open = true;
        World world = Bukkit.getWorld(state.worldName);
        if (world != null) {
            for (BlockVector position : state.blocks) {
                Block block = world.getBlockAt(position.getBlockX(), position.getBlockY(), position.getBlockZ());
                if (block.getType() != state.material) continue;
                world.spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 6,
                        0.3, 0.3, 0.3, block.getBlockData());
                block.setType(Material.AIR, true);
            }
            if (state.displayId != null) {
                Entity display = Bukkit.getEntity(state.displayId);
                if (display != null) display.remove();
            }
            Sound sound = sound(plugin.getConfig().getString("door.sounds.open", "BLOCK_BEACON_ACTIVATE"));
            if (sound != null) world.playSound(state.centre, sound, 1.0F, 1.0F);
        }
        plugin.events().fireDoorOpened(plugin.snapshots().of(dungeon), forced);
    }

    /** A small scripted moment: the key rises out of the fallen guardian. */
    private void playKeyVisual(World world, Location guardianLocation) {
        if (guardianLocation == null) return;
        Location start = guardianLocation.clone().add(0.0, 0.6, 0.0);
        ItemDisplay display = world.spawn(start, ItemDisplay.class, entity -> {
            entity.setItemStack(new ItemStack(Material.TRIAL_KEY));
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setPersistent(false);
            entity.setBrightness(new Display.Brightness(15, 15));
        });
        Sound chime = sound("ENTITY_PLAYER_LEVELUP");
        if (chime != null) world.playSound(start, chime, 0.8F, 1.4F);
        // One interpolated rise set a tick after spawn, so the client lerps
        // from the spawn transform instead of snapping.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!display.isValid()) return;
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(40);
            display.setTransformation(new Transformation(new Vector3f(0.0F, 2.2F, 0.0F), new AxisAngle4f(),
                    new Vector3f(1.0F, 1.0F, 1.0F), new AxisAngle4f()));
        });
        Bukkit.getScheduler().runTaskLater(plugin, display::remove, 55L);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) return;
        DoorState state = closedDoorAt(block);
        if (state == null) return;
        event.setCancelled(true);
        deny(event.getPlayer(), state);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        DoorState state = closedDoorAt(event.getBlock());
        if (state == null) return;
        event.setCancelled(true);
        deny(event.getPlayer(), state);
    }

    private DoorState closedDoorAt(Block block) {
        DoorState state = doors.get(block.getWorld().getName());
        if (state == null || state.open) return null;
        return state.blocks.contains(new BlockVector(block.getX(), block.getY(), block.getZ())) ? state : null;
    }

    /** A player bumping the door twice per swing still reads one calm line. */
    private void deny(Player player, DoorState state) {
        long tick = Bukkit.getCurrentTick();
        long interval = Math.max(1L, plugin.getConfig().getLong("door.deny-message-interval-ticks", 60L));
        Long last = state.lastDeny.get(player.getUniqueId());
        if (last != null && tick - last < interval) return;
        state.lastDeny.put(player.getUniqueId(), tick);
        plugin.messages().send(player, "door-locked");
        Sound sound = sound(plugin.getConfig().getString("door.sounds.deny", "BLOCK_CHEST_LOCKED"));
        if (sound != null) player.playSound(player.getLocation(), sound, 0.8F, 0.9F);
    }

    /**
     * Runs on the configured interval. Only ever acts on a woken guardian
     * room that reports nothing alive while the key was never granted -
     * exactly the state that would strand the run.
     */
    public void watchdog() {
        for (Map.Entry<String, DoorState> entry : Map.copyOf(doors).entrySet()) {
            DoorState state = entry.getValue();
            DungeonInstance dungeon = plugin.rooms().dungeon(entry.getKey()).orElse(null);
            if (dungeon == null || !dungeon.id().equals(state.dungeonId)) {
                // The world is gone or reused; its entities went with it.
                doors.remove(entry.getKey(), state);
                continue;
            }
            if (state.open || dungeon.isKeyObtained() || dungeon.isCompleted()) continue;
            if (!plugin.mobs().isRoomVisited(state.dungeonId, state.guardianRoomId)) continue;
            if (plugin.mobs().livingCount(state.dungeonId, state.guardianRoomId) > 0) {
                state.quietChecks = 0;
                continue;
            }
            // Two consecutive quiet checks, so one glance mid-respawn or
            // mid-recount can never trigger a revival.
            if (++state.quietChecks < 2) continue;
            state.quietChecks = 0;
            int maxRevivals = Math.max(0, plugin.getConfig().getInt("door.watchdog.max-revivals", 2));
            DungeonRoom room = dungeon.room(state.guardianRoomId);
            if (room != null && state.revivals < maxRevivals) {
                state.revivals++;
                plugin.getLogger().warning("Key guardian of dungeon " + dungeon.id() + " stopped existing without"
                        + " granting the key; reviving it (attempt " + state.revivals + " of " + maxRevivals + ").");
                plugin.mobs().reviveRoleRoom(dungeon, room);
                for (Player player : dungeon.world().getPlayers()) {
                    plugin.messages().send(player, "door-guardian-revived");
                }
                continue;
            }
            plugin.getLogger().severe("Key guardian of dungeon " + dungeon.id() + " could not be revived; opening"
                    + " the sealed door so the run can continue.");
            for (Player player : dungeon.world().getPlayers()) {
                plugin.messages().send(player, "door-guardian-lost");
            }
            open(dungeon, state, true);
        }
    }

    private Material barrierMaterial() {
        String raw = plugin.getConfig().getString("door.material", "IRON_BARS");
        Material material = raw == null ? null : Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        return material == null || !material.isBlock() || material.isAir() ? Material.IRON_BARS : material;
    }

    /** Package-visible: the room gates speak the same audio language. */
    static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return Registry.SOUNDS.get(NamespacedKey.minecraft(raw.trim().toLowerCase(Locale.ROOT).replace('_', '.')));
    }

    private static final class DoorState {
        private final String dungeonId;
        private final String worldName;
        private final String guardianRoomId;
        private final Set<BlockVector> blocks;
        private final UUID displayId;
        private final Location centre;
        private final Material material;
        private final Map<UUID, Long> lastDeny = new HashMap<>();
        private boolean open;
        private int revivals;
        private int quietChecks;

        private DoorState(String dungeonId, String worldName, String guardianRoomId, Set<BlockVector> blocks,
                          UUID displayId, Location centre, Material material) {
            this.dungeonId = dungeonId;
            this.worldName = worldName;
            this.guardianRoomId = guardianRoomId;
            this.blocks = Set.copyOf(blocks);
            this.displayId = displayId;
            this.centre = centre;
            this.material = material;
        }
    }
}
