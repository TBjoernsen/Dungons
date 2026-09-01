package nl.riddernix.dungeonplugin.door

import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.generation.Bounds
import nl.riddernix.dungeonplugin.generation.DungeonLayout
import nl.riddernix.dungeonplugin.room.DungeonInstance
import nl.riddernix.dungeonplugin.room.DungeonRoom
import nl.riddernix.dungeonplugin.util.Messages
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.BlockVector
import java.util.Locale
import java.util.UUID

/**
 * Seals a combat room's exits while its mobs are alive, and the boss arena's
 * entrance while the boss is.
 *
 * Two rules shaped everything here. A room's *entrance* is never sealed, so
 * nobody is shut in with no way back - the exception is the boss arena, whose
 * entrance is its only doorway, and which therefore also opens itself the
 * moment no living player remains inside. And a room with nothing alive in it
 * never seals at all.
 *
 * Seals sit in the room's own wall: one plane at the doorway opening,
 * passable blocks only, so authored walls survive and the corridor's shape -
 * open platform or tunnel - is irrelevant. The key door in this package seals
 * a corridor mouth instead and predates platform corridors; this class
 * deliberately does not repeat that.
 *
 * Failsafes, because a stuck gate now blocks the main path: the mob manager's
 * recount fires missed clears into [onRoomCleared], a configurable timeout
 * opens a gate on its own, and `/dungeon room open` is the admin override. A
 * gate opened by timeout or command is latched and never re-seals that run.
 */
class DungeonRoomGateManager(private val plugin: DungeonPlugin) : Listener {

    /** Gate state per world, then per room; dungeon worlds never share runs. */
    private val gates = HashMap<String, HashMap<String, GateState>>()

    // ------------------------------------------------------------------
    //  Entry points
    // ------------------------------------------------------------------

    /** Whether the mob manager should leave the boss to be spawned by the gate. */
    fun defersBossSpawn(): Boolean =
        enabled() && plugin.config.getBoolean("room-gating.boss", true)

    /** Called by the registry after the enter events, so spawns are already queued. */
    fun onRoomEntered(dungeon: DungeonInstance, room: DungeonRoom, player: Player) {
        if (dungeon.isCompleted || !gated(room)) return
        if (room.type == DungeonLayout.RoomType.BOSS) {
            armArena(dungeon, room)
            return
        }
        val worldGates = gates.getOrPut(dungeon.world.name) { HashMap() }
        if (room.id in worldGates) return
        if (plugin.mobs.isRoomCleared(dungeon.id, room.id)) return
        val living = plugin.mobs.livingCount(dungeon.id, room.id)
        // The quiet-room rule: nothing alive, nothing sealed - a rest room or
        // an emptied recipe must never trap anyone behind a fight that is not
        // there.
        if (living <= 0) return

        val blocks = HashSet<BlockVector>()
        var centre: Location? = null
        for (tunnel in dungeon.tunnels) {
            val other = otherEnd(dungeon, room, tunnel)
            // Only ways onward are sealed; the way back to the parent stays
            // open by design.
            if (other == null || other.depth <= room.depth) continue
            val sealed = seal(dungeon, room, doorwayOf(room, tunnel), blocks)
            if (centre == null) centre = sealed
        }
        if (blocks.isEmpty()) return
        worldGates[room.id] = GateState(dungeon.id, room.id, false, blocks, material(), centre)
        sound(dungeon.world, centre, "seal", "BLOCK_IRON_DOOR_CLOSE")
        for (inside in playersInside(dungeon, room)) {
            plugin.messages.send(inside, "gate-sealed", Messages.ph("mobs", living))
        }
    }

    /** The clear moment, fired by the mob manager from kills and from the recount. */
    fun onRoomCleared(dungeon: DungeonInstance, room: DungeonRoom) {
        val worldGates = gates[dungeon.world.name]
        val state = worldGates?.get(room.id)
        if (state == null || state.dungeonId != dungeon.id || state.open) return
        open(dungeon, state, "gate-opened", playersInside(dungeon, room))
        // Cleared rooms can never seal again, so the record has nothing left
        // to remember.
        worldGates.remove(room.id)
    }

