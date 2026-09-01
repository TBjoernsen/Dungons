package nl.riddernix.dungeonplugin.skills

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

/**
 * Loads and validates the skill trees authored in skills.yml.
 *
 * Trees and classes are deliberately separate: a class points at a tree by
 * id, so several classes can share one layout today and get four different
 * ones later without any code change. The stat effect a node carries lives in
 * skills.yml too since the merge - the class layer reads it from here rather
 * than keeping a second catalogue.
 *
 * Positions are cartesian grid units around the root; the older angle/radius
 * form is converted to the same units at load time, so the renderer only ever
 * sees one coordinate system. Connections are no longer a pure tree: a node
 * may require several others (`any-of` choosing between ANY-semantics and
 * ALL-semantics), and an edge may bend through `routes` corner points.
 */
class SkillTreeLibrary(private val plugin: DungeonPlugin) {

    private val classes = LinkedHashMap<String, SkillClassEntry>()
    private val trees = LinkedHashMap<String, TreeData>()

    init {
        reload()
    }

    /** Re-reads skills.yml, writing the bundled default on first run. */
    fun reload() {
        classes.clear()
        trees.clear()
        val file = File(plugin.dataFolder, FILE_NAME)
        if (!file.isFile) {
            plugin.saveResource(FILE_NAME, false)
        } else {
            val installed = YamlConfiguration.loadConfiguration(file).getInt("skills-version", 0)
            if (installed < SKILLS_VERSION) {
                val backup = File(plugin.dataFolder,
                    "$FILE_NAME.v$installed.${System.currentTimeMillis()}.bak")
                try {
                    Files.copy(file.toPath(), backup.toPath())
                    plugin.saveResource(FILE_NAME, true)
                    plugin.logger.info("Replaced outdated $FILE_NAME (version $installed" +
                        ") with version $SKILLS_VERSION; the previous file is backed up as ${backup.name}.")
                } catch (exception: IOException) {
                    plugin.logger.severe("Could not back up outdated $FILE_NAME: " +
                        "${exception.message}; keeping the old file.")
                }
            }
        }
        val yaml = YamlConfiguration.loadConfiguration(file)

        val treeRoot = yaml.getConfigurationSection("trees")
        if (treeRoot != null) {
            for (treeId in treeRoot.getKeys(false)) {
                val section = treeRoot.getConfigurationSection(treeId)
                val tree = section?.let { readTree(treeId, it) }
                if (tree != null) {
                    trees[treeId.lowercase(Locale.ROOT)] = tree
                }
            }
        }

        val classRoot = yaml.getConfigurationSection("classes")
        if (classRoot != null) {
            for (classId in classRoot.getKeys(false)) {
                val section = classRoot.getConfigurationSection(classId) ?: continue
                val id = classId.lowercase(Locale.ROOT)
                var treeId = section.getString("tree", "")!!.lowercase(Locale.ROOT)
                var tree = trees[treeId]
                if (tree == null) {
                    // The pre-carousel format nested the nodes inside the
                    // class itself; it keeps working so old files still load.
                    tree = readTree(id, section)
                    if (tree != null) {
                        trees["inline:$id"] = tree
                        treeId = "inline:$id"
                    }
                }
                if (tree == null) {
                    plugin.logger.warning("Skill class '$id' points at unknown tree '" +
                        "${section.getString("tree")}' and is ignored.")
                    continue
                }
                classes[id] = SkillClassEntry(id, section.getString("name", classId)!!,
                    section.getString("description", "")!!, treeId)
            }
        }
        plugin.logger.info("Loaded ${trees.size} skill tree(s) for ${classes.size}" +
            " class(es): ${classes.keys.joinToString(", ")}")
    }

    /** The classes in the order skills.yml declares them - the carousel order. */
    fun classIds(): List<String> = classes.keys.toList()

