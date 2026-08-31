package nl.riddernix.dungeonforge.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** Fired once when a party defeats its dungeon boss. */
public final class DungeonCompletedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final int difficulty;
    private final Set<UUID> partyMembers;
    private final Duration duration;
    private final int mobKillCount;

    public DungeonCompletedEvent(int difficulty, Set<UUID> partyMembers, Duration duration, int mobKillCount) {
        this.difficulty = difficulty;
        this.partyMembers = Set.copyOf(partyMembers);
        this.duration = duration;
        this.mobKillCount = mobKillCount;
    }

    public int getDifficulty() { return difficulty; }
    public Set<UUID> getPartyMembers() { return partyMembers; }
    public Duration getDuration() { return duration; }
    public int getMobKillCount() { return mobKillCount; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
