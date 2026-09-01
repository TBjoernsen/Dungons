package nl.riddernix.dungeonplugin.event

import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.Optional

/**
 * Base of every skill event.
 *
 * Skill events deliberately do *not* extend [DungeonEvent]: skills belong to
 * a player, not to a run. A class is confirmed at a panel in the lobby and
 * points may be granted from anywhere, so there is often no dungeon to carry.
 * Everything else about the convention is shared - fired on the main thread,
 * through the same internal bus, visible in `/dungeon api status`, and only
 * after the plugin's own state is consistent unless a doc says otherwise.
 */
abstract class DungeonSkillEvent protected constructor(
    /** The player whose skill state this event is about. */
    val player: Player
) : Event()

/**
 * A player is about to unlock a skill node. **Cancellable.**
 *
 * Fires before anything changes: no points are deducted and the node is not
 * yet unlocked. Cancelling simply makes the attempt not happen - the player
 * keeps their points, the panel keeps rendering the node as available, and
 * they hear the deny sound. Use it to veto unlocks whose requirements live on
 * your side (a level gate, a quest, a cooldown); send the player a message
 * yourself when you do, because the panel only knows that "another plugin
 * refused it".
 *
 * **Do not refresh your own state from this event.** It fires before the
 * write and it can still be cancelled, so a listener that re-reads the
 * unlocked nodes here gets an answer without the node in it, and sometimes
 * refreshes for an unlock that never happens. [DungeonSkillNodesGainedEvent]
 * is the post-commit event for that.
 *
 * This fires for a node grant too, with a cost of 0 - a grant is vetoable
 * like any other acquisition. What a grant does *not* produce is a
 * [DungeonSkillPointsChangeEvent], since no points move, so before the gained
 * event there was no post-commit signal for one at all.
 *
 * Node ids are the keys authored in skills.yml: stable strings safe to
 * hardcode against, unchanged by tree redesigns that merely move nodes
 * around.
 */
class DungeonSkillNodeUnlockEvent(
    player: Player,
    /** The class whose tree the node belongs to. */
    val classId: String,
    /** The stable node id from skills.yml. */
    val nodeId: String,
    /** The level the node reaches when this goes through. */
    val newLevel: Int,
    /** Points this unlock costs. */
    val cost: Int,
    /** The player's available points before any deduction. */
    val pointsBefore: Int
) : DungeonSkillEvent(player), Cancellable {

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancelled: Boolean) { this.cancelled = cancelled }
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * Nodes were added to a player. Notification only.
 *
 * Fires once per purchase or grant, **after** the state is stored, so every
 * query already answers with the new nodes by the time a listener runs. This
 * is the event to refresh on; [DungeonSkillNodeUnlockEvent] is a veto hook
 * that fires before anything changes and can still be cancelled, so
 * refreshing there reads state that does not have the node yet.
 *
 * [DungeonSkillNodesRevokedEvent] is the opposite number. Between the two, a
 * listener that derives effects from the tree never has to keep its own copy
 * of what a player owns: re-read the unlocked nodes when one of them arrives.
 *
 * Not cancellable: by the time this fires the points are already spent and
 * the node is already stored.
 */
class DungeonSkillNodesGainedEvent(
    player: Player,
    /** The class whose tree the nodes belong to. */
    val classId: String,
    nodes: Set<String>,
    /** Points actually deducted, so 0 for a grant and for a free node. */
    val paid: Int,
    /** Whether the player bought this or it was handed to them. */
    val source: Source
) : DungeonSkillEvent(player) {

    /**
     * Where the node came from.
     *
     * Worth distinguishing because a free node is not the same event in a
     * levelling system as a bought one, and the price alone cannot tell them
     * apart - a node may legitimately cost 0.
     *
     * Give any switch over this an `else` branch: more sources may be added.
     */
    enum class Source {
        /** Bought by the player at the skill panel, paying the node's cost. */
        PURCHASED,
        /** Handed out through a grant, free. */
        GRANTED
    }

    /**
     * Every node added. One today, but a set because the revoke side already
     * carries several and a listener that handles both should not care.
     */
    val nodes: Set<String> = nodes.toSet()

    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * Nodes were taken away from a player. Notification only.
 *
 * Fires once per revoke or reset, after the state is stored, carrying *every*
 * node that went - a revoke also takes anything that was only reachable
 * through it, so this is usually more than the one node asked for. Listen to
 * it to undo whatever those nodes granted; unlocking has
 * [DungeonSkillNodeUnlockEvent] as its opposite number.
 *
 * Not cancellable: revoking is driven by the caller that asked for it, and a
 * half-applied revoke would leave a tree whose prerequisites no longer hold.
 */
class DungeonSkillNodesRevokedEvent(
    player: Player,
    val classId: String,
    nodes: Set<String>,
    /** Points handed back, the sum of what was actually paid for these nodes. */
    val refunded: Int
) : DungeonSkillEvent(player) {

    /** Every node removed, including ones that fell with the one that was asked for. */
    val nodes: Set<String> = nodes.toSet()

    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * A player confirmed a class. Notification only.
 *
 * Fires after the change is stored, so queries already answer with the new
 * class. Unlocks are kept per class: switching away and back loses nothing.
 * Also fires for the very first confirmation, with an empty previous class.
 */
class DungeonSkillClassChangeEvent(
    player: Player,
    private val previousClassIdOrNull: String?,
    val classId: String
) : DungeonSkillEvent(player) {

    /** Empty when this is the player's first confirmed class. */
    val previousClassId: Optional<String>
        get() = Optional.ofNullable(previousClassIdOrNull)

    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * A player's available skill points changed. Notification only.
 *
 * Fires after the balance is stored. One fires per change, including the
 * deduction of an unlock (reason [Reason.SPENT]), so listening to this alone
 * keeps an external scoreboard honest.
 */
class DungeonSkillPointsChangeEvent(
    player: Player,
    val previousPoints: Int,
    /** The new available balance. */
    val points: Int,
    val reason: Reason
) : DungeonSkillEvent(player) {

    /** Why the balance moved. */
    enum class Reason {
        /** Given through the points API or the admin command. */
        GRANTED,
        /** Taken through the points API or the admin command. */
        WITHDRAWN,
        /** Paid for a node unlock. */
        SPENT,
        /** Handed back by a revoke or a tree reset. */
        REFUNDED
    }

    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}
