package nl.riddernix.dungeonplugin.build

import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.function.IntConsumer

/**
 * Places the test box, spread across multiple ticks.
 *
 * The same pattern the real dungeon uses: never cram 110,000 blocks into a
 * single tick, because the server would stall for half a second and you would
 * see a TPS dip. Instead we keep a cursor and take a bite each tick.
 *
 * The cursor runs from 0 to size^3. From that single number we derive x, y and
 * z, so all progress fits in one long.
 */
class BoxBuilder private constructor(
    private val world: World,
    private val spec: BoxSpec,
    private val onProgress: IntConsumer,
    private val onComplete: Runnable
) : BukkitRunnable() {

    private val total: Long = spec.volume()
    private val area: Int = spec.size * spec.size

    private var cursor: Long = 0
    private var placed: Long = 0
    private var startNanos: Long = 0
    private var lastPercent: Int = -1

    override fun run() {
        val budget = spec.blocksPerTick
        var steps = 0

        while (cursor < total && steps < budget) {
            val y = (cursor / area).toInt()
            val rest = (cursor % area).toInt()
            val x = rest % spec.size
            val z = rest / spec.size
            cursor++
            steps++

            val data = blockAt(x, y, z) ?: continue
            world.getBlockAt(spec.originX + x, spec.originY + y, spec.originZ + z)
                .setBlockData(data, false) // false = skip physics updates, far faster
            placed++
        }

        val percent = (cursor * 100L / total).toInt()
        if (percent != lastPercent) {
            lastPercent = percent
            onProgress.accept(percent)
        }

        if (cursor >= total) {
            cancel()
            onComplete.run()
        }
    }

    /**
     * Decides which block belongs at a position. Coordinates are relative to
     * the box corner, so 0..size-1. Returns null if nothing should be placed.
     */
    private fun blockAt(x: Int, y: Int, z: Int): BlockData? {
        val max = spec.size - 1
        val onShell = x == 0 || x == max || y == 0 || y == max || z == 0 || z == max

        if (spec.hollow && !onShell) {
            return null // interior stays empty
        }

        if (y == 0) {
            if (spec.light != null && isLightPosition(x, z)) {
                return spec.light
            }
            return spec.floor
        }
        return spec.wall
    }

    private fun isLightPosition(x: Int, z: Int): Boolean {
        val spacing = spec.lightSpacing
        val offset = spacing / 2
        return x % spacing == offset && z % spacing == offset
    }

    /** Number of blocks actually placed (skips not counted). */
    fun placedBlocks(): Long = placed

    /** Elapsed time in milliseconds. */
    fun elapsedMillis(): Long = (System.nanoTime() - startNanos) / 1_000_000L

    companion object {
        /**
         * Starts the build.
         *
         * @param onProgress receives the percentage (0-100), only when it changes
         * @param onComplete runs on the main thread once everything is placed
         */
        fun start(plugin: Plugin, world: World, spec: BoxSpec,
                  onProgress: IntConsumer, onComplete: Runnable): BoxBuilder {
            val builder = BoxBuilder(world, spec, onProgress, onComplete)
            builder.startNanos = System.nanoTime()
            builder.runTaskTimer(plugin, 1L, 1L)
            return builder
        }
    }
}
