package nl.riddernix.dungeonplugin.door

import net.kyori.adventure.text.minimessage.MiniMessage
import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.room.DungeonInstance
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BlockVector
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.Locale
import java.util.UUID

/**
 * Builds and owns the sealed corridor of composed dungeons.
 *
 * The barrier fills every passable block of the locked corridor's mouth, two
 * planes deep, so an authored corridor tile is sealed whatever the shape of
 * its passage - walls are never replaced. The key is state on the dungeon
 * instance, granted the moment the key guardian dies, so it survives player
 * death, logout and party changes by construction.
 *
 * Because the door blocks the critical path rather than a side room, a
 * vanished guardian would strand the whole run. The watchdog therefore
 * revives a guardian that stopped existing without granting the key, and
 * after `door.watchdog.max-revivals` failed revivals it opens the door by
 * itself and says so in the log.
 */
class DungeonDoorManager(private val plugin: DungeonPlugin) : Listener {

    private val miniMessage = MiniMessage.miniMessage()

    /** One door per dungeon world; worlds are disposable and never share runs. */
    private val doors = HashMap<String, DoorState>()

    /** Seals the key gate of a freshly registered dungeon, if it declares one. */
    fun install(dungeon: DungeonInstance) {
        val gate = dungeon.keyGate
        if (gate == null) {
            doors.remove(dungeon.world.name)
            return
        }
        val tunnel = dungeon.tunnels.firstOrNull { it.id() == gate.lockedTunnelId }
        if (tunnel == null) {
            plugin.logger.severe("Dungeon ${dungeon.id} declares locked corridor ${gate.lockedTunnelId}" +
                " but no such corridor exists; the door was not built.")
            return
        }

        val world = dungeon.world
        val doorway = tunnel.firstDoorway
        val axisX = doorway.minX == doorway.maxX
        val step = if (axisX) Integer.signum(tunnel.secondDoorway.minX - doorway.minX)
            else Integer.signum(tunnel.secondDoorway.minZ - doorway.minZ)
        val material = barrierMaterial()

        // The doorway bounds are the planned opening; the cross-section is
        // widened a little so a hand-built opening that is slightly larger is
        // still sealed. Only passable blocks are filled, so authored walls
        // and ceilings are left exactly as built.
        val blocks = HashSet<BlockVector>()
        for (plane in 0..1) {
            if (axisX) {
                val x = doorway.minX + plane * step
                for (y in doorway.minY..doorway.maxY + 2) {
                    for (z in doorway.minZ - 2..doorway.maxZ + 2) {
                        seal(world, x, y, z, material, blocks)
                    }
                }
            } else {
                val z = doorway.minZ + plane * step
                for (y in doorway.minY..doorway.maxY + 2) {
                    for (x in doorway.minX - 2..doorway.maxX + 2) {
                        seal(world, x, y, z, material, blocks)
                    }
                }
            }
        }
        if (blocks.isEmpty()) {
            plugin.logger.severe("Locked corridor ${gate.lockedTunnelId} of dungeon ${dungeon.id}" +
                " had no passable blocks to seal; the door is effectively open.")
        }

        val centreY = (doorway.minY + doorway.maxY) / 2.0 + 0.5
        val centre = if (axisX)
            Location(world, doorway.minX + 0.5, centreY, doorway.centreZ() + 0.5)
        else
            Location(world, doorway.centreX() + 0.5, centreY, doorway.minZ + 0.5)
        val displayId = spawnLabel(world, centre, axisX, step)
        doors[world.name] = DoorState(dungeon.id, world.name, gate.guardianRoomId,
            blocks, displayId, centre, material)
    }

