package nl.riddernix.dungeonplugin.classes

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** The class layer's commands: /class, /skills, /skillshard and /soulshard. */
class ClassCommands(private val plugin: DungeonPlugin) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean =
        when (command.name.lowercase()) {
            "class" -> handleClass(sender, args)
            "skills" -> handleSkills(sender, args)
            "skillshard" -> handleShardGive(sender, args, "Skill Shard", plugin.classItems::skillShard)
            "soulshard" -> handleShardGive(sender, args, "Soul Shard", plugin.classItems::soulShard)
            else -> false
        }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (command.name.equals("class", true)) {
            return if (args.size == 1) startsWith(ClassType.entries.map { it.id }, args[0]) else emptyList()
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
            1 -> startsWith(listOf("soul", "staff", "reset", "help") +
                if (sender.hasPermission("dungeonplugin.admin"))
                    listOf("difficulty", "unlockdifficulty", "give", "testreset", "levelup", "hardreset", "focusdraw")
                else emptyList(), args[0])
            2 -> when (args[0].lowercase()) {
                "difficulty", "unlockdifficulty" -> startsWith((1..9).map(Int::toString), args[1])
                "soul" -> startsWith(ClassType.entries.map { it.id }, args[1])
                "hardreset", "reset" -> startsWith(listOf("confirm"), args[1])
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
        if (!plugin.classes.enabled) {
            player.sendMessage("§cClasses are disabled on this server.")
            return true
        }
        if (args.isEmpty()) {
            plugin.classPicker.open(player)
            return true
        }
        val classType = ClassType.fromInput(args[0]) ?: run {
            player.sendMessage("§cChoose Warrior, Archer, Paladin, or Mage.")
            return true
        }
        showSelectionResult(player, plugin.classes.selectClass(player, classType))
        return true
    }

    private fun handleSkills(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return sender.onlyPlayers()
        if (args.isEmpty()) {
            player.sendMessage("§7Use the in-world skill tree panel to manage your skills.")
            return true
        }
        when (args[0].lowercase()) {
            "soul" -> {
                val classType = ClassType.fromInput(args.getOrNull(1)) ?: return usage(player, "/skills soul <class>")
                showSelectionResult(player, plugin.classes.selectClass(player, classType))
            }
            "staff" -> {
                val target = args.getOrNull(1)?.let(plugin.server::getPlayerExact) ?: player
                if (target != player && !player.hasPermission("dungeonplugin.admin")) return noPermission(player)
                if (target != player || plugin.classes.activeClass(target.uniqueId) == ClassType.MAGE) {
                    plugin.classItems.give(target, plugin.classItems.mageStaff())
                    player.sendMessage("§aMage staff given to ${target.name}.")
                } else player.sendMessage("§cOnly Mages may claim a staff.")
            }
            "reset" -> {
                // The player-facing whole-tree reset, paid in Skill Shards.
                val cost = plugin.classes.resetCost(player)
                if (cost == 0) {
                    player.sendMessage("§7You have nothing to reset.")
                    return true
                }
                if (args.getOrNull(1)?.equals("confirm", ignoreCase = true) != true) {
                    player.sendMessage("§eResetting your tree refunds every point and costs §b$cost Skill Shard(s)§e.")
                    player.sendMessage("§7Run §f/skills reset confirm §7to proceed.")
                    return true
                }
                if (!plugin.classItems.consume(player, cost, plugin.classItems::isSkillShard)) {
                    player.sendMessage("§cYou need $cost Skill Shard(s) for a full reset.")
                    return true
                }
                val classId = plugin.skillProgress.activeClass(player.uniqueId)
                val result = plugin.skillProgress.resetTree(player, classId)
                if (result.isSuccess) {
                    player.sendMessage("§aYour skill tree was reset; every point was refunded.")
                } else {
                    player.sendMessage("§cNothing could be reset.")
                }
            }
            "difficulty", "unlockdifficulty" -> handleDifficulty(player, args)
            "give" -> handleGive(player, args)
            "testreset" -> {
                if (!player.hasPermission("dungeonplugin.admin")) return noPermission(player)
                plugin.classes.adminResetCharacter(player)
                player.sendMessage("§aTest reset complete. You are Level 1 with base stats.")
            }
            "levelup" -> {
                if (!player.hasPermission("dungeonplugin.admin")) return noPermission(player)
                val requested = args.getOrNull(1)?.toIntOrNull() ?: 1
                if (requested !in 1..99) return usage(player, "/skills levelup [1-99]")
                val gained = plugin.classes.adminLevelUp(player, requested)
                player.sendMessage("§aGained $gained level${if (gained == 1) "" else "s"}. " +
                    "Level: ${plugin.classes.data(player.uniqueId).level}/100.")
            }
            "hardreset" -> {
                if (!player.hasPermission("dungeonplugin.admin")) return noPermission(player)
                if (args.getOrNull(1)?.equals("confirm", ignoreCase = true) != true) {
                    player.sendMessage("§cThis wipes your class data and restores vanilla Attack, Health, and Armor values.")
                    player.sendMessage("§7Run §f/skills hardreset confirm §7to proceed.")
                } else {
                    plugin.classes.adminHardReset(player)
                    player.sendMessage("§aClass progression and combat stats have been reset.")
                }
            }
            "focusdraw" -> {
                if (!player.hasPermission("dungeonplugin.admin")) return noPermission(player)
                val percent = args.getOrNull(1)?.toDoubleOrNull() ?: return usage(player, "/skills focusdraw <0-75>")
                if (percent !in 0.0..75.0) return usage(player, "/skills focusdraw <0-75>")
                plugin.classesConfig.set("focus.full-draw-speed-percent", percent)
                plugin.classesConfig.save()
                player.sendMessage("§aFull Focus draw speed is now ${if (percent % 1.0 == 0.0) percent.toInt() else percent}%.")
            }
            "help" -> help(player)
            else -> help(player)
        }
        return true
    }

    private fun handleDifficulty(player: Player, args: Array<out String>) {
        if (!player.hasPermission("dungeonplugin.admin")) {
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
        plugin.classes.unlockDungeonDifficulty(target, difficulty)
        player.sendMessage("§a${target.name} has unlocked Difficulty $difficulty.")
    }

    private fun handleGive(player: Player, args: Array<out String>) {
        if (!player.hasPermission("dungeonplugin.admin")) {
            noPermission(player)
            return
        }
        val amount = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 2304) ?: 1
        val maker = when (args.getOrNull(1)?.lowercase()) {
            "skill-shard" -> plugin.classItems::skillShard
            "soul-shard" -> plugin.classItems::soulShard
            else -> {
                usage(player, "/skills give <skill-shard|soul-shard> [amount]")
                return
            }
        }
        var remaining = amount
        while (remaining > 0) {
            val stack = maker()
            stack.amount = minOf(64, remaining)
            plugin.classItems.give(player, stack)
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
        if (!player.hasPermission("dungeonplugin.admin")) return noPermission(player)
        val first = args.getOrNull(0)
        val directAmount = first?.toIntOrNull()
        val target = if (directAmount != null || first == null) player
        else plugin.server.getPlayerExact(first)
            ?: return usage(player, "${sender.name.lowercase()} [player] [amount]")
        val amount = (directAmount ?: args.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 2304)
        var remaining = amount
        while (remaining > 0) {
            val stack = maker()
            stack.amount = minOf(64, remaining)
            plugin.classItems.give(target, stack)
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
        player.sendMessage("§f/skills soul <class> §7Locked-class rebirth.  §f/skills reset §7Refund your tree for Skill Shards.")
        if (player.hasPermission("dungeonplugin.admin")) {
            player.sendMessage("§8Admin: /skillshard [player] [amount], /soulshard [player] [amount]")
            player.sendMessage("§8Admin: /skills unlockdifficulty [player] <1-9>, /skills levelup [levels]")
            player.sendMessage("§8Admin: /skills focusdraw <0-75>, /skills testreset, /skills hardreset confirm")
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
