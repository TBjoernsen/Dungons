package nl.riddernix.dungeonplugin.quest

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

/**
 * The two quest screens.
 *
 * - The **selector** is a single chest: one item per category
 *   ([QuestCategory]) - paper for Daily, an empty map for Weekly, a filled map
 *   for General - each labelled with what clicking it does.
 * - The **category list** is a double chest: the category's four quests, each
 *   showing title, objective, progress and reward, with a distinct look per
 *   state ([QuestManager.QuestState]). A fixed back item returns to the
 *   selector.
 *
 * Layout and text come from `quests.yml` (`menu.*`); state is read live from
 * [QuestManager] every time a screen is drawn, so progress made while a screen
 * is open is reflected by [refreshIfViewing] without reopening it.
 */
class QuestMenu(private val plugin: DungeonPlugin) {

    private val mini = MiniMessage.miniMessage()
    private val quests get() = plugin.quests
    private val yaml get() = plugin.questConfig.yaml

    // ------------------------------------------------------------------
    //  Opening
    // ------------------------------------------------------------------

    fun openSelector(player: Player) {
        val holder = SelectorHolder()
        val inventory = Bukkit.createInventory(holder, SELECTOR_SIZE,
            line(yaml.getString("menu.selector.title") ?: "<gold>Quests"))
        holder.inv = inventory
        fill(inventory, "menu.selector.filler")

        for (category in QuestCategory.entries) {
            val slot = yaml.getInt("menu.selector.${category.id}.slot", defaultSelectorSlot(category))
            if (slot in 0 until inventory.size) {
                inventory.setItem(slot, selectorItem(category))
                holder.categories[slot] = category
            }
        }
        player.openInventory(inventory)
    }

    fun openCategory(player: Player, category: QuestCategory) {
        val holder = CategoryHolder(category)
        val titleTemplate = yaml.getString("menu.list.title") ?: "<gold><category> Quests"
        val inventory = Bukkit.createInventory(holder, CATEGORY_SIZE,
            line(titleTemplate.replace("<category>", category.displayName)))
        holder.inv = inventory
        fill(inventory, "menu.list.filler")

        val slots = questSlots()
        for (questSlot in 0 until QuestCategory.SLOTS) {
            val invSlot = slots.getOrNull(questSlot) ?: continue
            if (invSlot !in 0 until inventory.size) continue
            inventory.setItem(invSlot, questItem(player, category, questSlot))
            holder.questSlots[invSlot] = questSlot
        }

        val backSlot = yaml.getInt("menu.list.back.slot", DEFAULT_BACK_SLOT)
        if (backSlot in 0 until inventory.size) {
            inventory.setItem(backSlot, backItem())
            holder.backSlot = backSlot
        }
        player.openInventory(inventory)
    }

    // ------------------------------------------------------------------
    //  Identity and click routing
    // ------------------------------------------------------------------

    fun isQuestMenu(inventory: Inventory?): Boolean {
        val holder = inventory?.getHolder(false)
        return holder is SelectorHolder || holder is CategoryHolder
    }