    /** Drops the arena bars at the kill itself, before completion moves anyone. */
    fun onBossDeath(dungeon: DungeonInstance, roomId: String) {
        val worldGates = gates[dungeon.world.name]
        val state = worldGates?.get(roomId)
        if (state == null || state.dungeonId != dungeon.id || state.open) return
        open(dungeon, state, null, emptyList())
        worldGates.remove(roomId)
    }

    /** One action-bar line per kill inside a sealed room: how many stand left. */
    fun notifyKill(dungeon: DungeonInstance, roomId: String) {
        val state = gates[dungeon.world.name]?.get(roomId)
        if (state == null || state.open || state.boss) return
        val living = plugin.mobs.livingCount(dungeon.id, roomId)
        if (living <= 0) return
        val room = dungeon.room(roomId) ?: return
        for (player in playersInside(dungeon, room)) {
            player.sendActionBar(plugin.messages.get("gate-remaining", Messages.ph("mobs", living)))
        }
    }

    enum class GateResult { OPENED, NONE }

    /** The admin override; a gate opened this way is latched for the run. */
    fun forceOpen(dungeon: DungeonInstance, room: DungeonRoom): GateResult {
        val state = gates[dungeon.world.name]?.get(room.id)
        if (state == null || state.open) return GateResult.NONE
        state.latched = true
        open(dungeon, state, "gate-forced", dungeon.world.players)
        return GateResult.OPENED
    }

    // ------------------------------------------------------------------
    //  The periodic pass, run from the mob manager's recount
    // ------------------------------------------------------------------

    fun tick() {
        val now = Bukkit.getCurrentTick().toLong()
        val timeoutTicks = plugin.config.getInt("room-gating.timeout-seconds", 300) * 20L
        for ((worldName, worldGates) in gates.toMap()) {
            val dungeon = plugin.rooms.dungeon(worldName)
            if (dungeon == null) {
                // The world went away and took its blocks with it.
                gates.remove(worldName)
                continue
            }
            for (state in worldGates.values.toList()) {
                if (state.dungeonId != dungeon.id) {
                    worldGates.remove(state.roomId, state)
                    continue
                }
                val room = dungeon.room(state.roomId) ?: continue
                if (dungeon.isCompleted) {
                    if (!state.open) open(dungeon, state, null, emptyList())
                    continue
                }
                if (state.boss) {
                    tickArena(dungeon, room, state)
                } else if (!state.open) {
                    if (timeoutTicks > 0 && now - state.sealedAtTick >= timeoutTicks) {
                        state.latched = true
                        open(dungeon, state, "gate-timeout", dungeon.world.players)
                        plugin.logger.warning("Gate timeout in dungeon ${dungeon.id}, room " +
                            "${state.roomId}: ${plugin.mobs.livingCount(dungeon.id, state.roomId)}" +
                            " mob(s) were still counted alive.")
                        continue
                    }
                    glowStragglers(dungeon, state, now)
                }
            }
        }
        // Arming is event-driven on entry, but the party can also *become*
        // complete without anyone crossing a boundary - the missing player
        // logs out, or dies elsewhere - so the waiting arena re-checks here.
        if (defersBossSpawn()) {
            for (world in plugin.worlds.loadedDungeonWorlds()) {
                val dungeon = plugin.rooms.dungeon(world)
                if (dungeon == null || dungeon.isCompleted) continue
                dungeon.rooms.firstOrNull { it.type == DungeonLayout.RoomType.BOSS }
                    ?.let { armArena(dungeon, it) }
            }
        }
    }

    // ------------------------------------------------------------------
    //  The boss arena
    // ------------------------------------------------------------------