    private fun spawnLabel(world: World, centre: Location, axisX: Boolean, step: Int): UUID? {
        val text = plugin.config.getString("door.label.text", "")
        if (text.isNullOrBlank()) return null
        val offset = plugin.config.getDouble("door.label.offset", 0.6)
        val brightness = plugin.config.getInt("door.label.brightness", 15).coerceIn(0, 15)
        val scale = plugin.config.getDouble("door.label.scale", 1.1).toFloat()
        val location = centre.clone().add(if (axisX) -step * offset else 0.0, 0.0, if (axisX) 0.0 else -step * offset)
        // The label faces back down the corridor mouth, towards the room the
        // approaching party arrives from.
        val yaw = if (axisX) (if (step > 0) 90.0F else -90.0F) else (if (step > 0) 180.0F else 0.0F)
        val display = world.spawn(location, TextDisplay::class.java) { entity ->
            entity.text(miniMessage.deserialize(text))
            entity.billboard = Display.Billboard.FIXED
            entity.setRotation(yaw, 0.0F)
            entity.isPersistent = false
            entity.brightness = Display.Brightness(brightness, brightness)
            entity.alignment = TextDisplay.TextAlignment.CENTER
            entity.transformation = Transformation(Vector3f(), AxisAngle4f(),
                Vector3f(scale, scale, scale), AxisAngle4f())
        }
        return display.uniqueId
    }

    /** The key moment: grant, announce, and open - in that order. */
    fun onGuardianDeath(dungeon: DungeonInstance, guardianLocation: Location?) {
        if (!dungeon.obtainKey()) return
        val gate = dungeon.keyGate
        plugin.events.fireKeyObtained(plugin.snapshots.of(dungeon), gate?.guardianRoomId ?: "")
        for (player in dungeon.world.players) {
            plugin.messages.send(player, "door-key-obtained")
        }
        playKeyVisual(dungeon.world, guardianLocation)
        val state = doors[dungeon.world.name]
        if (state != null && state.dungeonId == dungeon.id) {
            open(dungeon, state, false)
        }
    }

    enum class ForceResult { OPENED, ALREADY_OPEN, NO_DOOR }

    /** Admin or watchdog escape hatch: opens without the guardian's key. */
    fun forceOpen(dungeon: DungeonInstance): ForceResult {
        val state = doors[dungeon.world.name]
        if (state == null || state.dungeonId != dungeon.id) return ForceResult.NO_DOOR
        if (state.open) return ForceResult.ALREADY_OPEN
        open(dungeon, state, true)
        return ForceResult.OPENED
    }

    private fun open(dungeon: DungeonInstance, state: DoorState, forced: Boolean) {
        if (state.open) return
        state.open = true
        val world = Bukkit.getWorld(state.worldName)
        if (world != null) {
            for (position in state.blocks) {
                val block = world.getBlockAt(position.blockX, position.blockY, position.blockZ)
                if (block.type != state.material) continue
                world.spawnParticle(Particle.BLOCK, block.location.add(0.5, 0.5, 0.5), 6,
                    0.3, 0.3, 0.3, block.blockData)
                block.setType(Material.AIR, true)
            }
            if (state.displayId != null) {
                Bukkit.getEntity(state.displayId)?.remove()
            }
            val sound = sound(plugin.config.getString("door.sounds.open", "BLOCK_BEACON_ACTIVATE"))
            if (sound != null) world.playSound(state.centre, sound, 1.0F, 1.0F)
        }
        plugin.events.fireDoorOpened(plugin.snapshots.of(dungeon), forced)
    }

