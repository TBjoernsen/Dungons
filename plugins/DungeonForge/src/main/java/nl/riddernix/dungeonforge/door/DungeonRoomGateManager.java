package nl.riddernix.dungeonforge.door;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.generation.Bounds;
import nl.riddernix.dungeonforge.generation.DungeonLayout;
import nl.riddernix.dungeonforge.room.DungeonInstance;
import nl.riddernix.dungeonforge.room.DungeonRoom;
import nl.riddernix.dungeonforge.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BlockVector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Seals a combat room's exits while its mobs are alive, and the boss arena's
 * entrance while the boss is.
 *
 * <p>Two rules shaped everything here. A room's <em>entrance</em> is never
 * sealed, so nobody is shut in with no way back - the exception is the boss
 * arena, whose entrance is its only doorway, and which therefore also opens
 * itself the moment no living player remains inside. And a room with nothing
 * alive in it never seals at all.</p>
 *
 * <p>Seals sit in the room's own wall: one plane at the doorway opening,
 * passable blocks only, so authored walls survive and the corridor's shape -
 * open platform or tunnel - is irrelevant. The key door in this package seals
 * a corridor mouth instead and predates platform corridors; this class
 * deliberately does not repeat that.</p>
 *
 * <p>Failsafes, because a stuck gate now blocks the main path: the mob
 * manager's recount fires missed clears into {@link #onRoomCleared}, a
 * configurable timeout opens a gate on its own, and {@code /dungeon room open}
 * is the admin override. A gate opened by timeout or command is latched and
 * never re-seals that run.</p>
 */
public final class DungeonRoomGateManager implements Listener {

    private final DungeonForgePlugin plugin;
    /** Gate state per world, then per room; dungeon worlds never share runs. */
    private final Map<String, Map<String, GateState>> gates = new HashMap<>();

