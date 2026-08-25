package dev.thorb.classskills.api

import org.bukkit.entity.Player

/** Public bridge for the separate dungeon plugin. */
interface DungeonSkillService {
    /** True when the player's current ClassSkills level permits this dungeon difficulty. */
    fun canEnterDungeonDifficulty(player: Player, difficulty: Int): Boolean

    /** Raises a player's highest unlocked dungeon/skill-tree difficulty; it never lowers it. */
    fun unlockDungeonDifficulty(player: Player, difficulty: Int)

    /** Awards one dungeon mob kill using this plugin's provisional configured XP and shared drop table. */
    fun awardDungeonMobKill(player: Player, difficulty: Int)

    /** Lets the dungeon plugin own the future XP curve while this plugin owns levels and points. */
    fun grantDungeonExperience(player: Player, amount: Int)
}
