package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

import java.util.Set;

/**
 * Nodes were taken away from a player. Notification only.
 *
 * <p>Fires once per revoke or reset, after the state is stored, carrying
 * <em>every</em> node that went - a revoke also takes anything that was only
 * reachable through it, so this is usually more than the one node asked for.
 * Listen to it to undo whatever those nodes granted; unlocking has
 * {@link DungeonSkillNodeUnlockEvent} as its opposite number.</p>
 *
 * <p>Not cancellable: revoking is driven by the plugin that asked for it, and
 * a half-applied revoke would leave a tree whose prerequisites no longer
 * hold.</p>
 */
public final class DungeonSkillNodesRevokedEvent extends DungeonSkillEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String classId;
    private final Set<String> nodes;
    private final int refunded;

    public DungeonSkillNodesRevokedEvent(Player player, String classId, Set<String> nodes, int refunded) {
        super(player);
        this.classId = classId;
        this.nodes = Set.copyOf(nodes);
        this.refunded = refunded;
    }

    public String getClassId() {
        return classId;
    }

    /** Every node removed, including ones that fell with the one that was asked for. */
    public Set<String> getNodes() {
        return nodes;
    }

    /** Points handed back, the sum of what was actually paid for these nodes. */
    public int getRefunded() {
        return refunded;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
