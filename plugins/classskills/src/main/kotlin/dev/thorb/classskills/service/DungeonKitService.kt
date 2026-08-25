package dev.thorb.classskills.service

import dev.thorb.classskills.ClassSkillsPlugin
import dev.thorb.classskills.data.PlayerDataStore
import dev.thorb.classskills.model.ClassType
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.io.IOException
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration

/**
 * Temporarily replaces a dungeon entrant's inventory with their class kit. Snapshots
 * are persisted immediately so they remain recoverable across a plugin/server reload.
 */
class DungeonKitService(
    private val plugin: ClassSkillsPlugin,
    private val store: PlayerDataStore,
    private val items: ItemService
) {
    private val file = File(plugin.dataFolder, "dungeon-kit-snapshots.yml")
    private val snapshots = mutableMapOf<UUID, InventorySnapshot>()

    init {
        load()
    }

    fun updateDungeonState(player: Player, inDungeon: Boolean) {
        val hasSnapshot = player.uniqueId in snapshots
        when {
            inDungeon && !hasSnapshot -> equipKit(player)
            !inDungeon && hasSnapshot -> restoreInventory(player)
        }
    }

    private fun equipKit(player: Player) {
        val classType = store.get(player.uniqueId).classType ?: return
        snapshots[player.uniqueId] = InventorySnapshot.capture(player)
        save()

        player.inventory.storageContents = arrayOfNulls(36)
        player.inventory.armorContents = arrayOfNulls(4)
        player.inventory.setItemInOffHand(ItemStack(Material.AIR))
        player.inventory.heldItemSlot = 0

        when (classType) {
            ClassType.WARRIOR -> player.inventory.setItem(0, ItemStack(Material.NETHERITE_SWORD))
            ClassType.ARCHER -> {
                val bow = ItemStack(Material.BOW)
                bow.addEnchantment(Enchantment.INFINITY, 1)
                player.inventory.setItem(0, bow)
                player.inventory.setItem(8, ItemStack(Material.ARROW))
            }
            ClassType.PALADIN -> player.inventory.setItem(0, ItemStack(Material.NETHERITE_AXE))
            ClassType.MAGE -> player.inventory.setItem(0, items.mageStaff())
        }
        player.updateInventory()
        player.sendMessage("§6Dungeon kit equipped. §7Your inventory will return when the dungeon ends.")
    }

    private fun restoreInventory(player: Player) {
        val snapshot = snapshots.remove(player.uniqueId) ?: return
        snapshot.restore(player)
        save()
        player.updateInventory()
        player.sendMessage("§aYour pre-dungeon inventory has been restored.")
    }

    private fun load() {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getConfigurationSection("snapshots")?.getKeys(false)?.forEach { rawUuid ->
            val uuid = runCatching { UUID.fromString(rawUuid) }.getOrNull() ?: return@forEach
            snapshots[uuid] = InventorySnapshot.read(yaml, "snapshots.$rawUuid")
        }
    }

    private fun save() {
        val yaml = YamlConfiguration()
        snapshots.forEach { (uuid, snapshot) -> snapshot.write(yaml, "snapshots.$uuid") }
        try {
            yaml.save(file)
        } catch (exception: IOException) {
            plugin.logger.severe("Could not save dungeon kit snapshots: ${exception.message}")
        }
    }
}

private data class InventorySnapshot(
    val storage: Array<ItemStack?>,
    val armor: Array<ItemStack?>,
    val offHand: ItemStack?,
    val heldSlot: Int
) {
    fun restore(player: Player) {
        player.inventory.storageContents = storage.map { it?.clone() }.toTypedArray()
        player.inventory.armorContents = armor.map { it?.clone() }.toTypedArray()
        player.inventory.setItemInOffHand(offHand?.clone() ?: ItemStack(Material.AIR))
        player.inventory.heldItemSlot = heldSlot.coerceIn(0, 8)
    }

    fun write(yaml: YamlConfiguration, path: String) {
        storage.forEachIndexed { slot, item -> yaml.set("$path.storage.$slot", item) }
        armor.forEachIndexed { slot, item -> yaml.set("$path.armor.$slot", item) }
        yaml.set("$path.off-hand", offHand)
        yaml.set("$path.held-slot", heldSlot)
    }

    companion object {
        fun capture(player: Player): InventorySnapshot = InventorySnapshot(
            player.inventory.storageContents.map { it?.clone() }.toTypedArray(),
            player.inventory.armorContents.map { it?.clone() }.toTypedArray(),
            player.inventory.itemInOffHand.clone(),
            player.inventory.heldItemSlot
        )

        fun read(yaml: YamlConfiguration, path: String): InventorySnapshot = InventorySnapshot(
            Array(36) { slot -> yaml.getItemStack("$path.storage.$slot") },
            Array(4) { slot -> yaml.getItemStack("$path.armor.$slot") },
            yaml.getItemStack("$path.off-hand"),
            yaml.getInt("$path.held-slot", 0)
        )
    }
}
