package nl.riddernix.dungeonforge.party;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Retains party-instance occupancy across disconnects and restores rejoiners. */
public final class PartyListener implements Listener {

    private final DungeonForgePlugin plugin;

    public PartyListener(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.rooms().forget(event.getPlayer());
        plugin.parties().rememberDisconnect(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.parties().takeCompletionReturn(event.getPlayer().getUniqueId()).map(location -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> event.getPlayer().teleport(location));
            return true;
        }).orElse(false)) return;
        plugin.parties().reconnectLocation(event.getPlayer()).ifPresent(location ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    event.getPlayer().teleport(location);
                    plugin.rooms().refresh(event.getPlayer());
                }));
    }
}
