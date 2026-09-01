package nl.riddernix.dungeonplugin.classes

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffectType

/** Applies the tree-derived stat bonuses to the player's live attributes. */
class AttributeService(private val plugin: DungeonPlugin) {

    fun apply(player: Player) {
        applyModifier(player, Attribute.ATTACK_DAMAGE, "attack",
            plugin.classes.statBonus(player.uniqueId, StatType.ATTACK))
        applyModifier(player, Attribute.MAX_HEALTH, "health",
            plugin.classes.statBonus(player.uniqueId, StatType.HEALTH))
        applyModifier(player, Attribute.ARMOR, "armor",
            plugin.classes.statBonus(player.uniqueId, StatType.ARMOR))
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: return
        if (player.health > maxHealth) player.health = maxHealth
    }

    /**
     * Recovery path for corrupted test stats. This deliberately restores
     * vanilla base values and removes every modifier on the three stats the
     * class layer owns.
     */
    fun forceResetCombatStats(player: Player) {
        for (attribute in listOf(Attribute.ATTACK_DAMAGE, Attribute.MAX_HEALTH, Attribute.ARMOR)) {
            val instance = player.getAttribute(attribute) ?: continue
            instance.modifiers.toList().forEach(instance::removeModifier)
            instance.baseValue = attribute.defaultValue
        }
        player.removePotionEffect(PotionEffectType.STRENGTH)
        player.removePotionEffect(PotionEffectType.SPEED)
        player.removePotionEffect(PotionEffectType.SLOWNESS)
        player.removePotionEffect(PotionEffectType.JUMP_BOOST)
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: return
        player.health = player.health.coerceAtMost(maxHealth)
    }

    private fun applyModifier(player: Player, attribute: Attribute, suffix: String, amount: Double) {
        val instance = player.getAttribute(attribute) ?: return
        val key = NamespacedKey(plugin, "stat_$suffix")

        // Clean out any modifier from an older naming scheme; without a
        // stable key, refreshes and reloads can stack duplicate bonuses.
        instance.modifiers
            .filter { it.name.startsWith("classskills_") }
            .forEach(instance::removeModifier)
        instance.removeModifier(key)
        if (amount != 0.0) {
            instance.addTransientModifier(
                AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER))
        }
    }
}
