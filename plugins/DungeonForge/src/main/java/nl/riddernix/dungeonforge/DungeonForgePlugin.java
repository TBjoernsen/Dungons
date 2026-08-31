package nl.riddernix.dungeonforge;

import nl.riddernix.dungeonforge.command.DungeonCommand;
import nl.riddernix.dungeonforge.completion.DungeonCompletionManager;
import nl.riddernix.dungeonforge.door.DungeonDoorManager;
import nl.riddernix.dungeonforge.door.DungeonRoomGateManager;
import nl.riddernix.dungeonforge.trap.DungeonTrapManager;
import nl.riddernix.dungeonforge.fx.AnimationPreview;
import nl.riddernix.dungeonforge.model.ModelIntegration;
import nl.riddernix.dungeonforge.panel.DifficultyPanelListener;
import nl.riddernix.dungeonforge.panel.DifficultyPanelManager;
import nl.riddernix.dungeonforge.menu.DungeonMenu;
import nl.riddernix.dungeonforge.menu.DungeonMenuListener;
import nl.riddernix.dungeonforge.menu.PartyMenu;
import nl.riddernix.dungeonforge.menu.PartyMenuListener;
import nl.riddernix.dungeonforge.mob.DungeonMobManager;
import nl.riddernix.dungeonforge.api.DungeonForgeApi;
import nl.riddernix.dungeonforge.internal.DungeonEventBus;
import nl.riddernix.dungeonforge.internal.DungeonForgeApiImpl;
import nl.riddernix.dungeonforge.internal.DungeonSnapshots;
import nl.riddernix.dungeonforge.npc.DungeonLordListener;
import nl.riddernix.dungeonforge.npc.DungeonLordManager;
import nl.riddernix.dungeonforge.party.PartyListener;
import nl.riddernix.dungeonforge.party.PartyManager;
import nl.riddernix.dungeonforge.player.DungeonHungerListener;
import nl.riddernix.dungeonforge.player.DungeonPvpListener;
import nl.riddernix.dungeonforge.player.DungeonRespawnListener;
import nl.riddernix.dungeonforge.settings.DungeonSettingsDialog;
import nl.riddernix.dungeonforge.skills.ClassSkillsIntegration;
import nl.riddernix.dungeonforge.skills.SkillPanelListener;
import nl.riddernix.dungeonforge.skills.SkillPanelManager;
import nl.riddernix.dungeonforge.skills.SkillProgressManager;
import nl.riddernix.dungeonforge.skills.SkillTreeLibrary;
import nl.riddernix.dungeonforge.room.DungeonRoomRegistry;
import nl.riddernix.dungeonforge.room.CorridorLibrary;
import nl.riddernix.dungeonforge.room.NormalRoomLibrary;
import nl.riddernix.dungeonforge.util.Messages;
import nl.riddernix.dungeonforge.world.DungeonWorldManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.ServicePriority;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plugin entry point.
 *
 * <p>Coordinates configuration, dungeon-world lifecycle, parties, the
 * difficulty menu, and persistent Dungeon Lord NPCs.</p>
 */
public final class DungeonForgePlugin extends JavaPlugin {

    private static final int CONFIG_VERSION = 76;

    private Messages messages;
    private DungeonWorldManager worldManager;
    private PartyManager partyManager;
    private DungeonMenu dungeonMenu;
    private PartyMenu partyMenu;
    private DungeonLordManager dungeonLordManager;
    private DungeonCommand dungeonCommand;
    private NamespacedKey dungeonLordKey;
    private DungeonRoomRegistry roomRegistry;
    private NormalRoomLibrary normalRoomLibrary;
    private CorridorLibrary corridorLibrary;
    private BukkitTask roomScanTask;
    private DungeonMobManager dungeonMobManager;
    private DungeonCompletionManager dungeonCompletionManager;
    private DungeonDoorManager dungeonDoorManager;
    private DungeonRoomGateManager dungeonRoomGateManager;
    private DungeonTrapManager dungeonTrapManager;
    private DungeonForgeApi api;
    private NamespacedKey dungeonMobDungeonKey;
    private NamespacedKey dungeonMobRoomKey;
    private NamespacedKey dungeonMobTierKey;
    private NamespacedKey dungeonMobDifficultyKey;
    private NamespacedKey dungeonMobBossKey;
    private NamespacedKey dungeonMobBossThemeKey;
    private NamespacedKey dungeonMobCategoryKey;
    private NamespacedKey dungeonMobTestKey;
    private DungeonEventBus eventBus;
    private DungeonSnapshots snapshots;
    private boolean stopping;
    private DungeonSettingsDialog settingsDialog;
    private ModelIntegration modelIntegration;
    private AnimationPreview animationPreview;
    private DifficultyPanelManager panelManager;
    private NamespacedKey panelIdKey;
    private NamespacedKey panelRoleKey;
    private SkillTreeLibrary skillTreeLibrary;
    private SkillPanelManager skillPanelManager;
    private SkillProgressManager skillProgressManager;
    private ClassSkillsIntegration classSkillsIntegration;
    private NamespacedKey skillPanelIdKey;
    private NamespacedKey skillPanelRoleKey;

