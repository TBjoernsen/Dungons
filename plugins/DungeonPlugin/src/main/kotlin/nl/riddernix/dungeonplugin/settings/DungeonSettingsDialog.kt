package nl.riddernix.dungeonplugin.settings

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.minimessage.MiniMessage
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** Admin-only native Paper Dialog pages for safe numeric dungeon tuning. */
class DungeonSettingsDialog(private val plugin: DungeonPlugin) {

    private val miniMessage = MiniMessage.miniMessage()

    fun openHome(player: Player) {
        player.showDialog(Dialog.create { builder ->
            builder.empty()
                .base(DialogBase.builder(text("settings.title"))
                    .body(listOf(DialogBody.plainMessage(text("settings.home-description")))).build())
                .type(DialogType.multiAction(listOf(
                    button("settings.buttons.room-counts") { openRoomCounts(player) },
                    button("settings.buttons.generation") { openGeneration(player) },
                    button("settings.buttons.mobs-low") { openMobs(player, 1, 5) },
                    button("settings.buttons.mobs-high") { openMobs(player, 6, 9) },
                    button("settings.buttons.toggles") { openToggles(player) }
                ), null, 3))
        })
    }

    private fun openRoomCounts(player: Player) {
        val fields = ArrayList<NumberField>()
        for (difficulty in 1..9) {
            fields.add(NumberField("rooms$difficulty", "generation.rooms-per-difficulty.$difficulty",
                text("settings.labels.room-count", "difficulty", difficulty.toString()), 2F, 64F, 1F))
        }
        openNumbers(player, "settings.pages.room-counts", fields)
    }

    private fun openGeneration(player: Player) {
        val fields = listOf(
            field("prefab-width", "generation.prefab-room.size.x", "settings.labels.prefab-width", 7F, 127F, 2F),
            field("prefab-height", "generation.prefab-room.size.y", "settings.labels.prefab-height", 6F, 64F, 1F),
            field("prefab-depth", "generation.prefab-room.size.z", "settings.labels.prefab-depth", 7F, 127F, 2F),
            field("corridor-length", "generation.corridor.length", "settings.labels.corridor-length", 7F, 80F, 1F),
            field("branch-chance", "generation.branching.branch-frequency", "settings.labels.branch-chance", 0F, 1F, 0.05F),
            field("short-branch", "generation.branching.short-branch-max-length", "settings.labels.short-branch", 1F, 8F, 1F),
            field("long-branch", "generation.branching.long-branch-max-length", "settings.labels.long-branch", 1F, 8F, 1F),
            field("turn-min", "generation.critical-path.min-rooms-before-turn", "settings.labels.turn-min", 1F, 8F, 1F),
            field("turn-max", "generation.critical-path.max-rooms-before-turn", "settings.labels.turn-max", 1F, 8F, 1F)
        )
        openNumbers(player, "settings.pages.generation", fields)
    }

    private fun openMobs(player: Player, first: Int, last: Int) {
        val fields = ArrayList<NumberField>()
        for (difficulty in first..last) {
            val difficultyPath = "mobs.difficulties.$difficulty."
            val number = difficulty.toString()
            fields.add(NumberField("swarm-count-$number", difficultyPath + "categories.swarm.count", text("settings.labels.swarm-count", "difficulty", number), 0F, 32F, 1F))
            fields.add(NumberField("pack-count-$number", difficultyPath + "categories.pack.count", text("settings.labels.pack-count", "difficulty", number), 0F, 8F, 1F))
            fields.add(NumberField("champion-count-$number", difficultyPath + "categories.champion.count", text("settings.labels.champion-count", "difficulty", number), 0F, 3F, 1F))
            fields.add(NumberField("tier-$number", difficultyPath + "tier", text("settings.labels.tier", "difficulty", number), 1F, 10F, 1F))
        }
        openNumbers(player, if (first == 1) "settings.pages.mobs-low" else "settings.pages.mobs-high", fields)
    }

