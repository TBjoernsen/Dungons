package dev.thorb.classskills.command

import dev.thorb.classskills.ClassSkillsPlugin
import dev.thorb.classskills.model.ClassType
import dev.thorb.classskills.service.SelectionResult
import org.bukkit.inventory.ItemStack
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class ClassSkillsCommand(private val plugin: ClassSkillsPlugin) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean =
        when (command.name.lowercase()) {
            "class" -> handleClass(sender, args)
            "skills" -> handleSkills(sender, args)
            "skillshard" -> handleShardGive(sender, args, "Skill Shard", plugin.items::skillShard)
            "soulshard" -> handleShardGive(sender, args, "Soul Shard", plugin.items::soulShard)
            else -> false
        }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (command.name.equals("class", true)) {
            return if (args.size == 1) startsWith(ClassType.entries.map { it.name.lowercase() }, args[0]) else emptyList()
        }
        if (command.name.equals("skillshard", true) || command.name.equals("soulshard", true)) {
            return when (args.size) {
                1 -> startsWith(plugin.server.onlinePlayers.map { it.name } + listOf("1", "2", "4", "8", "16"), args[0])
                2 -> startsWith(listOf("1", "2", "4", "8", "16", "32", "64"), args[1])
                else -> emptyList()
            }
        }
        if (args.isEmpty()) return emptyList()
        return when (args.size) {
            1 -> startsWith(listOf("close", "soul", "staff", "help") + if (sender.hasPermission("classskills.admin")) listOf("purge", "difficulty", "unlockdifficulty", "give", "testreset", "levelup", "hardreset", "focusdraw") else emptyList(), args[0])
            2 -> when (args[0].lowercase()) {
                "tier", "difficulty", "unlockdifficulty" -> startsWith((1..9).map(Int::toString), args[1])
                "soul" -> startsWith(ClassType.entries.map { it.name.lowercase() }, args[1])
                "hardreset" -> startsWith(listOf("confirm"), args[1])
                "give" -> startsWith(listOf("skill-shard", "soul-shard"), args[1])
                "levelup" -> startsWith(listOf("1", "5", "10", "25", "50"), args[1])
                "focusdraw" -> startsWith(listOf("0", "10", "15", "20", "25", "30", "40", "50"), args[1])
                "staff" -> plugin.server.onlinePlayers.map { it.name }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "difficulty", "unlockdifficulty" -> plugin.server.onlinePlayers.map { it.name }
                "give" -> startsWith(listOf("1", "2", "4", "8", "16"), args[2])
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun handleClass(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return sender.onlyPlayers()
        if (args.isEmpty()) {
            plugin.holographicClassSelection.open(player)
            return true
        }
        val classType = ClassType.fromInput(args[0]) ?: run {
            player.sendMessage("§cChoose Warrior, Archer, Paladin, or Mage.")
            return true
        }
        showSelectionResult(player, plugin.progression.selectClass(player, classType))
        return true
    }

    private fun handleSkills(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return sender.onlyPlayers()
        if (args.isEmpty()) {
            player.sendMessage("§7Use the in-world skill tree panel to manage your skills.")
            return true
        }
        when (args[0].lowercase()) {
            "tier" -> {
                player.sendMessage("§7Use the in-world skill tree panel to manage your skills.")
            }
            "close" -> plugin.holographicSkillTree.close(player)
            "purge" -> {
                if (!player.hasPermission("classskills.admin")) return noPermission(player)
                plugin.holographicSkillTree.purge()
                player.sendMessage("§aRemoved all ClassSkills holograms.")
            }
            "soul" -> {
                val classType = ClassType.fromInput(args.getOrNull(1)) ?: return usage(player, "/skills soul <class>")
                showSelectionResult(player, plugin.progression.selectClass(player, classType))
            }
            "staff" -> {
                val target = args.getOrNull(1)?.let(plugin.server::getPlayerExact) ?: player
                if (target != player && !player.hasPermission("classskills.admin")) return noPermission(player)
                if (target != player || plugin.store.get(target.uniqueId).classType == ClassType.MAGE) {
                    plugin.progression.giveMageStaff(target)
                    player.sendMessage("§aMage staff given to ${target.name}.")
                } else player.sendMessage("§cOnly Mages may claim a staff.")
            }
            "difficulty", "unlockdifficulty" -> handleDifficulty(player, args)
            "give" -> handleGive(player, args)
            "testreset" -> {
                if (!player.hasPermission("classskills.admin")) return noPermission(player)
                plugin.progression.adminResetCharacter(player)
                player.sendMessage("§aTest reset complete. You are Level 1 with a clear tree and base stats.")
            }
            "levelup" -> {
                if (!player.hasPermission("classskills.admin")) return noPermission(player)
                val requested = args.getOrNull(1)?.toIntOrNull() ?: 1
                if (requested !in 1..99) return usage(player, "/skills levelup [1-99]")
                val gained = plugin.progression.adminLevelUp(player, requested)
                player.sendMessage("§aGained $gained level${if (gained == 1) "" else "s"}. Level: ${plugin.store.get(player.uniqueId).level}/100.")
            }
            "hardreset" -> {
                if (!player.hasPermission("classskills.admin")) return noPermission(player)
                if (args.getOrNull(1)?.equals("confirm", ignoreCase = true) != true) {
                    player.sendMessage("§cThis wipes your ClassSkills data and restores vanilla Attack, Health, and Armor values.")
                    player.sendMessage("§7Run §f/skills hardreset confirm §7to proceed.")
                } else {
                    plugin.progression.adminHardReset(player)
                    player.sendMessage("§aClassSkills progression and combat stats have been reset. Your selected class was retained.")
                }
            }
            "focusdraw" -> {
                if (!player.hasPermission("classskills.admin")) return noPermission(player)
                val percent = args.getOrNull(1)?.toDoubleOrNull() ?: return usage(player, "/skills focusdraw <0-75>")
                if (percent !in 0.0..75.0) return usage(player, "/skills focusdraw <0-75>")
                plugin.config.set("focus.full-draw-speed-percent", percent)
                plugin.saveConfig()
                player.sendMessage("§aFull Focus draw speed is now ${if (percent % 1.0 == 0.0) percent.toInt() else percent}%.")
            }
            "help" -> help(player)
            else -> help(player)
        }
        return true
    }

    private fun handleDifficulty(player: Player, args: Array<out String>) {
        if (!player.hasPermission("classskills.admin")) {
            noPermission(player)
            return
        }
        val directDifficulty = args.getOrNull(1)?.toIntOrNull()
        val target: Player
        val difficulty: Int
        if (directDifficulty != null) {
            target = player
            difficulty = directDifficulty
        } else {
            target = args.getOrNull(1)?.let(plugin.server::getPlayerExact) ?: run {
                usage(player, "/skills difficulty [player] <1-9>")
                return
            }
            difficulty = args.getOrNull(2)?.toIntOrNull() ?: run {
                usage(player, "/skills difficulty [player] <1-9>")
                return
            }
        }
        if (difficulty !in 1..9) {
            usage(player, "Difficulty must be 1-9.")
            return
        }
        plugin.progression.unlockDungeonDifficulty(target, difficulty)
        player.sendMessage("§a${target.name} has unlocked Difficulty $difficulty.")
    }

    private fun handleGive(player: Player, args: Array<out String>) {
        if (!player.hasPermission("classskills.admin")) {
            noPermission(player)
            return
        }
        val amount = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 2304) ?: 1
        val maker = when (args.getOrNull(1)?.lowercase()) {
            "skill-shard" -> plugin.items::skillShard
            "soul-shard" -> plugin.items::soulShard
            else -> {
                usage(player, "/skills give <skill-shard|soul-shard> [amount]")
                return
            }
        }
        var remaining = amount
        while (remaining > 0) {
            val stack = maker()
            stack.amount = minOf(64, remaining)
            plugin.items.give(player, stack)
            remaining -= stack.amount
        }
        player.sendMessage("§aGiven $amount ${args[1]}(s).")
    }

    private fun handleShardGive(
        sender: CommandSender,
        args: Array<out String>,
        itemName: String,
        maker: () -> ItemStack
    ): Boolean {
        val player = sender as? Player ?: return sender.onlyPlayers()
        if (!player.hasPermission("classskills.admin")) return noPermission(player)
        val first = args.getOrNull(0)
        val directAmount = first?.toIntOrNull()
        val target = if (directAmount != null || first == null) player else plugin.server.getPlayerExact(first)
            ?: return usage(player, "${sender.name.lowercase()} [player] [amount]")
        val amount = (if (directAmount != null) directAmount else args.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 2304)
        var remaining = amount
        while (remaining > 0) {
            val stack = maker()
            stack.amount = minOf(64, remaining)
            plugin.items.give(target, stack)
            remaining -= stack.amount
        }
        player.sendMessage("§aGave $amount $itemName${if (amount == 1) "" else "s"} to ${target.name}.")
        return true
    }

    private fun showSelectionResult(player: Player, result: SelectionResult) {
        when (result) {
            SelectionResult.SUCCESS -> Unit
            SelectionResult.ALREADY_SELECTED -> player.sendMessage("§eYou already have that class.")
            SelectionResult.LOCKED -> player.sendMessage("§cYour class is locked at Difficulty 3. Use a Soul Shard to change it.")
            SelectionResult.NEEDS_SOUL_SHARD -> player.sendMessage("§cYou need one Soul Shard to change your locked class.")
        }
    }

    private fun help(player: Player) {
        player.sendMessage("§6§lClass Skills")
        player.sendMessage("§f/class §7Choose or change class.  §f/skills §7Find the in-world skill panel.")
        player.sendMessage("§f/skills soul <class> §7Locked-class rebirth.")
        if (player.hasPermission("classskills.admin")) {
            player.sendMessage("§8Admin: /skillshard [player] [amount], /soulshard [player] [amount]")
            player.sendMessage("§8Admin: /skills unlockdifficulty [player] <1-9>, /skills levelup [levels]")
            player.sendMessage("§8Admin: /skills focusdraw <0-75>, /skills testreset, /skills hardreset confirm")
            player.sendMessage("§8Admin: /skills purge clears all skill-tree holograms.")
        }
    }

    private fun usage(player: Player, message: String): Boolean {
        player.sendMessage("§cUsage: $message")
        return true
    }

    private fun noPermission(player: Player): Boolean {
        player.sendMessage("§cYou do not have permission.")
        return true
    }

    private fun CommandSender.onlyPlayers(): Boolean {
        sendMessage("§cOnly players can use this command.")
        return true
    }

    private fun startsWith(options: List<String>, prefix: String): List<String> =
        options.filter { it.startsWith(prefix, ignoreCase = true) }
}
