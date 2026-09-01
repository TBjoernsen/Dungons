package nl.riddernix.dungeonplugin.skills

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
 * The skill panel's connection to the world: chunks bringing it back, clicks
 * selecting nodes, and players leaving cleaning their overlays up.
 */
class SkillPanelListener(private val plugin: DungeonPlugin) : Listener {

    /**
     * Panel entities are never saved with the chunk, so a loading chunk
     * starts empty and this refills it.
     */
    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        plugin.skillPanels.handleChunkLoad(event.chunk)
    }

    /** Right click. Fires once per hand; only the main hand counts. */
    @EventHandler
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND ||
            !plugin.skillPanels.isPanelEntity(event.rightClicked)) {
            return
        }
        event.isCancelled = true
        plugin.skillPanels.handleClick(event.player, event.rightClicked)
    }

    /** Left click, which fires as an attack even against invulnerable entities. */
    @EventHandler
    fun onAttack(event: PrePlayerAttackEntityEvent) {
        if (!plugin.skillPanels.isPanelEntity(event.attacked)) {
            return
        }
        event.isCancelled = true
        plugin.skillPanels.handleClick(event.player, event.attacked)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.skillPanels.handleQuit(event.player)
    }

    /** The overlays go; selection and test state stay for the session. */
    @EventHandler
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        plugin.skillPanels.clearOverlaysFor(event.player)
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        plugin.skillPanels.clearOverlaysFor(event.player)
    }
}
