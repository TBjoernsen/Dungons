package nl.riddernix.dungeonplugin.world

import org.bukkit.block.Biome
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.WorldInfo

/**
 * Sets THE_VOID biome everywhere.
 *
 * Without this you get 'plains' by default: green fog, bird sounds and a
 * light blue sky. THE_VOID gives a neutral, dark atmosphere that suits a
 * dungeon far better.
 *
 * If you later want per-room biomes (SOUL_SAND_VALLEY for a crypt, say), this
 * is the place: look at x/z and return something else. Remember that biome
 * also drives fog and water colour.
 */
class VoidBiomeProvider : BiomeProvider() {

    override fun getBiome(worldInfo: WorldInfo, x: Int, y: Int, z: Int): Biome = Biome.THE_VOID

    override fun getBiomes(worldInfo: WorldInfo): List<Biome> = listOf(Biome.THE_VOID)
}
