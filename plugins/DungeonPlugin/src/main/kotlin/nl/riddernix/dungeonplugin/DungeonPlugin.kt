package nl.riddernix.dungeonplugin

import nl.riddernix.dungeonplugin.classes.AbilityService
import nl.riddernix.dungeonplugin.classes.AttributeService
import nl.riddernix.dungeonplugin.classes.ClassCommands
import nl.riddernix.dungeonplugin.classes.ClassDungeonListener
import nl.riddernix.dungeonplugin.classes.ClassProgressionService
import nl.riddernix.dungeonplugin.classes.ClassesConfig
import nl.riddernix.dungeonplugin.classes.CoreListener
import nl.riddernix.dungeonplugin.classes.DungeonKitService
import nl.riddernix.dungeonplugin.classes.FeedbackService
import nl.riddernix.dungeonplugin.classes.HolographicClassSelection
import nl.riddernix.dungeonplugin.classes.ItemService
import nl.riddernix.dungeonplugin.classes.PassiveService
import nl.riddernix.dungeonplugin.command.DungeonCommand
import nl.riddernix.dungeonplugin.completion.DungeonCompletionManager
import nl.riddernix.dungeonplugin.door.DungeonDoorManager
import nl.riddernix.dungeonplugin.door.DungeonRoomGateManager
import nl.riddernix.dungeonplugin.fx.AnimationPreview
import nl.riddernix.dungeonplugin.internal.DungeonEventBus
import nl.riddernix.dungeonplugin.internal.DungeonQueries
import nl.riddernix.dungeonplugin.internal.DungeonSnapshots
import nl.riddernix.dungeonplugin.menu.PartyMenu
import nl.riddernix.dungeonplugin.menu.PartyMenuListener
import nl.riddernix.dungeonplugin.mob.DungeonMobManager
import nl.riddernix.dungeonplugin.model.ModelIntegration
import nl.riddernix.dungeonplugin.npc.DungeonLordListener
import nl.riddernix.dungeonplugin.npc.DungeonLordManager
import nl.riddernix.dungeonplugin.panel.DifficultyPanelListener
import nl.riddernix.dungeonplugin.panel.DifficultyPanelManager
import nl.riddernix.dungeonplugin.party.PartyListener
import nl.riddernix.dungeonplugin.party.PartyManager
import nl.riddernix.dungeonplugin.player.DungeonHungerListener
import nl.riddernix.dungeonplugin.player.DungeonPvpListener
import nl.riddernix.dungeonplugin.player.DungeonRespawnListener
import nl.riddernix.dungeonplugin.quest.QuestCommand
import nl.riddernix.dungeonplugin.quest.QuestConfig
import nl.riddernix.dungeonplugin.quest.QuestManager
import nl.riddernix.dungeonplugin.quest.QuestMenu
import nl.riddernix.dungeonplugin.quest.QuestMenuListener
import nl.riddernix.dungeonplugin.quest.QuestObjectiveListener
import nl.riddernix.dungeonplugin.room.CorridorLibrary
import nl.riddernix.dungeonplugin.room.DungeonRoomRegistry
import nl.riddernix.dungeonplugin.room.NormalRoomLibrary
import nl.riddernix.dungeonplugin.settings.DungeonSettingsDialog
import nl.riddernix.dungeonplugin.skills.SkillPanelListener
import nl.riddernix.dungeonplugin.skills.SkillPanelManager
import nl.riddernix.dungeonplugin.skills.SkillProgressManager
import nl.riddernix.dungeonplugin.skills.SkillTreeLibrary
import nl.riddernix.dungeonplugin.trap.DungeonTrapManager
import nl.riddernix.dungeonplugin.util.Messages
import nl.riddernix.dungeonplugin.world.DungeonWorldManager
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.TreeMap
import java.util.jar.JarFile

/**
 * Plugin entry point: DungeonForge's dungeon side and ClassSkills' class side
 * merged into one plugin.
 *
 * Coordinates configuration, dungeon-world lifecycle, parties, panels,
 * persistent Dungeon Lord NPCs, and the class layer (progression, passives,
 * abilities, kits).
 */
class DungeonPlugin : JavaPlugin() {

