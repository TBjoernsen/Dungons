package nl.riddernix.dungeonplugin.fx

import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.util.Messages
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import java.util.Locale
import java.util.UUID

/**
 * Plays a boss entrance where a player stands, without generating a dungeon.
 *
 * The stand-in is the real boss entity of the theme, at the theme's own scale
 * and summoning duration, so radius, rise depth and timing can be tuned
 * against what the fight will actually look like. It is tagged as a testing
 * mob, which means a stray one is swept up by `/dungeon summon clear`.
 */
class AnimationPreview(private val plugin: DungeonPlugin) {

    private val running = HashMap<UUID, UUID>()

    fun start(player: Player, name: String, requestedTheme: String?): Result {
        if (SpawnAnimations.names().none { it.equals(name, ignoreCase = true) }) {
            return Result(Status.UNKNOWN_ANIMATION, null, 0, null)
        }
        if (player.uniqueId in running) {
            return Result(Status.ALREADY_RUNNING, null, 0, null)
        }
        val theme = if (requestedTheme.isNullOrBlank()) themeUsing(name) else requestedTheme.lowercase(Locale.ROOT)
        if (theme == null || plugin.config.getConfigurationSection("mobs.themes.$theme") == null) {
            return Result(Status.UNKNOWN_THEME, theme, 0, null)
        }

        val bossPath = "mobs.themes.$theme.boss."
        val type = entityType(plugin.config.getString(bossPath + "type", "WARDEN"))
            ?: return Result(Status.INVALID_BOSS, theme, 0, null)
        val duration = maxOf(1, plugin.config.getInt(bossPath + "summoning.duration-ticks",
            plugin.config.getInt("mobs.boss-summoning.duration-ticks", 50)))
        val invulnerable = plugin.config.getBoolean(bossPath + "summoning.invulnerable",
            plugin.config.getBoolean("mobs.boss-summoning.invulnerable", true))

        val origin = player.location.block.location.add(0.5, 0.0, 0.5)
        if (origin.world!!.difficulty == Difficulty.PEACEFUL) {
            // Said up front, because the stand-in will not survive the tick.
            plugin.messages.send(player, "animate-peaceful", Messages.ph("entity", type.name))
        }
        val spawned = origin.world!!.spawnEntity(origin, type)
        if (spawned !is LivingEntity) {
            spawned.remove()
            return Result(Status.INVALID_BOSS, theme, 0, null)
        }
        prepare(spawned, theme)

        val animation = SpawnAnimations.create(plugin, spawned, name, invulnerable)
        if (animation == null) {
            spawned.remove()
            return Result(Status.UNKNOWN_ANIMATION, theme, 0, null)
        }
        running[player.uniqueId] = spawned.uniqueId
        if (!SpawnAnimations.beginSafely(plugin, animation)) {
            running.remove(player.uniqueId)
            spawned.remove()
            return Result(Status.FAILED, theme, 0, null)
        }
        run(player.uniqueId, spawned, animation, duration)
        return Result(Status.SUCCESS, theme, duration, SpawnAnimations.describe(animation))
    }

    /** Removes this player's preview, whether it is mid-animation or lingering. */
    fun stop(player: Player): Boolean {
        val standId = running.remove(player.uniqueId) ?: return false
        Bukkit.getEntity(standId)?.remove()
        return true
    }

    /** Removes every preview, used when the plugin reloads or shuts down. */
    fun stopAll() {
        for (standId in running.values.toList()) {
            Bukkit.getEntity(standId)?.remove()
        }
        running.clear()
    }

    fun animationNames(): List<String> = SpawnAnimations.names()

    /** Themes that already ask for this animation, offered as tab completions. */
    fun themes(): List<String> {
        val section = plugin.config.getConfigurationSection("mobs.themes") ?: return emptyList()
        return section.getKeys(false).sorted()
    }

    // ------------------------------------------------------------------

