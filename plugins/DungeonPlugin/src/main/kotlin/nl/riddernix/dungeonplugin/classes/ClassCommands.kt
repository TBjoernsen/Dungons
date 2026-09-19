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
            1 -> startsWith(listOf("soul", "staff", "reset", "mastery", "help") +
                if (sender.hasPermission("dungeonplugin.admin"))
                    listOf("difficulty", "unlockdifficulty", "give", "testreset", "levelup", "hardreset", "focusdraw", "passiverank", "maxtree")
                else emptyList(), args[0])
            2 -> when (args[0].lowercase()) {
                "difficulty", "unlockdifficulty" -> startsWith((1..9).map(Int::toString), args[1])
                "soul" -> startsWith(ClassType.entries.map { it.id }, args[1])
                "hardreset", "reset" -> startsWith(listOf("confirm"), args[1])
                "mastery" -> startsWith(listOf("reset", "quests"), args[1])
                "give" -> startsWith(listOf("skill-shard", "soul-shard"), args[1])
                "levelup" -> startsWith(listOf("1", "5", "10", "25", "50"), args[1])
                "focusdraw" -> startsWith(listOf("0", "10", "15", "20", "25", "30", "40", "50"), args[1])
                "passiverank" -> startsWith(listOf("tree", "0", "1", "2", "3", "4", "5"), args[1])
                "maxtree" -> startsWith(ClassType.entries.map { it.id }, args[1])
                "staff" -> plugin.server.onlinePlayers.map { it.name }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "difficulty", "unlockdifficulty", "passiverank", "maxtree" -> plugin.server.onlinePlayers.map { it.name }
                "give" -> startsWith(listOf("1", "2", "4", "8", "16"), args[2])
                "mastery" -> when {
                    args[1].equals("reset", true) -> startsWith(masteryBranchIds(sender), args[2])
                    args[1].equals("quests", true) -> startsWith(
                        listOf("claim") + if (sender.hasPermission("dungeonplugin.admin")) listOf("progress") else emptyList(),
                        args[2])
                    else -> emptyList()
                }
                else -> emptyList()
            }
            4 -> when {
                args[0].equals("mastery", true) && args[1].equals("reset", true) -> startsWith(listOf("confirm"), args[3])
                args[0].equals("mastery", true) && args[1].equals("quests", true) && args[2].equals("progress", true) ->
                    startsWith(listOf("100", "1000", "5000", "20000"), args[3])
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
                    plugin.classItems.give(target, plugin.classItems.mageStaff(plugin.classes.subclass(target.uniqueId)))
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
            "mastery" -> handleMastery(player, args)
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
            "maxtree" -> {
                if (!player.hasPermission("dungeonplugin.admin")) return noPermission(player)
                val classType = ClassType.fromInput(args.getOrNull(1))
                    ?: return usage(player, "/skills maxtree <class> [player]")
                val targetArg = args.getOrNull(2)
                val target = if (targetArg == null) player
                    else plugin.server.getPlayerExact(targetArg)
                        ?: return usage(player, "§cNo online player '$targetArg'.")
                val granted = plugin.skillProgress.maxOutTree(target, classType.id)
                val active = plugin.classes.activeClass(target.uniqueId) == classType
                player.sendMessage(if (granted > 0)
                    "§a${target.name}'s ${classType.displayName} tree is now fully unlocked ($granted node(s) granted)."
                else "§e${target.name}'s ${classType.displayName} tree was already fully unlocked.")
                if (!active) player.sendMessage("§7${target.name} isn't playing ${classType.displayName} right now - §f/class ${classType.id}§7 to switch and see it.")
            }
            "passiverank" -> {
                if (!player.hasPermission("dungeonplugin.admin")) return noPermission(player)
                val raw = args.getOrNull(1) ?: return usage(player, "/skills passiverank <0-5|tree> [player]")
                val targetArg = args.getOrNull(2)
                val target = if (targetArg == null) player
                    else plugin.server.getPlayerExact(targetArg)
                        ?: return usage(player, "§cNo online player '$targetArg'.")
                val value = if (raw.equals("tree", true) || raw.equals("reset", true)) -1
                    else raw.toIntOrNull()?.takeIf { it in 0..5 }
                        ?: return usage(player, "/skills passiverank <0-5|tree> [player]")
                plugin.classes.setDebugSignatureRank(target, value)
                val passive = plugin.classes.activeClass(target.uniqueId)?.passiveName ?: "signature passive"
                if (value < 0) {
                    player.sendMessage("§a${target.name}: $passive rank now follows the skill tree again.")
                } else {
                    player.sendMessage("§a${target.name}: $passive rank forced to §e$value §7(testing override).")
                }
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

    /**
     * The mastery/subclass step: `/skills mastery` to choose,
     * `/skills mastery reset <branch> confirm` to switch. An admin always
     * gets the picker - no level, proximity or already-chosen gate, and the
     * pick is free - so the flow can be replayed while it is being tested.
     */
    private fun handleMastery(player: Player, args: Array<out String>) {
        val classType = plugin.classes.activeClass(player.uniqueId) ?: return
        if (args.size >= 2 && args[1].equals("reset", ignoreCase = true)) {
            handleMasteryReset(player, classType, args)
            return
        }
        if (args.size >= 2 && args[1].equals("quests", ignoreCase = true)) {
            handleMasteryQuests(player, args)
            return
        }
        val options = plugin.classes.subclassOptions(classType)
        if (options.isEmpty()) {
            player.sendMessage("§7${classType.displayName} has no mastery branches yet.")
            return
        }
        val admin = player.hasPermission("dungeonplugin.admin")
        if (!admin) {
            val current = plugin.classes.subclass(player.uniqueId)
            if (current != null) {
                val name = plugin.classesConfig.subclassOption(classType.id, current)?.name ?: current
                val cost = plugin.classesConfig.subclassResetSoulShardCost(classType.id)
                player.sendMessage("§dYour mastery: §f$name")
                player.sendMessage("§7/skills mastery reset <branch> confirm §7switches for §b$cost Soul Shard(s)§7.")
                return
            }
            if (!plugin.classes.isMasteryEligible(player)) {
                player.sendMessage("§7Reach Level ${plugin.classesConfig.subclassUnlockLevel(classType.id)} to choose a mastery.")
                return
            }
            if (!nearOwnSkillPanel(player)) {
                player.sendMessage("§7Stand near your skill tree to choose your mastery.")
                return
            }
        } else {
            player.sendMessage("§8[admin] Level, proximity and already-chosen checks skipped.")
        }
        plugin.masterySelection.open(player, classType, options, forced = admin)
    }

    private fun handleMasteryReset(player: Player, classType: ClassType, args: Array<out String>) {
        val branch = args.getOrNull(2) ?: run {
            usage(player, "/skills mastery reset <branch> confirm")
            return
        }
        if (!nearOwnSkillPanel(player)) {
            player.sendMessage("§7Stand near your skill tree to change your mastery.")
            return
        }
        val cost = plugin.classesConfig.subclassResetSoulShardCost(classType.id)
        if (args.getOrNull(3)?.equals("confirm", ignoreCase = true) != true) {
            player.sendMessage("§eSwitching mastery costs §b$cost Soul Shard(s)§e.")
            player.sendMessage("§7Run §f/skills mastery reset $branch confirm §7to proceed.")
            return
        }
        when (plugin.classes.resetSubclass(player, branch)) {
            SubclassResult.SUCCESS -> {
                val name = plugin.classesConfig.subclassOption(classType.id, branch)?.name ?: branch
                player.sendMessage("§aYou are now a $name.")
            }
            SubclassResult.NOT_CHOSEN_YET -> player.sendMessage("§cChoose a mastery first with /skills mastery.")
            SubclassResult.UNKNOWN_SUBCLASS -> player.sendMessage("§cUnknown mastery branch '$branch'.")
            SubclassResult.ALREADY_CHOSEN -> player.sendMessage("§eThat is already your mastery.")
            SubclassResult.NEEDS_SOUL_SHARDS -> player.sendMessage("§cYou need $cost Soul Shard(s) to switch.")
            SubclassResult.NO_CLASS, SubclassResult.TOO_LOW_LEVEL -> player.sendMessage("§cThat is not available right now.")
        }
    }

    /**
     * `/skills mastery quests` (view), `/skills mastery quests claim`, and
     * the admin test command `/skills mastery quests progress <amount>` -
     * mirrors `/quests progress` for the fixed mastery ladder. There is no
     * board page for this yet (that is a follow-up); this command is the
     * whole interface for now.
     */
    private fun handleMasteryQuests(player: Player, args: Array<out String>) {
        val subclassId = plugin.classes.subclass(player.uniqueId)
        if (subclassId == null) {
            player.sendMessage("§7Choose a mastery first with §f/skills mastery§7.")
            return
        }
        val line = plugin.classes.masteryQuestLine(player.uniqueId)
        if (line == null) {
            player.sendMessage("§7No mastery quests configured for that branch yet.")
            return
        }
        val sub = args.getOrNull(2)?.lowercase()
        if (sub == "claim") {
            when (plugin.classes.claimMasteryQuest(player)) {
                MasteryClaimResult.CLAIMED -> {
                    val level = plugin.classes.masteryProgress(player.uniqueId, subclassId).level
                    player.sendMessage("§6§lMastery quest claimed! §7Level $level/${line.ladder.size}.")
                }
                MasteryClaimResult.NOT_READY -> player.sendMessage("§cThat quest isn't complete yet.")
                MasteryClaimResult.MAX_LEVEL -> player.sendMessage("§eYou've claimed every mastery quest on this branch.")
                MasteryClaimResult.NO_LINE -> player.sendMessage("§7No mastery quests configured for that branch yet.")
            }
            return
        }
        val progress = plugin.classes.masteryProgress(player.uniqueId, subclassId)
        if (sub == "progress") {
            if (!player.hasPermission("dungeonplugin.admin")) { noPermission(player); return }
            if (progress.level >= line.ladder.size) {
                player.sendMessage("§eAlready at max level - nothing to progress.")
                return
            }
            val amount = args.getOrNull(3)?.toIntOrNull()
            if (amount == null || amount <= 0) {
                usage(player, "/skills mastery quests progress <amount>")
                return
            }
            val objective = line.ladder[progress.level].objective
            plugin.classes.addMasteryProgress(player, objective, amount)
            player.sendMessage("§7Added §f$amount §7to your §f${objective.id}§7 mastery progress (current step).")
            return
        }
        if (progress.level >= line.ladder.size) {
            player.sendMessage("§6§lMastery: §eMax level (${line.ladder.size}/${line.ladder.size}).")
            return
        }
        player.sendMessage("§6§lMastery Quests §7- Level ${progress.level}/${line.ladder.size}")
        val current = line.ladder[progress.level]
        val currentCount = progress.counters.getOrDefault(current.objective, 0)
        player.sendMessage("§e${current.title} §7- ${current.description} " +
            "§f(${currentCount.coerceAtMost(current.required)}/${current.required})")
        if (progress.level + 1 < line.ladder.size) {
            val next = line.ladder[progress.level + 1]
            val nextCount = progress.counters.getOrDefault(next.objective, 0)
            player.sendMessage("§7Next: §f${next.title} §7- ${next.description} " +
                "§8(${nextCount.coerceAtMost(next.required)}/${next.required})")
        }
        if (currentCount >= current.required) {
            player.sendMessage("§a§lReady to claim! §7Run §f/skills mastery quests claim§7.")
        }
    }

    private fun nearOwnSkillPanel(player: Player): Boolean {
        val classType = plugin.classes.activeClass(player.uniqueId) ?: return false
        val radius = plugin.classesConfig.getDouble("mastery.select-radius", 6.0)
        return plugin.skillPanels.list().any { panel ->
            panel.classId.equals(classType.id, ignoreCase = true) &&
                panel.location.world == player.world &&
                panel.location.distanceSquared(player.location) <= radius * radius
        }
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
        player.sendMessage("§f/skills mastery §7Choose your subclass near the skill tree, once eligible.")
        player.sendMessage("§f/skills mastery quests §7View your mastery quest ladder.  §f...claim §7Claim a finished one.")
        if (player.hasPermission("dungeonplugin.admin")) {
            player.sendMessage("§8Admin: /skillshard [player] [amount], /soulshard [player] [amount]")
            player.sendMessage("§8Admin: /skills unlockdifficulty [player] <1-9>, /skills levelup [levels]")
            player.sendMessage("§8Admin: /skills passiverank <0-5|tree> [player], /skills focusdraw <0-75>")
            player.sendMessage("§8Admin: /skills maxtree <class> [player] §7Unlocks that class's whole skill tree.")
            player.sendMessage("§8Admin: /skills testreset, /skills hardreset confirm")
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

    private fun masteryBranchIds(sender: CommandSender): List<String> {
        val classType = (sender as? Player)?.let { plugin.classes.activeClass(it.uniqueId) } ?: return emptyList()
        return plugin.classes.subclassOptions(classType).map { it.id }
    }
}
