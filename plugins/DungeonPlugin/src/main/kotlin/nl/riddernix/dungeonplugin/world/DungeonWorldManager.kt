package nl.riddernix.dungeonplugin.world

import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.event.DungeonEndReason
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.entity.Player
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.logging.Level

/**
 * Owns the lifecycle of dungeon worlds: creating, looking up, emptying and
 * deleting them from disk.
 *
 * Everything world-related lives here so the rest of the plugin doesn't need
 * to care. When you move to per-party instances later, this is the only class
 * you have to extend.
 */
class DungeonWorldManager(private val plugin: DungeonPlugin) {

    /** Worlds this plugin created. */
    private val managed = ArrayList<String>()

    /** Where a player stood before entering the dungeon. */
    private val returnPoints = HashMap<UUID, Location>()

    private var prefix = "dungeon_"

    /**
     * Lookup table from gamerule name to GameRule object, built lazily.
     *
     * Paper is migrating gamerules to the registry system; the old
     * `GameRule.values()` and `getName()` are both marked for removal. So we
     * read the registry first and only fall back to the old API if that turns
     * up nothing. Once Paper actually drops the old methods you can delete
     * the fallback block and nothing else changes.
     *
     * Lazy because registries are only populated after the server finishes
     * loading - eager initialisation would run too early.
     */
    private var gameRuleIndex: Map<String, GameRule<*>>? = null

    init {
        reload()
    }

    fun reload() {
        prefix = plugin.config.getString("world.prefix", "dungeon_") ?: "dungeon_"
    }

    // ------------------------------------------------------------------
    //  Naming and lookup
    // ------------------------------------------------------------------

    /** The world name belonging to this player, e.g. "dungeon_riddernix". */
    fun worldNameFor(player: Player): String = prefix + player.name.lowercase(Locale.ROOT)

    fun isDungeonWorld(world: World?): Boolean = world != null && world.name.startsWith(prefix)

    fun loadedDungeonWorlds(): List<World> = Bukkit.getWorlds().filter { isDungeonWorld(it) }

    // ------------------------------------------------------------------
    //  Creating
    // ------------------------------------------------------------------

    /**
     * Creates a fresh void world. If one already existed, loaded or only on
     * disk, it is removed first so you always start clean.
     *
     * @return the new world, or `null` if creation failed
     */
    fun createFresh(name: String): World? {
        deleteWorld(name)

        val spawn = initialSpawn()

        val creator = WorldCreator(name)
            .environment(World.Environment.NORMAL)
            .type(WorldType.FLAT)
            .generateStructures(false)
            .generator(VoidChunkGenerator(spawn[0], spawn[1], spawn[2]))
            .biomeProvider(VoidBiomeProvider())
            .seed(0L)

        val world = creator.createWorld()
        if (world == null) {
            plugin.logger.severe("Failed to create world '$name'.")
            return null
        }

        applySettings(world, spawn[0], spawn[1], spawn[2])
        managed.add(name)
        return world
    }

    /**
     * Creates or loads a void world that outlives the server session.
     *
     * Unlike [createFresh] this never wipes an existing world, keeps
     * auto-save on and is not registered as a managed dungeon, so [deleteAll]
     * leaves it alone. Currently unused; kept because a persistent void world
     * is a different thing from a disposable one and rebuilding that
     * distinction later is more work than keeping it.
     *
     * @return the world, or `null` if creation failed
     */
    fun createOrLoadPlainWorld(name: String, spawnY: Int): World? {
        val existing = Bukkit.getWorld(name)
        if (existing != null) {
            return existing
        }
        val creator = WorldCreator(name)
            .environment(World.Environment.NORMAL)
            .type(WorldType.FLAT)
            .generateStructures(false)
            .generator(VoidChunkGenerator(0, spawnY, 0))
            .biomeProvider(VoidBiomeProvider())
            .seed(0L)

        val world = creator.createWorld()
        if (world == null) {
            plugin.logger.severe("Failed to create world '$name'.")
            return null
        }
        world.setSpawnLocation(0, spawnY, 0)
        world.isAutoSave = true
        world.setSpawnFlags(false, false)
        world.time = plugin.config.getLong("world.time", 18000L)
        world.setStorm(false)
        world.isThundering = false
        applyGameRules(world)
        return world
    }