    /**
     * The user-chosen rule: the boss spawns the moment *every* player stands
     * in the arena, and the entrance seals in the same breath. A wipe opens
     * it again so the party can walk back in, and a full arena seals it anew
     * - the fight resumes against the boss as they left it.
     */
    private fun armArena(dungeon: DungeonInstance, room: DungeonRoom) {
        if (!defersBossSpawn()) return
        val worldGates = gates.getOrPut(dungeon.world.name) { HashMap() }
        val state = worldGates[room.id]
        if (state != null && (state.latched || !state.open)) return

        // Spectators pass through blocks and would wait forever outside; they
        // neither count as needed nor as present.
        val needed = dungeon.world.players.filter { !it.isDead && it.gameMode != GameMode.SPECTATOR }
        if (needed.isEmpty()) return
        val inside = needed.filter { contains(room.bounds, it) }
        if (inside.isEmpty()) return
        if (inside.size < needed.size) {
            for (player in inside) {
                player.sendActionBar(plugin.messages.get("arena-waiting",
                    Messages.ph("present", inside.size), Messages.ph("needed", needed.size)))
            }
            return
        }

        if (state == null) {
            val blocks = HashSet<BlockVector>()
            var centre: Location? = null
            for (tunnel in dungeon.tunnels) {
                if (otherEnd(dungeon, room, tunnel) == null) continue
                val sealed = seal(dungeon, room, doorwayOf(room, tunnel), blocks)
                if (centre == null) centre = sealed
            }
            if (blocks.isEmpty()) {
                // An arena that cannot seal still gets its fight; the gate is
                // simply absent rather than the boss never appearing.
                plugin.logger.warning("Boss arena ${room.id} of dungeon ${dungeon.id}" +
                    " had no doorway blocks to seal; the fight starts ungated.")
                worldGates[room.id] = latchedPlaceholder(dungeon, room)
            } else {
                worldGates[room.id] = GateState(dungeon.id, room.id, true, blocks, material(), centre)
                sound(dungeon.world, centre, "seal", "BLOCK_IRON_DOOR_CLOSE")
            }
            for (player in inside) plugin.messages.send(player, "arena-sealed")
            plugin.mobs.spawnBossRoomNow(dungeon, room)
            return
        }

        // Everyone is back after a wipe: same blocks, same fight.
        reseal(dungeon, state)
        for (player in inside) plugin.messages.send(player, "arena-sealed")
    }

    private fun tickArena(dungeon: DungeonInstance, room: DungeonRoom, state: GateState) {
        if (state.open || state.latched) return
        val timeoutTicks = plugin.config.getInt("room-gating.timeout-seconds", 300) * 20L
        if (timeoutTicks > 0 && Bukkit.getCurrentTick() - state.sealedAtTick >= timeoutTicks) {
            state.latched = true
            open(dungeon, state, "gate-timeout", dungeon.world.players)
            plugin.logger.warning("Arena gate timeout in dungeon ${dungeon.id}" +
                "; the boss fight ran longer than the configured seal.")
            return
        }
        val anyoneAlive = dungeon.world.players
            .filter { !it.isDead && it.gameMode != GameMode.SPECTATOR }
            .any { contains(room.bounds, it) }
        if (!anyoneAlive) {
            // Not latched: the arena re-seals when the whole party returns.
            open(dungeon, state, "arena-wipe-opened", dungeon.world.players)
        }
    }

    // ------------------------------------------------------------------
    //  Blocks
    // ------------------------------------------------------------------

    /**
     * Fills one plane in the room's wall at the doorway opening. Widened two
     * blocks around the planned bounds so a hand-built opening slightly
     * larger than planned is still covered - on the wall plane the widening
     * meets wall blocks and changes nothing, which is why there is no second
     * plane: that is the part of the key door that assumed corridors have
     * walls.
     */
    private fun seal(dungeon: DungeonInstance, room: DungeonRoom, doorway: Bounds, blocks: MutableSet<BlockVector>): Location {
        val world = dungeon.world
        val axisX = doorway.minX == doorway.maxX
        val at = if (axisX) doorway.minX else doorway.minZ
        val inward = if (axisX) Integer.signum(room.bounds.centreX() - at) else Integer.signum(room.bounds.centreZ() - at)
        val minCross = (if (axisX) doorway.minZ else doorway.minX) - 2
        val maxCross = (if (axisX) doorway.maxZ else doorway.maxX) + 2
        nudgeOutOfPlane(world, room, axisX, at, minCross, maxCross, doorway.minY, doorway.maxY + 2, inward)
        val material = material()
        for (y in doorway.minY..doorway.maxY + 2) {
            for (cross in minCross..maxCross) {
                val x = if (axisX) at else cross
                val z = if (axisX) cross else at
                val block = world.getBlockAt(x, y, z)
                if (!block.isPassable) continue
                // Physics on, so bars join into one gate instead of posts.
                block.setType(material, true)
                blocks.add(BlockVector(x, y, z))
            }
        }
        val centreY = (doorway.minY + doorway.maxY) / 2.0 + 0.5
        return if (axisX) Location(world, at + 0.5, centreY, doorway.centreZ() + 0.5)
            else Location(world, doorway.centreX() + 0.5, centreY, at + 0.5)
    }

