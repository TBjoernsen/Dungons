package nl.riddernix.dungeonplugin.quest

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

/** The quest board's link to the world: chunk reloads, clicks, and cleanup on leave. */
class QuestBoardListener(private val plugin: DungeonPlugin) : Listener {

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        plugin.questBoards.handleChunkLoad(event.chunk)
    }

    /** Right click; fires once per hand, only the main hand counts. */
    @EventHandler
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND || !plugin.questBoards.isBoardEntity(event.rightClicked)) return
        event.isCancelled = true
        plugin.questBoards.handleClick(event.player, event.rightClicked)
    }

    /** Left click, which fires as an attack even against invulnerable entities. */
    @EventHandler
    fun onAttack(event: PrePlayerAttackEntityEvent) {
        if (!plugin.questBoards.isBoardEntity(event.attacked)) return
        event.isCancelled = true
        plugin.questBoards.handleClick(event.player, event.attacked)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.questBoards.handleQuit(event.player)
    }

    @EventHandler
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        plugin.questBoards.clearOverlaysFor(event.player)
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        plugin.questBoards.clearOverlaysFor(event.player)
    }
}
