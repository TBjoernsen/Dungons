package nl.riddernix.dungeonforge.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.riddernix.dungeonforge.DungeonForgePlugin;
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
import java.util.Map;
import java.util.UUID;

/** Configurable party management inventory backed by the existing party commands. */
public final class PartyMenu {

    private final DungeonForgePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public PartyMenu(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, size(), component("party-menu.title"));
        holder.inventory = inventory;
        fill(inventory);

        DungeonParty party = plugin.parties().partyOf(player.getUniqueId()).orElse(null);
        boolean leader = party == null || party.isLeader(player.getUniqueId());
        List<UUID> members = party == null ? List.of(player.getUniqueId()) : new ArrayList<>(party.members());
        List<Integer> memberSlots = plugin.getConfig().getIntegerList("party-menu.member-slots");
        for (int index = 0; index < Math.min(memberSlots.size(), members.size()); index++) {
            int slot = memberSlots.get(index);
            if (valid(slot, inventory)) {
                UUID memberId = members.get(index);
                inventory.setItem(slot, memberItem(memberId, memberId.equals(player.getUniqueId()), leader));
                holder.members.put(slot, memberId);
            }
        }

        if (leader) {
            List<Integer> inviteSlots = plugin.getConfig().getIntegerList("party-menu.invite-slots");
            List<? extends Player> candidates = Bukkit.getOnlinePlayers().stream()
                    .filter(candidate -> !candidate.getUniqueId().equals(player.getUniqueId()))
                    .filter(candidate -> party == null || !party.members().contains(candidate.getUniqueId()))
                    .sorted(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            for (int index = 0; index < Math.min(inviteSlots.size(), candidates.size()); index++) {
                int slot = inviteSlots.get(index);
                if (valid(slot, inventory)) {
                    Player candidate = candidates.get(index);
                    inventory.setItem(slot, onlineHead("party-menu.invite-player", candidate,
                            Messages.ph("player", candidate.getName())));
                    holder.invites.put(slot, candidate.getUniqueId());
                }
            }
        }

        placeControl(inventory, "back");
        placeControl(inventory, "close");
        player.openInventory(inventory);
    }

    public boolean isMenu(Inventory inventory) {
        return inventory.getHolder(false) instanceof Holder;
    }

    public void click(Player player, Inventory inventory, int slot) {
        if (!(inventory.getHolder(false) instanceof Holder holder)) {
            return;
        }
        if (slot == plugin.getConfig().getInt("party-menu.controls.back.slot", 45)) {
            // There is no chest menu behind this any more; the difficulty
            // panel in the world took its place.
            player.closeInventory();
            plugin.panels().sendLocator(player);
            return;
        }
        if (slot == plugin.getConfig().getInt("party-menu.controls.close.slot", 53)) {
            player.closeInventory();
            return;
        }
        UUID invitee = holder.invites.get(slot);
        if (invitee != null) {
            Player target = Bukkit.getPlayer(invitee);
            if (target != null) {
                player.performCommand("dungeon party invite " + target.getName());
            }
            open(player);
            return;
        }
        UUID member = holder.members.get(slot);
        if (member == null) {
            return;
        }
        if (member.equals(player.getUniqueId())) {
            if (plugin.parties().partyOf(member).isPresent()) {
                player.performCommand("dungeon party leave");
            }
            open(player);
            return;
        }
        DungeonParty party = plugin.parties().partyOf(player.getUniqueId()).orElse(null);
        if (party != null && party.isLeader(player.getUniqueId())) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(member);
            if (target.getName() != null) {
                player.performCommand("dungeon party kick " + target.getName());
            }
            open(player);
        }
    }

    private ItemStack memberItem(UUID memberId, boolean self, boolean viewerIsLeader) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(memberId);
        Player online = Bukkit.getPlayer(memberId);
        String name = offline.getName() == null ? "unknown" : offline.getName();
        String action = self ? plugin.getConfig().getString("party-menu.member.self-action", "<gray>Click to leave.")
                : viewerIsLeader ? plugin.getConfig().getString("party-menu.member.kick-action", "<red>Click to remove.")
                : plugin.getConfig().getString("party-menu.member.member-action", "<gray>Party member.");
        TagResolver actionPlaceholder = Placeholder.component("action", miniMessage.deserialize(action));
        if (online != null && online.isOnline()) {
            return onlineHead("party-menu.member", online, Messages.ph("player", name), actionPlaceholder);
        }
        return item("party-menu.member-offline", Messages.ph("player", name), actionPlaceholder);
    }

    private ItemStack onlineHead(String path, Player player, TagResolver... resolvers) {
        ItemStack stack = item(path, resolvers);
        if (stack.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setPlayerProfile(player.getPlayerProfile());
            stack.setItemMeta(skullMeta);
        }
        return stack;
    }

    private void fill(Inventory inventory) {
        if (!plugin.getConfig().getBoolean("party-menu.filler.enabled", true)) {
            return;
        }
        ItemStack stack = item("party-menu.filler");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, stack);
        }
    }

    private void placeControl(Inventory inventory, String control) {
        int slot = plugin.getConfig().getInt("party-menu.controls." + control + ".slot", 0);
        if (valid(slot, inventory)) {
            inventory.setItem(slot, item("party-menu.controls." + control));
        }
    }

    private ItemStack item(String path, TagResolver... resolvers) {
        Material material = Material.matchMaterial(plugin.getConfig().getString(path + ".material", "BARRIER"));
        ItemStack stack = new ItemStack(material == null ? Material.BARRIER : material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(component(path + ".name", resolvers));
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList(path + ".lore")) {
            lore.add(miniMessage.deserialize(line, TagResolver.resolver(resolvers)));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private Component component(String path, TagResolver... resolvers) {
        return miniMessage.deserialize(plugin.getConfig().getString(path, "DungeonForge"), TagResolver.resolver(resolvers));
    }

    private int size() {
        int value = plugin.getConfig().getInt("party-menu.size", 54);
        return value >= 9 && value <= 54 && value % 9 == 0 ? value : 54;
    }

    private static boolean valid(int slot, Inventory inventory) {
        return slot >= 0 && slot < inventory.getSize();
    }

    private static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, UUID> members = new HashMap<>();
        private final Map<Integer, UUID> invites = new HashMap<>();
        @Override public Inventory getInventory() { return inventory; }
    }
}
