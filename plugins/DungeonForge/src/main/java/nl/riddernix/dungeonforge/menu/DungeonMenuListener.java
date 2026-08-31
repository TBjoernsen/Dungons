package nl.riddernix.dungeonforge.menu;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Prevents menu items from being moved and forwards its button clicks. */
public final class DungeonMenuListener implements Listener {

    private final DungeonForgePlugin plugin;

    public DungeonMenuListener(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.menu().isMenu(event.getView().getTopInventory())) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            int slot = event.getRawSlot();
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.menu().click(player, slot));
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (plugin.menu().isMenu(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }
}
