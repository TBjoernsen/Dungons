package dev.thorb.classskills.model

import org.bukkit.Material

enum class ClassType(
    val displayName: String,
    val tabPrefix: String,
    val icon: Material,
    val weaponDescription: String,
    val passiveName: String,
    /** Stable node suffix; display names may change without invalidating bought nodes. */
    val signatureNodeKey: String = passiveName.lowercase()
) {
    WARRIOR("Warrior", "§c[Warrior] §r", Material.IRON_SWORD, "swords", "Rage"),
    ARCHER("Archer", "§a[Archer] §r", Material.BOW, "bows", "Focus"),
    PALADIN("Paladin", "§6[Paladin] §r", Material.IRON_AXE, "axes", "Taunt", "judgment"),
    MAGE("Mage", "§5[Mage] §r", Material.BLAZE_ROD, "custom staffs", "Arcane Charge");

    companion object {
        fun fromInput(value: String?): ClassType? = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true) || it.displayName.equals(value, ignoreCase = true)
        }
    }
}

enum class StatType(val displayName: String, val icon: Material) {
    ATTACK("Attack", Material.IRON_SWORD),
    HEALTH("Max Health", Material.GOLDEN_APPLE),
    ARMOR("Armor", Material.IRON_CHESTPLATE)
}

enum class NodeKind {
    STAT,
    SIGNATURE_UPGRADE
}

data class SkillNode(
    val id: String,
    val classType: ClassType,
    val tier: Int,
    val title: String,
    val cost: Int,
    val kind: NodeKind,
    val statType: StatType? = null,
    val value: Double = 0.0,
    val passiveRank: Int? = null
)

data class PlayerSkillData(
    var classType: ClassType? = null,
    var level: Int = 1,
    var experience: Int = 0,
    var availablePoints: Int = 2,
    var unlockedDifficulty: Int = 1,
    /**
     * Volatile projection of DungeonForge's active-class node state. This is never
     * persisted and must only be refreshed from DungeonForge's v5 query API.
     */
    val derivedNodeIds: MutableSet<String> = linkedSetOf(),
    var rage: Double = 0.0,
    var rageActiveUntil: Long = 0L,
    /** Runtime-only combat timestamp; Rage begins decaying after a period without damage dealt or received. */
    var lastRageCombatAt: Long = System.currentTimeMillis(),
    var focus: Int = 0,
    // Legacy storage key retained for existing player files; it now tracks Taunt damage.
    var judgment: Double = 0.0,
    var mana: Double = 0.0,
    var pointEconomyVersion: Int = 2,
    var treeLayoutVersion: Int = 2,
    /** Inactive class profiles. The currently selected class stays in the top-level fields. */
    val classProfiles: MutableMap<ClassType, ClassProgress> = linkedMapOf()
)

data class ClassProgress(
    val level: Int,
    val experience: Int,
    val availablePoints: Int,
    val unlockedDifficulty: Int
)

/**
 * The permanent three-branch tree has 40 purchasable nodes per class: Weapon (11),
 * Ability (15, including the Rank-I root), and Survival (14). Nodes remain individually independent; their only
 * requirement is their assigned dungeon-difficulty tier.
 */
class SkillTreeCatalog {
    val nodes: List<SkillNode> = buildList {
        for (classType in ClassType.entries) {
            addWeaponBranch(classType)
            addAbilityBranch(classType)
            addSurvivalBranch(classType)
        }
    }

    val byId: Map<String, SkillNode> = nodes.associateBy { it.id }

    fun forClassAndTier(classType: ClassType, tier: Int): List<SkillNode> =
        nodes.filter { it.classType == classType && it.tier == tier }

    fun prerequisitesFor(@Suppress("UNUSED_PARAMETER") node: SkillNode): List<String> = emptyList()

    fun hasPrerequisites(data: PlayerSkillData, node: SkillNode): Boolean =
        prerequisitesFor(node).all(data.derivedNodeIds::contains)

