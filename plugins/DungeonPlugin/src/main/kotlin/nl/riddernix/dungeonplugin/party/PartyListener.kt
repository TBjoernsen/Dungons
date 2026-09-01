package nl.riddernix.dungeonplugin.party

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/** Retains party-instance occupancy across disconnects and restores rejoiners. */
class PartyListener(private val plugin: DungeonPlugin) : Listener {

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.rooms.forget(event.player)
        plugin.parties.rememberDisconnect(event.player)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val completionReturn = plugin.parties.takeCompletionReturn(event.player.uniqueId)
        if (completionReturn != null) {
            plugin.server.scheduler.runTask(plugin, Runnable { event.player.teleport(completionReturn) })
            return
        }
        plugin.parties.reconnectLocation(event.player)?.let { location ->
            plugin.server.scheduler.runTask(plugin, Runnable {
                event.player.teleport(location)
                plugin.rooms.refresh(event.player)
            })
        }
    }
}
