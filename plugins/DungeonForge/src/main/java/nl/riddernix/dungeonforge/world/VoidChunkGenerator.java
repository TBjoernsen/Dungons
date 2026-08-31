package nl.riddernix.dungeonforge.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/**
 * Generates absolutely nothing.
 *
 * <p>Every generate* method is empty and every shouldGenerate* method returns
 * {@code false}, so Minecraft skips terrain, caves, structures, decoration and
 * mob spawning entirely. That is exactly what a dungeon wants: no terrain in
 * the way, no chunk generation cost, and everything you see is something you
 * placed yourself.</p>
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    private final int spawnX;
    private final int spawnY;
    private final int spawnZ;

    public VoidChunkGenerator(int spawnX, int spawnY, int spawnZ) {
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // intentionally empty
    }

    @Override
    public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // intentionally empty
    }

    @Override
    public void generateBedrock(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // intentionally empty - no bedrock layer
    }

    @Override
    public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // intentionally empty
    }

    // The WorldInfo variants are the current API. The old no-argument versions
    // still exist but are deprecated - don't use them.

    @Override
    public boolean shouldGenerateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return false;
    }

    // shouldGenerateBedrock() has no WorldInfo variant and is deprecated. We
    // leave it alone: generateBedrock() above is empty, so no bedrock layer
    // gets generated either way.

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new VoidBiomeProvider();
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, spawnX + 0.5, spawnY, spawnZ + 0.5);
    }
}
