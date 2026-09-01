package nl.riddernix.dungeonplugin.classes

import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.event.SkillWriteStatus
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Levels, XP, per-class profiles and the point budget - the progression half
 * of the merged class layer.
 *
 * The division of ownership survives the merge unchanged, just without the
 * plugin boundary: this service owns class, level and the level-derived point
 * budget; [nl.riddernix.dungeonplugin.skills.SkillProgressManager] owns which
 * nodes were bought and therefore `spent`. Available points are derived,
 * never stored: `budget - spent`. A refund lowers `spent` and nothing else,
 * so nothing can ever be handed out twice.
 *
 * The *active* class also lives in SkillProgressManager - one authority
 * instead of the old two-plugin sync. What this file stores per player is the
 * per-class profile: level, XP and unlocked difficulty, swapped in and out on
 * a class change so switching away and back loses nothing.
 */
class ClassProgressionService(private val plugin: DungeonPlugin) {

    private val file = File(plugin.dataFolder, "players.yml")
    private val byPlayer = HashMap<UUID, PlayerClassData>()

    /** Whether the class layer runs at all; off leaves a dungeons-only server. */
    val enabled: Boolean
        get() = plugin.classesConfig.getBoolean("classes.enabled", true)

    init {
        load()
    }

    // ------------------------------------------------------------------
    //  Queries
    // ------------------------------------------------------------------

    fun data(playerId: UUID): PlayerClassData = byPlayer.getOrPut(playerId) { PlayerClassData() }

    /** The player's active class, read from the one authority. */
    fun activeClass(playerId: UUID): ClassType? =
        ClassType.fromInput(plugin.skillProgress.activeClass(playerId))

    /** The active class profile's current level-based point budget. */
    fun budget(player: Player): Int = pointBudgetForLevel(data(player.uniqueId).level)

    fun hasUnlockedDifficulty(player: Player, difficulty: Int): Boolean =
        difficulty in 1..9 && data(player.uniqueId).unlockedDifficulty >= difficulty

    fun canEnterDungeonDifficulty(player: Player, difficulty: Int): Boolean {
        if (difficulty !in 1..9) return false
        if (!enabled) return true
        return maximumDungeonDifficultyForLevel(data(player.uniqueId).level) >= difficulty
    }

    fun maximumDungeonDifficultyForLevel(level: Int): Int =
        (((level.coerceIn(1, 100) - 1) / 10) + 1).coerceAtMost(9)

    /**
     * The node-derived stat bonus for one stat, straight from skills.yml's
     * per-node effect data. The unlocked set is small (40 nodes at most), so
     * this is computed fresh rather than cached and mirrored.
     */
    fun statBonus(playerId: UUID, stat: StatType): Double {
        val classId = plugin.skillProgress.activeClass(playerId) ?: return 0.0
        val tree = plugin.skillTrees.tree(classId) ?: return 0.0
        var total = 0.0
        for (nodeId in plugin.skillProgress.unlockedNodes(playerId, classId).keys) {
            val node = tree.nodes[nodeId] ?: continue
            if (StatType.fromConfig(node.effectStat) == stat) total += node.effectValue
        }
        return total
    }

    /** The highest signature passive rank the player's unlocked nodes carry. */
    fun signatureRank(playerId: UUID): Int {
        val classId = plugin.skillProgress.activeClass(playerId) ?: return 0
        val tree = plugin.skillTrees.tree(classId) ?: return 0
        var rank = 0
        for (nodeId in plugin.skillProgress.unlockedNodes(playerId, classId).keys) {
            val node = tree.nodes[nodeId] ?: continue
            if (node.effectPassiveRank > rank) rank = node.effectPassiveRank
        }
        return rank
    }

    // ------------------------------------------------------------------
    //  Difficulty and XP
    // ------------------------------------------------------------------

    /** Keeps skill-tree tiers aligned with the highest difficulty earned through level. */
    fun syncDifficultyToLevel(player: Player) {
        val data = data(player.uniqueId)
        val levelDifficulty = maximumDungeonDifficultyForLevel(data.level)
        if (levelDifficulty > data.unlockedDifficulty) {
            unlockDungeonDifficulty(player, levelDifficulty)
        }
    }

    fun unlockDungeonDifficulty(player: Player, difficulty: Int) {
        require(difficulty in 1..9) { "Difficulty must be between 1 and 9." }
        val data = data(player.uniqueId)
        val previous = data.unlockedDifficulty
        data.unlockedDifficulty = maxOf(data.unlockedDifficulty, difficulty)
        if (previous < 3 && data.unlockedDifficulty >= 3 && activeClass(player.uniqueId) != null) {
            player.sendMessage("§6Your class is now locked in. " +
                "${activeClass(player.uniqueId)!!.passiveName} Rank II is now available in the skill tree!")
        }
        save()
        plugin.refreshClassPlayer(player)
    }

