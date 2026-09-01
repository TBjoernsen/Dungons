package nl.riddernix.dungeonplugin.trap

import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.room.DungeonInstance
import nl.riddernix.dungeonplugin.room.DungeonTrap
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.util.BlockVector
import org.bukkit.util.BoundingBox
import java.util.Locale
import java.util.UUID

/**
 * Authored pressure-plate trap floors (config `trap`).
 *
 * Everything a trap needs is captured when its dungeon registers: the placed
 * blocks of every marked column are snapshotted from the world, so restoring
 * the floor is an exact rebuild of whatever the schematic put there, plates
 * included. The drop itself kills deliberately - this is a void world, and a
 * player who falls out of it is a player the plugin has lost control over -
 * and the hole it leaves does run through to the void, so anything that jumps
 * in afterwards still dies a normal void death.
 *
 * One trap has one timer: firing moves it out of the armed phase until the
 * floor has been rebuilt, so a second player on the plate can neither re-fire
 * it nor stretch the countdown.
 */
class DungeonTrapManager(private val plugin: DungeonPlugin) : Listener {

    private val byWorld = HashMap<String, List<TrapState>>()

    /** Snapshots every authored trap of a freshly registered dungeon. */
    fun install(dungeon: DungeonInstance) {
        val worldName = dungeon.world.name
        byWorld.remove(worldName)
        if (dungeon.traps.isEmpty()) return
        val states = ArrayList<TrapState>()
        for (trap in dungeon.traps.values) {
            val room = dungeon.room(trap.roomId)
            if (room == null || trap.columns.isEmpty()) continue
            val bottom = room.bounds.minY
            val maximumRise = maxOf(0, plugin.config.getInt("trap.max-column-height", 8))
            val snapshot = ArrayList<BlockSnapshot>()
            for (column in trap.columns) {
                // Whatever stands on the marked floor comes down with it: the
                // column climbs through anything that is not air and stops at
                // the first gap. A plate on the floor is caught by the same
                // rule rather than by a special case.
                val rise = DungeonTrap.rise(
                    { y -> !dungeon.world.getBlockAt(column.x, y, column.z).type.isAir },
                    column.topY, maximumRise)
                for (y in column.topY + rise downTo bottom) {
                    val block = dungeon.world.getBlockAt(column.x, y, column.z)
                    snapshot.add(BlockSnapshot(column.x, y, column.z, block.blockData))
                }
            }
            states.add(TrapState(dungeon.id, trap, bottom, snapshot))
        }
        if (states.isNotEmpty()) byWorld[worldName] = states
    }

    @EventHandler
    fun onPlatePress(event: PlayerInteractEvent) {
        if (event.action != Action.PHYSICAL || event.clickedBlock == null) return
        trigger(event.clickedBlock!!)
    }

    /** Mobs and other non-players stepping on a plate arrive through this event. */
    @EventHandler
    fun onEntityPress(event: EntityInteractEvent) {
        trigger(event.block)
    }

    private fun trigger(block: Block) {
        val states = byWorld[block.world.name] ?: return
        val position = BlockVector(block.x, block.y, block.z)
        for (state in states) {
            if (state.phase != Phase.ARMED || position !in state.trap.plates) continue
            fire(state, block.world)
            return
        }
    }