    fun handleClick(player: Player, inventory: Inventory, slot: Int) {
        when (val holder = inventory.getHolder(false)) {
            is SelectorHolder -> {
                val category = holder.categories[slot] ?: return
                openCategory(player, category)
            }
            is CategoryHolder -> {
                if (slot == holder.backSlot) {
                    openSelector(player)
                    return
                }
                val questSlot = holder.questSlots[slot] ?: return
                when (quests.claim(player, holder.category, questSlot)) {
                    QuestManager.ClaimResult.CLAIMED -> { /* claim() already gave feedback + redrew */ }
                    QuestManager.ClaimResult.NOT_COMPLETE ->
                        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.0f)
                    QuestManager.ClaimResult.ALREADY_CLAIMED ->
                        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.0f)
                    QuestManager.ClaimResult.MISSING ->
                        player.sendMessage("§cThat quest slot is empty.")
                }
            }
        }
    }

    /** Redraws the quest items in place if the player is looking at a category list. */
    fun refreshIfViewing(player: Player) {
        val top = player.openInventory.topInventory
        val holder = top.getHolder(false) as? CategoryHolder ?: return
        for ((invSlot, questSlot) in holder.questSlots) {
            top.setItem(invSlot, questItem(player, holder.category, questSlot))
        }
    }

    // ------------------------------------------------------------------
    //  Item building
    // ------------------------------------------------------------------

    private fun selectorItem(category: QuestCategory): ItemStack {
        val path = "menu.selector.${category.id}"
        val material = material(yaml.getString("$path.material"), defaultSelectorMaterial(category))
        val stack = ItemStack(material)
        stack.editMeta { meta ->
            meta.displayName(line(yaml.getString("$path.name") ?: "<yellow>${category.displayName} Quests"))
            val lore = ArrayList<Component>()
            val configured = yaml.getStringList("$path.lore")
            if (configured.isNotEmpty()) {
                configured.forEach { lore.add(line(it)) }
            } else {
                lore.add(line("<gray>Click to view your ${category.displayName.lowercase()} quests."))
            }
            meta.lore(lore)
        }
        return stack
    }

    private fun questItem(player: Player, category: QuestCategory, questSlot: Int): ItemStack {
        val definition = quests.definition(category, questSlot)
        val state = quests.state(player.uniqueId, category, questSlot)
        if (definition == null || state == QuestManager.QuestState.MISSING) {
            val stack = ItemStack(material(yaml.getString("menu.list.state.missing.material"), Material.BARRIER))
            stack.editMeta { it.displayName(line("<red>No quest")) }
            return stack
        }

        val counter = quests.counter(player.uniqueId, category, questSlot)
        val shown = definition.clamp(counter)
        val stateKey = when (state) {
            QuestManager.QuestState.COMPLETE_UNCLAIMED -> "complete"
            QuestManager.QuestState.CLAIMED -> "claimed"
            else -> "in-progress"
        }
        val material = material(yaml.getString("menu.list.state.$stateKey.material"), defaultStateMaterial(state))
        val stack = ItemStack(material)
        stack.editMeta { meta ->
            meta.displayName(line("<gold>${definition.title}"))
            val lore = ArrayList<Component>()
            lore.add(Component.empty())
            lore.add(line("<gray>${definition.description}"))
            lore.add(Component.empty())
            lore.add(line("<yellow>Progress: <white>$shown<gray>/<white>${definition.required}"))
            lore.add(line("<yellow>Reward: <white>${definition.reward.ifBlank { "(placeholder)" }}"))
            lore.add(Component.empty())
            lore.add(when (state) {
                QuestManager.QuestState.COMPLETE_UNCLAIMED -> line("<green><bold>Complete!</bold> <gray>Click to claim your reward.")
                QuestManager.QuestState.CLAIMED -> line("<dark_gray>Reward claimed.")
                else -> line("<gray>In progress…")
            })
            meta.lore(lore)
            if (state == QuestManager.QuestState.COMPLETE_UNCLAIMED) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true)
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            }
        }
        return stack
    }

    private fun backItem(): ItemStack {
        val stack = ItemStack(material(yaml.getString("menu.list.back.material"), Material.ARROW))
        stack.editMeta { meta ->
            meta.displayName(line(yaml.getString("menu.list.back.name") ?: "<yellow>Back"))
            meta.lore(listOf(line("<gray>Return to the category selector.")))
        }
        return stack
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private fun fill(inventory: Inventory, path: String) {
        if (!yaml.getBoolean("$path.enabled", true)) return
        val stack = ItemStack(material(yaml.getString("$path.material"), Material.GRAY_STAINED_GLASS_PANE))
        stack.editMeta { it.displayName(Component.empty()) }
        for (slot in 0 until inventory.size) inventory.setItem(slot, stack)
    }

    private fun questSlots(): List<Int> {
        val configured = yaml.getIntegerList("menu.list.quest-slots")
        return if (configured.size >= QuestCategory.SLOTS) configured else DEFAULT_QUEST_SLOTS
    }

    private fun material(raw: String?, fallback: Material): Material =
        raw?.let { Material.matchMaterial(it.uppercase()) } ?: fallback

    /** MiniMessage, with the client's default italic on menu text turned off. */
    private fun line(raw: String): Component = mini.deserialize("<!italic>$raw")

    // ------------------------------------------------------------------
    //  Holders
    // ------------------------------------------------------------------

    private class SelectorHolder : InventoryHolder {
        var inv: Inventory? = null
        val categories = HashMap<Int, QuestCategory>()
        override fun getInventory(): Inventory = inv!!
    }

    private class CategoryHolder(val category: QuestCategory) : InventoryHolder {
        var inv: Inventory? = null
        var backSlot: Int = -1
        val questSlots = HashMap<Int, Int>()
        override fun getInventory(): Inventory = inv!!
    }

    companion object {
        private const val SELECTOR_SIZE = 27
        private const val CATEGORY_SIZE = 54
        private const val DEFAULT_BACK_SLOT = 49
        private val DEFAULT_QUEST_SLOTS = listOf(19, 21, 23, 25)

        private fun defaultSelectorSlot(category: QuestCategory): Int = when (category) {
            QuestCategory.DAILY -> 11
            QuestCategory.WEEKLY -> 13
            QuestCategory.GENERAL -> 15
        }

        private fun defaultSelectorMaterial(category: QuestCategory): Material = when (category) {
            QuestCategory.DAILY -> Material.PAPER
            QuestCategory.WEEKLY -> Material.MAP
            QuestCategory.GENERAL -> Material.FILLED_MAP
        }

        private fun defaultStateMaterial(state: QuestManager.QuestState): Material = when (state) {
            QuestManager.QuestState.COMPLETE_UNCLAIMED -> Material.GOLD_INGOT
            QuestManager.QuestState.CLAIMED -> Material.GRAY_DYE
            else -> Material.LIME_DYE
        }
    }
}

/** Cancels every interaction with a quest screen and routes clicks to [QuestMenu]. */
class QuestMenuListener(private val plugin: DungeonPlugin) : Listener {

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val top = event.view.topInventory
        if (!plugin.questMenu.isQuestMenu(top)) return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != top) return
        val slot = event.rawSlot
        if (slot < 0 || slot >= top.size) return
        plugin.server.scheduler.runTask(plugin, Runnable { plugin.questMenu.handleClick(player, top, slot) })
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (plugin.questMenu.isQuestMenu(event.view.topInventory)) event.isCancelled = true
    }
}
