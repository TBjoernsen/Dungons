package nl.riddernix.dungeonplugin.classes

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.util.Locale
import java.util.UUID

/** Sidebar, tab prefix, and the audiovisual confirmations of the class layer. */
class FeedbackService(private val plugin: DungeonPlugin) {

    private val boards = HashMap<UUID, Scoreboard>()

    /** The Archer's persistent Focus readout, shown once a bar starts building. */
    private val focusBars = HashMap<UUID, BossBar>()

    fun refresh(player: Player) {
        updateTabName(player)
        val board = boards.getOrPut(player.uniqueId) { Bukkit.getScoreboardManager().newScoreboard }
        @Suppress("DEPRECATION")
        val objective = board.getObjective("dungeonplugin")
            ?: board.registerNewObjective("dungeonplugin", "dummy", "§6§lClass Skills")
        objective.displaySlot = DisplaySlot.SIDEBAR
        board.entries.forEach(board::resetScores)

        val data = plugin.classes.data(player.uniqueId)
        val className = plugin.classes.activeClass(player.uniqueId)?.displayName ?: "Unchosen"
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val attack = player.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 1.0
        val armor = player.getAttribute(Attribute.ARMOR)?.value ?: 0.0
        val points = plugin.skillProgress.points(player.uniqueId)
        val lines = mutableListOf(
            "§fClass: §e$className",
            "§fLevel: §e${data.level}/100",
            "§fPoints: §e$points",
            "§fDifficulty: §e${data.unlockedDifficulty}/9",
            " ",
            "§cHealth: §f${number(maxHealth / 2.0)} ♥",
            "§cAttack: §f${number(attack)}",
            "§9Armor: §f${number(armor)}",
            "  ",
            "§d${plugin.classPassives.readout(player)}"
        )
        lines.add(2, "§fXP: §e${data.experience}/${plugin.classPassives.experienceToNextLevel(player)}")
        val xpBonus = plugin.questXpMultiplier(player.uniqueId)
        if (xpBonus > 1.0) {
            val separator = lines.indexOf(" ")
            if (separator > 0) {
                lines.add(separator, "§bXP Bonus: §e×${String.format(Locale.US, "%.2f", xpBonus)}")
            }
        }
        val passiveLines = plugin.classPassives.readoutLines(player)
        if (passiveLines.size > 1) {
            // Replace the normal passive entry with the compact two-line
            // ready prompt.
            lines.removeAt(lines.lastIndex)
            lines.addAll(passiveLines.map { "§d$it" })
        }
        lines.reversed().forEachIndexed { index, text ->
            // The invisible colour suffix keeps otherwise identical
            // scoreboard lines unique.
            objective.getScore("$text§${index.toString(16)}").score = index
        }
        if (player.scoreboard != board) player.scoreboard = board
        updateFocusBar(player)
    }

    fun remove(player: Player) {
        boards.remove(player.uniqueId)
        focusBars.remove(player.uniqueId)?.let(player::hideBossBar)
    }

    /** Hides every Focus bar - called on plugin disable so a reload leaves none orphaned. */
    fun shutdown() {
        focusBars.forEach { (id, bar) -> plugin.server.getPlayer(id)?.hideBossBar(bar) }
        focusBars.clear()
    }

    /**
     * The Archer's Focus bossbar: appears the moment a bar starts building,
     * fills white as stacks land, and flips to a full yellow "FOCUSED" once a
     * Focus Shot is banked.
     */
    private fun updateFocusBar(player: Player) {
        val status = plugin.classPassives.focusStatus(player)
        if (status == null || status.stacks <= 0) {
            focusBars.remove(player.uniqueId)?.let(player::hideBossBar)
            return
        }
        val bar = focusBars.getOrPut(player.uniqueId) {
            BossBar.bossBar(Component.empty(), 0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS)
                .also { player.showBossBar(it) }
        }
        bar.name(Component.text(
            if (status.full) "⚡ FOCUSED  —  left-click for Focus Shot"
            else "Focus  ${status.stacks} / ${status.required}"))
        bar.progress((status.stacks.toFloat() / status.required.toFloat()).coerceIn(0f, 1f))
        bar.color(if (status.full) BossBar.Color.YELLOW else BossBar.Color.WHITE)
    }

