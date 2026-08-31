package nl.riddernix.dungeonforge.api;

/** Why a skill write did or did not happen. */
public enum SkillWriteStatus {

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
    /** A listener cancelled {@link DungeonSkillNodeUnlockEvent}. */
    REFUSED,
    /**
     * Refused because this call came from inside a listener reacting to an
     * earlier one, too many levels deep. The guard against a plugin answering
     * its own event by calling straight back in.
     */
    REENTRANT
}
