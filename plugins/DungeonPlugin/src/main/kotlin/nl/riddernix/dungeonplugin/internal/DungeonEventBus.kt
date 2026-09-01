package nl.riddernix.dungeonplugin.internal

import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.event.DungeonBossDeathEvent
import nl.riddernix.dungeonplugin.event.DungeonBossSummonEvent
import nl.riddernix.dungeonplugin.event.DungeonCompletedEvent
import nl.riddernix.dungeonplugin.event.DungeonDoorOpenedEvent
import nl.riddernix.dungeonplugin.event.DungeonEndEvent
import nl.riddernix.dungeonplugin.event.DungeonEndReason
import nl.riddernix.dungeonplugin.event.DungeonInfo
import nl.riddernix.dungeonplugin.event.DungeonKeyObtainedEvent
import nl.riddernix.dungeonplugin.event.DungeonMobDeathEvent
import nl.riddernix.dungeonplugin.event.DungeonMobInfo
import nl.riddernix.dungeonplugin.event.DungeonMobSpawnEvent
import nl.riddernix.dungeonplugin.event.DungeonPlayerDeathEvent
import nl.riddernix.dungeonplugin.event.DungeonPlayerEnterEvent
import nl.riddernix.dungeonplugin.event.DungeonPlayerLeaveEvent
import nl.riddernix.dungeonplugin.event.DungeonRoomClearedEvent
import nl.riddernix.dungeonplugin.event.DungeonRoomEnterEvent
import nl.riddernix.dungeonplugin.event.DungeonRoomInfo
import nl.riddernix.dungeonplugin.event.DungeonRoomType
import nl.riddernix.dungeonplugin.event.DungeonSkillClassChangeEvent
import nl.riddernix.dungeonplugin.event.DungeonSkillNodeUnlockEvent
import nl.riddernix.dungeonplugin.event.DungeonSkillNodesGainedEvent
import nl.riddernix.dungeonplugin.event.DungeonSkillNodesRevokedEvent
import nl.riddernix.dungeonplugin.event.DungeonSkillPointsChangeEvent
import nl.riddernix.dungeonplugin.event.DungeonStartEvent
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The single place any dungeon or skill event is fired from.
 *
 * This exists because the event layer rotted once already: the mob and room
 * systems were rewritten and nothing failed visibly when call sites went
 * missing. Three things guard against that happening again.
 *
 * One, every event goes through a method here, so a rewrite has to delete a
 * named call to break the layer rather than quietly dropping a `callEvent`
 * buried in a manager. Two, each method counts its own firings, so
 * `/dungeon api status` shows at a glance which events have never fired this
 * session - a dropped call site becomes a zero somebody can see. Three, the
 * event list below is the inventory: a type that is not in it is not reported
 * and not testable, which makes adding one without wiring it up an obvious
 * omission rather than a silent one.
 */
class DungeonEventBus(private val plugin: DungeonPlugin) {

    private val fired = LinkedHashMap<String, AtomicInteger>()

    /** Dungeons already ended, so completion and world deletion cannot both end one. */
    private val ended = HashSet<String>()

    init {
        for (type in EVENT_TYPES) {
            fired[type.simpleName] = AtomicInteger()
        }
    }

    // ------------------------------------------------------------------
    //  Firing
    // ------------------------------------------------------------------

    /** @return false when a listener cancelled the run */
    fun fireStart(dungeon: DungeonInfo, leader: Player): Boolean {
        val event = DungeonStartEvent(dungeon, leader)
        fire(event)
        return !event.isCancelled
    }

    fun firePlayerEnter(dungeon: DungeonInfo, player: Player) {
        fire(DungeonPlayerEnterEvent(dungeon, player))
    }

    fun firePlayerLeave(dungeon: DungeonInfo, player: Player) {
        fire(DungeonPlayerLeaveEvent(dungeon, player))
    }

    fun firePlayerDeath(dungeon: DungeonInfo, player: Player, source: PlayerDeathEvent, room: DungeonRoomInfo?) {
        fire(DungeonPlayerDeathEvent(dungeon, player, source, room))
    }

