package nl.riddernix.dungeonforge.skills;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.api.DungeonSkillNodesGainedEvent;
import nl.riddernix.dungeonforge.api.DungeonSkillPointsChangeEvent;
import nl.riddernix.dungeonforge.api.SkillWriteResult;
import nl.riddernix.dungeonforge.api.SkillWriteStatus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The authoritative skill progression state: active class, unlocked node
 * levels per class, and the point balance.
 *
 * <p>Everything lives in plain in-memory maps keyed by UUID, so the queries a
 * gameplay plugin calls on every hit - {@code hasNode} above all - are hash
 * lookups, never rebuilds. The file ({@code skill-progress.yml}) is only
 * touched when something changes.</p>
 *
 * <p>What a node does is deliberately absent. To this class a node is a
 * stable id from skills.yml, a level, and what was paid for it; the effect
 * belongs to whatever plugin listens through the API.</p>
 *
 * <p><strong>The invariant every write protects:</strong> a held node's
 * prerequisites are always held too. Granting refuses to break it and
 * revoking cascades to preserve it, because a tree where a node hangs off
 * nothing renders wrong and can never be reasoned about again.</p>
 */
public final class SkillProgressManager {

    private static final String FILE_NAME = "skill-progress.yml";
    /** Until levelled skills exist, every node caps at one. */
    private static final int MAX_LEVEL = 1;
    /**
     * How deep API writes may nest. One level lets a listener answer a change
     * with a related change; a plugin that answers its own event by calling
     * straight back in runs out of room instead of looping forever.
     */
    private static final int MAX_WRITE_DEPTH = 4;

    public enum UnlockResult { UNLOCKED, ALREADY_UNLOCKED, LOCKED, DIFFICULTY_LOCKED, NOT_ENOUGH_POINTS,
        UNKNOWN_NODE, REFUSED }

    private final DungeonForgePlugin plugin;
    private final File file;
    private final Map<UUID, Progress> byPlayer = new HashMap<>();
    private final Map<UUID, Integer> writeDepth = new HashMap<>();
    private boolean warnedAboutGrants;

    public SkillProgressManager(DungeonForgePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        load();
    }

    // ------------------------------------------------------------------
    //  Queries - all O(1) or a copy of one small map
    // ------------------------------------------------------------------

    /**
     * ClassSkills owns the class when it is installed - picking one lives on
     * that side. What is stored here is only the fallback for a server
     * running DungeonForge on its own.
     */
    public Optional<String> activeClass(UUID playerId) {
        Player player = org.bukkit.Bukkit.getPlayer(playerId);
        if (player != null) {
            Optional<String> owned = plugin.classSkills().activeClass(player);
            if (owned.isPresent()) {
                return owned;
            }
        }
        Progress progress = byPlayer.get(playerId);
        return Optional.ofNullable(progress == null ? null : progress.activeClass);
    }

    /** The level of one node in one class's tree; 0 when locked. */
    public int nodeLevel(UUID playerId, String classId, String nodeId) {
        Held held = held(playerId, classId, nodeId);
        return held == null ? 0 : held.level;
    }

    /** The hot-path check: active class, one node, at least this level. */
    public boolean hasNode(UUID playerId, String nodeId, int minimumLevel) {
        String classId = activeClass(playerId).orElse(null);
        return classId != null && nodeLevel(playerId, classId, nodeId) >= Math.max(1, minimumLevel);
    }

    /** A copy of the active class's unlocks, node id to level. */
    public Map<String, Integer> unlockedNodes(UUID playerId) {
        return activeClass(playerId).map(classId -> unlockedNodes(playerId, classId)).orElseGet(Map::of);
    }

    /** One specific class's unlocks, for panels showing a class that is not active. */
    public Map<String, Integer> unlockedNodes(UUID playerId, String classId) {
        Map<String, Held> held = heldNodes(playerId, classId);
        Map<String, Integer> levels = new HashMap<>();
        held.forEach((nodeId, entry) -> levels.put(nodeId, entry.level));
        return Map.copyOf(levels);
    }