    // --- dungeon side -------------------------------------------------
    lateinit var messages: Messages
        private set
    lateinit var worlds: DungeonWorldManager
        private set
    lateinit var parties: PartyManager
        private set
    lateinit var partyMenu: PartyMenu
        private set
    lateinit var dungeonLords: DungeonLordManager
        private set
    lateinit var command: DungeonCommand
        private set
    lateinit var dungeonLordKey: NamespacedKey
        private set
    lateinit var rooms: DungeonRoomRegistry
        private set
    lateinit var normalRooms: NormalRoomLibrary
        private set
    lateinit var corridors: CorridorLibrary
        private set
    lateinit var mobs: DungeonMobManager
        private set
    lateinit var completions: DungeonCompletionManager
        private set
    lateinit var doors: DungeonDoorManager
        private set
    lateinit var gates: DungeonRoomGateManager
        private set
    lateinit var traps: DungeonTrapManager
        private set
    lateinit var events: DungeonEventBus
        private set
    lateinit var snapshots: DungeonSnapshots
        private set
    lateinit var queries: DungeonQueries
        private set
    lateinit var settings: DungeonSettingsDialog
        private set
    lateinit var models: ModelIntegration
        private set
    lateinit var animations: AnimationPreview
        private set
    lateinit var panels: DifficultyPanelManager
        private set
    lateinit var skillTrees: SkillTreeLibrary
        private set
    lateinit var skillProgress: SkillProgressManager
        private set
    lateinit var skillPanels: SkillPanelManager
        private set

    // --- quest layer ------------------------------------------------
    lateinit var questConfig: QuestConfig
        private set
    lateinit var quests: QuestManager
        private set
    lateinit var questMenu: QuestMenu
        private set

    lateinit var dungeonMobDungeonKey: NamespacedKey
        private set
    lateinit var dungeonMobRoomKey: NamespacedKey
        private set
    lateinit var dungeonMobTierKey: NamespacedKey
        private set
    lateinit var dungeonMobDifficultyKey: NamespacedKey
        private set
    lateinit var dungeonMobBossKey: NamespacedKey
        private set
    lateinit var dungeonMobBossThemeKey: NamespacedKey
        private set
    lateinit var dungeonMobCategoryKey: NamespacedKey
        private set
    lateinit var dungeonMobTestKey: NamespacedKey
        private set
    lateinit var panelIdKey: NamespacedKey
        private set
    lateinit var panelRoleKey: NamespacedKey
        private set
    lateinit var skillPanelIdKey: NamespacedKey
        private set
    lateinit var skillPanelRoleKey: NamespacedKey
        private set

    // --- class side ---------------------------------------------------
    lateinit var classesConfig: ClassesConfig
        private set
    lateinit var classes: ClassProgressionService
        private set
    lateinit var classItems: ItemService
        private set
    lateinit var classKits: DungeonKitService
        private set
    lateinit var classAttributes: AttributeService
        private set
    lateinit var classPassives: PassiveService
        private set
    lateinit var classAbilities: AbilityService
        private set
    lateinit var classFeedback: FeedbackService
        private set
    lateinit var classPicker: HolographicClassSelection
        private set

    private var coreListener: CoreListener? = null
    private var roomScanTask: BukkitTask? = null

    /** True once the plugin is shutting down, so cleanup is not read as abandonment. */
    var isStopping: Boolean = false
        private set

