package dev.thorb.classskills.menu

import dev.thorb.classskills.ClassSkillsPlugin
import dev.thorb.classskills.model.NodeKind
import dev.thorb.classskills.model.SkillNode
import dev.thorb.classskills.model.StatType
import dev.thorb.classskills.service.PurchaseResult
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.function.Consumer
import java.util.logging.Level
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/** Paper Dialog version of the skill tree. Each dungeon tier is a separate page. */
class SkillTreeDialog(private val plugin: ClassSkillsPlugin) {
    fun open(player: Player, page: Int = 0) {
        val data = plugin.store.get(player.uniqueId)
        if (data.classType == null) {
            player.sendMessage(Component.text("Choose a class before opening its skill tree.", NamedTextColor.RED))
            plugin.classScreen.open(player)
            return
        }
        try {
            player.javaClass.methods.firstOrNull { it.name == "closeDialog" && it.parameterCount == 0 }?.invoke(player)
            val dialog = createDialog(player, page.coerceIn(0, 8))
            player.javaClass.methods.firstOrNull { it.name == "showDialog" && it.parameterCount == 1 }
                ?.invoke(player, dialog)
                ?: unavailable(player)
        } catch (exception: Exception) {
            plugin.logger.log(Level.WARNING, "Unable to construct the Paper Dialog skill tree.", exception)
            unavailable(player)
        }
    }

    private fun unavailable(player: Player) {
        player.sendMessage(Component.text("The Dialog skill tree requires Paper 26.1.2 or newer.", NamedTextColor.RED))
    }

    private fun createDialog(player: Player, page: Int): Any {
        val dialogClass = Class.forName("io.papermc.paper.dialog.Dialog")
        val baseClass = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase")
        val bodyClass = Class.forName("io.papermc.paper.registry.data.dialog.body.DialogBody")
        val typeClass = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType")
        val buttonClass = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton")
        val actionClass = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction")
        val callbackClass = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogActionCallback")
        val clickCallbackClass = Class.forName("net.kyori.adventure.text.event.ClickCallback")
        val clickOptionsClass = Class.forName("net.kyori.adventure.text.event.ClickCallback\$Options")
        val data = plugin.store.get(player.uniqueId)
        val classType = requireNotNull(data.classType)
        val tier = page + 1
        val nodes = plugin.catalog.forClassAndTier(classType, tier)

        val body = listOf(
            static(bodyClass, "plainMessage", Component.text(
                "${classType.displayName} • Tier $tier/9\nLevel ${data.level}  •  ${data.availablePoints} points available  •  Dungeon Difficulty ${data.unlockedDifficulty}/9",
                NamedTextColor.GRAY
            ), 190),
            static(bodyClass, "plainMessage", statSummary(data), 190),
            static(bodyClass, "plainMessage", Component.text(
                if (tier > data.unlockedDifficulty) "Tier $tier is locked — complete Dungeon Difficulty $tier to unlock it."
                else "Select an available node to spend Skill Points. Nodes with a connected prerequisite remain locked until the earlier node is bought.",
                if (tier > data.unlockedDifficulty) NamedTextColor.RED else NamedTextColor.DARK_GRAY
            ), 190)
        )

        val optionsBuilder = static(clickOptionsClass, "builder")
        invoke(optionsBuilder, "uses", clickCallbackClass.getField("UNLIMITED_USES").get(null))
        invoke(optionsBuilder, "lifetime", Duration.ofMinutes(30))
        val options = invoke(optionsBuilder, "build")
        val buttons = buildList {
            add(navButton(buttonClass, actionClass, callbackClass, options, "← Previous", NamedTextColor.GRAY) { open(it, (page + 8) % 9) })
            nodes.forEach { node -> add(nodeButton(buttonClass, actionClass, callbackClass, options, node, page, data)) }
            add(navButton(buttonClass, actionClass, callbackClass, options, "Next →", NamedTextColor.GRAY) { open(it, (page + 1) % 9) })
        }

        val base = static(baseClass, "builder", Component.text("Skill Tree  •  ${classType.displayName}", NamedTextColor.GOLD))
        invoke(base, "canCloseWithEscape", true)
        invoke(base, "pause", false)
        val none = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase\$DialogAfterAction").getField("NONE").get(null)
        invoke(base, "afterAction", none)
        invoke(base, "body", body)
        val type = static(typeClass, "multiAction", buttons)
        invoke(type, "columns", 3)
        val factory = Proxy.newProxyInstance(Consumer::class.java.classLoader, arrayOf(Consumer::class.java)) { _, method, args ->
            if (method.name == "accept") {
                val builder = args?.firstOrNull() ?: return@newProxyInstance null
                invoke(builder, "empty")
                invoke(builder, "base", invoke(base, "build"))
                invoke(builder, "type", invoke(type, "build"))
            }
            null
        } as Consumer<Any>
        return static(dialogClass, "create", factory)
    }

