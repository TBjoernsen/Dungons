package dev.thorb.classskills.service

import dev.thorb.classskills.ClassSkillsPlugin
import dev.thorb.classskills.api.DungeonSkillService
import dev.thorb.classskills.api.ClassSkillsApi
import dev.thorb.classskills.api.ClassSkillsPlayerProfile
import dev.thorb.classskills.data.PlayerDataStore
import dev.thorb.classskills.model.ClassType
import dev.thorb.classskills.model.ClassProgress
import dev.thorb.classskills.model.PlayerSkillData
import dev.thorb.classskills.model.SkillTreeCatalog
import dev.thorb.classskills.model.StatType
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffectType
import java.util.concurrent.ThreadLocalRandom
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import java.time.Duration
import kotlin.math.ceil
import kotlin.math.roundToInt

class ItemService(private val plugin: ClassSkillsPlugin) {
    private val itemKindKey = NamespacedKey(plugin, "item_kind")
    private val staffKind = "mage_staff"
    private val skillShardKind = "skill_shard"
    private val soulShardKind = "soul_shard"
    private val arcaneBoltKey = NamespacedKey(plugin, "arcane_bolt")
    private val focusShotKey = NamespacedKey(plugin, "focus_shot")

    fun skillShard(): ItemStack = taggedItem(
        Material.PRISMARINE_CRYSTALS,
        "§bSkill Shard",
        listOf("§7Right-click to open the skill tree.", "§7Right-click an unlocked node to revoke it."),
        skillShardKind
    )

    fun soulShard(): ItemStack = taggedItem(
        Material.ECHO_SHARD,
        "§dSoul Shard",
        listOf("§7Right-click to open class switching.", "§cLocked changes reset the character to Level 1."),
        soulShardKind
    )

    fun mageStaff(): ItemStack = taggedItem(
        Material.BLAZE_ROD,
        "§5Apprentice Staff",
        listOf("§7A Mage's class weapon.", "§dLeft-click: Arcane Bolt", "§aRight-click: Healing Spell"),
        staffKind
    )

    fun isStaff(item: ItemStack?): Boolean = isKind(item, staffKind)
    fun isSkillShard(item: ItemStack?): Boolean = isKind(item, skillShardKind)
    fun isSoulShard(item: ItemStack?): Boolean = isKind(item, soulShardKind)

    fun markArcaneBolt(projectile: Projectile) {
        projectile.persistentDataContainer.set(arcaneBoltKey, PersistentDataType.BYTE, 1)
    }

    fun isArcaneBolt(projectile: Projectile): Boolean =
        projectile.persistentDataContainer.has(arcaneBoltKey, PersistentDataType.BYTE)

    fun markFocusShot(projectile: Projectile) {
        projectile.persistentDataContainer.set(focusShotKey, PersistentDataType.BYTE, 1)
    }

    fun isFocusShot(projectile: Projectile): Boolean =
        projectile.persistentDataContainer.has(focusShotKey, PersistentDataType.BYTE)

    fun isAllowedWeapon(classType: ClassType, item: ItemStack?): Boolean {
        if (item == null || item.type.isAir) return false
        return when (classType) {
            ClassType.WARRIOR -> item.type.name.endsWith("_SWORD")
            ClassType.ARCHER -> item.type == Material.BOW
            ClassType.PALADIN -> item.type.name.endsWith("_AXE")
            ClassType.MAGE -> isStaff(item)
        }
    }

    fun isRestrictedWeapon(item: ItemStack?): Boolean {
        if (item == null || item.type.isAir) return false
        return item.type.name.endsWith("_SWORD") || item.type.name.endsWith("_AXE") ||
            item.type == Material.BOW || item.type == Material.CROSSBOW || isStaff(item)
    }

    fun give(player: Player, item: ItemStack) {
        player.inventory.addItem(item).values.forEach { overflow ->
            player.world.dropItemNaturally(player.location, overflow)
        }
    }

    fun count(player: Player, predicate: (ItemStack?) -> Boolean): Int =
        player.inventory.contents.sumOf { stack -> if (predicate(stack)) stack?.amount ?: 0 else 0 }

