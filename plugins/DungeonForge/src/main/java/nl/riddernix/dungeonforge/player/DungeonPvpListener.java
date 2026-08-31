package nl.riddernix.dungeonforge.player;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Prevents player-caused damage to other players only in disposable dungeon worlds. */
public final class DungeonPvpListener implements Listener {
    private final DungeonForgePlugin plugin;
    private final Map<UUID, UUID> lingeringPotionOwners = new HashMap<>();

    public DungeonPvpListener(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Covers melee, arrows, explosions, custom damage sources, and other direct damage. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !enabled(victim)) return;
        Player attacker = playerOwner(event.getDamageSource().getCausingEntity());
        if (attacker != null && sameDungeon(attacker, victim)) event.setCancelled(true);
    }

    /** Stops harmful splash-potion effects on party members while leaving mobs affected. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        Player thrower = shooter(event.getPotion());
        if (thrower == null || !enabled(thrower) || !isHarmful(event.getPotion())) return;
        for (var target : event.getAffectedEntities()) {
            if (target instanceof Player player && sameDungeon(thrower, player)) event.setIntensity(player, 0.0);
        }
    }

    /** Records the player who created a lingering cloud so later cloud applications are filtered too. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLingeringPotionSplash(LingeringPotionSplashEvent event) {
        Player thrower = shooter(event.getEntity());
        if (thrower != null && enabled(thrower) && isHarmful(event.getEntity())) {
            lingeringPotionOwners.put(event.getAreaEffectCloud().getUniqueId(), thrower.getUniqueId());
        }
    }

    /** Stops lingering-potion effects reaching party members, without affecting nearby dungeon mobs. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAreaEffectCloud(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        UUID ownerId = lingeringPotionOwners.get(cloud.getUniqueId());
        Player thrower = ownerId == null ? null : plugin.getServer().getPlayer(ownerId);
        if (thrower == null || !enabled(thrower)) {
            if (!cloud.isValid() || cloud.isDead()) lingeringPotionOwners.remove(cloud.getUniqueId());
            return;
        }
        event.getAffectedEntities().removeIf(target -> target instanceof Player player && sameDungeon(thrower, player));
    }

    private Player shooter(Projectile projectile) {
        return playerOwner(projectile);
    }

    private Player playerOwner(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            return source instanceof Player player ? player : null;
        }
        return null;
    }

    private boolean enabled(Player player) {
        return plugin.getConfig().getBoolean("combat.disable-player-damage-in-dungeons", true)
                && plugin.worlds().isDungeonWorld(player.getWorld());
    }

    private boolean sameDungeon(Player first, Player second) {
        return first.getWorld().equals(second.getWorld()) && enabled(second);
    }

    private static boolean isHarmful(ThrownPotion potion) {
        return potion.getEffects().stream().anyMatch(effect -> switch (effect.getType().getKey().getKey()) {
            case "instant_damage", "poison", "wither" -> true;
            default -> false;
        });
    }
}
