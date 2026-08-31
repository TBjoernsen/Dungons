package nl.riddernix.dungeonforge.trap;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.room.DungeonInstance;
import nl.riddernix.dungeonforge.room.DungeonRoom;
import nl.riddernix.dungeonforge.room.DungeonTrap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Authored pressure-plate trap floors (§config {@code trap}).
 *
 * <p>Everything a trap needs is captured when its dungeon registers: the
 * placed blocks of every marked column are snapshotted from the world, so
 * restoring the floor is an exact rebuild of whatever the schematic put
 * there, plates included. The drop itself kills deliberately - this is a
 * void world, and a player who falls out of it is a player the plugin has
 * lost control over - and the hole it leaves does run through to the void,
 * so anything that jumps in afterwards still dies a normal void death.</p>
 *
 * <p>One trap has one timer: firing moves it out of the armed phase until the
 * floor has been rebuilt, so a second player on the plate can neither re-fire
 * it nor stretch the countdown.</p>
 */
public final class DungeonTrapManager implements Listener {

    /** How far above a column's floor the deliberate kill reaches, in blocks. */
    private static final int KILL_HEIGHT = 4;

    private final DungeonForgePlugin plugin;
    private final Map<String, List<TrapState>> byWorld = new HashMap<>();

    public DungeonTrapManager(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Snapshots every authored trap of a freshly registered dungeon. */
    public void install(DungeonInstance dungeon) {
        String worldName = dungeon.world().getName();
        byWorld.remove(worldName);
        if (dungeon.traps().isEmpty()) return;
        List<TrapState> states = new ArrayList<>();
        for (DungeonTrap trap : dungeon.traps().values()) {
            DungeonRoom room = dungeon.room(trap.roomId());
            if (room == null || trap.columns().isEmpty()) continue;
            int bottom = room.bounds().minY();
            int maximumRise = Math.max(0, plugin.getConfig().getInt("trap.max-column-height", 8));
            List<BlockSnapshot> snapshot = new ArrayList<>();
            for (DungeonTrap.Column column : trap.columns()) {
                // Whatever stands on the marked floor comes down with it: the
                // column climbs through anything that is not air and stops at
                // the first gap. A plate on the floor is caught by the same
                // rule rather than by a special case.
                int rise = DungeonTrap.rise(
                        y -> !dungeon.world().getBlockAt(column.x(), y, column.z()).getType().isAir(),
                        column.topY(), maximumRise);
                for (int y = column.topY() + rise; y >= bottom; y--) {
                    Block block = dungeon.world().getBlockAt(column.x(), y, column.z());
                    snapshot.add(new BlockSnapshot(column.x(), y, column.z(), block.getBlockData()));
                }
            }
            states.add(new TrapState(dungeon.id(), trap, bottom, snapshot));
        }
        if (!states.isEmpty()) byWorld.put(worldName, states);
    }

    @EventHandler
    public void onPlatePress(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null) return;
        trigger(event.getClickedBlock());
    }

    /** Mobs and other non-players stepping on a plate arrive through this event. */
    @EventHandler
    public void onEntityPress(EntityInteractEvent event) {
        trigger(event.getBlock());
    }

    private void trigger(Block block) {
        List<TrapState> states = byWorld.get(block.getWorld().getName());
        if (states == null) return;
        BlockVector position = new BlockVector(block.getX(), block.getY(), block.getZ());
        for (TrapState state : states) {
            if (state.phase != Phase.ARMED || !state.trap.plates().contains(position)) continue;
            fire(state, block.getWorld());
            return;
        }
    }

    private void fire(TrapState state, World world) {
        state.phase = Phase.TRIGGERED;
        Location centre = state.centre(world);
        Sound click = sound(plugin.getConfig().getString("trap.sounds.trigger", "BLOCK_DISPENSER_FAIL"));
        if (click != null) world.playSound(centre, click, 1.0F, 0.7F);
        // Two timers from the same moment: the floor goes almost at once, the
        // kill lands later so the victim gets a fall before it.
        long dropDelay = seconds("trap.drop-delay-seconds", 0.4);
        long killDelay = seconds("trap.kill-delay-seconds", 3.0);
        Bukkit.getScheduler().runTaskLater(plugin, () -> drop(state, world), dropDelay);
        Bukkit.getScheduler().runTaskLater(plugin, () -> kill(state, world), killDelay);
    }

    private long seconds(String path, double fallback) {
        return Math.max(0L, Math.round(plugin.getConfig().getDouble(path, fallback) * 20.0));
    }

    /**
     * The deliberate death, covering both who stood on the floor when it went
     * and anyone who has wandered on since.
     *
     * <p>The first group is remembered rather than looked up, because by now
     * they are metres below the room and no longer anywhere near it. That is
     * the whole point of doing this by hand: in a void world a fall has no
     * ending of its own.</p>
     */
    private void kill(TrapState state, World world) {
        DungeonInstance dungeon = plugin.rooms().dungeon(world).orElse(null);
        if (dungeon == null || !dungeon.id().equals(state.dungeonId)) return;
        List<LivingEntity> victims = new ArrayList<>(livingOnColumns(state, world));
        for (java.util.UUID id : state.falling) {
            if (Bukkit.getEntity(id) instanceof LivingEntity living && !victims.contains(living)) {
                victims.add(living);
            }
        }
        state.falling.clear();
        for (LivingEntity victim : victims) {
            victim.damage(1_000_000.0);
        }
    }