    fun consume(player: Player, amount: Int, predicate: (ItemStack?) -> Boolean): Boolean {
        if (amount <= 0) return true
        if (count(player, predicate) < amount) return false
        var remaining = amount
        for (slot in player.inventory.contents.indices) {
            val stack = player.inventory.getItem(slot) ?: continue
            if (!predicate(stack)) continue
            val taken = minOf(remaining, stack.amount)
            if (taken == stack.amount) player.inventory.setItem(slot, null)
            else stack.amount -= taken
            remaining -= taken
            if (remaining == 0) return true
        }
        return true
    }

    private fun taggedItem(material: Material, name: String, lore: List<String>, kind: String): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.setDisplayName(name)
        meta.lore = lore
        meta.persistentDataContainer.set(itemKindKey, PersistentDataType.STRING, kind)
        item.itemMeta = meta
        return item
    }

    private fun isKind(item: ItemStack?, kind: String): Boolean =
        item?.itemMeta?.persistentDataContainer?.get(itemKindKey, PersistentDataType.STRING) == kind
}

class AttributeService(
    private val plugin: ClassSkillsPlugin,
    private val catalog: SkillTreeCatalog
) {
    fun apply(player: Player, data: PlayerSkillData) {
        applyModifier(player, Attribute.ATTACK_DAMAGE, "attack", catalog.totalFor(data, StatType.ATTACK))
        applyModifier(player, Attribute.MAX_HEALTH, "health", catalog.totalFor(data, StatType.HEALTH))
        applyModifier(player, Attribute.ARMOR, "armor", catalog.totalFor(data, StatType.ARMOR))
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: return
        if (player.health > maxHealth) player.health = maxHealth
    }

    /**
     * Recovery path for corrupted test stats. This deliberately restores vanilla base values
     * and removes every modifier on the three stats ClassSkills owns.
     */
    fun forceResetCombatStats(player: Player) {
        listOf(Attribute.ATTACK_DAMAGE, Attribute.MAX_HEALTH, Attribute.ARMOR).forEach { attribute ->
            val instance = player.getAttribute(attribute) ?: return@forEach
            instance.modifiers.toList().forEach(instance::removeModifier)
            instance.baseValue = instance.defaultValue
        }
        player.removePotionEffect(PotionEffectType.STRENGTH)
        player.removePotionEffect(PotionEffectType.SPEED)
        player.removePotionEffect(PotionEffectType.SLOWNESS)
        player.removePotionEffect(PotionEffectType.JUMP_BOOST)
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: return
        player.health = player.health.coerceAtMost(maxHealth)
    }

    private fun applyModifier(player: Player, attribute: Attribute, suffix: String, amount: Double) {
        val instance = player.getAttribute(attribute) ?: return
        val key = NamespacedKey(plugin, "stat_$suffix")

        // Migrate away modifiers made by 0.1.0's deprecated random-UUID constructor.
        // Without a stable key, refreshes/reloads can leave duplicate +1 attack modifiers.
        instance.modifiers
            .filter { it.name.startsWith("classskills_") }
            .forEach(instance::removeModifier)
        instance.removeModifier(key)
        if (amount != 0.0) {
            instance.addTransientModifier(
                AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER)
            )
        }
    }
}

