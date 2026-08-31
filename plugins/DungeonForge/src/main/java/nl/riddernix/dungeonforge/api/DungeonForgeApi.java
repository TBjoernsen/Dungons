package nl.riddernix.dungeonforge.api;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Stable, main-thread API for plugins that add gameplay to DungeonForge.
 *
 * <p>Obtain it through Bukkit's services manager:</p>
 * <pre>{@code
 * DungeonForgeApi api = getServer().getServicesManager()
 *         .load(DungeonForgeApi.class);
 * }</pre>
 *
 * <p>Nothing here returns null and nothing throws when the answer is simply
 * "not in a dungeon": queries return {@link Optional}, {@link OptionalInt} or
 * an empty list instead. Call it from the main thread.</p>
 */
public interface DungeonForgeApi {

    /**
     * Rises by one whenever anything is added. The package is additive from
     * version 2 on: existing events, methods and record components stay.
     * Version 3 added the skill tree: queries, points, and three events.
     * Version 4 added the skill write API and
     * {@link DungeonSkillNodesRevokedEvent}. One caveat on additivity there:
     * {@code DungeonSkillPointsChangeEvent.Reason} gained {@code REFUNDED},
     * so an exhaustive switch over it needs a default branch.
     * Version 5 added {@link DungeonSkillNodesGainedEvent}, the post-commit
     * counterpart to the revoke event: purchases and grants both fire it after
     * the state is stored, which the cancellable
     * {@link DungeonSkillNodeUnlockEvent} cannot do.
     */
    int API_VERSION = 5;

    /** The version of the DungeonForge that is actually running. */
    int getApiVersion();

    // ------------------------------------------------------------------
    //  Dungeons
    // ------------------------------------------------------------------

    /** Returns false when the player is not in a live DungeonForge dungeon. */
    boolean isInDungeon(Player player);

    /** The dungeon this player is standing in, empty when they are not in one. */
    Optional<DungeonInfo> getDungeon(Player player);

    /** The dungeon occupying this world, empty when the world is not a dungeon. */
    Optional<DungeonInfo> getDungeon(World world);

    /** Look one up by the id carried on every event. */
    Optional<DungeonInfo> getDungeonById(String dungeonId);

    /** Every dungeon currently running, in no particular order. */
    List<DungeonInfo> getActiveDungeons();

    /** Empty when the player is not in a live dungeon. */
    OptionalInt getDifficulty(Player player);

    // ------------------------------------------------------------------
    //  Rooms
    // ------------------------------------------------------------------

    /** Empty when the player is outside a room, including while in a corridor. */
    Optional<DungeonRoomInfo> getCurrentRoom(Player player);

    /** Every room in a dungeon's layout, empty when the id is unknown. */
    List<DungeonRoomInfo> getRooms(String dungeonId);

    /** True once every mob spawned for that room has been killed. */
    boolean isRoomCleared(String dungeonId, String roomId);

    // ------------------------------------------------------------------
    //  Mobs and parties
    // ------------------------------------------------------------------

    /** Returns true for live dungeon mobs and for tagged testing mobs from /dungeon summon. */
    boolean isDungeonMob(Entity entity);

    /** Empty when the entity is not a DungeonForge dungeon mob. */
    Optional<DungeonMobInfo> getDungeonMob(Entity entity);

    /** UUIDs of the player's current party, or an empty list when they have no party. */
    List<UUID> getPartyMembers(Player player);

    // ------------------------------------------------------------------
    //  Skills
    // ------------------------------------------------------------------

    /** The class the player confirmed at a panel, empty when they never did. */
    Optional<String> getActiveClass(Player player);

    /**
     * The hot-path check: whether the player's active class has this node at
     * this level or higher. A hash lookup - safe to call on every hit. Node
     * ids are the stable keys authored in skills.yml.
     */
    boolean hasSkillNode(Player player, String nodeId, int minimumLevel);

    /** The node's level in the player's active class; 0 when locked or classless. */
    int getSkillNodeLevel(Player player, String nodeId);

    /** A copy of the active class's unlocks, node id to level. Not for hot paths. */
    Map<String, Integer> getUnlockedSkillNodes(Player player);

    /** The player's available (unspent) skill points. */
    int getSkillPoints(Player player);

    /** Points ever paid for unlocks, across all classes. */
    int getSpentSkillPoints(Player player);

    /**
     * Adds points, firing {@link DungeonSkillPointsChangeEvent}. Progression
     * rewards belong to the listening plugin, so this is the intended source
     * of every point a player ever gets.
     *
     * @return the new available balance
     */
    int grantSkillPoints(Player player, int amount);

    /** Takes up to {@code amount}; the balance never goes negative. @return the new balance */
    int withdrawSkillPoints(Player player, int amount);

    /** Every class id defined in skills.yml, in carousel order. */
    List<String> getSkillClasses();

    // ------------------------------------------------------------------
    //  Skill writes
    //
    //  All of these operate on the player's active class, return a
    //  SkillWriteResult rather than throwing, keep the tree's prerequisites
    //  consistent, persist immediately, and redraw any panel the player is
    //  looking at. They require an online player; for someone offline, apply
    //  the change when they join.
    // ------------------------------------------------------------------

    /**
     * Gives a node without charging for it.
     *
     * <p>Free deliberately - points are your currency, and
     * {@link #grantSkillPoints} hands those out. Prerequisites are still
     * required ({@code LOCKED} otherwise), because a node hanging off nothing
     * corrupts the tree. Since nothing was paid, revoking it later refunds
     * nothing, so grant/revoke cannot mint points. Fires the cancellable
     * {@link DungeonSkillNodeUnlockEvent} exactly as a panel unlock does.</p>
     */
    SkillWriteResult grantSkillNode(Player player, String nodeId);

    /**
     * Takes a node away, together with anything that was only reachable
     * through it, refunding whatever was actually paid for all of them.
     *
     * <p>The cascade is the deliberate choice: leaving a node whose
     * prerequisites are gone would render wrongly and could never be reasoned
     * about again. {@link SkillWriteResult#nodes()} lists everything that
     * went, which is usually more than you asked for.</p>
     */
    SkillWriteResult revokeSkillNode(Player player, String nodeId);

    /** Clears the active class's tree, refunding everything paid into it. */
    SkillWriteResult resetSkillTree(Player player);

    /** The same for a named class, whether or not it is the active one. */
    SkillWriteResult resetSkillTree(Player player, String classId);

    /**
     * Switches the player's class. A class they have never touched is fine -
     * its tree starts empty - and unlocks are kept per class, so switching
     * away and back loses nothing. An unknown id is {@code NO_SUCH_CLASS} and
     * changes nothing.
     */
    SkillWriteResult setActiveClass(Player player, String classId);
}
