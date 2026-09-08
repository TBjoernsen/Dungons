package nl.riddernix.dungeonplugin.classes

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Arrow
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The four signature passives: Rage, Focus, Taunt and Arcane Charge. */
class PassiveService(private val plugin: DungeonPlugin) {

    private var activeTaunt: ActiveTaunt? = null

    fun tick() {
        maintainTaunt()
        val now = System.currentTimeMillis()
        for (player in plugin.server.onlinePlayers) {
            val data = plugin.classes.data(player.uniqueId)
            val rank = plugin.classes.signatureRank(player.uniqueId)
            val classType = plugin.classes.activeClass(player.uniqueId)
            if (classType == ClassType.WARRIOR) {
                decayRageOutOfCombat(data, rank, now)
            } else if (classType == ClassType.MAGE) {
                // Mana and Arcane Bolt are baseline. Arcane Charge is the
                // Difficulty-3 signature enhancement, not a gate on casting.
                data.mana = (data.mana + manaRegenerationPerSecond()).coerceAtMost(maxMana(rank))
            } else {
                data.mana = 0.0
            }
            // A quiet aura while a Focus bar is full, so a banked Focus Shot
            // is not something you forget you are holding.
            if (classType == ClassType.ARCHER && rank > 0 && data.focus >= focusThreshold(rank) &&
                plugin.queries.isInDungeon(player)) {
                player.world.spawnParticle(Particle.END_ROD,
                    player.location.clone().add(0.0, 1.1, 0.0), 2, 0.3, 0.45, 0.3, 0.0)
            }
        }
    }

