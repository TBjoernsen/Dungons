package nl.riddernix.dungeonforge.world;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.api.DungeonEndReason;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Owns the lifecycle of dungeon worlds: creating, looking up, emptying and
 * deleting them from disk.
 *
 * <p>Everything world-related lives here so the rest of the plugin doesn't
 * need to care. When you move to per-party instances later, this is the only
 * class you have to extend.</p>
 */
public final class DungeonWorldManager {

    private final DungeonForgePlugin plugin;

    /** Worlds this plugin created. */
    private final List<String> managed = new ArrayList<>();

    /** Where a player stood before entering the dungeon. */
    private final Map<UUID, Location> returnPoints = new HashMap<>();

    private String prefix;

    public DungeonWorldManager(DungeonForgePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.prefix = plugin.getConfig().getString("world.prefix", "dungeon_");
    }

    // ------------------------------------------------------------------
    //  Naming and lookup
    // ------------------------------------------------------------------

    /** The world name belonging to this player, e.g. "dungeon_riddernix". */
    public String worldNameFor(Player player) {
        return prefix + player.getName().toLowerCase(Locale.ROOT);
    }

    public boolean isDungeonWorld(World world) {
        return world != null && world.getName().startsWith(prefix);
    }

    public List<World> loadedDungeonWorlds() {
        List<World> result = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (isDungeonWorld(world)) {
                result.add(world);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    //  Creating
    // ------------------------------------------------------------------

    /**
     * Creates a fresh void world. If one already existed, loaded or only on
     * disk, it is removed first so you always start clean.
     *
     * @return the new world, or {@code null} if creation failed
     */
    public World createFresh(String name) {
        deleteWorld(name);

        int[] spawn = initialSpawn();

        WorldCreator creator = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .generator(new VoidChunkGenerator(spawn[0], spawn[1], spawn[2]))
                .biomeProvider(new VoidBiomeProvider())
                .seed(0L);

        World world = creator.createWorld();
        if (world == null) {
            plugin.getLogger().severe("Failed to create world '" + name + "'.");
            return null;
        }

        applySettings(world, spawn[0], spawn[1], spawn[2]);
        managed.add(name);
        return world;
    }

    /**
     * Creates or loads a void world that outlives the server session.
     *
     * <p>Unlike {@link #createFresh(String)} this never wipes an existing
     * world, keeps auto-save on and is not registered as a managed dungeon, so
     * {@link #deleteAll()} leaves it alone. Currently unused; kept because a
     * persistent void world is a different thing from a disposable one and
     * rebuilding that distinction later is more work than keeping it.</p>
     *
     * @return the world, or {@code null} if creation failed
     */
    public World createOrLoadPlainWorld(String name, int spawnY) {
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            return existing;
        }
        WorldCreator creator = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .generator(new VoidChunkGenerator(0, spawnY, 0))
                .biomeProvider(new VoidBiomeProvider())
                .seed(0L);

        World world = creator.createWorld();
        if (world == null) {
            plugin.getLogger().severe("Failed to create world '" + name + "'.");
            return null;
        }
        world.setSpawnLocation(0, spawnY, 0);
        world.setAutoSave(true);
        world.setSpawnFlags(false, false);
        world.setTime(plugin.getConfig().getLong("world.time", 18000L));
        world.setStorm(false);
        world.setThundering(false);
        applyGameRules(world);
        return world;
    }

    /** Returns the spawn point in the centre of the test box. */
    private int[] initialSpawn() {
        int size = plugin.getConfig().getInt("box.size", 48);
        return new int[]{
                plugin.getConfig().getInt("box.origin.x", 0) + size / 2,
                plugin.getConfig().getInt("box.origin.y", 64) + 1,
                plugin.getConfig().getInt("box.origin.z", 0) + size / 2
        };
    }

    private void applySettings(World world, int spawnX, int spawnY, int spawnZ) {
        world.setSpawnLocation(spawnX, spawnY, spawnZ);
        world.setAutoSave(false);          // dungeons are disposable, don't save them
        world.setSpawnFlags(false, false); // no natural monster or animal spawns

        String difficultyName = plugin.getConfig().getString("world.difficulty", "NORMAL");
        try {
            world.setDifficulty(Difficulty.valueOf(difficultyName.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Unknown difficulty in config: " + difficultyName);
        }

        world.setTime(plugin.getConfig().getLong("world.time", 18000L));
        if (plugin.getConfig().getBoolean("world.clear-weather", true)) {
            world.setStorm(false);
            world.setThundering(false);
        }

        applyGameRules(world);
    }

    /**
     * Lookup table from gamerule name to GameRule object, built lazily.
     *
     * <p>Paper is migrating gamerules to the registry system; the old
     * {@code GameRule.values()} and {@code getName()} are both marked for
     * removal. So we read the registry first and only fall back to the old API
     * if that turns up nothing. Once Paper actually drops the old methods you
     * can delete the fallback block and nothing else changes.</p>
     *
     * <p>Lazy because registries are only populated after the server finishes
     * loading - a static initializer would run too early.</p>
     */
    private Map<String, GameRule<?>> gameRuleIndex;

    private Map<String, GameRule<?>> gameRuleIndex() {
        if (gameRuleIndex != null) {
            return gameRuleIndex;
        }
        Map<String, GameRule<?>> index = new HashMap<>();

        for (GameRule<?> rule : Registry.GAME_RULE) {
            NamespacedKey key = Registry.GAME_RULE.getKey(rule);
            if (key != null) {
                index.put(normalizeGameRuleName(key.getKey()), rule);
            }
        }

        // Registry keys are snake_case (for example keep_inventory), while
        // Minecraft's familiar command names and this config use camelCase.
        // Add the legacy names too, so either spelling resolves consistently.
        legacyGameRuleIndex().forEach(index::putIfAbsent);

        gameRuleIndex = Map.copyOf(index);
        return gameRuleIndex;
    }

    @SuppressWarnings("removal")
    private static Map<String, GameRule<?>> legacyGameRuleIndex() {
        Map<String, GameRule<?>> index = new HashMap<>();
        for (GameRule<?> rule : GameRule.values()) {
            index.put(normalizeGameRuleName(rule.getName()), rule);
        }
        return index;
    }

    private static String normalizeGameRuleName(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    /**
     * Resolves one configured name straight against the registry.
     *
     * <p>The index above needs the registry to answer {@code getKey(rule)}
     * and the legacy {@code GameRule.values()} to still return entries. When
     * both come up empty, every rule reads as unknown and no dungeon world is
     * ever configured - no keepInventory, no suppressed vanilla spawns.
     * Handing the registry a key it can build itself needs neither.</p>
     */
    private static GameRule<?> lookupGameRule(String configuredName) {
        return Registry.GAME_RULE.get(NamespacedKey.minecraft(snakeCaseGameRuleName(configuredName)));
    }

    /** {@code doDaylightCycle} and {@code do_daylight_cycle} both become the registry's spelling. */
    private static String snakeCaseGameRuleName(String name) {
        StringBuilder result = new StringBuilder(name.length() + 4);
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (Character.isUpperCase(character)) {
                if (index > 0 && result.charAt(result.length() - 1) != '_') {
                    result.append('_');
                }
                result.append(Character.toLowerCase(character));
            } else {
                result.append(character == '-' ? '_' : character);
            }
        }
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private void applyGameRules(World world) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("world.gamerules");
        if (section == null) {
            return;
        }
        Map<String, GameRule<?>> index = gameRuleIndex();
        for (String key : section.getKeys(false)) {
            GameRule<?> rule = index.get(normalizeGameRuleName(key));
            if (rule == null) {
                rule = lookupGameRule(key);
            }
            if (rule == null) {
                plugin.getLogger().warning("Unknown gamerule in config: " + key);
                continue;
            }
            Object raw = section.get(key);
            if (rule.getType() == Boolean.class && raw instanceof Boolean value) {
                world.setGameRule((GameRule<Boolean>) rule, value);
            } else if (rule.getType() == Integer.class && raw instanceof Integer value) {
                world.setGameRule((GameRule<Integer>) rule, value);
            } else {
                plugin.getLogger().warning("Wrong type for gamerule " + key + ": " + raw);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Deleting
    // ------------------------------------------------------------------

    /**
     * Moves players out, unloads the world and deletes its folder.
     *
     * @return true if nothing is left of it
     */
    public boolean deleteWorld(String name) {
        // Fired before anything is torn down, so the dungeon can still be
        // inspected. The bus makes it once-per-dungeon, so a completed run
        // that already ended does not end a second time here.
        plugin.rooms().dungeon(name).ifPresent(dungeon -> plugin.events().fireEnd(
                plugin.snapshots().ending(dungeon, dungeon.isCompleted()),
                dungeon.isCompleted() ? DungeonEndReason.COMPLETED
                        : plugin.isStopping() ? DungeonEndReason.CLEANED_UP : DungeonEndReason.ABANDONED));
        plugin.mobs().removeWorld(name);
        plugin.rooms().remove(name);
        World world = Bukkit.getWorld(name);
        if (world != null) {
            evacuate(world);
            if (!Bukkit.unloadWorld(world, false)) {
                plugin.getLogger().warning("Could not unload world '" + name + "'.");
                return false;
            }
        }
        managed.remove(name);

        Path folder = Bukkit.getWorldContainer().toPath().resolve(name);
        if (!Files.exists(folder)) {
            return true;
        }
        return deleteRecursively(folder);
    }

    /** Deletes every world this plugin created. */
    public void deleteAll() {
        for (String name : new ArrayList<>(managed)) {
            deleteWorld(name);
        }
    }

    /**
     * Removes folders matching the prefix that are not currently loaded.
     * Runs on enable so a crash doesn't leave clutter behind.
     */
    public int purgeOrphanedWorldFolders() {
        Path container = Bukkit.getWorldContainer().toPath();
        int removed = 0;
        try (var stream = Files.list(container)) {
            List<Path> candidates = stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .toList();
            for (Path path : candidates) {
                String name = path.getFileName().toString();
                if (Bukkit.getWorld(name) != null) {
                    continue; // still running, leave it alone
                }
                if (deleteRecursively(path)) {
                    removed++;
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not scan the server root.", ex);
        }
        return removed;
    }

    private boolean deleteRecursively(Path root) {
        try (var stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
            return !Files.exists(root);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not delete folder: " + root, ex);
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  Moving players in and out
    // ------------------------------------------------------------------

    /** Remembers where the player was and teleports them into the dungeon. */
    public void enter(Player player, World dungeon) {
        enter(player, dungeon.getSpawnLocation());
    }

    /** Remembers the player's return point and enters at an explicit safe dungeon location. */
    public void enter(Player player, Location destination) {
        if (!isDungeonWorld(player.getWorld())) {
            returnPoints.put(player.getUniqueId(), player.getLocation());
        }
        World from = player.getWorld();
        player.teleport(destination);
        // Fired after the teleport lands, so the player is really inside when
        // a listener sees it, and only when the world actually changed.
        if (!player.getWorld().equals(from)) {
            plugin.rooms().dungeon(player.getWorld()).ifPresent(dungeon ->
                    plugin.events().firePlayerEnter(plugin.snapshots().of(dungeon), player));
        }
    }

    /** Puts the player back where they came from (or the main spawn). */
    public void exit(Player player) {
        leaving(player);
        player.teleport(returnLocationFor(player));
        returnPoints.remove(player.getUniqueId());
    }

    /** One place for "this player is no longer in that dungeon". */
    private void leaving(Player player) {
        plugin.rooms().dungeon(player.getWorld()).ifPresent(dungeon ->
                plugin.events().firePlayerLeave(plugin.snapshots().of(dungeon), player));
    }

    /** Clears everyone out of a world that is about to be deleted. */
    public void evacuate(World world) {
        for (Player player : new ArrayList<>(world.getPlayers())) {
            leaving(player);
            player.teleport(returnLocationFor(player));
            returnPoints.remove(player.getUniqueId());
            player.sendMessage(plugin.messages().get("evacuated"));
        }
    }

    private Location returnLocationFor(Player player) {
        String mode = plugin.getConfig().getString("teleport.fallback", "last");
        if ("last".equalsIgnoreCase(mode)) {
            Location saved = returnPoints.get(player.getUniqueId());
            if (saved != null && saved.getWorld() != null && !isDungeonWorld(saved.getWorld())) {
                return saved;
            }
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }
}
