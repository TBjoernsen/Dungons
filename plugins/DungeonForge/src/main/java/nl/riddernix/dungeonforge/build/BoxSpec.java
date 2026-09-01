package nl.riddernix.dungeonforge.build;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * All settings for the test box, read from config.yml.
 *
 * <p>A record fits well here: it is pure data, read once and never mutated.
 * If you later want different materials per room, just make several
 * BoxSpecs.</p>
 */
public record BoxSpec(
        int originX,
        int originY,
        int originZ,
        int size,
        boolean hollow,
        BlockData wall,
        BlockData floor,
        BlockData light,
        int lightSpacing,
        int blocksPerTick
) {

    /** Total number of positions the builder walks through. */
    public long volume() {
        return (long) size * size * size;
    }

    public static BoxSpec fromConfig(FileConfiguration config, Logger logger) {
        int size = Math.max(3, config.getInt("box.size", 48));

        BlockData wall = parseMaterial(config.getString("box.material", "STONE"), Material.STONE, logger);
        BlockData floor = parseMaterial(config.getString("box.floor-material", "STONE_BRICKS"), Material.STONE_BRICKS, logger);

        boolean lightsOn = config.getBoolean("box.lights.enabled", true);
        BlockData light = lightsOn
                ? parseMaterial(config.getString("box.lights.material", "SEA_LANTERN"), Material.SEA_LANTERN, logger)
                : null;

        return new BoxSpec(
                config.getInt("box.origin.x", 0),
                config.getInt("box.origin.y", 64),
                config.getInt("box.origin.z", 0),
                size,
                config.getBoolean("box.hollow", true),
                wall,
                floor,
                light,
                Math.max(2, config.getInt("box.lights.spacing", 8)),
                Math.max(256, config.getInt("performance.blocks-per-tick", 20000))
        );
    }

    private static BlockData parseMaterial(String name, Material fallback, Logger logger) {
        if (name != null) {
            Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (material != null && material.isBlock()) {
                return material.createBlockData();
            }
            logger.warning("Unknown or unplaceable material '" + name + "', falling back to " + fallback + ".");
        }
        return fallback.createBlockData();
    }
}
