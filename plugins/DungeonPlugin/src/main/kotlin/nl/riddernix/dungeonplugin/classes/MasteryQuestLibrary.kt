package nl.riddernix.dungeonplugin.classes

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** What a cumulative counter toward a mastery ladder step actually counts. */
enum class MasteryObjective(val id: String) {
    DEAL_DAMAGE("deal_damage"),
    HEAL_AMOUNT("heal_amount");

    companion object {
        fun fromId(raw: String?): MasteryObjective? = entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
    }
}

data class MasteryQuestStep(val required: Int, val title: String, val description: String)

data class MasteryQuestLine(val objective: MasteryObjective, val rewardXp: Int, val ladder: List<MasteryQuestStep>)

/**
 * The fixed ten-step mastery quest ladder per subclass, from
 * `mastery-quests.yml` - deliberately separate from the random-rolled
 * Daily/Weekly/General system in [nl.riddernix.dungeonplugin.quest.QuestManager].
 * A line is one cumulative objective checked against ten increasing
 * thresholds; [ClassProgressionService] owns the per-player progress and
 * claiming against these definitions.
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
                val objective = MasteryObjective.fromId(yaml.getString(path + "objective")) ?: continue
                val rewardXp = yaml.getInt(path + "reward-xp", 0)
                val ladder = yaml.getMapList(path + "ladder").mapNotNull { raw ->
                    val required = (raw["required"] as? Number)?.toInt() ?: return@mapNotNull null
                    val title = raw["title"] as? String ?: subclassId
                    val description = raw["description"] as? String ?: ""
                    MasteryQuestStep(required, title, description)
                }
                if (ladder.isNotEmpty()) lines[subclassId.lowercase()] = MasteryQuestLine(objective, rewardXp, ladder)
            }
        }
    }

    fun line(subclassId: String): MasteryQuestLine? = lines[subclassId.lowercase()]

    companion object {
        private const val FILE_NAME = "mastery-quests.yml"
    }
}