    /** Confirmed post-commit purchase feedback. Particles are deliberately non-damaging. */
    fun nodePurchased(player: Player) {
        val burst = player.location.clone().add(0.0, 0.15, 0.0)
        val green = Particle.DustOptions(Color.fromRGB(80, 255, 110), 1.5f)
        player.world.spawnParticle(Particle.FIREWORK, burst, 14, 0.42, 0.12, 0.42, 0.05)
        player.world.spawnParticle(Particle.DUST, burst, 34, 0.55, 0.14, 0.55, 0.08, green)
        // A Note Block placed on a gold block uses the bell instrument.
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 0.65f)
    }

    /** Dungeon XP level-up feedback. Particle fireworks are visual-only and cannot deal damage. */
    fun levelUp(player: Player) {
        val burst = player.location.clone().add(0.0, 0.15, 0.0)
        val blue = Particle.DustOptions(Color.fromRGB(65, 155, 255), 1.6f)
        player.world.spawnParticle(Particle.FIREWORK, burst, 16, 0.45, 0.13, 0.45, 0.06)
        player.world.spawnParticle(Particle.DUST, burst, 38, 0.58, 0.15, 0.58, 0.09, blue)
    }

    fun rageTriggered(player: Player) {
        val center = player.location.clone().add(0.0, 0.85, 0.0)
        val red = Particle.DustOptions(Color.fromRGB(245, 55, 55), 1.65f)
        player.world.spawnParticle(Particle.DUST, center, 42, 0.52, 0.65, 0.52, 0.1, red)
        player.world.spawnParticle(Particle.FLAME, center, 26, 0.45, 0.55, 0.45, 0.05)
        player.playSound(player.location, Sound.ENTITY_RAVAGER_ROAR, 0.75f, 1.1f)
    }

    /** Warrior Dash launch: the whoosh, plus a short particle streak that rides the player for the lunge. */
    fun warriorDashCast(player: Player, berserk: Boolean) {
        val world = player.world
        player.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, if (berserk) 0.7f else 0.95f)
        if (berserk) player.playSound(player.location, Sound.ENTITY_RAVAGER_ROAR, 0.5f, 1.35f)
        object : BukkitRunnable() {
            private var ticks = 0
            override fun run() {
                if (ticks++ >= 6 || !player.isOnline) {
                    cancel(); return
                }
                val at = player.location.clone().add(0.0, 0.9, 0.0)
                if (berserk) {
                    val red = Particle.DustOptions(Color.fromRGB(245, 70, 45), 1.5f)
                    world.spawnParticle(Particle.DUST, at, 8, 0.28, 0.35, 0.28, 0.0, red)
                    world.spawnParticle(Particle.FLAME, at, 4, 0.2, 0.25, 0.2, 0.01)
                } else {
                    world.spawnParticle(Particle.CLOUD, at, 6, 0.22, 0.3, 0.22, 0.01)
                    world.spawnParticle(Particle.CRIT, at, 5, 0.25, 0.3, 0.25, 0.05)
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    /** Warrior Dash connecting with one or more enemies: a sweep burst and a crunch. */
    fun warriorDashImpact(player: Player, berserk: Boolean) {
        val at = player.location.clone().add(0.0, 1.0, 0.0)
        player.world.spawnParticle(Particle.SWEEP_ATTACK, at, if (berserk) 3 else 1, 0.4, 0.3, 0.4, 0.0)
        player.world.spawnParticle(Particle.CRIT, at, if (berserk) 24 else 14, 0.5, 0.4, 0.5, 0.25)
        player.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, if (berserk) 0.85f else 1.1f)
    }

    /** Focus Shot leaving the bow: a heavy release and a glinting trail that rides the arrow. */
    fun focusShotFired(player: Player, arrow: Arrow) {
        player.playSound(player.location, Sound.ITEM_CROSSBOW_LOADING_END, 0.9f, 0.8f)
        player.playSound(player.location, Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.6f)
        player.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.4f)
        val cyan = Particle.DustOptions(Color.fromRGB(90, 210, 255), 1.2f)
        object : BukkitRunnable() {
            private var ticks = 0
            override fun run() {
                if (ticks++ >= 60 || !arrow.isValid || arrow.isDead || arrow.isInBlock) {
                    cancel(); return
                }
                val at = arrow.location
                at.world?.spawnParticle(Particle.DUST, at, 4, 0.03, 0.03, 0.03, 0.0, cyan)
                at.world?.spawnParticle(Particle.CRIT, at, 2, 0.02, 0.02, 0.02, 0.0)
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    /** Focus Shot connecting: a sharp burst at the impact point. */
    fun focusShotImpact(where: Location) {
        val world = where.world ?: return
        world.spawnParticle(Particle.CRIT, where, 30, 0.25, 0.25, 0.25, 0.35)
        world.spawnParticle(Particle.FIREWORK, where, 10, 0.2, 0.2, 0.2, 0.08)
        world.spawnParticle(Particle.SWEEP_ATTACK, where, 2, 0.1, 0.1, 0.1, 0.0)
        world.playSound(where, Sound.ENTITY_ARROW_HIT_PLAYER, 1.0f, 0.7f)
        world.playSound(where, Sound.ENTITY_GENERIC_EXPLODE, 0.35f, 1.6f)
    }

    fun tauntTriggered(player: Player) {
        val center = player.location.clone().add(0.0, 0.85, 0.0)
        val gold = Particle.DustOptions(Color.fromRGB(255, 205, 55), 1.55f)
        player.world.spawnParticle(Particle.DUST, center, 40, 0.58, 0.7, 0.58, 0.08, gold)
        player.world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 26, 0.42, 0.6, 0.42, 0.08)
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 0.85f, 1.15f)
    }

    private fun updateTabName(player: Player) {
        val classType = plugin.classes.activeClass(player.uniqueId)
        @Suppress("DEPRECATION")
        player.setPlayerListName((classType?.tabPrefix ?: "") + player.name)
    }

    private fun number(value: Double): String = String.format(Locale.US, "%.1f", value)
}
