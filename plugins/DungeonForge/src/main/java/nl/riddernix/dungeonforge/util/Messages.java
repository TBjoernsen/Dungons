package nl.riddernix.dungeonforge.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Thin layer over the messages section of config.yml.
 *
 * <p>All text runs through MiniMessage, so config.yml can use {@code <red>},
 * {@code <gradient:...>} and {@code <bold>} directly. Placeholders are written
 * as {@code <world>} and filled in via {@link #ph(String, String)}.</p>
 */
public final class Messages {

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private String prefix = "";

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.prefix = plugin.getConfig().getString("messages.prefix", "");
    }

    /** Builds a Component from config.yml, with the prefix in front. */
    public Component get(String key, TagResolver... placeholders) {
        String raw = plugin.getConfig().getString("messages." + key, "<red>Missing message: " + key);
        return miniMessage.deserialize(prefix + raw, placeholders);
    }

    /** Same as {@link #get} but without the prefix - handy for action bars. */
    public Component bare(String key, TagResolver... placeholders) {
        String raw = plugin.getConfig().getString("messages." + key, "<red>Missing message: " + key);
        return miniMessage.deserialize(raw, placeholders);
    }

    public void send(CommandSender target, String key, TagResolver... placeholders) {
        target.sendMessage(get(key, placeholders));
    }

    /** Creates a placeholder: ph("world", "dungeon_teun") fills &lt;world&gt;. */
    public static TagResolver ph(String key, String value) {
        return Placeholder.unparsed(key, value);
    }

    /** Inserts a ready-made component, so a placeholder can carry a click or hover of its own. */
    public static TagResolver ph(String key, Component value) {
        return Placeholder.component(key, value);
    }

    public static TagResolver ph(String key, int value) {
        return Placeholder.unparsed(key, Integer.toString(value));
    }

    public static TagResolver ph(String key, long value) {
        return Placeholder.unparsed(key, Long.toString(value));
    }
}
