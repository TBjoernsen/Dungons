package nl.riddernix.dungeonforge.build;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.function.IntConsumer;

/**
 * Places the test box, spread across multiple ticks.
 *
 * <p>This is the same pattern the real dungeon uses: never cram 110,000
 * blocks into a single tick, because the server would stall for half a second
 * and you would see a TPS dip. Instead we keep a cursor and take a bite each
 * tick.</p>
 *
 * <p>The cursor runs from 0 to size^3. From that single number we derive x, y
 * and z, so all progress fits in one long.</p>
 */
public final class BoxBuilder extends BukkitRunnable {

    private final World world;
    private final BoxSpec spec;
    private final IntConsumer onProgress;
    private final Runnable onComplete;

    private final long total;
    private final int area;

    private long cursor;
    private long placed;
    private long startNanos;
    private int lastPercent = -1;

    private BoxBuilder(World world, BoxSpec spec, IntConsumer onProgress, Runnable onComplete) {
        this.world = world;
        this.spec = spec;
        this.onProgress = onProgress;
        this.onComplete = onComplete;
        this.total = spec.volume();
        this.area = spec.size() * spec.size();
    }

    /**
     * Starts the build.
     *
     * @param onProgress receives the percentage (0-100), only when it changes
     * @param onComplete runs on the main thread once everything is placed
     */
    public static BoxBuilder start(Plugin plugin, World world, BoxSpec spec,
                                   IntConsumer onProgress, Runnable onComplete) {
        BoxBuilder builder = new BoxBuilder(world, spec, onProgress, onComplete);
        builder.startNanos = System.nanoTime();
        builder.runTaskTimer(plugin, 1L, 1L);
        return builder;
    }

    @Override
    public void run() {
        int budget = spec.blocksPerTick();
        int steps = 0;

        while (cursor < total && steps < budget) {
            int y = (int) (cursor / area);
            int rest = (int) (cursor % area);
            int x = rest % spec.size();
            int z = rest / spec.size();
            cursor++;
            steps++;

            BlockData data = blockAt(x, y, z);
            if (data == null) {
                continue;
            }
            world.getBlockAt(spec.originX() + x, spec.originY() + y, spec.originZ() + z)
                    .setBlockData(data, false); // false = skip physics updates, far faster
            placed++;
        }

        int percent = (int) (cursor * 100L / total);
        if (percent != lastPercent) {
            lastPercent = percent;
            onProgress.accept(percent);
        }

        if (cursor >= total) {
            cancel();
            onComplete.run();
        }
    }

    /**
     * Decides which block belongs at a position. Coordinates are relative to
     * the box corner, so 0..size-1.
     *
     * @return the block, or {@code null} if nothing should be placed
     */
    private BlockData blockAt(int x, int y, int z) {
        int max = spec.size() - 1;
        boolean onShell = x == 0 || x == max || y == 0 || y == max || z == 0 || z == max;

        if (spec.hollow() && !onShell) {
            return null; // interior stays empty
        }

        if (y == 0) {
            if (spec.light() != null && isLightPosition(x, z)) {
                return spec.light();
            }
            return spec.floor();
        }
        return spec.wall();
    }

    private boolean isLightPosition(int x, int z) {
        int spacing = spec.lightSpacing();
        int offset = spacing / 2;
        return x % spacing == offset && z % spacing == offset;
    }

    /** Number of blocks actually placed (skips not counted). */
    public long placedBlocks() {
        return placed;
    }

    /** Elapsed time in milliseconds. */
    public long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
