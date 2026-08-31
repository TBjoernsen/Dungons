package nl.riddernix.dungeonforge.menu;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/** Prevents item movement in the party menu and dispatches its configured actions. */
public final class PartyMenuListener implements Listener {
    private final DungeonForgePlugin plugin;
    public PartyMenuListener(DungeonForgePlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!plugin.partyMenu().isMenu(top)) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize() || !(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.partyMenu().click(player, top, slot));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (plugin.partyMenu().isMenu(event.getView().getTopInventory())) event.setCancelled(true);
    }
}
