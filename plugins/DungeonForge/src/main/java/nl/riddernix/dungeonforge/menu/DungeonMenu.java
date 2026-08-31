package nl.riddernix.dungeonforge.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.command.DungeonCommand;
import nl.riddernix.dungeonforge.party.DungeonParty;
import nl.riddernix.dungeonforge.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Builds and handles the per-player dungeon difficulty selection menu. */
public final class DungeonMenu {

    private final DungeonForgePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Integer> selectedDifficulties = new HashMap<>();

    public DungeonMenu(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Opens the configured menu, retaining the player's current selection. */
    public void open(Player player) {
        int selected = selectedDifficulties.getOrDefault(player.getUniqueId(), 1);
        int size = configuredSize();
        MenuHolder holder = new MenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, size,
                component("menu.title", Messages.ph("player", player.getName())));
        holder.inventory = inventory;

        fillBackground(inventory);
        for (int difficulty = 1; difficulty <= 9; difficulty++) {
            int slot = plugin.getConfig().getInt("menu.difficulties." + difficulty + ".slot", defaultDifficultySlot(difficulty));
            if (validSlot(slot, inventory)) {
                inventory.setItem(slot, difficultyItem(difficulty, difficulty == selected));
            }
        }

        placeControls(player, inventory);
        player.openInventory(inventory);
    }

    /** Identifies inventories created by this menu. */
    public boolean isMenu(Inventory inventory) {
        return inventory.getHolder(false) instanceof MenuHolder;
    }

    /** Executes a click in the top menu inventory. */
    public void click(Player player, int slot) {
        if (slot == plugin.getConfig().getInt("menu.controls.close.slot", 26)) {
            player.closeInventory();
            return;
        }
        for (int difficulty = 1; difficulty <= 9; difficulty++) {
            int difficultySlot = plugin.getConfig().getInt("menu.difficulties." + difficulty + ".slot",
                    defaultDifficultySlot(difficulty));
            if (slot == difficultySlot) {
                selectedDifficulties.put(player.getUniqueId(), difficulty);
                open(player);
                return;
            }
        }

        if (slot == plugin.getConfig().getInt("menu.controls.party.slot", 10)) {
            plugin.partyMenu().open(player);
            return;
        }

        int startSlot = plugin.getConfig().getInt("menu.controls.start.slot", 16);
        if (slot != startSlot || plugin.command().startStatus(player) != DungeonCommand.StartStatus.READY) {
            return;
        }
        int selected = selectedDifficulties.getOrDefault(player.getUniqueId(), 1);
        player.closeInventory();
        plugin.command().startFromMenu(player, selected);
    }

    private void fillBackground(Inventory inventory) {
        if (!plugin.getConfig().getBoolean("menu.filler.enabled", true)) {
            return;
        }
        ItemStack filler = item("menu.filler");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private ItemStack difficultyItem(int difficulty, boolean selected) {
        String path = "menu.difficulties." + difficulty;
        int rooms = plugin.getConfig().getInt("generation.rooms-per-difficulty." + difficulty, difficulty);
        int partySize = plugin.getConfig().getInt(path + ".recommended-party-size", 1);
        List<Component> lore = lore(path, Messages.ph("difficulty", difficulty), Messages.ph("rooms", rooms),
                Messages.ph("party-size", partySize));
        if (selected) {
            String selectedLine = plugin.getConfig().getString(path + ".selected-lore", "");
            if (!selectedLine.isBlank()) {
                lore.add(parse(selectedLine, Messages.ph("difficulty", difficulty), Messages.ph("rooms", rooms),
                        Messages.ph("party-size", partySize)));
            }
        }
        return item(path, lore, Messages.ph("difficulty", difficulty), Messages.ph("rooms", rooms),
                Messages.ph("party-size", partySize));
    }

    private void placeControls(Player player, Inventory inventory) {
        DungeonCommand.StartStatus status = plugin.command().startStatus(player);
        int startSlot = plugin.getConfig().getInt("menu.controls.start.slot", 16);
        if (validSlot(startSlot, inventory)) {
            inventory.setItem(startSlot, status == DungeonCommand.StartStatus.READY
                    ? item("menu.controls.start") : disabledStartItem(status));
        }

        int partySlot = plugin.getConfig().getInt("menu.controls.party.slot", 10);
        if (validSlot(partySlot, inventory)) {
            ItemStack partyItem = item("menu.controls.party", partyLore(player),
                    Messages.ph("members", partyMembers(player)));
            if (partyItem.getItemMeta() instanceof SkullMeta skullMeta) {
                skullMeta.setPlayerProfile(player.getPlayerProfile());
                partyItem.setItemMeta(skullMeta);
            }
            inventory.setItem(partySlot, partyItem);
        }

        int closeSlot = plugin.getConfig().getInt("menu.controls.close.slot", 26);
        if (validSlot(closeSlot, inventory)) {
            inventory.setItem(closeSlot, item("menu.controls.close"));
        }
    }

    private ItemStack disabledStartItem(DungeonCommand.StartStatus status) {
        String reasonPath = "menu.controls.start.disabled-reasons."
                + status.name().toLowerCase(Locale.ROOT).replace('_', '-');
        String reason = plugin.getConfig().getString(reasonPath, "<red>Starting is unavailable.");
        TagResolver reasonPlaceholder = Placeholder.component("reason", parse(reason));
        List<Component> lore = lore("menu.controls.start.disabled", reasonPlaceholder);
        return item("menu.controls.start.disabled", lore, reasonPlaceholder);
    }

    private List<Component> partyLore(Player player) {
        return lore("menu.controls.party", Messages.ph("members", partyMembers(player)));
    }

    private String partyMembers(Player player) {
        DungeonParty party = plugin.parties().partyOf(player.getUniqueId()).orElse(null);
        if (party == null) {
            return player.getName();
        }
        return party.members().stream()
                .map(Bukkit::getOfflinePlayer)
                .map(OfflinePlayer::getName)
                .map(name -> name == null ? "unknown" : name)
                .reduce((left, right) -> left + ", " + right)
                .orElse(player.getName());
    }

    private ItemStack item(String path, TagResolver... resolvers) {
        return item(path, lore(path, resolvers), resolvers);
    }

    private ItemStack item(String path, List<Component> lore, TagResolver... resolvers) {
        Material material = material(plugin.getConfig().getString(path + ".material", "BARRIER"));
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        String name = plugin.getConfig().getString(path + ".name", " ");
        meta.displayName(parse(name, resolvers));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private List<Component> lore(String path, TagResolver... resolvers) {
        List<Component> result = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList(path + ".lore")) {
            result.add(parse(line, resolvers));
        }
        return result;
    }

    private Component component(String path, TagResolver... resolvers) {
        return parse(plugin.getConfig().getString(path, "DungeonForge"), resolvers);
    }

    private Component parse(String text, TagResolver... resolvers) {
        return miniMessage.deserialize(text, TagResolver.resolver(resolvers));
    }

    private static Material material(String configured) {
        Material material = Material.matchMaterial(configured);
        return material == null ? Material.BARRIER : material;
    }

    private int configuredSize() {
        int size = plugin.getConfig().getInt("menu.size", 27);
        return size >= 9 && size <= 54 && size % 9 == 0 ? size : 27;
    }

    private static boolean validSlot(int slot, Inventory inventory) {
        return slot >= 0 && slot < inventory.getSize();
    }

    private static int defaultDifficultySlot(int difficulty) {
        int row = (difficulty - 1) / 3;
        int column = (difficulty - 1) % 3;
        return row * 9 + column + 3;
    }

    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
