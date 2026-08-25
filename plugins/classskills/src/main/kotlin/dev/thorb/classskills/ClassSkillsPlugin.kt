package dev.thorb.classskills

import dev.thorb.classskills.api.DungeonSkillService
import dev.thorb.classskills.api.ClassSkillsApi
import dev.thorb.classskills.command.ClassSkillsCommand
import dev.thorb.classskills.data.PlayerDataStore
import dev.thorb.classskills.integration.DungeonForgeBridge
import dev.thorb.classskills.listener.CoreListener
import dev.thorb.classskills.menu.ClassScreenDialog
import dev.thorb.classskills.menu.SkillTreeDialog
import dev.thorb.classskills.model.SkillTreeCatalog
import dev.thorb.classskills.model.ClassType
import dev.thorb.classskills.service.AttributeService
import dev.thorb.classskills.service.ItemService
import dev.thorb.classskills.service.DungeonKitService
import dev.thorb.classskills.service.PassiveService
import dev.thorb.classskills.service.ProgressionService
import dev.thorb.classskills.service.AbilityService
import dev.thorb.classskills.ui.FeedbackService
import dev.thorb.classskills.ui.SkillMenus
import dev.thorb.classskills.ui.HolographicSkillTree
import dev.thorb.classskills.ui.HolographicClassSelection
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

class ClassSkillsPlugin : JavaPlugin() {
    lateinit var store: PlayerDataStore
        private set
    lateinit var catalog: SkillTreeCatalog
        private set
    lateinit var items: ItemService
        private set
    lateinit var dungeonKits: DungeonKitService
        private set
    lateinit var attributes: AttributeService
        private set
    lateinit var progression: ProgressionService
        private set
    lateinit var passives: PassiveService
        private set
    lateinit var abilities: AbilityService
        private set
    lateinit var feedback: FeedbackService
        private set
    lateinit var menus: SkillMenus
        private set
    lateinit var classScreen: ClassScreenDialog
        private set
    lateinit var skillScreen: SkillTreeDialog
        private set
    lateinit var holographicSkillTree: HolographicSkillTree
        private set
    lateinit var holographicClassSelection: HolographicClassSelection
        private set
    private lateinit var dungeonForge: DungeonForgeBridge
    private lateinit var coreListener: CoreListener

