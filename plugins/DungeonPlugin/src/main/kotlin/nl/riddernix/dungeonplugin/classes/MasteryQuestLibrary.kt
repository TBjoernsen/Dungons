package nl.riddernix.dungeonplugin.classes

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * What a counter toward a mastery ladder step actually counts. Each is its
 * own lifetime counter per player per subclass (see
 * [ClassProgressionService.MasteryProgress]) - it never resets, and it keeps
 * accumulating even while a different objective's step is the "current" one,
 * so a ladder can freely interleave objectives (Sharpshooter's does) without
 * losing progress made on the others in the meantime.
 */
enum class MasteryObjective(val id: String) {
    DEAL_DAMAGE("deal_damage"),
    HEAL_AMOUNT("heal_amount"),
    DEADEYE_MARKS("deadeye_marks"),
    FOCUS_SHOT_KILLS("focus_shot_kills"),
    SCOPE_SECONDS("scope_seconds"),
    TEMPEST_HITS("tempest_hits"),
    SKYFALL_KILLS("skyfall_kills"),
    WIND_DASH_USES("wind_dash_uses"),
    MINIONS_SUMMONED("minions_summoned"),
    MINION_KILLS("minion_kills");

    companion object {
        fun fromId(raw: String?): MasteryObjective? = entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
    }
}

/** One ladder rung - its own objective now, not shared across the whole line. */
data class MasteryQuestStep(val objective: MasteryObjective, val required: Int, val title: String, val description: String)

data class MasteryQuestLine(val rewardXp: Int, val ladder: List<MasteryQuestStep>)

/**
 * The fixed ten-step mastery quest ladder per subclass, from
 * `mastery-quests.yml` - deliberately separate from the random-rolled
 * Daily/Weekly/General system in [nl.riddernix.dungeonplugin.quest.QuestManager].
 * [ClassProgressionService] owns the per-player progress and claiming
 * against these definitions.
 */
class MasteryQuestLibrary(private val plugin: DungeonPlugin) {

    private lateinit var yaml: YamlConfiguration
    private val file = File(plugin.dataFolder, FILE_NAME)
    private val lines = HashMap<String, MasteryQuestLine>()

    init {
        reload()
    }

    fun reload() {
        if (!file.isFile) plugin.saveResource(FILE_NAME, false)
        yaml = YamlConfiguration.loadConfiguration(file)
        plugin.getResource(FILE_NAME)?.use { resource ->
            val defaults = YamlConfiguration.loadConfiguration(InputStreamReader(resource, StandardCharsets.UTF_8))
            // Ladders are meant to be tuned in the bundled resource, not hand-held
            // on the server - a version bump replaces the whole file, same as
            // ClassesConfig does for classes.yml. Without this, a deployed
            // server's copy would keep stale ladder shapes forever, since a
            // per-key merge never touches a top-level key (e.g. "mage") that
            // already exists on disk.
            val bundledVersion = defaults.getInt("mastery-quests-version", 0)
            if (yaml.getInt("mastery-quests-version", 0) < bundledVersion) {
                plugin.logger.info(
                    "$FILE_NAME: mastery-quests-version ${yaml.getInt("mastery-quests-version", 0)} -> $bundledVersion; " +
                        "replacing with bundled defaults (tune mastery-quests.yml in the plugin, not on the server).")
                file.delete()
                plugin.saveResource(FILE_NAME, false)
                yaml = YamlConfiguration.loadConfiguration(file)
            }
            yaml.setDefaults(defaults)
            yaml.options().copyDefaults(true)
        }
        lines.clear()
        // Every class may define its own subclasses' ladders - not just Mage.
        // Subclass ids are the only key (not "<class>.<subclass>"), so two
        // classes' subclasses must not share an id; ClassProgressionService's
        // mastery progress storage has the same constraint.
        for (classType in ClassType.entries) {
            val classSection = yaml.getConfigurationSection(classType.id) ?: continue
            for (subclassId in classSection.getKeys(false)) {
                val path = "${classType.id}.$subclassId."
                val rewardXp = yaml.getInt(path + "reward-xp", 0)
                val ladder = yaml.getMapList(path + "ladder").mapNotNull { raw ->
                    val objective = MasteryObjective.fromId(raw["objective"] as? String) ?: return@mapNotNull null
                    val required = (raw["required"] as? Number)?.toInt() ?: return@mapNotNull null
                    val title = raw["title"] as? String ?: subclassId
                    val description = raw["description"] as? String ?: ""
                    MasteryQuestStep(objective, required, title, description)
                }
                if (ladder.isNotEmpty()) lines[subclassId.lowercase()] = MasteryQuestLine(rewardXp, ladder)
            }
        }
    }

    fun line(subclassId: String): MasteryQuestLine? = lines[subclassId.lowercase()]

    companion object {
        private const val FILE_NAME = "mastery-quests.yml"
    }
}
