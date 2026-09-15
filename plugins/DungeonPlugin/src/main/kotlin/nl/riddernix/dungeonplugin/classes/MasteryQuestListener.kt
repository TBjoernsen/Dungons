package nl.riddernix.dungeonplugin.classes

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import kotlin.math.roundToInt

/**
 * Feeds Battlemage's mastery quest ladder from real damage dealt - mirrors
 * [nl.riddernix.dungeonplugin.quest.QuestObjectiveListener]'s damage hook
 * exactly. Enchanter's ladder (healing done) is fed directly from
 * [AbilityService.castMageHeal] instead of a generic event, since attributing
 * a heal-over-time tick back to whoever cast it is unreliable - the ability
 * call site already knows.
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
}
