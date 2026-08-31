package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * A player's available skill points changed. Notification only.
 *
 * <p>Fires after the balance is stored. One fires per change, including the
 * deduction of an unlock (reason {@link Reason#SPENT}), so listening to this
 * alone keeps an external scoreboard honest.</p>
 */
public final class DungeonSkillPointsChangeEvent extends DungeonSkillEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Why the balance moved. */
    public enum Reason {
        /** Given through the API or the admin command. */
        GRANTED,
        /** Taken through the API or the admin command. */
        WITHDRAWN,
        /** Paid for a node unlock. */
        SPENT,
        /** Handed back by a revoke or a tree reset. Added in API 4. */
        REFUNDED
    }

    private final int previousPoints;
    private final int points;
    private final Reason reason;

    public DungeonSkillPointsChangeEvent(Player player, int previousPoints, int points, Reason reason) {
        super(player);
        this.previousPoints = previousPoints;
        this.points = points;
        this.reason = reason;
    }

    public int getPreviousPoints() {
        return previousPoints;
    }

    /** The new available balance. */
    public int getPoints() {
        return points;
    }

    public Reason getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
