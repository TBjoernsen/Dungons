package nl.riddernix.dungeonforge.skills;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and validates the skill trees authored in skills.yml.
 *
 * <p>Trees and classes are deliberately separate: a class points at a tree by
 * id, so several classes can share one layout today and get four different
 * ones later without any code change. What a node does when unlocked is
 * deliberately absent - that belongs to whatever plugin listens through the
 * API.</p>
 *
 * <p>Positions are cartesian grid units around the root; the older
 * angle/radius form is converted to the same units at load time, so the
 * renderer only ever sees one coordinate system. Connections are no longer a
 * pure tree: a node may require several others ({@code any-of} choosing
 * between ANY-semantics and ALL-semantics), and an edge may bend through
 * {@code routes} corner points.</p>
 */
public final class SkillTreeLibrary {

    private static final String FILE_NAME = "skills.yml";
    /**
     * Must match {@code skills-version} in the bundled skills.yml, exactly
     * like the config's pair of numbers. This exists because the file once
     * silently kept an outdated tree: it is only written when missing, so a
     * redesign in the jar never reached servers that already had one.
     */
    private static final int SKILLS_VERSION = 5;

    private final DungeonForgePlugin plugin;
    private final Map<String, SkillClassEntry> classes = new LinkedHashMap<>();
    private final Map<String, TreeData> trees = new LinkedHashMap<>();

