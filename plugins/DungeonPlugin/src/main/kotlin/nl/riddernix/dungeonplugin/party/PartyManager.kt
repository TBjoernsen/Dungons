package nl.riddernix.dungeonplugin.party

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import java.time.Duration
import java.util.UUID

/** Owns parties, pending invitations, and active party-instance bookkeeping. */
class PartyManager {

    private val parties = HashMap<UUID, DungeonParty>()
    private val partyByMember = HashMap<UUID, UUID>()
    private val invitations = HashMap<UUID, Invitation>()
    private val instances = HashMap<UUID, PartyInstance>()
    private val completionReturns = HashMap<UUID, Location>()

    private var inviteLifetime = Duration.ofSeconds(60)
    private var offlineLifetime = Duration.ofMinutes(10)
    private var maxSize = 4

    fun reload(config: FileConfiguration) {
        inviteLifetime = Duration.ofSeconds(maxOf(5, config.getLong("party.invite-expiry-seconds", 60L)))
        offlineLifetime = Duration.ofSeconds(maxOf(60, config.getLong("party.offline-instance-timeout-seconds", 600L)))
        maxSize = maxOf(1, config.getInt("party.max-size", 4))
    }

    fun partyOf(playerId: UUID): DungeonParty? = partyByMember[playerId]?.let { parties[it] }

    fun partyForLeader(leaderId: UUID): DungeonParty = partyOf(leaderId) ?: run {
        val party = DungeonParty(leaderId)
        parties[party.id] = party
        partyByMember[leaderId] = party.id
        party
    }

    fun invite(leader: Player, invitee: Player): InviteResult {
        if (leader.uniqueId == invitee.uniqueId) {
            return InviteResult.SELF
        }
        val party = partyForLeader(leader.uniqueId)
        if (!party.isLeader(leader.uniqueId)) {
            return InviteResult.NOT_LEADER
        }
        if (invitee.uniqueId in partyByMember) {
            return InviteResult.ALREADY_IN_PARTY
        }
        if (party.size >= maxSize) {
            return InviteResult.FULL
        }
        invitations[invitee.uniqueId] = Invitation(party.id, System.nanoTime() + inviteLifetime.toNanos())
        return InviteResult.OK
    }

    fun accept(playerId: UUID): AcceptResult {
        val invitation = invitations.remove(playerId)
        if (invitation == null || invitation.expired()) {
            return AcceptResult(InviteResult.EXPIRED, null)
        }
        if (playerId in partyByMember) {
            return AcceptResult(InviteResult.ALREADY_IN_PARTY, null)
        }
        val party = parties[invitation.partyId] ?: return AcceptResult(InviteResult.EXPIRED, null)
        if (party.size >= maxSize) {
            return AcceptResult(InviteResult.FULL, null)
        }
        party.add(playerId)
        partyByMember[playerId] = party.id
        return AcceptResult(InviteResult.OK, party)
    }

    /** Removes a pending invitation without joining its party. */
    fun decline(playerId: UUID): Boolean {
        val invitation = invitations.remove(playerId)
        return invitation != null && !invitation.expired()
    }

    fun removeMember(playerId: UUID): Removal? {
        val party = partyOf(playerId) ?: return null
        val oldLeader = party.leader
        val newLeader = party.remove(playerId)
        partyByMember.remove(playerId)
        invitations.entries.removeIf { it.value.partyId == party.id }
        if (newLeader == null) {
            parties.remove(party.id)
        }
        return Removal(party, oldLeader, newLeader)
    }

    fun instanceForMember(playerId: UUID): PartyInstance? = partyOf(playerId)?.let { instances[it.id] }

    fun isInside(playerId: UUID): Boolean =
        instanceForMember(playerId)?.insideMembers?.contains(playerId) ?: false

    fun instanceForParty(party: DungeonParty): PartyInstance? = instances[party.id]

    fun hasInstance(party: DungeonParty): Boolean = party.id in instances

    fun partyForWorld(worldName: String): DungeonParty? =
        instances.values.firstOrNull { it.worldName == worldName }?.let { parties[it.partyId] }

    fun activateInstance(party: DungeonParty, worldName: String): PartyInstance {
        val instance = PartyInstance(party.id, worldName)
        instances[party.id] = instance
        return instance
    }

    /** Records both dungeon occupancy and the safe location from which the player entered. */
    @JvmOverloads
    fun markEntered(playerId: UUID, returnLocation: Location? = null) {
        instanceForMember(playerId)?.let { instance ->
            instance.insideMembers.add(playerId)
            if (returnLocation?.world != null) {
                instance.returnLocations[playerId] = returnLocation.clone()
            }
            instance.disconnectedLocations.remove(playerId)
            instance.clearOfflineSince()
        }
    }