    fun fireRoomEnter(dungeon: DungeonInfo, player: Player, room: DungeonRoomInfo, firstVisit: Boolean) {
        fire(DungeonRoomEnterEvent(dungeon, player, room, firstVisit))
    }

    fun fireRoomCleared(dungeon: DungeonInfo, room: DungeonRoomInfo, playersInside: List<UUID>) {
        fire(DungeonRoomClearedEvent(dungeon, room, playersInside))
    }

    fun fireMobSpawn(dungeon: DungeonInfo?, entity: LivingEntity, mob: DungeonMobInfo) {
        fire(DungeonMobSpawnEvent(dungeon, entity, mob))
    }

    fun fireMobDeath(dungeon: DungeonInfo, entity: LivingEntity, source: EntityDeathEvent?,
                     mob: DungeonMobInfo, room: DungeonRoomInfo?) {
        fire(DungeonMobDeathEvent(dungeon, entity, source, mob, room))
        if (mob.boss) {
            fire(DungeonBossDeathEvent(dungeon, entity, source, mob.theme))
        }
    }

    fun fireBossSummon(dungeon: DungeonInfo, boss: LivingEntity, theme: String, durationTicks: Int) {
        fire(DungeonBossSummonEvent(dungeon, boss, theme, durationTicks))
    }

    fun fireKeyObtained(dungeon: DungeonInfo, guardianRoomId: String) {
        fire(DungeonKeyObtainedEvent(dungeon, guardianRoomId))
    }

    /** @return false when a listener vetoed the unlock */
    fun fireSkillNodeUnlock(player: Player, classId: String, nodeId: String, newLevel: Int,
                            cost: Int, pointsBefore: Int): Boolean {
        val event = DungeonSkillNodeUnlockEvent(player, classId, nodeId, newLevel, cost, pointsBefore)
        fire(event)
        return !event.isCancelled
    }

    fun fireSkillClassChange(player: Player, previousClassId: String?, classId: String) {
        fire(DungeonSkillClassChangeEvent(player, previousClassId, classId))
    }

    /**
     * Fired after the node is stored, so a listener that re-reads the queries
     * sees it. The cancellable [DungeonSkillNodeUnlockEvent] cannot serve that
     * purpose: it fires before the write and may still be vetoed.
     */
    fun fireSkillNodesGained(player: Player, classId: String, nodes: Set<String>,
                             paid: Int, source: DungeonSkillNodesGainedEvent.Source) {
        fire(DungeonSkillNodesGainedEvent(player, classId, nodes, paid, source))
    }

    fun fireSkillNodesRevoked(player: Player, classId: String, nodes: Set<String>, refunded: Int) {
        fire(DungeonSkillNodesRevokedEvent(player, classId, nodes, refunded))
    }

    fun fireSkillPointsChange(player: Player, previousPoints: Int, points: Int,
                              reason: DungeonSkillPointsChangeEvent.Reason) {
        fire(DungeonSkillPointsChangeEvent(player, previousPoints, points, reason))
    }

    fun fireDoorOpened(dungeon: DungeonInfo, forced: Boolean) {
        fire(DungeonDoorOpenedEvent(dungeon, forced))
    }

    /**
     * Ends a dungeon. Also fires the older [DungeonCompletedEvent] on a
     * completion, which stays from the first API generation.
     */
    fun fireEnd(dungeon: DungeonInfo, reason: DungeonEndReason) {
        // Completion ends a dungeon, and deleting its world would end it
        // again a few seconds later. Exactly one wins.
        if (!ended.add(dungeon.id)) {
            return
        }
        fire(DungeonEndEvent(dungeon, reason))
        if (reason == DungeonEndReason.COMPLETED) {
            fire(DungeonCompletedEvent(dungeon.difficulty, dungeon.partyMembers.toSet(),
                dungeon.runDuration, dungeon.mobsKilled))
        }
    }

    private fun fire(event: Event) {
        fired[event.javaClass.simpleName]?.incrementAndGet()
        Bukkit.getPluginManager().callEvent(event)
    }

