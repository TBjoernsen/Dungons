package nl.riddernix.dungeonplugin.room

import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.Plugin
import java.util.Locale

/** Reads all configured marker materials from one shared source of truth. */
object DungeonMarkerDefinitions {

    fun read(config: FileConfiguration, plugin: Plugin): List<Definition> {
        val section = config.getConfigurationSection("mobs.markers.materials") ?: return emptyList()
        val byMaterial = LinkedHashMap<Material, Definition>()
        for (category in section.getKeys(false)) {
            val value = section.getString(category, "")
            val material = Material.matchMaterial((value ?: "").uppercase(Locale.ROOT))
            if (material == null || !material.isBlock) {
                plugin.logger.warning("Unknown marker material for '$category': $value.")
                continue
            }
            val normalizedCategory = category.lowercase(Locale.ROOT)
            val description = config.getString("mobs.markers.descriptions.$normalizedCategory", "") ?: ""
            val definition = Definition(normalizedCategory, material, description)
            val previous = byMaterial.put(material, definition)
            if (previous != null) plugin.logger.warning("Marker material $material is assigned to both '" +
                "${previous.category}' and '$category'; the latter will be used.")
        }
        val doorValue = config.getString("generation.rooms.markers.doorway.material", "RED_WOOL")
        val doorMaterial = Material.matchMaterial((doorValue ?: "").uppercase(Locale.ROOT))
        if (doorMaterial == null || !doorMaterial.isBlock) {
            plugin.logger.warning("Unknown doorway marker material: $doorValue.")
        } else {
            val doorway = Definition("doorway", doorMaterial,
                config.getString("generation.rooms.markers.doorway.description",
                    "Marks a prefab doorway directly below this block.")!!)
            val previous = byMaterial.put(doorMaterial, doorway)
            if (previous != null) plugin.logger.warning("Doorway marker material $doorMaterial" +
                " was also assigned to '${previous.category}'; doorway markers take priority.")
        }
        for (kind in SpecialMarkerKind.entries) {
            val path = "generation.rooms.markers.${kind.configName}"
            val value = config.getString("$path.material",
                if (kind == SpecialMarkerKind.PLAYER_SPAWN) "GRAY_WOOL" else "LIGHT_BLUE_WOOL")
            val material = Material.matchMaterial((value ?: "").uppercase(Locale.ROOT))
            if (material == null || !material.isBlock) {
                plugin.logger.warning("Unknown ${kind.configName} marker material: $value.")
                continue
            }
            val definition = Definition(kind.configName, material,
                config.getString("$path.description", "Marks a prefab ${kind.configName} position.")!!)
            val previous = byMaterial.put(material, definition)
            if (previous != null) plugin.logger.warning("Special marker material $material is assigned to both '" +
                "${previous.category}' and '${kind.configName}'; the special marker takes priority.")
        }
        return byMaterial.values.toList()
    }

    data class Definition(val category: String, val material: Material, val description: String)
}
