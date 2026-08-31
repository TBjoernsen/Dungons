package nl.riddernix.dungeonforge.internal;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.api.DungeonForgeApi;
import nl.riddernix.dungeonforge.api.DungeonInfo;
import nl.riddernix.dungeonforge.api.DungeonMobInfo;
import nl.riddernix.dungeonforge.api.DungeonRoomInfo;
import nl.riddernix.dungeonforge.room.DungeonInstance;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Internal implementation registered through Bukkit's services manager.
 *
 * <p>Every method answers "not in a dungeon" with an empty Optional or an
 * empty list rather than null or an exception, so a caller never has to guard
 * against DungeonForge's own state.</p>
 */
public final class DungeonForgeApiImpl implements DungeonForgeApi {

    private final DungeonForgePlugin plugin;
    /** Ids already reported, so a query on every hit logs one line, not thousands. */
    private final Set<String> warnedNodeIds = new HashSet<>();

    public DungeonForgeApiImpl(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int getApiVersion() {
        return DungeonForgeApi.API_VERSION;
    }

    @Override
    public boolean isInDungeon(Player player) {
        return plugin.rooms().dungeon(player.getWorld()).isPresent();
    }

    @Override
    public Optional<DungeonInfo> getDungeon(Player player) {
        return plugin.rooms().dungeon(player.getWorld()).map(plugin.snapshots()::of);
    }

    @Override
    public Optional<DungeonInfo> getDungeon(World world) {
        return plugin.rooms().dungeon(world).map(plugin.snapshots()::of);
    }

    @Override
    public Optional<DungeonInfo> getDungeonById(String dungeonId) {
        return instance(dungeonId).map(plugin.snapshots()::of);
    }

    @Override
    public List<DungeonInfo> getActiveDungeons() {
        List<DungeonInfo> active = new ArrayList<>();
        for (World world : plugin.worlds().loadedDungeonWorlds()) {
            plugin.rooms().dungeon(world).map(plugin.snapshots()::of).ifPresent(active::add);
        }
        return List.copyOf(active);
    }

    @Override
    public OptionalInt getDifficulty(Player player) {
        return plugin.rooms().dungeon(player.getWorld())
                .map(dungeon -> OptionalInt.of(dungeon.difficulty()))
                .orElseGet(OptionalInt::empty);
    }

    @Override
    public Optional<DungeonRoomInfo> getCurrentRoom(Player player) {
        return plugin.rooms().room(player).map(plugin.snapshots()::of);
    }

    @Override
    public List<DungeonRoomInfo> getRooms(String dungeonId) {
        return instance(dungeonId)
                .map(dungeon -> dungeon.rooms().stream().map(plugin.snapshots()::of).toList())
                .orElseGet(List::of);
    }

    @Override
    public boolean isRoomCleared(String dungeonId, String roomId) {
        return plugin.mobs().isRoomCleared(dungeonId, roomId);
    }

    @Override
    public boolean isDungeonMob(Entity entity) {
        return getDungeonMob(entity).isPresent();
    }

    @Override
    public Optional<DungeonMobInfo> getDungeonMob(Entity entity) {
        var data = entity.getPersistentDataContainer();
        Integer tier = data.get(plugin.dungeonMobTierKey(), PersistentDataType.INTEGER);
        Integer difficulty = data.get(plugin.dungeonMobDifficultyKey(), PersistentDataType.INTEGER);
        if (tier == null || difficulty == null) {
            return Optional.empty();
        }
        boolean boss = Byte.valueOf((byte) 1).equals(data.get(plugin.dungeonMobBossKey(), PersistentDataType.BYTE));
        return Optional.of(new DungeonMobInfo(tier, difficulty,
                data.get(plugin.dungeonMobCategoryKey(), PersistentDataType.STRING),
                data.get(plugin.dungeonMobBossThemeKey(), PersistentDataType.STRING), boss));
    }

    @Override
    public List<UUID> getPartyMembers(Player player) {
        return plugin.parties().partyOf(player.getUniqueId())
                .map(party -> List.copyOf(party.members()))
                .orElseGet(List::of);
    }

    @Override
    public Optional<String> getActiveClass(Player player) {
        return plugin.skillProgress().activeClass(player.getUniqueId());
    }

    @Override
    public boolean hasSkillNode(Player player, String nodeId, int minimumLevel) {
        reportUnknownNode("hasSkillNode", nodeId);
        return plugin.skillProgress().hasNode(player.getUniqueId(), nodeId, minimumLevel);
    }

    @Override
    public int getSkillNodeLevel(Player player, String nodeId) {
        reportUnknownNode("getSkillNodeLevel", nodeId);
        return plugin.skillProgress().activeClass(player.getUniqueId())
                .map(classId -> plugin.skillProgress().nodeLevel(player.getUniqueId(), classId, nodeId))
                .orElse(0);
    }

    /**
     * Says something when a caller asks about a node that does not exist.
     *
     * <p>These two queries answer "no" and "0" for an unknown id exactly as
     * they do for a node the player has not bought, which makes an id mismatch
     * between two plugins invisible: everything simply behaves as though
     * nothing is ever unlocked. One log line per id turns that into a question
     * somebody can answer.</p>
     */
    private void reportUnknownNode(String method, String nodeId) {
        if (plugin.skillTrees().knowsNode(nodeId)) {
            return;
        }
        if (!warnedNodeIds.add(String.valueOf(nodeId))) {
            return;
        }
        plugin.getLogger().warning(method + " was asked about node '" + nodeId + "', which no skill tree "
                + "declares. It will answer as though the node is not unlocked, for every player, forever. "
                + "Check the id against skills.yml - this is what an id scheme that drifted between two "
                + "plugins looks like.");
    }

    @Override
    public java.util.Map<String, Integer> getUnlockedSkillNodes(Player player) {
        return plugin.skillProgress().unlockedNodes(player.getUniqueId());
    }

    @Override
    public int getSkillPoints(Player player) {
        return plugin.skillProgress().points(player.getUniqueId());
    }

    @Override
    public int getSpentSkillPoints(Player player) {
        return plugin.skillProgress().spentPoints(player.getUniqueId());
    }

    @Override
    public int grantSkillPoints(Player player, int amount) {
        return plugin.skillProgress().grantPoints(player, amount);
    }

    @Override
    public int withdrawSkillPoints(Player player, int amount) {
        return plugin.skillProgress().withdrawPoints(player, amount);
    }

    @Override
    public List<String> getSkillClasses() {
        return plugin.skillTrees().classIds();
    }

    @Override
    public nl.riddernix.dungeonforge.api.SkillWriteResult grantSkillNode(Player player, String nodeId) {
        return plugin.skillProgress().grantNode(player, nodeId);
    }

    @Override
    public nl.riddernix.dungeonforge.api.SkillWriteResult revokeSkillNode(Player player, String nodeId) {
        return plugin.skillProgress().revokeNode(player, nodeId);
    }

    @Override
    public nl.riddernix.dungeonforge.api.SkillWriteResult resetSkillTree(Player player) {
        return plugin.skillProgress().resetTree(player,
                plugin.skillProgress().activeClass(player.getUniqueId()).orElse(null));
    }

    @Override
    public nl.riddernix.dungeonforge.api.SkillWriteResult resetSkillTree(Player player, String classId) {
        return plugin.skillProgress().resetTree(player, classId);
    }

    @Override
    public nl.riddernix.dungeonforge.api.SkillWriteResult setActiveClass(Player player, String classId) {
        return plugin.skillProgress().setActiveClass(player, classId);
    }

    private Optional<DungeonInstance> instance(String dungeonId) {
        if (dungeonId == null) {
            return Optional.empty();
        }
        for (World world : plugin.worlds().loadedDungeonWorlds()) {
            Optional<DungeonInstance> dungeon = plugin.rooms().dungeon(world)
                    .filter(candidate -> candidate.id().equals(dungeonId));
            if (dungeon.isPresent()) {
                return dungeon;
            }
        }
        return Optional.empty();
    }
}
