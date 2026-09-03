package nl.riddernix.dungeonplugin.quest

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.ZoneId

/**
 * `quests.yml` - the quest layer's own configuration: the pool each category
 * draws from, the two menu layouts, and the refresh timing.
 *
 * Merged the same way as `classes.yml`: missing keys are filled from the
 * bundled defaults, existing values are left untouched, so admin edits and
 * later real quest content survive updates. Runtime state (which quests are
 * currently active, per-player progress) is *not* here - that lives in
 * `quest-data.yml`, owned by [QuestManager].
 */
class QuestConfig(private val plugin: DungeonPlugin) {

    lateinit var yaml: YamlConfiguration
        private set

    private val file = File(plugin.dataFolder, FILE_NAME)

    /** Parsed pools, rebuilt only on [reload] - [pool] is hit on every mob kill. */
    private val poolCache = java.util.EnumMap<QuestCategory, List<QuestDefinition>>(QuestCategory::class.java)

    init {
        reload()
    }

    fun reload() {
        if (!file.isFile) {
            plugin.saveResource(FILE_NAME, false)
        }
        yaml = YamlConfiguration.loadConfiguration(file)
        plugin.getResource(FILE_NAME)?.use { resource ->
            val defaults = YamlConfiguration.loadConfiguration(InputStreamReader(resource, StandardCharsets.UTF_8))
            yaml.setDefaults(defaults)
            yaml.options().copyDefaults(true)
        }
        save()
        poolCache.clear()
        for (category in QuestCategory.entries) poolCache[category] = parsePool(category)
    }

    fun save() {
        try {
            yaml.save(file)
        } catch (exception: IOException) {
            plugin.logger.severe("Could not save $FILE_NAME: ${exception.message}")
        }
    }

    // ------------------------------------------------------------------
    //  Timing
    // ------------------------------------------------------------------

    /**
     * The zone the daily and weekly boundaries are measured in. The spec says
     * "midnight EST"; the default is the full US Eastern zone so the boundary
     * stays at local midnight across the EST/EDT change. Set a fixed offset
     * like `-05:00` here for a hard EST that ignores daylight saving.
     */
    fun zone(): ZoneId = try {
        ZoneId.of(yaml.getString("timing.timezone", DEFAULT_ZONE) ?: DEFAULT_ZONE)
    } catch (ex: Exception) {
        plugin.logger.warning("quests.yml timing.timezone is invalid; falling back to $DEFAULT_ZONE.")
        ZoneId.of(DEFAULT_ZONE)
    }

    /** How often the scheduler re-checks whether a boundary has passed. */
    fun refreshCheckSeconds(): Long = maxOf(5L, yaml.getLong("timing.refresh-check-seconds", 60L))

    // ------------------------------------------------------------------
    //  Quest pool
    // ------------------------------------------------------------------

    /**
     * Every quest defined for a category, in file order. A refresh rolls
     * [QuestCategory.SLOTS] of these at random ([QuestCategory.GENERAL] simply
     * takes the first four). Served from a cache rebuilt on [reload].
     */
    fun pool(category: QuestCategory): List<QuestDefinition> =
        poolCache[category] ?: parsePool(category).also { poolCache[category] = it }

    private fun parsePool(category: QuestCategory): List<QuestDefinition> {
        val section = yaml.getConfigurationSection("pool.${category.id}") ?: return emptyList()
        val out = ArrayList<QuestDefinition>()
        for (id in section.getKeys(false)) {
            val entry = section.getConfigurationSection(id) ?: continue
            val objective = QuestObjective.fromId(entry.getString("objective"))
            if (objective == null) {
                plugin.logger.warning("quests.yml pool.${category.id}.$id has an unknown objective " +
                    "'${entry.getString("objective")}'; skipping it.")
                continue
            }
            val required = entry.getInt("required", 1).coerceAtLeast(1)
            out.add(QuestDefinition(
                id = id,
                title = entry.getString("title", id) ?: id,
                description = entry.getString("description", "") ?: "",
                objective = objective,
                required = required,
                reward = entry.getString("reward", "") ?: ""
            ))
        }
        return out
    }

    companion object {
        private const val FILE_NAME = "quests.yml"
        private const val DEFAULT_ZONE = "America/New_York"
    }
}