    @Override
    public void onEnable() {
        migrateConfiguration();

        this.messages = new Messages(this);
        // Before the libraries read their folders, so a fresh install has
        // rooms to load rather than falling back to procedural stone.
        extractBundledSchematics();
        this.worldManager = new DungeonWorldManager(this);
        this.roomRegistry = new DungeonRoomRegistry(this);
        this.normalRoomLibrary = new NormalRoomLibrary(this);
        this.normalRoomLibrary.reload();
        this.corridorLibrary = new CorridorLibrary(this);
        this.corridorLibrary.reload();
        reportPrefabMarkerConventions();
        this.dungeonMobDungeonKey = new NamespacedKey(this, "dungeon_mob_dungeon");
        this.dungeonMobRoomKey = new NamespacedKey(this, "dungeon_mob_room");
        this.dungeonMobTierKey = new NamespacedKey(this, "dungeon_mob_tier");
        this.dungeonMobDifficultyKey = new NamespacedKey(this, "dungeon_mob_difficulty");
        this.dungeonMobBossKey = new NamespacedKey(this, "dungeon_mob_boss");
        this.dungeonMobBossThemeKey = new NamespacedKey(this, "dungeon_mob_boss_theme");
        this.dungeonMobCategoryKey = new NamespacedKey(this, "dungeon_mob_category");
        this.dungeonMobTestKey = new NamespacedKey(this, "dungeon_mob_test");
        // Built before anything that can fire: every API event goes through
        // the bus, and every snapshot handed out is built by the snapshotter.
        this.eventBus = new DungeonEventBus(this);
        this.snapshots = new DungeonSnapshots(this);
        this.dungeonMobManager = new DungeonMobManager(this);
        this.dungeonCompletionManager = new DungeonCompletionManager(this);
        this.dungeonDoorManager = new DungeonDoorManager(this);
        this.dungeonRoomGateManager = new DungeonRoomGateManager(this);
        this.dungeonTrapManager = new DungeonTrapManager(this);
        this.api = new DungeonForgeApiImpl(this);
        this.settingsDialog = new DungeonSettingsDialog(this);
        this.animationPreview = new AnimationPreview(this);
        getServer().getServicesManager().register(DungeonForgeApi.class, api, this, ServicePriority.Normal);
        this.partyManager = new PartyManager();
        partyManager.reload(getConfig());
        this.dungeonMenu = new DungeonMenu(this);
        this.partyMenu = new PartyMenu(this);
        this.dungeonLordKey = new NamespacedKey(this, "dungeon_lord");
        this.dungeonLordManager = new DungeonLordManager(this);
        dungeonLordManager.load();
        this.panelIdKey = new NamespacedKey(this, "dungeon_panel");
        this.panelRoleKey = new NamespacedKey(this, "dungeon_panel_role");
        this.panelManager = new DifficultyPanelManager(this);
        panelManager.load();
        // Separate keys from the difficulty panel, so each manager's orphan
        // sweep can never mistake the other's entities for its own strays.
        this.skillPanelIdKey = new NamespacedKey(this, "dungeon_skill_panel");
        this.skillPanelRoleKey = new NamespacedKey(this, "dungeon_skill_panel_role");
        this.skillTreeLibrary = new SkillTreeLibrary(this);
        // Before the progress manager: it asks this for the class and the
        // point budget the moment it starts answering queries.
        this.classSkillsIntegration = new ClassSkillsIntegration(this);
        this.skillProgressManager = new SkillProgressManager(this);
        this.skillPanelManager = new SkillPanelManager(this);
        skillPanelManager.load();
        // Clean up anything left behind by a crash or a /stop while inside a
        // dungeon, so old world folders don't pile up.
        int purged = worldManager.purgeOrphanedWorldFolders();
        if (purged > 0) {
            getLogger().info("Cleaned up " + purged + " leftover dungeon world folder(s).");
        }

        PluginCommand command = getCommand("dungeon");
        if (command == null) {
            getLogger().severe("Command 'dungeon' is missing from plugin.yml - disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.dungeonCommand = new DungeonCommand(this);
        command.setExecutor(dungeonCommand);
        command.setTabCompleter(dungeonCommand);
        getServer().getPluginManager().registerEvents(new PartyListener(this), this);
        getServer().getPluginManager().registerEvents(new DungeonMenuListener(this), this);
        getServer().getPluginManager().registerEvents(new PartyMenuListener(this), this);
        getServer().getPluginManager().registerEvents(dungeonMobManager, this);
        getServer().getPluginManager().registerEvents(dungeonDoorManager, this);
        getServer().getPluginManager().registerEvents(dungeonRoomGateManager, this);
        getServer().getPluginManager().registerEvents(dungeonTrapManager, this);
        // Built after the mob manager so it can listen to its spawn event, and
        // registered unconditionally: it simply does nothing without an engine.
        this.modelIntegration = new ModelIntegration(this);
        getServer().getPluginManager().registerEvents(modelIntegration, this);
        getServer().getPluginManager().registerEvents(new DungeonHungerListener(this), this);
        getServer().getPluginManager().registerEvents(new DungeonPvpListener(this), this);
        getServer().getPluginManager().registerEvents(new DungeonRespawnListener(this), this);
        getServer().getPluginManager().registerEvents(new DungeonLordListener(this), this);
        getServer().getPluginManager().registerEvents(new DifficultyPanelListener(this), this);
        getServer().getPluginManager().registerEvents(new SkillPanelListener(this), this);
        getServer().getPluginManager().registerEvents(classSkillsIntegration, this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (String worldName : partyManager.collectExpiredOfflineWorlds()) {
                worldManager.deleteWorld(worldName);
            }
        }, 1200L, 1200L);
        getServer().getScheduler().runTaskTimer(this, dungeonLordManager::maintain, 100L, 100L);
        startRoomScanTask();
        long recountInterval = Math.max(1L, getConfig().getLong("mobs.recount-interval-ticks", 40L));
        getServer().getScheduler().runTaskTimer(this, dungeonMobManager::recount, recountInterval, recountInterval);
        long doorInterval = Math.max(1L, getConfig().getLong("door.watchdog.interval-ticks", 100L));
        getServer().getScheduler().runTaskTimer(this, dungeonDoorManager::watchdog, doorInterval, doorInterval);
        getServer().getScheduler().runTaskTimer(this, dungeonTrapManager::tick, 10L, 10L);
        // Personal panel rows follow players in and out of range a few times
        // per second; clicks themselves are handled by events, not this task.
        getServer().getScheduler().runTaskTimer(this, panelManager::tick, 10L, 10L);
        getServer().getScheduler().runTaskTimer(this, skillPanelManager::tick, 10L, 10L);

        // The startup half of the rot guard: if a rewrite ever drops an event
        // type from the bus, this line stops matching the API's own inventory.
        verifyApiEventInventory();
        getLogger().info("DungeonForge enabled. API v" + DungeonForgeApi.API_VERSION + " with "
                + eventBus.eventTypes().size() + " event type(s); /dungeon api status shows what is firing.");
    }

    /**
     * The second half of the rot guard. {@code /dungeon api status} proves
     * wired events still fire; this proves the bus inventory still covers the
     * whole api package. An event class added without a bus entry would ship
     * uncounted and invisible to the diagnostics - exactly the silence that
     * let the API rot once.
     */
    private void verifyApiEventInventory() {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(getFile())) {
            java.util.Set<String> packaged = new java.util.HashSet<>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("nl/riddernix/dungeonforge/api/") || !name.endsWith(".class")) continue;
                Class<?> type = Class.forName(name.substring(0, name.length() - 6).replace('/', '.'),
                        false, getClassLoader());
                if (org.bukkit.event.Event.class.isAssignableFrom(type)
                        && !java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
                    packaged.add(type.getSimpleName());
                }
            }
            java.util.Set<String> wired = new java.util.HashSet<>();
            for (Class<?> type : eventBus.eventTypes()) wired.add(type.getSimpleName());
            packaged.removeAll(wired);
            if (!packaged.isEmpty()) {
                getLogger().severe("API event type(s) missing from DungeonEventBus: " + String.join(", ", packaged)
                        + ". They would fire uncounted or not at all; add them to EVENT_TYPES and give them a fire method.");
            }
        } catch (Exception exception) {
            getLogger().warning("Could not verify the API event inventory: " + exception.getMessage());
        }
    }

    @Override
    public void onDisable() {
        // Tells the world manager that dungeons disappearing from here on are
        // being cleaned up rather than abandoned by their party.
        this.stopping = true;
        if (animationPreview != null) {
            // Preview stand-ins belong to a session, never to a saved world.
            animationPreview.stopAll();
        }
        if (panelManager != null) {
            // Panels respawn from panels.yml on the next enable.
            panelManager.despawnAll();
        }
        if (skillPanelManager != null) {
            skillPanelManager.despawnAll();
        }
        if (dungeonMobManager != null) {
            dungeonMobManager.saveTestingMobLocations();
        }
        if (skillProgressManager != null) {
            skillProgressManager.save();
        }
        if (worldManager != null) {
            worldManager.deleteAll();
        }
        getLogger().info("DungeonForge disabled.");
    }

    /** Reloads config.yml and everything that depends on it. */
    public void reloadEverything() {
        migrateConfiguration();
        reloadConfig();
        messages.reload();
        worldManager.reload();
        partyManager.reload(getConfig());
        dungeonLordManager.reload();
        normalRoomLibrary.reload();
        corridorLibrary.reload();
        reportPrefabMarkerConventions();
        modelIntegration.reload();
        panelManager.reload();
        skillTreeLibrary.reload();
        skillPanelManager.reload();
        startRoomScanTask();
    }

    public Messages messages() {
        return messages;
    }

    public DungeonWorldManager worlds() {
        return worldManager;
    }

    public PartyManager parties() {
        return partyManager;
    }

    public DungeonMenu menu() {
        return dungeonMenu;
    }

    public PartyMenu partyMenu() {
        return partyMenu;
    }

    public DungeonLordManager dungeonLords() {
        return dungeonLordManager;
    }

    public DungeonCommand command() {
        return dungeonCommand;
    }

    public NamespacedKey dungeonLordKey() {
        return dungeonLordKey;
    }

    public DungeonRoomRegistry rooms() {
        return roomRegistry;
    }

    public NormalRoomLibrary normalRooms() {
        return normalRoomLibrary;
    }

    public CorridorLibrary corridors() {
        return corridorLibrary;
    }

    /**
     * Markers are intentionally allowed to sit outside a build's structural
     * shell. What must agree is their vertical convention across all authored
     * room and corridor files.
     */
    private void reportPrefabMarkerConventions() {
        Map<Integer, List<String>> filesByOffset = new TreeMap<>();
        for (NormalRoomLibrary.Inspection inspection : normalRoomLibrary.inspections()) {
            for (int offset : inspection.markerVerticalOffsets()) {
                filesByOffset.computeIfAbsent(offset, unused -> new ArrayList<>()).add("room " + inspection.fileName());
            }
        }
        for (CorridorLibrary.Inspection inspection : corridorLibrary.inspections()) {
            for (int offset : inspection.markerVerticalOffsets()) {
                filesByOffset.computeIfAbsent(offset, unused -> new ArrayList<>()).add("corridor " + inspection.fileName());
            }
        }
        if (filesByOffset.size() <= 1) return;

        Map.Entry<Integer, List<String>> convention = filesByOffset.entrySet().stream()
                .max(Comparator.<Map.Entry<Integer, List<String>>>comparingInt(entry -> entry.getValue().size())
                        .thenComparing(Map.Entry::getKey))
                .orElseThrow();
        List<String> oddFiles = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> entry : filesByOffset.entrySet()) {
            if (!entry.getKey().equals(convention.getKey())) {
                oddFiles.add("y" + signed(entry.getKey()) + ": " + String.join(", ", entry.getValue()));
            }
        }
        getLogger().warning("Prefab marker vertical-offset mismatch. Common convention is y" + signed(convention.getKey())
                + " above structural content; files to re-save: " + String.join(" | ", oddFiles)
                + ". All groups: " + filesByOffset.entrySet().stream().map(entry -> "y" + signed(entry.getKey())
                        + "=" + String.join(", ", entry.getValue())).reduce((left, right) -> left + "; " + right).orElse("none"));
    }

    private static String signed(int value) {
        return value >= 0 ? "+" + value : Integer.toString(value);
    }

    public DungeonMobManager mobs() { return dungeonMobManager; }
    public DungeonDoorManager doors() { return dungeonDoorManager; }
    public DungeonRoomGateManager gates() { return dungeonRoomGateManager; }
    public DungeonTrapManager traps() { return dungeonTrapManager; }
    public DungeonForgeApi api() { return api; }
    public NamespacedKey dungeonMobDungeonKey() { return dungeonMobDungeonKey; }
    public NamespacedKey dungeonMobRoomKey() { return dungeonMobRoomKey; }
    public NamespacedKey dungeonMobTierKey() { return dungeonMobTierKey; }
    public NamespacedKey dungeonMobDifficultyKey() { return dungeonMobDifficultyKey; }
    public NamespacedKey dungeonMobBossKey() { return dungeonMobBossKey; }
    public NamespacedKey dungeonMobBossThemeKey() { return dungeonMobBossThemeKey; }
    public NamespacedKey dungeonMobCategoryKey() { return dungeonMobCategoryKey; }
    public DungeonEventBus events() { return eventBus; }
    public DungeonSnapshots snapshots() { return snapshots; }
    /** True once the plugin is shutting down, so cleanup is not read as abandonment. */
    public boolean isStopping() { return stopping; }
    public NamespacedKey dungeonMobTestKey() { return dungeonMobTestKey; }
    public DungeonCompletionManager completions() { return dungeonCompletionManager; }
    public DungeonSettingsDialog settings() { return settingsDialog; }
    public ModelIntegration models() { return modelIntegration; }
    public AnimationPreview animations() { return animationPreview; }
    public DifficultyPanelManager panels() { return panelManager; }
    public NamespacedKey panelIdKey() { return panelIdKey; }
    public NamespacedKey panelRoleKey() { return panelRoleKey; }
    public SkillTreeLibrary skillTrees() { return skillTreeLibrary; }
    public SkillProgressManager skillProgress() { return skillProgressManager; }
    public ClassSkillsIntegration classSkills() { return classSkillsIntegration; }
    public SkillPanelManager skillPanels() { return skillPanelManager; }
    public NamespacedKey skillPanelIdKey() { return skillPanelIdKey; }
    public NamespacedKey skillPanelRoleKey() { return skillPanelRoleKey; }

    private void startRoomScanTask() {
        if (roomScanTask != null) {
            roomScanTask.cancel();
        }
        long interval = Math.max(1L, getConfig().getLong("rooms.detection-interval-ticks", 10L));
        roomScanTask = getServer().getScheduler().runTaskTimer(this, roomRegistry::scanPlayers, interval, interval);
    }

    /**
     * Unpacks the schematics bundled in the jar into their live folders.
     *
     * <p>Only ever into an <em>empty</em> folder: the moment a server has its
     * own schematics, those are the truth and the bundled copies stay in the
     * jar. That keeps a fresh install working out of the box without ever
     * fighting a hand-edited room, and dropping a new file into the folder
     * still needs no rebuild.</p>
     */
    private void extractBundledSchematics() {
        for (String folder : List.of("rooms", "corridors")) {
            File target = new File(getDataFolder(), folder);
            if (!target.isDirectory() && !target.mkdirs()) {
                getLogger().severe("Could not create " + target.getAbsolutePath() + ".");
                continue;
            }
            File[] present = target.listFiles(file -> file.isFile()
                    && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".schem"));
            if (present != null && present.length > 0) {
                continue;
            }
            int extracted = 0;
            // Enumerated from the jar rather than a hardcoded list, so adding
            // a schematic to the resources is all it takes to ship it.
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(getFile())) {
                for (java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.startsWith(folder + "/")
                            || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".schem")) {
                        continue;
                    }
                    saveResource(name, false);
                    extracted++;
                }
            } catch (IOException exception) {
                getLogger().severe("Could not read bundled schematics: " + exception.getMessage());
                continue;
            }
            if (extracted > 0) {
                getLogger().info("Unpacked " + extracted + " bundled schematic(s) into " + folder
                        + "/. Edit or replace them freely; they are only written when the folder is empty.");
            }
        }
    }

    /** Replaces outdated configuration files after preserving a timestamped backup. */
    private void migrateConfiguration() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.isFile()) {
            saveDefaultConfig();
            return;
        }

        int installedVersion = YamlConfiguration.loadConfiguration(configFile).getInt("config-version", 0);
        if (installedVersion >= CONFIG_VERSION) {
            return;
        }

        File backup = new File(getDataFolder(), "config.yml.v" + installedVersion
                + "." + System.currentTimeMillis() + ".bak");
        try {
            Files.copy(configFile.toPath(), backup.toPath());
            saveResource("config.yml", true);
            getLogger().info("Replaced outdated config.yml with version " + CONFIG_VERSION
                    + "; previous configuration backed up as " + backup.getName() + ".");
        } catch (IOException ex) {
            getLogger().severe("Could not back up outdated config.yml: " + ex.getMessage());
        }
    }

}
