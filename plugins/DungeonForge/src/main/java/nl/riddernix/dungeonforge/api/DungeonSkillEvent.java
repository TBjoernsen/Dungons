package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;

/**
 * Base of every skill event.
 *
 * <p>Skill events deliberately do <em>not</em> extend {@link DungeonEvent}:
 * skills belong to a player, not to a run. A class is confirmed at a panel in
 * the lobby and points may be granted from anywhere, so there is often no
 * dungeon to carry. Everything else about the convention is shared - fired on
 * the main thread, through the same internal bus, visible in
 * {@code /dungeon api status}, and only after DungeonForge's own state is
 * consistent unless a Javadoc says otherwise.</p>
 */
public abstract class DungeonSkillEvent extends Event {

    private final Player player;

    protected DungeonSkillEvent(Player player) {
        this.player = player;
    }

    /** The player whose skill state this event is about. */
    public Player getPlayer() {
        return player;
    }
}