    override fun onEnable() {
        saveDefaultConfig()
        // Read this before defaults are added so an older server config is migrated once.
        val existingShardDropTableVersion = config.getInt("balance.shard-drop-table-version", 0)
        val existingDungeonXpBalanceVersion = config.getInt("balance.dungeon-xp-balance-version", 0)
        val existingXpRequirementBalanceVersion = config.getInt("balance.xp-requirement-balance-version", 0)
        val existingAbilityBalanceVersion = config.getInt("balance.ability-balance-version", 0)
        val existingArcaneBoltSplashVersion = config.getInt("balance.arcane-bolt-splash-version", 0)
        // Add new balance keys to existing installations without overwriting their choices.
        config.addDefault("focus.full-draw-speed-percent", 25.0)
        config.addDefault("archer.attack-stat-damage-multiplier", 1.0)
        config.addDefault("dungeon-xp-multiplier", 0.20)
        config.addDefault("xp-to-next-level.requirement-multiplier", 1.12)
        config.addDefault("balance.shard-drop-table-version", 2)
        config.addDefault("balance.dungeon-xp-balance-version", 4)
        config.addDefault("balance.xp-requirement-balance-version", 4)
        config.addDefault("balance.ability-balance-version", 4)
        config.addDefault("balance.arcane-bolt-splash-version", 2)
        val completionXpDefaults = listOf(100, 125, 155, 190, 230, 275, 325, 380, 440)
        completionXpDefaults.forEachIndexed { index, amount ->
            config.addDefault("completion-xp-per-difficulty.${index + 1}", amount)
        }
        config.addDefault("mage.base-max-mana", 200.0)
        config.addDefault("mage.mana-regeneration-per-second", 10.0)
        config.addDefault("mage.arcane-bolt-mana-cost", 10.0)
        config.addDefault("mage.arcane-bolt-base-damage", 5.0)
        config.addDefault("mage.arcane-bolt-damage-per-rank", 1.5)
        config.addDefault("mage.arcane-bolt-cooldown-ticks", 8)
        config.addDefault("mage.arcane-bolt-splash-radius", 1.5)
        config.addDefault("mage.arcane-bolt-splash-damage-percent", 50.0)
        config.addDefault("warrior.rage-decay-delay-seconds", 15.0)
        config.addDefault("warrior.rage-decay-per-second", 10.0)
        config.addDefault("paladin.taunt-damage-threshold", 75.0)
        config.addDefault("paladin.taunt-duration-seconds", 8.0)
        config.addDefault("paladin.turtle-master-resistance-amplifier", 2)
        config.addDefault("paladin.turtle-master-slowness-amplifier", 3)
        config.addDefault("abilities.warrior.cooldown-seconds", 5.0)
        config.addDefault("abilities.warrior.dash-speed", 1.5)
        config.addDefault("abilities.warrior.bonus-damage", 4.0)
        config.addDefault("abilities.archer.cooldown-seconds", 2.5)
        config.addDefault("abilities.archer.jump-velocity", 0.9)
        config.addDefault("abilities.paladin.cooldown-seconds", 7.5)
        config.addDefault("abilities.paladin.shield-seconds", 4.0)
        config.addDefault("abilities.paladin.absorption-amplifier", 1)
        config.addDefault("abilities.paladin.shield-hearts", 5.0)
        config.addDefault("abilities.mage.cooldown-seconds", 5.0)
        config.addDefault("abilities.mage.blink-distance", 10.0)
        config.addDefault("abilities.mage.blink-mana-cost", 35.0)
        config.addDefault("abilities.mage.heal-cooldown-seconds", 30.0)
        config.addDefault("abilities.mage.heal-mana-cost", 50.0)
        config.addDefault("abilities.mage.heal-range", 20.0)
        // Version 0.3.2 raises the Mage baseline; retain any other config choices.
        if (config.getDouble("mage.mana-regeneration-per-second", 5.0) == 5.0) {
            config.set("mage.mana-regeneration-per-second", 10.0)
        }
        if (config.getDouble("paladin.taunt-damage-threshold", 50.0) == 50.0) {
            config.set("paladin.taunt-damage-threshold", 75.0)
        }
        if (existingShardDropTableVersion < 2) {
            // Each row totals 100, so Skill/Soul weights directly correspond to the
            // independent per-dungeon percentage rolls used by the reward system.
            (1..9).forEach { difficulty ->
                val skillChance = 10 + (difficulty - 1) * 5
                val soulChance = 2 + (difficulty - 1)
                config.set("shared-drop-table.$difficulty.skill-shard", skillChance)
                config.set("shared-drop-table.$difficulty.soul-shard", soulChance)
                config.set("shared-drop-table.$difficulty.nothing", 100 - skillChance - soulChance)
            }
            config.set("balance.shard-drop-table-version", 2)
        }
        // Retune previous defaults while retaining administrator-selected custom values.
        if (existingDungeonXpBalanceVersion < 4 && config.getDouble("dungeon-xp-multiplier", 0.25) == 0.25) {
            config.set("dungeon-xp-multiplier", 0.20)
            config.set("balance.dungeon-xp-balance-version", 4)
        }
        if (existingXpRequirementBalanceVersion < 4 &&
            config.getDouble("xp-to-next-level.requirement-multiplier", 1.10) == 1.10
        ) {
            config.set("xp-to-next-level.requirement-multiplier", 1.12)
            config.set("balance.xp-requirement-balance-version", 4)
        }
        if (existingAbilityBalanceVersion < 2) {
            if (config.getDouble("abilities.archer.cooldown-seconds", 5.0) == 5.0) {
                config.set("abilities.archer.cooldown-seconds", 2.5)
            }
            if (config.getDouble("abilities.paladin.cooldown-seconds", 8.0) == 8.0) {
                config.set("abilities.paladin.cooldown-seconds", 7.5)
            }
            if (config.getDouble("abilities.mage.blink-distance", 7.0) == 7.0) {
                config.set("abilities.mage.blink-distance", 10.0)
            }
            config.set("balance.ability-balance-version", 2)
        }
        if (existingAbilityBalanceVersion < 3) {
            if (config.getDouble("abilities.archer.jump-velocity", 0.62) == 0.62) {
                config.set("abilities.archer.jump-velocity", 0.9)
            }
            config.set("balance.ability-balance-version", 3)
        }
        if (existingAbilityBalanceVersion < 4) {
            // Migrate the former default, but retain any server-specific Mana tuning.
            if (config.getDouble("mage.arcane-bolt-mana-cost", 20.0) == 20.0) {
                config.set("mage.arcane-bolt-mana-cost", 10.0)
            }
            config.set("balance.ability-balance-version", 4)
        }
        if (existingArcaneBoltSplashVersion < 2) {
            // Retune the original 3-block default without changing administrator-selected ranges.
            if (config.getDouble("mage.arcane-bolt-splash-radius", 3.0) == 3.0) {
                config.set("mage.arcane-bolt-splash-radius", 1.5)
            }
            config.set("balance.arcane-bolt-splash-version", 2)
        }
        config.options().copyDefaults(true)
        saveConfig()
        store = PlayerDataStore(this).also { it.load() }
        catalog = SkillTreeCatalog()
        items = ItemService(this)
        dungeonKits = DungeonKitService(this, store, items)
        attributes = AttributeService(this, catalog)
        progression = ProgressionService(this, store, catalog, items, attributes)
        dungeonForge = DungeonForgeBridge(this).also { it.connect() }
        passives = PassiveService(this, store, catalog, items)
        abilities = AbilityService(this)
        feedback = FeedbackService(store, catalog, passives) { player -> dungeonForge.skillPoints(player) }
        menus = SkillMenus(this)
        classScreen = ClassScreenDialog(this)
        skillScreen = SkillTreeDialog(this)
        holographicSkillTree = HolographicSkillTree(this)
        holographicClassSelection = HolographicClassSelection(this)
        coreListener = CoreListener(this)

        server.servicesManager.register(DungeonSkillService::class.java, progression, this, ServicePriority.Normal)
        server.servicesManager.register(ClassSkillsApi::class.java, progression, this, ServicePriority.Normal)
        server.pluginManager.registerEvents(coreListener, this)
        server.pluginManager.registerEvents(abilities, this)
        server.pluginManager.registerEvents(menus, this)
        server.pluginManager.registerEvents(holographicSkillTree, this)
        server.pluginManager.registerEvents(holographicClassSelection, this)
        val command = ClassSkillsCommand(this)
        getCommand("class")?.setExecutor(command)
        getCommand("class")?.tabCompleter = command
        getCommand("skills")?.setExecutor(command)
        getCommand("skills")?.tabCompleter = command
        getCommand("skillshard")?.setExecutor(command)
        getCommand("skillshard")?.tabCompleter = command
        getCommand("soulshard")?.setExecutor(command)
        getCommand("soulshard")?.tabCompleter = command

        server.scheduler.runTaskTimer(this, Runnable {
            dungeonForge.syncOnlinePlayers()
            passives.tick()
            server.onlinePlayers.forEach { player ->
                coreListener.stripArmor(player)
                refreshPlayer(player)
            }
            abilities.tick()
        }, 20L, 20L)
        server.onlinePlayers.forEach(::warmUpNodeEffects)
        logger.info("ClassSkills enabled with ${catalog.nodes.count { it.classType == ClassType.WARRIOR }} nodes per class tree.")
    }

