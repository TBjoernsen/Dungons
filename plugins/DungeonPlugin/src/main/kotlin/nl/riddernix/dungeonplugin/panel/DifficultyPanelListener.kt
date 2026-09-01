package nl.riddernix.dungeonplugin.panel

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * The panel's connection to the world: chunks bringing it back, clicks
 * driving it, and players leaving cleaning their personal row up.
 */
class DifficultyPanelListener(private val plugin: DungeonPlugin) : Listener {

    /**
     * Panel entities are never saved with the chunk, so a chunk that unloads
     * takes them with it and a chunk that loads starts empty. This is the
     * half that puts them back.
     */
    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        plugin.panels.handleChunkLoad(event.chunk)
    }

    /** Right click. The event fires once per hand; only the main hand counts. */
    @EventHandler
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND ||
            !plugin.panels.isPanelEntity(event.rightClicked)) {
            return
        }
        event.isCancelled = true
        plugin.panels.handleClick(event.player, event.rightClicked)
    }

    /** Left click, which fires as an attack even against invulnerable entities. */
    @EventHandler
    fun onAttack(event: PrePlayerAttackEntityEvent) {
        if (!plugin.panels.isPanelEntity(event.attacked)) {
            return
        }
        event.isCancelled = true
        plugin.panels.handleClick(event.player, event.attacked)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.panels.handleQuit(event.player)
    }

    /** The selection survives both of these; only the rendered row is cleaned. */
    @EventHandler
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        plugin.panels.clearPersonalRows(event.player)
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        plugin.panels.clearPersonalRows(event.player)
    }
}
