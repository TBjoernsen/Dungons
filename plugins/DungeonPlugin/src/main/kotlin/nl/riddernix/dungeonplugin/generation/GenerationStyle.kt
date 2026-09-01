package nl.riddernix.dungeonplugin.generation

import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.configuration.file.FileConfiguration
import java.util.EnumMap
import java.util.Locale
import java.util.logging.Logger

/** Materials for procedural rooms, fallback normal rooms, and temporary corridor platforms. */
class GenerationStyle private constructor(
    roomMaterials: Map<DungeonLayout.RoomType, BlockData>,
    markerMaterials: Map<String, BlockData>,
    private val corridorFloorMaterial: BlockData,
    private val corridorLipMaterial: BlockData
) {

    private val roomMaterials: Map<DungeonLayout.RoomType, BlockData> = roomMaterials.toMap()
    private val markerMaterials: Map<String, BlockData> = markerMaterials.toMap()
    private val air: BlockData = Material.AIR.createBlockData()

    fun material(type: DungeonLayout.RoomType): BlockData = roomMaterials.getValue(type)

    fun corridorFloorMaterial(): BlockData = corridorFloorMaterial

    fun corridorLipMaterial(): BlockData = corridorLipMaterial

    fun air(): BlockData = air

    fun marker(category: String): BlockData =
        markerMaterials[category.lowercase(Locale.ROOT)] ?: Material.WHITE_WOOL.createBlockData()

    companion object {
        fun fromConfig(config: FileConfiguration, logger: Logger): GenerationStyle {
            val rooms = EnumMap<DungeonLayout.RoomType, BlockData>(DungeonLayout.RoomType::class.java)
            rooms[DungeonLayout.RoomType.SPAWN] =
                material(config, "generation.materials.entrance", Material.MOSS_BLOCK, logger)
            rooms[DungeonLayout.RoomType.NORMAL] =
                material(config, "generation.materials.normal-room", Material.STONE, logger)
            rooms[DungeonLayout.RoomType.BRANCH] =
                material(config, "generation.materials.branch", Material.STONE, logger)
            rooms[DungeonLayout.RoomType.BOSS] =
                material(config, "generation.materials.boss", Material.REDSTONE_BLOCK, logger)
            val markers = HashMap<String, BlockData>()
            for (category in arrayOf("swarm", "pack", "champion")) {
                markers[category] = material(config, "mobs.markers.materials.$category", Material.WHITE_WOOL, logger)
            }
            return GenerationStyle(rooms, markers,
                material(config, "generation.corridor.floor-material", Material.STONE, logger),
                material(config, "generation.corridor.safety-lips.material", Material.STONE, logger))
        }

        private fun material(config: FileConfiguration, path: String, fallback: Material, logger: Logger): BlockData {
            val raw = config.getString(path, fallback.name)
            var material = raw?.let { Material.matchMaterial(it.uppercase(Locale.ROOT)) }
            if (material == null || !material.isBlock) {
                logger.warning("Unknown or unplaceable material at '$path': $raw. Falling back to $fallback.")
                material = fallback
            }
            return material.createBlockData()
        }
    }
}
