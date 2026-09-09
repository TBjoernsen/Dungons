package nl.riddernix.dungeonplugin.classes

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import nl.riddernix.dungeonplugin.DungeonPlugin
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
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.cos

/**
 * Vanilla-client ability keybind. Minecraft's Swap Hands key defaults to F
 * and can be rebound by each player in Controls; Paper exposes it through
 * PlayerSwapHandItemsEvent.
 */
class AbilityService(private val plugin: DungeonPlugin) : Listener {

    private val cooldownUntil = HashMap<UUID, Long>()
    private val mageHealCooldownUntil = HashMap<UUID, Long>()
    private val shieldExpiry = HashMap<UUID, Long>()

    /** Per Archer: the wall-clock ms until which a Wind Jump still counts for a Skyfall shot. */
    private val windJumpUntil = HashMap<UUID, Long>()

    /** Per max-rank Archer: ms until which a granted second Wind Jump charge (a forward Wind Dash) can be spent. */
    private val windDashChargeUntil = HashMap<UUID, Long>()
    private val hoveredHealTargets = HashMap<UUID, HoveredHealTarget>()
    private val originalGlowStates = HashMap<UUID, Boolean>()
    private val shieldCapacityKey = NamespacedKey(plugin, "paladin_active_shield_capacity")
    private val healHighlightTeamName = "dp_heal_hover"

    /** Refreshes the Mage's heal-target highlight. Runs several times a second so the glow tracks the crosshair. */
    fun tickHealHover() {
        plugin.server.onlinePlayers.forEach { caster ->
            val canTarget = plugin.queries.isInDungeon(caster) &&
                plugin.classes.activeClass(caster.uniqueId) == ClassType.MAGE &&
                plugin.classItems.isStaff(caster.inventory.itemInMainHand)
            updateHoveredHealTarget(caster, if (canTarget) raycastHealTarget(caster) else null)
        }
    }

    fun shutdown() {
        plugin.server.onlinePlayers.toList().forEach { updateHoveredHealTarget(it, null) }
        hoveredHealTargets.clear()
        originalGlowStates.clear()
        mageHealCooldownUntil.clear()
    }

    /**
     * True while an Archer is still inside the Wind Jump window and off the
     * ground - the condition for a full-Focus bow shot to become a Skyfall
     * AoE arrow.
     */
    @Suppress("DEPRECATION")
    fun isWindJumping(player: Player): Boolean =
        (windJumpUntil[player.uniqueId] ?: 0L) > System.currentTimeMillis() && !player.isOnGround

    fun remove(player: Player) {
        cooldownUntil.remove(player.uniqueId)
        mageHealCooldownUntil.remove(player.uniqueId)
        windJumpUntil.remove(player.uniqueId)
        windDashChargeUntil.remove(player.uniqueId)
        updateHoveredHealTarget(player, null)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMageHealAirClick(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || !event.action.isRightClick) return
        // Right-clicking a normal dungeon wall or floor should still cast the
        // heal, while buttons, containers, and similar usable blocks retain
        // their normal interaction. Material.isInteractable is deprecated for
        // being approximate, but approximate is exactly what this filter is.
        @Suppress("DEPRECATION")
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
        if (!plugin.queries.isInDungeon(player)) return
        val classType = plugin.classes.activeClass(player.uniqueId) ?: return
        if (!plugin.classItems.isAllowedWeapon(classType, player.inventory.itemInMainHand)) return
        event.isCancelled = true

        // A max-Focus Archer's granted second charge: a forward Wind Dash
        // spendable inside its window, ahead of (and ignoring) the cooldown.
        if (classType == ClassType.ARCHER &&
            (windDashChargeUntil[player.uniqueId] ?: 0L) > System.currentTimeMillis()) {
            windDashChargeUntil.remove(player.uniqueId)
            if (archerDoubleJump(player, forward = true)) {
                cooldownUntil[player.uniqueId] = System.currentTimeMillis() + cooldownMillis(classType)
            }
            return
        }

        val remaining = (cooldownUntil[player.uniqueId] ?: 0L) - System.currentTimeMillis()
        if (remaining > 0) {
            player.sendActionBar(Component.text("Ability ready in ${ceil(remaining / 1000.0).toInt()}s.", NamedTextColor.GRAY))
            return
        }
        val activated = when (classType) {
            ClassType.WARRIOR -> warriorDash(player)
            ClassType.ARCHER -> archerDoubleJump(player, forward = false)
            ClassType.PALADIN -> paladinShield(player)
            ClassType.MAGE -> mageBlink(player)
        }
        if (activated) cooldownUntil[player.uniqueId] = System.currentTimeMillis() + cooldownMillis(classType)
    }

