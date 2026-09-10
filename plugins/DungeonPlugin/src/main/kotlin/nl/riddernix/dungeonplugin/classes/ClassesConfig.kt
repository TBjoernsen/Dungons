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
 * Normally this file merges: missing keys are filled from the bundled
 * defaults, existing values are left alone so admin tuning survives updates.
 * But it also carries a `config-version`, and when the bundled version is
 * higher than the file's the whole file is replaced with the bundled copy -
 * the balance numbers here change often and are meant to be tuned in the
 * bundled resource, not hand-held on the server across updates. Bump
 * `config-version` in the bundled `classes.yml` whenever those defaults move.
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
        plugin.getResource(FILE_NAME)?.use { resource ->
            val defaults = YamlConfiguration.loadConfiguration(InputStreamReader(resource, StandardCharsets.UTF_8))
            val bundledVersion = defaults.getInt("config-version", 0)
            if (yaml.getInt("config-version", 0) < bundledVersion) {
                plugin.logger.info(
                    "$FILE_NAME: config-version ${yaml.getInt("config-version", 0)} -> $bundledVersion; " +
                        "replacing with bundled defaults (tune classes.yml in the plugin, not on the server).")
                file.delete()
                plugin.saveResource(FILE_NAME, false)
                yaml = YamlConfiguration.loadConfiguration(file)
            }
            // Fill any still-missing keys without overwriting the rest.
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