    private void drop(TrapState state, World world) {
        DungeonInstance dungeon = plugin.rooms().dungeon(world).orElse(null);
        if (dungeon == null || !dungeon.id().equals(state.dungeonId)) return;
        state.phase = Phase.OPEN;
        long returnTicks = Math.max(20L, plugin.getConfig().getLong("trap.floor-return-seconds", 15L) * 20L);
        state.restoreAtTick = Bukkit.getCurrentTick() + returnTicks;

        // Noted while they are still standing on it: once the blocks are gone
        // they drop out of the room and could never be found by position.
        for (LivingEntity standing : livingOnColumns(state, world)) {
            state.falling.add(standing.getUniqueId());
        }
        for (BlockSnapshot snapshot : state.snapshot) {
            Block block = world.getBlockAt(snapshot.x(), snapshot.y(), snapshot.z());
            if (block.getType().isAir()) continue;
            if (snapshot.y() >= state.topY() - 1) {
                world.spawnParticle(Particle.BLOCK, snapshot.x() + 0.5, snapshot.y() + 0.5, snapshot.z() + 0.5,
                        8, 0.3, 0.3, 0.3, block.getBlockData());
            }
            block.setType(Material.AIR, false);
        }
        Sound collapse = sound(plugin.getConfig().getString("trap.sounds.collapse", "BLOCK_GRAVEL_BREAK"));
        if (collapse != null) world.playSound(state.centre(world), collapse, 1.2F, 0.8F);
    }

    /** Runs a few times per second; rebuilds any open floor whose time is up. */
    public void tick() {
        long now = Bukkit.getCurrentTick();
        for (Map.Entry<String, List<TrapState>> entry : Map.copyOf(byWorld).entrySet()) {
            DungeonInstance dungeon = plugin.rooms().dungeon(entry.getKey()).orElse(null);
            if (dungeon == null || entry.getValue().stream().noneMatch(state -> dungeon.id().equals(state.dungeonId))) {
                byWorld.remove(entry.getKey(), entry.getValue());
                continue;
            }
            for (TrapState state : entry.getValue()) {
                if (state.phase == Phase.OPEN && now >= state.restoreAtTick) {
                    restore(state, dungeon.world());
                }
            }
        }
    }

    private void restore(TrapState state, World world) {
        // Nothing may be entombed: whatever hovers inside the hole is lifted
        // onto the floor being rebuilt. Anything already below the room is
        // past saving and finishes its fall into the void.
        for (DungeonTrap.Column column : state.trap.columns()) {
            BoundingBox cell = new BoundingBox(column.x(), state.bottom, column.z(),
                    column.x() + 1.0, column.topY() + 2.0, column.z() + 1.0);
            for (Entity entity : world.getNearbyEntities(cell)) {
                if (!(entity instanceof LivingEntity) && !(entity instanceof Item)) continue;
                Location lifted = entity.getLocation();
                lifted.setY(column.topY() + 1.0);
                entity.teleport(lifted);
            }
        }
        for (BlockSnapshot snapshot : state.snapshot) {
            world.getBlockAt(snapshot.x(), snapshot.y(), snapshot.z()).setBlockData(snapshot.data(), false);
        }
        Sound rebuilt = sound(plugin.getConfig().getString("trap.sounds.restore", "BLOCK_STONE_PLACE"));
        if (rebuilt != null) world.playSound(state.centre(world), rebuilt, 1.0F, 0.9F);
        state.phase = Phase.ARMED;
    }

    private static List<LivingEntity> livingOnColumns(TrapState state, World world) {
        List<LivingEntity> victims = new ArrayList<>();
        for (DungeonTrap.Column column : state.trap.columns()) {
            BoundingBox cell = new BoundingBox(column.x(), column.topY() + 1.0, column.z(),
                    column.x() + 1.0, column.topY() + 1.0 + KILL_HEIGHT, column.z() + 1.0);
            for (Entity entity : world.getNearbyEntities(cell)) {
                if (entity instanceof LivingEntity living && !victims.contains(living)) victims.add(living);
            }
        }
        return victims;
    }

    private static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return Registry.SOUNDS.get(NamespacedKey.minecraft(raw.trim().toLowerCase(Locale.ROOT).replace('_', '.')));
    }

    private enum Phase { ARMED, TRIGGERED, OPEN }

    private record BlockSnapshot(int x, int y, int z, BlockData data) { }

    private static final class TrapState {
        private final String dungeonId;
        private final DungeonTrap trap;
        private final int bottom;
        private final List<BlockSnapshot> snapshot;
        private Phase phase = Phase.ARMED;
        private long restoreAtTick;
        /** Who went down with the floor and is still owed their death. */
        private final java.util.Set<java.util.UUID> falling = new java.util.HashSet<>();

        private TrapState(String dungeonId, DungeonTrap trap, int bottom, List<BlockSnapshot> snapshot) {
            this.dungeonId = dungeonId;
            this.trap = trap;
            this.bottom = bottom;
            this.snapshot = List.copyOf(snapshot);
        }

        private int topY() {
            return trap.columns().stream().mapToInt(DungeonTrap.Column::topY).max().orElse(bottom);
        }

        private Location centre(World world) {
            double x = trap.columns().stream().mapToInt(DungeonTrap.Column::x).average().orElse(0.0);
            double z = trap.columns().stream().mapToInt(DungeonTrap.Column::z).average().orElse(0.0);
            return new Location(world, x + 0.5, topY() + 1.0, z + 0.5);
        }
    }
}