    private fun warriorDash(player: Player): Boolean {
        val cfg = plugin.classesConfig
        // Berserk (a spent Rage bar) empowers the lunge: further, harder, with
        // a real knock-up. The Dash also feeds Rage on a connect, so the loop
        // is sword -> dash to top off -> Berserk -> empowered dash.
        val berserk = plugin.classPassives.isBerserk(player)
        val direction = horizontalDirection(player)

        val speed = cfg.getDouble("abilities.warrior.dash-speed", 1.5) *
            if (berserk) cfg.getDouble("abilities.warrior.berserk-speed-multiplier", 1.35) else 1.0
        player.velocity = direction.clone().multiply(speed).setY(if (berserk) 0.22 else 0.16)

        val damage = cfg.getDouble("abilities.warrior.bonus-damage", 4.0) *
            if (berserk) cfg.getDouble("abilities.warrior.berserk-damage-multiplier", 1.8) else 1.0
        val radius = cfg.getDouble("abilities.warrior.dash-radius", 2.6)

        val hits = player.getNearbyEntities(radius, 1.6, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it != player && it !is Player && (plugin.queries.isDungeonMob(it) || it is Monster) }
        hits.forEach { enemy ->
            enemy.damage(damage, player)
            val push = direction.clone().multiply(if (berserk) 0.55 else 0.35)
            enemy.velocity = enemy.velocity.add(push).setY(if (berserk) 0.42 else 0.16)
        }

        plugin.classFeedback.warriorDashCast(player, berserk)
        if (hits.isNotEmpty()) {
            plugin.classFeedback.warriorDashImpact(player, berserk)
            plugin.classPassives.feedRage(player, cfg.getDouble("warrior.dash-rage-on-hit", 25.0))
        }
        player.sendActionBar(Component.text(if (berserk) "Berserk Dash!" else "Dash!", NamedTextColor.RED))
        return true
    }

    /**
     * The Archer F-key. [forward] `false` is the vertical Wind Jump; `true` is
     * the forward Wind Dash - same air-only mechanics and cues, horizontal
     * launch instead of lift. A first Wind Jump at max Focus rank grants a
     * short window in which the next press becomes a Wind Dash.
     */
    private fun archerDoubleJump(player: Player, forward: Boolean): Boolean {
        @Suppress("DEPRECATION")
        if (player.isOnGround) {
            player.sendActionBar(Component.text(
                if (forward) "Wind Dash needs you airborne." else "Double Jump can only be used in the air.",
                NamedTextColor.GRAY))
            return false
        }
        val cfg = plugin.classesConfig
        val power = cfg.getDouble("abilities.archer.jump-velocity", 0.9)
        player.world.spawnParticle(Particle.CLOUD, player.location.clone().add(0.0, 0.12, 0.0), 20, 0.28, 0.05, 0.28, 0.08)
        if (forward) {
            val push = cfg.getDouble("abilities.archer.wind-dash-forward-multiplier", 1.7)
            player.velocity = horizontalDirection(player).multiply(power * push).setY(0.3)
        } else {
            player.velocity = player.velocity.clone().setY(power)
        }
        player.world.playSound(player.location, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, if (forward) 0.85f else 1.1f)

        val windowSeconds = cfg.getDouble("abilities.archer.wind-jump-window-seconds", 4.0).coerceAtLeast(0.0)
        windJumpUntil[player.uniqueId] = System.currentTimeMillis() + (windowSeconds * 1000).toLong()

        var chargeNote = ""
        if (!forward && plugin.classes.signatureRank(player.uniqueId) >=
            cfg.getInt("abilities.archer.wind-jump-double-charge-min-rank", 4)) {
            val secs = cfg.getDouble("abilities.archer.wind-jump-second-charge-seconds", 3.0).coerceAtLeast(0.0)
            windDashChargeUntil[player.uniqueId] = System.currentTimeMillis() + (secs * 1000).toLong()
            chargeNote = " §e+ Wind Dash"
        }
        val focused = plugin.classPassives.focusFull(player)
        player.sendActionBar(Component.text(
            (if (forward) "Wind Dash!" else "Wind Jump!") +
                (if (focused) " §b§lSkyfall armed" else "") + chargeNote,
            NamedTextColor.GREEN))
        return true
    }

