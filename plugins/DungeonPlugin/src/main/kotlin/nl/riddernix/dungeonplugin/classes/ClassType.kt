package nl.riddernix.dungeonplugin.classes

import org.bukkit.Material

/**
 * The four playable classes.
 *
 * The id (lowercase name) doubles as the class id in skills.yml, so the
 * progression, the tree and the combat layer all speak the same four strings.
 */
enum class ClassType(
    val displayName: String,
    val tabPrefix: String,
    val icon: Material,
    val weaponDescription: String,
    val passiveName: String
) {
    WARRIOR("Warrior", "§c[Warrior] §r", Material.IRON_SWORD, "swords", "Rage"),
    ARCHER("Archer", "§a[Archer] §r", Material.BOW, "bows", "Focus"),
    PALADIN("Paladin", "§6[Paladin] §r", Material.IRON_AXE, "axes", "Taunt"),
    MAGE("Mage", "§5[Mage] §r", Material.BLAZE_ROD, "custom staffs", "Arcane Charge");

    val id: String get() = name.lowercase()

    companion object {
        fun fromInput(value: String?): ClassType? = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true) || it.displayName.equals(value, ignoreCase = true)
        }
    }
}

/** The three stat lines a skill node can raise. */
enum class StatType(val displayName: String, val configName: String) {
    ATTACK("Attack", "attack"),
    HEALTH("Max Health", "health"),
    ARMOR("Armor", "armor");

    companion object {
        fun fromConfig(value: String?): StatType? = entries.firstOrNull {
            it.configName.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true)
        }
    }
}
