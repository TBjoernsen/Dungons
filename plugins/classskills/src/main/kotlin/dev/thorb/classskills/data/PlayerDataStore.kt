package dev.thorb.classskills.data

import dev.thorb.classskills.model.ClassType
import dev.thorb.classskills.model.ClassProgress
import dev.thorb.classskills.model.PlayerSkillData
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.util.UUID

class PlayerDataStore(private val plugin: JavaPlugin) {
    private val file = File(plugin.dataFolder, "players.yml")
    private val data = mutableMapOf<UUID, PlayerSkillData>()
    private lateinit var yaml: YamlConfiguration

    fun load() {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (!file.exists()) file.createNewFile()
        yaml = YamlConfiguration.loadConfiguration(file)
    }

    fun get(uuid: UUID): PlayerSkillData = data.getOrPut(uuid) { read(uuid) }

    fun save(uuid: UUID) {
        val player = data[uuid] ?: return
        val path = "players.$uuid"
        yaml.set("$path.class", player.classType?.name)
        yaml.set("$path.level", player.level)
        yaml.set("$path.experience", player.experience)
        yaml.set("$path.available-points", player.availablePoints)
        yaml.set("$path.unlocked-difficulty", player.unlockedDifficulty)
        // DungeonForge owns unlocked nodes. Remove the old ClassSkills copy during
        // the next save instead of retaining a second source of truth.
        yaml.set("$path.nodes", null)
        yaml.set("$path.rage", player.rage)
        yaml.set("$path.rage-active-until", player.rageActiveUntil)
        yaml.set("$path.focus", player.focus)
        yaml.set("$path.judgment", player.judgment)
        yaml.set("$path.mana", player.mana)
        yaml.set("$path.point-economy-version", player.pointEconomyVersion)
        yaml.set("$path.tree-layout-version", player.treeLayoutVersion)
        yaml.set("$path.class-profiles", null)
        player.classProfiles.forEach { (classType, progress) ->
            val profile = "$path.class-profiles.${classType.name.lowercase()}"
            yaml.set("$profile.level", progress.level)
            yaml.set("$profile.experience", progress.experience)
            yaml.set("$profile.available-points", progress.availablePoints)
            yaml.set("$profile.unlocked-difficulty", progress.unlockedDifficulty)
            yaml.set("$profile.nodes", null)
        }
        flush()
    }

    fun saveAll() {
        data.keys.toList().forEach(::save)
    }

    /** Removes every ClassSkills-owned field and recreates the default Level-1 profile. */
    fun hardReset(uuid: UUID): PlayerSkillData {
        data.remove(uuid)
        yaml.set("players.$uuid", null)
        flush()
        return get(uuid)
    }

    private fun read(uuid: UUID): PlayerSkillData {
        val path = "players.$uuid"
        val profiles = linkedMapOf<ClassType, ClassProgress>()
        yaml.getConfigurationSection("$path.class-profiles")?.getKeys(false)?.forEach { key ->
            val classType = ClassType.fromInput(key) ?: return@forEach
            val profile = "$path.class-profiles.$key"
            profiles[classType] = ClassProgress(
                level = yaml.getInt("$profile.level", 1).coerceIn(1, 100),
                experience = yaml.getInt("$profile.experience", 0).coerceAtLeast(0),
                availablePoints = yaml.getInt("$profile.available-points", 2).coerceAtLeast(0),
                unlockedDifficulty = yaml.getInt("$profile.unlocked-difficulty", 1).coerceIn(1, 9)
            )
        }
        return PlayerSkillData(
            classType = ClassType.fromInput(yaml.getString("$path.class")),
            level = yaml.getInt("$path.level", 1).coerceIn(1, 100),
            experience = yaml.getInt("$path.experience", 0).coerceAtLeast(0),
            availablePoints = yaml.getInt("$path.available-points", 2).coerceAtLeast(0),
            unlockedDifficulty = yaml.getInt("$path.unlocked-difficulty", 1).coerceIn(1, 9),
            rage = yaml.getDouble("$path.rage", 0.0),
            rageActiveUntil = yaml.getLong("$path.rage-active-until", 0L),
            focus = yaml.getInt("$path.focus", 0).coerceAtLeast(0),
            judgment = yaml.getDouble("$path.judgment", 0.0).coerceAtLeast(0.0),
            mana = yaml.getDouble("$path.mana", 0.0).coerceAtLeast(0.0),
            pointEconomyVersion = yaml.getInt("$path.point-economy-version", 1),
            treeLayoutVersion = yaml.getInt("$path.tree-layout-version", 1),
            classProfiles = profiles
        )
    }

    private fun flush() {
        try {
            yaml.save(file)
        } catch (exception: IOException) {
            plugin.logger.severe("Could not save players.yml: ${exception.message}")
        }
    }
}
