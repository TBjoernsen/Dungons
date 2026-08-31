package nl.riddernix.dungeonforge.room;

import org.bukkit.util.BlockVector;

import java.util.List;
import java.util.Set;

/**
 * One room's authored trap floor, in world coordinates after placement.
 *
 * <p>Each column's {@code topY} is the visible walking-floor block that
 * vanishes; the drop carves everything from there down to the bottom of the
 * room, so the hole opens into the void. Every pressure plate recorded for
 * the room triggers the same single trap.</p>
 */
public record DungeonTrap(String roomId, List<Column> columns, Set<BlockVector> plates) {

    public DungeonTrap {
        columns = List.copyOf(columns);
        plates = Set.copyOf(plates);
    }

    public record Column(int x, int topY, int z) { }

    /**
     * How far the collapse climbs above one marked floor block.
     *
     * <p>The single definition of the rule, so the count reported by
     * {@code /dungeon rooms} and the blocks actually removed at runtime can
     * never drift apart. Straight up from the marker, through anything that
     * is not air, and stopping at the first gap: whatever stands on the floor
     * goes down with it, while a platform floating above the trap keeps its
     * gap and stays put. {@code maximumRise} is the ceiling on how much of a
     * tall pillar can be taken along.</p>
     *
     * @param solidAbove tests whether the block at that height is not air
     * @return the number of blocks above {@code topY} that fall
     */
    public static int rise(java.util.function.IntPredicate solidAbove, int topY, int maximumRise) {
        int rise = 0;
        while (rise < maximumRise && solidAbove.test(topY + rise + 1)) {
            rise++;
        }
        return rise;
    }
}
