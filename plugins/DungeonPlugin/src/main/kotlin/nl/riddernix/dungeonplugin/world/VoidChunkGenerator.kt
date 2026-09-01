package nl.riddernix.dungeonplugin.world

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random

/**
 * Generates absolutely nothing.
 *
 * Every generate* method is empty and every shouldGenerate* method returns
 * `false`, so Minecraft skips terrain, caves, structures, decoration and mob
 * spawning entirely. That is exactly what a dungeon wants: no terrain in the
 * way, no chunk generation cost, and everything you see is something you
 * placed yourself.
 */
class VoidChunkGenerator(
    private val spawnX: Int,
    private val spawnY: Int,
    private val spawnZ: Int
) : ChunkGenerator() {

    override fun generateNoise(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        // intentionally empty
    }

    override fun generateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        // intentionally empty
    }

    override fun generateBedrock(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        // intentionally empty - no bedrock layer
    }

    override fun generateCaves(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        // intentionally empty
    }

    // The WorldInfo variants are the current API. The old no-argument versions
    // still exist but are deprecated - don't use them.

    override fun shouldGenerateNoise(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int) = false

    override fun shouldGenerateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int) = false

    override fun shouldGenerateCaves(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int) = false

    override fun shouldGenerateDecorations(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int) = false

    override fun shouldGenerateMobs(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int) = false

    override fun shouldGenerateStructures(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int) = false

    // shouldGenerateBedrock() has no WorldInfo variant and is deprecated. We
    // leave it alone: generateBedrock() above is empty, so no bedrock layer
    // gets generated either way.

    override fun getDefaultBiomeProvider(worldInfo: WorldInfo): BiomeProvider = VoidBiomeProvider()

    override fun getFixedSpawnLocation(world: World, random: Random): Location =
        Location(world, spawnX + 0.5, spawnY.toDouble(), spawnZ + 0.5)
}
