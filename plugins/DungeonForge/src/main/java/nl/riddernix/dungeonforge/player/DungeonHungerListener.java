package nl.riddernix.dungeonforge.player;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;

/** Prevents hunger and saturation drain in dungeon worlds without blocking eating. */
public final class DungeonHungerListener implements Listener {

    private final DungeonForgePlugin plugin;

    public DungeonHungerListener(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && freezeEnabled(player)
                && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onExhaustion(EntityExhaustionEvent event) {
        if (event.getEntity() instanceof Player player && freezeEnabled(player)) {
            event.setCancelled(true);
        }
    }

    private boolean freezeEnabled(Player player) {
        return plugin.getConfig().getBoolean("hunger.freeze-in-dungeons", true)
                && plugin.worlds().isDungeonWorld(player.getWorld());
    }
}