    fun purchasedDependents(data: PlayerSkillData, nodeId: String): List<SkillNode> =
        nodes.filter { it.id in data.derivedNodeIds && nodeId in prerequisitesFor(it) }

    fun signatureRank(data: PlayerSkillData): Int =
        signatureRank(data.classType, data.unlockedDifficulty, data.derivedNodeIds)

    fun signatureRank(
        classType: ClassType?,
        @Suppress("UNUSED_PARAMETER") unlockedDifficulty: Int,
        nodeIds: Collection<String>
    ): Int {
        if (classType == null) return 0
        return nodeIds.mapNotNull { nodeId ->
            byId[nodeId]
                ?.takeIf { it.classType == classType && it.kind == NodeKind.SIGNATURE_UPGRADE }
                ?.passiveRank
        }.maxOrNull() ?: 0
    }

    fun spentPoints(data: PlayerSkillData): Int = spentPoints(data.derivedNodeIds)

    fun spentPoints(nodeIds: Collection<String>): Int = nodeIds.sumOf { byId[it]?.cost ?: 0 }

    fun totalFor(data: PlayerSkillData, stat: StatType): Double = totalFor(data.derivedNodeIds, stat)

    fun totalFor(nodeIds: Collection<String>, stat: StatType): Double = nodeIds.sumOf {
        val node = byId[it]
        if (node?.kind == NodeKind.STAT && node.statType == stat) node.value else 0.0
    }

    private fun MutableList<SkillNode>.addWeaponBranch(classType: ClassType) {
        val tiers = listOf(1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9)
        val values = listOf(1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 3.0, 3.0, 3.0)
        tiers.indices.forEach { index ->
            add(statNode(classType, "weapon", index + 1, tiers[index], "Weapon Training ${index + 1}", StatType.ATTACK, values[index]))
        }
    }

    private fun MutableList<SkillNode>.addAbilityBranch(classType: ClassType) {
        add(SkillNode(
            "${classType.name.lowercase()}_ability_00",
            classType,
            1,
            "${classType.passiveName} 1",
            0,
            NodeKind.SIGNATURE_UPGRADE,
            passiveRank = 1
        ))
        (2..6).forEach { rank ->
            val tier = rank + 1
            add(SkillNode("${classType.name.lowercase()}_ability_${(rank - 1).toString().padStart(2, '0')}", classType, tier, "${classType.passiveName} $rank", rank + 2, NodeKind.SIGNATURE_UPGRADE, passiveRank = rank))
        }
        val support = listOf(
            Triple(StatType.ATTACK, 1.0, 3), Triple(StatType.ATTACK, 1.0, 3), Triple(StatType.ATTACK, 2.0, 4),
            Triple(StatType.ATTACK, 2.0, 5), Triple(StatType.ATTACK, 3.0, 6), Triple(StatType.HEALTH, 2.0, 7),
            Triple(StatType.ARMOR, 2.0, 8), Triple(StatType.HEALTH, 2.0, 9), Triple(StatType.ARMOR, 2.0, 9)
        )
        support.forEachIndexed { index, (stat, value, tier) ->
            add(statNode(classType, "ability", index + 6, tier, "${classType.passiveName} Support ${index + 1}", stat, value))
        }
    }

    private fun MutableList<SkillNode>.addSurvivalBranch(classType: ClassType) {
        val tiers = listOf(1, 1, 2, 2, 3, 3, 4, 5, 6, 7, 8, 9, 9, 9)
        val values = listOf(1.0, 1.0, 2.0, 2.0, 2.0, 3.0, 3.0)
        (0 until 14).forEach { index ->
            val stat = if (index % 2 == 0) StatType.HEALTH else StatType.ARMOR
            add(statNode(classType, "survival", index + 1, tiers[index], "Survival ${index + 1}", stat, values[index / 2]))
        }
    }

    private fun statNode(classType: ClassType, branch: String, index: Int, tier: Int, title: String, stat: StatType, value: Double) =
        SkillNode("${classType.name.lowercase()}_${branch}_${index.toString().padStart(2, '0')}", classType, tier, title, tier, NodeKind.STAT, stat, value)
}