    /**
     * Available points, derived rather than stored.
     *
     * <p>With ClassSkills installed the pool is <strong>entirely</strong>
     * its level-derived budget: {@code budget - spent}. Points are earned by
     * levelling there, so nothing on this side hands any out - see
     * {@link #grantPoints}. Without ClassSkills the plugin falls back to what
     * it was granted itself, {@code granted - spent}, so a server running
     * DungeonForge alone still works.</p>
     *
     * <p>Deriving instead of storing is what stops the two sides drifting: a
     * purchase moves {@code spent} and the balance follows by arithmetic,
     * with no second entry that could disagree.</p>
     */
    public int points(UUID playerId) {
        Progress progress = byPlayer.get(playerId);
        int spent = progress == null ? 0 : progress.spent;
        if (plugin.classSkills().isAvailable()) {
            return Math.max(0, budget(playerId) - spent);
        }
        return Math.max(0, (progress == null ? 0 : progress.granted) - spent);
    }

    /** ClassSkills' budget for this player, or 0 when it is not installed. */
    public int budget(UUID playerId) {
        Player player = org.bukkit.Bukkit.getPlayer(playerId);
        return player == null ? 0 : plugin.classSkills().budget(player).orElse(0);
    }

    public int spentPoints(UUID playerId) {
        Progress progress = byPlayer.get(playerId);
        return progress == null ? 0 : progress.spent;
    }

    // ------------------------------------------------------------------
    //  Point balance
    // ------------------------------------------------------------------

    /** @return the previous class, empty on a first confirmation */
    public Optional<String> setActiveClassInternal(Player player, String classId) {
        Progress progress = progress(player.getUniqueId());
        String previous = progress.activeClass;
        progress.activeClass = classId.toLowerCase(Locale.ROOT);
        save();
        if (!progress.activeClass.equals(previous)) {
            plugin.events().fireSkillClassChange(player, previous, progress.activeClass);
        }
        return Optional.ofNullable(previous);
    }

    /**
     * Hands out points on DungeonForge's own budget.
     *
     * <p><strong>Inert while ClassSkills is installed:</strong> the pool is
     * its level-derived budget, so points are earned by levelling and cannot
     * be minted here. The call returns the unchanged balance and says so once
     * in the log rather than pretending to have worked.</p>
     *
     * @return the new balance
     */
    public int grantPoints(Player player, int amount) {
        return changePoints(player, Math.max(0, amount), DungeonSkillPointsChangeEvent.Reason.GRANTED);
    }

    /** Takes up to {@code amount}; the balance never goes negative. @return the new balance */
    public int withdrawPoints(Player player, int amount) {
        return changePoints(player, -Math.min(Math.max(0, amount), points(player.getUniqueId())),
                DungeonSkillPointsChangeEvent.Reason.WITHDRAWN);
    }

    private int changePoints(Player player, int delta, DungeonSkillPointsChangeEvent.Reason reason) {
        Progress progress = progress(player.getUniqueId());
        int previous = points(player.getUniqueId());
        if (delta == 0) return previous;
        if (plugin.classSkills().isAvailable()) {
            if (!warnedAboutGrants) {
                warnedAboutGrants = true;
                plugin.getLogger().warning("Something tried to change " + player.getName() + "'s skill points, but "
                        + "ClassSkills owns the budget: points come from levelling there and cannot be granted or "
                        + "taken here. The balance was left alone.");
            }
            return previous;
        }
        progress.granted += delta;
        save();
        // A balance shown on an open panel has to move with it. Without this a
        // reward granted through the API only appeared after walking away and
        // back, which reads as the reward never arriving.
        refreshPanels(player);
        int now = points(player.getUniqueId());
        plugin.events().fireSkillPointsChange(player, previous, now, reason);
        return now;
    }

    // ------------------------------------------------------------------
    //  The panel's unlock path
    // ------------------------------------------------------------------

