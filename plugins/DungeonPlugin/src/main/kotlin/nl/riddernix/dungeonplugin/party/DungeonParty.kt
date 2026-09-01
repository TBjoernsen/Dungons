package nl.riddernix.dungeonplugin.party

import java.util.UUID

/** A transient party with insertion order used for leadership transfer. */
class DungeonParty internal constructor(leader: UUID) {

    val id: UUID = UUID.randomUUID()
    var leader: UUID = leader
        private set
    private val memberSet = LinkedHashSet<UUID>()

    init {
        memberSet.add(leader)
    }

    fun isLeader(playerId: UUID): Boolean = leader == playerId

    val members: Set<UUID>
        get() = memberSet.toSet()

    val size: Int
        get() = memberSet.size

    internal fun add(playerId: UUID) {
        memberSet.add(playerId)
    }

    /** @return the new leader, or `null` when the party became empty */
    internal fun remove(playerId: UUID): UUID? {
        memberSet.remove(playerId)
        if (memberSet.isEmpty()) {
            return null
        }
        if (leader == playerId) {
            leader = memberSet.first()
        }
        return leader
    }
}
