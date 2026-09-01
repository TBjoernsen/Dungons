package nl.riddernix.dungeonplugin.internal

import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.event.DungeonInfo
import nl.riddernix.dungeonplugin.event.DungeonMobInfo
import nl.riddernix.dungeonplugin.event.DungeonRoomInfo
import nl.riddernix.dungeonplugin.event.SkillWriteResult
import nl.riddernix.dungeonplugin.room.DungeonInstance
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * The former public API surface, kept as an internal query facade.
 *
 * The merge made the one external consumer (ClassSkills) part of this plugin,
 * so nothing is registered with Bukkit's services manager any more. The
 * methods stay because `/dungeon api query` and several subsystems still want
 * one place that answers "not in a dungeon" with null or an empty collection
 * rather than throwing.
 */
class DungeonQueries(private val plugin: DungeonPlugin) {

    /** Ids already reported, so a query on every hit logs one line, not thousands. */
    private val warnedNodeIds = HashSet<String>()

    // ------------------------------------------------------------------
    //  Dungeons
    // ------------------------------------------------------------------

    /** False when the player is not in a live dungeon. */
    fun isInDungeon(player: Player): Boolean = plugin.rooms.dungeon(player.world) != null

    /** The dungeon this player is standing in, null when they are not in one. */
    fun dungeon(player: Player): DungeonInfo? =
        plugin.rooms.dungeon(player.world)?.let(plugin.snapshots::of)

    /** The dungeon occupying this world, null when the world is not a dungeon. */
    fun dungeon(world: World): DungeonInfo? =
        plugin.rooms.dungeon(world)?.let(plugin.snapshots::of)

    /** Look one up by the id carried on every event. */
    fun dungeonById(dungeonId: String?): DungeonInfo? =
        instance(dungeonId)?.let(plugin.snapshots::of)

    /** Every dungeon currently running, in no particular order. */
    fun activeDungeons(): List<DungeonInfo> =
        plugin.worlds.loadedDungeonWorlds().mapNotNull { world ->
            plugin.rooms.dungeon(world)?.let(plugin.snapshots::of)
        }

    /** Null when the player is not in a live dungeon. */
    fun difficulty(player: Player): Int? = plugin.rooms.dungeon(player.world)?.difficulty

    // ------------------------------------------------------------------
    //  Rooms
    // ------------------------------------------------------------------

    /** Null when the player is outside a room, including while in a corridor. */
    fun currentRoom(player: Player): DungeonRoomInfo? =
        plugin.rooms.room(player)?.let(plugin.snapshots::of)

    /** Every room in a dungeon's layout, empty when the id is unknown. */
    fun rooms(dungeonId: String?): List<DungeonRoomInfo> =
        instance(dungeonId)?.rooms?.map(plugin.snapshots::of) ?: emptyList()

    /** True once every mob spawned for that room has been killed. */
    fun isRoomCleared(dungeonId: String, roomId: String): Boolean =
        plugin.mobs.isRoomCleared(dungeonId, roomId)

    // ------------------------------------------------------------------
    //  Mobs and parties
    // ------------------------------------------------------------------

    /** True for live dungeon mobs and for tagged testing mobs from /dungeon summon. */
    fun isDungeonMob(entity: Entity): Boolean = dungeonMob(entity) != null

    /** Null when the entity is not a dungeon mob. */
    fun dungeonMob(entity: Entity): DungeonMobInfo? {
        val data = entity.persistentDataContainer
        val tier = data.get(plugin.dungeonMobTierKey, PersistentDataType.INTEGER) ?: return null
        val difficulty = data.get(plugin.dungeonMobDifficultyKey, PersistentDataType.INTEGER) ?: return null
        val boss = data.get(plugin.dungeonMobBossKey, PersistentDataType.BYTE) == 1.toByte()
        return DungeonMobInfo(tier, difficulty,
            data.get(plugin.dungeonMobCategoryKey, PersistentDataType.STRING),
            data.get(plugin.dungeonMobBossThemeKey, PersistentDataType.STRING), boss)
    }

    /** UUIDs of the player's current party, or an empty list when they have no party. */
    fun partyMembers(player: Player): List<UUID> =
        plugin.parties.partyOf(player.uniqueId)?.members?.toList() ?: emptyList()

    // ------------------------------------------------------------------
    //  Skills
    // ------------------------------------------------------------------

    /** The class the player confirmed, null when they never did. */
    fun activeClass(player: Player): String? = plugin.skillProgress.activeClass(player.uniqueId)

