package nl.riddernix.dungeonplugin.generation

import org.bukkit.World
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.function.Consumer
import java.util.function.IntConsumer

/** Builds all planned volumes incrementally, never consuming an entire tick. */
class DungeonLayoutBuilder private constructor(
    private val world: World,
    private val volumes: List<BuildOperation>,
    private val blocksPerTick: Int,
    private val onProgress: IntConsumer,
    private val onComplete: Consumer<Result>
) : BukkitRunnable() {

    private val totalPositions: Long = volumes.sumOf(BuildOperation::positions)

    private var volumeIndex = 0
    private var cursorInVolume = 0L
    private var processed = 0L
    private var changed = 0L
    private var startNanos = 0L
    private var lastPercent = -1

    override fun run() {
        var budget = blocksPerTick
        while (volumeIndex < volumes.size && budget-- > 0) {
            val volume = volumes[volumeIndex]
            volume.place(world, cursorInVolume)
            changed++
            processed++
            cursorInVolume++
            if (cursorInVolume >= volume.positions()) {
                volumeIndex++
                cursorInVolume = 0
            }
        }

        val percent = if (totalPositions == 0L) 100 else (processed * 100L / totalPositions).toInt()
        if (percent != lastPercent) {
            lastPercent = percent
            onProgress.accept(percent)
        }
        if (volumeIndex >= volumes.size) {
            cancel()
            onComplete.accept(Result(changed, (System.nanoTime() - startNanos) / 1_000_000L))
        }
    }

    data class Result(val blocksChanged: Long, val elapsedMillis: Long)

    companion object {
        fun start(plugin: Plugin, world: World, volumes: List<BuildOperation>,
                  blocksPerTick: Int, onProgress: IntConsumer,
                  onComplete: Consumer<Result>): DungeonLayoutBuilder {
            val builder = DungeonLayoutBuilder(world, volumes, maxOf(256, blocksPerTick),
                onProgress, onComplete)
            builder.startNanos = System.nanoTime()
            builder.runTaskTimer(plugin, 1L, 1L)
            return builder
        }
    }
}
