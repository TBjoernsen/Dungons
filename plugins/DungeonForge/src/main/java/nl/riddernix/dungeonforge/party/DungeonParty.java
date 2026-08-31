package nl.riddernix.dungeonforge.party;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** A transient party with insertion order used for leadership transfer. */
public final class DungeonParty {

    private final UUID id;
    private UUID leader;
    private final LinkedHashSet<UUID> members = new LinkedHashSet<>();

    DungeonParty(UUID leader) {
        this.id = UUID.randomUUID();
        this.leader = leader;
        members.add(leader);
    }

    public UUID id() {
        return id;
    }

    public UUID leader() {
        return leader;
    }

    public boolean isLeader(UUID playerId) {
        return leader.equals(playerId);
    }

    public Set<UUID> members() {
        return Set.copyOf(members);
    }

    public int size() {
        return members.size();
    }

    void add(UUID playerId) {
        members.add(playerId);
    }

    /** @return the new leader, or {@code null} when the party became empty */
    UUID remove(UUID playerId) {
        members.remove(playerId);
        if (members.isEmpty()) {
            return null;
        }
        if (leader.equals(playerId)) {
            leader = members.iterator().next();
        }
        return leader;
    }
}
