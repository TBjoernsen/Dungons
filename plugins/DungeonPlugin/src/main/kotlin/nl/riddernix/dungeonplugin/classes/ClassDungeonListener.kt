package nl.riddernix.dungeonplugin.classes

import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.event.DungeonEndEvent
import nl.riddernix.dungeonplugin.event.DungeonPlayerEnterEvent
import nl.riddernix.dungeonplugin.event.DungeonPlayerLeaveEvent
import nl.riddernix.dungeonplugin.event.DungeonSkillNodesGainedEvent
import nl.riddernix.dungeonplugin.event.DungeonSkillNodesRevokedEvent
import nl.riddernix.dungeonplugin.event.DungeonStartEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * The class layer's ear on the dungeon lifecycle.
 *
 * This replaces the old cross-plugin reflection bridge: the events are our
 * own now, so the level gate, the kit swap and the completion rewards hang
 * off them directly. The per-node tier veto the bridge used to apply is gone
 * for good - every node's difficulty requirement lives in skills.yml as
 * `requires-difficulty` since the merge, so the panel draws a gated node as
 * locked instead of letting a click be refused after the fact.
 */
class ClassDungeonListener(private val plugin: DungeonPlugin) : Listener {

    /** The level gate on starting a run: the leader's level must carry the difficulty. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onDungeonStart(event: DungeonStartEvent) {
        if (!plugin.classes.enabled) return
        val difficulty = event.dungeon?.difficulty ?: return
        val leader = event.leader
        if (plugin.classes.canEnterDungeonDifficulty(leader, difficulty)) return
        event.isCancelled = true
        val allowed = plugin.classes.maximumDungeonDifficultyForLevel(
            plugin.classes.data(leader.uniqueId).level)
        leader.sendMessage("§cDifficulty $difficulty is locked. " +
            "§7Reach Level ${(difficulty - 1) * 10 + 1} (currently allowed: Difficulty $allowed).")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDungeonPlayerEnter(event: DungeonPlayerEnterEvent) {
        if (!plugin.classes.enabled) return
        plugin.classKits.updateDungeonState(event.player, true)
        plugin.classes.syncDifficultyToLevel(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDungeonPlayerLeave(event: DungeonPlayerLeaveEvent) {
        if (!plugin.classes.enabled) return
        plugin.classKits.updateDungeonState(event.player, false)
    }

    /** Fires once per run; only COMPLETED runs pay out. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onDungeonEnd(event: DungeonEndEvent) {
        if (!plugin.classes.enabled) return
        if (!event.isCompleted) return
        val dungeon = event.dungeon ?: return
        val difficulty = dungeon.difficulty.takeIf { it in 1..9 } ?: return
        val mobKills = dungeon.mobsKilled
        for (memberId in dungeon.partyMembers) {
            val player = Bukkit.getPlayer(memberId)?.takeIf { it.isOnline } ?: continue
            plugin.classes.syncDifficultyToLevel(player)
            plugin.classes.awardDungeonCompletion(player, difficulty, mobKills)
        }
    }

    /** Post-commit node changes: reapply attribute effects and celebrate purchases. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onNodesGained(event: DungeonSkillNodesGainedEvent) {
        if (!plugin.classes.enabled) return
        if (event.source == DungeonSkillNodesGainedEvent.Source.PURCHASED) {
            plugin.classFeedback.nodePurchased(event.player)
        }
        plugin.refreshClassPlayer(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onNodesRevoked(event: DungeonSkillNodesRevokedEvent) {
        if (!plugin.classes.enabled) return
        plugin.refreshClassPlayer(event.player)
    }
}