    /**
     * A player standing in the opening as it closes would be walled in; they
     * are moved one step into the room instead, which is the direction they
     * were going.
     */
    private fun nudgeOutOfPlane(world: World, room: DungeonRoom, axisX: Boolean, at: Int,
                                minCross: Int, maxCross: Int, minY: Int, maxY: Int, inward: Int) {
        for (player in world.players) {
            val feet = player.location
            val along = if (axisX) feet.blockX else feet.blockZ
            val cross = if (axisX) feet.blockZ else feet.blockX
            if (along != at || cross < minCross || cross > maxCross) continue
            if (feet.blockY + 1 < minY || feet.blockY > maxY) continue
            val moved = feet.clone().add(if (axisX) inward * 1.5 else 0.0, 0.0, if (axisX) 0.0 else inward * 1.5)
            player.teleport(moved)
        }
    }

    private fun reseal(dungeon: DungeonInstance, state: GateState) {
        val world = dungeon.world
        // A body in a cell keeps that one cell open rather than being walled
        // in; the rest of the plane still closes around it.
        val occupied = HashSet<BlockVector>()
        for (player in world.players) {
            val feet = player.location
            occupied.add(BlockVector(feet.blockX, feet.blockY, feet.blockZ))
            occupied.add(BlockVector(feet.blockX, feet.blockY + 1, feet.blockZ))
        }
        for (position in state.blocks) {
            if (position in occupied) continue
            val block = world.getBlockAt(position.blockX, position.blockY, position.blockZ)
            if (!block.isPassable) continue
            block.setType(state.material, true)
        }
        state.open = false
        state.sealedAtTick = Bukkit.getCurrentTick().toLong()
        sound(world, state.centre, "seal", "BLOCK_IRON_DOOR_CLOSE")
    }

    private fun open(dungeon: DungeonInstance, state: GateState, messageKey: String?, audience: List<Player>) {
        if (state.open) return
        state.open = true
        val world = dungeon.world
        for (position in state.blocks) {
            val block = world.getBlockAt(position.blockX, position.blockY, position.blockZ)
            if (block.type != state.material) continue
            world.spawnParticle(Particle.BLOCK, block.location.add(0.5, 0.5, 0.5), 6,
                0.3, 0.3, 0.3, block.blockData)
            block.setType(Material.AIR, true)
        }
        sound(world, state.centre, "open", "BLOCK_IRON_DOOR_OPEN")
        if (messageKey != null) {
            for (player in audience) plugin.messages.send(player, messageKey)
        }
    }