    // ------------------------------------------------------------------
    //  Diagnostics
    // ------------------------------------------------------------------

    /**
     * One line per event type: how often it has fired this session and which
     * plugins are listening for it.
     *
     * Listener counts are the half that settles arguments: a count of zero
     * for an event a listener claims to handle means its handler never
     * reached Bukkit - usually because it registered against a different
     * class than the one fired here, or because it loaded too early.
     */
    fun status(): List<String> = EVENT_TYPES.map { type ->
        val count = fired[type.simpleName]?.get() ?: 0
        "${type.simpleName}: fired ${count}x, listeners ${listeners(type)}"
    }

    /** The plugins registered for one event type, as `Plugin(count)`. */
    fun listeners(type: Class<out Event>): String {
        return try {
            val handlers = type.getMethod("getHandlerList").invoke(null) as HandlerList
            val byPlugin = LinkedHashMap<String, Int>()
            for (listener in handlers.registeredListeners) {
                byPlugin.merge(listener.plugin.name, 1, Int::plus)
            }
            if (byPlugin.isEmpty()) "none"
            else byPlugin.entries.joinToString(", ") { (name, count) -> "$name($count)" }
        } catch (exception: ReflectiveOperationException) {
            "unavailable: ${exception.message}"
        } catch (exception: ClassCastException) {
            "unavailable: ${exception.message}"
        }
    }

    fun eventTypes(): List<Class<out Event>> = EVENT_TYPES

    /**
     * Fires one of every event at a real player and reports where each one
     * went. A line reading "0 listeners" for an event another plugin claims
     * to handle points at that plugin, not at this one.
     */
    fun fireAll(player: Player): List<String> {
        val dungeon = plugin.rooms.dungeon(player.world)
            ?.let { plugin.snapshots.of(it) }
            ?: plugin.snapshots.pending("api-test", 1, 0L, listOf(player.uniqueId))
        val room = plugin.rooms.room(player)?.let { plugin.snapshots.of(it) }
            ?: DungeonRoomInfo("api-test-room", DungeonRoomType.NORMAL, 0)
        val mob = DungeonMobInfo(1, dungeon.difficulty, "swarm", "crypt", false)

        val report = ArrayList<String>()
        report.add("test dungeon: ${dungeon.id} (${dungeon.state})")
        report.add(report(DungeonStartEvent(dungeon, player)))
        report.add(report(DungeonPlayerEnterEvent(dungeon, player)))
        report.add(report(DungeonRoomEnterEvent(dungeon, player, room, true)))
        report.add(report(DungeonMobSpawnEvent(dungeon, player, mob)))
        report.add(report(DungeonMobDeathEvent(dungeon, player, null, mob, room)))
        report.add(report(DungeonRoomClearedEvent(dungeon, room, listOf(player.uniqueId))))
        report.add(report(DungeonKeyObtainedEvent(dungeon, "api-test-room")))
        report.add(report(DungeonDoorOpenedEvent(dungeon, false)))
        report.add(report(DungeonSkillNodeUnlockEvent(player, "api-test-class", "api-test-node", 1, 1, 1)))
        report.add(report(DungeonSkillNodesGainedEvent(player, "api-test-class", setOf("api-test-node"),
            1, DungeonSkillNodesGainedEvent.Source.PURCHASED)))
        report.add(report(DungeonSkillNodesRevokedEvent(player, "api-test-class", setOf("api-test-node"), 1)))
        report.add(report(DungeonSkillClassChangeEvent(player, null, "api-test-class")))
        report.add(report(DungeonSkillPointsChangeEvent(player, 0, 1, DungeonSkillPointsChangeEvent.Reason.GRANTED)))
        report.add(report(DungeonBossSummonEvent(dungeon, player, "crypt", 60)))
        report.add(report(DungeonBossDeathEvent(dungeon, player, null, "crypt")))
        report.add(report(DungeonPlayerLeaveEvent(dungeon, player)))
        report.add(report(DungeonEndEvent(dungeon, DungeonEndReason.COMPLETED)))
        report.add(report(DungeonCompletedEvent(dungeon.difficulty, dungeon.partyMembers.toSet(),
            dungeon.runDuration, dungeon.mobsKilled)))
        report.add("player death is not simulated: it needs a real Bukkit death event")
        return report
    }

