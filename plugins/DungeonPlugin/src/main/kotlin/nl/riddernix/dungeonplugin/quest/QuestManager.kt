package nl.riddernix.dungeonplugin.quest

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.EnumMap
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The quest layer's authority: which quests each category currently holds,
 * when each was last refreshed, and every player's progress and claim state.
 *
 * Ownership mirrors the rest of the plugin - one file, in-memory maps, saved
 * on change. [QuestConfig] owns the *content* (the pool, menu layout, timing);
 * this class owns the *state* and writes it to `quest-data.yml`.
 *
 * Refresh model:
 * - [QuestCategory.DAILY] / [QuestCategory.WEEKLY] regenerate on their zone
 *   boundary. [checkScheduledRefresh] is called on a timer *and* once at
 *   startup, so a boundary missed while the server was offline is caught up
 *   immediately rather than waited out.
 * - A refreshing category wipes every player's progress for it when it rolls.
 * - [QuestCategory.GENERAL] only ever rolls on an explicit [forceRefresh].
 */
class QuestManager(private val plugin: DungeonPlugin) {

    /** One player's progress on one slot of one category. */
    class SlotProgress(
        var definitionId: String,
        var counter: Int = 0,
        var claimed: Boolean = false
    )

    enum class QuestState { IN_PROGRESS, COMPLETE_UNCLAIMED, CLAIMED, MISSING }

    enum class ClaimResult { CLAIMED, NOT_COMPLETE, ALREADY_CLAIMED, MISSING }

    private val config get() = plugin.questConfig
    private val file = File(plugin.dataFolder, FILE_NAME)

    /** slot index -> definition id, one list of [QuestCategory.SLOTS] per category. */
    private val sets = EnumMap<QuestCategory, MutableList<String>>(QuestCategory::class.java)
    private val lastRefresh = EnumMap<QuestCategory, Long>(QuestCategory::class.java)
    private val progress = HashMap<UUID, EnumMap<QuestCategory, Array<SlotProgress?>>>()

    /**
     * Set by the high-frequency path ([addProgress] on a hit that isn't a
     * completion) instead of writing the file on every mob. [flushIfDirty] is
     * called on the quest timer and on shutdown. Rare, meaningful changes
     * (a completion, a claim, a refresh) still save immediately.
     */
    private var dirty = false

    init {
        load()
        // Roll anything that has never been generated (fresh install), then
        // catch up any boundary crossed while the server was down.
        for (category in QuestCategory.entries) {
            if (sets[category].isNullOrEmpty() || sets[category]!!.all { it.isEmpty() }) {
                roll(category, stamp = true)
            }
        }
        checkScheduledRefresh()
        save()
    }

    // ------------------------------------------------------------------
    //  Queries
    // ------------------------------------------------------------------

    /** The definition in a slot, or null if the stored id is no longer in the pool. */
    fun definition(category: QuestCategory, slot: Int): QuestDefinition? {
        if (slot !in 0 until QuestCategory.SLOTS) return null
        val id = sets[category]?.getOrNull(slot).orEmpty()
        if (id.isEmpty()) return null
        return config.pool(category).firstOrNull { it.id == id }
    }

    /** All [QuestCategory.SLOTS] definitions for a category, nulls where a slot is unresolved. */
    fun definitions(category: QuestCategory): List<QuestDefinition?> =
        (0 until QuestCategory.SLOTS).map { definition(category, it) }

    fun lastRefreshMillis(category: QuestCategory): Long = lastRefresh[category] ?: 0L

    fun counter(playerId: UUID, category: QuestCategory, slot: Int): Int =
        slotOrNull(playerId, category, slot)?.counter ?: 0

    fun state(playerId: UUID, category: QuestCategory, slot: Int): QuestState {
        val definition = definition(category, slot) ?: return QuestState.MISSING
        val entry = slotOrNull(playerId, category, slot)
        val count = entry?.counter ?: 0
        return when {
            entry?.claimed == true -> QuestState.CLAIMED
            definition.isComplete(count) -> QuestState.COMPLETE_UNCLAIMED
            else -> QuestState.IN_PROGRESS
        }
    }

    // ------------------------------------------------------------------
    //  Progress and claiming
    // ------------------------------------------------------------------