    private fun openToggles(player: Player) {
        val fields = listOf(
            BooleanField("hunger", "hunger.freeze-in-dungeons", text("settings.labels.hunger")),
            BooleanField("party-pvp", "combat.disable-player-damage-in-dungeons", text("settings.labels.party-pvp")),
            BooleanField("spawn-room", "mobs.spawn-room", text("settings.labels.spawn-room")),
            BooleanField("teleport-create", "teleport.on-create", text("settings.labels.teleport-create"))
        )
        player.showDialog(Dialog.create { builder ->
            builder.empty()
                .base(DialogBase.builder(text("settings.pages.toggles"))
                    .body(listOf(DialogBody.plainMessage(text("settings.toggles-description"))))
                    .inputs(fields.map { field ->
                        DialogInput.bool(field.key, field.label,
                            plugin.config.getBoolean(field.path), string("settings.boolean-on"), string("settings.boolean-off"))
                    }).build())
                .type(DialogType.multiAction(pageButtons(player,
                    { reset(fields.map { it.path }) { openToggles(player) } },
                    { view ->
                        fields.forEach { field ->
                            val value = view.getBoolean(field.key)
                            if (value != null) plugin.config.set(field.path, value)
                        }
                    }), null, 3))
        })
    }

    private fun openNumbers(player: Player, titlePath: String, fields: List<NumberField>) {
        player.showDialog(Dialog.create { builder ->
            builder.empty()
                .base(DialogBase.builder(text(titlePath))
                    .body(listOf(DialogBody.plainMessage(text("settings.range-description"))))
                    .inputs(fields.map { field ->
                        DialogInput.numberRange(field.key, field.label, field.minimum, field.maximum)
                            .step(field.step).initial(plugin.config.getDouble(field.path).toFloat()).build()
                    }).build())
                .type(DialogType.multiAction(pageButtons(player,
                    { reset(fields.map { it.path }) { openNumbers(player, titlePath, fields) } },
                    { view -> saveNumbers(fields, view) }), null, 3))
        })
    }

    private fun pageButtons(player: Player, reset: Runnable, apply: (DialogResponseView) -> Unit): List<ActionButton> {
        return listOf(
            actionButton("settings.buttons.save") { view ->
                apply(view)
                plugin.saveConfig()
                plugin.messages.send(player, "settings-saved")
            },
            button("settings.buttons.reset") { reset.run() },
            button("settings.buttons.back") { openHome(player) }
        )
    }

    private fun saveNumbers(fields: List<NumberField>, view: DialogResponseView) {
        for (field in fields) {
            val value = view.getFloat(field.key) ?: continue
            plugin.config.set(field.path, if (field.step >= 1.0F) Math.round(value) else value.toDouble())
        }
    }

    private fun reset(paths: List<String>, reopen: Runnable) {
        val defaults: YamlConfiguration
        try {
            plugin.getResource("config.yml").use { resource ->
                if (resource == null) return
                defaults = YamlConfiguration.loadConfiguration(InputStreamReader(resource, StandardCharsets.UTF_8))
            }
        } catch (exception: IOException) {
            plugin.logger.warning("Could not read the bundled default config: ${exception.message}")
            return
        }
        for (path in paths) {
            plugin.config.set(path, defaults.get(path))
        }
        plugin.saveConfig()
        reopen.run()
    }

    private fun button(path: String, action: () -> Unit): ActionButton =
        actionButton(path) { action() }

    private fun actionButton(path: String, callback: (DialogResponseView) -> Unit): ActionButton {
        return ActionButton.builder(text(path)).action(DialogAction.customClick({ view, audience -> callback(view) },
            ClickCallback.Options.builder().uses(1).build())).build()
    }

    private fun field(key: String, path: String, labelPath: String, min: Float, max: Float, step: Float): NumberField =
        NumberField(key, path, text(labelPath), min, max, step)

    private fun text(path: String, vararg replacements: String): Component {
        var value = plugin.config.getString(path, path)!!
        var index = 0
        while (index + 1 < replacements.size) {
            value = value.replace("<${replacements[index]}>", replacements[index + 1])
            index += 2
        }
        return miniMessage.deserialize(value)
    }

    private fun string(path: String): String = plugin.config.getString(path, path)!!

    private data class NumberField(val key: String, val path: String, val label: Component,
                                   val minimum: Float, val maximum: Float, val step: Float)

    private data class BooleanField(val key: String, val path: String, val label: Component)
}
