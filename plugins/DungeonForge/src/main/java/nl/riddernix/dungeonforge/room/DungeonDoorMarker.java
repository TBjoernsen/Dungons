package nl.riddernix.dungeonforge.room;

import org.bukkit.block.BlockFace;

/**
 * The centre line of a doorway marker group. This is separate from the air
 * opening below it: prefabs use the marker group as their exact connector
 * reference while the opening remains the player-sized passage.
 */
public record DungeonDoorMarker(int x, int y, int z, BlockFace facing, int width) {
    public DungeonDoorMarker {
        if (width < 1 || (width & 1) == 0) {
            throw new IllegalArgumentException("Doorway marker width must be a positive odd number.");
        }
    }
}
