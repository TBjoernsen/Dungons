package nl.riddernix.dungeonplugin.quest

import java.util.Locale

/**
 * How progress on a quest is measured. Deliberately tiny for now: two generic
 * counters that are trivial to trigger and test, so the whole flow can be
 * verified end to end before real objective types are designed.
 *
 * Real objective types (mine X ore, reach a location, clear a dungeon...) slot
 * in here later; nothing structural changes when they do.
 */
enum class QuestObjective(val id: String) {

    /** +1 per mob a player kills, of any type. */
    KILL_ANY("kill_any"),

    /** +N per hit, where N is the damage the player dealt that hit (rounded). */
    DEAL_DAMAGE("deal_damage");

    companion object {
        fun fromId(raw: String?): QuestObjective? {
            val key = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.id == key }
        }
    }
}

/**
 * One quest template: the content, independent of any player. Progress and
 * claim state live per player in [QuestManager], never here.
 *
 * This is the structural contract the spec asks for - a title, a description
 * / objective, a numeric requirement, and a reward - so placeholder quests can
 * be swapped for real ones without touching the system around them. [reward]
 * is a plain string for now; it becomes something richer (items, XP, currency)
 * when rewards are designed.
 */
data class QuestDefinition(
    val id: String,
    val title: String,
    val description: String,
    val objective: QuestObjective,
    val required: Int,
    val reward: String
) {
    /** Progress clamped into 0..[required], for display and completion checks. */
    fun clamp(counter: Int): Int = counter.coerceIn(0, required)

    fun isComplete(counter: Int): Boolean = counter >= required
}