    public DungeonRoomGateManager(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Entry points
    // ------------------------------------------------------------------

    /** Whether the mob manager should leave the boss to be spawned by the gate. */
    public boolean defersBossSpawn() {
        return enabled() && plugin.getConfig().getBoolean("room-gating.boss", true);
    }

    /** Called by the registry after the enter events, so spawns are already queued. */
    public void onRoomEntered(DungeonInstance dungeon, DungeonRoom room, Player player) {
        if (dungeon.isCompleted() || !gated(room)) return;
        if (room.type() == DungeonLayout.RoomType.BOSS) {
            armArena(dungeon, room);
            return;
        }
        Map<String, GateState> worldGates = gates.computeIfAbsent(dungeon.world().getName(), ignored -> new HashMap<>());
        if (worldGates.containsKey(room.id())) return;
        if (plugin.mobs().isRoomCleared(dungeon.id(), room.id())) return;
        int living = plugin.mobs().livingCount(dungeon.id(), room.id());
        // The quiet-room rule: nothing alive, nothing sealed - a rest room or
        // an emptied recipe must never trap anyone behind a fight that is not
        // there.
        if (living <= 0) return;

        Set<BlockVector> blocks = new HashSet<>();
        Location centre = null;
        for (DungeonLayout.Tunnel tunnel : dungeon.tunnels()) {
            DungeonRoom other = otherEnd(dungeon, room, tunnel);
            // Only ways onward are sealed; the way back to the parent stays
            // open by design.
            if (other == null || other.depth() <= room.depth()) continue;
            Location sealed = seal(dungeon, room, doorwayOf(room, tunnel), blocks);
            if (centre == null) centre = sealed;
        }
        if (blocks.isEmpty()) return;
        worldGates.put(room.id(), new GateState(dungeon.id(), room.id(), false, blocks, material(), centre));
        sound(dungeon.world(), centre, "seal", "BLOCK_IRON_DOOR_CLOSE");
        for (Player inside : playersInside(dungeon, room)) {
            plugin.messages().send(inside, "gate-sealed", Messages.ph("mobs", living));
        }
    }

    /** The clear moment, fired by the mob manager from kills and from the recount. */
    public void onRoomCleared(DungeonInstance dungeon, DungeonRoom room) {
        Map<String, GateState> worldGates = gates.get(dungeon.world().getName());
        GateState state = worldGates == null ? null : worldGates.get(room.id());
        if (state == null || !state.dungeonId.equals(dungeon.id()) || state.open) return;
        open(dungeon, state, "gate-opened", playersInside(dungeon, room));
        // Cleared rooms can never seal again, so the record has nothing left
        // to remember.
        worldGates.remove(room.id());
    }

    /** Drops the arena bars at the kill itself, before completion moves anyone. */
    public void onBossDeath(DungeonInstance dungeon, String roomId) {
        Map<String, GateState> worldGates = gates.get(dungeon.world().getName());
        GateState state = worldGates == null ? null : worldGates.get(roomId);
        if (state == null || !state.dungeonId.equals(dungeon.id()) || state.open) return;
        open(dungeon, state, null, List.of());
        worldGates.remove(roomId);
    }

    /** One action-bar line per kill inside a sealed room: how many stand left. */
    public void notifyKill(DungeonInstance dungeon, String roomId) {
        Map<String, GateState> worldGates = gates.get(dungeon.world().getName());
        GateState state = worldGates == null ? null : worldGates.get(roomId);
        if (state == null || state.open || state.boss) return;
        int living = plugin.mobs().livingCount(dungeon.id(), roomId);
        if (living <= 0) return;
        DungeonRoom room = dungeon.room(roomId);
        if (room == null) return;
        for (Player player : playersInside(dungeon, room)) {
            player.sendActionBar(plugin.messages().get("gate-remaining", Messages.ph("mobs", living)));
        }
    }

    public enum GateResult { OPENED, NONE }

    /** The admin override; a gate opened this way is latched for the run. */
    public GateResult forceOpen(DungeonInstance dungeon, DungeonRoom room) {
        Map<String, GateState> worldGates = gates.get(dungeon.world().getName());
        GateState state = worldGates == null ? null : worldGates.get(room.id());
        if (state == null || state.open) return GateResult.NONE;
        state.latched = true;
        open(dungeon, state, "gate-forced", dungeon.world().getPlayers());
        return GateResult.OPENED;
    }

    // ------------------------------------------------------------------
    //  The periodic pass, run from the mob manager's recount
    // ------------------------------------------------------------------

    public void tick() {
        long now = Bukkit.getCurrentTick();
        long timeoutTicks = plugin.getConfig().getInt("room-gating.timeout-seconds", 300) * 20L;
        for (Map.Entry<String, Map<String, GateState>> world : Map.copyOf(gates).entrySet()) {
            DungeonInstance dungeon = plugin.rooms().dungeon(world.getKey()).orElse(null);
            if (dungeon == null) {
                // The world went away and took its blocks with it.
                gates.remove(world.getKey());
                continue;
            }
            for (GateState state : List.copyOf(world.getValue().values())) {
                if (!state.dungeonId.equals(dungeon.id())) {
                    world.getValue().remove(state.roomId, state);
                    continue;
                }
                DungeonRoom room = dungeon.room(state.roomId);
                if (room == null) continue;
                if (dungeon.isCompleted()) {
                    if (!state.open) open(dungeon, state, null, List.of());
                    continue;
                }
                if (state.boss) {
                    tickArena(dungeon, room, state);
                } else if (!state.open) {
                    if (timeoutTicks > 0 && now - state.sealedAtTick >= timeoutTicks) {
                        state.latched = true;
                        open(dungeon, state, "gate-timeout", dungeon.world().getPlayers());
                        plugin.getLogger().warning("Gate timeout in dungeon " + dungeon.id() + ", room "
                                + state.roomId + ": " + plugin.mobs().livingCount(dungeon.id(), state.roomId)
                                + " mob(s) were still counted alive.");
                        continue;
                    }
                    glowStragglers(dungeon, state, now);
                }
            }
        }
        // Arming is event-driven on entry, but the party can also *become*
        // complete without anyone crossing a boundary - the missing player
        // logs out, or dies elsewhere - so the waiting arena re-checks here.
        if (defersBossSpawn()) {
            for (World world : plugin.worlds().loadedDungeonWorlds()) {
                DungeonInstance dungeon = plugin.rooms().dungeon(world).orElse(null);
                if (dungeon == null || dungeon.isCompleted()) continue;
                dungeon.rooms().stream().filter(room -> room.type() == DungeonLayout.RoomType.BOSS)
                        .findFirst().ifPresent(room -> armArena(dungeon, room));
            }
        }
    }

    // ------------------------------------------------------------------
    //  The boss arena
    // ------------------------------------------------------------------

    /**
     * The user-chosen rule: the boss spawns the moment <em>every</em> player
     * stands in the arena, and the entrance seals in the same breath. A wipe
     * opens it again so the party can walk back in, and a full arena seals it
     * anew - the fight resumes against the boss as they left it.
     */
    private void armArena(DungeonInstance dungeon, DungeonRoom room) {
        if (!defersBossSpawn()) return;
        Map<String, GateState> worldGates = gates.computeIfAbsent(dungeon.world().getName(), ignored -> new HashMap<>());
        GateState state = worldGates.get(room.id());
        if (state != null && (state.latched || !state.open)) return;

        // Spectators pass through blocks and would wait forever outside; they
        // neither count as needed nor as present.
        List<Player> needed = dungeon.world().getPlayers().stream()
                .filter(player -> !player.isDead() && player.getGameMode() != GameMode.SPECTATOR).toList();
        if (needed.isEmpty()) return;
        List<Player> inside = needed.stream().filter(player -> contains(room.bounds(), player)).toList();
        if (inside.isEmpty()) return;
        if (inside.size() < needed.size()) {
            for (Player player : inside) {
                player.sendActionBar(plugin.messages().get("arena-waiting",
                        Messages.ph("present", inside.size()), Messages.ph("needed", needed.size())));
            }
            return;
        }

        if (state == null) {
            Set<BlockVector> blocks = new HashSet<>();
            Location centre = null;
            for (DungeonLayout.Tunnel tunnel : dungeon.tunnels()) {
                if (otherEnd(dungeon, room, tunnel) == null) continue;
                Location sealed = seal(dungeon, room, doorwayOf(room, tunnel), blocks);
                if (centre == null) centre = sealed;
            }
            if (blocks.isEmpty()) {
                // An arena that cannot seal still gets its fight; the gate is
                // simply absent rather than the boss never appearing.
                plugin.getLogger().warning("Boss arena " + room.id() + " of dungeon " + dungeon.id()
                        + " had no doorway blocks to seal; the fight starts ungated.");
                worldGates.put(room.id(), latchedPlaceholder(dungeon, room));
            } else {
                worldGates.put(room.id(), new GateState(dungeon.id(), room.id(), true, blocks, material(), centre));
                sound(dungeon.world(), centre, "seal", "BLOCK_IRON_DOOR_CLOSE");
            }
            for (Player player : inside) plugin.messages().send(player, "arena-sealed");
            plugin.mobs().spawnBossRoomNow(dungeon, room);
            return;
        }

        // Everyone is back after a wipe: same blocks, same fight.
        reseal(dungeon, state);
        for (Player player : inside) plugin.messages().send(player, "arena-sealed");
    }

    private void tickArena(DungeonInstance dungeon, DungeonRoom room, GateState state) {
        if (state.open || state.latched) return;
        long timeoutTicks = plugin.getConfig().getInt("room-gating.timeout-seconds", 300) * 20L;
        if (timeoutTicks > 0 && Bukkit.getCurrentTick() - state.sealedAtTick >= timeoutTicks) {
            state.latched = true;
            open(dungeon, state, "gate-timeout", dungeon.world().getPlayers());
            plugin.getLogger().warning("Arena gate timeout in dungeon " + dungeon.id()
                    + "; the boss fight ran longer than the configured seal.");
            return;
        }
        boolean anyoneAlive = dungeon.world().getPlayers().stream()
                .filter(player -> !player.isDead() && player.getGameMode() != GameMode.SPECTATOR)
                .anyMatch(player -> contains(room.bounds(), player));
        if (!anyoneAlive) {
            // Not latched: the arena re-seals when the whole party returns.
            open(dungeon, state, "arena-wipe-opened", dungeon.world().getPlayers());
        }
    }

    // ------------------------------------------------------------------
    //  Blocks
    // ------------------------------------------------------------------

    /**
     * Fills one plane in the room's wall at the doorway opening. Widened two
     * blocks around the planned bounds so a hand-built opening slightly larger
     * than planned is still covered - on the wall plane the widening meets
     * wall blocks and changes nothing, which is why there is no second plane:
     * that is the part of the key door that assumed corridors have walls.
     */
    private Location seal(DungeonInstance dungeon, DungeonRoom room, Bounds doorway, Set<BlockVector> blocks) {
        World world = dungeon.world();
        boolean axisX = doorway.minX() == doorway.maxX();
        int at = axisX ? doorway.minX() : doorway.minZ();
        int inward = axisX ? Integer.signum(room.bounds().centreX() - at) : Integer.signum(room.bounds().centreZ() - at);
        int minCross = (axisX ? doorway.minZ() : doorway.minX()) - 2;
        int maxCross = (axisX ? doorway.maxZ() : doorway.maxX()) + 2;
        nudgeOutOfPlane(world, room, axisX, at, minCross, maxCross, doorway.minY(), doorway.maxY() + 2, inward);
        Material material = material();
        for (int y = doorway.minY(); y <= doorway.maxY() + 2; y++) {
            for (int cross = minCross; cross <= maxCross; cross++) {
                int x = axisX ? at : cross;
                int z = axisX ? cross : at;
                Block block = world.getBlockAt(x, y, z);
                if (!block.isPassable()) continue;
                // Physics on, so bars join into one gate instead of posts.
                block.setType(material, true);
                blocks.add(new BlockVector(x, y, z));
            }
        }
        double centreY = (doorway.minY() + doorway.maxY()) / 2.0 + 0.5;
        return axisX ? new Location(world, at + 0.5, centreY, doorway.centreZ() + 0.5)
                : new Location(world, doorway.centreX() + 0.5, centreY, at + 0.5);
    }

    /**
     * A player standing in the opening as it closes would be walled in;
     * they are moved one step into the room instead, which is the direction
     * they were going.
     */
    private void nudgeOutOfPlane(World world, DungeonRoom room, boolean axisX, int at,
                                 int minCross, int maxCross, int minY, int maxY, int inward) {
        for (Player player : world.getPlayers()) {
            Location feet = player.getLocation();
            int along = axisX ? feet.getBlockX() : feet.getBlockZ();
            int cross = axisX ? feet.getBlockZ() : feet.getBlockX();
            if (along != at || cross < minCross || cross > maxCross) continue;
            if (feet.getBlockY() + 1 < minY || feet.getBlockY() > maxY) continue;
            Location moved = feet.clone().add(axisX ? inward * 1.5 : 0.0, 0.0, axisX ? 0.0 : inward * 1.5);
            player.teleport(moved);
        }
    }

    private void reseal(DungeonInstance dungeon, GateState state) {
        World world = dungeon.world();
        // A body in a cell keeps that one cell open rather than being walled
        // in; the rest of the plane still closes around it.
        Set<BlockVector> occupied = new HashSet<>();
        for (Player player : world.getPlayers()) {
            Location feet = player.getLocation();
            occupied.add(new BlockVector(feet.getBlockX(), feet.getBlockY(), feet.getBlockZ()));
            occupied.add(new BlockVector(feet.getBlockX(), feet.getBlockY() + 1, feet.getBlockZ()));
        }
        for (BlockVector position : state.blocks) {
            if (occupied.contains(position)) continue;
            Block block = world.getBlockAt(position.getBlockX(), position.getBlockY(), position.getBlockZ());
            if (!block.isPassable()) continue;
            block.setType(state.material, true);
        }
        state.open = false;
        state.sealedAtTick = Bukkit.getCurrentTick();
        sound(world, state.centre, "seal", "BLOCK_IRON_DOOR_CLOSE");
    }

    private void open(DungeonInstance dungeon, GateState state, String messageKey, List<? extends Player> audience) {
        if (state.open) return;
        state.open = true;
        World world = dungeon.world();
        for (BlockVector position : state.blocks) {
            Block block = world.getBlockAt(position.getBlockX(), position.getBlockY(), position.getBlockZ());
            if (block.getType() != state.material) continue;
            world.spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 6,
                    0.3, 0.3, 0.3, block.getBlockData());
            block.setType(Material.AIR, true);
        }
        sound(world, state.centre, "open", "BLOCK_IRON_DOOR_OPEN");
        if (messageKey != null) {
            for (Player player : audience) plugin.messages().send(player, messageKey);
        }
    }

