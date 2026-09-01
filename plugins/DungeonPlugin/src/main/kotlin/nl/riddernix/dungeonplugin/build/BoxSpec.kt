package nl.riddernix.dungeonplugin.build

import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.configuration.file.FileConfiguration
import java.util.Locale
import java.util.logging.Logger

/**
 * All settings for the test box, read from config.yml.
 *
 * Pure data, read once and never mutated. If you later want different
 * materials per room, just make several BoxSpecs.
 */
data class BoxSpec(
    val originX: Int,
    val originY: Int,
    val originZ: Int,
    val size: Int,
    val hollow: Boolean,
    val wall: BlockData,
    val floor: BlockData,
    val light: BlockData?,
    val lightSpacing: Int,
    val blocksPerTick: Int
) {

    /** Total number of positions the builder walks through. */
    fun volume(): Long = size.toLong() * size * size

    companion object {
        fun fromConfig(config: FileConfiguration, logger: Logger): BoxSpec {
            val size = maxOf(3, config.getInt("box.size", 48))

            val wall = parseMaterial(config.getString("box.material", "STONE"), Material.STONE, logger)
            val floor = parseMaterial(config.getString("box.floor-material", "STONE_BRICKS"), Material.STONE_BRICKS, logger)

            val lightsOn = config.getBoolean("box.lights.enabled", true)
            val light = if (lightsOn)
                parseMaterial(config.getString("box.lights.material", "SEA_LANTERN"), Material.SEA_LANTERN, logger)
            else null

            return BoxSpec(
                config.getInt("box.origin.x", 0),
                config.getInt("box.origin.y", 64),
                config.getInt("box.origin.z", 0),
                size,
                config.getBoolean("box.hollow", true),
                wall,
                floor,
                light,
                maxOf(2, config.getInt("box.lights.spacing", 8)),
                maxOf(256, config.getInt("performance.blocks-per-tick", 20000))
            )
        }

        private fun parseMaterial(name: String?, fallback: Material, logger: Logger): BlockData {
            if (name != null) {
                val material = Material.matchMaterial(name.uppercase(Locale.ROOT))
                if (material != null && material.isBlock) {
                    return material.createBlockData()
                }
                logger.warning("Unknown or unplaceable material '$name', falling back to $fallback.")
            }
            return fallback.createBlockData()
        }
    }
}
