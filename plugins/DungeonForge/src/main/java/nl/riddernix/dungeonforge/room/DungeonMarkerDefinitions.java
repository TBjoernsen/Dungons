package nl.riddernix.dungeonforge.room;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads all configured marker materials from one shared source of truth. */
public final class DungeonMarkerDefinitions {

    private DungeonMarkerDefinitions() {
    }

    public static List<Definition> read(FileConfiguration config, Plugin plugin) {
        ConfigurationSection section = config.getConfigurationSection("mobs.markers.materials");
        if (section == null) return List.of();
        Map<Material, Definition> byMaterial = new LinkedHashMap<>();
        for (String category : section.getKeys(false)) {
            String value = section.getString(category, "");
            Material material = Material.matchMaterial(value == null ? "" : value.toUpperCase(Locale.ROOT));
            if (material == null || !material.isBlock()) {
                plugin.getLogger().warning("Unknown marker material for '" + category + "': " + value + ".");
                continue;
            }
            String normalizedCategory = category.toLowerCase(Locale.ROOT);
            String description = config.getString("mobs.markers.descriptions." + normalizedCategory, "");
            Definition definition = new Definition(normalizedCategory, material, description == null ? "" : description);
            Definition previous = byMaterial.put(material, definition);
            if (previous != null) plugin.getLogger().warning("Marker material " + material + " is assigned to both '"
                    + previous.category() + "' and '" + category + "'; the latter will be used.");
        }
        String doorValue = config.getString("generation.rooms.markers.doorway.material", "RED_WOOL");
        Material doorMaterial = Material.matchMaterial(doorValue == null ? "" : doorValue.toUpperCase(Locale.ROOT));
        if (doorMaterial == null || !doorMaterial.isBlock()) {
            plugin.getLogger().warning("Unknown doorway marker material: " + doorValue + ".");
        } else {
            Definition doorway = new Definition("doorway", doorMaterial,
                    config.getString("generation.rooms.markers.doorway.description", "Marks a prefab doorway directly below this block."));
            Definition previous = byMaterial.put(doorMaterial, doorway);
            if (previous != null) plugin.getLogger().warning("Doorway marker material " + doorMaterial
                    + " was also assigned to '" + previous.category() + "'; doorway markers take priority.");
        }
        for (SpecialMarkerKind kind : SpecialMarkerKind.values()) {
            String path = "generation.rooms.markers." + kind.configName();
            String value = config.getString(path + ".material", kind == SpecialMarkerKind.PLAYER_SPAWN ? "GRAY_WOOL" : "LIGHT_BLUE_WOOL");
            Material material = Material.matchMaterial(value == null ? "" : value.toUpperCase(Locale.ROOT));
            if (material == null || !material.isBlock()) {
                plugin.getLogger().warning("Unknown " + kind.configName() + " marker material: " + value + ".");
                continue;
            }
            Definition definition = new Definition(kind.configName(), material,
                    config.getString(path + ".description", "Marks a prefab " + kind.configName() + " position."));
            Definition previous = byMaterial.put(material, definition);
            if (previous != null) plugin.getLogger().warning("Special marker material " + material + " is assigned to both '"
                    + previous.category() + "' and '" + kind.configName() + "'; the special marker takes priority.");
        }
        return List.copyOf(new ArrayList<>(byMaterial.values()));
    }

    public record Definition(String category, Material material, String description) {
    }
}