    /** The glow failsafe: the last stragglers of a long-sealed room light up. */
    private void glowStragglers(DungeonInstance dungeon, GateState state, long now) {
        if (!plugin.getConfig().getBoolean("room-gating.glow-last-mobs.enabled", true)) return;
        int atMost = Math.max(1, plugin.getConfig().getInt("room-gating.glow-last-mobs.at-most", 2));
        long afterTicks = plugin.getConfig().getInt("room-gating.glow-last-mobs.after-seconds", 30) * 20L;
        if (now - state.sealedAtTick < afterTicks) return;
        List<LivingEntity> living = plugin.mobs().livingEntities(state.dungeonId, state.roomId);
        if (living.isEmpty() || living.size() > atMost) return;
        for (LivingEntity entity : living) {
            // Re-applied every pass while the condition holds; twice the pass
            // interval, so it never flickers out between passes.
            entity.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 80, 0, true, false));
        }
    }

    // ------------------------------------------------------------------
    //  Denial
    // ------------------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) return;
        GateState state = closedGateAt(block);
        if (state == null) return;
        event.setCancelled(true);
        deny(event.getPlayer(), state);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        GateState state = closedGateAt(event.getBlock());
        if (state == null) return;
        event.setCancelled(true);
        deny(event.getPlayer(), state);
    }

    private GateState closedGateAt(Block block) {
        Map<String, GateState> worldGates = gates.get(block.getWorld().getName());
        if (worldGates == null) return null;
        BlockVector position = new BlockVector(block.getX(), block.getY(), block.getZ());
        for (GateState state : worldGates.values()) {
            if (!state.open && state.blocks.contains(position)) return state;
        }
        return null;
    }

    /** Same throttle as the key door: bumping bars twice a swing reads once. */
    private void deny(Player player, GateState state) {
        long tick = Bukkit.getCurrentTick();
        long interval = Math.max(1L, plugin.getConfig().getLong("door.deny-message-interval-ticks", 60L));
        Long last = state.lastDeny.get(player.getUniqueId());
        if (last != null && tick - last < interval) return;
        state.lastDeny.put(player.getUniqueId(), tick);
        plugin.messages().send(player, state.boss ? "arena-locked" : "gate-locked");
        Sound sound = DungeonDoorManager.sound(plugin.getConfig().getString("room-gating.sounds.deny", "BLOCK_CHEST_LOCKED"));
        if (sound != null) player.playSound(player.getLocation(), sound, 0.8F, 0.9F);
    }

    // ------------------------------------------------------------------
    //  Small helpers
    // ------------------------------------------------------------------

    private boolean enabled() {
        return plugin.getConfig().getBoolean("room-gating.enabled", true);
    }

    private boolean gated(DungeonRoom room) {
        if (!enabled()) return false;
        return switch (room.type()) {
            case NORMAL -> plugin.getConfig().getBoolean("room-gating.normal", true);
            case BRANCH -> plugin.getConfig().getBoolean("room-gating.branch", false);
            case BOSS -> plugin.getConfig().getBoolean("room-gating.boss", true);
            default -> false;
        };
    }

    private Material material() {
        String raw = plugin.getConfig().getString("room-gating.material", "IRON_BARS");
        Material material = raw == null ? null : Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        return material == null || !material.isBlock() || material.isAir() ? Material.IRON_BARS : material;
    }

    private DungeonRoom otherEnd(DungeonInstance dungeon, DungeonRoom room, DungeonLayout.Tunnel tunnel) {
        if (tunnel.firstRoomId().equals(room.id())) return dungeon.room(tunnel.secondRoomId());
        if (tunnel.secondRoomId().equals(room.id())) return dungeon.room(tunnel.firstRoomId());
        return null;
    }

    private Bounds doorwayOf(DungeonRoom room, DungeonLayout.Tunnel tunnel) {
        return tunnel.firstRoomId().equals(room.id()) ? tunnel.firstDoorway() : tunnel.secondDoorway();
    }

    private boolean contains(Bounds bounds, Player player) {
        Location at = player.getLocation();
        return bounds.contains(at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    private List<Player> playersInside(DungeonInstance dungeon, DungeonRoom room) {
        List<Player> inside = new ArrayList<>();
        for (Player player : dungeon.world().getPlayers()) {
            if (contains(room.bounds(), player)) inside.add(player);
        }
        return inside;
    }

    private void sound(World world, Location centre, String key, String fallback) {
        if (centre == null) return;
        Sound sound = DungeonDoorManager.sound(plugin.getConfig().getString("room-gating.sounds." + key, fallback));
        if (sound != null) world.playSound(centre, sound, 1.0F, 1.0F);
    }

    /** An arena that could not seal still needs a record, or it would re-arm every entry. */
    private GateState latchedPlaceholder(DungeonInstance dungeon, DungeonRoom room) {
        GateState state = new GateState(dungeon.id(), room.id(), true, Set.of(), material(), null);
        state.latched = true;
        state.open = true;
        return state;
    }

    private static final class GateState {
        private final String dungeonId;
        private final String roomId;
        private final boolean boss;
        private final Set<BlockVector> blocks;
        private final Material material;
        private final Location centre;
        private final Map<UUID, Long> lastDeny = new HashMap<>();
        private boolean open;
        /** Set by timeout and the admin command: this gate never seals again. */
        private boolean latched;
        private long sealedAtTick;

        private GateState(String dungeonId, String roomId, boolean boss, Set<BlockVector> blocks,
                          Material material, Location centre) {
            this.dungeonId = dungeonId;
            this.roomId = roomId;
            this.boss = boss;
            this.blocks = Set.copyOf(blocks);
            this.material = material;
            this.centre = centre;
            this.sealedAtTick = Bukkit.getCurrentTick();
        }
    }
}