    /**
     * The hot-path check: whether the player's active class has this node at
     * this level or higher. A hash lookup - safe to call on every hit. Node
     * ids are the stable keys authored in skills.yml.
     */
    fun hasSkillNode(player: Player, nodeId: String, minimumLevel: Int): Boolean {
        reportUnknownNode("hasSkillNode", nodeId)
        return plugin.skillProgress.hasNode(player.uniqueId, nodeId, minimumLevel)
    }

    /** The node's level in the player's active class; 0 when locked or classless. */
    fun skillNodeLevel(player: Player, nodeId: String): Int {
        reportUnknownNode("getSkillNodeLevel", nodeId)
        val classId = plugin.skillProgress.activeClass(player.uniqueId) ?: return 0
        return plugin.skillProgress.nodeLevel(player.uniqueId, classId, nodeId)
    }

    /**
     * Says something when a caller asks about a node that does not exist.
     *
     * These two queries answer "no" and "0" for an unknown id exactly as they
     * do for a node the player has not bought, which makes an id mismatch
     * invisible: everything simply behaves as though nothing is ever
     * unlocked. One log line per id turns that into a question somebody can
     * answer.
     */
    private fun reportUnknownNode(method: String, nodeId: String) {
        if (plugin.skillTrees.knowsNode(nodeId)) {
            return
        }
        if (!warnedNodeIds.add(nodeId)) {
            return
        }
        plugin.logger.warning("$method was asked about node '$nodeId', which no skill tree " +
            "declares. It will answer as though the node is not unlocked, for every player, forever. " +
            "Check the id against skills.yml.")
    }

    /** A copy of the active class's unlocks, node id to level. Not for hot paths. */
    fun unlockedSkillNodes(player: Player): Map<String, Int> =
        plugin.skillProgress.unlockedNodes(player.uniqueId)

    /** The player's available (unspent) skill points. */
    fun skillPoints(player: Player): Int = plugin.skillProgress.points(player.uniqueId)

    /** Points ever paid for unlocks, across all classes. */
    fun spentSkillPoints(player: Player): Int = plugin.skillProgress.spentPoints(player.uniqueId)

    /** Adds points, firing the points-change event. @return the new available balance */
    fun grantSkillPoints(player: Player, amount: Int): Int =
        plugin.skillProgress.grantPoints(player, amount)

    /** Takes up to `amount`; the balance never goes negative. @return the new balance */
    fun withdrawSkillPoints(player: Player, amount: Int): Int =
        plugin.skillProgress.withdrawPoints(player, amount)

    /** Every class id defined in skills.yml, in carousel order. */
    fun skillClasses(): List<String> = plugin.skillTrees.classIds()

    // ------------------------------------------------------------------
    //  Skill writes
    // ------------------------------------------------------------------

    /**
     * Gives a node without charging for it. Prerequisites are still required
     * (`LOCKED` otherwise), because a node hanging off nothing corrupts the
     * tree. Since nothing was paid, revoking it later refunds nothing, so
     * grant/revoke cannot mint points. Fires the cancellable unlock event
     * exactly as a panel unlock does.
     */
    fun grantSkillNode(player: Player, nodeId: String): SkillWriteResult =
        plugin.skillProgress.grantNode(player, nodeId)

    /**
     * Takes a node away, together with anything that was only reachable
     * through it, refunding whatever was actually paid for all of them.
     */
    fun revokeSkillNode(player: Player, nodeId: String): SkillWriteResult =
        plugin.skillProgress.revokeNode(player, nodeId)

    /** Clears the active class's tree, refunding everything paid into it. */
    fun resetSkillTree(player: Player): SkillWriteResult =
        plugin.skillProgress.resetTree(player, plugin.skillProgress.activeClass(player.uniqueId))

    /** The same for a named class, whether or not it is the active one. */
    fun resetSkillTree(player: Player, classId: String?): SkillWriteResult =
        plugin.skillProgress.resetTree(player, classId)

    /** Switches the player's class. Unlocks are kept per class. */
    fun setActiveClass(player: Player, classId: String): SkillWriteResult =
        plugin.skillProgress.setActiveClass(player, classId)

    private fun instance(dungeonId: String?): DungeonInstance? {
        if (dungeonId == null) {
            return null
        }
        for (world in plugin.worlds.loadedDungeonWorlds()) {
            val dungeon = plugin.rooms.dungeon(world)?.takeIf { it.id == dungeonId }
            if (dungeon != null) {
                return dungeon
            }
        }
        return null
    }
}
