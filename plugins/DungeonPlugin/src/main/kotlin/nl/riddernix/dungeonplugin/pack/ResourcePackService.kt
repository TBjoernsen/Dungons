package nl.riddernix.dungeonplugin.pack

import net.kyori.adventure.text.Component
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/**
 * Sends the plugin's resource pack to players and remembers who actually has
 * it loaded, so menus can use pack art for them and plain fallbacks for
 * everyone else. Lives in its own `resourcepack.yml` (merged like quests.yml,
 * never replaced) so the URL and hash survive config-version bumps.
 */
class ResourcePackService(private val plugin: DungeonPlugin) : Listener {

    private val file = File(plugin.dataFolder, FILE_NAME)
    private lateinit var yaml: YamlConfiguration
    private val loaded = HashSet<UUID>()

    init {
        reload()
    }

    fun reload() {
        if (!file.isFile) plugin.saveResource(FILE_NAME, false)
        yaml = YamlConfiguration.loadConfiguration(file)
        plugin.getResource(FILE_NAME)?.use { resource ->
            yaml.setDefaults(YamlConfiguration.loadConfiguration(InputStreamReader(resource, StandardCharsets.UTF_8)))
            yaml.options().copyDefaults(true)
        }
        try {
            yaml.save(file)
        } catch (exception: IOException) {
            plugin.logger.severe("Could not save $FILE_NAME: ${exception.message}")
        }
    }

    /** True when menus should use pack art for [player] rather than the vanilla fallback. */
    fun useCardModels(player: Player): Boolean = when ((yaml.getString("card-models") ?: "auto").lowercase(Locale.ROOT)) {
        "always" -> true
        "never" -> false
        else -> player.uniqueId in loaded
    }

    fun send(player: Player) {
        val url = yaml.getString("url").orEmpty().trim()
        if (!yaml.getBoolean("enabled", false) || url.isEmpty()) return
        val hash = decodeSha1(yaml.getString("sha1").orEmpty())
        if (hash == null) {
            plugin.logger.warning("$FILE_NAME: sha1 is missing or not 40 hex characters - clients will re-download the pack on every join.")
        }
        val prompt = Component.text(yaml.getString("prompt") ?: "")
        player.setResourcePack(url, hash, prompt, yaml.getBoolean("required", false))
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        plugin.server.scheduler.runTaskLater(plugin, Runnable { if (player.isOnline) send(player) }, 20L)
    }

    @EventHandler
    fun onStatus(event: PlayerResourcePackStatusEvent) {
        val id = event.player.uniqueId
        when (event.status) {
            Status.SUCCESSFULLY_LOADED -> loaded.add(id)
            Status.DECLINED, Status.FAILED_DOWNLOAD, Status.INVALID_URL, Status.FAILED_RELOAD, Status.DISCARDED -> loaded.remove(id)
            else -> Unit
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        loaded.remove(event.player.uniqueId)
    }

    private fun decodeSha1(hex: String): ByteArray? {
        val clean = hex.trim()
        if (clean.length != 40 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(20) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private companion object {
        const val FILE_NAME = "resourcepack.yml"
    }
}
