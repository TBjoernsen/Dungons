package nl.riddernix.dungeonplugin.event

import java.time.Duration
import java.util.UUID
import kotlin.math.min

/** Public room categories carried on room events. */
enum class DungeonRoomType {
    SPAWN, NORMAL, BRANCH, BOSS
}

/** Why a dungeon stopped existing. */
enum class DungeonEndReason {
    /** The boss was defeated. */
    COMPLETED,
    /** Everyone left, or the party leader ended the run. */
    ABANDONED,
    /** Removed administratively, on shutdown, or as leftover from a crash. */
    CLEANED_UP
}

/** Where a dungeon is in its life. */
enum class DungeonState {
    /** Requested and being built; no player is inside yet. */
    GENERATING,
    /** Built and playable. */
    ACTIVE,
    /** The boss is down and the return sequence is running. */
    COMPLETED,
    /** Being torn down; its world is about to disappear. */
    ENDING
}

/** Immutable description of a room currently occupied by a player. */
data class DungeonRoomInfo(val id: String, val type: DungeonRoomType, val depth: Int)

/**
 * Immutable metadata attached to a dungeon mob.
 *
 * @property tier       power band, rising with difficulty
 * @property difficulty the dungeon difficulty this mob was rolled for, 1 to 9
 * @property category   `swarm`, `pack`, `champion` or `boss`
 * @property theme      the theme it came from, for example `crypt`; may be empty
 * @property boss       whether this is the dungeon's boss
 */
class DungeonMobInfo(val tier: Int, val difficulty: Int, category: String?, theme: String?, val boss: Boolean) {
    val category: String = category ?: ""
    val theme: String = theme ?: ""
}

/**
 * An immutable snapshot of one dungeon.
 *
 * Every event carries one of these, so a listener never has to track dungeon
 * state itself. It is a snapshot, not a live view: read what you need during
 * the event rather than holding on to it.
 *
 * @property id                stable for the life of the dungeon
 * @property worldName         the world this dungeon owns; it is deleted when the run ends
 * @property difficulty        1 to 9
 * @property seed              the layout seed, so a run can be reproduced
 * @property state             where the dungeon is in its life
 * @property roomsTotal        rooms in the layout, corridors excluded
 * @property roomsCleared      rooms whose mobs have all been killed
 * @property deepestRoomDepth  how far from the entrance the party has reached
 * @property mobsKilled        dungeon mobs killed so far
 * @property partyMembers      everyone in the party, whether or not they are inside
 * @property runDuration       time since the party entered; zero before it starts
 */
class DungeonInfo(
    val id: String,
    val worldName: String,
    val difficulty: Int,
    val seed: Long,
    val state: DungeonState,
    val roomsTotal: Int,
    val roomsCleared: Int,
    val deepestRoomDepth: Int,
    val mobsKilled: Int,
    partyMembers: List<UUID>,
    val runDuration: Duration
) {
    val partyMembers: List<UUID> = partyMembers.toList()

    /** Rooms cleared as a fraction of the layout, 0.0 to 1.0. */
    fun progress(): Double = if (roomsTotal <= 0) 0.0 else min(1.0, roomsCleared.toDouble() / roomsTotal)

    override fun toString(): String =
        "DungeonInfo[id=$id, worldName=$worldName, difficulty=$difficulty, seed=$seed, state=$state, " +
            "roomsTotal=$roomsTotal, roomsCleared=$roomsCleared, deepestRoomDepth=$deepestRoomDepth, " +
            "mobsKilled=$mobsKilled, partyMembers=$partyMembers, runDuration=$runDuration]"
}

/** Why a skill write did or did not happen. */
enum class SkillWriteStatus {
    /** The change was applied and stored. */
    SUCCESS,
    /** The state already matched the request, so nothing was written. Not an error. */
    UNCHANGED,
    /** No node with that id exists in the class being written to. */
    NO_SUCH_NODE,
    /** No class with that id is defined in skills.yml. */
    NO_SUCH_CLASS,
    /** The player has never confirmed a class, so there is no tree to write to. */
    NO_ACTIVE_CLASS,
    /** The node's prerequisites are not met; granting it would leave the tree inconsistent. */
    LOCKED,
    /** A listener cancelled [DungeonSkillNodeUnlockEvent]. */
    REFUSED,
    /**
     * Refused because this call came from inside a listener reacting to an
     * earlier one, too many levels deep. The guard against a plugin answering
     * its own event by calling straight back in.
     */
    REENTRANT
}

/**
 * What one skill write did.
 *
 * Returned by every mutating skill call so a caller can branch on the outcome
 * instead of guessing. Nothing throws: an unknown node or a player without a
 * class is a [SkillWriteStatus], not an exception.
 *
 * @property status       why it did or did not happen
 * @property classId      the class written to, empty when the call never got that far
 * @property nodes        every node whose level changed, including cascaded revokes
 * @property pointsBefore the player's available points before this call
 * @property points       their available points after it
 */
class SkillWriteResult(
    val status: SkillWriteStatus,
    val classId: String,
    nodes: Set<String>,
    val pointsBefore: Int,
    val points: Int
) {
    val nodes: Set<String> = nodes.toSet()

    /** True for both [SkillWriteStatus.SUCCESS] and [SkillWriteStatus.UNCHANGED]. */
    val isSuccess: Boolean
        get() = status == SkillWriteStatus.SUCCESS || status == SkillWriteStatus.UNCHANGED

    /** Points handed back by a revoke or reset; negative when a call spent them. */
    fun pointsChanged(): Int = points - pointsBefore

    /** Node ids in a stable order, handy for logging what a cascade took. */
    fun sortedNodes(): List<String> = nodes.sorted()

    companion object {
        fun failed(status: SkillWriteStatus, classId: String?, points: Int): SkillWriteResult =
            SkillWriteResult(status, classId ?: "", emptySet(), points, points)
    }
}
