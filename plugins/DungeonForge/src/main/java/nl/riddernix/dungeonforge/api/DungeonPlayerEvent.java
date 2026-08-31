package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.Player;

/** Base class for the events that are about one particular player. */
public abstract class DungeonPlayerEvent extends DungeonEvent {

    private final Player player;

    protected DungeonPlayerEvent(DungeonInfo dungeon, Player player) {
        super(dungeon);
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }
}
