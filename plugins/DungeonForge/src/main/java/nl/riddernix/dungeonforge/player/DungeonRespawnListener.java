package nl.riddernix.dungeonforge.player;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Selects a safe respawn point explicitly instead of relying on void-world defaults. */
public final class DungeonRespawnListener implements Listener {
    private final DungeonForgePlugin plugin;
    public DungeonRespawnListener(DungeonForgePlugin plugin) { this.plugin = plugin; }

    /**
     * Republishes a death inside a dungeon through the API, with the dungeon
     * and room already worked out. Runs at MONITOR so DungeonForge's own
     * handling is settled, while the underlying event is still there for a
     * listener to change drops on.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        plugin.rooms().dungeon(player.getWorld()).ifPresent(dungeon ->
                plugin.events().firePlayerDeath(plugin.snapshots().of(dungeon), player, event,
                        plugin.rooms().room(player).map(plugin.snapshots()::of).orElse(null)));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (plugin.parties().takeCompletionReturn(player.getUniqueId()).map(location -> {
            event.setRespawnLocation(location);
            return true;
        }).orElse(false)) return;
        String mode = plugin.getConfig().getString("death.respawn-mode", "dungeon-entrance");
        World deathWorld = player.getWorld();
        if ("dungeon-entrance".equalsIgnoreCase(mode)
                && plugin.worlds().isDungeonWorld(deathWorld)
                && Bukkit.getWorld(deathWorld.getName()) == deathWorld) {
            event.setRespawnLocation(plugin.rooms().dungeon(deathWorld)
                    .flatMap(nl.riddernix.dungeonforge.room.DungeonInstance::playerSpawnLocation)
                    .orElseGet(() -> safeSpawn(deathWorld)));
            return;
        }
        event.setRespawnLocation(Bukkit.getWorlds().getFirst().getSpawnLocation());
    }

    private static Location safeSpawn(World world) {
        return world.getSpawnLocation().clone().add(0.5, 0.0, 0.5);
    }
}
