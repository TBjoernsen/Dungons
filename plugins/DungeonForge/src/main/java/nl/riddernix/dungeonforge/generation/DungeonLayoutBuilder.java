package nl.riddernix.dungeonforge.generation;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Builds all planned volumes incrementally, never consuming an entire tick. */
public final class DungeonLayoutBuilder extends BukkitRunnable {

    private final World world;
    private final List<? extends BuildOperation> volumes;
    private final int blocksPerTick;
    private final IntConsumer onProgress;
    private final Consumer<Result> onComplete;
    private final long totalPositions;

    private int volumeIndex;
    private long cursorInVolume;
    private long processed;
    private long changed;
    private long startNanos;
    private int lastPercent = -1;

    private DungeonLayoutBuilder(World world, List<? extends BuildOperation> volumes, int blocksPerTick,
                                 IntConsumer onProgress, Consumer<Result> onComplete) {
        this.world = world;
        this.volumes = volumes;
        this.blocksPerTick = blocksPerTick;
        this.onProgress = onProgress;
        this.onComplete = onComplete;
        this.totalPositions = volumes.stream().mapToLong(BuildOperation::positions).sum();
    }

    public static DungeonLayoutBuilder start(Plugin plugin, World world, List<? extends BuildOperation> volumes,
                                             int blocksPerTick, IntConsumer onProgress,
                                             Consumer<Result> onComplete) {
        DungeonLayoutBuilder builder = new DungeonLayoutBuilder(world, volumes, Math.max(256, blocksPerTick),
                onProgress, onComplete);
        builder.startNanos = System.nanoTime();
        builder.runTaskTimer(plugin, 1L, 1L);
        return builder;
    }

    @Override
    public void run() {
        int budget = blocksPerTick;
        while (volumeIndex < volumes.size() && budget-- > 0) {
            BuildOperation volume = volumes.get(volumeIndex);
            volume.place(world, cursorInVolume);
            changed++;
            processed++;
            cursorInVolume++;
            if (cursorInVolume >= volume.positions()) {
                volumeIndex++;
                cursorInVolume = 0;
            }
        }

        int percent = totalPositions == 0 ? 100 : (int) (processed * 100L / totalPositions);
        if (percent != lastPercent) {
            lastPercent = percent;
            onProgress.accept(percent);
        }
        if (volumeIndex >= volumes.size()) {
            cancel();
            onComplete.accept(new Result(changed, (System.nanoTime() - startNanos) / 1_000_000L));
        }
    }

    public record Result(long blocksChanged, long elapsedMillis) {
    }
}
