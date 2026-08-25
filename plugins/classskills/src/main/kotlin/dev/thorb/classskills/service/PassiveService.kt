package dev.thorb.classskills.service

import dev.thorb.classskills.ClassSkillsPlugin
import dev.thorb.classskills.data.PlayerDataStore
import dev.thorb.classskills.model.ClassType
import dev.thorb.classskills.model.SkillTreeCatalog
import org.bukkit.entity.Mob
import org.bukkit.entity.Arrow
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Snowball
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import java.util.UUID
import kotlin.math.roundToInt

class PassiveService(
    private val plugin: ClassSkillsPlugin,
    private val store: PlayerDataStore,
    private val catalog: SkillTreeCatalog,
    private val items: ItemService
) {
    private var activeTaunt: ActiveTaunt? = null

    fun tick() {
        maintainTaunt()
        val now = System.currentTimeMillis()
        for (player in plugin.server.onlinePlayers) {
            val data = store.get(player.uniqueId)
            val rank = catalog.signatureRank(data)
            if (data.classType == ClassType.WARRIOR) {
                decayRageOutOfCombat(data, rank, now)
            } else if (data.classType == ClassType.MAGE) {
                // Mana and Arcane Bolt are baseline. Arcane Charge is the
                // Difficulty-3 signature enhancement, not a gate on casting.
                data.mana = (data.mana + manaRegenerationPerSecond()).coerceAtMost(maxMana(rank))
            } else if (data.classType != ClassType.MAGE) {
                data.mana = 0.0
            }
        }
    }

    fun handleIncomingDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val data = store.get(player.uniqueId)
        when (data.classType) {
            ClassType.WARRIOR -> {
                if (catalog.signatureRank(data) > 0) addRage(player, event.finalDamage * 4.0)
            }
            ClassType.ARCHER -> {
                if (data.focus > 0) {
                    data.focus = 0
                    player.sendMessage("§eYour Focus was broken.")
                }
            }
            else -> Unit
        }
    }

    fun handleDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager as? Player ?: return
        val data = store.get(damager.uniqueId)
        val rank = catalog.signatureRank(data)
        when (data.classType) {
            ClassType.WARRIOR -> if (rank > 0 && items.isAllowedWeapon(ClassType.WARRIOR, damager.inventory.itemInMainHand)) {
                addRage(damager, event.finalDamage * 6.0)
            }
            ClassType.PALADIN -> if (rank > 0 && items.isAllowedWeapon(ClassType.PALADIN, damager.inventory.itemInMainHand)) {
                buildTaunt(damager, event.finalDamage, rank)
            }
            else -> Unit
        }
    }

    /** Activates a fully charged Taunt. Called from the Paladin's Left-Shift input. */
    fun activateTaunt(player: Player): TauntActivationResult {
        val data = store.get(player.uniqueId)
        val rank = catalog.signatureRank(data)
        if (data.classType != ClassType.PALADIN || rank == 0) return TauntActivationResult.LOCKED
        if (activeTaunt?.playerId == player.uniqueId && activeTaunt!!.expiresAt > System.currentTimeMillis()) {
            return TauntActivationResult.ALREADY_ACTIVE
        }
        if (data.judgment < tauntThreshold(rank)) return TauntActivationResult.NOT_READY

        data.judgment = 0.0
        val durationTicks = tauntDurationTicks(rank)
        activeTaunt = ActiveTaunt(player.uniqueId, System.currentTimeMillis() + durationTicks * 50L)
        // Turtle Master is a potion recipe combining these two effects; Paper exposes
        // the effects themselves rather than a separate TURTLE_MASTER effect type.
        player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, durationTicks, turtleMasterResistanceAmplifier(), true, false, true))
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, durationTicks, turtleMasterSlownessAmplifier(), true, false, true))
        targetAllMobs(player)
        plugin.feedback.tauntTriggered(player)
        player.sendMessage("§6Taunt activated! §fNearby mobs now focus you.")
        plugin.refreshPlayer(player)
        return TauntActivationResult.SUCCESS
    }

    fun handleBowShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val data = store.get(player.uniqueId)
        val rank = catalog.signatureRank(data)
        if (data.classType != ClassType.ARCHER || rank == 0) return
        val focus = data.focus
        if (focus >= focusThreshold(rank)) {
            // Convert the shorter physical draw to the force of a longer vanilla draw. This
            // makes Focus feel like a real draw-speed bonus while never exceeding full draw.
            val drawnTicks = ticksForBowForce(event.force.toDouble())
            val effectiveTicks = (drawnTicks / (1.0 - fullFocusDrawSpeedBonus())).coerceAtMost(20.0)
            val currentForce = event.force.toDouble()
            val effectiveForce = bowForceForTicks(effectiveTicks)
            if (currentForce > 0.0) {
                event.projectile.velocity = event.projectile.velocity.multiply(effectiveForce / currentForce)
            }
        }
    }

    fun handleProjectileDamage(event: EntityDamageByEntityEvent, shooter: Player) {
        val data = store.get(shooter.uniqueId)
        val rank = catalog.signatureRank(data)
        if (data.classType != ClassType.ARCHER || rank == 0) return
        event.damage += archerAttackBonus(shooter)
        val currentFocus = data.focus
        val requiredHits = focusThreshold(rank)
        if (currentFocus > 0) {
            // The bar gets shorter through Focus V, so use its completion ratio rather
            // than a fixed per-hit value. Every rank still improves the full-charge bonus.
            event.damage += event.damage * (currentFocus.toDouble() / requiredHits) * fullFocusDamageBonus(rank)
        }
        data.focus = (data.focus + 1).coerceAtMost(requiredHits)
        if (data.focus == requiredHits) {
            shooter.sendActionBar(Component.text("§a§lFocus fully charged! §fLeft-click your bow for a Focus Shot."))
        } else {
            shooter.sendActionBar(Component.text("§eFocus: ${data.focus}/$requiredHits §7(land hits without taking damage)"))
        }
        plugin.refreshPlayer(shooter)
    }

    /** Fires the Focus charge as a separate, free arrow and consumes the full 10-stack charge. */
    fun castFocusShot(player: Player): FocusShotResult {
        val data = store.get(player.uniqueId)
        if (data.classType != ClassType.ARCHER || catalog.signatureRank(data) == 0) return FocusShotResult.LOCKED
        if (!items.isAllowedWeapon(ClassType.ARCHER, player.inventory.itemInMainHand)) return FocusShotResult.WRONG_WEAPON
        if (data.focus < focusThreshold(catalog.signatureRank(data))) return FocusShotResult.NOT_CHARGED

        val arrow = player.launchProjectile(Arrow::class.java)
        arrow.velocity = player.eyeLocation.direction.normalize().multiply(3.0)
        // A Focus Shot is defined as two fully charged bow hits. Preserve the full-draw
        // critical baseline before applying its explicit double-damage multiplier on hit.
        arrow.isCritical = true
        items.markFocusShot(arrow)
        data.focus = 0
        player.world.playSound(player.location, Sound.ENTITY_ARROW_SHOOT, 1.0f, 1.15f)
        player.sendActionBar(Component.text("§aFocus Shot! §fDouble-damage arrow fired."))
        plugin.refreshPlayer(player)
        return FocusShotResult.SUCCESS
    }

    fun handleFocusShotDamage(event: EntityDamageByEntityEvent, shooter: Player, projectile: Projectile) {
        val data = store.get(shooter.uniqueId)
        if (data.classType != ClassType.ARCHER) {
            event.isCancelled = true
            return
        }
        event.damage = (event.damage + archerAttackBonus(shooter)) * focusShotDamageMultiplier()
        projectile.world.spawnParticle(Particle.CRIT, projectile.location, 20, 0.12, 0.12, 0.12, 0.12)
    }

    fun handleProjectileMiss(event: ProjectileHitEvent, shooter: Player) {
        if (event.hitEntity != null) return
        val data = store.get(shooter.uniqueId)
        if (data.classType == ClassType.ARCHER && data.focus > 0) {
            data.focus = 0
            shooter.sendMessage("§eYour Focus was broken by a missed shot.")
        }
    }

    fun castArcaneBolt(player: Player): ArcaneCastResult {
        val data = store.get(player.uniqueId)
        val rank = catalog.signatureRank(data)
        if (data.classType != ClassType.MAGE) return ArcaneCastResult.MANA_LOCKED
        if (!items.isStaff(player.inventory.itemInMainHand)) return ArcaneCastResult.WRONG_WEAPON
        val manaCost = arcaneBoltManaCost()
        if (data.mana < manaCost) return ArcaneCastResult.INSUFFICIENT_MANA
        if (player.hasCooldown(org.bukkit.Material.BLAZE_ROD)) return ArcaneCastResult.COOLDOWN

        data.mana -= manaCost
        val bolt = player.launchProjectile(Snowball::class.java)
        // Keep vanilla snowball physics. It is a true projectile and can travel freely.
        bolt.velocity = player.eyeLocation.direction.normalize().multiply(1.5)
        bolt.isSilent = true
        items.markArcaneBolt(bolt)
        player.setCooldown(org.bukkit.Material.BLAZE_ROD, plugin.config.getInt("mage.arcane-bolt-cooldown-ticks", 8).coerceAtLeast(1))
        player.world.playSound(player.location, Sound.ENTITY_SNOWBALL_THROW, 0.7f, 0.8f)
        player.sendActionBar(Component.text("§dArcane Bolt §7(-${manaCost.toInt()} Mana)"))
        plugin.refreshPlayer(player)
        return ArcaneCastResult.SUCCESS
    }

    fun handleArcaneBoltDamage(event: EntityDamageByEntityEvent, shooter: Player, projectile: Projectile) {
        val data = store.get(shooter.uniqueId)
        val rank = catalog.signatureRank(data)
        if (data.classType != ClassType.MAGE) {
            event.isCancelled = true
            return
        }
        event.damage = arcaneBoltDamage(rank)
        projectile.world.spawnParticle(Particle.ENCHANT, projectile.location, 18, 0.15, 0.15, 0.15, 0.15)
    }

    /** Applies the bolt's delayed splash after Paper has finished processing its direct projectile hit. */
    fun handleArcaneBoltHit(event: ProjectileHitEvent) {
        val projectile = event.entity
        projectile.world.spawnParticle(Particle.WITCH, projectile.location, 24, 0.18, 0.18, 0.18, 0.12)
        val shooter = projectile.shooter as? Player ?: return
        if (!plugin.isInDungeon(shooter)) return
        val data = store.get(shooter.uniqueId)
        if (data.classType != ClassType.MAGE) return

        val radius = arcaneBoltSplashRadius()
        val splashDamage = arcaneBoltDamage(catalog.signatureRank(data)) * arcaneBoltSplashDamageMultiplier()
        if (radius <= 0.0 || splashDamage <= 0.0) return
        val impact = projectile.location.clone()
        val directTargetId = event.hitEntity?.uniqueId
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!shooter.isOnline || !plugin.isInDungeon(shooter)) return@Runnable
            impact.world.getNearbyEntities(impact, radius, radius, radius)
                .filterIsInstance<LivingEntity>()
                .filter { it.uniqueId != directTargetId && plugin.isDungeonMob(it) }
                .forEach { mob ->
                    // Damage attributed to the caster normally applies combat knockback.
                    // Splash is damage-only; retain the mob's pre-impact movement instead.
                    val velocity = mob.velocity.clone()
                    mob.damage(splashDamage, shooter)
                    mob.velocity = velocity
                }
            impact.world.spawnParticle(Particle.ENCHANT, impact, 28, radius / 3.0, 0.22, radius / 3.0, 0.08)
        })
    }

    fun readout(player: Player): String {
        val data = store.get(player.uniqueId)
        val rank = catalog.signatureRank(data)
        return when (data.classType) {
            ClassType.WARRIOR -> {
                if (rank == 0) "Rage: unlock free Rank I in the skill tree"
                else if (data.rageActiveUntil > System.currentTimeMillis()) {
                    "Rage $rank: BERSERK ${((data.rageActiveUntil - System.currentTimeMillis()) / 1000.0).coerceAtLeast(0.0).roundToInt()}s"
                } else "Rage $rank: ${data.rage.roundToInt()}/${rageThreshold(rank).roundToInt()}"
            }
            ClassType.ARCHER -> if (rank == 0) "Focus: unlock free Rank I in the skill tree" else if (data.focus >= focusThreshold(rank)) {
                "Focus $rank: §aFULL (${focusThreshold(rank)}/${focusThreshold(rank)}, Left Click: Focus Shot)"
            } else "Focus $rank: ${data.focus}/${focusThreshold(rank)} (build to activate)"
            ClassType.PALADIN -> if (rank == 0) "Taunt: unlock free Rank I in the skill tree" else {
                val active = activeTaunt?.playerId == player.uniqueId && activeTaunt!!.expiresAt > System.currentTimeMillis()
                if (active) "Taunt $rank: ACTIVE ${((activeTaunt!!.expiresAt - System.currentTimeMillis()) / 1000.0).coerceAtLeast(0.0).roundToInt()}s"
                else if (data.judgment >= tauntThreshold(rank)) "Taunt $rank: READY"
                else "Taunt $rank: ${data.judgment.roundToInt()}/${tauntThreshold(rank).roundToInt()} damage"
            }
            ClassType.MAGE -> if (rank == 0) {
                "Mana: ${data.mana.roundToInt()}/${maxMana(rank).roundToInt()} | Unlock free Arcane Charge Rank I"
            } else {
                "Mana: ${data.mana.roundToInt()}/${maxMana(rank).roundToInt()} | Arcane Charge $rank"
            }
            null -> "Choose a class with /class"
        }
    }

    fun experienceToNextLevel(player: Player): Int = plugin.progression.experienceToNextLevel(player)

    /** Sidebar-friendly readout. A charged Taunt deliberately uses two compact lines. */
    fun readoutLines(player: Player): List<String> {
        val data = store.get(player.uniqueId)
        val rank = catalog.signatureRank(data)
        return if (data.classType == ClassType.PALADIN && rank > 0 &&
            activeTaunt?.playerId != player.uniqueId && data.judgment >= tauntThreshold(rank)
        ) {
            listOf("Taunt $rank: READY", "§6[SNEAK] §fto activate")
        } else {
            listOf(readout(player))
        }
    }

    private fun addRage(player: Player, amount: Double) {
        val data = store.get(player.uniqueId)
        val rank = catalog.signatureRank(data)
        if (rank == 0 || data.rageActiveUntil > System.currentTimeMillis()) return
        if (amount <= 0.0) return
        data.lastRageCombatAt = System.currentTimeMillis()
        data.rage = (data.rage + amount).coerceAtMost(rageThreshold(rank))
        if (data.rage >= rageThreshold(rank)) {
            data.rage = 0.0
            val durationTicks = 60 + rank * 20
            data.rageActiveUntil = System.currentTimeMillis() + durationTicks * 50L
            player.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, durationTicks, if (rank >= 4) 1 else 0, true, false, true))
            player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, durationTicks, if (rank >= 5) 1 else 0, true, false, true))
            plugin.feedback.rageTriggered(player)
            player.sendMessage("§cRage erupts! §fDamage and speed increased.")
        }
    }

    private fun buildTaunt(player: Player, damage: Double, rank: Int) {
        val data = store.get(player.uniqueId)
        val wasReady = data.judgment >= tauntThreshold(rank)
        data.judgment = (data.judgment + damage.coerceAtLeast(0.0)).coerceAtMost(tauntThreshold(rank))
        if (!wasReady && data.judgment >= tauntThreshold(rank)) {
            player.sendActionBar(
                Component.text("Taunt ready! Press [", NamedTextColor.GOLD)
                    .append(Component.keybind("key.sneak").color(NamedTextColor.YELLOW))
                    .append(Component.text("] to activate.", NamedTextColor.WHITE))
            )
            plugin.refreshPlayer(player)
        }
    }

    private fun rageThreshold(rank: Int): Double = 100.0 - (rank - 1) * 8.0

    private fun decayRageOutOfCombat(data: dev.thorb.classskills.model.PlayerSkillData, rank: Int, now: Long) {
        if (rank == 0 || data.rage <= 0.0 || data.rageActiveUntil > now) return
        val idleMillis = now - data.lastRageCombatAt
        if (idleMillis < rageDecayDelayMillis()) return
        data.rage = (data.rage - rageDecayPerSecond()).coerceAtLeast(0.0)
    }

    private fun rageDecayDelayMillis(): Long =
        (plugin.config.getDouble("warrior.rage-decay-delay-seconds", 15.0).coerceAtLeast(0.0) * 1_000.0).toLong()

    private fun rageDecayPerSecond(): Double =
        plugin.config.getDouble("warrior.rage-decay-per-second", 10.0).coerceAtLeast(0.0)

    /** Focus I-V reduce the bar; Focus VI keeps the five-hit bar and upgrades the shot. */
    private fun focusThreshold(rank: Int): Int =
        (10 - rank).coerceAtLeast(5)
    private fun fullFocusDamageBonus(rank: Int): Double =
        (0.20 + rank * 0.05).coerceAtMost(0.50)
    /** Focus rank changes charge speed and regular-shot bonuses; a spent Focus Shot is always 2×. */
    private fun focusShotDamageMultiplier(): Double = 2.0
    private fun archerAttackBonus(player: Player): Double =
        (player.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 1.0) *
            plugin.config.getDouble("archer.attack-stat-damage-multiplier", 1.0).coerceAtLeast(0.0)
    private fun tauntThreshold(rank: Int): Double =
        (plugin.config.getDouble("paladin.taunt-damage-threshold", 75.0) - (rank - 1) * 4.0).coerceAtLeast(20.0)
    private fun tauntDurationTicks(rank: Int): Int =
        ((plugin.config.getDouble("paladin.taunt-duration-seconds", 8.0) + (rank - 1)) * 20.0).roundToInt().coerceAtLeast(20)
    private fun turtleMasterResistanceAmplifier(): Int =
        plugin.config.getInt("paladin.turtle-master-resistance-amplifier", 2).coerceIn(0, 4)
    private fun turtleMasterSlownessAmplifier(): Int =
        plugin.config.getInt("paladin.turtle-master-slowness-amplifier", 3).coerceIn(0, 5)

    private fun maintainTaunt() {
        val taunt = activeTaunt ?: return
        val player = plugin.server.getPlayer(taunt.playerId)
        if (player == null || !player.isOnline || taunt.expiresAt <= System.currentTimeMillis() ||
            store.get(player.uniqueId).classType != ClassType.PALADIN
        ) {
            activeTaunt = null
            return
        }
        targetAllMobs(player)
    }

    /** The sole active record means the most recently activated Paladin always wins aggro. */
    private fun targetAllMobs(player: Player) {
        player.world.entities.filterIsInstance<Mob>().forEach { mob ->
            if (mob.isValid && !mob.isDead) mob.target = player
        }
    }
    private fun maxMana(rank: Int): Double =
        plugin.config.getDouble("mage.base-max-mana", 200.0).coerceAtLeast(1.0) + rank * 20.0
    private fun manaRegenerationPerSecond(): Double =
        plugin.config.getDouble("mage.mana-regeneration-per-second", 10.0).coerceAtLeast(0.1)
    private fun arcaneBoltManaCost(): Double = plugin.config.getDouble("mage.arcane-bolt-mana-cost", 20.0).coerceAtLeast(1.0)
    private fun arcaneBoltDamage(rank: Int): Double =
        plugin.config.getDouble("mage.arcane-bolt-base-damage", 5.0) +
            plugin.config.getDouble("mage.arcane-bolt-damage-per-rank", 1.5) * rank
    private fun arcaneBoltSplashRadius(): Double =
        plugin.config.getDouble("mage.arcane-bolt-splash-radius", 1.5).coerceAtLeast(0.0)
    private fun arcaneBoltSplashDamageMultiplier(): Double =
        (plugin.config.getDouble("mage.arcane-bolt-splash-damage-percent", 50.0) / 100.0).coerceAtLeast(0.0)
    private fun fullFocusDrawSpeedBonus(): Double =
        (plugin.config.getDouble("focus.full-draw-speed-percent", 25.0) / 100.0).coerceIn(0.0, 0.75)

    private fun fullFocusDrawPercent(): Int = (fullFocusDrawSpeedBonus() * 100.0).roundToInt()

    /** Vanilla's bow-charge curve: f = (x² + 2x) / 3, capped at full charge. */
    private fun bowForceForTicks(ticks: Double): Double {
        val draw = ticks.coerceAtLeast(0.0) / 20.0
        return ((draw * draw + 2.0 * draw) / 3.0).coerceAtMost(1.0)
    }

    private fun ticksForBowForce(force: Double): Double {
        val clamped = force.coerceIn(0.0, 1.0)
        return ((kotlin.math.sqrt(1.0 + 3.0 * clamped) - 1.0) * 20.0).coerceIn(0.0, 20.0)
    }
}

private data class ActiveTaunt(val playerId: UUID, val expiresAt: Long)

enum class ArcaneCastResult { SUCCESS, MANA_LOCKED, WRONG_WEAPON, INSUFFICIENT_MANA, COOLDOWN }

enum class FocusShotResult { SUCCESS, LOCKED, WRONG_WEAPON, NOT_CHARGED }

enum class TauntActivationResult { SUCCESS, LOCKED, NOT_READY, ALREADY_ACTIVE }