    /** Returns the spawn point in the centre of the test box. */
    private fun initialSpawn(): IntArray {
        val size = plugin.config.getInt("box.size", 48)
        return intArrayOf(
            plugin.config.getInt("box.origin.x", 0) + size / 2,
            plugin.config.getInt("box.origin.y", 64) + 1,
            plugin.config.getInt("box.origin.z", 0) + size / 2
        )
    }

    private fun applySettings(world: World, spawnX: Int, spawnY: Int, spawnZ: Int) {
        world.setSpawnLocation(spawnX, spawnY, spawnZ)
        world.isAutoSave = false          // dungeons are disposable, don't save them
        world.setSpawnFlags(false, false) // no natural monster or animal spawns

        val difficultyName = plugin.config.getString("world.difficulty", "NORMAL")!!
        try {
            world.difficulty = Difficulty.valueOf(difficultyName.uppercase(Locale.ROOT))
        } catch (ex: IllegalArgumentException) {
            plugin.logger.warning("Unknown difficulty in config: $difficultyName")
        }

        world.time = plugin.config.getLong("world.time", 18000L)
        if (plugin.config.getBoolean("world.clear-weather", true)) {
            world.setStorm(false)
            world.isThundering = false
        }

        applyGameRules(world)
    }

    private fun gameRuleIndex(): Map<String, GameRule<*>> {
        gameRuleIndex?.let { return it }
        val index = HashMap<String, GameRule<*>>()

        for (rule in Registry.GAME_RULE) {
            val key = Registry.GAME_RULE.getKey(rule)
            if (key != null) {
                index[normalizeGameRuleName(key.key)] = rule
            }
        }

        // Registry keys are snake_case (for example keep_inventory), while
        // Minecraft's familiar command names and this config use camelCase.
        // Add the legacy names too, so either spelling resolves consistently.
        legacyGameRuleIndex().forEach { (name, rule) -> index.putIfAbsent(name, rule) }

        val immutable = index.toMap()
        gameRuleIndex = immutable
        return immutable
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyGameRules(world: World) {
        val section = plugin.config.getConfigurationSection("world.gamerules") ?: return
        val index = gameRuleIndex()
        for (key in section.getKeys(false)) {
            var rule = index[normalizeGameRuleName(key)]
            if (rule == null) {
                rule = lookupGameRule(key)
            }
            if (rule == null) {
                plugin.logger.warning("Unknown gamerule in config: $key")
                continue
            }
            val raw = section.get(key)
            if (rule.type == Boolean::class.javaObjectType && raw is Boolean) {
                world.setGameRule(rule as GameRule<Boolean>, raw)
            } else if (rule.type == Int::class.javaObjectType && raw is Int) {
                world.setGameRule(rule as GameRule<Int>, raw)
            } else {
                plugin.logger.warning("Wrong type for gamerule $key: $raw")
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
    fun deleteWorld(name: String): Boolean {
        // Fired before anything is torn down, so the dungeon can still be
        // inspected. The bus makes it once-per-dungeon, so a completed run
        // that already ended does not end a second time here.
        plugin.rooms.dungeon(name)?.let { dungeon ->
            plugin.events.fireEnd(
                plugin.snapshots.ending(dungeon, dungeon.isCompleted),
                when {
                    dungeon.isCompleted -> DungeonEndReason.COMPLETED
                    plugin.isStopping -> DungeonEndReason.CLEANED_UP
                    else -> DungeonEndReason.ABANDONED
                })
        }
        plugin.mobs.removeWorld(name)
        plugin.rooms.remove(name)
        val world = Bukkit.getWorld(name)
        if (world != null) {
            evacuate(world)
            if (!Bukkit.unloadWorld(world, false)) {
                plugin.logger.warning("Could not unload world '$name'.")
                return false
            }
        }
        managed.remove(name)

        val folder = Bukkit.getWorldContainer().toPath().resolve(name)
        if (!Files.exists(folder)) {
            return true
        }
        return deleteRecursively(folder)
    }

    /** Deletes every world this plugin created. */
    fun deleteAll() {
        for (name in managed.toList()) {
            deleteWorld(name)
        }
    }

    /**
     * Removes folders matching the prefix that are not currently loaded.
     * Runs on enable so a crash doesn't leave clutter behind.
     */
    fun purgeOrphanedWorldFolders(): Int {
        val container = Bukkit.getWorldContainer().toPath()
        var removed = 0
        try {
            Files.list(container).use { stream ->
                val candidates = stream
                    .filter { Files.isDirectory(it) }
                    .filter { it.fileName.toString().startsWith(prefix) }
                    .toList()
                for (path in candidates) {
                    val name = path.fileName.toString()
                    if (Bukkit.getWorld(name) != null) {
                        continue // still running, leave it alone
                    }
                    if (deleteRecursively(path)) {
                        removed++
                    }
                }
            }
        } catch (ex: IOException) {
            plugin.logger.log(Level.WARNING, "Could not scan the server root.", ex)
        }
        return removed
    }

    private fun deleteRecursively(root: Path): Boolean {
        return try {
            Files.walk(root).use { stream ->
                val paths = stream.sorted(Comparator.reverseOrder()).toList()
                for (path in paths) {
                    Files.deleteIfExists(path)
                }
                !Files.exists(root)
            }
        } catch (ex: IOException) {
            plugin.logger.log(Level.WARNING, "Could not delete folder: $root", ex)
            false
        }
    }

    // ------------------------------------------------------------------
    //  Moving players in and out
    // ------------------------------------------------------------------

    /** Remembers where the player was and teleports them into the dungeon. */
    fun enter(player: Player, dungeon: World) {
        enter(player, dungeon.spawnLocation)
    }

    /** Remembers the player's return point and enters at an explicit safe dungeon location. */
    fun enter(player: Player, destination: Location) {
        if (!isDungeonWorld(player.world)) {
            returnPoints[player.uniqueId] = player.location
        }
        val from = player.world
        player.teleport(destination)
        // Fired after the teleport lands, so the player is really inside when
        // a listener sees it, and only when the world actually changed.
        if (player.world != from) {
            plugin.rooms.dungeon(player.world)?.let { dungeon ->
                plugin.events.firePlayerEnter(plugin.snapshots.of(dungeon), player)
            }
        }
    }

    /** Puts the player back where they came from (or the main spawn). */
    fun exit(player: Player) {
        leaving(player)
        player.teleport(returnLocationFor(player))
        returnPoints.remove(player.uniqueId)
    }

    /** One place for "this player is no longer in that dungeon". */
    private fun leaving(player: Player) {
        plugin.rooms.dungeon(player.world)?.let { dungeon ->
            plugin.events.firePlayerLeave(plugin.snapshots.of(dungeon), player)
        }
    }

    /** Clears everyone out of a world that is about to be deleted. */
    fun evacuate(world: World) {
        for (player in world.players.toList()) {
            leaving(player)
            player.teleport(returnLocationFor(player))
            returnPoints.remove(player.uniqueId)
            player.sendMessage(plugin.messages.get("evacuated"))
        }
    }

    private fun returnLocationFor(player: Player): Location {
        val mode = plugin.config.getString("teleport.fallback", "last")
        if ("last".equals(mode, ignoreCase = true)) {
            val saved = returnPoints[player.uniqueId]
            if (saved?.world != null && !isDungeonWorld(saved.world)) {
                return saved
            }
        }
        return Bukkit.getWorlds()[0].spawnLocation
    }

    companion object {
        @Suppress("removal", "DEPRECATION")
        private fun legacyGameRuleIndex(): Map<String, GameRule<*>> {
            val index = HashMap<String, GameRule<*>>()
            for (rule in GameRule.values()) {
                index[normalizeGameRuleName(rule.name)] = rule
            }
            return index
        }

        private fun normalizeGameRuleName(name: String): String =
            name.lowercase(Locale.ROOT).replace("_", "").replace("-", "")

        /**
         * Resolves one configured name straight against the registry.
         *
         * The index above needs the registry to answer `getKey(rule)` and the
         * legacy `GameRule.values()` to still return entries. When both come
         * up empty, every rule reads as unknown and no dungeon world is ever
         * configured - no keepInventory, no suppressed vanilla spawns.
         * Handing the registry a key it can build itself needs neither.
         */
        private fun lookupGameRule(configuredName: String): GameRule<*>? =
            Registry.GAME_RULE.get(NamespacedKey.minecraft(snakeCaseGameRuleName(configuredName)))

        /** `doDaylightCycle` and `do_daylight_cycle` both become the registry's spelling. */
        private fun snakeCaseGameRuleName(name: String): String {
            val result = StringBuilder(name.length + 4)
            for (index in name.indices) {
                val character = name[index]
                if (character.isUpperCase()) {
                    if (index > 0 && result[result.length - 1] != '_') {
                        result.append('_')
                    }
                    result.append(character.lowercaseChar())
                } else {
                    result.append(if (character == '-') '_' else character)
                }
            }
            return result.toString()
        }
    }
}