    override fun onEnable() {
        migrateConfiguration()

        messages = Messages(this)
        // Before the libraries read their folders, so a fresh install has
        // rooms to load rather than falling back to procedural stone.
        extractBundledSchematics()
        worlds = DungeonWorldManager(this)
        rooms = DungeonRoomRegistry(this)
        normalRooms = NormalRoomLibrary(this)
        normalRooms.reload()
        corridors = CorridorLibrary(this)
        corridors.reload()
        reportPrefabMarkerConventions()
        dungeonMobDungeonKey = NamespacedKey(this, "dungeon_mob_dungeon")
        dungeonMobRoomKey = NamespacedKey(this, "dungeon_mob_room")
        dungeonMobTierKey = NamespacedKey(this, "dungeon_mob_tier")
        dungeonMobDifficultyKey = NamespacedKey(this, "dungeon_mob_difficulty")
        dungeonMobBossKey = NamespacedKey(this, "dungeon_mob_boss")
        dungeonMobBossThemeKey = NamespacedKey(this, "dungeon_mob_boss_theme")
        dungeonMobCategoryKey = NamespacedKey(this, "dungeon_mob_category")
        dungeonMobTestKey = NamespacedKey(this, "dungeon_mob_test")
        // Built before anything that can fire: every event goes through the
        // bus, and every snapshot handed out is built by the snapshotter.
        events = DungeonEventBus(this)
        snapshots = DungeonSnapshots(this)
        mobs = DungeonMobManager(this)
        completions = DungeonCompletionManager(this)
        doors = DungeonDoorManager(this)
        gates = DungeonRoomGateManager(this)
        traps = DungeonTrapManager(this)
        queries = DungeonQueries(this)
        settings = DungeonSettingsDialog(this)
        animations = AnimationPreview(this)
        parties = PartyManager()
        parties.reload(config)
        partyMenu = PartyMenu(this)
        dungeonLordKey = NamespacedKey(this, "dungeon_lord")
        dungeonLords = DungeonLordManager(this)
        dungeonLords.load()
        panelIdKey = NamespacedKey(this, "dungeon_panel")
        panelRoleKey = NamespacedKey(this, "dungeon_panel_role")
        panels = DifficultyPanelManager(this)
        panels.load()
        // Separate keys from the difficulty panel, so each manager's orphan
        // sweep can never mistake the other's entities for its own strays.
        skillPanelIdKey = NamespacedKey(this, "dungeon_skill_panel")
        skillPanelRoleKey = NamespacedKey(this, "dungeon_skill_panel_role")
        skillTrees = SkillTreeLibrary(this)

        // The class layer, built before the skill progress manager starts
        // answering point queries: available points derive from its budget.
        classesConfig = ClassesConfig(this)
        classItems = ItemService(this)
        classes = ClassProgressionService(this)
        skillProgress = SkillProgressManager(this)
        classAttributes = AttributeService(this)
        classKits = DungeonKitService(this)
        classPassives = PassiveService(this)
        classAbilities = AbilityService(this)
        classFeedback = FeedbackService(this)
        classPicker = HolographicClassSelection(this)

        skillPanels = SkillPanelManager(this)
        skillPanels.load()

        // The quest layer. The menu is built before the manager so a /reload
        // with players online can redraw an open quest screen during the
        // manager's start-up catch-up refresh.
        questConfig = QuestConfig(this)
        questMenu = QuestMenu(this)
        quests = QuestManager(this)

        // Clean up anything left behind by a crash or a /stop while inside a
        // dungeon, so old world folders don't pile up.
        val purged = worlds.purgeOrphanedWorldFolders()
        if (purged > 0) {
            logger.info("Cleaned up $purged leftover dungeon world folder(s).")
        }

        val dungeonCommand = getCommand("dungeon")
        if (dungeonCommand == null) {
            logger.severe("Command 'dungeon' is missing from plugin.yml - disabling plugin.")
            server.pluginManager.disablePlugin(this)
            return
        }
        command = DungeonCommand(this)
        dungeonCommand.setExecutor(command)
        dungeonCommand.tabCompleter = command
        server.pluginManager.registerEvents(PartyListener(this), this)
        server.pluginManager.registerEvents(PartyMenuListener(this), this)
        server.pluginManager.registerEvents(mobs, this)
        server.pluginManager.registerEvents(doors, this)
        server.pluginManager.registerEvents(gates, this)
        server.pluginManager.registerEvents(traps, this)
        // Built after the mob manager so it can listen to its spawn event,
        // and registered unconditionally: it simply does nothing without an
        // engine.
        models = ModelIntegration(this)
        server.pluginManager.registerEvents(models, this)
        server.pluginManager.registerEvents(DungeonHungerListener(this), this)
        server.pluginManager.registerEvents(DungeonPvpListener(this), this)
        server.pluginManager.registerEvents(DungeonRespawnListener(this), this)
        server.pluginManager.registerEvents(DungeonLordListener(this), this)
        server.pluginManager.registerEvents(DifficultyPanelListener(this), this)
        server.pluginManager.registerEvents(SkillPanelListener(this), this)
        server.pluginManager.registerEvents(QuestMenuListener(this), this)
        server.pluginManager.registerEvents(QuestObjectiveListener(this), this)
        getCommand("quests")?.let {
            val questCommand = QuestCommand(this)
            it.setExecutor(questCommand)
            it.tabCompleter = questCommand
        } ?: logger.severe("Command 'quests' is missing from plugin.yml.")

        // The class layer's listeners and commands, active only when enabled
        // - a dungeons-only server skips all of it.
        if (classes.enabled) {
            val core = CoreListener(this)
            coreListener = core
            server.pluginManager.registerEvents(core, this)
            server.pluginManager.registerEvents(classAbilities, this)
            server.pluginManager.registerEvents(classPicker, this)
            server.pluginManager.registerEvents(ClassDungeonListener(this), this)
            val classCommands = ClassCommands(this)
            for (name in listOf("class", "skills", "skillshard", "soulshard")) {
                val registered = getCommand(name)
                if (registered == null) {
                    logger.severe("Command '$name' is missing from plugin.yml.")
                    continue
                }
                registered.setExecutor(classCommands)
                registered.tabCompleter = classCommands
            }
            server.scheduler.runTaskTimer(this, Runnable {
                classPassives.tick()
                classAbilities.tick()
                for (player in server.onlinePlayers) {
                    coreListener?.stripArmor(player)
                    refreshClassPlayer(player)
                }
            }, 20L, 20L)
        }

        server.scheduler.runTaskTimer(this, Runnable {
            for (worldName in parties.collectExpiredOfflineWorlds()) {
                worlds.deleteWorld(worldName)
            }
        }, 1200L, 1200L)
        server.scheduler.runTaskTimer(this, Runnable { dungeonLords.maintain() }, 100L, 100L)
        startRoomScanTask()
        val recountInterval = maxOf(1L, config.getLong("mobs.recount-interval-ticks", 40L))
        server.scheduler.runTaskTimer(this, Runnable { mobs.recount() }, recountInterval, recountInterval)
        val doorInterval = maxOf(1L, config.getLong("door.watchdog.interval-ticks", 100L))
        server.scheduler.runTaskTimer(this, Runnable { doors.watchdog() }, doorInterval, doorInterval)
        server.scheduler.runTaskTimer(this, Runnable { traps.tick() }, 10L, 10L)
        // Personal panel rows follow players in and out of range a few times
        // per second; clicks themselves are handled by events, not this task.
        server.scheduler.runTaskTimer(this, Runnable { panels.tick() }, 10L, 10L)
        server.scheduler.runTaskTimer(this, Runnable { skillPanels.tick() }, 10L, 10L)
        // Rolls daily/weekly quests when their zone boundary passes. A
        // boundary missed while offline is already caught up in the manager's
        // constructor, so this only handles the server-running case.
        val questCheckTicks = maxOf(100L, questConfig.refreshCheckSeconds() * 20L)
        server.scheduler.runTaskTimer(this, Runnable {
            quests.checkScheduledRefresh()
            quests.flushIfDirty()
        }, questCheckTicks, questCheckTicks)

        logger.info("DungeonPlugin enabled with ${events.eventTypes().size} internal event type(s); " +
            "classes ${if (classes.enabled) "enabled" else "disabled"}.")
    }