    private fun paladinShield(player: Player): Boolean {
        val cfg = plugin.classesConfig
        val rank = plugin.classes.signatureRank(player.uniqueId).coerceAtLeast(1)
        val target = (player.getTargetEntity(12) as? Player)?.takeIf { it.world == player.world } ?: player
        val seconds = cfg.getDouble("abilities.paladin.shield-seconds", 4.0)
        val hearts = cfg.getDouble("abilities.paladin.shield-hearts", 5.0) +
            cfg.getDouble("abilities.paladin.shield-hearts-per-rank", 1.0) * (rank - 1)
        val shieldHealth = hearts * 2.0
        val absorbAmp = cfg.getInt("abilities.paladin.absorption-amplifier", 1).coerceIn(0, 4)
        val capacity = target.getAttribute(Attribute.MAX_ABSORPTION)
        capacity?.removeModifier(shieldCapacityKey)
        capacity?.addTransientModifier(AttributeModifier(shieldCapacityKey, shieldHealth, AttributeModifier.Operation.ADD_NUMBER))
        // The native effect makes the client render yellow hearts
        // consistently; the amount is immediately limited to the shield size.
        target.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, (seconds * 20).toInt(), absorbAmp, true, false, true))
        target.absorptionAmount = maxOf(target.absorptionAmount, shieldHealth)

        // Bless (high rank): scrub the ally's Slowness / Weakness and grant a
        // brief Resistance on top of the shield.
        var blessed = false
        if (rank >= cfg.getInt("abilities.paladin.shield-bless-min-rank", 3)) {
            target.removePotionEffect(PotionEffectType.SLOWNESS)
            target.removePotionEffect(PotionEffectType.WEAKNESS)
            val blessSeconds = cfg.getDouble("abilities.paladin.shield-bless-seconds", 3.0).coerceAtLeast(0.0)
            val blessTicks = (blessSeconds * 20).toInt()
            if (blessTicks > 0) {
                target.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, blessTicks, 0, true, false, true))
            }
            blessed = true
            if (target != player) {
                target.sendActionBar(Component.text(
                    "§6✦ Blessed §7- Slowness/Weakness cleansed, Resistance ${blessSeconds.toInt()}s", NamedTextColor.GOLD))
            }
        }

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
        plugin.classFeedback.paladinShieldCast(target)
        val recipient = if (target == player) "yourself" else target.name
        player.sendActionBar(Component.text(
            (if (blessed) "Blessed & shielded " else "Shielded ") + recipient + ".", NamedTextColor.GOLD))
        return true
    }

    private fun mageBlink(player: Player): Boolean {
        val data = plugin.classes.data(player.uniqueId)
        val cost = plugin.classesConfig.getDouble("abilities.mage.blink-mana-cost", 35.0)
        if (data.mana < cost) {
            player.sendActionBar(Component.text("Not enough Mana (${cost.toInt()} required).", NamedTextColor.RED))
            return false
        }
        val destination = safeBlinkDestination(player) ?: run {
            player.sendActionBar(Component.text("No safe space to blink to.", NamedTextColor.RED))
            return false
        }
        data.mana -= cost
        val momentum = player.velocity.clone()
        player.teleport(destination)
        // Teleports normally clear velocity. Reapply it next tick so Blink
        // repositions without killing a sprint, jump, or fall trajectory.
        plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) player.velocity = momentum })
        player.sendActionBar(Component.text("Blink! (-${cost.toInt()} Mana)", NamedTextColor.LIGHT_PURPLE))
        return true
    }

    private fun castMageHeal(caster: Player) {
        if (!plugin.queries.isInDungeon(caster)) return
        if (plugin.classes.activeClass(caster.uniqueId) != ClassType.MAGE) return
        if (!plugin.classItems.isStaff(caster.inventory.itemInMainHand)) return

        val now = System.currentTimeMillis()
        val remaining = (mageHealCooldownUntil[caster.uniqueId] ?: 0L) - now
        if (remaining > 0) {
            caster.sendActionBar(Component.text("Healing spell ready in ${ceil(remaining / 1000.0).toInt()}s.", NamedTextColor.GRAY))
            return
        }

        val data = plugin.classes.data(caster.uniqueId)
        val cost = plugin.classesConfig.getDouble("abilities.mage.heal-mana-cost", 50.0).coerceAtLeast(0.0)
        if (data.mana < cost) {
            caster.sendActionBar(Component.text("Not enough Mana (${cost.toInt()} required).", NamedTextColor.RED))
            return
        }

        val target = currentHealTarget(caster) ?: caster
        data.mana -= cost
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
        plugin.refreshClassPlayer(caster)
    }

    private fun safeBlinkDestination(player: Player): Location? {
        val start = player.location
        val direction = horizontalDirection(player)
        val maxDistance = plugin.classesConfig.getDouble("abilities.mage.blink-distance", 10.0)
        var result: Location? = null
        // Check every part of the route, rather than only checking the final
        // spot. Otherwise a valid space on the far side of a wall would let
        // Blink skip it.
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

    /**
     * Who a Heal cast actually lands on: commit the ally currently under the
     * highlight ([tickHealHover] validated range + cone + line of sight for it
     * a few ticks ago), as long as they are still online, alive, in the same
     * world and within `heal-range`. Only if there is no live highlight does
     * it fall back to a fresh cone check for this exact instant. Returns null
     * -> [castMageHeal] heals the caster.
     *
     * This is the fix for "highlight reaches far, heal only lands point-blank":
     * the click no longer re-runs a tight angular cone against a target that
     * is a pixel wide at range - it just confirms what is already glowing.
     */
    private fun currentHealTarget(caster: Player): Player? {
        val range = plugin.classesConfig.getDouble("abilities.mage.heal-range", 50.0).coerceAtLeast(0.0)
        val hovered = hoveredHealTargets[caster.uniqueId]?.let { plugin.server.getPlayer(it.playerId) }
        if (hovered != null && hovered.isOnline && !hovered.isDead && hovered.world == caster.world &&
            hovered.location.distanceSquared(caster.location) <= range * range) {
            return hovered
        }
        return raycastHealTarget(caster)
    }

    /**
     * The ally the caster is aiming at: the player nearest their crosshair
     * within `heal-range` and inside a `heal-aim-cone-degrees` cone, so it
     * does not need a pixel-perfect ray. Line of sight is still required
     * unless `heal-require-line-of-sight` is off - no healing through walls.
     */
    private fun raycastHealTarget(caster: Player): Player? {
        val range = plugin.classesConfig.getDouble("abilities.mage.heal-range", 50.0).coerceAtLeast(0.0)
        if (range <= 0.0) return null
        val minCos = cos(Math.toRadians(
            plugin.classesConfig.getDouble("abilities.mage.heal-aim-cone-degrees", 12.0).coerceIn(1.0, 60.0)))
        val requireLos = plugin.classesConfig.getBoolean("abilities.mage.heal-require-line-of-sight", true)
        val eye = caster.eyeLocation
        val look = eye.direction
        var best: Player? = null
        var bestAlignment = minCos
        for (other in caster.world.players) {
            if (other === caster || !other.isOnline || other.isDead) continue
            val toTarget = other.eyeLocation.toVector().subtract(eye.toVector())
            val distance = toTarget.length()
            if (distance < 0.1 || distance > range) continue
            val alignment = toTarget.clone().normalize().dot(look)
            if (alignment < bestAlignment) continue
            if (requireLos && caster.world.rayTraceBlocks(
                    eye, toTarget, distance, FluidCollisionMode.NEVER, true) != null) continue
            bestAlignment = alignment
            best = other
        }
        return best
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
            ?: caster.scoreboard.registerNewTeam(healHighlightTeamName).also {
                @Suppress("DEPRECATION")
                it.color = ChatColor.GREEN
            }
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
        (plugin.classesConfig.getDouble("abilities.${type.id}.cooldown-seconds", 5.0) * 1000).toLong()

    private fun mageHealCooldownMillis(): Long =
        (plugin.classesConfig.getDouble("abilities.mage.heal-cooldown-seconds", 30.0).coerceAtLeast(0.0) * 1000.0).toLong()

    private fun horizontalDirection(player: Player): Vector =
        player.location.direction.clone().setY(0.0).normalize()

    private data class HoveredHealTarget(val playerId: UUID, val entryName: String)
}
