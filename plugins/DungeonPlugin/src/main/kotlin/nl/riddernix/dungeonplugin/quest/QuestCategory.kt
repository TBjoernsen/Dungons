package nl.riddernix.dungeonplugin.quest

import java.util.Locale

/**
 * The three quest tracks. Each holds exactly [SLOTS] quests at a time and has
 * its own refresh rule:
 *
 * - [DAILY] regenerates every midnight in the configured zone.
 * - [WEEKLY] regenerates every Friday at midnight in the configured zone.
 * - [GENERAL] never regenerates on a timer; its set is rolled once and then
 *   left alone. Whether it ever refreshes at all is a later decision.
 *
 * When a refreshing category rolls a new set, every player's progress on that
 * category is wiped, because the quests behind the slots have changed.
 * [GENERAL] progress is kept indefinitely.
 */
enum class QuestCategory(val id: String, val displayName: String, val refreshing: Boolean) {

    DAILY("daily", "Daily", true),
    WEEKLY("weekly", "Weekly", true),
    GENERAL("general", "General", false);

    companion object {

        /** Quests held per category at any one time. */
        const val SLOTS = 4

        fun fromId(raw: String?): QuestCategory? {
            val key = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.id == key }
        }
    }
}
