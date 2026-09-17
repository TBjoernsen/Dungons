package nl.riddernix.dungeonplugin.generation

import nl.riddernix.dungeonplugin.generation.TemplateGeometry.Dir
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.WallSign

/**
 * Generates a stand-in room for a slot whose pool has no valid file yet: the
 * exact exterior of its size class, an unmistakable missing-texture
 * checkerboard, doorway openings exactly where the layout needs them, and a
 * sign naming the slot role above each entrance. The dungeon must stay fully
 * playable before a single real room has been built.
 */
object PlaceholderShell {

    /** A doorway the layout requires, in the shell's local coordinates. */
    data class ShellDoor(val face: Dir, val cross: Int, val floor: Int)

    data class Sign(val x: Int, val y: Int, val z: Int, val text: String)

    class Shell(val operation: BuildOperation, signs: List<Sign>) {
        val signs: List<Sign> = signs.toList()
    }

    /**
     * Builds the shell as world-space block entries.
     *
     * The floor follows the doors: with every door on one level the room is
     * flat; with two levels (a stairs link, the descending great hall) the
     * high side keeps a platform and full-width steps drop one block per row
     * towards the low side, entered from [highFace].
     */
    fun build(sizeX: Int, sizeY: Int, sizeZ: Int, origin: TemplateGeometry.Origin, doors: List<ShellDoor>,
              openingWidth: Int, openingHeight: Int, materials: List<Material>, roleLabel: String,
              highFace: Dir?): Shell {
        val floors = doors.map { it.floor }.distinct().sorted()
        val lowFloor = floors.firstOrNull() ?: 1
        val highFloor = floors.lastOrNull() ?: 1
        val axis = highFace ?: doors.maxByOrNull { it.floor }?.face ?: Dir.NORTH

        val entries = ArrayList<BlockListOperation.Entry>()
        val signs = ArrayList<Sign>()
        val palette = materials.ifEmpty { listOf(Material.MAGENTA_CONCRETE, Material.BLACK_CONCRETE) }
            .map(Material::createBlockData)

        fun place(x: Int, y: Int, z: Int, data: BlockData? = null) {
            entries.add(BlockListOperation.Entry(origin.x + x, origin.y + y, origin.z + z,
                data ?: palette[Math.floorMod(x + y + z, palette.size)]))
        }

        // Every doorway opening as a set of local cells left out of the walls.
        val openings = HashSet<Long>()
        val half = (openingWidth - 1) / 2
        for (door in doors) {
            for (dy in 0 until openingHeight) {
                for (dcross in -half..half) {
                    val y = door.floor + dy
                    when (door.face) {
                        Dir.NORTH -> openings.add(pack(door.cross + dcross, y, 0))
                        Dir.SOUTH -> openings.add(pack(door.cross + dcross, y, sizeZ - 1))
                        Dir.WEST -> openings.add(pack(0, y, door.cross + dcross))
                        Dir.EAST -> openings.add(pack(sizeX - 1, y, door.cross + dcross))
                    }
                }
            }
        }

        // The interior floor height at one walking position: the high level
        // holds until the steps begin, then drops one block per row.
        val interiorSpan = distanceSpan(axis, sizeX, sizeZ)
        val drop = highFloor - lowFloor
        val plateau = maxOf(2, (interiorSpan - drop) / 2)
        fun floorAt(x: Int, z: Int): Int {
            if (drop == 0) return lowFloor
            val travelled = travelled(axis, x, z, sizeX, sizeZ)
            return (highFloor - maxOf(0, travelled - plateau)).coerceAtLeast(lowFloor)
        }

        for (x in 0 until sizeX) {
            for (z in 0 until sizeZ) {
                val wall = x == 0 || x == sizeX - 1 || z == 0 || z == sizeZ - 1
                if (wall) {
                    for (y in 0 until sizeY) {
                        if (pack(x, y, z) in openings) continue
                        place(x, y, z)
                    }
                    continue
                }
                // Solid fill up to one below the walking level, so the raised
                // side of a stairs shell has no hollow underside to fall into.
                val top = floorAt(x, z) - 1
                for (y in 0..top) place(x, y, z)
                place(x, sizeY - 1, z)
            }
        }

        for (door in doors) {
            val inward = door.face.opposite()
            val signY = door.floor + openingHeight
            val (signX, signZ) = when (door.face) {
                Dir.NORTH -> door.cross to 1
                Dir.SOUTH -> door.cross to sizeZ - 2
                Dir.WEST -> 1 to door.cross
                Dir.EAST -> sizeX - 2 to door.cross
            }
            if (signY < sizeY - 1) {
                place(signX, signY, signZ, wallSign(inward))
                signs.add(Sign(origin.x + signX, origin.y + signY, origin.z + signZ, roleLabel))
            }
        }
        return Shell(BlockListOperation(entries), signs)
    }

    private fun wallSign(facing: Dir): BlockData {
        val data = Material.OAK_WALL_SIGN.createBlockData() as WallSign
        data.facing = when (facing) {
            Dir.NORTH -> org.bukkit.block.BlockFace.NORTH
            Dir.SOUTH -> org.bukkit.block.BlockFace.SOUTH
            Dir.EAST -> org.bukkit.block.BlockFace.EAST
            Dir.WEST -> org.bukkit.block.BlockFace.WEST
        }
        return data
    }

    private fun distanceSpan(axis: Dir, sizeX: Int, sizeZ: Int): Int =
        if (axis == Dir.NORTH || axis == Dir.SOUTH) sizeZ - 2 else sizeX - 2

    /** How far a position lies from the high-side wall, walking inward from [axis]. */
    private fun travelled(axis: Dir, x: Int, z: Int, sizeX: Int, sizeZ: Int): Int = when (axis) {
        Dir.NORTH -> z - 1
        Dir.SOUTH -> sizeZ - 2 - z
        Dir.WEST -> x - 1
        Dir.EAST -> sizeX - 2 - x
    }

    private fun pack(x: Int, y: Int, z: Int): Long =
        (x.toLong() and 0xFFFFF shl 40) or (y.toLong() and 0xFFFFF shl 20) or (z.toLong() and 0xFFFFF)
}