    /** A small scripted moment: the key rises out of the fallen guardian. */
    private fun playKeyVisual(world: World, guardianLocation: Location?) {
        if (guardianLocation == null) return
        val start = guardianLocation.clone().add(0.0, 0.6, 0.0)
        val display = world.spawn(start, ItemDisplay::class.java) { entity ->
            entity.setItemStack(ItemStack(Material.TRIAL_KEY))
            entity.billboard = Display.Billboard.CENTER
            entity.isPersistent = false
            entity.brightness = Display.Brightness(15, 15)
        }
        val chime = sound("ENTITY_PLAYER_LEVELUP")
        if (chime != null) world.playSound(start, chime, 0.8F, 1.4F)
        // One interpolated rise set a tick after spawn, so the client lerps
        // from the spawn transform instead of snapping.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!display.isValid) return@Runnable
            display.interpolationDelay = 0
            display.interpolationDuration = 40
            display.transformation = Transformation(Vector3f(0.0F, 2.2F, 0.0F), AxisAngle4f(),
                Vector3f(1.0F, 1.0F, 1.0F), AxisAngle4f())
        })
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { display.remove() }, 55L)
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        val state = closedDoorAt(block) ?: return
        event.isCancelled = true
        deny(event.player, state)
    }

    @EventHandler
    fun onBreak(event: BlockBreakEvent) {
        val state = closedDoorAt(event.block) ?: return
        event.isCancelled = true
        deny(event.player, state)
    }

    private fun closedDoorAt(block: Block): DoorState? {
        val state = doors[block.world.name]
        if (state == null || state.open) return null
        return if (BlockVector(block.x, block.y, block.z) in state.blocks) state else null
    }

    /** A player bumping the door twice per swing still reads one calm line. */
    private fun deny(player: Player, state: DoorState) {
        val tick = Bukkit.getCurrentTick().toLong()
        val interval = maxOf(1L, plugin.config.getLong("door.deny-message-interval-ticks", 60L))
        val last = state.lastDeny[player.uniqueId]
        if (last != null && tick - last < interval) return
        state.lastDeny[player.uniqueId] = tick
        plugin.messages.send(player, "door-locked")
        val sound = sound(plugin.config.getString("door.sounds.deny", "BLOCK_CHEST_LOCKED"))
        if (sound != null) player.playSound(player.location, sound, 0.8F, 0.9F)
    }

    /**
     * Runs on the configured interval. Only ever acts on a woken guardian
     * room that reports nothing alive while the key was never granted -
     * exactly the state that would strand the run.
     */
    fun watchdog() {
        for ((worldName, state) in doors.toMap()) {
            val dungeon = plugin.rooms.dungeon(worldName)
            if (dungeon == null || dungeon.id != state.dungeonId) {
                // The world is gone or reused; its entities went with it.
                doors.remove(worldName, state)
                continue
            }
            if (state.open || dungeon.isKeyObtained || dungeon.isCompleted) continue
            if (!plugin.mobs.isRoomVisited(state.dungeonId, state.guardianRoomId)) continue
            if (plugin.mobs.livingCount(state.dungeonId, state.guardianRoomId) > 0) {
                state.quietChecks = 0
                continue
            }
            // Two consecutive quiet checks, so one glance mid-respawn or
            // mid-recount can never trigger a revival.
            if (++state.quietChecks < 2) continue
            state.quietChecks = 0
            val maxRevivals = maxOf(0, plugin.config.getInt("door.watchdog.max-revivals", 2))
            val room = dungeon.room(state.guardianRoomId)
            if (room != null && state.revivals < maxRevivals) {
                state.revivals++
                plugin.logger.warning("Key guardian of dungeon ${dungeon.id} stopped existing without" +
                    " granting the key; reviving it (attempt ${state.revivals} of $maxRevivals).")
                plugin.mobs.reviveRoleRoom(dungeon, room)
                for (player in dungeon.world.players) {
                    plugin.messages.send(player, "door-guardian-revived")
                }
                continue
            }
            plugin.logger.severe("Key guardian of dungeon ${dungeon.id} could not be revived; opening" +
                " the sealed door so the run can continue.")
            for (player in dungeon.world.players) {
                plugin.messages.send(player, "door-guardian-lost")
            }
            open(dungeon, state, true)
        }
    }

    private fun barrierMaterial(): Material {
        val raw = plugin.config.getString("door.material", "IRON_BARS")
        val material = raw?.let { Material.matchMaterial(it.trim().uppercase(Locale.ROOT)) }
        return if (material == null || !material.isBlock || material.isAir) Material.IRON_BARS else material
    }

    private class DoorState(
        val dungeonId: String,
        val worldName: String,
        val guardianRoomId: String,
        blocks: Set<BlockVector>,
        val displayId: UUID?,
        val centre: Location,
        val material: Material
    ) {
        val blocks: Set<BlockVector> = blocks.toSet()
        val lastDeny = HashMap<UUID, Long>()
        var open = false
        var revivals = 0
        var quietChecks = 0
    }

    companion object {
        private fun seal(world: World, x: Int, y: Int, z: Int, material: Material, blocks: MutableSet<BlockVector>) {
            val block = world.getBlockAt(x, y, z)
            if (!block.isPassable) return
            // Physics on, so a connecting block such as iron bars joins up
            // into one gate instead of standing as loose posts.
            block.setType(material, true)
            blocks.add(BlockVector(x, y, z))
        }

        /** Internal: the room gates speak the same audio language. */
        internal fun sound(raw: String?): Sound? {
            if (raw.isNullOrBlank()) return null
            return Registry.SOUNDS.get(NamespacedKey.minecraft(raw.trim().lowercase(Locale.ROOT).replace('_', '.')))
        }
    }
}
