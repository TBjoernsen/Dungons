package nl.riddernix.dungeonplugin.completion

import net.kyori.adventure.title.Title
import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.event.DungeonEndReason
import nl.riddernix.dungeonplugin.party.DungeonParty
import nl.riddernix.dungeonplugin.room.DungeonInstance
import nl.riddernix.dungeonplugin.util.Messages
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import java.time.Duration
import java.util.UUID

/** Owns the one-shot transition from a finished run - won or lost - to a cleaned-up instance. */
class DungeonCompletionManager(private val plugin: DungeonPlugin) {

    private val completions = HashMap<String, CompletionRun>()
    private val cleanupLockedMembers = LinkedHashSet<UUID>()

    /** Completes a dungeon once. Duplicate boss-death notifications are ignored. */
    fun complete(dungeon: DungeonInstance) {
        if (!dungeon.complete()) return
        endRun(dungeon, DungeonEndReason.COMPLETED)
    }

    /** Fails a dungeon once - the party's life pool ran out. */
    fun fail(dungeon: DungeonInstance) {
        if (!dungeon.fail()) return
        endRun(dungeon, DungeonEndReason.FAILED)
    }

    /** The shared tail of both paths: freeze the run, fire the end event, announce, then return and clean up. */
    private fun endRun(dungeon: DungeonInstance, reason: DungeonEndReason) {
        val party = plugin.parties.partyForWorld(dungeon.world.name)
        val partyMembers = party?.members ?: playerIds(dungeon.world)
        val occupants = if (party == null) playerIds(dungeon.world) else plugin.parties.instanceMembers(party)
        val duration = party?.let { plugin.parties.runDuration(it) } ?: Duration.ZERO
        val run = CompletionRun(dungeon, party, partyMembers.toSet(), occupants.toSet(), duration, dungeon.mobKillCount)
        completions[dungeon.id] = run
        cleanupLockedMembers.addAll(partyMembers)

        plugin.mobs.despawnDungeonMobs(dungeon)
        // The bus fires the older DungeonCompletedEvent alongside this on a
        // completion, so listeners written against the first API keep working.
        plugin.events.fireEnd(plugin.snapshots.ending(dungeon, reason == DungeonEndReason.COMPLETED), reason)
        if (reason == DungeonEndReason.COMPLETED) announce(run) else announceDefeat(run)

        val grace = maxOf(0L, plugin.config.getLong("completion.grace-period-ticks", 160L))
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { beginReturn(run) }, grace)
    }

    /** Prevents a member from starting another instance until this cleanup ends. */
    fun isCleaningUp(playerId: UUID): Boolean = playerId in cleanupLockedMembers

    private fun announce(run: CompletionRun) {
        val duration = format(run.duration)
        for (memberId in run.partyMembers) {
            val player = Bukkit.getPlayer(memberId)
            if (player == null || !player.isOnline) continue
            player.showTitle(Title.title(
                plugin.messages.bare("dungeon-completed-title", Messages.ph("difficulty", run.dungeon.difficulty), Messages.ph("duration", duration), Messages.ph("kills", run.mobKillCount)),
                plugin.messages.bare("dungeon-completed-subtitle", Messages.ph("difficulty", run.dungeon.difficulty), Messages.ph("duration", duration), Messages.ph("kills", run.mobKillCount)),
                Title.Times.times(Duration.ofMillis(maxOf(0L, plugin.config.getLong("completion.title.fade-in-millis", 400L))),
                    Duration.ofMillis(maxOf(0L, plugin.config.getLong("completion.title.stay-millis", 3000L))),
                    Duration.ofMillis(maxOf(0L, plugin.config.getLong("completion.title.fade-out-millis", 600L))))))
            player.playSound(player.location, completionSound(),
                plugin.config.getDouble("completion.sound.volume", 1.0).toFloat(),
                plugin.config.getDouble("completion.sound.pitch", 1.0).toFloat())
            plugin.messages.send(player, "dungeon-completed",
                Messages.ph("difficulty", run.dungeon.difficulty), Messages.ph("duration", duration), Messages.ph("kills", run.mobKillCount))
        }
    }

    private fun announceDefeat(run: CompletionRun) {
        for (memberId in run.partyMembers) {
            val player = Bukkit.getPlayer(memberId) ?: continue
            if (!player.isOnline) continue
            player.showTitle(Title.title(
                net.kyori.adventure.text.Component.text("§4§lDefeat"),
                net.kyori.adventure.text.Component.text("§7Out of lives - Difficulty ${run.dungeon.difficulty}"),
                Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(3000), Duration.ofMillis(600))))
            player.playSound(player.location, Sound.ENTITY_WITHER_DEATH, 0.7f, 1.4f)
            player.sendMessage("§4§lThe party is out of lives. §7You still keep the XP for the attempt.")
        }
    }

    private fun beginReturn(run: CompletionRun) {
        if (completions[run.dungeon.id] !== run) return
        for (memberId in run.occupants) {
            val destination = returnLocation(run, memberId)
            plugin.parties.queueCompletionReturn(memberId, destination)
            val player = Bukkit.getPlayer(memberId)
            if (player == null || !player.isOnline) continue
            // The direct teleport below never triggers a leave, so fire it
            // here (while the player is still inside) - otherwise the class
            // layer never restores the pre-dungeon inventory. Idempotent:
            // updateDungeonState only acts when a snapshot is still held.
            if (plugin.rooms.dungeon(player.world) != null) {
                plugin.events.firePlayerLeave(plugin.snapshots.of(run.dungeon), player)
            }
            if (player.isDead) {
                player.spigot().respawn()
            } else {
                player.teleport(destination)
                plugin.parties.takeCompletionReturn(memberId)
                plugin.rooms.refresh(player)
            }
        }
        waitForRespawns(run, 0)
    }

    private fun waitForRespawns(run: CompletionRun, attempts: Int) {
        if (completions[run.dungeon.id] !== run) return
        val deadMember = run.occupants
            .mapNotNull(Bukkit::getPlayer)
            .any { it.isOnline && it.isDead }
        val maximumAttempts = maxOf(1, plugin.config.getInt("completion.respawn-settle-attempts", 20))
        if (deadMember && attempts < maximumAttempts) {
            for (memberId in run.occupants) {
                val player = Bukkit.getPlayer(memberId)
                if (player != null && player.isOnline && player.isDead) player.spigot().respawn()
            }
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { waitForRespawns(run, attempts + 1) }, 1L)
            return
        }
        cleanUp(run)
    }

    private fun cleanUp(run: CompletionRun) {
        if (completions.remove(run.dungeon.id) !== run) return
        val world = run.dungeon.world
        for (player in world.players.toList()) {
            if (player.isDead) continue
            player.teleport(returnLocation(run, player.uniqueId))
            plugin.rooms.refresh(player)
        }
        if (run.party != null) plugin.parties.endInstance(run.party)
        plugin.worlds.deleteWorld(world.name)
        cleanupLockedMembers.removeAll(run.partyMembers)
    }

    private fun returnLocation(run: CompletionRun, playerId: UUID): Location =
        if (run.party == null) fallbackLocation()
        else plugin.parties.returnLocation(run.party, playerId) ?: fallbackLocation()

    private fun fallbackLocation(): Location = Bukkit.getWorlds().first().spawnLocation

    private fun completionSound(): Sound {
        val raw = plugin.config.getString("completion.sound.type", "UI_TOAST_CHALLENGE_COMPLETE")
        val sound = raw?.let { Registry.SOUNDS.get(NamespacedKey.minecraft(it.lowercase().replace('_', '.'))) }
        return sound ?: Sound.UI_TOAST_CHALLENGE_COMPLETE
    }

    private class CompletionRun(val dungeon: DungeonInstance, val party: DungeonParty?, val partyMembers: Set<UUID>,
                                val occupants: Set<UUID>, val duration: Duration, val mobKillCount: Int)

    companion object {
        private fun playerIds(world: World): Set<UUID> = world.players.mapTo(LinkedHashSet()) { it.uniqueId }

        private fun format(duration: Duration): String {
            val seconds = maxOf(0L, duration.toSeconds())
            return String.format("%d:%02d", seconds / 60L, seconds % 60L)
        }
    }
}
