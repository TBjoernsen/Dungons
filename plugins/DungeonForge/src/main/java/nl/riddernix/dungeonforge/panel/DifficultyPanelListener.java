package nl.riddernix.dungeonforge.panel;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * The panel's connection to the world: chunks bringing it back, clicks
 * driving it, and players leaving cleaning their personal row up.
 */
public final class DifficultyPanelListener implements Listener {

    private final DungeonForgePlugin plugin;

    public DifficultyPanelListener(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Panel entities are never saved with the chunk, so a chunk that unloads
     * takes them with it and a chunk that loads starts empty. This is the
     * half that puts them back.
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.panels().handleChunkLoad(event.getChunk());
    }

    /** Right click. The event fires once per hand; only the main hand counts. */
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !plugin.panels().isPanelEntity(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        plugin.panels().handleClick(event.getPlayer(), event.getRightClicked());
    }

    /** Left click, which fires as an attack even against invulnerable entities. */
    @EventHandler
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (!plugin.panels().isPanelEntity(event.getAttacked())) {
            return;
        }
        event.setCancelled(true);
        plugin.panels().handleClick(event.getPlayer(), event.getAttacked());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.panels().handleQuit(event.getPlayer());
    }

    /** The selection survives both of these; only the rendered row is cleaned. */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        plugin.panels().clearPersonalRows(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.panels().clearPersonalRows(event.getPlayer());
    }
}
