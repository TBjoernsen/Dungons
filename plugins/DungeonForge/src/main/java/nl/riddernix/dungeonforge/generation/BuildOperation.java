package nl.riddernix.dungeonforge.generation;

import org.bukkit.World;

/** One cursor-addressable world edit performed by {@link DungeonLayoutBuilder}. */
public interface BuildOperation {
    long positions();
    void place(World world, long cursor);
}