    fun handleIncomingDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val data = plugin.classes.data(player.uniqueId)
        when (plugin.classes.activeClass(player.uniqueId)) {
            ClassType.WARRIOR -> {
                if (plugin.classes.signatureRank(player.uniqueId) > 0) addRage(player, event.finalDamage * 4.0)
            }
            ClassType.ARCHER -> {
                if (data.focus > 0) {
                    // Chip damage only nicks concentration; a real hit shatters
                    // it. Keeps Focus playable in a busy dungeon instead of
                    // wiping on every stray arrow.
                    val breakThreshold = plugin.classesConfig.getDouble("archer.focus-break-damage-threshold", 4.0)
                    if (event.finalDamage >= breakThreshold) {
                        data.focus = 0
                        player.sendActionBar(Component.text("§cFocus shattered!"))
                    } else {
                        val chip = maxOf(1, plugin.classesConfig.getInt("archer.focus-chip-penalty", 1))
                        data.focus = (data.focus - chip).coerceAtLeast(0)
                        player.sendActionBar(Component.text("§eFocus rattled §7(-$chip)"))
                    }
                    plugin.refreshClassPlayer(player)
                }
            }
            else -> Unit
        }
    }

    fun handleDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager as? Player ?: return
        val rank = plugin.classes.signatureRank(damager.uniqueId)
        when (plugin.classes.activeClass(damager.uniqueId)) {
            ClassType.WARRIOR -> if (rank > 0 && plugin.classItems.isAllowedWeapon(ClassType.WARRIOR, damager.inventory.itemInMainHand)) {
                addRage(damager, event.finalDamage * 6.0)
            }
            ClassType.PALADIN -> if (rank > 0 && plugin.classItems.isAllowedWeapon(ClassType.PALADIN, damager.inventory.itemInMainHand)) {
                buildTaunt(damager, event.finalDamage, rank)
            }
            else -> Unit
        }
    }

    /** Activates a fully charged Taunt. Called from the Paladin's Left-Shift input. */
    fun activateTaunt(player: Player): TauntActivationResult {
        val data = plugin.classes.data(player.uniqueId)
        val rank = plugin.classes.signatureRank(player.uniqueId)
        if (plugin.classes.activeClass(player.uniqueId) != ClassType.PALADIN || rank == 0) return TauntActivationResult.LOCKED
        val taunt = activeTaunt
        if (taunt?.playerId == player.uniqueId && taunt.expiresAt > System.currentTimeMillis()) {
            return TauntActivationResult.ALREADY_ACTIVE
        }
        if (data.judgment < tauntThreshold(rank)) return TauntActivationResult.NOT_READY

        data.judgment = 0.0
        val durationTicks = tauntDurationTicks(rank)
        activeTaunt = ActiveTaunt(player.uniqueId, System.currentTimeMillis() + durationTicks * 50L)
        // Turtle Master is a potion recipe combining these two effects; Paper
        // exposes the effects themselves rather than a separate TURTLE_MASTER
        // effect type.
        player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, durationTicks, turtleMasterResistanceAmplifier(), true, false, true))
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, durationTicks, turtleMasterSlownessAmplifier(), true, false, true))
        targetAllMobs(player)
        plugin.classFeedback.tauntTriggered(player)
        player.sendMessage("§6Taunt activated! §fNearby mobs now focus you.")
        plugin.refreshClassPlayer(player)
        return TauntActivationResult.SUCCESS
    }

    fun handleBowShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val data = plugin.classes.data(player.uniqueId)
        val rank = plugin.classes.signatureRank(player.uniqueId)
        if (plugin.classes.activeClass(player.uniqueId) != ClassType.ARCHER || rank == 0) return
        val focus = data.focus
        if (focus >= focusThreshold(rank)) {
            // Convert the shorter physical draw to the force of a longer
            // vanilla draw. This makes Focus feel like a real draw-speed
            // bonus while never exceeding full draw.
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
        val data = plugin.classes.data(shooter.uniqueId)
        val rank = plugin.classes.signatureRank(shooter.uniqueId)
        if (plugin.classes.activeClass(shooter.uniqueId) != ClassType.ARCHER || rank == 0) return
        event.damage += archerAttackBonus(shooter)
        val currentFocus = data.focus
        val requiredHits = focusThreshold(rank)
        if (currentFocus > 0) {
            // The bar gets shorter through Focus V, so use its completion
            // ratio rather than a fixed per-hit value. Every rank still
            // improves the full-charge bonus.
            event.damage += event.damage * (currentFocus.toDouble() / requiredHits) * fullFocusDamageBonus(rank)
        }
        data.focus = (data.focus + 1).coerceAtMost(requiredHits)
        if (data.focus == requiredHits) {
            shooter.sendActionBar(Component.text("§a§lFocus fully charged! §fLeft-click your bow for a Focus Shot."))
        } else {
            shooter.sendActionBar(Component.text("§eFocus: ${data.focus}/$requiredHits §7(land hits without taking damage)"))
        }
        plugin.refreshClassPlayer(shooter)
    }

    /** Fires the Focus charge as a separate, free arrow and consumes the full charge. */
    fun castFocusShot(player: Player): FocusShotResult {
        val data = plugin.classes.data(player.uniqueId)
        val rank = plugin.classes.signatureRank(player.uniqueId)
        if (plugin.classes.activeClass(player.uniqueId) != ClassType.ARCHER || rank == 0) return FocusShotResult.LOCKED
        if (!plugin.classItems.isAllowedWeapon(ClassType.ARCHER, player.inventory.itemInMainHand)) return FocusShotResult.WRONG_WEAPON
        if (data.focus < focusThreshold(rank)) return FocusShotResult.NOT_CHARGED

        val arrow = player.launchProjectile(Arrow::class.java)
        arrow.velocity = player.eyeLocation.direction.normalize()
            .multiply(plugin.classesConfig.getDouble("archer.focus-shot-speed", 3.4))
        // A Focus Shot is defined as two fully charged bow hits. Keep the
        // full-draw critical baseline; the damage multiplier is applied on hit.
        arrow.isCritical = true
        // High Focus ranks let the shot punch through and carry on.
        val pierceFromRank = plugin.classesConfig.getInt("archer.focus-shot-pierce-from-rank", 4)
        arrow.pierceLevel = if (rank >= pierceFromRank) (rank - pierceFromRank + 1).coerceAtMost(4) else 0
        arrow.isGlowing = true
        plugin.classItems.markFocusShot(arrow)
        data.focus = 0
        plugin.classFeedback.focusShotFired(player, arrow)
        player.sendActionBar(Component.text("§b§lFOCUS SHOT"))
        plugin.refreshClassPlayer(player)
        return FocusShotResult.SUCCESS
    }

    fun handleFocusShotDamage(event: EntityDamageByEntityEvent, shooter: Player, projectile: Projectile) {
        if (plugin.classes.activeClass(shooter.uniqueId) != ClassType.ARCHER) {
            event.isCancelled = true
            return
        }
        val rank = plugin.classes.signatureRank(shooter.uniqueId)
        event.damage = (event.damage + archerAttackBonus(shooter)) * focusShotDamageMultiplier(rank)
        plugin.classFeedback.focusShotImpact(projectile.location)
    }

    fun handleProjectileMiss(event: ProjectileHitEvent, shooter: Player) {
        if (event.hitEntity != null) return
        val data = plugin.classes.data(shooter.uniqueId)
        if (plugin.classes.activeClass(shooter.uniqueId) != ClassType.ARCHER || data.focus <= 0) return
        // A whiff costs a chunk of the bar, not the whole thing.
        val penalty = maxOf(1, plugin.classesConfig.getInt("archer.focus-miss-penalty", 2))
        data.focus = (data.focus - penalty).coerceAtLeast(0)
        shooter.sendActionBar(Component.text(
            if (data.focus == 0) "§eFocus lost - the shot went wide." else "§eFocus slipped §7(-$penalty)"))
        plugin.refreshClassPlayer(shooter)
    }

    fun castArcaneBolt(player: Player): ArcaneCastResult {
        val data = plugin.classes.data(player.uniqueId)
        if (plugin.classes.activeClass(player.uniqueId) != ClassType.MAGE) return ArcaneCastResult.MANA_LOCKED
        if (!plugin.classItems.isStaff(player.inventory.itemInMainHand)) return ArcaneCastResult.WRONG_WEAPON
        val manaCost = arcaneBoltManaCost()
        if (data.mana < manaCost) return ArcaneCastResult.INSUFFICIENT_MANA
        if (player.hasCooldown(Material.BLAZE_ROD)) return ArcaneCastResult.COOLDOWN

        data.mana -= manaCost
        val rank = plugin.classes.signatureRank(player.uniqueId)
        // A raycast, not a thrown entity: fast, straight, no gravity. The orb
        // and trail are drawn by the flight; the splash comes back here.
        ArcaneBoltFlight.launch(plugin, player, arcaneBoltDamage(rank)) { impact, directTargetId ->
            arcaneBoltSplash(player, impact, directTargetId)
        }
        player.setCooldown(Material.BLAZE_ROD, maxOf(1, plugin.classesConfig.getInt("mage.arcane-bolt-cooldown-ticks", 8)))
        castBoltSound(player)
        player.sendActionBar(Component.text("§dArcane Bolt §7(-${manaCost.toInt()} Mana)"))
        plugin.refreshClassPlayer(player)
        return ArcaneCastResult.SUCCESS
    }

    private fun castBoltSound(player: Player) {
        val raw = plugin.classesConfig.getString("mage.bolt.cast-sound", "block_amethyst_block_chime")
        val sound = if (raw.isBlank()) null
        else org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.minecraft(raw.lowercase().replace('_', '.')))
        if (sound != null) player.world.playSound(player.location, sound, 0.7f, 1.2f)
    }

    /**
     * The Arcane Bolt's area splash, run one tick after the direct hit so it
     * reads as a follow-up. Dungeon mobs only, damage-only (movement kept), and
     * a no-op outside a dungeon.
     */
    fun arcaneBoltSplash(shooter: Player, impact: Location, directTargetId: UUID?) {
        if (!plugin.queries.isInDungeon(shooter)) return
        if (plugin.classes.activeClass(shooter.uniqueId) != ClassType.MAGE) return
        val radius = arcaneBoltSplashRadius()
        val splashDamage = arcaneBoltDamage(plugin.classes.signatureRank(shooter.uniqueId)) * arcaneBoltSplashDamageMultiplier()
        if (radius <= 0.0 || splashDamage <= 0.0) return
        val at = impact.clone()
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!shooter.isOnline || !plugin.queries.isInDungeon(shooter)) return@Runnable
            at.world!!.getNearbyEntities(at, radius, radius, radius)
                .filterIsInstance<LivingEntity>()
                .filter { it.uniqueId != directTargetId && plugin.queries.isDungeonMob(it) }
                .forEach { mob ->
                    val velocity = mob.velocity.clone()
                    mob.damage(splashDamage, shooter)
                    mob.velocity = velocity
                }
            at.world!!.spawnParticle(Particle.ENCHANT, at,
                maxOf(0, plugin.classesConfig.getInt("mage.bolt.splash-particles", 14)),
                radius / 3.0, 0.22, radius / 3.0, 0.08)
        })
    }

    fun readout(player: Player): String {
        val data = plugin.classes.data(player.uniqueId)
        val rank = plugin.classes.signatureRank(player.uniqueId)
        return when (plugin.classes.activeClass(player.uniqueId)) {
            ClassType.WARRIOR -> {
                if (rank == 0) "Rage: unlock Rank I in the skill tree"
                else if (data.rageActiveUntil > System.currentTimeMillis()) {
                    "Rage $rank: BERSERK ${((data.rageActiveUntil - System.currentTimeMillis()) / 1000.0).coerceAtLeast(0.0).roundToInt()}s"
                } else "Rage $rank: ${data.rage.roundToInt()}/${rageThreshold(rank).roundToInt()}"
            }
            ClassType.ARCHER -> if (rank == 0) "Focus: unlock Rank I in the skill tree" else if (data.focus >= focusThreshold(rank)) {
                "Focus $rank: §aFULL (${focusThreshold(rank)}/${focusThreshold(rank)}, Left Click: Focus Shot)"
            } else "Focus $rank: ${data.focus}/${focusThreshold(rank)} (build to activate)"
            ClassType.PALADIN -> if (rank == 0) "Taunt: unlock Rank I in the skill tree" else {
                val taunt = activeTaunt
                val active = taunt?.playerId == player.uniqueId && taunt.expiresAt > System.currentTimeMillis()
                if (active) "Taunt $rank: ACTIVE ${((activeTaunt!!.expiresAt - System.currentTimeMillis()) / 1000.0).coerceAtLeast(0.0).roundToInt()}s"
                else if (data.judgment >= tauntThreshold(rank)) "Taunt $rank: READY"
                else "Taunt $rank: ${data.judgment.roundToInt()}/${tauntThreshold(rank).roundToInt()} damage"
            }
            ClassType.MAGE -> if (rank == 0) {
                "Mana: ${data.mana.roundToInt()}/${maxMana(rank).roundToInt()} | Unlock Arcane Charge Rank I"
            } else {
                "Mana: ${data.mana.roundToInt()}/${maxMana(rank).roundToInt()} | Arcane Charge $rank"
            }
            null -> "Choose a class with /class"
        }
    }

    fun experienceToNextLevel(player: Player): Int = plugin.classes.experienceToNextLevel(player)

    /** Sidebar-friendly readout. A charged Taunt deliberately uses two compact lines. */
    fun readoutLines(player: Player): List<String> {
        val data = plugin.classes.data(player.uniqueId)
        val rank = plugin.classes.signatureRank(player.uniqueId)
        return if (plugin.classes.activeClass(player.uniqueId) == ClassType.PALADIN && rank > 0 &&
            activeTaunt?.playerId != player.uniqueId && data.judgment >= tauntThreshold(rank)
        ) {
            listOf("Taunt $rank: READY", "§6[SNEAK] §fto activate")
        } else {
            listOf(readout(player))
        }
    }

    /** True while the Warrior's Berserk window from a spent Rage bar is still open. */
    fun isBerserk(player: Player): Boolean =
        plugin.classes.data(player.uniqueId).rageActiveUntil > System.currentTimeMillis()

    /**
     * Lets an active ability feed the Rage bar - the Warrior Dash uses this so
     * it plugs into the Berserk loop instead of standing apart from it. Shares
     * every guard in [addRage]: a no-op below Rage Rank I or while Berserk is
     * already running, and it can tip the bar over the threshold and erupt.
     */
    fun feedRage(player: Player, amount: Double) = addRage(player, amount)

    private fun addRage(player: Player, amount: Double) {
        val data = plugin.classes.data(player.uniqueId)
        val rank = plugin.classes.signatureRank(player.uniqueId)
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
            plugin.classFeedback.rageTriggered(player)
            player.sendMessage("§cRage erupts! §fDamage and speed increased.")
        }
    }

    private fun buildTaunt(player: Player, damage: Double, rank: Int) {
        val data = plugin.classes.data(player.uniqueId)
        val wasReady = data.judgment >= tauntThreshold(rank)
        data.judgment = (data.judgment + damage.coerceAtLeast(0.0)).coerceAtMost(tauntThreshold(rank))
        if (!wasReady && data.judgment >= tauntThreshold(rank)) {
            player.sendActionBar(
                Component.text("Taunt ready! Press [", NamedTextColor.GOLD)
                    .append(Component.keybind("key.sneak").color(NamedTextColor.YELLOW))
                    .append(Component.text("] to activate.", NamedTextColor.WHITE))
            )
            plugin.refreshClassPlayer(player)
        }
    }

    private fun rageThreshold(rank: Int): Double {
        val base = plugin.classesConfig.getDouble("warrior.rage-threshold-base", 200.0)
        val perRank = plugin.classesConfig.getDouble("warrior.rage-threshold-per-rank", 8.0)
        return (base - (rank - 1) * perRank).coerceAtLeast(1.0)
    }

    private fun decayRageOutOfCombat(data: PlayerClassData, rank: Int, now: Long) {
        if (rank == 0 || data.rage <= 0.0 || data.rageActiveUntil > now) return
        val idleMillis = now - data.lastRageCombatAt
        if (idleMillis < rageDecayDelayMillis()) return
        data.rage = (data.rage - rageDecayPerSecond()).coerceAtLeast(0.0)
    }

    private fun rageDecayDelayMillis(): Long =
        (plugin.classesConfig.getDouble("warrior.rage-decay-delay-seconds", 15.0).coerceAtLeast(0.0) * 1_000.0).toLong()

    private fun rageDecayPerSecond(): Double =
        plugin.classesConfig.getDouble("warrior.rage-decay-per-second", 10.0).coerceAtLeast(0.0)

    /** Focus I-V reduce the bar; Focus VI keeps the five-hit bar and upgrades the shot. */
    private fun focusThreshold(rank: Int): Int = (10 - rank).coerceAtLeast(5)

    private fun fullFocusDamageBonus(rank: Int): Double = (0.20 + rank * 0.05).coerceAtMost(0.50)

    /** The spent Focus Shot's on-hit multiplier: base at Rank I, growing each rank. */
    private fun focusShotDamageMultiplier(rank: Int): Double {
        val base = plugin.classesConfig.getDouble("archer.focus-shot-base-multiplier", 2.0)
        val perRank = plugin.classesConfig.getDouble("archer.focus-shot-multiplier-per-rank", 0.25)
        return (base + (rank - 1).coerceAtLeast(0) * perRank).coerceAtLeast(1.0)
    }

    /** Current Focus state for HUD use, or null unless the player is an Archer with Focus unlocked. */
    fun focusStatus(player: Player): FocusStatus? {
        if (plugin.classes.activeClass(player.uniqueId) != ClassType.ARCHER) return null
        val rank = plugin.classes.signatureRank(player.uniqueId)
        if (rank == 0) return null
        val required = focusThreshold(rank)
        val stacks = plugin.classes.data(player.uniqueId).focus
        return FocusStatus(stacks, required, stacks >= required)
    }

    private fun archerAttackBonus(player: Player): Double =
        (player.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 1.0) *
            plugin.classesConfig.getDouble("archer.attack-stat-damage-multiplier", 1.0).coerceAtLeast(0.0)

    private fun tauntThreshold(rank: Int): Double =
        (plugin.classesConfig.getDouble("paladin.taunt-damage-threshold", 75.0) - (rank - 1) * 4.0).coerceAtLeast(20.0)

    private fun tauntDurationTicks(rank: Int): Int =
        ((plugin.classesConfig.getDouble("paladin.taunt-duration-seconds", 8.0) + (rank - 1)) * 20.0).roundToInt().coerceAtLeast(20)

    private fun turtleMasterResistanceAmplifier(): Int =
        plugin.classesConfig.getInt("paladin.turtle-master-resistance-amplifier", 2).coerceIn(0, 4)

    private fun turtleMasterSlownessAmplifier(): Int =
        plugin.classesConfig.getInt("paladin.turtle-master-slowness-amplifier", 3).coerceIn(0, 5)

    private fun maintainTaunt() {
        val taunt = activeTaunt ?: return
        val player = plugin.server.getPlayer(taunt.playerId)
        if (player == null || !player.isOnline || taunt.expiresAt <= System.currentTimeMillis() ||
            plugin.classes.activeClass(player.uniqueId) != ClassType.PALADIN
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
        plugin.classesConfig.getDouble("mage.base-max-mana", 200.0).coerceAtLeast(1.0) + rank * 20.0

    private fun manaRegenerationPerSecond(): Double =
        plugin.classesConfig.getDouble("mage.mana-regeneration-per-second", 10.0).coerceAtLeast(0.1)

    private fun arcaneBoltManaCost(): Double =
        plugin.classesConfig.getDouble("mage.arcane-bolt-mana-cost", 10.0).coerceAtLeast(1.0)

    private fun arcaneBoltDamage(rank: Int): Double =
        plugin.classesConfig.getDouble("mage.arcane-bolt-base-damage", 5.0) +
            plugin.classesConfig.getDouble("mage.arcane-bolt-damage-per-rank", 1.5) * rank

    private fun arcaneBoltSplashRadius(): Double =
        plugin.classesConfig.getDouble("mage.arcane-bolt-splash-radius", 1.5).coerceAtLeast(0.0)

    private fun arcaneBoltSplashDamageMultiplier(): Double =
        (plugin.classesConfig.getDouble("mage.arcane-bolt-splash-damage-percent", 50.0) / 100.0).coerceAtLeast(0.0)

    private fun fullFocusDrawSpeedBonus(): Double =
        (plugin.classesConfig.getDouble("focus.full-draw-speed-percent", 25.0) / 100.0).coerceIn(0.0, 0.75)

    /** Vanilla's bow-charge curve: f = (x^2 + 2x) / 3, capped at full charge. */
    private fun bowForceForTicks(ticks: Double): Double {
        val draw = ticks.coerceAtLeast(0.0) / 20.0
        return ((draw * draw + 2.0 * draw) / 3.0).coerceAtMost(1.0)
    }

    private fun ticksForBowForce(force: Double): Double {
        val clamped = force.coerceIn(0.0, 1.0)
        return ((sqrt(1.0 + 3.0 * clamped) - 1.0) * 20.0).coerceIn(0.0, 20.0)
    }
}

private data class ActiveTaunt(val playerId: UUID, val expiresAt: Long)

/** Snapshot of an Archer's Focus bar for the HUD. */
data class FocusStatus(val stacks: Int, val required: Int, val full: Boolean)

enum class ArcaneCastResult { SUCCESS, MANA_LOCKED, WRONG_WEAPON, INSUFFICIENT_MANA, COOLDOWN }

enum class FocusShotResult { SUCCESS, LOCKED, WRONG_WEAPON, NOT_CHARGED }

enum class TauntActivationResult { SUCCESS, LOCKED, NOT_READY, ALREADY_ACTIVE }
