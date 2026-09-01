package nl.riddernix.dungeonplugin.room

import net.kyori.adventure.text.Component
import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.generation.Bounds
import nl.riddernix.dungeonplugin.generation.DungeonLayout
import nl.riddernix.dungeonplugin.util.Messages
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID

/** Owns live dungeon room metadata and periodic player-to-room detection. */
class DungeonRoomRegistry(private val plugin: DungeonPlugin) {

    private val byWorld = HashMap<String, DungeonInstance>()
    private val playerAreas = HashMap<UUID, PlayerArea>()

    @JvmOverloads
    fun register(world: World, layout: DungeonLayout, markers: Map<String, List<DungeonMarker>>,
                 doorways: Map<String, List<DungeonDoorway>> = emptyMap(),
                 playableBounds: Map<String, Bounds> = emptyMap(),
                 playerSpawns: Map<String, DungeonSpecialMarker> = emptyMap(),
                 bossSpawns: Map<String, DungeonSpecialMarker> = emptyMap(),
                 traps: Map<String, DungeonTrap> = emptyMap(),
                 prefabFiles: Map<String, String> = emptyMap()): DungeonInstance {
        val instance = DungeonInstance(world, layout, markers, doorways, playableBounds,
            playerSpawns, bossSpawns, traps, prefabFiles)
        // Fixed here because this runs after the world is built and before any
        // room is populated: the one moment the party is known and no mob has
        // been scaled yet. A solo run has no party, which is the 1 default.
        plugin.parties.partyForWorld(world.name)?.let { party -> instance.lockPartySize(party.members.size) }
        byWorld[world.name] = instance
        // The world is fully built by the time an instance registers, so the
        // key gate's barrier and the trap snapshots can go straight in.
        plugin.doors.install(instance)
        plugin.traps.install(instance)
        return instance
    }

    fun dungeon(world: World?): DungeonInstance? = world?.let { byWorld[it.name] }

    fun dungeon(worldName: String): DungeonInstance? = byWorld[worldName]

    fun room(player: Player): DungeonRoom? {
        val current = playerAreas[player.uniqueId] ?: return null
        val roomId = current.roomId ?: return null
        return dungeon(current.worldName)?.room(roomId)
    }

    /** The schematic a room was built from, so a wrong room names its own file. */
    private fun prefabTag(player: Player, prefab: String?): Component =
        Component.text(prefab ?: "procedural")

    fun remove(worldName: String) {
        byWorld.remove(worldName)
        playerAreas.entries.removeIf { it.value.worldName == worldName }
    }

    fun forget(player: Player) {
        playerAreas.remove(player.uniqueId)
    }

    /** Checks every online player; called by a configurable repeating task. */
    fun scanPlayers() {
        for (player in Bukkit.getOnlinePlayers()) {
            refresh(player)
        }
    }

    /** Immediately checks a player after a teleport or rejoin. */
    fun refresh(player: Player) {
        val instance = byWorld[player.world.name]
        val previous = playerAreas[player.uniqueId]
        val next = instance?.let { findRoom(it, player) }
        val nextCorridor = if (next == null && instance != null) findCorridor(instance, player) else null
        if (previous?.roomId != null && (instance == null || previous.worldName != instance.world.name ||
                next == null || previous.roomId != next.id)) {
            val oldDungeon = byWorld[previous.worldName]
            if (oldDungeon != null) {
                val oldRoom = oldDungeon.room(previous.roomId)
                if (oldRoom != null) {
                    Bukkit.getPluginManager().callEvent(DungeonRoomLeaveEvent(player, oldDungeon, oldRoom))
                }
            }
        }
        val changedDungeon = previous == null || instance == null || previous.worldName != instance.world.name
        if (instance != null && nextCorridor != null && (changedDungeon || nextCorridor.id != previous.corridorId)) {
            val fromId = previous?.lastRoomId
            val comingFrom = fromId?.let { instance.room(it) }
            Bukkit.getPluginManager().callEvent(DungeonCorridorEnterEvent(player, instance, nextCorridor, comingFrom))
        }
        if (instance != null && next != null && (changedDungeon || next.id != previous.roomId)) {
            // The public event goes first, while "has anyone been here" is
            // still answerable: the internal event below is what spawns mobs.
            plugin.events.fireRoomEnter(plugin.snapshots.of(instance), player, plugin.snapshots.of(next),
                !plugin.mobs.isRoomVisited(instance.id, next.id))
            Bukkit.getPluginManager().callEvent(DungeonRoomEnterEvent(player, instance, next))
            // After the internal event: the mob manager has queued this room's
            // spawns by then, so the gate sees a live count to decide on.
            plugin.gates.onRoomEntered(instance, next, player)
            // A composed room reads as its role, because "branch" says nothing
            // about whether you walked into the parkour run or the guardian's
            // lair - and the role is what the layout actually promised.
            val type = next.type.name.lowercase(Locale.ROOT)
            val prefab = instance.prefabFile(next.id)
            plugin.messages.send(player, "room-entered", Messages.ph("id", next.id),
                Messages.ph("type", if (next.role == null) type else "$type/${next.role}"),
                Messages.ph("role", next.role ?: "none"),
                Messages.ph("prefab", prefabTag(player, prefab)),
                Messages.ph("depth", next.depth))
        }
        if (instance == null) {
            playerAreas.remove(player.uniqueId)
        } else {
            val lastRoom = next?.id ?: previous?.lastRoomId
            playerAreas[player.uniqueId] = PlayerArea(instance.world.name,
                next?.id, nextCorridor?.id, lastRoom)
        }
    }

    private data class PlayerArea(val worldName: String, val roomId: String?, val corridorId: String?, val lastRoomId: String?)

    companion object {
        private fun findRoom(instance: DungeonInstance, player: Player): DungeonRoom? {
            val x = player.location.blockX
            val y = player.location.blockY
            val z = player.location.blockZ
            return instance.rooms
                .filter { it.bounds.contains(x, y, z) }
                .maxByOrNull { it.depth }
        }

        private fun findCorridor(instance: DungeonInstance, player: Player): DungeonCorridor? {
            val x = player.location.blockX
            val y = player.location.blockY
            val z = player.location.blockZ
            return instance.corridors.firstOrNull { it.contains(x, y, z) }
        }
    }
}