    /**
     * The one path to a paid unlock, used by the panel and by nothing else.
     * The checks run in the order a player would want to hear about them, and
     * the cancellable event fires only for an attempt that would otherwise
     * succeed - a veto listener never has to re-validate prerequisites.
     */
    public UnlockResult unlock(Player player, String classId, String nodeId) {
        SkillTreeLibrary.SkillClassTree tree = plugin.skillTrees().tree(classId).orElse(null);
        SkillTreeLibrary.SkillNode node = tree == null ? null : tree.nodes().get(nodeId);
        if (node == null) return UnlockResult.UNKNOWN_NODE;
        UUID playerId = player.getUniqueId();
        String key = classId.toLowerCase(Locale.ROOT);
        if (nodeLevel(playerId, key, nodeId) >= MAX_LEVEL) return UnlockResult.ALREADY_UNLOCKED;
        if (!reachable(node, heldNodes(playerId, key).keySet())) return UnlockResult.LOCKED;
        if (!difficultyMet(player, node)) return UnlockResult.DIFFICULTY_LOCKED;
        Progress progress = progress(playerId);
        int previousPoints = points(playerId);
        if (previousPoints < node.cost()) return UnlockResult.NOT_ENOUGH_POINTS;
        if (!plugin.events().fireSkillNodeUnlock(player, key, nodeId, 1, node.cost(), previousPoints)) {
            return UnlockResult.REFUSED;
        }
        progress.spent += node.cost();
        progress.unlocked.computeIfAbsent(key, ignored -> new HashMap<>())
                .put(nodeId, new Held(1, node.cost()));
        save();
        // Nodes before points, the same order the revoke path uses, so a
        // listener handling both sees the tree change and then its price.
        plugin.events().fireSkillNodesGained(player, key, Set.of(nodeId), node.cost(),
                DungeonSkillNodesGainedEvent.Source.PURCHASED);
        plugin.events().fireSkillPointsChange(player, previousPoints, points(playerId),
                DungeonSkillPointsChangeEvent.Reason.SPENT);
        return UnlockResult.UNLOCKED;
    }

    // ------------------------------------------------------------------
    //  The public write API
    // ------------------------------------------------------------------

    /**
     * Gives a node without charging for it.
     *
     * <p>Free on purpose: points are the listening plugin's currency, and it
     * has {@code grantSkillPoints} for those. Prerequisites are still
     * required, because handing out a node whose parents are missing is the
     * one thing that corrupts a tree. Nothing paid means nothing refunded if
     * it is revoked later, so a grant/revoke cycle cannot mint points.</p>
     *
     * <p>A {@code requires-difficulty} gate is <em>not</em> enforced here. It
     * is a progression rule rather than a structural one, and a grant is a
     * deliberate act by the plugin that asked for it - the same reason cost is
     * waived.</p>
     */
    public SkillWriteResult grantNode(Player player, String nodeId) {
        return write(player, points -> {
            String classId = activeClass(player.getUniqueId()).orElse(null);
            if (classId == null) return SkillWriteResult.failed(SkillWriteStatus.NO_ACTIVE_CLASS, "", points);
            SkillTreeLibrary.SkillClassTree tree = plugin.skillTrees().tree(classId).orElse(null);
            SkillTreeLibrary.SkillNode node = tree == null ? null : tree.nodes().get(nodeId);
            if (node == null) return SkillWriteResult.failed(SkillWriteStatus.NO_SUCH_NODE, classId, points);
            Map<String, Held> held = heldNodes(player.getUniqueId(), classId);
            if (held.containsKey(nodeId)) {
                return SkillWriteResult.failed(SkillWriteStatus.UNCHANGED, classId, points);
            }
            if (!reachable(node, held.keySet())) {
                return SkillWriteResult.failed(SkillWriteStatus.LOCKED, classId, points);
            }
            if (!plugin.events().fireSkillNodeUnlock(player, classId, nodeId, 1, 0, points)) {
                return SkillWriteResult.failed(SkillWriteStatus.REFUSED, classId, points);
            }
            progress(player.getUniqueId()).unlocked
                    .computeIfAbsent(classId, ignored -> new HashMap<>()).put(nodeId, new Held(1, 0));
            save();
            refreshPanels(player);
            // A grant costs nothing, so no points event carries it. Without
            // this a plugin deriving effects from the tree would never hear
            // about the one node that is handed out rather than bought.
            plugin.events().fireSkillNodesGained(player, classId, Set.of(nodeId), 0,
                    DungeonSkillNodesGainedEvent.Source.GRANTED);
            return new SkillWriteResult(SkillWriteStatus.SUCCESS, classId, Set.of(nodeId), points, points);
        });
    }

