package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

import java.util.Set;

/**
 * Nodes were added to a player. Notification only.
 *
 * <p>Fires once per purchase or grant, <strong>after</strong> the state is
 * stored, so every query on {@link DungeonForgeApi} already answers with the
 * new nodes by the time a listener runs. This is the event to refresh on;
 * {@link DungeonSkillNodeUnlockEvent} is a veto hook that fires before
 * anything changes and can still be cancelled, so refreshing there reads
 * state that does not have the node yet.</p>
 *
 * <p>{@link DungeonSkillNodesRevokedEvent} is the opposite number. Between the
 * two, a plugin that derives effects from the tree never has to keep its own
 * copy of what a player owns: re-read {@link DungeonForgeApi#getUnlockedSkillNodes}
 * when one of them arrives.</p>
 *
 * <p>Not cancellable: by the time this fires the points are already spent and
 * the node is already stored.</p>
 */
public final class DungeonSkillNodesGainedEvent extends DungeonSkillEvent {

    /**
     * Where the node came from.
     *
     * <p>Worth distinguishing because a free node is not the same event in a
     * levelling system as a bought one, and the price alone cannot tell them
     * apart - a node may legitimately cost 0, as the tree's root does.</p>
     *
     * <p>Give any switch over this a {@code default} branch: more sources may
     * be added.</p>
     */
    public enum Source {
        /** Bought by the player at the skill panel, paying the node's cost. */
        PURCHASED,
        /** Handed out through {@link DungeonForgeApi#grantSkillNode}, free. */
        GRANTED
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final String classId;
    private final Set<String> nodes;
    private final int paid;
    private final Source source;

    public DungeonSkillNodesGainedEvent(Player player, String classId, Set<String> nodes,
                                        int paid, Source source) {
        super(player);
        this.classId = classId;
        this.nodes = Set.copyOf(nodes);
        this.paid = paid;
        this.source = source;
    }

    /** The class whose tree the nodes belong to. */
    public String getClassId() {
        return classId;
    }

    /**
     * Every node added. One today, but a set because the revoke side already
     * carries several and a listener that handles both should not care.
     */
    public Set<String> getNodes() {
        return nodes;
    }

    /** Points actually deducted, so 0 for a grant and for a free node. */
    public int getPaid() {
        return paid;
    }

    /** Whether the player bought this or it was handed to them. */
    public Source getSource() {
        return source;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
