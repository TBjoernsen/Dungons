package nl.riddernix.dungeonplugin.event

import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import java.time.Duration
import java.util.UUID

/**
 * Base class for everything the dungeon side fires.
 *
 * Every event carries the dungeon it belongs to. All of them are fired on the
 * main thread, at a point where the plugin's own state is already consistent -
 * a listener never sees a half-updated dungeon.
 *
 * Only [DungeonStartEvent] is cancellable. Everything else is a notification:
 * the thing has already happened and cancelling it could leave a dungeon stuck
 * half-built or half-cleared.
 */
abstract class DungeonEvent protected constructor(
    /** The dungeon this event belongs to, as it was when the event fired. */
    val dungeon: DungeonInfo?
) : Event()

/** Base class for the events that are about one particular player. */
abstract class DungeonPlayerEvent protected constructor(
    dungeon: DungeonInfo?,
    val player: Player
) : DungeonEvent(dungeon)

/**
 * A dungeon has been requested and is about to be generated.
 *
 * **The only cancellable dungeon event.** It fires before any world is created
 * and before the layout is built, which is the one moment where stopping costs
 * nothing: cancel it and no world, no rooms and no mobs are ever made. Use it
 * to gate runs on level, permission, cooldown or currency. Cancelling tells
 * the leader the run was refused.
 *
 * Because nothing exists yet, [dungeon] describes the dungeon that *would* be
 * built: its state is [DungeonState.GENERATING], the world name is reserved
 * but not yet created, and the room counts are zero. Everything after this
 * event carries a real dungeon.
 */
