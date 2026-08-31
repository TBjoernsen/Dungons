package nl.riddernix.dungeonforge.room;

import org.bukkit.block.BlockFace;

/** A prefab doorway marker resolved into a world position and its outward face. */
public record DungeonDoorway(int x, int y, int z, BlockFace wall, BlockFace facing) {
}