    fun markRunStarted(party: DungeonParty) {
        instanceForParty(party)?.markRunStarted()
    }

    fun runDuration(party: DungeonParty): Duration =
        instanceForParty(party)?.runDuration() ?: Duration.ZERO

    fun instanceMembers(party: DungeonParty): Set<UUID> =
        instanceForParty(party)?.insideMembers?.toSet() ?: emptySet()

    fun returnLocation(party: DungeonParty, playerId: UUID): Location? =
        instanceForParty(party)?.returnLocations?.get(playerId)?.clone()

    /** Holds a post-completion destination until a live, dead, or offline player can use it. */
    fun queueCompletionReturn(playerId: UUID, location: Location?) {
        if (location?.world != null) completionReturns[playerId] = location.clone()
    }

    fun takeCompletionReturn(playerId: UUID): Location? = completionReturns.remove(playerId)?.clone()

    /** @return a world to delete when this was the last player inside */
    fun markExited(playerId: UUID): String? {
        val instance = instanceForMember(playerId) ?: return null
        instance.insideMembers.remove(playerId)
        instance.disconnectedLocations.remove(playerId)
        if (instance.insideMembers.isNotEmpty()) {
            return null
        }
        instances.remove(instance.partyId)
        return instance.worldName
    }

    fun endInstance(party: DungeonParty): String? = instances.remove(party.id)?.worldName

    fun rememberDisconnect(player: Player) {
        instanceForMember(player.uniqueId)?.let { instance ->
            if (player.uniqueId in instance.insideMembers) {
                instance.disconnectedLocations[player.uniqueId] = player.location
                if (allInsideOffline(instance)) {
                    instance.markOfflineNow()
                }
            }
        }
    }

    fun reconnectLocation(player: Player): Location? {
        val instance = instanceForMember(player.uniqueId) ?: return null
        if (player.uniqueId !in instance.insideMembers) {
            return null
        }
        instance.clearOfflineSince()
        return instance.disconnectedLocations.remove(player.uniqueId)
    }

    /** Returns party worlds whose every recorded occupant has been offline too long. */
    fun collectExpiredOfflineWorlds(): List<String> {
        val now = System.nanoTime()
        val expired = ArrayList<String>()
        for (instance in instances.values.toList()) {
            if (instance.insideMembers.isEmpty()) {
                continue
            }
            if (!allInsideOffline(instance)) {
                instance.clearOfflineSince()
                continue
            }
            if (instance.offlineSinceNanos == 0L) {
                instance.markOfflineNow()
                continue
            }
            if (now - instance.offlineSinceNanos >= offlineLifetime.toNanos()) {
                instances.remove(instance.partyId)
                expired.add(instance.worldName)
            }
        }
        return expired
    }

    fun worldNameFor(party: DungeonParty): String =
        "dungeon_party_" + party.id.toString().replace("-", "")

    enum class InviteResult {
        OK, SELF, NOT_LEADER, ALREADY_IN_PARTY, FULL, EXPIRED
    }

    data class AcceptResult(val result: InviteResult, val party: DungeonParty?)

    data class Removal(val party: DungeonParty, val oldLeader: UUID, val newLeader: UUID?) {
        fun leadershipChanged(): Boolean = newLeader != null && oldLeader != newLeader
        fun dissolved(): Boolean = newLeader == null
    }

    private data class Invitation(val partyId: UUID, val expiresAtNanos: Long) {
        fun expired(): Boolean = System.nanoTime() > expiresAtNanos
    }

    class PartyInstance internal constructor(val partyId: UUID, val worldName: String) {
        internal val insideMembers = HashSet<UUID>()
        internal val disconnectedLocations = HashMap<UUID, Location>()
        internal val returnLocations = HashMap<UUID, Location>()
        internal var offlineSinceNanos = 0L
            private set
        private var runStartedAtNanos = 0L

        internal fun markRunStarted() {
            if (runStartedAtNanos == 0L) runStartedAtNanos = System.nanoTime()
        }

        internal fun runDuration(): Duration =
            if (runStartedAtNanos == 0L) Duration.ZERO else Duration.ofNanos(System.nanoTime() - runStartedAtNanos)

        internal fun markOfflineNow() {
            offlineSinceNanos = System.nanoTime()
        }

        internal fun clearOfflineSince() {
            offlineSinceNanos = 0L
        }
    }

    companion object {
        private fun allInsideOffline(instance: PartyInstance): Boolean {
            for (playerId in instance.insideMembers) {
                val player = Bukkit.getPlayer(playerId)
                if (player != null && player.isOnline) {
                    return false
                }
            }
            return true
        }
    }
}
