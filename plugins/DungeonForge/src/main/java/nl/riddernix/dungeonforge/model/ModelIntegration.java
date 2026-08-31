package nl.riddernix.dungeonforge.model;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.api.DungeonMobSpawnEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gives dungeon mobs a custom animated model.
 *
 * <p>This listens to DungeonForge's own public spawn event instead of reaching
 * into the spawner, so the mob pipeline stays untouched and any server without
 * a model engine keeps plain vanilla mobs.</p>
 */
public final class ModelIntegration implements Listener {

    private final DungeonForgePlugin plugin;
    private final Set<String> missingModels = new HashSet<>();
    private ModelApplier applier;
    private ModelApplier.Options options = new ModelApplier.Options(true, true);

    public ModelIntegration(DungeonForgePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Re-reads the display options and re-detects the model engine. */
    public void reload() {
        missingModels.clear();
        options = new ModelApplier.Options(
                plugin.getConfig().getBoolean("models.hide-vanilla-entity", true),
                plugin.getConfig().getBoolean("models.scale-with-entity", true));
        if (!plugin.getConfig().getBoolean("models.enabled", true)) {
            applier = null;
            return;
        }
        applier = detectApplier();
        if (applier != null) {
            plugin.getLogger().info("Custom mob models enabled through " + applier.engineName()
                    + " (" + applier.modelNames().size() + " model(s) loaded).");
        }
    }

    /**
     * Loading {@link BetterModelApplier} before this check would fail with a
     * {@link NoClassDefFoundError}, so the plugin lookup has to come first.
     */
    private ModelApplier detectApplier() {
        Plugin betterModel = plugin.getServer().getPluginManager().getPlugin("BetterModel");
        if (betterModel == null || !betterModel.isEnabled()) {
            if (hasConfiguredModels()) {
                plugin.getLogger().warning("config.yml assigns custom mob models, but BetterModel is not installed. "
                        + "Dungeon mobs will keep their vanilla appearance.");
            }
            return null;
        }
        return new BetterModelApplier();
    }

    /** True when at least one theme or default actually names a model. */
    private boolean hasConfiguredModels() {
        return !configuredModelNames().isEmpty();
    }

    /** Every model name config.yml refers to, for reporting and validation. */
    public List<String> configuredModelNames() {
        List<String> names = new ArrayList<>();
        collect(names, "models.defaults.boss");
        var defaults = plugin.getConfig().getConfigurationSection("models.defaults.categories");
        if (defaults != null) {
            for (String category : defaults.getKeys(false)) collect(names, "models.defaults.categories." + category);
        }
        var themes = plugin.getConfig().getConfigurationSection("models.themes");
        if (themes == null) return List.copyOf(names);
        for (String theme : themes.getKeys(false)) {
            collect(names, "models.themes." + theme + ".boss");
            var categories = plugin.getConfig().getConfigurationSection("models.themes." + theme + ".categories");
            if (categories == null) continue;
            for (String category : categories.getKeys(false)) {
                collect(names, "models.themes." + theme + ".categories." + category);
            }
        }
        return List.copyOf(names);
    }

    private void collect(List<String> names, String path) {
        String value = plugin.getConfig().getString(path, "");
        if (value != null && !value.isBlank() && !names.contains(value)) names.add(value);
    }

    /** One line per configured model, saying whether the engine actually has it. */
    public List<String> diagnostics() {
        List<String> lines = new ArrayList<>();
        if (applier == null) {
            lines.add(plugin.getConfig().getBoolean("models.enabled", true)
                    ? "No model engine detected - install BetterModel to use custom models."
                    : "Custom models are disabled in config.yml (models.enabled).");
            return lines;
        }
        lines.add("engine=" + applier.engineName() + " loaded-models=" + applier.modelNames().size());
        for (String name : configuredModelNames()) {
            lines.add(name + " = " + (applier.modelNames().contains(name) ? "found" : "MISSING from the engine"));
        }
        if (configuredModelNames().isEmpty()) lines.add("config.yml assigns no models yet (models.themes / models.defaults).");
        return lines;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDungeonMobSpawn(DungeonMobSpawnEvent event) {
        if (applier == null) return;
        String model = modelFor(event);
        if (model == null) return;
        String animation = plugin.getConfig().getString("models.animations.spawn", "");
        if (!applier.apply(event.getEntity(), model, animation, options) && missingModels.add(model)) {
            plugin.getLogger().warning("Model '" + model + "' is assigned in config.yml but "
                    + applier.engineName() + " has no such model loaded. That mob stays vanilla.");
        }
    }

    /** Theme first, then the shared default; blank at both levels means vanilla. */
    private String modelFor(DungeonMobSpawnEvent event) {
        String theme = normalize(event.getTheme());
        String suffix = event.isBoss() ? "boss" : "categories." + normalize(event.getCategory());
        String themed = theme.isEmpty() ? "" : value("models.themes." + theme + "." + suffix);
        return themed.isEmpty() ? emptyToNull(value("models.defaults." + suffix)) : themed;
    }

    private String value(String path) {
        String raw = plugin.getConfig().getString(path, "");
        return raw == null ? "" : raw.trim();
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