class DungeonStartEvent(
    dungeon: DungeonInfo,
    /** The player who asked for the run. */
    val leader: Player
) : DungeonEvent(dungeon), Cancellable {

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * A player has arrived inside a dungeon world. Notification only.
 *
 * Fired after the teleport has landed, so the player is already standing in
 * the dungeon when a listener sees it.
 */
class DungeonPlayerEnterEvent(dungeon: DungeonInfo, player: Player) : DungeonPlayerEvent(dungeon, player) {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * A player has left a dungeon world. Notification only.
 *
 * Covers walking out, being evacuated, disconnecting and dying out of the
 * instance, so it is a reliable place to clean up anything held per player.
 * The dungeon itself may well carry on without them.
 */
class DungeonPlayerLeaveEvent(dungeon: DungeonInfo, player: Player) : DungeonPlayerEvent(dungeon, player) {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * A player has died inside a dungeon. Notification only.
 *
 * Fired from Bukkit's own death event, which is still available here for
 * drops, experience and the death message. Change those on [source]; this
 * event exists to tell you which dungeon and room it happened in without you
 * having to work it out.
 */
class DungeonPlayerDeathEvent(
    dungeon: DungeonInfo,
    player: Player,
    /** The underlying Bukkit event, for drops, experience and the message. */
    val source: PlayerDeathEvent,
    /** The room they died in, or null if they were in a corridor. */
    val room: DungeonRoomInfo?
) : DungeonPlayerEvent(dungeon, player) {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * A player has walked into a room. Notification only.
 *
 * Fired after the plugin has registered the room as occupied, and before its
 * mobs are spawned, so a listener can prepare something for the fight that is
 * about to start.
 */
class DungeonRoomEnterEvent(
    dungeon: DungeonInfo,
    player: Player,
    val room: DungeonRoomInfo,
    /** False when anyone has been in this room before, so backtracking is easy to ignore. */
    val isFirstVisit: Boolean
) : DungeonPlayerEvent(dungeon, player) {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * Every mob in a room has been killed. Notification only.
 *
 * Fires once per room per dungeon, after the last mob's death has been
 * counted, so [dungeon] already includes this room in its cleared total.
 * Rooms that never had mobs never fire it.
 */
class DungeonRoomClearedEvent(
    dungeon: DungeonInfo,
    val room: DungeonRoomInfo,
    /** Who was standing in the dungeon when it cleared, for handing out rewards. */
    playersInside: List<UUID>
) : DungeonEvent(dungeon) {
    val playersInside: List<UUID> = playersInside.toList()
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * A dungeon mob has been spawned and set up. Notification only.
 *
 * Fired after the plugin has applied its own attributes, equipment and name,
 * and before the mob is handed to the world. Anything you change here sticks,
 * so this is the place to buff a mob, re-equip it or attach your own metadata.
 *
 * Also fires for the tagged test mobs from `/dungeon summon`, which belong to
 * no dungeon. For those [dungeon] is null - check it before using it, or
 * check [isTestMob].
 */
class DungeonMobSpawnEvent(
    dungeon: DungeonInfo?,
    val entity: LivingEntity,
    /** Tier, difficulty, category and theme of the mob being spawned. */
    val mob: DungeonMobInfo
) : DungeonEvent(dungeon) {

    /** True for `/dungeon summon` test mobs, which have no dungeon. */
    val isTestMob: Boolean get() = dungeon == null
    val difficulty: Int get() = mob.difficulty
    val tier: Int get() = mob.tier
    val isBoss: Boolean get() = mob.boss

    /** The theme this mob was rolled from, for example `crypt`. */
    val theme: String get() = mob.theme

    /** `swarm`, `pack`, `champion` or `boss`. */
    val category: String get() = mob.category

    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * A dungeon mob has died. Notification only.
 *
 * Fired after the plugin has counted the kill, so the dungeon's mob total
 * already includes it. Vanilla drops are cleared and experience set on the
 * underlying event; add drops of your own to [source] from a listener at
 * `MONITOR` priority, which runs after this plugin is finished with it.
 *
 * Bosses fire [DungeonBossDeathEvent] as well as this one.
 */
class DungeonMobDeathEvent(
    dungeon: DungeonInfo,
    val entity: LivingEntity,
    /** The underlying Bukkit event, for drops and experience. */
    val source: EntityDeathEvent?,
    /** Tier, difficulty, category and theme of the mob that died. */
    val mob: DungeonMobInfo,
    /** The room it was spawned for, or null if that room is already gone. */
    val room: DungeonRoomInfo?
) : DungeonEvent(dungeon) {
    val isBoss: Boolean get() = mob.boss
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * The boss's summoning sequence has begun. Notification only.
 *
 * Fires when the first player commits to the arena, at the start of the
 * entrance animation and before the boss's minions arrive. The boss entity
 * already exists but has its AI switched off for the duration, so this is the
 * moment to start music, a title, or a scripted effect of your own.
 *
 * Not cancellable: the sequence owns the boss's AI and invulnerability and
 * has to run to its end to hand them back, so stopping it halfway would leave
 * an inert, invulnerable boss standing in the arena.
 */
class DungeonBossSummonEvent(
    dungeon: DungeonInfo,
    val boss: LivingEntity,
    /** The theme this boss belongs to, for example `rift`. */
    val theme: String,
    /** How long the sequence runs, so effects can be timed against it. */
    val durationTicks: Int
) : DungeonEvent(dungeon) {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * The dungeon boss has been defeated. Notification only.
 *
 * Fires alongside [DungeonMobDeathEvent] for the same entity, before the
 * completion sequence starts. The dungeon is still [DungeonState.ACTIVE]
 * here; [DungeonEndEvent] follows immediately after with
 * [DungeonEndReason.COMPLETED]. Reward on the end event rather than this one
 * if you care about the run's final numbers.
 */
class DungeonBossDeathEvent(
    dungeon: DungeonInfo,
    val boss: LivingEntity,
    val source: EntityDeathEvent?,
    /** The theme this boss belonged to, for example `rift`. */
    val theme: String
) : DungeonEvent(dungeon) {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * The party obtained the key of a composed dungeon's sealed door.
 * Notification only.
 *
 * Fires once per dungeon, the moment its key guardian dies. The key is party
 * state, not an item: it cannot be dropped, lost on death, or carried out of
 * the dungeon. [DungeonDoorOpenedEvent] follows immediately.
 */
class DungeonKeyObtainedEvent(
    dungeon: DungeonInfo,
    /** The room whose guardian held the key. */
    val guardianRoomId: String
) : DungeonEvent(dungeon) {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * The sealed door of a composed dungeon opened. Notification only.
 *
 * Fires once per dungeon: normally right after [DungeonKeyObtainedEvent], or
 * on its own when the door was forced - by an admin command, or automatically
 * after the key guardian stopped existing and could not be revived.
 */
class DungeonDoorOpenedEvent(
    dungeon: DungeonInfo,
    /** True when the door was opened without the guardian's key. */
    val isForced: Boolean
) : DungeonEvent(dungeon) {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * A dungeon has ended. Notification only.
 *
 * Fired while the world still exists and before it is deleted, so the dungeon
 * can still be inspected. It fires exactly once per dungeon whatever the
 * reason, so it is the right place to release anything a listener has been
 * tracking per run.
 */
class DungeonEndEvent(dungeon: DungeonInfo, val reason: DungeonEndReason) : DungeonEvent(dungeon) {

    /** Convenience for the common "did they win" check. */
    val isCompleted: Boolean get() = reason == DungeonEndReason.COMPLETED

    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** Fired once when a party defeats its dungeon boss. Kept from the first API. */
class DungeonCompletedEvent(
    val difficulty: Int,
    partyMembers: Set<UUID>,
    val duration: Duration,
    val mobKillCount: Int
) : Event() {
    val partyMembers: Set<UUID> = partyMembers.toSet()
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}
