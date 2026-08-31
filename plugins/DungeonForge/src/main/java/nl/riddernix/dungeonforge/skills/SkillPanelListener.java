package nl.riddernix.dungeonforge.skills;

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
 * The skill panel's connection to the world: chunks bringing it back, clicks
 * selecting nodes, and players leaving cleaning their overlays up.
 */
public final class SkillPanelListener implements Listener {

    private final DungeonForgePlugin plugin;

    public SkillPanelListener(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Panel entities are never saved with the chunk, so a loading chunk
     * starts empty and this refills it.
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.skillPanels().handleChunkLoad(event.getChunk());
    }

    /** Right click. Fires once per hand; only the main hand counts. */
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !plugin.skillPanels().isPanelEntity(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        plugin.skillPanels().handleClick(event.getPlayer(), event.getRightClicked());
    }

    /** Left click, which fires as an attack even against invulnerable entities. */
    @EventHandler
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (!plugin.skillPanels().isPanelEntity(event.getAttacked())) {
            return;
        }
        event.setCancelled(true);
        plugin.skillPanels().handleClick(event.getPlayer(), event.getAttacked());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.skillPanels().handleQuit(event.getPlayer());
    }

    /** The overlays go; selection and test state stay for the session. */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        plugin.skillPanels().clearOverlaysFor(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.skillPanels().clearOverlaysFor(event.getPlayer());
    }
}