    public SkillTreeLibrary(DungeonForgePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Re-reads skills.yml, writing the bundled default on first run. */
    public void reload() {
        classes.clear();
        trees.clear();
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.isFile()) {
            plugin.saveResource(FILE_NAME, false);
        } else {
            int installed = YamlConfiguration.loadConfiguration(file).getInt("skills-version", 0);
            if (installed < SKILLS_VERSION) {
                File backup = new File(plugin.getDataFolder(),
                        FILE_NAME + ".v" + installed + "." + System.currentTimeMillis() + ".bak");
                try {
                    java.nio.file.Files.copy(file.toPath(), backup.toPath());
                    plugin.saveResource(FILE_NAME, true);
                    plugin.getLogger().info("Replaced outdated " + FILE_NAME + " (version " + installed
                            + ") with version " + SKILLS_VERSION + "; the previous file is backed up as "
                            + backup.getName() + ".");
                } catch (java.io.IOException exception) {
                    plugin.getLogger().severe("Could not back up outdated " + FILE_NAME + ": "
                            + exception.getMessage() + "; keeping the old file.");
                }
            }
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection treeRoot = yaml.getConfigurationSection("trees");
        if (treeRoot != null) {
            for (String treeId : treeRoot.getKeys(false)) {
                ConfigurationSection section = treeRoot.getConfigurationSection(treeId);
                TreeData tree = section == null ? null : readTree(treeId, section);
                if (tree != null) {
                    trees.put(treeId.toLowerCase(Locale.ROOT), tree);
                }
            }
        }

        ConfigurationSection classRoot = yaml.getConfigurationSection("classes");
        if (classRoot != null) {
            for (String classId : classRoot.getKeys(false)) {
                ConfigurationSection section = classRoot.getConfigurationSection(classId);
                if (section == null) {
                    continue;
                }
                String id = classId.toLowerCase(Locale.ROOT);
                String treeId = section.getString("tree", "").toLowerCase(Locale.ROOT);
                TreeData tree = trees.get(treeId);
                if (tree == null) {
                    // The pre-carousel format nested the nodes inside the
                    // class itself; it keeps working so old files still load.
                    tree = readTree(id, section);
                    if (tree != null) {
                        trees.put("inline:" + id, tree);
                        treeId = "inline:" + id;
                    }
                }
                if (tree == null) {
                    plugin.getLogger().warning("Skill class '" + id + "' points at unknown tree '"
                            + section.getString("tree") + "' and is ignored.");
                    continue;
                }
                classes.put(id, new SkillClassEntry(id, section.getString("name", classId),
                        section.getString("description", ""), treeId));
            }
        }
        plugin.getLogger().info("Loaded " + trees.size() + " skill tree(s) for " + classes.size()
                + " class(es): " + String.join(", ", classes.keySet()));
    }

    /** The classes in the order skills.yml declares them - the carousel order. */
    public List<String> classIds() {
        return List.copyOf(classes.keySet());
    }

    public String displayName(String classId) {
        SkillClassEntry entry = classId == null ? null : classes.get(classId.toLowerCase(Locale.ROOT));
        return entry == null ? classId : entry.displayName();
    }

    public String description(String classId) {
        SkillClassEntry entry = classId == null ? null : classes.get(classId.toLowerCase(Locale.ROOT));
        return entry == null ? "" : entry.description();
    }

    /** One class's tree: the shared structure under the class's own name. */
    public Optional<SkillClassTree> tree(String classId) {
        SkillClassEntry entry = classId == null ? null : classes.get(classId.toLowerCase(Locale.ROOT));
        TreeData tree = entry == null ? null : trees.get(entry.treeId());
        return tree == null ? Optional.empty()
                : Optional.of(new SkillClassTree(entry.id(), entry.displayName(), tree.branches(), tree.nodes()));
    }

    /**
     * Whether any configured tree declares this node id.
     *
     * <p>Every tree rather than one player's, because this answers a
     * diagnostic question - "is this id one we could ever know?" - for callers
     * that may ask before a class is set. A false alarm about a real id would
     * be worse than staying quiet about a typo in a class nobody plays.</p>
     */
    public boolean knowsNode(String nodeId) {
        if (nodeId == null) {
            return false;
        }
        for (TreeData tree : trees.values()) {
            if (tree.nodes().containsKey(nodeId)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------

    private TreeData readTree(String treeId, ConfigurationSection section) {
        Map<String, SkillBranch> branches = new LinkedHashMap<>();
        ConfigurationSection branchSection = section.getConfigurationSection("branches");
        if (branchSection != null) {
            for (String branchId : branchSection.getKeys(false)) {
                double[] at = position(branchSection.getConfigurationSection(branchId),
                        branchSection.getDouble(branchId + ".label-angle", 90.0),
                        branchSection.getDouble(branchId + ".label-radius", 4.5));
                branches.put(branchId, new SkillBranch(branchId,
                        branchSection.getString(branchId + ".label", branchId), at[0], at[1]));
            }
        }

        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        ConfigurationSection nodeSection = section.getConfigurationSection("nodes");
        if (nodeSection == null || nodeSection.getKeys(false).isEmpty()) {
            plugin.getLogger().warning("Skill tree '" + treeId + "' has no nodes and is ignored.");
            return null;
        }
        for (String nodeId : nodeSection.getKeys(false)) {
            ConfigurationSection entry = nodeSection.getConfigurationSection(nodeId);
            if (entry == null) {
                continue;
            }
            double[] at = position(entry, entry.getDouble("angle", 90.0), entry.getDouble("radius", 1.0));
            nodes.put(nodeId, new SkillNode(nodeId,
                    entry.getString("name", nodeId),
                    entry.getString("description", ""),
                    Math.max(0, entry.getInt("cost", 1)),
                    Math.clamp(entry.getInt("requires-difficulty", 0), 0, 9),
                    List.copyOf(entry.getStringList("requires")),
                    entry.getBoolean("any-of", false),
                    at[0], at[1],
                    entry.getString("branch", ""),
                    readRoutes(treeId, nodeId, entry.getConfigurationSection("routes"))));
        }

        // A requirement pointing nowhere would render a line into thin air and
        // could never be satisfied, so it is dropped loudly rather than kept.
        for (SkillNode node : List.copyOf(nodes.values())) {
            List<String> valid = new ArrayList<>();
            for (String required : node.requires()) {
                if (nodes.containsKey(required)) {
                    valid.add(required);
                } else {
                    plugin.getLogger().warning("Skill node '" + treeId + ":" + node.id()
                            + "' requires unknown node '" + required + "'; that requirement is ignored.");
                }
            }
            if (valid.size() != node.requires().size()) {
                nodes.put(node.id(), new SkillNode(node.id(), node.name(), node.description(), node.cost(),
                        node.requiresDifficulty(), List.copyOf(valid), node.anyOf(), node.unitX(), node.unitY(),
                        node.branch(), node.routes()));
            }
        }
        if (nodes.values().stream().noneMatch(node -> node.requires().isEmpty())) {
            plugin.getLogger().warning("Skill tree '" + treeId + "' has no root node (one without requires); "
                    + "unlocking can never start.");
        }
        return new TreeData(Map.copyOf(branches), Map.copyOf(nodes));
    }

    /** Cartesian x/y wins; angle/radius is converted so both forms coexist. */
    private static double[] position(ConfigurationSection entry, double fallbackAngle, double fallbackRadius) {
        if (entry != null && entry.contains("x")) {
            return new double[]{entry.getDouble("x"), entry.getDouble("y", 0.0)};
        }
        double radians = Math.toRadians(entry == null ? fallbackAngle : entry.getDouble("angle", fallbackAngle));
        double radius = entry == null ? fallbackRadius : entry.getDouble("radius", fallbackRadius);
        return new double[]{Math.cos(radians) * radius, Math.sin(radians) * radius};
    }

    /** Corner points per prerequisite, as [x, y] pairs in the same grid units. */
    private Map<String, List<double[]>> readRoutes(String treeId, String nodeId, ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, List<double[]>> routes = new LinkedHashMap<>();
        for (String requiredId : section.getKeys(false)) {
            List<double[]> points = new ArrayList<>();
            for (Object raw : section.getList(requiredId, List.of())) {
                if (raw instanceof List<?> pair && pair.size() >= 2
                        && pair.get(0) instanceof Number x && pair.get(1) instanceof Number y) {
                    points.add(new double[]{x.doubleValue(), y.doubleValue()});
                } else {
                    plugin.getLogger().warning("Skill node '" + treeId + ":" + nodeId + "' has a malformed route "
                            + "point for '" + requiredId + "'; expected [x, y].");
                }
            }
            if (!points.isEmpty()) {
                routes.put(requiredId, List.copyOf(points));
            }
        }
        return Map.copyOf(routes);
    }

    // ------------------------------------------------------------------

    /**
     * One node. {@code anyOf} selects the unlock rule for multi-prerequisite
     * nodes: any single requirement unlocked (paths that rejoin) versus all of
     * them (a true gate).
     *
     * <p>{@code requiresDifficulty} is the lowest dungeon difficulty a player
     * must have cleared to buy it, or 0 for no such gate. The rule is tree
     * data; the answer is not, and comes from ClassSkills.</p>
     */
    public record SkillNode(String id, String name, String description, int cost, int requiresDifficulty,
                            List<String> requires, boolean anyOf, double unitX, double unitY, String branch,
                            Map<String, List<double[]>> routes) {
    }

    /** A branch exists for its label; nodes reference it cosmetically. */
    public record SkillBranch(String id, String label, double unitX, double unitY) {
    }

    /** One class's complete view: the shared structure under its own name. */
    public record SkillClassTree(String id, String displayName, Map<String, SkillBranch> branches,
                                 Map<String, SkillNode> nodes) {

        /** The node every chain starts from: the first one without requirements. */
        public Optional<SkillNode> rootNode() {
            return nodes.values().stream().filter(node -> node.requires().isEmpty()).findFirst();
        }
    }

    private record SkillClassEntry(String id, String displayName, String description, String treeId) {
    }

    private record TreeData(Map<String, SkillBranch> branches, Map<String, SkillNode> nodes) {
    }
}