    override fun onDisable() {
        // Tells the world manager that dungeons disappearing from here on are
        // being cleaned up rather than abandoned by their party.
        isStopping = true
        if (this::animations.isInitialized) {
            // Preview stand-ins belong to a session, never to a saved world.
            animations.stopAll()
        }
        if (this::panels.isInitialized) {
            // Panels respawn from panels.yml on the next enable.
            panels.despawnAll()
        }
        if (this::skillPanels.isInitialized) {
            skillPanels.despawnAll()
        }
        if (this::mobs.isInitialized) {
            mobs.saveTestingMobLocations()
        }
        if (this::skillProgress.isInitialized) {
            skillProgress.save()
        }
        if (this::quests.isInitialized) {
            quests.save()
        }
        if (this::classAbilities.isInitialized) {
            classAbilities.shutdown()
        }
        if (this::classPicker.isInitialized) {
            classPicker.shutdown()
        }
        if (this::classes.isInitialized) {
            classes.save()
        }
        if (this::worlds.isInitialized) {
            worlds.deleteAll()
        }
        logger.info("DungeonPlugin disabled.")
    }

    /** Reloads config.yml and everything that depends on it. */
    fun reloadEverything() {
        migrateConfiguration()
        reloadConfig()
        messages.reload()
        worlds.reload()
        parties.reload(config)
        dungeonLords.reload()
        normalRooms.reload()
        corridors.reload()
        reportPrefabMarkerConventions()
        models.reload()
        panels.reload()
        skillTrees.reload()
        skillPanels.reload()
        classesConfig.reload()
        questConfig.reload()
        // Pool or timezone may have changed; catch up any boundary that now
        // counts as passed.
        quests.checkScheduledRefresh()
        startRoomScanTask()
    }

    /** One refresh path for the class layer: attributes, sidebar, tab name. */
    fun refreshClassPlayer(player: Player) {
        if (!this::classes.isInitialized || !classes.enabled) return
        classes.syncDifficultyToLevel(player)
        classAttributes.apply(player)
        classFeedback.refresh(player)
    }