    fun displayName(classId: String?): String? {
        val entry = classId?.let { classes[it.lowercase(Locale.ROOT)] }
        return entry?.displayName ?: classId
    }

    fun description(classId: String?): String {
        val entry = classId?.let { classes[it.lowercase(Locale.ROOT)] }
        return entry?.description ?: ""
    }

    /** One class's tree: the shared structure under the class's own name. */
    fun tree(classId: String?): SkillClassTree? {
        val entry = classId?.let { classes[it.lowercase(Locale.ROOT)] } ?: return null
        val tree = trees[entry.treeId] ?: return null
        return SkillClassTree(entry.id, entry.displayName, tree.branches, tree.nodes)
    }

    /**
     * Whether any configured tree declares this node id.
     *
     * Every tree rather than one player's, because this answers a diagnostic
     * question - "is this id one we could ever know?" - for callers that may
     * ask before a class is set. A false alarm about a real id would be worse
     * than staying quiet about a typo in a class nobody plays.
     */
    fun knowsNode(nodeId: String?): Boolean {
        if (nodeId == null) {
            return false
        }
        for (tree in trees.values) {
            if (nodeId in tree.nodes) {
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------

    private fun readTree(treeId: String, section: ConfigurationSection): TreeData? {
        val branches = LinkedHashMap<String, SkillBranch>()
        val branchSection = section.getConfigurationSection("branches")
        if (branchSection != null) {
            for (branchId in branchSection.getKeys(false)) {
                val at = position(branchSection.getConfigurationSection(branchId),
                    branchSection.getDouble("$branchId.label-angle", 90.0),
                    branchSection.getDouble("$branchId.label-radius", 4.5))
                branches[branchId] = SkillBranch(branchId,
                    branchSection.getString("$branchId.label", branchId)!!, at[0], at[1])
            }
        }

        val nodes = LinkedHashMap<String, SkillNode>()
        val nodeSection = section.getConfigurationSection("nodes")
        if (nodeSection == null || nodeSection.getKeys(false).isEmpty()) {
            plugin.logger.warning("Skill tree '$treeId' has no nodes and is ignored.")
            return null
        }
        for (nodeId in nodeSection.getKeys(false)) {
            val entry = nodeSection.getConfigurationSection(nodeId) ?: continue
            val at = position(entry, entry.getDouble("angle", 90.0), entry.getDouble("radius", 1.0))
            nodes[nodeId] = SkillNode(nodeId,
                entry.getString("name", nodeId)!!,
                entry.getString("description", "")!!,
                maxOf(0, entry.getInt("cost", 1)),
                entry.getInt("requires-difficulty", 0).coerceIn(0, 9),
                entry.getStringList("requires").toList(),
                entry.getBoolean("any-of", false),
                at[0], at[1],
                entry.getString("branch", "")!!,
                readRoutes(treeId, nodeId, entry.getConfigurationSection("routes")),
                entry.getString("effect.stat", "") ?: "",
                entry.getDouble("effect.value", 0.0),
                maxOf(0, entry.getInt("effect.passive-rank", 0)))
        }

        // A requirement pointing nowhere would render a line into thin air
        // and could never be satisfied, so it is dropped loudly rather than
        // kept.
        for (node in nodes.values.toList()) {
            val valid = ArrayList<String>()
            for (required in node.requires) {
                if (required in nodes) {
                    valid.add(required)
                } else {
                    plugin.logger.warning("Skill node '$treeId:${node.id}'" +
                        " requires unknown node '$required'; that requirement is ignored.")
                }
            }
            if (valid.size != node.requires.size) {
                nodes[node.id] = node.copy(requires = valid.toList())
            }
        }
        if (nodes.values.none { it.requires.isEmpty() }) {
            plugin.logger.warning("Skill tree '$treeId' has no root node (one without requires); " +
                "unlocking can never start.")
        }
        return TreeData(branches.toMap(), nodes.toMap())
    }

    /** Corner points per prerequisite, as [x, y] pairs in the same grid units. */
    private fun readRoutes(treeId: String, nodeId: String, section: ConfigurationSection?): Map<String, List<DoubleArray>> {
        if (section == null) {
            return emptyMap()
        }
        val routes = LinkedHashMap<String, List<DoubleArray>>()
        for (requiredId in section.getKeys(false)) {
            val points = ArrayList<DoubleArray>()
            for (raw in section.getList(requiredId, emptyList<Any>())!!) {
                if (raw is List<*> && raw.size >= 2 && raw[0] is Number && raw[1] is Number) {
                    points.add(doubleArrayOf((raw[0] as Number).toDouble(), (raw[1] as Number).toDouble()))
                } else {
                    plugin.logger.warning("Skill node '$treeId:$nodeId' has a malformed route " +
                        "point for '$requiredId'; expected [x, y].")
                }
            }
            if (points.isNotEmpty()) {
                routes[requiredId] = points.toList()
            }
        }
        return routes.toMap()
    }

    // ------------------------------------------------------------------

    /**
     * One node. [anyOf] selects the unlock rule for multi-prerequisite nodes:
     * any single requirement unlocked (paths that rejoin) versus all of them
     * (a true gate).
     *
     * [requiresDifficulty] is the lowest dungeon difficulty a player must
     * have cleared to buy it, or 0 for no such gate.
     *
     * [effectStat], [effectValue] and [effectPassiveRank] carry the node's
     * gameplay effect - an attack/health/armor bonus or a signature passive
     * rank - authored beside the node itself so the tree and its effects can
     * never drift apart.
     */
    data class SkillNode(
        val id: String, val name: String, val description: String, val cost: Int, val requiresDifficulty: Int,
        val requires: List<String>, val anyOf: Boolean, val unitX: Double, val unitY: Double, val branch: String,
        val routes: Map<String, List<DoubleArray>>,
        val effectStat: String = "", val effectValue: Double = 0.0, val effectPassiveRank: Int = 0
    )

    /** A branch exists for its label; nodes reference it cosmetically. */
    data class SkillBranch(val id: String, val label: String, val unitX: Double, val unitY: Double)

    /** One class's complete view: the shared structure under its own name. */
    class SkillClassTree(val id: String, val displayName: String,
                         val branches: Map<String, SkillBranch>, val nodes: Map<String, SkillNode>) {

        /** The node every chain starts from: the first one without requirements. */
        fun rootNode(): SkillNode? = nodes.values.firstOrNull { it.requires.isEmpty() }
    }

    private class SkillClassEntry(val id: String, val displayName: String, val description: String, val treeId: String)

    private class TreeData(val branches: Map<String, SkillBranch>, val nodes: Map<String, SkillNode>)

    companion object {
        private const val FILE_NAME = "skills.yml"

        /**
         * Must match `skills-version` in the bundled skills.yml, exactly like
         * the config's pair of numbers. This exists because the file once
         * silently kept an outdated tree: it is only written when missing, so
         * a redesign in the jar never reached servers that already had one.
         * Version 6 is the merge: per-node stat effects and universal
         * requires-difficulty gates moved into the file.
         */
        private const val SKILLS_VERSION = 6

        /** Cartesian x/y wins; angle/radius is converted so both forms coexist. */
        private fun position(entry: ConfigurationSection?, fallbackAngle: Double, fallbackRadius: Double): DoubleArray {
            if (entry != null && entry.contains("x")) {
                return doubleArrayOf(entry.getDouble("x"), entry.getDouble("y", 0.0))
            }
            val radians = Math.toRadians(entry?.getDouble("angle", fallbackAngle) ?: fallbackAngle)
            val radius = entry?.getDouble("radius", fallbackRadius) ?: fallbackRadius
            return doubleArrayOf(Math.cos(radians) * radius, Math.sin(radians) * radius)
        }
    }
}
