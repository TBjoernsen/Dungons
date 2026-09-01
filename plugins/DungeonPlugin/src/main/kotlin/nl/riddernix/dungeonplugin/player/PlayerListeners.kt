package nl.riddernix.dungeonplugin.player

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.ThrownPotion
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExhaustionEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.LingeringPotionSplashEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.event.player.PlayerRespawnEvent
import java.util.UUID

/** Prevents hunger and saturation drain in dungeon worlds without blocking eating. */
class DungeonHungerListener(private val plugin: DungeonPlugin) : Listener {

    @EventHandler
    fun onFoodLevelChange(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (freezeEnabled(player) && event.foodLevel < player.foodLevel) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onExhaustion(event: EntityExhaustionEvent) {
        val player = event.entity as? Player ?: return
        if (freezeEnabled(player)) {
            event.isCancelled = true
        }
    }

    private fun freezeEnabled(player: Player): Boolean =
        plugin.config.getBoolean("hunger.freeze-in-dungeons", true) &&
            plugin.worlds.isDungeonWorld(player.world)
}

/** Prevents player-caused damage to other players only in disposable dungeon worlds. */
class DungeonPvpListener(private val plugin: DungeonPlugin) : Listener {

    private val lingeringPotionOwners = HashMap<UUID, UUID>()

    /** Covers melee, arrows, explosions, custom damage sources, and other direct damage. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        if (!enabled(victim)) return
        val attacker = playerOwner(event.damageSource.causingEntity)
        if (attacker != null && sameDungeon(attacker, victim)) event.isCancelled = true
    }

    /** Stops harmful splash-potion effects on party members while leaving mobs affected. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPotionSplash(event: PotionSplashEvent) {
        val thrower = shooter(event.potion) ?: return
        if (!enabled(thrower) || !isHarmful(event.potion)) return
        for (target in event.affectedEntities) {
            if (target is Player && sameDungeon(thrower, target)) event.setIntensity(target, 0.0)
        }
    }

    /** Records the player who created a lingering cloud so later cloud applications are filtered too. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onLingeringPotionSplash(event: LingeringPotionSplashEvent) {
        val thrower = shooter(event.entity)
        if (thrower != null && enabled(thrower) && isHarmful(event.entity)) {
            lingeringPotionOwners[event.areaEffectCloud.uniqueId] = thrower.uniqueId
        }
    }

    /** Stops lingering-potion effects reaching party members, without affecting nearby dungeon mobs. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onAreaEffectCloud(event: AreaEffectCloudApplyEvent) {
        val cloud: AreaEffectCloud = event.entity
        val ownerId = lingeringPotionOwners[cloud.uniqueId]
        val thrower = ownerId?.let { plugin.server.getPlayer(it) }
        if (thrower == null || !enabled(thrower)) {
            if (!cloud.isValid || cloud.isDead) lingeringPotionOwners.remove(cloud.uniqueId)
            return
        }
        event.affectedEntities.removeIf { it is Player && sameDungeon(thrower, it) }
    }

    private fun shooter(projectile: Projectile): Player? = playerOwner(projectile)

    private fun playerOwner(entity: Entity?): Player? {
        if (entity is Player) return entity
        if (entity is Projectile) {
            return entity.shooter as? Player
        }
        return null
    }

    private fun enabled(player: Player): Boolean =
        plugin.config.getBoolean("combat.disable-player-damage-in-dungeons", true) &&
            plugin.worlds.isDungeonWorld(player.world)

    private fun sameDungeon(first: Player, second: Player): Boolean =
        first.world == second.world && enabled(second)

    companion object {
        private fun isHarmful(potion: ThrownPotion): Boolean = potion.effects.any { effect ->
            when (effect.type.key.key) {
                "instant_damage", "poison", "wither" -> true
                else -> false
            }
        }
    }
}

/** Selects a safe respawn point explicitly instead of relying on void-world defaults. */
class DungeonRespawnListener(private val plugin: DungeonPlugin) : Listener {

    /**
     * Republishes a death inside a dungeon through the event bus, with the
     * dungeon and room already worked out. Runs at MONITOR so the plugin's
     * own handling is settled, while the underlying event is still there for
     * a listener to change drops on.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        plugin.rooms.dungeon(player.world)?.let { dungeon ->
            plugin.events.firePlayerDeath(plugin.snapshots.of(dungeon), player, event,
                plugin.rooms.room(player)?.let { plugin.snapshots.of(it) })
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val completionReturn = plugin.parties.takeCompletionReturn(player.uniqueId)
        if (completionReturn != null) {
            event.respawnLocation = completionReturn
            return
        }
        val mode = plugin.config.getString("death.respawn-mode", "dungeon-entrance")
        val deathWorld = player.world
        if ("dungeon-entrance".equals(mode, ignoreCase = true) &&
            plugin.worlds.isDungeonWorld(deathWorld) &&
            Bukkit.getWorld(deathWorld.name) == deathWorld) {
            event.respawnLocation = plugin.rooms.dungeon(deathWorld)?.playerSpawnLocation()
                ?: safeSpawn(deathWorld)
            return
        }
        event.respawnLocation = Bukkit.getWorlds().first().spawnLocation
    }

    companion object {
        private fun safeSpawn(world: World): Location =
            world.spawnLocation.clone().add(0.5, 0.0, 0.5)
    }
}
