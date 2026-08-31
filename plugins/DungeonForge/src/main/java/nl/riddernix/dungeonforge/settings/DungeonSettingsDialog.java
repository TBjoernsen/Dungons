package nl.riddernix.dungeonforge.settings;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Admin-only native Paper Dialog pages for safe numeric dungeon tuning. */
public final class DungeonSettingsDialog {
    private final DungeonForgePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public DungeonSettingsDialog(DungeonForgePlugin plugin) { this.plugin = plugin; }

    public void openHome(Player player) {
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("settings.title"))
                        .body(List.of(DialogBody.plainMessage(text("settings.home-description")))).build())
                .type(DialogType.multiAction(List.of(
                        button("settings.buttons.room-counts", () -> openRoomCounts(player)),
                        button("settings.buttons.generation", () -> openGeneration(player)),
                        button("settings.buttons.mobs-low", () -> openMobs(player, 1, 5)),
                        button("settings.buttons.mobs-high", () -> openMobs(player, 6, 9)),
                        button("settings.buttons.toggles", () -> openToggles(player))
                ), null, 3))));
    }

    private void openRoomCounts(Player player) {
        List<NumberField> fields = new ArrayList<>();
        for (int difficulty = 1; difficulty <= 9; difficulty++) {
            fields.add(new NumberField("rooms" + difficulty, "generation.rooms-per-difficulty." + difficulty,
                    text("settings.labels.room-count", "difficulty", Integer.toString(difficulty)), 2, 64, 1));
        }
        openNumbers(player, "settings.pages.room-counts", fields);
    }

    private void openGeneration(Player player) {
        List<NumberField> fields = List.of(
                field("prefab-width", "generation.prefab-room.size.x", "settings.labels.prefab-width", 7, 127, 2),
                field("prefab-height", "generation.prefab-room.size.y", "settings.labels.prefab-height", 6, 64, 1),
                field("prefab-depth", "generation.prefab-room.size.z", "settings.labels.prefab-depth", 7, 127, 2),
                field("corridor-length", "generation.corridor.length", "settings.labels.corridor-length", 7, 80, 1),
                field("branch-chance", "generation.branching.branch-frequency", "settings.labels.branch-chance", 0, 1, 0.05f),
                field("short-branch", "generation.branching.short-branch-max-length", "settings.labels.short-branch", 1, 8, 1),
                field("long-branch", "generation.branching.long-branch-max-length", "settings.labels.long-branch", 1, 8, 1),
                field("turn-min", "generation.critical-path.min-rooms-before-turn", "settings.labels.turn-min", 1, 8, 1),
                field("turn-max", "generation.critical-path.max-rooms-before-turn", "settings.labels.turn-max", 1, 8, 1)
        );
        openNumbers(player, "settings.pages.generation", fields);
    }

    private void openMobs(Player player, int first, int last) {
        List<NumberField> fields = new ArrayList<>();
        for (int difficulty = first; difficulty <= last; difficulty++) {
            String difficultyPath = "mobs.difficulties." + difficulty + ".";
            String number = Integer.toString(difficulty);
            fields.add(new NumberField("swarm-count-" + number, difficultyPath + "categories.swarm.count", text("settings.labels.swarm-count", "difficulty", number), 0, 32, 1));
            fields.add(new NumberField("pack-count-" + number, difficultyPath + "categories.pack.count", text("settings.labels.pack-count", "difficulty", number), 0, 8, 1));
            fields.add(new NumberField("champion-count-" + number, difficultyPath + "categories.champion.count", text("settings.labels.champion-count", "difficulty", number), 0, 3, 1));
            fields.add(new NumberField("tier-" + number, difficultyPath + "tier", text("settings.labels.tier", "difficulty", number), 1, 10, 1));
        }
        openNumbers(player, first == 1 ? "settings.pages.mobs-low" : "settings.pages.mobs-high", fields);
    }

    private void openToggles(Player player) {
        List<BooleanField> fields = List.of(
                new BooleanField("hunger", "hunger.freeze-in-dungeons", text("settings.labels.hunger")),
                new BooleanField("party-pvp", "combat.disable-player-damage-in-dungeons", text("settings.labels.party-pvp")),
                new BooleanField("spawn-room", "mobs.spawn-room", text("settings.labels.spawn-room")),
                new BooleanField("teleport-create", "teleport.on-create", text("settings.labels.teleport-create"))
        );
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("settings.pages.toggles"))
                        .body(List.of(DialogBody.plainMessage(text("settings.toggles-description"))))
                        .inputs(fields.stream().map(field -> DialogInput.bool(field.key(), field.label(),
                                plugin.getConfig().getBoolean(field.path()), string("settings.boolean-on"), string("settings.boolean-off"))).toList()).build())
                .type(DialogType.multiAction(pageButtons(player,
                        () -> reset(fields.stream().map(BooleanField::path).toList(), () -> openToggles(player)),
                        view -> fields.forEach(field -> {
                            Boolean value = view.getBoolean(field.key());
                            if (value != null) plugin.getConfig().set(field.path(), value);
                        })), null, 3))));
    }

    private void openNumbers(Player player, String titlePath, List<NumberField> fields) {
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text(titlePath))
                        .body(List.of(DialogBody.plainMessage(text("settings.range-description"))))
                        .inputs(fields.stream().map(field -> DialogInput.numberRange(field.key(), field.label(), field.minimum(), field.maximum())
                                .step(field.step()).initial((float) plugin.getConfig().getDouble(field.path())).build()).toList()).build())
                .type(DialogType.multiAction(pageButtons(player,
                        () -> reset(fields.stream().map(NumberField::path).toList(), () -> openNumbers(player, titlePath, fields)),
                        view -> saveNumbers(fields, view)), null, 3))));
    }

    private List<ActionButton> pageButtons(Player player, Runnable reset, ResponseApplier apply) {
        return List.of(
                actionButton("settings.buttons.save", view -> { apply.apply(view); plugin.saveConfig(); plugin.messages().send(player, "settings-saved"); }),
                button("settings.buttons.reset", reset),
                button("settings.buttons.back", () -> openHome(player))
        );
    }

    private void saveNumbers(List<NumberField> fields, DialogResponseView view) {
        for (NumberField field : fields) {
            Float value = view.getFloat(field.key());
            if (value == null) continue;
            plugin.getConfig().set(field.path(), field.step() >= 1.0F ? Math.round(value) : value.doubleValue());
        }
    }

    private void reset(List<String> paths, Runnable reopen) {
        YamlConfiguration defaults;
        try (InputStream resource = plugin.getResource("config.yml")) {
            if (resource == null) return;
            defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not read the bundled default config: " + exception.getMessage());
            return;
        }
        for (String path : paths) {
            plugin.getConfig().set(path, defaults.get(path));
        }
        plugin.saveConfig();
        reopen.run();
    }

    private ActionButton button(String path, Runnable action) {
        return actionButton(path, ignored -> action.run());
    }

    private ActionButton actionButton(String path, ResponseApplier callback) {
        return ActionButton.builder(text(path)).action(DialogAction.customClick((view, audience) -> callback.apply(view),
                ClickCallback.Options.builder().uses(1).build())).build();
    }

    private NumberField field(String key, String path, String labelPath, float min, float max, float step) {
        return new NumberField(key, path, text(labelPath), min, max, step);
    }

    private Component text(String path, String... replacements) {
        String value = plugin.getConfig().getString(path, path);
        for (int index = 0; index + 1 < replacements.length; index += 2) value = value.replace("<" + replacements[index] + ">", replacements[index + 1]);
        return miniMessage.deserialize(value);
    }

    private String string(String path) {
        return plugin.getConfig().getString(path, path);
    }

    private record NumberField(String key, String path, Component label, float minimum, float maximum, float step) { }
    private record BooleanField(String key, String path, Component label) { }
    @FunctionalInterface private interface ResponseApplier { void apply(DialogResponseView view); }
}