class ProgressionService(
    private val plugin: ClassSkillsPlugin,
    private val store: PlayerDataStore,
    private val catalog: SkillTreeCatalog,
    private val items: ItemService,
    private val attributes: AttributeService
) : DungeonSkillService, ClassSkillsApi {
    override fun getAvailableSkillPoints(player: Player): Int =
        plugin.dungeonForgeSkillPoints(player) ?: store.get(player.uniqueId).availablePoints

    override fun getSpentSkillPoints(player: Player): Int =
        catalog.spentPoints(plugin.dungeonForgeUnlockedSkillNodeIds(player))

    override fun getTotalSkillPointBudget(player: Player): Int =
        pointBudgetForLevel(store.get(player.uniqueId).level)

    override fun getPlayerProfile(player: Player): ClassSkillsPlayerProfile {
        val data = store.get(player.uniqueId)
        val unlockedNodeIds = plugin.dungeonForgeUnlockedSkillNodeIds(player)
        return ClassSkillsPlayerProfile(
            classId = data.classType?.name?.lowercase(),
            level = data.level,
            maxLevel = 100,
            experience = data.experience,
            experienceToNextLevel = experienceToNextLevel(player),
            availableSkillPoints = getAvailableSkillPoints(player),
            unlockedDifficulty = data.unlockedDifficulty,
            signatureRank = catalog.signatureRank(data.classType, data.unlockedDifficulty, unlockedNodeIds),
            attackBonus = catalog.totalFor(unlockedNodeIds, StatType.ATTACK),
            maxHealthBonus = catalog.totalFor(unlockedNodeIds, StatType.HEALTH),
            armorBonus = catalog.totalFor(unlockedNodeIds, StatType.ARMOR),
            purchasedNodeIds = unlockedNodeIds
        )
    }

    override fun hasUnlockedDifficulty(player: Player, difficulty: Int): Boolean =
        difficulty in 1..9 && store.get(player.uniqueId).unlockedDifficulty >= difficulty

    override fun hasPurchasedNode(player: Player, nodeId: String): Boolean =
        nodeId in plugin.dungeonForgeUnlockedSkillNodeIds(player)

    override fun canEnterDungeonDifficulty(player: Player, difficulty: Int): Boolean {
        if (difficulty !in 1..9) return false
        return maximumDungeonDifficultyForLevel(store.get(player.uniqueId).level) >= difficulty
    }

    fun maximumDungeonDifficultyForLevel(level: Int): Int =
        (((level.coerceIn(1, 100) - 1) / 10) + 1).coerceAtMost(9)

    /** Keeps skill-tree tiers aligned with the highest difficulty earned through level. */
    fun syncDifficultyToLevel(player: Player) {
        val levelDifficulty = maximumDungeonDifficultyForLevel(store.get(player.uniqueId).level)
        if (levelDifficulty > store.get(player.uniqueId).unlockedDifficulty) {
            unlockDungeonDifficulty(player, levelDifficulty)
        }
    }

    override fun unlockDungeonDifficulty(player: Player, difficulty: Int) {
        require(difficulty in 1..9) { "Difficulty must be between 1 and 9." }
        val data = store.get(player.uniqueId)
        val previous = data.unlockedDifficulty
        data.unlockedDifficulty = maxOf(data.unlockedDifficulty, difficulty)
        if (previous < 3 && data.unlockedDifficulty >= 3 && data.classType != null) {
            player.sendMessage("§6Your class is now locked in. ${data.classType!!.passiveName} Rank II is now available in the skill tree!")
        }
        store.save(player.uniqueId)
        plugin.refreshPlayer(player)
    }

    override fun awardDungeonMobKill(player: Player, difficulty: Int) {
        require(difficulty in 1..9) { "Difficulty must be between 1 and 9." }
        unlockDungeonDifficulty(player, difficulty)
        grantDungeonExperience(player, plugin.config.getInt("xp-per-difficulty.$difficulty", 0))
        rollDungeonDrops(player, difficulty)
    }

    override fun grantDungeonExperience(player: Player, amount: Int) {
        grantSkillExperience(player, amount)
    }

    /** Converts Minecraft experience earned inside a DungeonForge dungeon into skill experience. */
    fun grantMinecraftExperience(player: Player, amount: Int) {
        grantSkillExperience(player, amount)
    }

    /** Awards all banked mob XP, completion XP, and shard loot in one completion summary. */
    fun awardDungeonCompletion(player: Player, difficulty: Int, mobKills: Int) {
        require(difficulty in 1..9) { "Difficulty must be between 1 and 9." }
        val mobExperience = plugin.config.getInt("xp-per-difficulty.$difficulty", 0).coerceAtLeast(0) * mobKills.coerceAtLeast(0)
        val completionExperience = plugin.config.getInt("completion-xp-per-difficulty.$difficulty", 0).coerceAtLeast(0)
        // Apply one configurable multiplier to the whole run, so mob-heavy dungeons cannot
        // outpace the intended progression curve while their completion bonus stays meaningful.
        val experienceMultiplier = plugin.config.getDouble("dungeon-xp-multiplier", 0.20).coerceIn(0.0, 10.0)
        val totalExperience = ((mobExperience + completionExperience) * experienceMultiplier).roundToInt()
        if (totalExperience > 0) {
            player.giveExp(totalExperience)
            grantSkillExperience(player, totalExperience)
        }
        val drops = rollDungeonDrops(player, difficulty)
        val shardSummary = buildList {
            if (drops.skillShards > 0) add("${drops.skillShards} Skill Shard${if (drops.skillShards == 1) "" else "s"}")
            if (drops.soulShards > 0) add("${drops.soulShards} Soul Shard${if (drops.soulShards == 1) "" else "s"}")
        }.ifEmpty { listOf("no shards") }.joinToString(" • ")
        player.showTitle(
            Title.title(
                Component.text("Dungeon Complete!"),
                Component.text("+$totalExperience XP • $shardSummary"),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500))
            )
        )
        player.sendMessage("§aDungeon rewards: §e+$totalExperience XP §7($mobKills mobs) §f• $shardSummary")
    }

    /** One independent roll for each shard type; a dungeon can award at most one of each. */
    fun rollDungeonDrops(player: Player, difficulty: Int): DungeonDropResult {
        require(difficulty in 1..9) { "Difficulty must be between 1 and 9." }
        return rollSharedDrop(player, difficulty)
    }

    private fun grantSkillExperience(player: Player, amount: Int) {
        ensurePointBudget(player)
        if (amount <= 0) return
        val data = store.get(player.uniqueId)
        if (data.level >= 100) return
        val previousMaximumDifficulty = maximumDungeonDifficultyForLevel(data.level)
        data.experience += amount
        while (data.level < 100) {
            val required = xpToNextLevel(data.level)
            if (data.experience < required) break
            data.experience -= required
            data.level++
            val awarded = pointsAwardedForLevel(data.level)
            awardLevelPoints(player, data, awarded)
            player.sendMessage("§aLevel ${data.level}! §e+$awarded Skill Points")
            plugin.feedback.levelUp(player)
        }
        if (data.level == 100) data.experience = 0
        store.save(player.uniqueId)
        plugin.refreshPlayer(player)
        val newMaximumDifficulty = maximumDungeonDifficultyForLevel(data.level)
        if (newMaximumDifficulty > previousMaximumDifficulty) {
            player.sendMessage("§6Difficulty $newMaximumDifficulty is now available at Level ${data.level}.")
        }
    }

    fun selectClass(player: Player, requested: ClassType, @Suppress("UNUSED_PARAMETER") requireSoulShard: Boolean = true): SelectionResult {
        ensurePointBudget(player)
        val data = store.get(player.uniqueId)
        if (data.classType == requested) return SelectionResult.ALREADY_SELECTED
        if (data.classType != null && data.unlockedDifficulty >= 3) {
            if (!items.consume(player, 1, items::isSoulShard)) return SelectionResult.NEEDS_SOUL_SHARD
            player.sendMessage("§dA Soul Shard lets you change to ${requested.displayName}.")
        } else {
            player.sendMessage("§aYou are now a ${requested.displayName}.")
        }
        // DungeonForge owns the active external class when available. Do this before
        // synchronizing the class-specific point balance.
        if (plugin.selectDungeonForgeClass(player, requested) == false) return SelectionResult.LOCKED
        plugin.dungeonForgeSkillPoints(player)?.let { data.availablePoints = it }
        data.classType?.let { current -> data.classProfiles[current] = snapshot(data) }
        val restored = data.classProfiles.remove(requested) ?: ClassProgress(1, 0, pointBudgetForLevel(1), 1)
        restore(data, requested, restored)
        plugin.syncDungeonForgeSkillPoints(player, data.availablePoints)?.let { data.availablePoints = it }
        clearCombatResources(data)
        if (requested == ClassType.MAGE && !player.inventory.contents.any(items::isStaff)) giveMageStaff(player)
        store.save(player.uniqueId)
        plugin.warmUpNodeEffects(player)
        return SelectionResult.SUCCESS
    }

    /** Node purchases are owned by DungeonForge's skill panel and API. */
    fun purchase(@Suppress("UNUSED_PARAMETER") player: Player, @Suppress("UNUSED_PARAMETER") nodeId: String): PurchaseResult {
        return PurchaseResult.DUNGEON_FORGE_OWNED
    }

    /** Replaces the active, non-persistent effect projection from DungeonForge's v5 query. */
    fun applyNodeEffects(player: Player, nodeIds: Collection<String>) {
        val data = store.get(player.uniqueId)
        val classType = data.classType
        val root = classType?.let { "${it.name.lowercase()}_ability_00" }
        val supported = nodeIds.filterTo(linkedSetOf()) { nodeId ->
            nodeId == root || catalog.byId[nodeId]?.classType == classType
        }
        data.derivedNodeIds.clear()
        data.derivedNodeIds.addAll(supported)
    }

    fun refundOne(@Suppress("UNUSED_PARAMETER") player: Player, @Suppress("UNUSED_PARAMETER") nodeId: String): RefundResult {
        return RefundResult.DUNGEON_FORGE_OWNED
    }

    fun resetTree(@Suppress("UNUSED_PARAMETER") player: Player): ResetResult {
        return ResetResult.DUNGEON_FORGE_OWNED
    }

    /** Development-only character reset: preserves class but clears all accumulated progression. */
    fun adminResetCharacter(player: Player) {
        val data = store.get(player.uniqueId)
        data.level = 1
        data.experience = 0
        data.availablePoints = pointBudgetForLevel(1)
        data.unlockedDifficulty = 1
        clearCombatResources(data)
        plugin.syncDungeonForgeSkillPoints(player, data.availablePoints)?.let { data.availablePoints = it }
        store.save(player.uniqueId)
        plugin.warmUpNodeEffects(player)
    }

    /** Development-only level grant. Each level awards the normal balanced point amount. */
    fun adminLevelUp(player: Player, levels: Int): Int {
        ensurePointBudget(player)
        val data = store.get(player.uniqueId)
        val gained = minOf(levels.coerceAtLeast(0), 100 - data.level)
        repeat(gained) {
            data.level++
            awardLevelPoints(player, data, pointsAwardedForLevel(data.level))
        }
        if (gained > 0) {
            store.save(player.uniqueId)
            plugin.refreshPlayer(player)
        }
        return gained
    }

    /** Admin recovery command for a corrupt ClassSkills profile or attribute state. */
    fun adminHardReset(player: Player) {
        // Keep the combat identity intact so a recovery reset never leaves the player unable
        // to attack under the weapon-lock rules.
        val selectedClass = store.get(player.uniqueId).classType
        val freshData = store.hardReset(player.uniqueId)
        freshData.classType = selectedClass
        freshData.availablePoints = 0
        freshData.pointEconomyVersion = POINT_ECONOMY_VERSION
        freshData.treeLayoutVersion = TREE_LAYOUT_VERSION
        plugin.resetDungeonForgeSkillTree(player)
        // DungeonForge may schedule its point refund after the reset command returns.
        // Queue our clear behind that transaction so a hard reset cannot retain it.
        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.clearDungeonForgeSkillPoints(player)
            store.save(player.uniqueId)
            attributes.forceResetCombatStats(player)
            plugin.warmUpNodeEffects(player)
        })
    }

    /** Migrates older profiles away from ClassSkills-owned node persistence. */
    fun ensurePointBudget(player: Player) {
        val data = store.get(player.uniqueId)
        if (data.treeLayoutVersion < TREE_LAYOUT_VERSION) {
            plugin.dungeonForgeSkillPoints(player)?.let { data.availablePoints = it }
            data.treeLayoutVersion = TREE_LAYOUT_VERSION
            store.save(player.uniqueId)
            player.sendMessage("§eSkill nodes are now managed by DungeonForge. Your class effects will refresh from its active tree.")
        }
        if (data.pointEconomyVersion >= POINT_ECONOMY_VERSION) return
        val target = pointBudgetForLevel(data.level)
        val current = getAvailableSkillPoints(player) + getSpentSkillPoints(player)
        val granted = (target - current).coerceAtLeast(0)
        data.availablePoints += granted
        data.pointEconomyVersion = POINT_ECONOMY_VERSION
        store.save(player.uniqueId)
        if (granted > 0) {
            plugin.grantDungeonForgeSkillPoints(player, granted)?.let { data.availablePoints = it }
            player.sendMessage("§aYour skill-point budget was updated: +$granted Skill Points.")
        }
    }

    /**
     * 201-point progression: a two-point starting budget, then 1 point at Levels 1-33,
     * 2 at Levels 34-65, and 3 at Levels 66-99. Level 100 is the capstone point.
     */
    fun pointBudgetForLevel(level: Int): Int = 1 + when (val capped = level.coerceIn(1, 100)) {
        in 1..33 -> capped
        in 34..65 -> 33 + (capped - 33) * 2
        in 66..99 -> 97 + (capped - 65) * 3
        else -> 200
    }

    private fun pointsAwardedForLevel(newLevel: Int): Int =
        pointBudgetForLevel(newLevel) - pointBudgetForLevel(newLevel - 1)

    /** DungeonForge owns live points when installed; local storage remains the standalone fallback. */
    private fun awardLevelPoints(player: Player, data: PlayerSkillData, amount: Int) {
        val dungeonForgeBalance = plugin.grantDungeonForgeSkillPoints(player, amount)
        if (dungeonForgeBalance == null) data.availablePoints += amount else data.availablePoints = dungeonForgeBalance
    }

    fun resetCost(player: Player): Int {
        val spent = getSpentSkillPoints(player)
        if (spent == 0) return 0
        return ceil(spent * plugin.config.getDouble("bulk-reset-shard-rate", 0.6)).toInt().coerceAtLeast(1)
    }

    fun experienceToNextLevel(player: Player): Int {
        val data = store.get(player.uniqueId)
        return if (data.level >= 100) 0 else xpToNextLevel(data.level)
    }

    fun giveMageStaff(player: Player) = items.give(player, items.mageStaff())

    private fun xpToNextLevel(currentLevel: Int): Int {
        val base = plugin.config.getInt("xp-to-next-level.base", 100)
        val baseRequirement = base +
            plugin.config.getInt("xp-to-next-level.per-current-level", 35) * (currentLevel - 1).coerceAtLeast(0)
        val requirementMultiplier = plugin.config.getDouble("xp-to-next-level.requirement-multiplier", 1.12)
            .coerceIn(0.01, 10.0)
        // Level 1 -> 2 costs exactly the configured base (100 by default). The
        // multiplier still scales the additional requirement of later levels.
        return ceil(base + (baseRequirement - base) * requirementMultiplier).toInt()
    }

    private fun rollSharedDrop(player: Player, difficulty: Int): DungeonDropResult {
        val section = plugin.config.getConfigurationSection("shared-drop-table.$difficulty") ?: return DungeonDropResult()
        val skillWeight = section.getInt("skill-shard", 0).coerceAtLeast(0)
        val soulWeight = section.getInt("soul-shard", 0).coerceAtLeast(0)
        val nothingWeight = section.getInt("nothing", 0).coerceAtLeast(0)
        val total = skillWeight + soulWeight + nothingWeight
        if (total <= 0) return DungeonDropResult()
        // Keep the configured weights as their per-dungeon chances, but roll the two
        // currencies separately. This permits at most one Skill Shard and one Soul Shard.
        val skillShards = if (ThreadLocalRandom.current().nextInt(total) < skillWeight) 1 else 0
        val soulShards = if (ThreadLocalRandom.current().nextInt(total) < soulWeight) 1 else 0
        if (skillShards == 1) items.give(player, items.skillShard())
        if (soulShards == 1) items.give(player, items.soulShard())
        return DungeonDropResult(skillShards, soulShards)
    }

    private fun clearCombatResources(data: PlayerSkillData) {
        data.rage = 0.0
        data.rageActiveUntil = 0L
        data.focus = 0
        data.judgment = 0.0
        data.mana = 0.0
    }

    private fun snapshot(data: PlayerSkillData) = ClassProgress(
        data.level, data.experience, data.availablePoints, data.unlockedDifficulty
    )

    private fun restore(data: PlayerSkillData, classType: ClassType, progress: ClassProgress) {
        data.classType = classType
        data.level = progress.level
        data.experience = progress.experience
        data.availablePoints = progress.availablePoints
        data.unlockedDifficulty = progress.unlockedDifficulty
        data.derivedNodeIds.clear()
    }

    private companion object {
        const val POINT_ECONOMY_VERSION = 6
        const val TREE_LAYOUT_VERSION = 3
    }
}

data class DungeonDropResult(val skillShards: Int = 0, val soulShards: Int = 0)

enum class SelectionResult { SUCCESS, ALREADY_SELECTED, LOCKED, NEEDS_SOUL_SHARD }
enum class PurchaseResult { SUCCESS, UNKNOWN_NODE, WRONG_CLASS, DIFFICULTY_LOCKED, PREREQUISITE_LOCKED, ALREADY_PURCHASED, INSUFFICIENT_POINTS, DUNGEON_FORGE_OWNED }
enum class RefundResult { SUCCESS, UNKNOWN_NODE, NOT_PURCHASED, NEEDS_SKILL_SHARD, HAS_DEPENDENTS, DUNGEON_FORGE_OWNED }
enum class ResetResult { SUCCESS, NOTHING_TO_RESET, NEEDS_SKILL_SHARDS, DUNGEON_FORGE_OWNED }
