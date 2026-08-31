package nl.riddernix.dungeonforge.npc;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/** Points players at the difficulty panel and blocks all damage to the Lord. */
public final class DungeonLordListener implements Listener {

    private final DungeonForgePlugin plugin;

    public DungeonLordListener(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!plugin.dungeonLords().isDungeonLord(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        // The chest menu is retired; the Lord now directs players to the
        // fixed difficulty panel standing in the world.
        plugin.panels().sendLocator(event.getPlayer());
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (plugin.dungeonLords().isDungeonLord(entity)) {
            event.setCancelled(true);
        }
    }
}
