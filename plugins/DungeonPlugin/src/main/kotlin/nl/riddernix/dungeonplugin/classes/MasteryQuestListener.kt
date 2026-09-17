package nl.riddernix.dungeonplugin.classes

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import kotlin.math.roundToInt

/**
 * Feeds mastery quest ladders from real gameplay events that no single
 * ability-cast call site can see on its own. Damage mirrors
 * [nl.riddernix.dungeonplugin.quest.QuestObjectiveListener]'s damage hook
 * exactly. Enchanter's ladder (healing done) is fed directly from
 * [AbilityService.castMageHeal] instead of a generic event, since attributing
 * a heal-over-time tick back to whoever cast it is unreliable - the ability
 * call site already knows. Skyfall/Deadeye/Tempest/mark/Scope/Wind Dash
 * progress is likewise reported directly from PassiveService/AbilityService,
 * at the exact moment each of those already knows what happened - only kill
 * attribution for a plain Focus Shot genuinely needs a death-event listener.
 */
class MasteryQuestListener(private val plugin: DungeonPlugin) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (event.entity !is LivingEntity || event.entity is Player) return
        val player = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player ?: return
            else -> return
        }
        val amount = event.finalDamage.roundToInt().coerceAtLeast(0)
        if (amount > 0) plugin.classes.addMasteryProgress(player, MasteryObjective.DEAL_DAMAGE, amount)
    }

    /** Sharpshooter's "kill with Focus Shot" - Skyfall's own kills are counted directly in PassiveService.detonateSkyfall instead. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) {
        val cause = event.entity.lastDamageCause as? EntityDamageByEntityEvent ?: return
        val projectile = cause.damager as? Projectile ?: return
        val shooter = projectile.shooter as? Player ?: return
        if (plugin.classItems.isFocusShot(projectile)) {
            plugin.classes.addMasteryProgress(shooter, MasteryObjective.FOCUS_SHOT_KILLS, 1)
        }
    }
}
