package nl.riddernix.dungeonforge.world;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.List;

/**
 * Sets THE_VOID biome everywhere.
 *
 * <p>Without this you get 'plains' by default: green fog, bird sounds and a
 * light blue sky. THE_VOID gives a neutral, dark atmosphere that suits a
 * dungeon far better.</p>
 *
 * <p>If you later want per-room biomes (SOUL_SAND_VALLEY for a crypt, say),
 * this is the place: look at x/z and return something else. Remember that
 * biome also drives fog and water colour.</p>
 */
public final class VoidBiomeProvider extends BiomeProvider {

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        return Biome.THE_VOID;
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return List.of(Biome.THE_VOID);
    }
}
