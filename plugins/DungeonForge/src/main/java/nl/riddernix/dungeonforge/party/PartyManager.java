package nl.riddernix.dungeonforge.party;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Owns parties, pending invitations, and active party-instance bookkeeping. */
public final class PartyManager {

    private final Map<UUID, DungeonParty> parties = new HashMap<>();
    private final Map<UUID, UUID> partyByMember = new HashMap<>();
    private final Map<UUID, Invitation> invitations = new HashMap<>();
    private final Map<UUID, PartyInstance> instances = new HashMap<>();
    private final Map<UUID, Location> completionReturns = new HashMap<>();

    private Duration inviteLifetime = Duration.ofSeconds(60);
    private Duration offlineLifetime = Duration.ofMinutes(10);
    private int maxSize = 4;

    public void reload(FileConfiguration config) {
        inviteLifetime = Duration.ofSeconds(Math.max(5, config.getLong("party.invite-expiry-seconds", 60L)));
        offlineLifetime = Duration.ofSeconds(Math.max(60, config.getLong("party.offline-instance-timeout-seconds", 600L)));
        maxSize = Math.max(1, config.getInt("party.max-size", 4));
    }

    public Optional<DungeonParty> partyOf(UUID playerId) {
        UUID partyId = partyByMember.get(playerId);
        return Optional.ofNullable(partyId == null ? null : parties.get(partyId));
    }

    public DungeonParty partyForLeader(UUID leaderId) {
        return partyOf(leaderId).orElseGet(() -> {
            DungeonParty party = new DungeonParty(leaderId);
            parties.put(party.id(), party);
            partyByMember.put(leaderId, party.id());
            return party;
        });
    }

    public InviteResult invite(Player leader, Player invitee) {
        if (leader.getUniqueId().equals(invitee.getUniqueId())) {
            return InviteResult.SELF;
        }
        DungeonParty party = partyForLeader(leader.getUniqueId());
        if (!party.isLeader(leader.getUniqueId())) {
            return InviteResult.NOT_LEADER;
        }
        if (partyByMember.containsKey(invitee.getUniqueId())) {
            return InviteResult.ALREADY_IN_PARTY;
        }
        if (party.size() >= maxSize) {
            return InviteResult.FULL;
        }
        invitations.put(invitee.getUniqueId(), new Invitation(party.id(), System.nanoTime() + inviteLifetime.toNanos()));
        return InviteResult.OK;
    }

    public AcceptResult accept(UUID playerId) {
        Invitation invitation = invitations.remove(playerId);
        if (invitation == null || invitation.expired()) {
            return new AcceptResult(InviteResult.EXPIRED, null);
        }
        if (partyByMember.containsKey(playerId)) {
            return new AcceptResult(InviteResult.ALREADY_IN_PARTY, null);
        }
        DungeonParty party = parties.get(invitation.partyId());
        if (party == null) {
            return new AcceptResult(InviteResult.EXPIRED, null);
        }
        if (party.size() >= maxSize) {
            return new AcceptResult(InviteResult.FULL, null);
        }
        party.add(playerId);
        partyByMember.put(playerId, party.id());
        return new AcceptResult(InviteResult.OK, party);
    }

    /** Removes a pending invitation without joining its party. */
    public boolean decline(UUID playerId) {
        Invitation invitation = invitations.remove(playerId);
        return invitation != null && !invitation.expired();
    }

    public Optional<Removal> removeMember(UUID playerId) {
        DungeonParty party = partyOf(playerId).orElse(null);
        if (party == null) {
            return Optional.empty();
        }
        UUID oldLeader = party.leader();
        UUID newLeader = party.remove(playerId);
        partyByMember.remove(playerId);
        invitations.entrySet().removeIf(entry -> entry.getValue().partyId().equals(party.id()));
        if (newLeader == null) {
            parties.remove(party.id());
        }
        return Optional.of(new Removal(party, oldLeader, newLeader));
    }

    public Optional<PartyInstance> instanceForMember(UUID playerId) {
        return partyOf(playerId).map(party -> instances.get(party.id())).filter(instance -> instance != null);
    }

    public boolean isInside(UUID playerId) {
        return instanceForMember(playerId)
                .map(instance -> instance.insideMembers().contains(playerId))
                .orElse(false);
    }

    public Optional<PartyInstance> instanceForParty(DungeonParty party) {
        return Optional.ofNullable(instances.get(party.id()));
    }

    public boolean hasInstance(DungeonParty party) {
        return instances.containsKey(party.id());
    }

    public Optional<DungeonParty> partyForWorld(String worldName) {
        return instances.values().stream()
                .filter(instance -> instance.worldName().equals(worldName))
                .findFirst()
                .map(instance -> parties.get(instance.partyId()));
    }

    public PartyInstance activateInstance(DungeonParty party, String worldName) {
        PartyInstance instance = new PartyInstance(party.id(), worldName);
        instances.put(party.id(), instance);
        return instance;
    }

    public void markEntered(UUID playerId) {
        markEntered(playerId, null);
    }

    /** Records both dungeon occupancy and the safe location from which the player entered. */
    public void markEntered(UUID playerId, Location returnLocation) {
        instanceForMember(playerId).ifPresent(instance -> {
            instance.insideMembers().add(playerId);
            if (returnLocation != null && returnLocation.getWorld() != null) {
                instance.returnLocations().put(playerId, returnLocation.clone());
            }
            instance.disconnectedLocations().remove(playerId);
            instance.clearOfflineSince();
        });
    }

