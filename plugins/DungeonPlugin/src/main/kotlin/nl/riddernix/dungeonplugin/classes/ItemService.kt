package nl.riddernix.dungeonplugin.classes

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** The class layer's tagged items: shards, the Mage staff, and projectile marks. */
class ItemService(plugin: DungeonPlugin) {

    private val itemKindKey = NamespacedKey(plugin, "item_kind")
    private val arcaneBoltKey = NamespacedKey(plugin, "arcane_bolt")
    private val focusShotKey = NamespacedKey(plugin, "focus_shot")

    fun skillShard(): ItemStack = taggedItem(
        Material.PRISMARINE_CRYSTALS,
        "§bSkill Shard",
        listOf("§7Right-click to open the skill tree.", "§7Right-click an unlocked node to revoke it."),
        SKILL_SHARD_KIND
    )

    fun soulShard(): ItemStack = taggedItem(
        Material.ECHO_SHARD,
        "§dSoul Shard",
        listOf("§7Right-click to open class switching.", "§cLocked changes reset the character to Level 1."),
        SOUL_SHARD_KIND
    )

    fun mageStaff(): ItemStack = taggedItem(
        Material.BLAZE_ROD,
        "§5Apprentice Staff",
        listOf("§7A Mage's class weapon.", "§dLeft-click: Arcane Bolt", "§aRight-click: Healing Spell"),
        STAFF_KIND
    )

    fun isStaff(item: ItemStack?): Boolean = isKind(item, STAFF_KIND)
    fun isSkillShard(item: ItemStack?): Boolean = isKind(item, SKILL_SHARD_KIND)
    fun isSoulShard(item: ItemStack?): Boolean = isKind(item, SOUL_SHARD_KIND)

    fun markArcaneBolt(projectile: Projectile) {
        projectile.persistentDataContainer.set(arcaneBoltKey, PersistentDataType.BYTE, 1)
    }

    fun isArcaneBolt(projectile: Projectile): Boolean =
        projectile.persistentDataContainer.has(arcaneBoltKey, PersistentDataType.BYTE)

    fun markFocusShot(projectile: Projectile) {
        projectile.persistentDataContainer.set(focusShotKey, PersistentDataType.BYTE, 1)
    }

    fun isFocusShot(projectile: Projectile): Boolean =
        projectile.persistentDataContainer.has(focusShotKey, PersistentDataType.BYTE)

    fun isAllowedWeapon(classType: ClassType, item: ItemStack?): Boolean {
        if (item == null || item.type.isAir) return false
        return when (classType) {
            ClassType.WARRIOR -> item.type.name.endsWith("_SWORD")
            ClassType.ARCHER -> item.type == Material.BOW
            ClassType.PALADIN -> item.type.name.endsWith("_AXE")
            ClassType.MAGE -> isStaff(item)
        }
    }

    fun isRestrictedWeapon(item: ItemStack?): Boolean {
        if (item == null || item.type.isAir) return false
        return item.type.name.endsWith("_SWORD") || item.type.name.endsWith("_AXE") ||
            item.type == Material.BOW || item.type == Material.CROSSBOW || isStaff(item)
    }

    fun give(player: Player, item: ItemStack) {
        player.inventory.addItem(item).values.forEach { overflow ->
            player.world.dropItemNaturally(player.location, overflow)
        }
    }

    fun count(player: Player, predicate: (ItemStack?) -> Boolean): Int =
        player.inventory.contents.sumOf { stack -> if (predicate(stack)) stack?.amount ?: 0 else 0 }

    fun consume(player: Player, amount: Int, predicate: (ItemStack?) -> Boolean): Boolean {
        if (amount <= 0) return true
        if (count(player, predicate) < amount) return false
        var remaining = amount
        for (slot in player.inventory.contents.indices) {
            val stack = player.inventory.getItem(slot) ?: continue
            if (!predicate(stack)) continue
            val taken = minOf(remaining, stack.amount)
            if (taken == stack.amount) player.inventory.setItem(slot, null)
            else stack.amount -= taken
            remaining -= taken
            if (remaining == 0) return true
        }
        return true
    }

    private fun taggedItem(material: Material, name: String, lore: List<String>, kind: String): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        @Suppress("DEPRECATION")
        meta.setDisplayName(name)
        @Suppress("DEPRECATION")
        meta.lore = lore
        meta.persistentDataContainer.set(itemKindKey, PersistentDataType.STRING, kind)
        item.itemMeta = meta
        return item
    }

    private fun isKind(item: ItemStack?, kind: String): Boolean =
        item?.itemMeta?.persistentDataContainer?.get(itemKindKey, PersistentDataType.STRING) == kind

    companion object {
        private const val STAFF_KIND = "mage_staff"
        private const val SKILL_SHARD_KIND = "skill_shard"
        private const val SOUL_SHARD_KIND = "soul_shard"
    }
}
