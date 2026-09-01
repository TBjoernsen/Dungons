package nl.riddernix.dungeonplugin.room

import nl.riddernix.dungeonplugin.generation.DungeonLayout
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.function.Consumer

/**
 * Converts configured structure marker blocks into immutable room metadata.
 *
 * The scanner deliberately runs after the tick-spread block build and also
 * uses a cursor itself. This same layer works for generated placeholders and
 * later for hand-built prefab rooms.
 */
class DungeonMarkerScanner private constructor(
    private val world: World,
    rooms: List<DungeonLayout.Room>,
    categories: Map<Material, String>,
    blocksPerTick: Int,
    private val onComplete: Consumer<Map<String, List<DungeonMarker>>>
) : BukkitRunnable() {

    private val rooms: List<DungeonLayout.Room> = rooms.toList()
    private val categories: Map<Material, String> = categories.toMap()
    private val blocksPerTick: Int = maxOf(256, blocksPerTick)
    private val found = HashMap<String, MutableList<DungeonMarker>>()
    private var roomIndex = 0
    private var x = 0
    private var y = 0
    private var z = 0

    init {
        if (rooms.isNotEmpty()) resetCursor(rooms.first())
    }

    override fun run() {
        var budget = blocksPerTick
        while (budget-- > 0 && roomIndex < rooms.size) {
            val room = rooms[roomIndex]
            val material = world.getBlockAt(x, y, z).type
            val category = categories[material]
            if (category != null) {
                found.getOrPut(room.id) { ArrayList() }
                    .add(DungeonMarker(category, x, y, z))
                world.getBlockAt(x, y, z).setType(Material.AIR, false)
            }
            advance(room)
        }
        if (roomIndex >= rooms.size) {
            cancel()
            val immutable = HashMap<String, List<DungeonMarker>>()
            found.forEach { (roomId, markers) -> immutable[roomId] = markers.toList() }
            onComplete.accept(immutable.toMap())
        }
    }

    private fun advance(room: DungeonLayout.Room) {
        x++
        if (x <= room.bounds.maxX - 1) return
        x = room.bounds.minX + 1
        z++
        if (z <= room.bounds.maxZ - 1) return
        z = room.bounds.minZ + 1
        y++
        if (y <= room.bounds.maxY - 1) return
        roomIndex++
        if (roomIndex < rooms.size) resetCursor(rooms[roomIndex])
    }

    private fun resetCursor(room: DungeonLayout.Room) {
        x = room.bounds.minX + 1
        y = room.bounds.minY + 1
        z = room.bounds.minZ + 1
    }

    companion object {
        fun start(plugin: Plugin, world: World, layouts: List<DungeonLayout>, config: FileConfiguration,
                  blocksPerTick: Int, onComplete: Consumer<Map<String, List<DungeonMarker>>>) {
            val rooms = layouts.flatMap { it.rooms }
            val categories = HashMap<Material, String>()
            for (definition in DungeonMarkerDefinitions.read(config, plugin)) {
                categories[definition.material] = definition.category
            }
            DungeonMarkerScanner(world, rooms, categories, blocksPerTick, onComplete)
                .runTaskTimer(plugin, 1L, 1L)
        }
    }
}
