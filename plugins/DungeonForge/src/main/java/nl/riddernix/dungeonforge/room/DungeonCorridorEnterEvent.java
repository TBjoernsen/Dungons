package nl.riddernix.dungeonforge.room;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired when periodic detection finds a player entering a registered corridor. */
public final class DungeonCorridorEnterEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final DungeonInstance dungeon;
    private final DungeonCorridor corridor;
    private final DungeonRoom comingFrom;
    public DungeonCorridorEnterEvent(Player player, DungeonInstance dungeon, DungeonCorridor corridor, DungeonRoom comingFrom) {
        this.player = player; this.dungeon = dungeon; this.corridor = corridor; this.comingFrom = comingFrom;
    }
    public Player getPlayer() { return player; }
    public DungeonInstance getDungeon() { return dungeon; }
    public DungeonCorridor getCorridor() { return corridor; }
    /** Null only if the player arrived in the corridor by an unusual route such as teleportation. */
    public DungeonRoom getComingFrom() { return comingFrom; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
