package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * A dungeon has been requested and is about to be generated.
 *
 * <p><b>The only cancellable event in this API.</b> It fires before any world
 * is created and before the layout is built, which is the one moment where
 * stopping costs nothing: cancel it and no world, no rooms and no mobs are
 * ever made. Use it to gate runs on level, permission, cooldown or currency.
 * Cancelling tells the leader the run was refused.</p>
 *
 * <p>Because nothing exists yet, {@link #getDungeon()} describes the dungeon
 * that <em>would</em> be built: its state is {@link DungeonState#GENERATING},
 * the world name is reserved but not yet created, and the room counts are
 * zero. Everything after this event carries a real dungeon.</p>
 */
public final class DungeonStartEvent extends DungeonEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player leader;
    private boolean cancelled;

    public DungeonStartEvent(DungeonInfo dungeon, Player leader) {
        super(dungeon);
        this.leader = leader;
    }

    /** The player who asked for the run. */
    public Player getLeader() {
        return leader;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
