package nl.riddernix.dungeonplugin.generation

import org.bukkit.World
import org.bukkit.block.data.BlockData

/** One cursor-addressable world edit performed by [DungeonLayoutBuilder]. */
interface BuildOperation {
    fun positions(): Long
    fun place(world: World, cursor: Long)
}

/** One cursor-addressable block operation. */
data class BuildVolume(val bounds: Bounds, val data: BlockData) : BuildOperation {

    override fun positions(): Long = bounds.volume()

    override fun place(world: World, cursor: Long) {
        val area = bounds.sizeX() * bounds.sizeZ()
        val localY = (cursor / area).toInt()
        val remainder = (cursor % area).toInt()
        val localX = remainder % bounds.sizeX()
        val localZ = remainder / bounds.sizeX()
        world.getBlockAt(bounds.minX + localX, bounds.minY + localY, bounds.minZ + localZ)
            .setBlockData(data, false)
    }
}

/** A sparse, ordered block list used for transformed schematic rooms. */
class BlockListOperation(entries: List<Entry>) : BuildOperation {

    private val entries: List<Entry> = entries.toList()

    override fun positions(): Long = entries.size.toLong()

    override fun place(world: World, cursor: Long) {
        val entry = entries[cursor.toInt()]
        world.getBlockAt(entry.x, entry.y, entry.z).setBlockData(entry.data, false)
    }

    data class Entry(val x: Int, val y: Int, val z: Int, val data: BlockData)
}
