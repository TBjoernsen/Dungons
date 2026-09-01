package nl.riddernix.dungeonplugin.classes

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * The class layer's own configuration file, `classes.yml`.
 *
 * Deliberately separate from config.yml: the dungeon config is replaced
 * wholesale on a version bump, while this file follows the class layer's
 * older philosophy of merging missing keys into an existing file so admin
 * tuning survives updates. Two files keep those two update models from
 * fighting each other.
 */
class ClassesConfig(private val plugin: DungeonPlugin) {

    lateinit var yaml: YamlConfiguration
        private set

    private val file = File(plugin.dataFolder, FILE_NAME)

    init {
        reload()
    }

    fun reload() {
        if (!file.isFile) {
            plugin.saveResource(FILE_NAME, false)
        }
        yaml = YamlConfiguration.loadConfiguration(file)
        // Missing keys are filled in from the bundled defaults without ever
        // overwriting a value the admin changed.
        plugin.getResource(FILE_NAME)?.use { resource ->
            val defaults = YamlConfiguration.loadConfiguration(InputStreamReader(resource, StandardCharsets.UTF_8))
            yaml.setDefaults(defaults)
            yaml.options().copyDefaults(true)
        }
        save()
    }

    fun save() {
        try {
            yaml.save(file)
        } catch (exception: IOException) {
            plugin.logger.severe("Could not save $FILE_NAME: ${exception.message}")
        }
    }

    fun getBoolean(path: String, default: Boolean): Boolean = yaml.getBoolean(path, default)
    fun getInt(path: String, default: Int): Int = yaml.getInt(path, default)
    fun getDouble(path: String, default: Double): Double = yaml.getDouble(path, default)
    fun getString(path: String, default: String): String = yaml.getString(path, default) ?: default
    fun set(path: String, value: Any?) = yaml.set(path, value)

    companion object {
        private const val FILE_NAME = "classes.yml"
    }
}