    /** Awards all banked mob XP, completion XP, and shard loot in one completion summary. */
    fun awardDungeonCompletion(player: Player, difficulty: Int, mobKills: Int) {
        require(difficulty in 1..9) { "Difficulty must be between 1 and 9." }
        val config = plugin.classesConfig
        val mobExperience = maxOf(0, config.getInt("xp-per-difficulty.$difficulty", 0)) * maxOf(0, mobKills)
        val completionExperience = maxOf(0, config.getInt("completion-xp-per-difficulty.$difficulty", 0))
        // Apply one configurable multiplier to the whole run, so mob-heavy
        // dungeons cannot outpace the intended progression curve while their
        // completion bonus stays meaningful.
        val experienceMultiplier = config.getDouble("dungeon-xp-multiplier", 0.22).coerceIn(0.0, 10.0)
        val totalExperience = ((mobExperience + completionExperience) * experienceMultiplier).roundToInt()
        if (totalExperience > 0) {
            player.giveExp(totalExperience)
            grantSkillExperience(player, totalExperience)
        }
        val drops = rollDungeonDrops(player, difficulty)
        val shardSummary = buildList {
            if (drops.skillShards > 0) add("${drops.skillShards} Skill Shard" + if (drops.skillShards == 1) "" else "s")
            if (drops.soulShards > 0) add("${drops.soulShards} Soul Shard" + if (drops.soulShards == 1) "" else "s")
        }.ifEmpty { listOf("no shards") }.joinToString(" • ")
        player.showTitle(net.kyori.adventure.title.Title.title(
            net.kyori.adventure.text.Component.text("Dungeon Complete!"),
            net.kyori.adventure.text.Component.text("+$totalExperience XP • $shardSummary"),
            net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(250),
                java.time.Duration.ofSeconds(3), java.time.Duration.ofMillis(500))))
        player.sendMessage("§aDungeon rewards: §e+$totalExperience XP §7($mobKills mobs) §f• $shardSummary")
    }

    /** One independent roll for each shard type; a dungeon can award at most one of each. */
    fun rollDungeonDrops(player: Player, difficulty: Int): DungeonDropResult {
        require(difficulty in 1..9) { "Difficulty must be between 1 and 9." }
        val section = plugin.classesConfig.yaml.getConfigurationSection("shared-drop-table.$difficulty")
            ?: return DungeonDropResult()
        val skillWeight = maxOf(0, section.getInt("skill-shard", 0))
        val soulWeight = maxOf(0, section.getInt("soul-shard", 0))
        val nothingWeight = maxOf(0, section.getInt("nothing", 0))
        val total = skillWeight + soulWeight + nothingWeight
        if (total <= 0) return DungeonDropResult()
        // Keep the configured weights as their per-dungeon chances, but roll
        // the two currencies separately. This permits at most one Skill Shard
        // and one Soul Shard.
        val skillShards = if (ThreadLocalRandom.current().nextInt(total) < skillWeight) 1 else 0
        val soulShards = if (ThreadLocalRandom.current().nextInt(total) < soulWeight) 1 else 0
        if (skillShards == 1) plugin.classItems.give(player, plugin.classItems.skillShard())
        if (soulShards == 1) plugin.classItems.give(player, plugin.classItems.soulShard())
        return DungeonDropResult(skillShards, soulShards)
    }

    fun grantSkillExperience(player: Player, amount: Int) {
        if (amount <= 0) return
        val data = data(player.uniqueId)
        if (data.level >= 100) return
        val previousMaximumDifficulty = maximumDungeonDifficultyForLevel(data.level)
        data.experience += amount
        while (data.level < 100) {
            val required = xpToNextLevel(data.level)
            if (data.experience < required) break
            data.experience -= required
            data.level++
            val awarded = pointBudgetForLevel(data.level) - pointBudgetForLevel(data.level - 1)
            player.sendMessage("§aLevel ${data.level}! §e+$awarded Skill Points")
            plugin.classFeedback.levelUp(player)
        }
        if (data.level == 100) data.experience = 0
        save()
        plugin.refreshClassPlayer(player)
        val newMaximumDifficulty = maximumDungeonDifficultyForLevel(data.level)
        if (newMaximumDifficulty > previousMaximumDifficulty) {
            player.sendMessage("§6Difficulty $newMaximumDifficulty is now available at Level ${data.level}.")
        }
    }

    // ------------------------------------------------------------------
    //  Class selection
    // ------------------------------------------------------------------

    fun selectClass(player: Player, requested: ClassType): SelectionResult {
        val data = data(player.uniqueId)
        val current = activeClass(player.uniqueId)
        if (current == requested) return SelectionResult.ALREADY_SELECTED
        if (current != null && data.unlockedDifficulty >= 3) {
            if (!plugin.classItems.consume(player, 1, plugin.classItems::isSoulShard)) {
                return SelectionResult.NEEDS_SOUL_SHARD
            }
            player.sendMessage("§dA Soul Shard lets you change to ${requested.displayName}.")
        } else {
            player.sendMessage("§aYou are now a ${requested.displayName}.")
        }
        // The outgoing profile is snapshotted before anything switches - the
        // old cross-plugin flow read the new balance into the old profile,
        // which the merge makes structurally impossible: profiles carry no
        // point balance at all.
        current?.let { data.classProfiles[it.id] = ClassProgress(data.level, data.experience, data.unlockedDifficulty) }
        val restored = data.classProfiles.remove(requested.id) ?: ClassProgress(1, 0, 1)
        data.level = restored.level
        data.experience = restored.experience
        data.unlockedDifficulty = restored.unlockedDifficulty
        data.clearCombatResources()
        val write = plugin.skillProgress.setActiveClass(player, requested.id)
        if (!write.isSuccess && write.status != SkillWriteStatus.UNCHANGED) {
            return SelectionResult.LOCKED
        }
        if (requested == ClassType.MAGE && player.inventory.contents.none { plugin.classItems.isStaff(it) }) {
            plugin.classItems.give(player, plugin.classItems.mageStaff())
        }
        save()
        plugin.refreshClassPlayer(player)
        return SelectionResult.SUCCESS
    }

    // ------------------------------------------------------------------
    //  Admin utilities
    // ------------------------------------------------------------------

    /** Development-only character reset: preserves class but clears all accumulated progression. */
    fun adminResetCharacter(player: Player) {
        val data = data(player.uniqueId)
        data.level = 1
        data.experience = 0
        data.unlockedDifficulty = 1
        data.clearCombatResources()
        save()
        plugin.refreshClassPlayer(player)
    }

    /** Development-only level grant. */
    fun adminLevelUp(player: Player, levels: Int): Int {
        val data = data(player.uniqueId)
        val gained = minOf(maxOf(0, levels), 100 - data.level)
        data.level += gained
        if (gained > 0) {
            save()
            plugin.refreshClassPlayer(player)
        }
        return gained
    }

    /** Admin recovery: wipes progression and the active class's tree. */
    fun adminHardReset(player: Player) {
        val active = plugin.skillProgress.activeClass(player.uniqueId)
        byPlayer.remove(player.uniqueId)
        if (active != null) {
            plugin.skillProgress.resetTree(player, active)
        }
        save()
        plugin.classAttributes.forceResetCombatStats(player)
        plugin.refreshClassPlayer(player)
    }

    /**
     * Whole-tree wipes cost ceil(spent x rate) Skill Shards, so they are
     * always cheaper than revoking every node separately but naturally scale
     * with a build's investment.
     */
    fun resetCost(player: Player): Int {
        val spent = plugin.skillProgress.spentPoints(player.uniqueId)
        if (spent == 0) return 0
        return maxOf(1, ceil(spent * plugin.classesConfig.getDouble("bulk-reset-shard-rate", 0.6)).toInt())
    }

    // ------------------------------------------------------------------
    //  Curves
    // ------------------------------------------------------------------

    /**
     * 201-point progression: a two-point starting budget, then 1 point at
     * Levels 1-33, 2 at Levels 34-65, and 3 at Levels 66-99. Level 100 is the
     * capstone point. Kept exactly as ClassSkills shipped it (its docs said
     * 200; the formula has always produced 201 - flagged in HANDOVER.md, not
     * silently changed).
     */
    fun pointBudgetForLevel(level: Int): Int = 1 + when (val capped = level.coerceIn(1, 100)) {
        in 1..33 -> capped
        in 34..65 -> 33 + (capped - 33) * 2
        in 66..99 -> 97 + (capped - 65) * 3
        else -> 200
    }

    fun experienceToNextLevel(player: Player): Int {
        val data = data(player.uniqueId)
        return if (data.level >= 100) 0 else xpToNextLevel(data.level)
    }

    private fun xpToNextLevel(currentLevel: Int): Int {
        val config = plugin.classesConfig
        val base = config.getInt("xp-to-next-level.base", 100)
        val baseRequirement = base +
            config.getInt("xp-to-next-level.per-current-level", 35) * maxOf(0, currentLevel - 1)
        val requirementMultiplier = config.getDouble("xp-to-next-level.requirement-multiplier", 1.12)
            .coerceIn(0.01, 10.0)
        // Level 1 -> 2 costs exactly the configured base (100 by default).
        // The multiplier still scales the additional requirement of later
        // levels.
        return ceil(base + (baseRequirement - base) * requirementMultiplier).toInt()
    }

    // ------------------------------------------------------------------
    //  Storage
    // ------------------------------------------------------------------

    private fun load() {
        byPlayer.clear()
        if (!file.isFile) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        val players = yaml.getConfigurationSection("players") ?: return
        for (rawId in players.getKeys(false)) {
            val playerId = try {
                UUID.fromString(rawId)
            } catch (exception: IllegalArgumentException) {
                plugin.logger.warning("Ignoring invalid entry '$rawId' in players.yml.")
                continue
            }
            val path = "players.$rawId"
            val data = PlayerClassData()
            data.level = yaml.getInt("$path.level", 1).coerceIn(1, 100)
            data.experience = maxOf(0, yaml.getInt("$path.experience", 0))
            data.unlockedDifficulty = yaml.getInt("$path.unlocked-difficulty", 1).coerceIn(1, 9)
            data.rage = yaml.getDouble("$path.rage", 0.0)
            data.focus = maxOf(0, yaml.getInt("$path.focus", 0))
            data.judgment = maxOf(0.0, yaml.getDouble("$path.judgment", 0.0))
            data.mana = maxOf(0.0, yaml.getDouble("$path.mana", 0.0))
            yaml.getConfigurationSection("$path.class-profiles")?.let { profiles ->
                for (classKey in profiles.getKeys(false)) {
                    val profile = "$path.class-profiles.$classKey"
                    data.classProfiles[classKey.lowercase()] = ClassProgress(
                        yaml.getInt("$profile.level", 1).coerceIn(1, 100),
                        maxOf(0, yaml.getInt("$profile.experience", 0)),
                        yaml.getInt("$profile.unlocked-difficulty", 1).coerceIn(1, 9))
                }
            }
            byPlayer[playerId] = data
        }
    }

    fun save() {
        val yaml = YamlConfiguration()
        for ((playerId, data) in byPlayer) {
            val path = "players.$playerId"
            yaml.set("$path.level", data.level)
            yaml.set("$path.experience", data.experience)
            yaml.set("$path.unlocked-difficulty", data.unlockedDifficulty)
            yaml.set("$path.rage", data.rage)
            yaml.set("$path.focus", data.focus)
            yaml.set("$path.judgment", data.judgment)
            yaml.set("$path.mana", data.mana)
            for ((classId, progress) in data.classProfiles) {
                val profile = "$path.class-profiles.$classId"
                yaml.set("$profile.level", progress.level)
                yaml.set("$profile.experience", progress.experience)
                yaml.set("$profile.unlocked-difficulty", progress.unlockedDifficulty)
            }
        }
        try {
            yaml.save(file)
        } catch (exception: IOException) {
            plugin.logger.severe("Could not save players.yml: ${exception.message}")
        }
    }
}

