package dev.thorb.classskills.ui

import dev.thorb.classskills.data.PlayerDataStore
import dev.thorb.classskills.model.SkillTreeCatalog
import dev.thorb.classskills.model.StatType
import dev.thorb.classskills.service.PassiveService
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.util.Locale
import java.util.UUID

class FeedbackService(
    private val store: PlayerDataStore,
    private val catalog: SkillTreeCatalog,
    private val passives: PassiveService,
    private val externalPoints: (Player) -> Int?
) {
    private val boards = mutableMapOf<UUID, Scoreboard>()

    fun refresh(player: Player) {
        updateTabName(player)
        val board = boards.getOrPut(player.uniqueId) { Bukkit.getScoreboardManager().newScoreboard }
        val objective = board.getObjective("classskills")
            ?: board.registerNewObjective("classskills", "dummy", "§6§lClass Skills")
        objective.displaySlot = DisplaySlot.SIDEBAR
        board.entries.forEach(board::resetScores)

        val data = store.get(player.uniqueId)
        val className = data.classType?.displayName ?: "Unchosen"
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val attack = player.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 1.0
        val armor = player.getAttribute(Attribute.ARMOR)?.value ?: 0.0
        val points = externalPoints(player) ?: data.availablePoints
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
            "§d${passives.readout(player)}"
        )
        lines.add(2, "${org.bukkit.ChatColor.WHITE}XP: ${org.bukkit.ChatColor.YELLOW}${data.experience}/${passives.experienceToNextLevel(player)}")
        val passiveLines = passives.readoutLines(player)
        if (passiveLines.size > 1) {
            // Replace the normal passive entry with the compact two-line ready prompt.
            lines.removeAt(lines.lastIndex)
            lines.addAll(passiveLines.map { org.bukkit.ChatColor.LIGHT_PURPLE.toString() + it })
        }
        lines.reversed().forEachIndexed { index, text ->
            // The invisible colour suffix keeps otherwise identical scoreboard lines unique.
            objective.getScore("$text§${index.toString(16)}").score = index
        }
        if (player.scoreboard != board) player.scoreboard = board
    }

    fun remove(player: Player) {
        boards.remove(player.uniqueId)
    }

    /** Confirmed post-commit DungeonForge purchase feedback. Particles are deliberately non-damaging. */
    fun nodePurchased(player: Player) {
        val burst = player.location.clone().add(0.0, 0.15, 0.0)
        val green = Particle.DustOptions(Color.fromRGB(80, 255, 110), 1.5f)
        player.world.spawnParticle(Particle.FIREWORK, burst, 14, 0.42, 0.12, 0.42, 0.05)
        player.world.spawnParticle(Particle.DUST, burst, 34, 0.55, 0.14, 0.55, 0.08, green)
        // A Note Block placed on a gold block uses the bell instrument.
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 0.65f)
    }

    /** The node unlock event is pre-commit; use this only when its current price exceeds the live balance. */
    fun nodePurchaseDenied(player: Player) {
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.9f, 0.6f)
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

    fun tauntTriggered(player: Player) {
        val center = player.location.clone().add(0.0, 0.85, 0.0)
        val gold = Particle.DustOptions(Color.fromRGB(255, 205, 55), 1.55f)
        player.world.spawnParticle(Particle.DUST, center, 40, 0.58, 0.7, 0.58, 0.08, gold)
        player.world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 26, 0.42, 0.6, 0.42, 0.08)
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 0.85f, 1.15f)
    }

    private fun updateTabName(player: Player) {
        val classType = store.get(player.uniqueId).classType
        player.setPlayerListName((classType?.tabPrefix ?: "") + player.name)
    }

    private fun number(value: Double): String = String.format(Locale.US, "%.1f", value)
}
