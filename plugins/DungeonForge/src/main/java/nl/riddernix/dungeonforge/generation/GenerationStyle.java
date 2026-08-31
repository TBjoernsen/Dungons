package nl.riddernix.dungeonforge.generation;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Materials for procedural rooms, fallback normal rooms, and temporary corridor platforms. */
public final class GenerationStyle {

    private final Map<DungeonLayout.RoomType, BlockData> roomMaterials;
    private final Map<String, BlockData> markerMaterials;
    private final BlockData corridorFloorMaterial;
    private final BlockData corridorLipMaterial;
    private final BlockData air = Material.AIR.createBlockData();

    private GenerationStyle(Map<DungeonLayout.RoomType, BlockData> roomMaterials, Map<String, BlockData> markerMaterials,
                            BlockData corridorFloorMaterial, BlockData corridorLipMaterial) {
        this.roomMaterials = Map.copyOf(roomMaterials);
        this.markerMaterials = Map.copyOf(markerMaterials);
        this.corridorFloorMaterial = corridorFloorMaterial;
        this.corridorLipMaterial = corridorLipMaterial;
    }

    public static GenerationStyle fromConfig(FileConfiguration config, Logger logger) {
        Map<DungeonLayout.RoomType, BlockData> rooms = new EnumMap<>(DungeonLayout.RoomType.class);
        rooms.put(DungeonLayout.RoomType.SPAWN,
                material(config, "generation.materials.entrance", Material.MOSS_BLOCK, logger));
        rooms.put(DungeonLayout.RoomType.NORMAL,
                material(config, "generation.materials.normal-room", Material.STONE, logger));
        rooms.put(DungeonLayout.RoomType.BRANCH,
                material(config, "generation.materials.branch", Material.STONE, logger));
        rooms.put(DungeonLayout.RoomType.BOSS,
                material(config, "generation.materials.boss", Material.REDSTONE_BLOCK, logger));
        Map<String, BlockData> markers = new java.util.HashMap<>();
        for (String category : new String[]{"swarm", "pack", "champion"}) {
            markers.put(category, material(config, "mobs.markers.materials." + category, Material.WHITE_WOOL, logger));
        }
        return new GenerationStyle(rooms, markers,
                material(config, "generation.corridor.floor-material", Material.STONE, logger),
                material(config, "generation.corridor.safety-lips.material", Material.STONE, logger));
    }

    public BlockData material(DungeonLayout.RoomType type) {
        return roomMaterials.get(type);
    }

    public BlockData corridorFloorMaterial() {
        return corridorFloorMaterial;
    }

    public BlockData corridorLipMaterial() {
        return corridorLipMaterial;
    }

    public BlockData air() {
        return air;
    }

    public BlockData marker(String category) {
        return markerMaterials.getOrDefault(category.toLowerCase(Locale.ROOT), Material.WHITE_WOOL.createBlockData());
    }

    private static BlockData material(FileConfiguration config, String path, Material fallback, Logger logger) {
        String raw = config.getString(path, fallback.name());
        Material material = raw == null ? null : Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
        if (material == null || !material.isBlock()) {
            logger.warning("Unknown or unplaceable material at '" + path + "': " + raw
                    + ". Falling back to " + fallback + ".");
            material = fallback;
        }
        return material.createBlockData();
    }
}