/**
 * One player's class-layer state. The top-level fields describe the active
 * class; inactive classes live in [classProfiles] and are swapped in on a
 * switch. Combat resources are runtime state that happens to persist so a
 * relog does not refill anything.
 */
class PlayerClassData {
    var level: Int = 1
    var experience: Int = 0
    var unlockedDifficulty: Int = 1
    var rage: Double = 0.0
    var rageActiveUntil: Long = 0L

    /** Runtime-only combat timestamp; Rage decays after time without combat. */
    var lastRageCombatAt: Long = System.currentTimeMillis()
    var focus: Int = 0
    var judgment: Double = 0.0
    var mana: Double = 0.0

    /** Inactive class profiles. The active class stays in the top-level fields. */
    val classProfiles = LinkedHashMap<String, ClassProgress>()

    fun clearCombatResources() {
        rage = 0.0
        rageActiveUntil = 0L
        focus = 0
        judgment = 0.0
        mana = 0.0
    }
}

data class ClassProgress(val level: Int, val experience: Int, val unlockedDifficulty: Int)

data class DungeonDropResult(val skillShards: Int = 0, val soulShards: Int = 0)

enum class SelectionResult { SUCCESS, ALREADY_SELECTED, LOCKED, NEEDS_SOUL_SHARD }
