package dev.thorb.classskills.menu;

import dev.thorb.classskills.ClassSkillsPlugin;
import dev.thorb.classskills.model.ClassType;
import dev.thorb.classskills.model.NodeKind;
import dev.thorb.classskills.model.PlayerSkillData;
import dev.thorb.classskills.model.SkillNode;
import dev.thorb.classskills.model.StatType;
import dev.thorb.classskills.service.SelectionResult;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** ClassSelect-style Paper dialog backed entirely by ClassSkills' existing data and rules. */
public final class ClassScreenDialog {
    private static final int COLUMN_WIDTH = 130;
    private static final int ICON_SIZE = 16;
    private static final int STAT_DESCRIPTION_WIDTH = COLUMN_WIDTH + 30;
    private static final int SECTION_WIDTH = COLUMN_WIDTH + 60;
    private static final TextColor GRADIENT_START = TextColor.color(0xC9A227);
    private static final TextColor GRADIENT_END = TextColor.color(0xF2E39B);
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(30)).build();
    private final ClassSkillsPlugin plugin;

    public ClassScreenDialog(ClassSkillsPlugin plugin) { this.plugin = plugin; }
    public void open(Player player) { open(player, 0); }
    public void open(Player player, int page) {
        player.closeDialog();
        player.showDialog(build(player, page));
    }

    private Dialog build(Player player, int page) {
        ClassType[] classes = ClassType.values();
        int index = Math.floorMod(page, classes.length);
        ClassType current = classes[index];
        PlayerSkillData data = plugin.getStore().get(player.getUniqueId());
        return Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(current.getDisplayName() + "  (" + (index + 1) + "/" + classes.length + ")", NamedTextColor.DARK_GRAY))
                        .canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(body(current, data)).build())
                .type(DialogType.multiAction(buttons(current, index)).columns(3).build()));
    }

    private List<DialogBody> body(ClassType current, PlayerSkillData data) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.item(new ItemStack(current.getIcon()))
                .description(DialogBody.plainMessage(statColumn(current, data), STAT_DESCRIPTION_WIDTH))
                .showTooltip(false).showDecorations(false).width(ICON_SIZE).height(ICON_SIZE).build());
        body.add(DialogBody.plainMessage(weaponBlock(current), SECTION_WIDTH));
        body.add(DialogBody.plainMessage(passiveBlock(current, data), SECTION_WIDTH));
        body.add(DialogBody.plainMessage(Component.text("Level " + data.getLevel() + "  -  Points " + data.getAvailablePoints() + "  -  Difficulty " + data.getUnlockedDifficulty(), NamedTextColor.DARK_GRAY), SECTION_WIDTH));
        return body;
    }

    private Component statColumn(ClassType current, PlayerSkillData data) {
        Component heading = TextLayout.heading(current.getDisplayName(), COLUMN_WIDTH, GRADIENT_START, GRADIENT_END).decorate(TextDecoration.BOLD);
        Component rows = Component.empty();
        boolean own = data.getClassType() == current;
        for (StatType stat : StatType.values()) {
            double max = treeMaximum(current, stat);
            String value = own ? trim(plugin.getCatalog().totalFor(data, stat)) + "/" + trim(max) : "+" + trim(max);
            rows = rows.append(TextLayout.leaderRow(stat.getDisplayName(), value, COLUMN_WIDTH, NamedTextColor.GRAY, NamedTextColor.WHITE)).appendNewline();
        }
        return Component.empty().append(heading).appendNewline().append(rows);
    }

    private double treeMaximum(ClassType current, StatType stat) {
        double total = 0;
        for (SkillNode node : plugin.getCatalog().getNodes()) {
            if (node.getClassType() == current && node.getKind() == NodeKind.STAT && node.getStatType() == stat) total += node.getValue();
        }
        return total;
    }

    private Component weaponBlock(ClassType current) {
        return Component.empty().append(TextLayout.heading("Weapon", COLUMN_WIDTH, GRADIENT_START, GRADIENT_END).decorate(TextDecoration.BOLD))
                .appendNewline().append(Component.text(current.getWeaponDescription(), NamedTextColor.GRAY));
    }

    private Component passiveBlock(ClassType current, PlayerSkillData data) {
        Component lines = Component.text(current.getPassiveName(), NamedTextColor.GRAY);
        if (data.getClassType() == current) {
            int rank = plugin.getCatalog().signatureRank(data);
            if (rank > 0) lines = lines.appendNewline().append(Component.text("Rank " + rank, NamedTextColor.YELLOW));
        }
        return Component.empty().append(TextLayout.heading("Passive", COLUMN_WIDTH, GRADIENT_START, GRADIENT_END).decorate(TextDecoration.BOLD)).appendNewline().append(lines);
    }

    private List<ActionButton> buttons(ClassType current, int index) {
        return List.of(
                ActionButton.builder(Component.text("<-", NamedTextColor.GRAY)).tooltip(Component.text("Previous class")).width(60).action(callback(player -> open(player, index - 1))).build(),
                ActionButton.builder(Component.text("Select", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)).tooltip(Component.text("Play as " + current.getDisplayName())).width(130).action(callback(player -> select(player, current))).build(),
                ActionButton.builder(Component.text("->", NamedTextColor.GRAY)).tooltip(Component.text("Next class")).width(60).action(callback(player -> open(player, index + 1))).build());
    }

    private void select(Player player, ClassType chosen) {
        SelectionResult result = plugin.getProgression().selectClass(player, chosen);
        switch (result) {
            case SUCCESS -> {
                player.closeDialog();
                player.playSound(player, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8F, 1.0F);
                player.sendMessage(Component.text("You are now a ", NamedTextColor.GRAY).append(Component.text(chosen.getDisplayName(), NamedTextColor.GOLD)).append(Component.text(".", NamedTextColor.GRAY)));
            }
            case ALREADY_SELECTED -> player.sendMessage(Component.text("Already your class.", NamedTextColor.GRAY));
            case LOCKED -> player.sendMessage(Component.text("That class is locked.", NamedTextColor.RED));
            case NEEDS_SOUL_SHARD -> player.sendMessage(Component.text("Switching class costs a Soul Shard.", NamedTextColor.RED));
        }
    }

    private DialogAction callback(PlayerHandler handler) {
        DialogActionCallback raw = (response, audience) -> {
            if (!(audience instanceof Player player)) return;
            Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) handler.handle(player); });
        };
        return DialogAction.customClick(raw, CALLBACK_OPTIONS);
    }

    private String trim(double value) { return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value); }
    @FunctionalInterface private interface PlayerHandler { void handle(Player player); }
}