    /**
     * Markers are intentionally allowed to sit outside a build's structural
     * shell. What must agree is their vertical convention across all authored
     * room and corridor files.
     */
    private fun reportPrefabMarkerConventions() {
        val filesByOffset = TreeMap<Int, MutableList<String>>()
        for (inspection in normalRooms.inspections()) {
            for (offset in inspection.markerVerticalOffsets) {
                filesByOffset.getOrPut(offset) { ArrayList() }.add("room ${inspection.fileName}")
            }
        }
        for (inspection in corridors.inspections()) {
            for (offset in inspection.markerVerticalOffsets) {
                filesByOffset.getOrPut(offset) { ArrayList() }.add("corridor ${inspection.fileName}")
            }
        }
        if (filesByOffset.size <= 1) return

        val convention = filesByOffset.entries
            .maxWithOrNull(compareBy<Map.Entry<Int, List<String>>> { it.value.size }.thenBy { it.key })
            ?: return
        val oddFiles = ArrayList<String>()
        for ((offset, files) in filesByOffset) {
            if (offset != convention.key) {
                oddFiles.add("y${signed(offset)}: ${files.joinToString(", ")}")
            }
        }
        logger.warning("Prefab marker vertical-offset mismatch. Common convention is y${signed(convention.key)}" +
            " above structural content; files to re-save: ${oddFiles.joinToString(" | ")}" +
            ". All groups: " + filesByOffset.entries.joinToString("; ") { (offset, files) ->
                "y${signed(offset)}=${files.joinToString(", ")}"
            })
    }

    private fun startRoomScanTask() {
        roomScanTask?.cancel()
        val interval = maxOf(1L, config.getLong("rooms.detection-interval-ticks", 10L))
        roomScanTask = server.scheduler.runTaskTimer(this, Runnable { rooms.scanPlayers() }, interval, interval)
    }

    /**
     * Unpacks the schematics bundled in the jar into their live folders.
     *
     * Only ever into an *empty* folder: the moment a server has its own
     * schematics, those are the truth and the bundled copies stay in the jar.
     * That keeps a fresh install working out of the box without ever fighting
     * a hand-edited room, and dropping a new file into the folder still needs
     * no rebuild.
     */
    private fun extractBundledSchematics() {
        for (folder in listOf("rooms", "corridors")) {
            val target = File(dataFolder, folder)
            if (!target.isDirectory && !target.mkdirs()) {
                logger.severe("Could not create ${target.absolutePath}.")
                continue
            }
            val present = target.listFiles { file: File ->
                file.isFile && file.name.lowercase(Locale.ROOT).endsWith(".schem")
            }
            if (present != null && present.isNotEmpty()) {
                continue
            }
            var extracted = 0
            // Enumerated from the jar rather than a hardcoded list, so adding
            // a schematic to the resources is all it takes to ship it.
            try {
                JarFile(file).use { jar ->
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (entry.isDirectory || !name.startsWith("$folder/") ||
                            !name.lowercase(Locale.ROOT).endsWith(".schem")) {
                            continue
                        }
                        saveResource(name, false)
                        extracted++
                    }
                }
            } catch (exception: IOException) {
                logger.severe("Could not read bundled schematics: ${exception.message}")
                continue
            }
            if (extracted > 0) {
                logger.info("Unpacked $extracted bundled schematic(s) into $folder" +
                    "/. Edit or replace them freely; they are only written when the folder is empty.")
            }
        }
    }

    /** Replaces outdated configuration files after preserving a timestamped backup. */
    private fun migrateConfiguration() {
        val configFile = File(dataFolder, "config.yml")
        if (!configFile.isFile) {
            saveDefaultConfig()
            return
        }

        val installedVersion = YamlConfiguration.loadConfiguration(configFile).getInt("config-version", 0)
        if (installedVersion >= CONFIG_VERSION) {
            return
        }

        val backup = File(dataFolder, "config.yml.v$installedVersion.${System.currentTimeMillis()}.bak")
        try {
            Files.copy(configFile.toPath(), backup.toPath())
            saveResource("config.yml", true)
            logger.info("Replaced outdated config.yml with version $CONFIG_VERSION" +
                "; previous configuration backed up as ${backup.name}.")
        } catch (ex: IOException) {
            logger.severe("Could not back up outdated config.yml: ${ex.message}")
        }
    }

    companion object {
        /**
         * DungeonPlugin restarts the config lineage at 1: its bundled
         * config.yml is the merge of DungeonForge's v76 content, so an old
         * DungeonForge file dropped into this folder would be replaced on the
         * spot either way.
         */
        private const val CONFIG_VERSION = 1

        private fun signed(value: Int): String = if (value >= 0) "+$value" else value.toString()
    }
}