    override fun onDisable() {
        if (::abilities.isInitialized) abilities.shutdown()
        if (::holographicClassSelection.isInitialized) holographicClassSelection.shutdown()
        if (::store.isInitialized) store.saveAll()
        server.servicesManager.unregisterAll(this)
    }

    fun refreshPlayer(player: Player) {
        store.get(player.uniqueId).classType?.let { syncDungeonForgeClass(player, it) }
        progression.ensurePointBudget(player)
        progression.syncDifficultyToLevel(player)
        attributes.apply(player, store.get(player.uniqueId))
        feedback.refresh(player)
    }

    /** Rebuilds the non-persistent node-effect projection after the class profile is ready. */
    fun warmUpNodeEffects(player: Player) {
        store.get(player.uniqueId).classType?.let { syncDungeonForgeClass(player, it) }
        if (!dungeonForge.refreshNodeEffects(player)) {
            progression.applyNodeEffects(player, emptySet())
        }
        refreshPlayer(player)
    }

    fun isInDungeon(player: Player): Boolean = ::dungeonForge.isInitialized && dungeonForge.isInDungeon(player)
    fun isDungeonMob(entity: org.bukkit.entity.Entity): Boolean = ::dungeonForge.isInitialized && dungeonForge.isDungeonMob(entity)
    fun dungeonDifficulty(player: Player): Int? = if (::dungeonForge.isInitialized) dungeonForge.difficulty(player) else null
    fun grantDungeonForgeSkillPoints(player: Player, amount: Int): Int? =
        if (::dungeonForge.isInitialized) dungeonForge.grantSkillPoints(player, amount) else null
    fun dungeonForgeSkillPoints(player: Player): Int? =
        if (::dungeonForge.isInitialized) dungeonForge.skillPoints(player) else null
    fun dungeonForgeUnlockedSkillNodeIds(player: Player): Set<String> =
        if (::dungeonForge.isInitialized) dungeonForge.unlockedClassSkillsNodeIds(player) ?: emptySet() else emptySet()
    fun selectDungeonForgeClass(player: Player, classType: ClassType): Boolean? =
        if (::dungeonForge.isInitialized) dungeonForge.setActiveClass(player, classType.name.lowercase()) else null
    fun syncDungeonForgeSkillPoints(player: Player, desired: Int): Int? =
        if (::dungeonForge.isInitialized) dungeonForge.syncSkillPoints(player, desired) else null
    fun syncDungeonForgeClass(player: Player, classType: ClassType): Boolean? =
        if (::dungeonForge.isInitialized) dungeonForge.syncActiveClass(player, classType.name.lowercase()) else null
    fun clearDungeonForgeSkillPoints(player: Player): Boolean? =
        if (::dungeonForge.isInitialized) dungeonForge.clearSkillPoints(player) else null
    /** Runs DungeonForge's player-facing full tree reset for ClassSkills hard resets. */
    fun resetDungeonForgeSkillTree(player: Player): Boolean =
        ::dungeonForge.isInitialized && player.performCommand("dungeon skills reset")
    fun recordDungeonMobKill(player: Player, difficulty: Int) {
        if (::dungeonForge.isInitialized) dungeonForge.recordDungeonMobKill(player, difficulty)
    }
    fun abandonDungeonRun(player: Player) {
        if (::dungeonForge.isInitialized) dungeonForge.abandonRun(player)
    }
    fun rememberSafeLocation(player: Player) {
        if (::dungeonForge.isInitialized && !dungeonForge.isInDungeon(player)) dungeonForge.rememberSafeLocation(player)
    }
}