    /**
     * Feeds an objective event into every matching, unfinished quest the
     * player currently holds - across all three categories. Called from the
     * placeholder objective listener.
     */
    fun addProgress(player: Player, objective: QuestObjective, amount: Int) {
        if (amount <= 0) return
        var touched = false
        var completedOne = false
        for (category in QuestCategory.entries) {
            for (slot in 0 until QuestCategory.SLOTS) {
                val definition = definition(category, slot) ?: continue
                if (definition.objective != objective) continue
                val entry = slot(player.uniqueId, category, slot)
                if (entry.claimed || definition.isComplete(entry.counter)) continue
                val before = entry.counter
                entry.counter = definition.clamp(entry.counter + amount)
                if (entry.counter == before) continue
                touched = true
                if (definition.isComplete(entry.counter)) {
                    completedOne = true
                    player.sendMessage("§6§lQuest complete! §r§e${definition.title} §7(${category.displayName})")
                    player.sendMessage("§7Claim your reward in §f/quests§7.")
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f)
                }
            }
        }
        if (touched) {
            // Plain counter ticks are batched to the timer; a completion is
            // worth writing to disk now.
            if (completedOne) save() else dirty = true
            plugin.questMenu.refreshIfViewing(player)
        }
    }

    /** Writes pending [addProgress] counter changes. Called on the quest timer and on shutdown. */
    fun flushIfDirty() {
        if (dirty) save()
    }

    fun claim(player: Player, category: QuestCategory, slot: Int): ClaimResult {
        val definition = definition(category, slot) ?: return ClaimResult.MISSING
        val entry = slot(player.uniqueId, category, slot)
        if (entry.claimed) return ClaimResult.ALREADY_CLAIMED
        if (!definition.isComplete(entry.counter)) return ClaimResult.NOT_COMPLETE
        entry.claimed = true
        save()
        // Placeholder reward: announce it. Real rewards (items, XP, currency)
        // are handed out here once designed.
        player.sendMessage("§aReward claimed: §f${definition.reward.ifBlank { "(placeholder)" }}")
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f)
        plugin.questMenu.refreshIfViewing(player)
        return ClaimResult.CLAIMED
    }

    // ------------------------------------------------------------------
    //  Refresh
    // ------------------------------------------------------------------

    /**
     * The scheduled path: for each timed category, roll a new set if the most
     * recent zone boundary is newer than its last refresh. Safe to call as
     * often as wanted - it is a no-op until a boundary actually passes.
     */
    fun checkScheduledRefresh() {
        val now = ZonedDateTime.now(config.zone())
        for (category in QuestCategory.entries) {
            if (!category.refreshing) continue
            val boundary = mostRecentBoundary(category, now) ?: continue
            if (lastRefreshMillis(category) < boundary.toInstant().toEpochMilli()) {
                performRefresh(category, "scheduled")
            }
        }
    }

    /** Admin path: roll a category now, whatever its timer says. Works on [QuestCategory.GENERAL] too. */
    fun forceRefresh(category: QuestCategory) = performRefresh(category, "forced")

    private fun performRefresh(category: QuestCategory, cause: String) {
        roll(category, stamp = true)
        // A refreshing category swaps the quests behind its slots, so every
        // player's progress on it is now meaningless and is wiped. General
        // keeps its progress unless an admin forced the roll.
        if (category.refreshing || cause == "forced") {
            for (byCategory in progress.values) {
                byCategory.remove(category)
            }
        }
        save()
        plugin.logger.info("Quest category '${category.id}' refreshed ($cause): " +
            "${sets[category]?.joinToString(", ").orEmpty()}")
        for (online in plugin.server.onlinePlayers) {
            plugin.questMenu.refreshIfViewing(online)
        }
    }

    /** Picks [QuestCategory.SLOTS] definition ids from the pool into [sets]. */
    private fun roll(category: QuestCategory, stamp: Boolean) {
        val ids = config.pool(category).map { it.id }
        val picked = ArrayList<String>()
        if (ids.isNotEmpty()) {
            // General keeps file order for a stable one-off set; the timed
            // categories draw at random.
            val ordered = if (category == QuestCategory.GENERAL) ids else ids.shuffled()
            for (id in ordered) {
                if (picked.size >= QuestCategory.SLOTS) break
                picked.add(id)
            }
            // Pool smaller than a full set: repeat to fill the slots rather
            // than leave holes, and say so.
            if (picked.size < QuestCategory.SLOTS) {
                plugin.logger.warning("quests.yml pool.${category.id} has only ${ids.size} quest(s); " +
                    "need ${QuestCategory.SLOTS}. Slots will repeat until the pool is filled out.")
                var i = 0
                while (picked.size < QuestCategory.SLOTS) {
                    picked.add(ids[i % ids.size]); i++
                }
            }
        } else {
            plugin.logger.warning("quests.yml pool.${category.id} is empty; that category will show no quests.")
            repeat(QuestCategory.SLOTS) { picked.add("") }
        }
        // Highest requirement to the left, so the row reads as a clean
        // descending gradient rather than a random jumble.
        val required = config.pool(category).associate { it.id to it.required }
        picked.sortByDescending { required[it] ?: 0 }
        sets[category] = picked
        if (stamp) lastRefresh[category] = System.currentTimeMillis()
    }

    /** Most recent daily midnight / Friday midnight in the configured zone, at or before [now]. */
    private fun mostRecentBoundary(category: QuestCategory, now: ZonedDateTime): ZonedDateTime? = when (category) {
        QuestCategory.DAILY -> now.toLocalDate().atStartOfDay(now.zone)
        QuestCategory.WEEKLY -> {
            val friday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))
            val start = friday.atStartOfDay(now.zone)
            if (start.isAfter(now)) start.minusWeeks(1) else start
        }
        QuestCategory.GENERAL -> null
    }

    /** First daily midnight / Friday midnight in the configured zone strictly after now; null for [QuestCategory.GENERAL]. */
    fun nextRefreshMillis(category: QuestCategory): Long? {
        if (!category.refreshing) return null
        val now = ZonedDateTime.now(config.zone())
        val next = when (category) {
            QuestCategory.DAILY -> now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            QuestCategory.WEEKLY -> now.toLocalDate()
                .with(TemporalAdjusters.next(DayOfWeek.FRIDAY)).atStartOfDay(now.zone)
            QuestCategory.GENERAL -> return null
        }
        return next.toInstant().toEpochMilli()
    }

    // ------------------------------------------------------------------
    //  Per-player storage helpers
    // ------------------------------------------------------------------

    private fun slot(playerId: UUID, category: QuestCategory, slot: Int): SlotProgress {
        val byCategory = progress.getOrPut(playerId) { EnumMap(QuestCategory::class.java) }
        val slots = byCategory.getOrPut(category) { arrayOfNulls(QuestCategory.SLOTS) }
        return slots[slot] ?: SlotProgress(sets[category]?.getOrNull(slot).orEmpty()).also { slots[slot] = it }
    }

    private fun slotOrNull(playerId: UUID, category: QuestCategory, slot: Int): SlotProgress? =
        progress[playerId]?.get(category)?.getOrNull(slot)

    // ------------------------------------------------------------------
    //  Persistence
    // ------------------------------------------------------------------

    private fun load() {
        sets.clear(); lastRefresh.clear(); progress.clear()
        if (!file.isFile) return
        val yaml = YamlConfiguration.loadConfiguration(file)

        for (category in QuestCategory.entries) {
            val base = "sets.${category.id}"
            val stored = yaml.getStringList("$base.slots")
            if (stored.isNotEmpty()) {
                val slots = MutableList(QuestCategory.SLOTS) { stored.getOrNull(it).orEmpty() }
                sets[category] = slots
            }
            if (yaml.contains("$base.last-refresh")) {
                lastRefresh[category] = yaml.getLong("$base.last-refresh")
            }
        }

        val players = yaml.getConfigurationSection("players") ?: return
        for (rawId in players.getKeys(false)) {
            val playerId = try {
                UUID.fromString(rawId)
            } catch (ex: IllegalArgumentException) {
                plugin.logger.warning("Ignoring invalid player id '$rawId' in $FILE_NAME.")
                continue
            }
            val byCategory = EnumMap<QuestCategory, Array<SlotProgress?>>(QuestCategory::class.java)
            for (category in QuestCategory.entries) {
                val section = players.getConfigurationSection("$rawId.${category.id}") ?: continue
                val slots = arrayOfNulls<SlotProgress>(QuestCategory.SLOTS)
                for (key in section.getKeys(false)) {
                    val index = key.toIntOrNull() ?: continue
                    if (index !in 0 until QuestCategory.SLOTS) continue
                    val entry = section.getConfigurationSection(key) ?: continue
                    slots[index] = SlotProgress(
                        definitionId = entry.getString("def", "") ?: "",
                        counter = entry.getInt("counter", 0).coerceAtLeast(0),
                        claimed = entry.getBoolean("claimed", false)
                    )
                }
                byCategory[category] = slots
            }
            if (byCategory.isNotEmpty()) progress[playerId] = byCategory
        }
    }

    fun save() {
        val yaml = YamlConfiguration()
        for (category in QuestCategory.entries) {
            val base = "sets.${category.id}"
            yaml.set("$base.slots", sets[category] ?: List(QuestCategory.SLOTS) { "" })
            yaml.set("$base.last-refresh", lastRefreshMillis(category))
        }
        for ((playerId, byCategory) in progress) {
            for ((category, slots) in byCategory) {
                for (index in slots.indices) {
                    val entry = slots[index] ?: continue
                    val path = "players.$playerId.${category.id}.$index"
                    yaml.set("$path.def", entry.definitionId)
                    yaml.set("$path.counter", entry.counter)
                    yaml.set("$path.claimed", entry.claimed)
                }
            }
        }
        try {
            yaml.save(file)
            dirty = false
        } catch (ex: IOException) {
            plugin.logger.severe("Could not save $FILE_NAME: ${ex.message}")
        }
    }

    companion object {
        private const val FILE_NAME = "quest-data.yml"

        /** Rounds a damage figure to the counter step used by [QuestObjective.DEAL_DAMAGE]. */
        fun damageToCount(damage: Double): Int = damage.roundToInt().coerceAtLeast(0)
    }
}
