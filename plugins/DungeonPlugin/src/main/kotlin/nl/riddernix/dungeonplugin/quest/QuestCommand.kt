package nl.riddernix.dungeonplugin.quest

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * `/quests` - opens the quest selector. Admin subcommands
 * (`dungeonplugin.admin`) drive and inspect the system for testing:
 *
 * - `/quests refresh <daily|weekly|general>` - roll a category now.
 * - `/quests progress <kill|damage|all> <amount>` - add progress to your own
 *   active quests without grinding mobs (`all` advances every objective, so
 *   a whole category can be finished in one command).
 * - `/quests info` - current sets, per-slot progress and state, whether each
 *   category is complete, and the resulting XP multiplier.
 */
class QuestCommand(private val plugin: DungeonPlugin) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            val player = sender as? Player ?: return notPlayer(sender)
            plugin.questMenu.openSelector(player)
            return true
        }
        return when (args[0].lowercase(Locale.ROOT)) {
            "refresh" -> handleRefresh(sender, args)
            "progress" -> handleProgress(sender, args)
            "info" -> handleInfo(sender)
            else -> {
                sender.sendMessage("§7Usage: §f/quests §7| §f/quests refresh|progress|info")
                true
            }
        }
    }

    private fun handleRefresh(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("dungeonplugin.admin")) return noPermission(sender)
        val category = args.getOrNull(1)?.let { QuestCategory.fromId(it) }
        if (category == null) {
            sender.sendMessage("§cUsage: /quests refresh <daily|weekly|general>")
            return true
        }
        plugin.quests.forceRefresh(category)
        sender.sendMessage("§aRolled a new §f${category.displayName}§a quest set.")
        return true
    }

    private fun handleProgress(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("dungeonplugin.admin")) return noPermission(sender)
        val player = sender as? Player ?: return notPlayer(sender)
        val which = args.getOrNull(1)?.lowercase(Locale.ROOT)
        val objectives = when (which) {
            "kill", "kill_any", "mob" -> listOf(QuestObjective.KILL_ANY)
            "damage", "deal_damage", "dmg" -> listOf(QuestObjective.DEAL_DAMAGE)
            "all", "both" -> QuestObjective.entries.toList()
            else -> emptyList()
        }
        val amount = args.getOrNull(2)?.toIntOrNull()
        if (objectives.isEmpty() || amount == null || amount <= 0) {
            player.sendMessage("§cUsage: /quests progress <kill|damage|all> <amount>")
            return true
        }
        objectives.forEach { plugin.quests.addProgress(player, it, amount) }
        player.sendMessage("§7Added §f$amount §7to your " +
            "§f${objectives.joinToString("/") { it.id }}§7 quests.")
        return true
    }

    private fun handleInfo(sender: CommandSender): Boolean {
        if (!sender.hasPermission("dungeonplugin.admin")) return noPermission(sender)
        val zone = plugin.questConfig.zone()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        val player = sender as? Player
        sender.sendMessage("§6Quests §7(zone §f$zone§7)")
        for (category in QuestCategory.entries) {
            val last = plugin.quests.lastRefreshMillis(category)
            val lastText = if (last <= 0) "never"
            else ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(last), zone).format(formatter)
            val done = player != null && plugin.quests.categoryComplete(player.uniqueId, category)
            sender.sendMessage("§e${category.displayName}§7: last refresh §f$lastText" +
                if (player != null) "  §7complete=§f${if (done) "§ayes" else "§cno"}" else "")
            plugin.quests.definitions(category).forEachIndexed { index, definition ->
                val progress = if (player != null && definition != null) {
                    val c = plugin.quests.counter(player.uniqueId, category, index)
                    val st = plugin.quests.state(player.uniqueId, category, index)
                    " §7$c/§f${definition.required} §8${st.name.lowercase()}"
                } else ""
                sender.sendMessage("  §7$index. §f${definition?.title ?: "§c(unresolved)"} " +
                    "§8[${definition?.objective?.id ?: "-"} x${definition?.required ?: "-"}]$progress")
            }
            nextBoundary(category, ZonedDateTime.now(zone))?.let {
                sender.sendMessage("  §7next scheduled refresh: §f${it.format(formatter)}")
            }
        }
        if (player != null) {
            val multiplier = plugin.quests.xpMultiplier(player.uniqueId)
            val parts = plugin.quests.multiplierBreakdown(player.uniqueId)
            sender.sendMessage("§6XP multiplier: §a×${QuestManager.format(multiplier)} " +
                "§7(${percent(multiplier)})  §8stacking=${
                    if (plugin.questConfig.multiplierStacksMultiplicatively()) "multiplicative" else "additive"}")
            for (category in QuestCategory.entries.filter { it.refreshing }) {
                val factor = plugin.questConfig.categoryMultiplier(category)
                val active = parts.any { it.first == category }
                sender.sendMessage("  §7${category.displayName}: config §f×${QuestManager.format(factor)} " +
                    "§8${if (active) "§a(active)" else "§7(inactive)"}")
            }
        }
        return true
    }

    private fun percent(multiplier: Double): String {
        val bonus = ((multiplier - 1.0) * 100.0).let { if (it < 0) 0.0 else it }
        return "+${bonus.toInt()}% dungeon XP"
    }

    private fun nextBoundary(category: QuestCategory, now: ZonedDateTime): ZonedDateTime? = when (category) {
        QuestCategory.DAILY -> now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        QuestCategory.WEEKLY -> now.toLocalDate()
            .with(TemporalAdjusters.next(DayOfWeek.FRIDAY)).atStartOfDay(now.zone)
        QuestCategory.GENERAL -> null
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val admin = sender.hasPermission("dungeonplugin.admin")
        return when (args.size) {
            1 -> filter(buildList {
                if (admin) addAll(listOf("refresh", "progress", "info"))
            }, args[0])
            2 -> when (args[0].lowercase(Locale.ROOT)) {
                "refresh" -> filter(QuestCategory.entries.map { it.id }, args[1])
                "progress" -> filter(listOf("kill", "damage", "all"), args[1])
                else -> emptyList()
            }
            3 -> if (args[0].equals("progress", true)) filter(listOf("1", "5", "10", "50"), args[2]) else emptyList()
            else -> emptyList()
        }
    }

    private fun filter(options: List<String>, partial: String): List<String> {
        val lower = partial.lowercase(Locale.ROOT)
        return options.filter { it.lowercase(Locale.ROOT).startsWith(lower) }
    }

    private fun noPermission(sender: CommandSender): Boolean {
        sender.sendMessage("§cYou don't have permission to do that.")
        return true
    }

    private fun notPlayer(sender: CommandSender): Boolean {
        sender.sendMessage("§cOnly players can use that.")
        return true
    }
}