    private fun statSummary(data: dev.thorb.classskills.model.PlayerSkillData): Component = Component.text(
        "Attack ${trim(plugin.catalog.totalFor(data, StatType.ATTACK))}   •   " +
            "Health +${trim(plugin.catalog.totalFor(data, StatType.HEALTH) / 2)} hearts   •   " +
            "Armor ${trim(plugin.catalog.totalFor(data, StatType.ARMOR))}   •   " +
            "${data.classType!!.passiveName} Rank ${plugin.catalog.signatureRank(data)}",
        NamedTextColor.WHITE
    )

    private fun nodeButton(
        buttonClass: Class<*>, actionClass: Class<*>, callbackClass: Class<*>, options: Any,
        node: SkillNode, page: Int, data: dev.thorb.classskills.model.PlayerSkillData
    ): Any {
        val title = node.title
        val description = effectText(node) + " • Cost ${node.cost} point${if (node.cost == 1) "" else "s"}"
        val state = when {
            node.id in data.derivedNodeIds -> "Complete"
            node.tier > data.unlockedDifficulty -> "Locked: Difficulty ${node.tier}"
            !plugin.catalog.hasPrerequisites(data, node) -> "Locked: prerequisite"
            data.availablePoints < node.cost -> "Needs ${node.cost} points"
            else -> "Available"
        }
        val color = when {
            node.id in data.derivedNodeIds -> NamedTextColor.GREEN
            state.startsWith("Locked") -> NamedTextColor.DARK_GRAY
            state.startsWith("Needs") -> NamedTextColor.RED
            else -> NamedTextColor.YELLOW
        }
        return navButton(buttonClass, actionClass, callbackClass, options, "$title — $state", color, "$description\n$state") { audience ->
            val live = plugin.store.get(audience.uniqueId)
            val liveColor = when {
                node.id in live.derivedNodeIds -> NamedTextColor.GREEN
                node.tier > live.unlockedDifficulty || !plugin.catalog.hasPrerequisites(live, node) -> NamedTextColor.DARK_GRAY
                live.availablePoints < node.cost -> NamedTextColor.RED
                else -> NamedTextColor.YELLOW
            }
            if (liveColor != NamedTextColor.YELLOW) {
                audience.sendMessage(Component.text("$title: $description", liveColor))
            } else when (plugin.progression.purchase(audience, node.id)) {
                PurchaseResult.SUCCESS -> audience.sendMessage(Component.text("Unlocked $title.", NamedTextColor.GREEN))
                PurchaseResult.DIFFICULTY_LOCKED -> audience.sendMessage(Component.text("Unlock Dungeon Difficulty ${node.tier} first.", NamedTextColor.RED))
                PurchaseResult.PREREQUISITE_LOCKED -> audience.sendMessage(Component.text("Unlock the connected node above first.", NamedTextColor.RED))
                PurchaseResult.INSUFFICIENT_POINTS -> audience.sendMessage(Component.text("You need ${node.cost} Skill Points.", NamedTextColor.RED))
                else -> audience.sendMessage(Component.text("That node cannot be unlocked.", NamedTextColor.RED))
            }
            open(audience, page)
        }
    }

    private fun navButton(buttonClass: Class<*>, actionClass: Class<*>, callbackClass: Class<*>, options: Any, label: String, color: NamedTextColor, tooltip: String = label, handler: (Player) -> Unit): Any {
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, _, args ->
            val player = args?.getOrNull(1) as? Player ?: return@newProxyInstance null
            plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) handler(player) })
            null
        }
        val action = static(actionClass, "customClick", callback, options)
        val builder = static(buttonClass, "builder", Component.text(label, color))
        invoke(builder, "tooltip", Component.text(tooltip, color))
        invoke(builder, "width", 130)
        invoke(builder, "action", action)
        return invoke(builder, "build")
    }

    private fun effectText(node: SkillNode): String = when (node.kind) {
        NodeKind.SIGNATURE_UPGRADE -> "${node.classType.passiveName} Rank ${node.passiveRank}"
        NodeKind.STAT -> when (node.statType) {
            StatType.ATTACK -> "+${trim(node.value)} Attack"
            StatType.HEALTH -> "+${trim(node.value / 2)} hearts"
            StatType.ARMOR -> "+${trim(node.value)} Armor"
            null -> "Stat upgrade"
        }
    }

    private fun static(type: Class<*>, name: String, vararg args: Any): Any =
        type.methods.first { it.name == name && it.parameterCount == args.size }.invoke(null, *args)
    private fun invoke(receiver: Any, name: String, vararg args: Any): Any =
        receiver.javaClass.methods.first { it.name == name && it.parameterCount == args.size }.invoke(receiver, *args)
    private fun trim(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
