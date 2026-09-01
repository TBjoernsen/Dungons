package nl.riddernix.dungeonplugin.model

import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.event.DungeonMobSpawnEvent
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.Locale

/**
 * Attaches a custom model to a live dungeon mob.
 *
 * The interface exists so [ModelIntegration] never mentions a model engine's
 * classes. Only the implementation does, and that implementation is loaded
 * exclusively when its plugin is present.
 */
interface ModelApplier {

    /** Names the engine behind this applier for console reporting. */
    fun engineName(): String

    /** Every model name the engine currently has loaded. */
    fun modelNames(): Set<String>

    /**
     * Attaches [modelName] to [entity].
     *
     * @param animation optional animation to start immediately, blank for none
     * @return true when the model existed and was attached
     */
    fun apply(entity: LivingEntity, modelName: String, animation: String?, options: Options): Boolean

    /** Per-server display choices, read once from config.yml. */
    data class Options(val hideVanillaEntity: Boolean, val scaleWithEntity: Boolean)
}

/**
 * Gives dungeon mobs a custom animated model.
 *
 * This listens to the plugin's own spawn event instead of reaching into the
 * spawner, so the mob pipeline stays untouched and any server without a model
 * engine keeps plain vanilla mobs.
 */
class ModelIntegration(private val plugin: DungeonPlugin) : Listener {

    private val missingModels = HashSet<String>()
    private var applier: ModelApplier? = null
    private var options = ModelApplier.Options(hideVanillaEntity = true, scaleWithEntity = true)

    init {
        reload()
    }

    /** Re-reads the display options and re-detects the model engine. */
    fun reload() {
        missingModels.clear()
        options = ModelApplier.Options(
            plugin.config.getBoolean("models.hide-vanilla-entity", true),
            plugin.config.getBoolean("models.scale-with-entity", true))
        if (!plugin.config.getBoolean("models.enabled", true)) {
            applier = null
            return
        }
        applier = detectApplier()
        applier?.let {
            plugin.logger.info("Custom mob models enabled through ${it.engineName()}" +
                " (${it.modelNames().size} model(s) loaded).")
        }
    }

    /**
     * Loading [BetterModelApplier] before this check would fail with a
     * [NoClassDefFoundError], so the plugin lookup has to come first.
     */
    private fun detectApplier(): ModelApplier? {
        val betterModel = plugin.server.pluginManager.getPlugin("BetterModel")
        if (betterModel == null || !betterModel.isEnabled) {
            if (hasConfiguredModels()) {
                plugin.logger.warning("config.yml assigns custom mob models, but BetterModel is not installed. " +
                    "Dungeon mobs will keep their vanilla appearance.")
            }
            return null
        }
        return BetterModelApplier()
    }

    /** True when at least one theme or default actually names a model. */
    private fun hasConfiguredModels(): Boolean = configuredModelNames().isNotEmpty()

    /** Every model name config.yml refers to, for reporting and validation. */
    fun configuredModelNames(): List<String> {
        val names = ArrayList<String>()
        collect(names, "models.defaults.boss")
        val defaults = plugin.config.getConfigurationSection("models.defaults.categories")
        if (defaults != null) {
            for (category in defaults.getKeys(false)) collect(names, "models.defaults.categories.$category")
        }
        val themes = plugin.config.getConfigurationSection("models.themes") ?: return names.toList()
        for (theme in themes.getKeys(false)) {
            collect(names, "models.themes.$theme.boss")
            val categories = plugin.config.getConfigurationSection("models.themes.$theme.categories") ?: continue
            for (category in categories.getKeys(false)) {
                collect(names, "models.themes.$theme.categories.$category")
            }
        }
        return names.toList()
    }

    private fun collect(names: MutableList<String>, path: String) {
        val value = plugin.config.getString(path, "")
        if (!value.isNullOrBlank() && value !in names) names.add(value)
    }

    /** One line per configured model, saying whether the engine actually has it. */
    fun diagnostics(): List<String> {
        val lines = ArrayList<String>()
        val applier = applier
        if (applier == null) {
            lines.add(if (plugin.config.getBoolean("models.enabled", true))
                "No model engine detected - install BetterModel to use custom models."
            else "Custom models are disabled in config.yml (models.enabled).")
            return lines
        }
        lines.add("engine=${applier.engineName()} loaded-models=${applier.modelNames().size}")
        for (name in configuredModelNames()) {
            lines.add("$name = ${if (name in applier.modelNames()) "found" else "MISSING from the engine"}")
        }
        if (configuredModelNames().isEmpty()) lines.add("config.yml assigns no models yet (models.themes / models.defaults).")
        return lines
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDungeonMobSpawn(event: DungeonMobSpawnEvent) {
        val applier = applier ?: return
        val model = modelFor(event) ?: return
        val animation = plugin.config.getString("models.animations.spawn", "")
        if (!applier.apply(event.entity, model, animation, options) && missingModels.add(model)) {
            plugin.logger.warning("Model '$model' is assigned in config.yml but " +
                "${applier.engineName()} has no such model loaded. That mob stays vanilla.")
        }
    }

    /** Theme first, then the shared default; blank at both levels means vanilla. */
    private fun modelFor(event: DungeonMobSpawnEvent): String? {
        val theme = normalize(event.theme)
        val suffix = if (event.isBoss) "boss" else "categories." + normalize(event.category)
        val themed = if (theme.isEmpty()) "" else value("models.themes.$theme.$suffix")
        return if (themed.isEmpty()) value("models.defaults.$suffix").ifEmpty { null } else themed
    }

    private fun value(path: String): String = plugin.config.getString(path, "")?.trim() ?: ""

    companion object {
        private fun normalize(raw: String?): String = raw?.trim()?.lowercase(Locale.ROOT) ?: ""
    }
}
