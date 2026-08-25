package dev.thorb.classskills.service

import dev.thorb.classskills.ClassSkillsPlugin
import dev.thorb.classskills.model.ClassType
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.ChatColor
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import kotlin.math.ceil

/**
 * Vanilla-client ability keybind. Minecraft's Swap Hands key defaults to F and can be
 * rebound by each player in Controls; Paper exposes it through PlayerSwapHandItemsEvent.
 */
class AbilityService(private val plugin: ClassSkillsPlugin) : Listener {
    private val cooldownUntil = mutableMapOf<UUID, Long>()
    private val mageHealCooldownUntil = mutableMapOf<UUID, Long>()
    private val shieldExpiry = mutableMapOf<UUID, Long>()
    private val hoveredHealTargets = mutableMapOf<UUID, HoveredHealTarget>()
    private val originalGlowStates = mutableMapOf<UUID, Boolean>()
    private val shieldCapacityKey = NamespacedKey(plugin, "paladin_active_shield_capacity")
    private val healHighlightTeamName = "cs_heal_hover"

    /** Called by the plugin's existing periodic task to maintain Mage heal targeting. */
    fun tick() {
        plugin.server.onlinePlayers.forEach { caster ->
            val canTarget = plugin.isInDungeon(caster) &&
                plugin.store.get(caster.uniqueId).classType == ClassType.MAGE &&
                plugin.items.isStaff(caster.inventory.itemInMainHand)
            updateHoveredHealTarget(caster, if (canTarget) raycastHealTarget(caster) else null)
        }
    }

    fun shutdown() {
        plugin.server.onlinePlayers.toList().forEach { updateHoveredHealTarget(it, null) }
        hoveredHealTargets.clear()
        originalGlowStates.clear()
        mageHealCooldownUntil.clear()
    }

