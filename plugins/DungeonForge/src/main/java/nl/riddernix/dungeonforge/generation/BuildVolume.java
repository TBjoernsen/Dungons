package nl.riddernix.dungeonforge.generation;

import org.bukkit.block.data.BlockData;

/** One cursor-addressable block operation. */
public record BuildVolume(Bounds bounds, BlockData data) implements BuildOperation {
    public long positions() {
        return bounds.volume();
    }

    @Override
    public void place(org.bukkit.World world, long cursor) {
        int area = bounds.sizeX() * bounds.sizeZ();
        int localY = (int) (cursor / area);
        int remainder = (int) (cursor % area);
        int localX = remainder % bounds.sizeX();
        int localZ = remainder / bounds.sizeX();
        world.getBlockAt(bounds.minX() + localX, bounds.minY() + localY, bounds.minZ() + localZ)
                .setBlockData(data, false);
    }
}
