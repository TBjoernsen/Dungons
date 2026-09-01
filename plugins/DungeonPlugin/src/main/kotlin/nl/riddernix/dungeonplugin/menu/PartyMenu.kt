package nl.riddernix.dungeonplugin.menu

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.util.Messages
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID

/** Configurable party management inventory backed by the existing party commands. */
class PartyMenu(private val plugin: DungeonPlugin) {

    private val miniMessage = MiniMessage.miniMessage()

    fun open(player: Player) {
        val holder = Holder()
        val inventory = Bukkit.createInventory(holder, size(), component("party-menu.title"))
        holder.inventory = inventory
        fill(inventory)

        val party = plugin.parties.partyOf(player.uniqueId)
        val leader = party == null || party.isLeader(player.uniqueId)
        val members = party?.members?.toList() ?: listOf(player.uniqueId)
        val memberSlots = plugin.config.getIntegerList("party-menu.member-slots")
        for (index in 0 until minOf(memberSlots.size, members.size)) {
            val slot = memberSlots[index]
            if (valid(slot, inventory)) {
                val memberId = members[index]
                inventory.setItem(slot, memberItem(memberId, memberId == player.uniqueId, leader))
                holder.members[slot] = memberId
            }
        }

        if (leader) {
            val inviteSlots = plugin.config.getIntegerList("party-menu.invite-slots")
            val candidates = Bukkit.getOnlinePlayers()
                .filter { it.uniqueId != player.uniqueId }
                .filter { party == null || it.uniqueId !in party.members }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            for (index in 0 until minOf(inviteSlots.size, candidates.size)) {
                val slot = inviteSlots[index]
                if (valid(slot, inventory)) {
                    val candidate = candidates[index]
                    inventory.setItem(slot, onlineHead("party-menu.invite-player", candidate,
                        Messages.ph("player", candidate.name)))
                    holder.invites[slot] = candidate.uniqueId
                }
            }
        }

        placeControl(inventory, "back")
        placeControl(inventory, "close")
        player.openInventory(inventory)
    }

    fun isMenu(inventory: Inventory): Boolean = inventory.getHolder(false) is Holder

    fun click(player: Player, inventory: Inventory, slot: Int) {
        val holder = inventory.getHolder(false) as? Holder ?: return
        if (slot == plugin.config.getInt("party-menu.controls.back.slot", 45)) {
            // There is no chest menu behind this any more; the difficulty
            // panel in the world took its place.
            player.closeInventory()
            plugin.panels.sendLocator(player)
            return
        }
        if (slot == plugin.config.getInt("party-menu.controls.close.slot", 53)) {
            player.closeInventory()
            return
        }
        val invitee = holder.invites[slot]
        if (invitee != null) {
            val target = Bukkit.getPlayer(invitee)
            if (target != null) {
                player.performCommand("dungeon party invite ${target.name}")
            }
            open(player)
            return
        }
        val member = holder.members[slot] ?: return
        if (member == player.uniqueId) {
            if (plugin.parties.partyOf(member) != null) {
                player.performCommand("dungeon party leave")
            }
            open(player)
            return
        }
        val party = plugin.parties.partyOf(player.uniqueId)
        if (party != null && party.isLeader(player.uniqueId)) {
            val target = Bukkit.getOfflinePlayer(member)
            if (target.name != null) {
                player.performCommand("dungeon party kick ${target.name}")
            }
            open(player)
        }
    }

    private fun memberItem(memberId: UUID, self: Boolean, viewerIsLeader: Boolean): ItemStack {
        val offline = Bukkit.getOfflinePlayer(memberId)
        val online = Bukkit.getPlayer(memberId)
        val name = offline.name ?: "unknown"
        val action = when {
            self -> plugin.config.getString("party-menu.member.self-action", "<gray>Click to leave.")!!
            viewerIsLeader -> plugin.config.getString("party-menu.member.kick-action", "<red>Click to remove.")!!
            else -> plugin.config.getString("party-menu.member.member-action", "<gray>Party member.")!!
        }
        val actionPlaceholder = Placeholder.component("action", miniMessage.deserialize(action))
        if (online != null && online.isOnline) {
            return onlineHead("party-menu.member", online, Messages.ph("player", name), actionPlaceholder)
        }
        return item("party-menu.member-offline", Messages.ph("player", name), actionPlaceholder)
    }

    private fun onlineHead(path: String, player: Player, vararg resolvers: TagResolver): ItemStack {
        val stack = item(path, *resolvers)
        val meta = stack.itemMeta
        if (meta is SkullMeta) {
            meta.playerProfile = player.playerProfile
            stack.itemMeta = meta
        }
        return stack
    }

    private fun fill(inventory: Inventory) {
        if (!plugin.config.getBoolean("party-menu.filler.enabled", true)) {
            return
        }
        val stack = item("party-menu.filler")
        for (slot in 0 until inventory.size) {
            inventory.setItem(slot, stack)
        }
    }

    private fun placeControl(inventory: Inventory, control: String) {
        val slot = plugin.config.getInt("party-menu.controls.$control.slot", 0)
        if (valid(slot, inventory)) {
            inventory.setItem(slot, item("party-menu.controls.$control"))
        }
    }

    private fun item(path: String, vararg resolvers: TagResolver): ItemStack {
        val material = Material.matchMaterial(plugin.config.getString("$path.material", "BARRIER")!!)
        val stack = ItemStack(material ?: Material.BARRIER)
        val meta = stack.itemMeta
        meta.displayName(component("$path.name", *resolvers))
        val lore = ArrayList<Component>()
        for (line in plugin.config.getStringList("$path.lore")) {
            lore.add(miniMessage.deserialize(line, TagResolver.resolver(*resolvers)))
        }
        meta.lore(lore)
        stack.itemMeta = meta
        return stack
    }

    private fun component(path: String, vararg resolvers: TagResolver): Component =
        miniMessage.deserialize(plugin.config.getString(path, "DungeonPlugin")!!, TagResolver.resolver(*resolvers))

    private fun size(): Int {
        val value = plugin.config.getInt("party-menu.size", 54)
        return if (value in 9..54 && value % 9 == 0) value else 54
    }

    private class Holder : InventoryHolder {
        @JvmField
        var inventory: Inventory? = null
        val members = HashMap<Int, UUID>()
        val invites = HashMap<Int, UUID>()
        override fun getInventory(): Inventory = inventory!!
    }

    companion object {
        private fun valid(slot: Int, inventory: Inventory): Boolean = slot in 0 until inventory.size
    }
}

/** Prevents item movement in the party menu and dispatches its configured actions. */
class PartyMenuListener(private val plugin: DungeonPlugin) : Listener {

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val top = event.view.topInventory
        if (!plugin.partyMenu.isMenu(top)) return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.rawSlot < 0 || event.rawSlot >= top.size) return
        val slot = event.rawSlot
        plugin.server.scheduler.runTask(plugin, Runnable { plugin.partyMenu.click(player, top, slot) })
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (plugin.partyMenu.isMenu(event.view.topInventory)) event.isCancelled = true
    }
}