    fun remove(player: Player) {
        cooldownUntil.remove(player.uniqueId)
        mageHealCooldownUntil.remove(player.uniqueId)
        updateHoveredHealTarget(player, null)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMageHealAirClick(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || !event.action.isRightClick) return
        // Right-clicking a normal dungeon wall or floor should still cast the heal, while
        // buttons, containers, and similar usable blocks retain their normal interaction.
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.clickedBlock?.type?.isInteractable == true) return
        castMageHeal(event.player)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMageHealPlayerClick(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND || event.rightClicked !is Player) return
        castMageHeal(event.player)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        if (!plugin.isInDungeon(player)) return
        val classType = plugin.store.get(player.uniqueId).classType ?: return
        if (!plugin.items.isAllowedWeapon(classType, player.inventory.itemInMainHand)) return
        event.isCancelled = true
        val remaining = (cooldownUntil[player.uniqueId] ?: 0L) - System.currentTimeMillis()
        if (remaining > 0) {
            player.sendActionBar(Component.text("Ability ready in ${ceil(remaining / 1000.0).toInt()}s.", NamedTextColor.GRAY))
            return
        }
        val activated = when (classType) {
            ClassType.WARRIOR -> warriorDash(player)
            ClassType.ARCHER -> archerDoubleJump(player)
            ClassType.PALADIN -> paladinShield(player)
            ClassType.MAGE -> mageBlink(player)
        }
        if (activated) cooldownUntil[player.uniqueId] = System.currentTimeMillis() + cooldownMillis(classType)
    }

    private fun warriorDash(player: Player): Boolean {
        val direction = horizontalDirection(player)
        player.velocity = direction.multiply(plugin.config.getDouble("abilities.warrior.dash-speed", 1.5)).setY(0.16)
        val damage = plugin.config.getDouble("abilities.warrior.bonus-damage", 4.0)
        player.getNearbyEntities(2.4, 1.5, 2.4).filterIsInstance<Monster>().forEach { enemy ->
            enemy.damage(damage, player)
            enemy.velocity = enemy.velocity.add(direction.clone().multiply(0.35)).setY(0.16)
        }
        player.sendActionBar(Component.text("Dash!", NamedTextColor.RED))
        return true
    }

    private fun archerDoubleJump(player: Player): Boolean {
        if (player.isOnGround) {
            player.sendActionBar(Component.text("Double Jump can only be used in the air.", NamedTextColor.GRAY))
            return false
        }
        player.world.spawnParticle(Particle.CLOUD, player.location.clone().add(0.0, 0.12, 0.0), 20, 0.28, 0.05, 0.28, 0.08)
        player.velocity = player.velocity.clone().setY(plugin.config.getDouble("abilities.archer.jump-velocity", 0.9))
        player.sendActionBar(Component.text("Wind Jump!", NamedTextColor.GREEN))
        return true
    }

    private fun paladinShield(player: Player): Boolean {
        val target = (player.getTargetEntity(12) as? Player)?.takeIf { it.world == player.world } ?: player
        val seconds = plugin.config.getDouble("abilities.paladin.shield-seconds", 4.0)
        val shieldHealth = plugin.config.getDouble("abilities.paladin.shield-hearts", 5.0) * 2.0
        val capacity = target.getAttribute(Attribute.MAX_ABSORPTION)
        capacity?.removeModifier(shieldCapacityKey)
        capacity?.addTransientModifier(AttributeModifier(shieldCapacityKey, shieldHealth, AttributeModifier.Operation.ADD_NUMBER))
        // The native effect makes the client render yellow hearts consistently; the
        // amount is immediately limited to the requested five-heart shield.
        target.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, (seconds * 20).toInt(), 1, true, false, true))
        target.absorptionAmount = maxOf(target.absorptionAmount, shieldHealth)
        val expiresAt = System.currentTimeMillis() + (seconds * 1000).toLong()
        shieldExpiry[target.uniqueId] = expiresAt
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (target.isOnline && shieldExpiry[target.uniqueId] == expiresAt) {
                target.absorptionAmount = shieldHealth
            }
        })
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!target.isOnline || shieldExpiry[target.uniqueId] != expiresAt) return@Runnable
            target.getAttribute(Attribute.MAX_ABSORPTION)?.removeModifier(shieldCapacityKey)
            target.removePotionEffect(PotionEffectType.ABSORPTION)
            if (target.absorptionAmount <= shieldHealth) target.absorptionAmount = 0.0
            shieldExpiry.remove(target.uniqueId)
        }, (seconds * 20).toLong())
        val recipient = if (target == player) "yourself" else target.name
        player.sendActionBar(Component.text("Shielded $recipient.", NamedTextColor.GOLD))
        return true
    }

    private fun mageBlink(player: Player): Boolean {
        val data = plugin.store.get(player.uniqueId)
        val cost = plugin.config.getDouble("abilities.mage.blink-mana-cost", 35.0)
        if (data.mana < cost) {
            player.sendActionBar(Component.text("Not enough Mana (${cost.toInt()} required).", NamedTextColor.RED))
            return false
        }
        val destination = safeBlinkDestination(player) ?: run {
            player.sendActionBar(Component.text("No safe space to blink to.", NamedTextColor.RED))
            return false
        }
        data.mana -= cost
        plugin.store.save(player.uniqueId)
        val momentum = player.velocity.clone()
        player.teleport(destination)
        // Teleports normally clear velocity. Reapply it next tick so Blink repositions
        // without killing a sprint, jump, or fall trajectory.
        plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) player.velocity = momentum })
        player.sendActionBar(Component.text("Blink! (-${cost.toInt()} Mana)", NamedTextColor.LIGHT_PURPLE))
        return true
    }

    private fun castMageHeal(caster: Player) {
        if (!plugin.isInDungeon(caster)) return
        if (plugin.store.get(caster.uniqueId).classType != ClassType.MAGE) return
        if (!plugin.items.isStaff(caster.inventory.itemInMainHand)) return

        val now = System.currentTimeMillis()
        val remaining = (mageHealCooldownUntil[caster.uniqueId] ?: 0L) - now
        if (remaining > 0) {
            caster.sendActionBar(Component.text("Healing spell ready in ${ceil(remaining / 1000.0).toInt()}s.", NamedTextColor.GRAY))
            return
        }

        val data = plugin.store.get(caster.uniqueId)
        val cost = plugin.config.getDouble("abilities.mage.heal-mana-cost", 50.0).coerceAtLeast(0.0)
        if (data.mana < cost) {
            caster.sendActionBar(Component.text("Not enough Mana (${cost.toInt()} required).", NamedTextColor.RED))
            return
        }

        val target = raycastHealTarget(caster) ?: caster
        data.mana -= cost
        plugin.store.save(caster.uniqueId)
        mageHealCooldownUntil[caster.uniqueId] = now + mageHealCooldownMillis()
        target.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 100, 1, true, true, true))
        val effectLocation = target.location.clone().add(0.0, 1.0, 0.0)
        target.world.spawnParticle(Particle.HEART, effectLocation, 10, 0.35, 0.45, 0.35, 0.02)
        target.world.spawnParticle(Particle.HAPPY_VILLAGER, effectLocation, 16, 0.38, 0.5, 0.38, 0.05)
        target.world.playSound(effectLocation, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.25f)

        if (target == caster) {
            caster.sendMessage(Component.text("You healed yourself with Regeneration II. (-${cost.toInt()} Mana)", NamedTextColor.LIGHT_PURPLE))
        } else {
            caster.sendMessage(Component.text("You healed ${target.name} with Regeneration II. (-${cost.toInt()} Mana)", NamedTextColor.LIGHT_PURPLE))
            target.sendMessage(Component.text("${caster.name} healed you with Regeneration II.", NamedTextColor.GREEN))
        }
        plugin.refreshPlayer(caster)
    }

    private fun safeBlinkDestination(player: Player): Location? {
        val start = player.location
        val direction = horizontalDirection(player)
        val maxDistance = plugin.config.getDouble("abilities.mage.blink-distance", 7.0)
        var result: Location? = null
        // Check every part of the route, rather than only checking the final spot.
        // Otherwise a valid space on the far side of a wall would let Blink skip it.
        var distance = 0.25
        while (distance <= maxDistance) {
            val candidate = start.clone().add(direction.clone().multiply(distance))
            val feetClear = candidate.block.isPassable
            val headClear = candidate.clone().add(0.0, 1.0, 0.0).block.isPassable
            if (!feetClear || !headClear) break
            result = candidate
            distance += 0.25
        }
        return result
    }

    /** Finds the closest player in the caster's exact line of sight without looking through blocks. */
    private fun raycastHealTarget(caster: Player): Player? {
        val eyeLocation = caster.eyeLocation
        val hit = caster.world.rayTrace(
            eyeLocation,
            eyeLocation.direction,
            plugin.config.getDouble("abilities.mage.heal-range", 20.0).coerceAtLeast(0.0),
            FluidCollisionMode.NEVER,
            true,
            0.35
        ) { candidate ->
            candidate is Player && candidate.uniqueId != caster.uniqueId && candidate.isOnline && !candidate.isDead
        }
        return hit?.hitEntity as? Player
    }

    private fun updateHoveredHealTarget(caster: Player, target: Player?) {
        val casterId = caster.uniqueId
        val previous = hoveredHealTargets[casterId]
        if (previous?.playerId == target?.uniqueId) {
            target?.let {
                it.isGlowing = true
                addHealHighlight(caster, it)
            }
            return
        }

        previous?.let { removeHealHighlight(caster, it.entryName) }
        hoveredHealTargets.remove(casterId)
        previous?.let { releaseGlow(it.playerId) }
        if (target == null) {
            return
        }

        hoveredHealTargets[casterId] = HoveredHealTarget(target.uniqueId, target.name)
        originalGlowStates.putIfAbsent(target.uniqueId, target.isGlowing)
        target.isGlowing = true
        addHealHighlight(caster, target)
    }

    private fun addHealHighlight(caster: Player, target: Player) {
        val team = caster.scoreboard.getTeam(healHighlightTeamName)
            ?: caster.scoreboard.registerNewTeam(healHighlightTeamName).also { it.color = ChatColor.GREEN }
        team.addEntry(target.name)
    }

    private fun removeHealHighlight(caster: Player, entryName: String) {
        caster.scoreboard.getTeam(healHighlightTeamName)?.removeEntry(entryName)
    }

    private fun releaseGlow(targetId: UUID) {
        if (hoveredHealTargets.values.any { it.playerId == targetId }) return
        val wasGlowing = originalGlowStates.remove(targetId) ?: return
        plugin.server.getPlayer(targetId)?.isGlowing = wasGlowing
    }

    private fun cooldownMillis(type: ClassType): Long =
        (plugin.config.getDouble("abilities.${type.name.lowercase()}.cooldown-seconds", 5.0) * 1000).toLong()
    private fun mageHealCooldownMillis(): Long =
        (plugin.config.getDouble("abilities.mage.heal-cooldown-seconds", 30.0).coerceAtLeast(0.0) * 1000.0).toLong()
    private fun horizontalDirection(player: Player): Vector = player.location.direction.clone().setY(0.0).normalize()

    private data class HoveredHealTarget(val playerId: UUID, val entryName: String)
}
