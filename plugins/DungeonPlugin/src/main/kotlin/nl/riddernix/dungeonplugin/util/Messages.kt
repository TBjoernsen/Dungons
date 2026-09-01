package nl.riddernix.dungeonplugin.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin

/**
 * Thin layer over the messages section of config.yml.
 *
 * All text runs through MiniMessage, so config.yml can use `<red>`,
 * `<gradient:...>` and `<bold>` directly. Placeholders are written as
 * `<world>` and filled in via [ph].
 */
class Messages(private val plugin: JavaPlugin) {

    private val miniMessage = MiniMessage.miniMessage()
    private var prefix = ""

    init {
        reload()
    }

    fun reload() {
        prefix = plugin.config.getString("messages.prefix", "") ?: ""
    }

    /** Builds a Component from config.yml, with the prefix in front. */
    fun get(key: String, vararg placeholders: TagResolver): Component {
        val raw = plugin.config.getString("messages.$key", "<red>Missing message: $key")!!
        return miniMessage.deserialize(prefix + raw, *placeholders)
    }

    /** Same as [get] but without the prefix - handy for action bars. */
    fun bare(key: String, vararg placeholders: TagResolver): Component {
        val raw = plugin.config.getString("messages.$key", "<red>Missing message: $key")!!
        return miniMessage.deserialize(raw, *placeholders)
    }

    fun send(target: CommandSender, key: String, vararg placeholders: TagResolver) {
        target.sendMessage(get(key, *placeholders))
    }

    companion object {
        /** Creates a placeholder: ph("world", "dungeon_teun") fills `<world>`. */
        @JvmStatic
        fun ph(key: String, value: String): TagResolver = Placeholder.unparsed(key, value)

        /** Inserts a ready-made component, so a placeholder can carry a click or hover of its own. */
        @JvmStatic
        fun ph(key: String, value: Component): TagResolver = Placeholder.component(key, value)

        @JvmStatic
        fun ph(key: String, value: Int): TagResolver = Placeholder.unparsed(key, value.toString())

        @JvmStatic
        fun ph(key: String, value: Long): TagResolver = Placeholder.unparsed(key, value.toString())
    }
}
