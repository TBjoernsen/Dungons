package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * A player is about to unlock a skill node. **Cancellable.**
 *
 * <p>Fires before anything changes: no points are deducted and the node is
 * not yet unlocked. Cancelling simply makes the attempt not happen - the
 * player keeps their points, the panel keeps rendering the node as available,
 * and they hear the deny sound. Use it to veto unlocks whose requirements
 * live on your side (a level gate, a quest, a cooldown); send the player a
 * message yourself when you do, because DungeonForge only knows that
 * "another plugin refused it".</p>
 *
 * <p><strong>Do not refresh your own state from this event.</strong> It fires
 * before the write and it can still be cancelled, so a listener that re-reads
 * {@link DungeonForgeApi#getUnlockedSkillNodes} here gets an answer without
 * the node in it, and sometimes refreshes for an unlock that never happens.
 * {@link DungeonSkillNodesGainedEvent} is the post-commit event for that.</p>
 *
 * <p>This fires for {@link DungeonForgeApi#grantSkillNode} too, with a cost of
 * 0 - a grant is vetoable like any other acquisition. What a grant does
 * <em>not</em> produce is a {@code DungeonSkillPointsChangeEvent}, since no
 * points move, so before the gained event there was no post-commit signal for
 * one at all.</p>
 *
 * <p>Node ids are the keys authored in skills.yml: stable strings safe to
 * hardcode against, unchanged by tree redesigns that merely move nodes
 * around.</p>
 */
public final class DungeonSkillNodeUnlockEvent extends DungeonSkillEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String classId;
    private final String nodeId;
    private final int newLevel;
    private final int cost;
    private final int pointsBefore;
    private boolean cancelled;

    public DungeonSkillNodeUnlockEvent(Player player, String classId, String nodeId, int newLevel,
                                       int cost, int pointsBefore) {
        super(player);
        this.classId = classId;
        this.nodeId = nodeId;
        this.newLevel = newLevel;
        this.cost = cost;
        this.pointsBefore = pointsBefore;
    }

    /** The class whose tree the node belongs to. */
    public String getClassId() {
        return classId;
    }

    /** The stable node id from skills.yml. */
    public String getNodeId() {
        return nodeId;
    }

    /** The level the node reaches when this goes through. */
    public int getNewLevel() {
        return newLevel;
    }

    /** Points this unlock costs. */
    public int getCost() {
        return cost;
    }

    /** The player's available points before any deduction. */
    public int getPointsBefore() {
        return pointsBefore;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