    private fun run(owner: UUID, stand: LivingEntity, animation: SpawnAnimation, duration: Int) {
        object : BukkitRunnable() {
            private var ticks = 0
            private var lingering = -1
            private var standReported = false

            override fun run() {
                // A losing stand-in used to wipe the whole entrance in
                // silence. The visuals are the entire point of a preview, so
                // they play on without it and the disappearance is reported
                // instead.
                if ((!stand.isValid || stand.isDead) && !standReported) {
                    standReported = true
                    reportMissingStand(owner, stand)
                }
                if (lingering >= 0) {
                    // The outro is already playing; only the clean-up is left.
                    if (lingering++ < LINGER_TICKS) {
                        return
                    }
                    running.remove(owner)
                    stand.remove()
                    cancel()
                    return
                }
                // Mirrors what BossSummoningSequence does every tick, so the
                // preview turns exactly like the real entrance does.
                if (stand.isValid) {
                    stand.setRotation(stand.location.yaw + 12.0F, 0.0F)
                }
                if (!SpawnAnimations.tickSafely(plugin, animation, ticks, duration)) {
                    // tickSafely already logged the cause and cleaned up.
                    running.remove(owner)
                    stand.remove()
                    cancel()
                    return
                }
                if (++ticks >= duration) {
                    animation.finish()
                    lingering = 0
                }
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    /**
     * The server took the stand-in away. On Peaceful every monster is removed
     * the tick after it spawns, and a plugin cancelling CreatureSpawnEvent
     * has the same effect, so name both rather than leaving an empty arena.
     */
    private fun reportMissingStand(owner: UUID, stand: LivingEntity) {
        plugin.logger.warning("Animation preview stand-in ${stand.type} was removed by the server" +
            " (world difficulty ${stand.world.difficulty}). The entrance keeps playing without it;" +
            " on PEACEFUL every monster is removed one tick after spawning.")
        val player = Bukkit.getPlayer(owner)
        if (player != null) {
            plugin.messages.send(player, "animate-stand-gone",
                Messages.ph("entity", stand.type.name),
                Messages.ph("difficulty", stand.world.difficulty.name))
        }
    }

    private fun prepare(stand: LivingEntity, theme: String) {
        stand.setAI(false)
        stand.isInvulnerable = true
        stand.setRemoveWhenFarAway(false)
        stand.isPersistent = false
        if (stand is Ageable) {
            stand.setAdult()
        }
        // Same scale the real fight uses, because rise depth has to clear it.
        val scale = stand.getAttribute(Attribute.SCALE)
        val configured = bossScale(theme)
        if (scale != null && configured > 0.0) {
            scale.baseValue = configured
        }
        stand.persistentDataContainer.set(plugin.dungeonMobTestKey, PersistentDataType.BYTE, 1.toByte())
    }

    /** The scale of the first difficulty band that uses this theme. */
    private fun bossScale(theme: String): Double {
        for (difficulty in 1..9) {
            val path = "mobs.difficulties.$difficulty."
            if (theme.equals(plugin.config.getString(path + "theme", ""), ignoreCase = true)) {
                return plugin.config.getDouble(path + "boss.scale", 1.0)
            }
        }
        return 1.0
    }

    /** The first theme whose boss already asks for this animation. */
    private fun themeUsing(name: String): String? {
        val section = plugin.config.getConfigurationSection("mobs.themes") ?: return null
        for (theme in section.getKeys(false)) {
            val configured = plugin.config.getString("mobs.themes.$theme.boss.summoning.animation", "")
            if (configured != null && configured.trim().equals(name, ignoreCase = true)) {
                return theme
            }
        }
        return null
    }

    enum class Status {
        SUCCESS,
        UNKNOWN_ANIMATION,
        UNKNOWN_THEME,
        INVALID_BOSS,
        ALREADY_RUNNING,
        FAILED
    }

    /** [detail] names what the animation created, or null when it never started. */
    data class Result(val status: Status, val theme: String?, val durationTicks: Int, val detail: String?)

    companion object {
        /**
         * Grace period after the outro before the stand-in is taken away.
         * Long enough to actually look at what the entrance ended on, which a
         * second and a bit was not.
         */
        private const val LINGER_TICKS = 80

        private fun entityType(value: String?): EntityType? = try {
            val type = EntityType.valueOf((value ?: "WARDEN").trim().uppercase(Locale.ROOT))
            if (type.entityClass != null && LivingEntity::class.java.isAssignableFrom(type.entityClass!!)) type else null
        } catch (exception: IllegalArgumentException) {
            null
        }
    }
}