    /**
     * Takes a node away, and with it anything that was only reachable through
     * it, refunding whatever was paid for all of them.
     */
    public SkillWriteResult revokeNode(Player player, String nodeId) {
        return write(player, points -> {
            String classId = activeClass(player.getUniqueId()).orElse(null);
            if (classId == null) return SkillWriteResult.failed(SkillWriteStatus.NO_ACTIVE_CLASS, "", points);
            SkillTreeLibrary.SkillClassTree tree = plugin.skillTrees().tree(classId).orElse(null);
            if (tree == null || !tree.nodes().containsKey(nodeId)) {
                return SkillWriteResult.failed(SkillWriteStatus.NO_SUCH_NODE, classId, points);
            }
            Map<String, Held> held = heldNodes(player.getUniqueId(), classId);
            if (!held.containsKey(nodeId)) {
                return SkillWriteResult.failed(SkillWriteStatus.UNCHANGED, classId, points);
            }
            return remove(player, classId, tree, cascade(tree, held, nodeId), points);
        });
    }

    /** Clears one class's tree completely, refunding everything paid into it. */
    public SkillWriteResult resetTree(Player player, String classId) {
        return write(player, points -> {
            String key = classId == null ? null : classId.toLowerCase(Locale.ROOT);
            if (key == null || plugin.skillTrees().tree(key).isEmpty()) {
                return SkillWriteResult.failed(SkillWriteStatus.NO_SUCH_CLASS, key == null ? "" : key, points);
            }
            Map<String, Held> held = heldNodes(player.getUniqueId(), key);
            if (held.isEmpty()) return SkillWriteResult.failed(SkillWriteStatus.UNCHANGED, key, points);
            return remove(player, key, plugin.skillTrees().tree(key).orElse(null),
                    new HashSet<>(held.keySet()), points);
        });
    }

    /**
     * Switches the player's class. A class they have never touched is fine -
     * its tree simply starts empty - and unlocks are kept per class, so
     * switching away and back loses nothing.
     */
    public SkillWriteResult setActiveClass(Player player, String classId) {
        return write(player, points -> {
            String key = classId == null ? null : classId.toLowerCase(Locale.ROOT);
            if (key == null || plugin.skillTrees().tree(key).isEmpty()) {
                return SkillWriteResult.failed(SkillWriteStatus.NO_SUCH_CLASS, key == null ? "" : key, points);
            }
            if (key.equals(activeClass(player.getUniqueId()).orElse(null))) {
                return SkillWriteResult.failed(SkillWriteStatus.UNCHANGED, key, points);
            }
            setActiveClassInternal(player, key);
            refreshPanels(player);
            return new SkillWriteResult(SkillWriteStatus.SUCCESS, key, Set.of(), points, points);
        });
    }

    /** Removes a resolved set of nodes, refunds it, fires both events, redraws. */
    private SkillWriteResult remove(Player player, String classId, SkillTreeLibrary.SkillClassTree tree,
                                    Set<String> removing, int points) {
        Progress progress = progress(player.getUniqueId());
        Map<String, Held> held = progress.unlocked.getOrDefault(classId, Map.of());
        int refund = 0;
        for (String nodeId : removing) {
            Held entry = held.get(nodeId);
            if (entry != null) refund += entry.paid;
        }
        held.keySet().removeAll(removing);
        // Only spent moves: the balance is derived from it, so adding the
        // refund to a stored total as well would hand it over twice.
        progress.spent = Math.max(0, progress.spent - refund);
        save();
        refreshPanels(player);
        plugin.events().fireSkillNodesRevoked(player, classId, removing, refund);
        int now = points(player.getUniqueId());
        if (refund > 0) {
            plugin.events().fireSkillPointsChange(player, points, now,
                    DungeonSkillPointsChangeEvent.Reason.REFUNDED);
        }
        return new SkillWriteResult(SkillWriteStatus.SUCCESS, classId, removing, points, now);
    }