    /**
     * Fires one test event without counting it, since a test firing is not a
     * dungeon actually doing something.
     */
    private fun report(event: Event): String {
        val type = event.javaClass.simpleName
        val listeners = listeners(event.javaClass)
        try {
            Bukkit.getPluginManager().callEvent(event)
        } catch (exception: RuntimeException) {
            return "$type: a listener threw $exception"
        }
        val cancelled = if (event is Cancellable && event.isCancelled) " - CANCELLED by a listener" else ""
        return "$type: delivered to $listeners$cancelled"
    }

    /** What the query side answers for one player, right now. */
    fun queryDump(queries: DungeonQueries, player: Player): List<String> {
        val lines = ArrayList<String>()
        lines.add("isInDungeon=${queries.isInDungeon(player)}")
        lines.add("getDungeon=${queries.dungeon(player)?.toString() ?: "empty"}")
        lines.add("getDifficulty=${queries.difficulty(player)?.toString() ?: "empty"}")
        lines.add("getCurrentRoom=${queries.currentRoom(player)?.toString() ?: "empty"}")
        lines.add("getPartyMembers=${queries.partyMembers(player).size} member(s)")
        lines.add("getActiveDungeons=${queries.activeDungeons().size}")
        queries.dungeon(player)?.let { dungeon ->
            lines.add("getRooms=${queries.rooms(dungeon.id).size}")
            lines.add("progress=${Math.round(dungeon.progress() * 100.0)}% (" +
                "${dungeon.roomsCleared}/${dungeon.roomsTotal} rooms)")
        }
        lines.add("getSkillClasses=${queries.skillClasses().joinToString(",")}")
        lines.add("getActiveClass=${queries.activeClass(player) ?: "none"}")
        lines.add("getSkillPoints=${queries.skillPoints(player)} (spent ${queries.spentSkillPoints(player)})")
        val unlocked = queries.unlockedSkillNodes(player)
        lines.add("getUnlockedSkillNodes=${unlocked.size}" +
            if (unlocked.isEmpty()) "" else " " + unlocked.keys.sorted().joinToString(","))
        return lines
    }

    /** Total listeners registered across every event, by anything but this plugin. */
    fun foreignListenerCount(): Int {
        var total = 0
        for (type in EVENT_TYPES) {
            try {
                val handlers = type.getMethod("getHandlerList").invoke(null) as HandlerList
                for (listener in handlers.registeredListeners) {
                    if (listener.plugin != plugin) {
                        total++
                    }
                }
            } catch (ignored: ReflectiveOperationException) {
                // Reported in detail by status(); the count simply skips it.
            } catch (ignored: ClassCastException) {
                // Same.
            }
        }
        return total
    }

    companion object {
        /** Every event type this plugin can fire, in the order the docs list them. */
        private val EVENT_TYPES: List<Class<out Event>> = listOf(
            DungeonStartEvent::class.java,
            DungeonPlayerEnterEvent::class.java,
            DungeonRoomEnterEvent::class.java,
            DungeonMobSpawnEvent::class.java,
            DungeonMobDeathEvent::class.java,
            DungeonRoomClearedEvent::class.java,
            DungeonKeyObtainedEvent::class.java,
            DungeonDoorOpenedEvent::class.java,
            DungeonSkillNodeUnlockEvent::class.java,
            DungeonSkillNodesGainedEvent::class.java,
            DungeonSkillNodesRevokedEvent::class.java,
            DungeonSkillClassChangeEvent::class.java,
            DungeonSkillPointsChangeEvent::class.java,
            DungeonBossSummonEvent::class.java,
            DungeonBossDeathEvent::class.java,
            DungeonPlayerDeathEvent::class.java,
            DungeonPlayerLeaveEvent::class.java,
            DungeonEndEvent::class.java,
            DungeonCompletedEvent::class.java)
    }
}
