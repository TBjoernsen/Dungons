package nl.riddernix.dungeonplugin.fx

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.entity.LivingEntity
import java.util.Locale
import java.util.logging.Level

/** Looks up the scripted entrance a boss's `summoning.animation` asks for. */
object SpawnAnimations {

    private val NAMES = listOf("rift_tear")

    fun names(): List<String> = NAMES

    /**
     * Runs one step and reports whether the animation is still healthy.
     *
     * An exception thrown inside a repeating task cancels that task. For an
     * animation that means the shards freeze mid-pose and the boss stays
     * wherever it was put, with nothing on screen explaining why. Catching it
     * here turns that silence into a console entry and a clean removal.
     *
     * @return false when the animation failed and was removed
     */
    fun tickSafely(plugin: DungeonPlugin, animation: SpawnAnimation, ticks: Int, durationTicks: Int): Boolean {
        return try {
            animation.tick(ticks, durationTicks)
            true
        } catch (exception: RuntimeException) {
            plugin.logger.log(Level.SEVERE, "Boss entrance animation failed on tick $ticks" +
                " and was removed (${describe(animation)}).", exception)
            abortQuietly(plugin, animation)
            false
        }
    }

    /** @return false when the animation could not start and was removed */
    fun beginSafely(plugin: DungeonPlugin, animation: SpawnAnimation): Boolean {
        return try {
            animation.begin()
            plugin.logger.info("Boss entrance started - ${describe(animation)}")
            true
        } catch (exception: RuntimeException) {
            plugin.logger.log(Level.SEVERE, "Boss entrance animation failed to start and was removed.", exception)
            abortQuietly(plugin, animation)
            false
        }
    }

    fun describe(animation: SpawnAnimation): String = try {
        animation.describe()
    } catch (exception: RuntimeException) {
        "description unavailable: $exception"
    }

    private fun abortQuietly(plugin: DungeonPlugin, animation: SpawnAnimation) {
        try {
            animation.abort()
        } catch (exception: RuntimeException) {
            plugin.logger.log(Level.SEVERE, "Boss entrance animation could not be cleaned up.", exception)
        }
    }

    /**
     * @param allowRise whether the boss may be buried and raised, which is
     *                  only safe while the summoning sequence keeps it
     *                  invulnerable
     * @return the animation, or `null` for "none" and for unknown names
     */
    fun create(plugin: DungeonPlugin, boss: LivingEntity, name: String?, allowRise: Boolean): SpawnAnimation? {
        if (name.isNullOrBlank()) {
            return null
        }
        val key = name.trim().lowercase(Locale.ROOT).replace('-', '_')
        return when (key) {
            "rift_tear" -> RiftTearAnimation(plugin, boss, allowRise)
            "none" -> null
            else -> {
                plugin.logger.warning("Unknown boss summoning animation '$name'. Known animations: " +
                    NAMES.joinToString(", ") + ". The default particle pulse is used instead.")
                null
            }
        }
    }
}