    /** The glow failsafe: the last stragglers of a long-sealed room light up. */
    private fun glowStragglers(dungeon: DungeonInstance, state: GateState, now: Long) {
        if (!plugin.config.getBoolean("room-gating.glow-last-mobs.enabled", true)) return
        val atMost = maxOf(1, plugin.config.getInt("room-gating.glow-last-mobs.at-most", 2))
        val afterTicks = plugin.config.getInt("room-gating.glow-last-mobs.after-seconds", 30) * 20L
        if (now - state.sealedAtTick < afterTicks) return
        val living = plugin.mobs.livingEntities(state.dungeonId, state.roomId)
        if (living.isEmpty() || living.size > atMost) return
        for (entity in living) {
            // Re-applied every pass while the condition holds; twice the pass
            // interval, so it never flickers out between passes.
            entity.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, 80, 0, true, false))
        }
    }

    // ------------------------------------------------------------------
    //  Denial
    // ------------------------------------------------------------------

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        val state = closedGateAt(block) ?: return
        event.isCancelled = true
        deny(event.player, state)
    }

    @EventHandler
    fun onBreak(event: BlockBreakEvent) {
        val state = closedGateAt(event.block) ?: return
        event.isCancelled = true
        deny(event.player, state)
    }

    private fun closedGateAt(block: Block): GateState? {
        val worldGates = gates[block.world.name] ?: return null
        val position = BlockVector(block.x, block.y, block.z)
        for (state in worldGates.values) {
            if (!state.open && position in state.blocks) return state
        }
        return null
    }

    /** Same throttle as the key door: bumping bars twice a swing reads once. */
    private fun deny(player: Player, state: GateState) {
        val tick = Bukkit.getCurrentTick().toLong()
        val interval = maxOf(1L, plugin.config.getLong("door.deny-message-interval-ticks", 60L))
        val last = state.lastDeny[player.uniqueId]
        if (last != null && tick - last < interval) return
        state.lastDeny[player.uniqueId] = tick
        plugin.messages.send(player, if (state.boss) "arena-locked" else "gate-locked")
        val sound = DungeonDoorManager.sound(plugin.config.getString("room-gating.sounds.deny", "BLOCK_CHEST_LOCKED"))
        if (sound != null) player.playSound(player.location, sound, 0.8F, 0.9F)
    }

    // ------------------------------------------------------------------
    //  Small helpers
    // ------------------------------------------------------------------

    private fun enabled(): Boolean = plugin.config.getBoolean("room-gating.enabled", true)

    private fun gated(room: DungeonRoom): Boolean {
        if (!enabled()) return false
        return when (room.type) {
            DungeonLayout.RoomType.NORMAL -> plugin.config.getBoolean("room-gating.normal", true)
            DungeonLayout.RoomType.BRANCH -> plugin.config.getBoolean("room-gating.branch", false)
            DungeonLayout.RoomType.BOSS -> plugin.config.getBoolean("room-gating.boss", true)
            else -> false
        }
    }

    private fun material(): Material {
        val raw = plugin.config.getString("room-gating.material", "IRON_BARS")
        val material = raw?.let { Material.matchMaterial(it.trim().uppercase(Locale.ROOT)) }
        return if (material == null || !material.isBlock || material.isAir) Material.IRON_BARS else material
    }

    private fun otherEnd(dungeon: DungeonInstance, room: DungeonRoom, tunnel: DungeonLayout.Tunnel): DungeonRoom? {
        if (tunnel.firstRoomId == room.id) return dungeon.room(tunnel.secondRoomId)
        if (tunnel.secondRoomId == room.id) return dungeon.room(tunnel.firstRoomId)
        return null
    }

    private fun doorwayOf(room: DungeonRoom, tunnel: DungeonLayout.Tunnel): Bounds =
        if (tunnel.firstRoomId == room.id) tunnel.firstDoorway else tunnel.secondDoorway

    private fun contains(bounds: Bounds, player: Player): Boolean {
        val at = player.location
        return bounds.contains(at.blockX, at.blockY, at.blockZ)
    }

    private fun playersInside(dungeon: DungeonInstance, room: DungeonRoom): List<Player> =
        dungeon.world.players.filter { contains(room.bounds, it) }

    private fun sound(world: World, centre: Location?, key: String, fallback: String) {
        if (centre == null) return
        val sound = DungeonDoorManager.sound(plugin.config.getString("room-gating.sounds.$key", fallback))
        if (sound != null) world.playSound(centre, sound, 1.0F, 1.0F)
    }

    /** An arena that could not seal still needs a record, or it would re-arm every entry. */
    private fun latchedPlaceholder(dungeon: DungeonInstance, room: DungeonRoom): GateState {
        val state = GateState(dungeon.id, room.id, true, emptySet(), material(), null)
        state.latched = true
        state.open = true
        return state
    }

    private class GateState(
        val dungeonId: String,
        val roomId: String,
        val boss: Boolean,
        blocks: Set<BlockVector>,
        val material: Material,
        val centre: Location?
    ) {
        val blocks: Set<BlockVector> = blocks.toSet()
        val lastDeny = HashMap<UUID, Long>()
        var open = false

        /** Set by timeout and the admin command: this gate never seals again. */
        var latched = false
        var sealedAtTick: Long = Bukkit.getCurrentTick().toLong()
    }
}