    /**
     * Everything that must go when one node does.
     *
     * <p>A fixpoint rather than a walk down the children: with {@code any-of}
     * nodes a path can rejoin, so a node with a second route still stands.
     * Repeating until nothing more falls is the only way to get that right.</p>
     */
    private Set<String> cascade(SkillTreeLibrary.SkillClassTree tree, Map<String, Held> held, String nodeId) {
        Set<String> removing = new HashSet<>();
        removing.add(nodeId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String candidate : new ArrayList<>(held.keySet())) {
                if (removing.contains(candidate)) continue;
                SkillTreeLibrary.SkillNode node = tree.nodes().get(candidate);
                if (node == null || node.requires().isEmpty()) continue;
                Set<String> surviving = new HashSet<>(held.keySet());
                surviving.removeAll(removing);
                if (!reachable(node, surviving)) {
                    removing.add(candidate);
                    changed = true;
                }
            }
        }
        return removing;
    }

    /**
     * Whether the player has cleared the difficulty a node asks for.
     *
     * <p>The requirement is tree data, the answer is not: ClassSkills owns
     * what a player has cleared. Without it every gate passes, so a server
     * running DungeonForge on its own sees the tree it always saw.</p>
     */
    public boolean difficultyMet(Player player, SkillTreeLibrary.SkillNode node) {
        return node.requiresDifficulty() <= 0
                || plugin.classSkills().hasUnlockedDifficulty(player, node.requiresDifficulty());
    }

    private static boolean reachable(SkillTreeLibrary.SkillNode node, Set<String> held) {
        if (node.requires().isEmpty()) return true;
        return node.anyOf()
                ? node.requires().stream().anyMatch(held::contains)
                : node.requires().stream().allMatch(held::contains);
    }

    /**
     * Runs one write with the re-entrancy guard around it.
     *
     * <p>Every mutation fires events, and a listener may call back in. One or
     * two levels of that is a plugin reacting sensibly; past
     * {@link #MAX_WRITE_DEPTH} it is a loop, and the caller is told so rather
     * than the server stalling.</p>
     */
    private SkillWriteResult write(Player player, java.util.function.IntFunction<SkillWriteResult> body) {
        UUID playerId = player.getUniqueId();
        int depth = writeDepth.getOrDefault(playerId, 0);
        int points = points(playerId);
        if (depth >= MAX_WRITE_DEPTH) {
            plugin.getLogger().warning("Skill write for " + player.getName() + " nested "
                    + depth + " deep and was refused; a listener is answering its own event.");
            return SkillWriteResult.failed(SkillWriteStatus.REENTRANT,
                    activeClass(playerId).orElse(""), points);
        }
        writeDepth.put(playerId, depth + 1);
        try {
            return body.apply(points);
        } finally {
            if (depth == 0) writeDepth.remove(playerId); else writeDepth.put(playerId, depth);
        }
    }

    /** Redraws this player's own overlays, so a change is on screen at once. */
    private void refreshPanels(Player player) {
        if (player.isOnline()) {
            plugin.skillPanels().refreshFor(player);
        }
    }

    /** The render-test backdoor: a free unlock, same storage, no cost and no veto. */
    public void unlockWithoutCost(Player player, String classId, String nodeId) {
        progress(player.getUniqueId()).unlocked
                .computeIfAbsent(classId.toLowerCase(Locale.ROOT), ignored -> new HashMap<>())
                .put(nodeId, new Held(1, 0));
        save();
    }

    /** Wipes one player's unlocks in every class; points are kept. */
    public void clearUnlocks(UUID playerId) {
        Progress progress = byPlayer.get(playerId);
        if (progress == null) return;
        progress.unlocked.clear();
        save();
    }

    // ------------------------------------------------------------------
    //  Storage
    // ------------------------------------------------------------------

    private Progress progress(UUID playerId) {
        return byPlayer.computeIfAbsent(playerId, ignored -> new Progress());
    }

    private Map<String, Held> heldNodes(UUID playerId, String classId) {
        Progress progress = byPlayer.get(playerId);
        if (progress == null || classId == null) return Map.of();
        return progress.unlocked.getOrDefault(classId.toLowerCase(Locale.ROOT), Map.of());
    }

    private Held held(UUID playerId, String classId, String nodeId) {
        return heldNodes(playerId, classId).get(nodeId);
    }

    private void load() {
        byPlayer.clear();
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String rawId : yaml.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(rawId);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring invalid entry '" + rawId + "' in " + FILE_NAME + ".");
                continue;
            }
            Progress progress = new Progress();
            progress.activeClass = yaml.getString(rawId + ".class");
            progress.spent = Math.max(0, yaml.getInt(rawId + ".spent", 0));
            // Files written before points became derived stored the remaining
            // balance; remaining plus spent is what was granted in total.
            progress.granted = yaml.contains(rawId + ".granted")
                    ? yaml.getInt(rawId + ".granted")
                    : yaml.getInt(rawId + ".points", 0) + progress.spent;
            ConfigurationSection classes = yaml.getConfigurationSection(rawId + ".unlocked");
            if (classes != null) {
                for (String classId : classes.getKeys(false)) {
                    ConfigurationSection nodes = classes.getConfigurationSection(classId);
                    if (nodes == null) continue;
                    Map<String, Held> levels = new HashMap<>();
                    for (String nodeId : nodes.getKeys(false)) {
                        ConfigurationSection entry = nodes.getConfigurationSection(nodeId);
                        if (entry == null) {
                            // Written before paid amounts were tracked: it can
                            // only have come from the panel, which always pays.
                            levels.put(nodeId, new Held(Math.max(1, nodes.getInt(nodeId, 1)),
                                    configuredCost(classId, nodeId)));
                        } else {
                            levels.put(nodeId, new Held(Math.max(1, entry.getInt("level", 1)),
                                    Math.max(0, entry.getInt("paid", 0))));
                        }
                    }
                    progress.unlocked.put(classId.toLowerCase(Locale.ROOT), levels);
                }
            }
            byPlayer.put(playerId, progress);
        }
    }

    private int configuredCost(String classId, String nodeId) {
        return plugin.skillTrees().tree(classId)
                .map(tree -> tree.nodes().get(nodeId))
                .map(SkillTreeLibrary.SkillNode::cost)
                .orElse(0);
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Progress> entry : byPlayer.entrySet()) {
            String base = entry.getKey().toString();
            Progress progress = entry.getValue();
            if (progress.activeClass != null) yaml.set(base + ".class", progress.activeClass);
            yaml.set(base + ".granted", progress.granted);
            yaml.set(base + ".spent", progress.spent);
            for (Map.Entry<String, Map<String, Held>> tree : progress.unlocked.entrySet()) {
                for (Map.Entry<String, Held> node : tree.getValue().entrySet()) {
                    String path = base + ".unlocked." + tree.getKey() + "." + node.getKey();
                    yaml.set(path + ".level", node.getValue().level);
                    yaml.set(path + ".paid", node.getValue().paid);
                }
            }
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save " + FILE_NAME + ": " + exception.getMessage());
        }
    }

    /** One held node: its level, and what was actually paid - a gift refunds nothing. */
    private record Held(int level, int paid) { }

    private static final class Progress {
        private String activeClass;
        /** Points handed out by this plugin, on top of any ClassSkills budget. */
        private int granted;
        private int spent;
        private final Map<String, Map<String, Held>> unlocked = new HashMap<>();
    }
}
