package dev.thorb.classskills.menu

import dev.thorb.classskills.ClassSkillsPlugin
import dev.thorb.classskills.model.ClassType
import dev.thorb.classskills.service.SelectionResult
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.function.Consumer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Sound
import org.bukkit.entity.Player

/**
 * One stable Paper dialog with all four classes. It uses reflection so the plugin can still
 * compile against its compatibility Paper API, while the screen activates on Paper 26+.
 */
class ClassOverviewDialog(private val plugin: ClassSkillsPlugin) {
    fun open(player: Player) {
        try {
            val dialog = createDialog()
            player.javaClass.methods.firstOrNull { it.name == "showDialog" && it.parameterCount == 1 }
                ?.invoke(player, dialog)
                ?: fallback(player)
        } catch (exception: ReflectiveOperationException) {
            plugin.logger.warning("Paper Dialog API is unavailable: ${exception.message}")
            fallback(player)
        }
    }

    private fun createDialog(): Any {
        val dialogClass = Class.forName("io.papermc.paper.dialog.Dialog")
        val dialogBaseClass = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase")
        val dialogBodyClass = Class.forName("io.papermc.paper.registry.data.dialog.body.DialogBody")
        val dialogTypeClass = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType")
        val actionButtonClass = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton")
        val dialogActionClass = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction")
        val callbackClass = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogActionCallback")
        val clickOptionsClass = Class.forName("net.kyori.adventure.text.event.ClickCallback\$Options")
        val clickCallbackClass = Class.forName("net.kyori.adventure.text.event.ClickCallback")

        val body = ClassType.entries.map { classType ->
            static(dialogBodyClass, "plainMessage", classSummary(classType), 260)
        }
        val optionsBuilder = static(clickOptionsClass, "builder")
        val unlimited = clickCallbackClass.getField("UNLIMITED_USES").get(null)
        invoke(optionsBuilder, "uses", unlimited)
        invoke(optionsBuilder, "lifetime", Duration.ofMinutes(30))
        val options = invoke(optionsBuilder, "build")

        val buttons = ClassType.entries.map { classType ->
            val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, _, args ->
                // The callback method name varies across Paper API revisions. Object-method
                // calls contain no Player, so responding only when one is supplied is safe.
                val audience = args?.getOrNull(1) as? Player ?: return@newProxyInstance null
                plugin.server.scheduler.runTask(plugin, Runnable { select(audience, classType) })
                null
            }
            val action = static(dialogActionClass, "customClick", callback, options)
            val button = static(actionButtonClass, "builder", Component.text("Select ${classType.displayName}", classColor(classType)))
            invoke(button, "tooltip", Component.text("Choose ${classType.displayName}"))
            invoke(button, "width", 125)
            invoke(button, "action", action)
            invoke(button, "build")
        }

        val base = static(dialogBaseClass, "builder", Component.text("Choose your class", NamedTextColor.GOLD))
        invoke(base, "canCloseWithEscape", true)
        invoke(base, "pause", false)
        val afterAction = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase\$DialogAfterAction").getField("NONE").get(null)
        invoke(base, "afterAction", afterAction)
        invoke(base, "body", body)
        val type = static(dialogTypeClass, "multiAction", buttons)
        invoke(type, "columns", 2)

        val dialogBuilder = Proxy.newProxyInstance(Consumer::class.java.classLoader, arrayOf(Consumer::class.java)) { _, method, args ->
            if (method.name == "accept") {
                val factory = args?.firstOrNull() ?: return@newProxyInstance null
                invoke(factory, "empty")
                invoke(factory, "base", invoke(base, "build"))
                invoke(factory, "type", invoke(type, "build"))
            }
            null
        } as Consumer<Any>
        return static(dialogClass, "create", dialogBuilder)
    }

    private fun classSummary(classType: ClassType): Component = Component.empty()
        .append(Component.text(classType.displayName, classColor(classType)))
        .append(Component.text("\nWeapon: ${classType.weaponDescription}", NamedTextColor.GRAY))
        .append(Component.text("\nPassive: ${classType.passiveName}", NamedTextColor.GRAY))

    private fun select(player: Player, classType: ClassType) {
        when (plugin.progression.selectClass(player, classType)) {
            SelectionResult.SUCCESS -> {
                player.javaClass.methods.firstOrNull { it.name == "closeDialog" && it.parameterCount == 0 }?.invoke(player)
                player.playSound(player, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8F, 1.0F)
            }
            SelectionResult.ALREADY_SELECTED -> player.sendMessage("§7That is already your class.")
            SelectionResult.NEEDS_SOUL_SHARD -> player.sendMessage("§cSwitching class costs a Soul Shard.")
            SelectionResult.LOCKED -> player.sendMessage("§cThat class is locked.")
        }
    }

    private fun fallback(player: Player) {
        player.sendMessage("§eThis class screen needs Paper 26.1.2+; opening the compatibility menu instead.")
        plugin.menus.openClassMenu(player)
    }

    private fun classColor(classType: ClassType): NamedTextColor = when (classType) {
        ClassType.WARRIOR -> NamedTextColor.RED
        ClassType.ARCHER -> NamedTextColor.GREEN
        ClassType.PALADIN -> NamedTextColor.GOLD
        ClassType.MAGE -> NamedTextColor.LIGHT_PURPLE
    }

    private fun static(type: Class<*>, name: String, vararg args: Any?): Any =
        type.methods.first { it.name == name && it.parameterCount == args.size }.invoke(null, *args)

    private fun invoke(target: Any, name: String, vararg args: Any?): Any =
        target.javaClass.methods.first { it.name == name && it.parameterCount == args.size }.invoke(target, *args)
}
