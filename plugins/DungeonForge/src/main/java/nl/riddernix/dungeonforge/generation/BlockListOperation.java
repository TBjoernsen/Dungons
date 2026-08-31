package nl.riddernix.dungeonforge.generation;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.List;

/** A sparse, ordered block list used for transformed schematic rooms. */
public final class BlockListOperation implements BuildOperation {
    private final List<Entry> entries;

    public BlockListOperation(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    @Override
    public long positions() {
        return entries.size();
    }

    @Override
    public void place(World world, long cursor) {
        Entry entry = entries.get((int) cursor);
        world.getBlockAt(entry.x(), entry.y(), entry.z()).setBlockData(entry.data(), false);
    }

    public record Entry(int x, int y, int z, BlockData data) {
    }
}
