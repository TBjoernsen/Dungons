package dev.thorb.classskills.api

import org.bukkit.entity.Player

/**
 * Read-only public API for plugins that need to react to ClassSkills progression.
 * Obtain it through Bukkit's ServicesManager; do not construct an implementation.
 */
interface ClassSkillsApi {
    /** Returns a point-in-time view of this player's currently selected class profile. */
    fun getPlayerProfile(player: Player): ClassSkillsPlayerProfile

    /** Current unspent skill points for the active class profile. */
    fun getAvailableSkillPoints(player: Player): Int

    /** Points committed to DungeonForge-owned nodes for the active class. */
    fun getSpentSkillPoints(player: Player): Int

    /** The active class profile's current level-based point budget. */
    fun getTotalSkillPointBudget(player: Player): Int

    /** Whether the specified dungeon difficulty is available to this player. */
    fun hasUnlockedDifficulty(player: Player, difficulty: Int): Boolean

    /** True when DungeonForge reports the supplied ClassSkills node id for the active class. */
    fun hasPurchasedNode(player: Player, nodeId: String): Boolean
}

/** Immutable data returned by [ClassSkillsApi]. Values are safe to retain but will not update live. */
data class ClassSkillsPlayerProfile(
    val classId: String?,
    val level: Int,
    val maxLevel: Int,
    val experience: Int,
    val experienceToNextLevel: Int,
    val availableSkillPoints: Int,
    val unlockedDifficulty: Int,
    val signatureRank: Int,
    val attackBonus: Double,
    val maxHealthBonus: Double,
    val armorBonus: Double,
    val purchasedNodeIds: Set<String>
)