    private fun fire(state: TrapState, world: World) {
        state.phase = Phase.TRIGGERED
        val centre = state.centre(world)
        val click = sound(plugin.config.getString("trap.sounds.trigger", "BLOCK_DISPENSER_FAIL"))
        if (click != null) world.playSound(centre, click, 1.0F, 0.7F)
        // Two timers from the same moment: the floor goes almost at once, the
        // kill lands later so the victim gets a fall before it.
        val dropDelay = seconds("trap.drop-delay-seconds", 0.4)
        val killDelay = seconds("trap.kill-delay-seconds", 3.0)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { drop(state, world) }, dropDelay)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { kill(state, world) }, killDelay)
    }

    private fun seconds(path: String, fallback: Double): Long =
        maxOf(0L, Math.round(plugin.config.getDouble(path, fallback) * 20.0))

    /**
     * The deliberate death, covering both who stood on the floor when it went
     * and anyone who has wandered on since.
     *
     * The first group is remembered rather than looked up, because by now
     * they are metres below the room and no longer anywhere near it. That is
     * the whole point of doing this by hand: in a void world a fall has no
     * ending of its own.
     */
    private fun kill(state: TrapState, world: World) {
        val dungeon = plugin.rooms.dungeon(world) ?: return
        if (dungeon.id != state.dungeonId) return
        val victims = ArrayList(livingOnColumns(state, world))
        for (id in state.falling) {
            val entity = Bukkit.getEntity(id)
            if (entity is LivingEntity && entity !in victims) {
                victims.add(entity)
            }
        }
        state.falling.clear()
        for (victim in victims) {
            victim.damage(1_000_000.0)
        }
    }

    private fun drop(state: TrapState, world: World) {
        val dungeon = plugin.rooms.dungeon(world) ?: return
        if (dungeon.id != state.dungeonId) return
        state.phase = Phase.OPEN
        val returnTicks = maxOf(20L, plugin.config.getLong("trap.floor-return-seconds", 15L) * 20L)
        state.restoreAtTick = Bukkit.getCurrentTick() + returnTicks

        // Noted while they are still standing on it: once the blocks are gone
        // they drop out of the room and could never be found by position.
        for (standing in livingOnColumns(state, world)) {
            state.falling.add(standing.uniqueId)
        }
        for (snapshot in state.snapshot) {
            val block = world.getBlockAt(snapshot.x, snapshot.y, snapshot.z)
            if (block.type.isAir) continue
            if (snapshot.y >= state.topY() - 1) {
                world.spawnParticle(Particle.BLOCK, snapshot.x + 0.5, snapshot.y + 0.5, snapshot.z + 0.5,
                    8, 0.3, 0.3, 0.3, block.blockData)
            }
            block.setType(Material.AIR, false)
        }
        val collapse = sound(plugin.config.getString("trap.sounds.collapse", "BLOCK_GRAVEL_BREAK"))
        if (collapse != null) world.playSound(state.centre(world), collapse, 1.2F, 0.8F)
    }

    /** Runs a few times per second; rebuilds any open floor whose time is up. */
    fun tick() {
        val now = Bukkit.getCurrentTick().toLong()
        for ((worldName, states) in byWorld.toMap()) {
            val dungeon = plugin.rooms.dungeon(worldName)
            if (dungeon == null || states.none { dungeon.id == it.dungeonId }) {
                byWorld.remove(worldName, states)
                continue
            }
            for (state in states) {
                if (state.phase == Phase.OPEN && now >= state.restoreAtTick) {
                    restore(state, dungeon.world)
                }
            }
        }
    }

    private fun restore(state: TrapState, world: World) {
        // Nothing may be entombed: whatever hovers inside the hole is lifted
        // onto the floor being rebuilt. Anything already below the room is
        // past saving and finishes its fall into the void.
        for (column in state.trap.columns) {
            val cell = BoundingBox(column.x.toDouble(), state.bottom.toDouble(), column.z.toDouble(),
                column.x + 1.0, column.topY + 2.0, column.z + 1.0)
            for (entity in world.getNearbyEntities(cell)) {
                if (entity !is LivingEntity && entity !is Item) continue
                val lifted = entity.location
                lifted.y = column.topY + 1.0
                entity.teleport(lifted)
            }
        }
        for (snapshot in state.snapshot) {
            world.getBlockAt(snapshot.x, snapshot.y, snapshot.z).setBlockData(snapshot.data, false)
        }
        val rebuilt = sound(plugin.config.getString("trap.sounds.restore", "BLOCK_STONE_PLACE"))
        if (rebuilt != null) world.playSound(state.centre(world), rebuilt, 1.0F, 0.9F)
        state.phase = Phase.ARMED
    }

    private enum class Phase { ARMED, TRIGGERED, OPEN }

    private data class BlockSnapshot(val x: Int, val y: Int, val z: Int, val data: BlockData)

    private class TrapState(
        val dungeonId: String,
        val trap: DungeonTrap,
        val bottom: Int,
        snapshot: List<BlockSnapshot>
    ) {
        val snapshot: List<BlockSnapshot> = snapshot.toList()
        var phase = Phase.ARMED
        var restoreAtTick = 0L

        /** Who went down with the floor and is still owed their death. */
        val falling = HashSet<UUID>()

        fun topY(): Int = trap.columns.maxOfOrNull { it.topY } ?: bottom

        fun centre(world: World): Location {
            val x = trap.columns.map { it.x }.average().takeIf { !it.isNaN() } ?: 0.0
            val z = trap.columns.map { it.z }.average().takeIf { !it.isNaN() } ?: 0.0
            return Location(world, x + 0.5, topY() + 1.0, z + 0.5)
        }
    }

    companion object {
        /** How far above a column's floor the deliberate kill reaches, in blocks. */
        private const val KILL_HEIGHT = 4

        private fun livingOnColumns(state: TrapState, world: World): List<LivingEntity> {
            val victims = ArrayList<LivingEntity>()
            for (column in state.trap.columns) {
                val cell = BoundingBox(column.x.toDouble(), column.topY + 1.0, column.z.toDouble(),
                    column.x + 1.0, column.topY + 1.0 + KILL_HEIGHT, column.z + 1.0)
                for (entity in world.getNearbyEntities(cell)) {
                    if (entity is LivingEntity && entity !in victims) victims.add(entity)
                }
            }
            return victims
        }

        private fun sound(raw: String?): Sound? {
            if (raw.isNullOrBlank()) return null
            return Registry.SOUNDS.get(NamespacedKey.minecraft(raw.trim().lowercase(Locale.ROOT).replace('_', '.')))
        }
    }
}
