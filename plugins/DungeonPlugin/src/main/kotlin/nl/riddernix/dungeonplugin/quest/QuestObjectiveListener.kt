package nl.riddernix.dungeonplugin.quest

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent

/**
 * Placeholder objective tracking: the two generic counters in [QuestObjective].
 * Deliberately broad and easy to trigger so the flow can be tested end to end
 * before real objective types exist. Real trackers (dungeon clears, block
 * breaks, locations reached...) become extra listeners here later.
 */
class QuestObjectiveListener(private val plugin: DungeonPlugin) : Listener {

    /** Any mob kill credited to a player -> +1 on KILL_ANY quests. Players don't count. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onKill(event: EntityDeathEvent) {
        if (event.entity is Player) return
        val killer = event.entity.killer ?: return
        plugin.quests.addProgress(killer, QuestObjective.KILL_ANY, 1)
    }

    /** Damage a player deals to a monster, directly or by projectile -> +damage on DEAL_DAMAGE quests. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (event.entity !is LivingEntity || event.entity is Player) return
        val player = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player ?: return
            else -> return
        }
        val amount = QuestManager.damageToCount(event.finalDamage)
        if (amount > 0) plugin.quests.addProgress(player, QuestObjective.DEAL_DAMAGE, amount)
    }
}
