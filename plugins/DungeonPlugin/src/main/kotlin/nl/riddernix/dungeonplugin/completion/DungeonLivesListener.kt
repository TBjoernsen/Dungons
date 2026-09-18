package nl.riddernix.dungeonplugin.completion

import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.event.DungeonPlayerDeathEvent
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * The party's shared life pool. Every death inside the dungeon spends one
 * life; when the pool hits zero the run is failed (returned to spawn with a
 * reduced XP payout - see [nl.riddernix.dungeonplugin.classes.ClassProgressionService.awardDungeonLoss]).
 *
 * The pool size is set once at registration from `classes.yml` `lives.*`; a
 * run with `lives.enabled: false` skips all of this and keeps the old
 * infinite-respawn behaviour.
 */
class DungeonLivesListener(private val plugin: DungeonPlugin) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDungeonDeath(event: DungeonPlayerDeathEvent) {
        val dungeon = plugin.rooms.dungeon(event.player.world) ?: return
        if (!dungeon.livesEnabled || dungeon.isCompleted || dungeon.isFailed) return

        val remaining = dungeon.consumeLife()
        val party = plugin.parties.partyForWorld(dungeon.world.name)
        val recipients = party?.members ?: dungeon.world.players.map { it.uniqueId }
        for (id in recipients) {
            val member = Bukkit.getPlayer(id) ?: continue
            if (!member.isOnline) continue
            if (remaining > 0) {
                member.sendActionBar(net.kyori.adventure.text.Component.text(
                    "§c✖ ${event.player.name} fell — §e$remaining §clife" +
                        (if (remaining == 1) "" else "s") + " left"))
                member.playSound(member.location, Sound.ENTITY_ITEM_BREAK, 0.6f, 0.8f)
            }
        }

        if (remaining <= 0) {
            // Next tick, so the death event finishes and the fail's end event /
            // XP payout run on players that have settled into the respawn state.
            Bukkit.getScheduler().runTask(plugin, Runnable {
                plugin.rooms.dungeon(dungeon.world)?.let { plugin.completions.fail(it) }
            })
        }
    }
}