    public void markRunStarted(DungeonParty party) {
        instanceForParty(party).ifPresent(PartyInstance::markRunStarted);
    }

    public Duration runDuration(DungeonParty party) {
        return instanceForParty(party).map(PartyInstance::runDuration).orElse(Duration.ZERO);
    }

    public Set<UUID> instanceMembers(DungeonParty party) {
        return instanceForParty(party).map(instance -> Set.copyOf(instance.insideMembers())).orElse(Set.of());
    }

    public Optional<Location> returnLocation(DungeonParty party, UUID playerId) {
        return instanceForParty(party).map(instance -> instance.returnLocations().get(playerId)).map(Location::clone);
    }

    /** Holds a post-completion destination until a live, dead, or offline player can use it. */
    public void queueCompletionReturn(UUID playerId, Location location) {
        if (location != null && location.getWorld() != null) completionReturns.put(playerId, location.clone());
    }

    public Optional<Location> takeCompletionReturn(UUID playerId) {
        Location location = completionReturns.remove(playerId);
        return Optional.ofNullable(location == null ? null : location.clone());
    }

    /** @return a world to delete when this was the last player inside */
    public Optional<String> markExited(UUID playerId) {
        PartyInstance instance = instanceForMember(playerId).orElse(null);
        if (instance == null) {
            return Optional.empty();
        }
        instance.insideMembers().remove(playerId);
        instance.disconnectedLocations().remove(playerId);
        if (!instance.insideMembers().isEmpty()) {
            return Optional.empty();
        }
        instances.remove(instance.partyId());
        return Optional.of(instance.worldName());
    }

    public Optional<String> endInstance(DungeonParty party) {
        PartyInstance instance = instances.remove(party.id());
        return Optional.ofNullable(instance == null ? null : instance.worldName());
    }

    public void rememberDisconnect(Player player) {
        instanceForMember(player.getUniqueId()).ifPresent(instance -> {
            if (instance.insideMembers().contains(player.getUniqueId())) {
                instance.disconnectedLocations().put(player.getUniqueId(), player.getLocation());
                if (allInsideOffline(instance)) {
                    instance.markOfflineNow();
                }
            }
        });
    }

    public Optional<Location> reconnectLocation(Player player) {
        PartyInstance instance = instanceForMember(player.getUniqueId()).orElse(null);
        if (instance == null || !instance.insideMembers().contains(player.getUniqueId())) {
            return Optional.empty();
        }
        instance.clearOfflineSince();
        return Optional.ofNullable(instance.disconnectedLocations().remove(player.getUniqueId()));
    }

    /** Returns party worlds whose every recorded occupant has been offline too long. */
    public List<String> collectExpiredOfflineWorlds() {
        long now = System.nanoTime();
        List<String> expired = new ArrayList<>();
        for (PartyInstance instance : new ArrayList<>(instances.values())) {
            if (instance.insideMembers().isEmpty()) {
                continue;
            }
            if (!allInsideOffline(instance)) {
                instance.clearOfflineSince();
                continue;
            }
            if (instance.offlineSinceNanos() == 0L) {
                instance.markOfflineNow();
                continue;
            }
            if (now - instance.offlineSinceNanos() >= offlineLifetime.toNanos()) {
                instances.remove(instance.partyId());
                expired.add(instance.worldName());
            }
        }
        return expired;
    }

    public String worldNameFor(DungeonParty party) {
        return "dungeon_party_" + party.id().toString().replace("-", "");
    }

    private static boolean allInsideOffline(PartyInstance instance) {
        for (UUID playerId : instance.insideMembers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                return false;
            }
        }
        return true;
    }

    public enum InviteResult {
        OK, SELF, NOT_LEADER, ALREADY_IN_PARTY, FULL, EXPIRED
    }

    public record AcceptResult(InviteResult result, DungeonParty party) {
    }

    public record Removal(DungeonParty party, UUID oldLeader, UUID newLeader) {
        public boolean leadershipChanged() {
            return newLeader != null && !oldLeader.equals(newLeader);
        }

        public boolean dissolved() {
            return newLeader == null;
        }
    }

    private record Invitation(UUID partyId, long expiresAtNanos) {
        private boolean expired() {
            return System.nanoTime() > expiresAtNanos;
        }
    }

    public static final class PartyInstance {
        private final UUID partyId;
        private final String worldName;
        private final Set<UUID> insideMembers = new HashSet<>();
        private final Map<UUID, Location> disconnectedLocations = new HashMap<>();
        private final Map<UUID, Location> returnLocations = new HashMap<>();
        private long offlineSinceNanos;
        private long runStartedAtNanos;

        private PartyInstance(UUID partyId, String worldName) {
            this.partyId = partyId;
            this.worldName = worldName;
        }

        public UUID partyId() {
            return partyId;
        }

        public String worldName() {
            return worldName;
        }

        Set<UUID> insideMembers() {
            return insideMembers;
        }

        Map<UUID, Location> disconnectedLocations() {
            return disconnectedLocations;
        }

        Map<UUID, Location> returnLocations() {
            return returnLocations;
        }

        void markRunStarted() {
            if (runStartedAtNanos == 0L) runStartedAtNanos = System.nanoTime();
        }

        Duration runDuration() {
            return runStartedAtNanos == 0L ? Duration.ZERO : Duration.ofNanos(System.nanoTime() - runStartedAtNanos);
        }

        long offlineSinceNanos() {
            return offlineSinceNanos;
        }

        void markOfflineNow() {
            offlineSinceNanos = System.nanoTime();
        }

        void clearOfflineSince() {
            offlineSinceNanos = 0L;
        }
    }
}
