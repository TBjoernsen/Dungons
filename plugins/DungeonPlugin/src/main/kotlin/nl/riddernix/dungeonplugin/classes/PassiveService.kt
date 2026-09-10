package nl.riddernix.dungeonplugin.classes

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
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
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The four signature passives: Rage, Focus, Taunt and Arcane Charge. */
class PassiveService(private val plugin: DungeonPlugin) {

    private var activeTaunt: ActiveTaunt? = null
    private var tauntFadeWarned = false

    /** Per Paladin: their live Consecrated Ground - a fixed circle that buffs anyone standing in it. */
    private val consecrations = HashMap<UUID, Consecration>()
    private val tauntKnockbackKey = NamespacedKey(plugin, "paladin_taunt_kb")

    private class Consecration(
        val centre: Location,
        val radiusSq: Double,
        val expiresAt: Long,
        val task: BukkitTask,
    )

    /** Per Archer: wall-clock ms before Scope can trigger again. */
    private val scopeReadyAt = HashMap<UUID, Long>()

    /** Warriors currently inside a Berserk window - so [tick] can fire the "started/fading/ended" cues once each. */
    private val berserkActive = HashSet<UUID>()
    private val berserkFadeWarned = HashSet<UUID>()

    fun tick() {
        maintainTaunt()
        val now = System.currentTimeMillis()
        for (player in plugin.server.onlinePlayers) {
            val data = plugin.classes.data(player.uniqueId)
            val rank = plugin.classes.signatureRank(player.uniqueId)
            val classType = plugin.classes.activeClass(player.uniqueId)
            if (classType == ClassType.WARRIOR) {
                decayRageOutOfCombat(data, rank, now)
                updateBerserkPresence(player, data, now)
            } else if (classType == ClassType.PALADIN) {
                decayJudgmentOutOfCombat(data, rank, now)
                updateTauntPresence(player, now)
                if (data.retributionUntil > now && data.retributionPower > 0.0) {
                    val gold = Particle.DustOptions(org.bukkit.Color.fromRGB(255, 235, 150), 1.3f)
                    player.world.spawnParticle(Particle.DUST, player.location.clone().add(0.0, 1.2, 0.0), 6, 0.35, 0.5, 0.35, 0.0, gold)
                }
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
                val rank = plugin.classes.signatureRank(player.uniqueId)
                if (rank > 0 && isBerserk(player) &&
                    rank >= plugin.classesConfig.getInt("warrior.berserk-resistance-min-rank", 3)) {
                    val cut = plugin.classesConfig.getDouble("warrior.berserk-damage-reduction", 0.25).coerceIn(0.0, 0.9)
                    event.damage *= (1.0 - cut)
                }
                if (rank > 0) addRage(player, event.finalDamage * 4.0)
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
            ClassType.PALADIN -> if (isTaunting(player)) {
                // The Taunt stance is a fightable one: flat mitigation on top
                // of Resistance, and every hit soaked banks Zeal - which at
                // Taunt end fires the Holy Nova and arms the next Smite.
                val cut = plugin.classesConfig.getDouble("paladin.taunt-damage-reduction", 0.30).coerceIn(0.0, 0.9)
                event.damage *= (1.0 - cut)
                // Charge off the incoming blow (event.damage), not finalDamage -
                // otherwise absorption hearts eating the hit means no Zeal.
                addZeal(player, event.damage * plugin.classesConfig.getDouble("paladin.zeal-per-damage", 1.0))
            }
            else -> Unit
        }
        // Consecrated Ground absorbs damage for anyone standing in it -
        // Paladin or ally, regardless of class.
        if (inConsecration(player)) {
            val cut = plugin.classesConfig.getDouble("paladin.consecration-damage-reduction", 0.20).coerceIn(0.0, 0.9)
            event.damage *= (1.0 - cut)
        }
    }

    fun handleDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager as? Player ?: return
        val rank = plugin.classes.signatureRank(damager.uniqueId)
        when (plugin.classes.activeClass(damager.uniqueId)) {
            ClassType.WARRIOR -> if (rank > 0 && plugin.classItems.isAllowedWeapon(ClassType.WARRIOR, damager.inventory.itemInMainHand)) {
                addRage(damager, event.finalDamage * 6.0)          // no-op while Berserk is running
                if (isBerserk(damager)) berserkLifesteal(damager, event.finalDamage, rank)
            }
            ClassType.PALADIN -> if (rank > 0 && plugin.classItems.isAllowedWeapon(ClassType.PALADIN, damager.inventory.itemInMainHand)) {
                buildTaunt(damager, event.finalDamage, rank)
                val victim = event.entity
                val data = plugin.classes.data(damager.uniqueId)
                // Smite is the ONE stored strike after Taunt ends - not a
                // window, not a buff during Taunt. It lands on the next mob the
                // Paladin hits, then it is spent.
                if (victim is LivingEntity && victim !is Player && !isTaunting(damager) &&
                    data.retributionUntil > System.currentTimeMillis() && data.retributionPower > 0.0) {
                    val bonus = smiteFlat(rank) +
                        plugin.classesConfig.getDouble("paladin.retribution-bonus", 12.0) * data.retributionPower.coerceIn(0.0, 1.0)
                    event.damage += bonus
                    data.retributionUntil = 0L
                    data.retributionPower = 0.0
                    val at = victim.location.clone().add(0.0, 1.0, 0.0)
                    victim.world.spawnParticle(Particle.END_ROD, at, 24, 0.3, 0.4, 0.3, 0.06)
                    victim.world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 12, 0.25, 0.35, 0.25, 0.12)
                    victim.world.playSound(victim.location, Sound.ITEM_TRIDENT_THUNDER, 0.5f, 1.4f)
                    damager.sendActionBar(Component.text("§6§lSMITE"))
                    plugin.refreshClassPlayer(damager)
                }
            }
            else -> Unit
        }
        // Consecrated Ground lifesteal: standing in the circle heals you
        // 1 health per `consecration-heal-per-damage` damage you deal.
        val healPer = plugin.classesConfig.getDouble("paladin.consecration-heal-per-damage", 5.0)
        if (healPer > 0.0 && event.finalDamage > 0.0 && inConsecration(damager)) {
            val maxHp = damager.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            damager.health = (damager.health + event.finalDamage / healPer).coerceIn(0.0, maxHp)
        }
    }

    /** Activates a fully charged Taunt. Called from the Paladin's Sneak input. */
    fun activateTaunt(player: Player): TauntActivationResult {
        val data = plugin.classes.data(player.uniqueId)
        val rank = plugin.classes.signatureRank(player.uniqueId)
        if (plugin.classes.activeClass(player.uniqueId) != ClassType.PALADIN || rank == 0) return TauntActivationResult.LOCKED
        val taunt = activeTaunt
        if (taunt?.playerId == player.uniqueId && taunt.expiresAt > System.currentTimeMillis()) {
            return TauntActivationResult.ALREADY_ACTIVE
        }
        if (data.judgment < tauntThreshold(rank)) return TauntActivationResult.NOT_READY

        val cfg = plugin.classesConfig
        data.judgment = 0.0
        data.zeal = 0.0
        data.retributionUntil = 0L
        tauntFadeWarned = false
        val durationTicks = tauntDurationTicks(rank)
        activeTaunt = ActiveTaunt(player.uniqueId, System.currentTimeMillis() + durationTicks * 50L, rank)

        // A stance you can fight in, but still a tank's: Slowness I (not the
        // old Turtle Master IV), a knockback lock, real Resistance, plus the
        // flat mitigation applied in handleIncomingDamage. Being swarmed as a
        // Paladin has to be survivable.
        val slowAmp = cfg.getInt("paladin.taunt-slowness-amplifier", 0).coerceIn(0, 5)
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, durationTicks, slowAmp, true, false, true))
        val resistAmp = cfg.getInt("paladin.taunt-resistance-amplifier", 1).coerceIn(0, 4)
        player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, durationTicks, resistAmp, true, false, true))
        applyTauntKnockbackLock(player, cfg.getDouble("paladin.taunt-knockback-resistance", 1.0).coerceIn(0.0, 1.0))

        targetMobsInRadius(player)
        startConsecration(player, durationTicks)
        plugin.classFeedback.tauntTriggered(player)
        player.sendActionBar(Component.text("§6§lHOLD THE LINE §7- ground consecrated"))
        plugin.refreshClassPlayer(player)
        return TauntActivationResult.SUCCESS
    }

    fun isTaunting(player: Player): Boolean {
        val t = activeTaunt ?: return false
        return t.playerId == player.uniqueId && t.expiresAt > System.currentTimeMillis()
    }

    /** Live Taunt state for the HUD boss bar, or null unless this player's Taunt is up. */
    fun tauntStatus(player: Player): TauntStatus? {
        if (!isTaunting(player)) return null
        val rank = plugin.classes.signatureRank(player.uniqueId).coerceAtLeast(1)
        val cfg = plugin.classesConfig
        val threshold = zealThreshold()
        val smite = cfg.getDouble("paladin.smite-base", 2.0) + cfg.getDouble("paladin.smite-per-rank", 1.5) * (rank - 1)
        val zeal = plugin.classes.data(player.uniqueId).zeal
        // What each Smite will carry once this Taunt ends, at the current Zeal.
        val pendingRetribution = cfg.getDouble("paladin.retribution-bonus", 12.0) * (zeal / threshold).coerceIn(0.0, 1.0)
        val remainMs = (activeTaunt?.expiresAt ?: 0L) - System.currentTimeMillis()
        return TauntStatus(zeal, threshold, smite, pendingRetribution, (remainMs / 1000.0).coerceAtLeast(0.0))
    }

    private fun applyTauntKnockbackLock(player: Player, value: Double) {
        val attr = player.getAttribute(Attribute.KNOCKBACK_RESISTANCE) ?: return
        attr.getModifier(tauntKnockbackKey)?.let { attr.removeModifier(it) }
        if (value > 0.0) {
            attr.addTransientModifier(AttributeModifier(tauntKnockbackKey, value, AttributeModifier.Operation.ADD_NUMBER))
        }
    }

    private fun removeTauntKnockbackLock(player: Player) {
        val attr = player.getAttribute(Attribute.KNOCKBACK_RESISTANCE) ?: return
        attr.getModifier(tauntKnockbackKey)?.let { attr.removeModifier(it) }
    }

    private fun addZeal(player: Player, amount: Double) {
        if (amount <= 0.0) return
        val data = plugin.classes.data(player.uniqueId)
        val threshold = plugin.classesConfig.getDouble("paladin.zeal-threshold", 90.0).coerceAtLeast(1.0)
        // Zeal no longer auto-fires mid-fight - it banks for the Taunt-end
        // release (Holy Nova + empowered Smite).
        data.zeal = (data.zeal + amount).coerceAtMost(threshold)
        plugin.refreshClassPlayer(player)
    }

    private fun zealThreshold(): Double =
        plugin.classesConfig.getDouble("paladin.zeal-threshold", 90.0).coerceAtLeast(1.0)

    /** The flat portion of a Smite: `smite-base + smite-per-rank * (rank - 1)`. */
    private fun smiteFlat(rank: Int): Double =
        plugin.classesConfig.getDouble("paladin.smite-base", 2.0) +
            plugin.classesConfig.getDouble("paladin.smite-per-rank", 1.5) * (rank - 1)

    /**
     * Arms a single empowered Smite for after Taunt: the Paladin's next axe
     * hit carries `smiteFlat + retribution-bonus * power` (power 0..1 = how
     * full Zeal was), then it is spent. `smite-armed-timeout-seconds` is only
     * a safety cap so a stored strike does not linger across a whole dungeon.
     */
    private fun startRetribution(player: Player, power: Double) {
        if (power <= 0.0) return
        val timeout = plugin.classesConfig.getDouble("paladin.smite-armed-timeout-seconds", 20.0).coerceAtLeast(1.0)
        val data = plugin.classes.data(player.uniqueId)
        data.retributionUntil = System.currentTimeMillis() + (timeout * 1000.0).toLong()
        data.retributionPower = power.coerceIn(0.0, 1.0)
        player.sendActionBar(Component.text("§6§lSMITE ARMED §7- your next strike"))
        player.playSound(player.location, Sound.ITEM_TOTEM_USE, 0.5f, 0.9f)
    }

    /**
     * The Holy Nova: mobs around the Paladin take holy damage, nearby allies
     * (and the Paladin) are healed and briefly regenerate. [power] 0..1 scales
     * the payout - a full charge fires at 1.0, a parting nova on Taunt expiry
     * fires at whatever fraction of Zeal was banked.
     */
    fun releaseHolyNova(player: Player, power: Double) {
        val cfg = plugin.classesConfig
        val p = power.coerceIn(0.0, 1.0)
        if (p <= 0.0) return
        val rank = plugin.classes.signatureRank(player.uniqueId).coerceAtLeast(1)
        val radius = cfg.getDouble("paladin.nova-radius", 6.0).coerceIn(1.0, 24.0)
        val mobDamage = (cfg.getDouble("paladin.nova-damage", 6.0) +
            cfg.getDouble("paladin.nova-damage-per-rank", 2.0) * (rank - 1)) * p
        val allyHeal = (cfg.getDouble("paladin.nova-heal", 4.0) +
            cfg.getDouble("paladin.nova-heal-per-rank", 1.0) * (rank - 1)) * p
        val regenTicks = (cfg.getDouble("paladin.nova-regen-seconds", 4.0).coerceAtLeast(0.0) * 20.0).toInt()
        val centre = player.location
        val world = centre.world ?: return
        for (entity in world.getNearbyEntities(centre, radius, radius, radius)) {
            when {
                entity is Player -> {
                    if (allyHeal > 0.0) {
                        val maxHp = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                        entity.health = (entity.health + allyHeal).coerceIn(0.0, maxHp)
                    }
                    if (regenTicks > 0) {
                        entity.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, regenTicks, 0, true, false, true))
                    }
                }
                entity is LivingEntity && (plugin.queries.isDungeonMob(entity) || entity is Mob) ->
                    if (mobDamage > 0.0) entity.damage(mobDamage, player)
            }
        }
        plugin.classFeedback.paladinHolyNova(centre, radius)
    }

    private fun startConsecration(player: Player, durationTicks: Int) {
        val cfg = plugin.classesConfig
        consecrations.remove(player.uniqueId)?.task?.cancel()
        val centre = player.location.clone()
        val world = centre.world ?: return
        val radius = cfg.getDouble("paladin.consecration-radius", 8.0).coerceIn(1.0, 24.0)
        val interval = cfg.getInt("paladin.consecration-tick-interval", 10).coerceIn(2, 40).toLong()
        val slowAmp = cfg.getInt("paladin.consecration-slow-amplifier", 0)
        val dot = cfg.getDouble("paladin.consecration-dot", 1.0).coerceAtLeast(0.0)
        val slowTicks = (interval + 5L).toInt()
        val radiusSq = radius * radius
        val id = player.uniqueId
        val task = object : BukkitRunnable() {
            private var elapsed = 0L
            override fun run() {
                elapsed += interval
                if (elapsed > durationTicks || !player.isOnline || player.world != world) {
                    cancel(); consecrations.remove(id); return
                }
                if (slowAmp >= 0 || dot > 0.0) {
                    for (entity in world.getNearbyEntities(centre, radius, 4.0, radius)) {
                        if (entity !is LivingEntity || entity is Player) continue
                        if (!plugin.queries.isDungeonMob(entity) && entity !is Mob) continue
                        val dx = entity.location.x - centre.x
                        val dz = entity.location.z - centre.z
                        if (dx * dx + dz * dz > radiusSq) continue
                        // Sourceless: the ground burns them, it does not knock
                        // them out of the ring.
                        if (dot > 0.0) entity.damage(dot)
                        if (slowAmp >= 0) {
                            entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, slowTicks, slowAmp, true, false, true))
                        }
                    }
                }
                plugin.classFeedback.paladinConsecrationTick(centre, radius)
            }
        }.runTaskTimer(plugin, interval, interval)
        consecrations[id] = Consecration(centre, radiusSq, System.currentTimeMillis() + durationTicks * 50L, task)
    }

    /** True while the player stands in any live Consecrated Ground - the circle's buff zone. */
    fun inConsecration(player: Player): Boolean {
        val now = System.currentTimeMillis()
        return consecrations.values.any { c ->
            c.expiresAt > now && c.centre.world == player.world && run {
                val dx = player.location.x - c.centre.x
                val dz = player.location.z - c.centre.z
                dx * dx + dz * dz <= c.radiusSq
            }
        }
    }

    private fun targetMobsInRadius(player: Player) {
        val radius = plugin.classesConfig.getDouble("paladin.taunt-radius", 32.0).coerceIn(4.0, 128.0)
        for (entity in player.world.getNearbyEntities(player.location, radius, radius, radius)) {
            val mob = entity as? Mob ?: continue
            if (mob.isValid && !mob.isDead) mob.target = player
        }
    }

    private fun updateTauntPresence(player: Player, now: Long) {
        val t = activeTaunt ?: return
        if (t.playerId != player.uniqueId) return
        val remain = t.expiresAt - now
        if (remain <= 0L) return
        val gold = Particle.DustOptions(org.bukkit.Color.fromRGB(255, 210, 90), 1.4f)
        player.world.spawnParticle(Particle.DUST, player.location.clone().add(0.0, 1.1, 0.0), 8, 0.4, 0.6, 0.4, 0.0, gold)
        if (remain in 1..1600 && !tauntFadeWarned) {
            tauntFadeWarned = true
            player.sendActionBar(Component.text("§6Taunt fading..."))
            player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 1.2f)
        }
    }

    private fun decayJudgmentOutOfCombat(data: PlayerClassData, rank: Int, now: Long) {
        if (rank == 0 || data.judgment <= 0.0 || data.judgment >= tauntThreshold(rank)) return
        if (now - data.lastJudgmentCombatAt < judgmentDecayDelayMillis()) return
        data.judgment = (data.judgment - judgmentDecayPerSecond()).coerceAtLeast(0.0)
    }

    private fun judgmentDecayDelayMillis(): Long =
        (plugin.classesConfig.getDouble("paladin.judgment-decay-delay-seconds", 15.0).coerceAtLeast(0.0) * 1000.0).toLong()

    private fun judgmentDecayPerSecond(): Double =
        plugin.classesConfig.getDouble("paladin.judgment-decay-per-second", 5.0).coerceAtLeast(0.0)

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
            // Skyfall: loosed a drawn shot while airborne from Wind Jump with a
            // full bar - spend the bar and make this arrow detonate on impact.
            if (plugin.classAbilities.isWindJumping(player)) {
                (event.projectile as? Projectile)?.let { arrow ->
                    plugin.classItems.markSkyfallArrow(arrow)
                    data.focus = 0
                    plugin.classFeedback.skyfallArmed(player, arrow)
                    player.sendActionBar(Component.text("§b§lSKYFALL §floosed"))
                    plugin.refreshClassPlayer(player)
                }
            }
        }
    }

    /** True when an Archer's Focus bar is full (used to arm/telegraph Skyfall). */
    fun focusFull(player: Player): Boolean {
        if (plugin.classes.activeClass(player.uniqueId) != ClassType.ARCHER) return false
        val rank = plugin.classes.signatureRank(player.uniqueId)
        return rank > 0 && plugin.classes.data(player.uniqueId).focus >= focusThreshold(rank)
    }

    /**
     * Archer "Scope": crouching mid-air at Focus rank `archer.scope-min-rank`+
     * grants a short Slow Falling to steady a shot. Fired from the sneak
     * event; rests `archer.scope-cooldown-seconds` between uses.
     */
    @Suppress("DEPRECATION")
    fun tryScope(player: Player) {
        if (plugin.classes.activeClass(player.uniqueId) != ClassType.ARCHER) return
        if (plugin.classes.signatureRank(player.uniqueId) <
            plugin.classesConfig.getInt("archer.scope-min-rank", 5)) return
        if (player.isOnGround || player.isGliding) return
        val now = System.currentTimeMillis()
        if ((scopeReadyAt[player.uniqueId] ?: 0L) > now) return
        val durationTicks = plugin.classesConfig.getInt("archer.scope-duration-ticks", 24).coerceIn(5, 200)
        val cooldownMs = (plugin.classesConfig.getDouble("archer.scope-cooldown-seconds", 3.0)
            .coerceAtLeast(0.0) * 1000.0).toLong()
        scopeReadyAt[player.uniqueId] = now + cooldownMs
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, durationTicks, 0, true, false, true))
        player.playSound(player.location, Sound.ITEM_SPYGLASS_USE, 0.7f, 1.25f)
        player.world.spawnParticle(Particle.END_ROD, player.location.clone().add(0.0, 1.0, 0.0), 6, 0.25, 0.3, 0.25, 0.01)
        player.sendActionBar(Component.text("§bScope §7- steady your shot"))
    }

    /**
     * Detonates a Skyfall arrow where it landed - a non-terrain AoE burst that
     * damages nearby dungeon mobs and knocks them up. `archerAttackBonus`
     * scaled by `archer.skyfall-damage-multiplier`, within `archer.skyfall-radius`.
     */
    fun detonateSkyfall(where: Location, shooter: Player) {
        val radius = plugin.classesConfig.getDouble("archer.skyfall-radius", 4.0).coerceIn(1.0, 16.0)
        val damage = archerAttackBonus(shooter) *
            plugin.classesConfig.getDouble("archer.skyfall-damage-multiplier", 1.5).coerceAtLeast(0.0)
        val knockUp = plugin.classesConfig.getDouble("archer.skyfall-knockup", 0.35).coerceIn(0.0, 2.0)
        val world = where.world ?: return
        for (entity in world.getNearbyEntities(where, radius, radius, radius)) {
            val mob = entity as? LivingEntity ?: continue
            if (mob is Player || (!plugin.queries.isDungeonMob(mob) && mob !is Mob)) continue
            mob.damage(damage, shooter)
            val away = mob.location.toVector().subtract(where.toVector())
            if (away.lengthSquared() > 0.0001) away.normalize() else away.zero()
            mob.velocity = mob.velocity.add(away.multiply(0.35)).setY(knockUp)
        }
        plugin.classFeedback.skyfallDetonate(where)
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
                val cd = berserkCooldownSeconds(player)
                if (rank == 0) "Rage: unlock Rank I in the skill tree"
                else if (data.rageActiveUntil > System.currentTimeMillis()) {
                    "Rage $rank: §4BERSERK ${((data.rageActiveUntil - System.currentTimeMillis()) / 1000.0).coerceAtLeast(0.0).roundToInt()}s"
                } else if (cd > 0) {
                    "Rage $rank: §7cooldown ${cd}s"
                } else if (data.rage >= rageThreshold(rank)) {
                    "Rage $rank: §6READY §7[Sneak]"
                } else "Rage $rank: ${data.rage.roundToInt()}/${rageThreshold(rank).roundToInt()}"
            }
            ClassType.ARCHER -> if (rank == 0) "Focus: unlock Rank I in the skill tree" else if (data.focus >= focusThreshold(rank)) {
                "Focus $rank: §aFULL (${focusThreshold(rank)}/${focusThreshold(rank)}, Left Click: Focus Shot)"
            } else "Focus $rank: ${data.focus}/${focusThreshold(rank)} (build to activate)"
            ClassType.PALADIN -> if (rank == 0) "Taunt: unlock Rank I in the skill tree" else {
                val taunt = activeTaunt
                val active = taunt?.playerId == player.uniqueId && taunt.expiresAt > System.currentTimeMillis()
                if (active) {
                    val secs = ((taunt!!.expiresAt - System.currentTimeMillis()) / 1000.0).coerceAtLeast(0.0).roundToInt()
                    "Taunt $rank: §6ACTIVE ${secs}s §7| Zeal ${data.zeal.roundToInt()}/${zealThreshold().roundToInt()}"
                }
                else if (data.retributionUntil > System.currentTimeMillis() && data.retributionPower > 0.0) {
                    "Taunt $rank: §6SMITE ARMED §7- next strike"
                }
                else if (data.judgment >= tauntThreshold(rank)) "Taunt $rank: §6READY"
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

    /** Sidebar-friendly readout. A charged Taunt / a full Rage bar use two compact lines. */
    fun readoutLines(player: Player): List<String> {
        val data = plugin.classes.data(player.uniqueId)
        val rank = plugin.classes.signatureRank(player.uniqueId)
        val activeClass = plugin.classes.activeClass(player.uniqueId)
        if (activeClass == ClassType.PALADIN && rank > 0 &&
            activeTaunt?.playerId != player.uniqueId && data.judgment >= tauntThreshold(rank)) {
            return listOf("Taunt $rank: READY", "§6[SNEAK] §fto activate")
        }
        if (activeClass == ClassType.WARRIOR && rank > 0 &&
            data.rageActiveUntil <= System.currentTimeMillis() && data.rage >= rageThreshold(rank) &&
            berserkCooldownSeconds(player) == 0) {
            return listOf("Rage $rank: §6READY", "§6[SNEAK] §fto go Berserk")
        }
        return listOf(readout(player))
    }

    /** True while the Warrior's Berserk window is open. */
    fun isBerserk(player: Player): Boolean =
        plugin.classes.data(player.uniqueId).rageActiveUntil > System.currentTimeMillis()

    /**
     * Lets an active ability feed the Rage bar - the Warrior Dash uses this so
     * it plugs into the Berserk loop. Shares [addRage]'s guards: a no-op below
     * Rage Rank I or while Berserk (or its cooldown) is running, and it can
     * fill the bar to "ready" (unleashed with Sneak, it no longer auto-erupts).
     */
    fun feedRage(player: Player, amount: Double) = addRage(player, amount)

    private fun addRage(player: Player, amount: Double) {
        val data = plugin.classes.data(player.uniqueId)
        val rank = plugin.classes.signatureRank(player.uniqueId)
        // No Rage builds during Berserk itself or its post-Berserk cooldown.
        if (rank == 0 || berserkCooldownSeconds(player) > 0) return
        if (amount <= 0.0) return
        data.lastRageCombatAt = System.currentTimeMillis()
        val threshold = rageThreshold(rank)
        val wasReady = data.rage >= threshold
        data.rage = (data.rage + amount).coerceAtMost(threshold)
        if (!wasReady && data.rage >= threshold) {
            player.sendActionBar(Component.text("§4§lBERSERK READY §7- press §fSneak"))
            player.playSound(player.location, Sound.ENTITY_RAVAGER_ROAR, 0.5f, 0.75f)
            plugin.refreshClassPlayer(player)
        }
    }

    /** Seconds until this Warrior may go Berserk again (0 = ready now). */
    fun berserkCooldownSeconds(player: Player): Int {
        val data = plugin.classes.data(player.uniqueId)
        val cdMs = (plugin.classesConfig.getDouble("warrior.berserk-cooldown-seconds", 7.5)
            .coerceAtLeast(0.0) * 1000.0).toLong()
        val remain = (data.rageActiveUntil + cdMs) - System.currentTimeMillis()
        return if (remain <= 0L) 0 else ((remain + 999L) / 1000L).toInt()
    }

    /** The Warrior's Sneak input: unleash a full Rage bar into Berserk with a Seismic Slam. */
    fun activateBerserk(player: Player): BerserkActivationResult {
        if (plugin.classes.activeClass(player.uniqueId) != ClassType.WARRIOR) return BerserkActivationResult.WRONG_CLASS
        val rank = plugin.classes.signatureRank(player.uniqueId)
        if (rank == 0) return BerserkActivationResult.LOCKED
        val data = plugin.classes.data(player.uniqueId)
        if (data.rageActiveUntil > System.currentTimeMillis()) return BerserkActivationResult.ALREADY_ACTIVE
        if (berserkCooldownSeconds(player) > 0) return BerserkActivationResult.ON_COOLDOWN
        if (data.rage < rageThreshold(rank)) return BerserkActivationResult.NOT_READY
        startBerserk(player, data, rank)
        return BerserkActivationResult.SUCCESS
    }

    private fun startBerserk(player: Player, data: PlayerClassData, rank: Int) {
        val cfg = plugin.classesConfig
        data.rage = 0.0
        val durationTicks = cfg.getInt("warrior.berserk-base-ticks", 60) +
            rank * cfg.getInt("warrior.berserk-ticks-per-rank", 20)
        val now = System.currentTimeMillis()
        data.berserkStartedAt = now
        data.rageActiveUntil = now + durationTicks * 50L
        refreshBerserkPotions(player, rank, durationTicks)
        seismicSlam(player, rank)
        plugin.classFeedback.rageTriggered(player)
        player.sendActionBar(Component.text("§4§lBERSERK"))
        plugin.refreshClassPlayer(player)
    }

    private fun refreshBerserkPotions(player: Player, rank: Int, ticks: Int) {
        val cfg = plugin.classesConfig
        val strAmp = if (rank >= cfg.getInt("warrior.berserk-strength-2-min-rank", 4)) 1 else 0
        val spdAmp = if (rank >= cfg.getInt("warrior.berserk-speed-2-min-rank", 5)) 1 else 0
        player.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, ticks, strAmp, true, false, true))
        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, ticks, spdAmp, true, false, true))
    }

    /** Berserk lifesteal: heal a rank-gated fraction of the melee damage the Warrior deals. */
    private fun berserkLifesteal(player: Player, dealt: Double, rank: Int) {
        val cfg = plugin.classesConfig
        if (dealt <= 0.0 || rank < cfg.getInt("warrior.berserk-lifesteal-min-rank", 2)) return
        val frac = cfg.getDouble("warrior.berserk-lifesteal-fraction", 0.25).coerceIn(0.0, 1.0)
        if (frac <= 0.0) return
        val maxHp = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.health = (player.health + dealt * frac).coerceIn(0.0, maxHp)
    }

    /**
     * Bloodlust: a kill during Berserk stretches it by
     * `warrior.bloodlust-ticks-per-kill`, never past
     * `berserkStartedAt + warrior.berserk-max-seconds`. Rank-gated by
     * `warrior.bloodlust-min-rank`. Called from the kill event.
     */
    fun bloodlustOnKill(player: Player) {
        if (plugin.classes.activeClass(player.uniqueId) != ClassType.WARRIOR || !isBerserk(player)) return
        val rank = plugin.classes.signatureRank(player.uniqueId)
        val cfg = plugin.classesConfig
        if (rank < cfg.getInt("warrior.bloodlust-min-rank", 4)) return
        val data = plugin.classes.data(player.uniqueId)
        val now = System.currentTimeMillis()
        val extendMs = cfg.getInt("warrior.bloodlust-ticks-per-kill", 20).coerceAtLeast(0) * 50L
        val capMs = data.berserkStartedAt +
            (cfg.getDouble("warrior.berserk-max-seconds", 10.0).coerceAtLeast(0.0) * 1000.0).toLong()
        val newUntil = minOf(data.rageActiveUntil + extendMs, capMs)
        if (newUntil <= data.rageActiveUntil) return
        data.rageActiveUntil = newUntil
        refreshBerserkPotions(player, rank, ((newUntil - now) / 50L).toInt().coerceAtLeast(1))
        player.sendActionBar(Component.text("§4§lBLOODLUST §7+${"%.1f".format(extendMs / 1000.0)}s"))
        player.world.spawnParticle(Particle.DUST, player.location.clone().add(0.0, 1.0, 0.0), 14, 0.4, 0.5, 0.4, 0.0,
            Particle.DustOptions(org.bukkit.Color.fromRGB(150, 0, 0), 1.5f))
        player.playSound(player.location, Sound.ENTITY_WITHER_SPAWN, 0.22f, 1.7f)
    }

    /** Berserk's opening blow: an AoE stomp around the Warrior - damage, knockback, a brief stagger. */
    private fun seismicSlam(player: Player, rank: Int) {
        val cfg = plugin.classesConfig
        val shockwave = rank >= cfg.getInt("warrior.berserk-shockwave-min-rank", 5)
        val radius = cfg.getDouble("warrior.slam-radius", 4.0).coerceIn(1.0, 12.0) *
            (if (shockwave) cfg.getDouble("warrior.slam-shockwave-radius-multiplier", 1.6) else 1.0)
        val damage = cfg.getDouble("warrior.slam-damage", 8.0).coerceAtLeast(0.0)
        val knockback = cfg.getDouble("warrior.slam-knockback", 0.6).coerceAtLeast(0.0)
        val knockUp = cfg.getDouble("warrior.slam-knockup", 0.28).coerceIn(0.0, 1.0)
        val staggerTicks = cfg.getInt("warrior.slam-stagger-ticks", 40).coerceAtLeast(0)
        val centre = player.location
        centre.world?.getNearbyEntities(centre, radius, 3.0, radius)
            ?.filterIsInstance<LivingEntity>()
            ?.filter { it != player && it !is Player && (plugin.queries.isDungeonMob(it) || it is Mob) }
            ?.forEach { mob ->
                if (damage > 0.0) mob.damage(damage, player)
                val push = mob.location.toVector().subtract(centre.toVector())
                if (push.lengthSquared() > 1e-6) push.normalize() else push.zero()
                mob.velocity = mob.velocity.add(push.multiply(knockback)).setY(knockUp)
                if (staggerTicks > 0) {
                    mob.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, staggerTicks, 2, true, false, true))
                }
            }
        plugin.classFeedback.warriorSlam(player, shockwave)
    }

    private fun updateBerserkPresence(player: Player, data: PlayerClassData, now: Long) {
        val id = player.uniqueId
        if (data.rageActiveUntil > now) {
            if (berserkActive.add(id)) berserkFadeWarned.remove(id)
            player.world.spawnParticle(Particle.FLAME, player.location.clone().add(0.0, 1.0, 0.0), 10, 0.4, 0.6, 0.4, 0.01)
            player.world.spawnParticle(Particle.SMALL_FLAME, player.location.clone().add(0.0, 0.4, 0.0), 6, 0.35, 0.25, 0.35, 0.0)
            if ((data.rageActiveUntil - now) in 1..1600 && berserkFadeWarned.add(id)) {
                player.sendActionBar(Component.text("§cRage fading..."))
                player.playSound(player.location, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 0.7f)
            }
        } else if (berserkActive.remove(id)) {
            berserkFadeWarned.remove(id)
            player.sendActionBar(Component.text("§7Your Rage subsides."))
            player.playSound(player.location, Sound.ENTITY_BLAZE_DEATH, 0.4f, 0.9f)
        }
    }

    private fun buildTaunt(player: Player, damage: Double, rank: Int) {
        // No Judgment builds while the Taunt stance is already up - you spend
        // that window, you do not charge the next one during it.
        if (isTaunting(player)) return
        val data = plugin.classes.data(player.uniqueId)
        data.lastJudgmentCombatAt = System.currentTimeMillis()
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
        // A full bar is a banked Berserk - it holds until the player unleashes
        // it, like a charged Taunt. Only a partial bar bleeds out.
        if (data.rage >= rageThreshold(rank)) return
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

    private fun archerAttackBonus(player: Player): Double =
        (player.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 1.0) *
            plugin.classesConfig.getDouble("archer.attack-stat-damage-multiplier", 1.0).coerceAtLeast(0.0)

    private fun tauntThreshold(rank: Int): Double {
        val base = plugin.classesConfig.getDouble("paladin.taunt-damage-threshold", 250.0)
        val perRank = plugin.classesConfig.getDouble("paladin.taunt-threshold-per-rank", 15.0)
        return (base - (rank - 1) * perRank).coerceAtLeast(20.0)
    }

    private fun tauntDurationTicks(rank: Int): Int =
        ((plugin.classesConfig.getDouble("paladin.taunt-duration-seconds", 8.0) + (rank - 1)) * 20.0).roundToInt().coerceAtLeast(20)

    private fun maintainTaunt() {
        val taunt = activeTaunt ?: return
        val player = plugin.server.getPlayer(taunt.playerId)
        val ended = player == null || !player.isOnline || taunt.expiresAt <= System.currentTimeMillis() ||
            plugin.classes.activeClass(player.uniqueId) != ClassType.PALADIN
        if (ended) {
            activeTaunt = null
            consecrations.remove(taunt.playerId)?.task?.cancel()
            if (player != null) {
                removeTauntKnockbackLock(player)
                if (player.isOnline) {
                    // The Zeal you banked releases twice: a one-shot Holy Nova
                    // now, and an empowered-Smite (Retribution) window - both
                    // scaled by how full Zeal was.
                    val data = plugin.classes.data(player.uniqueId)
                    val power = (data.zeal / zealThreshold()).coerceIn(0.0, 1.0)
                    data.zeal = 0.0
                    if (power > 0.0) {
                        releaseHolyNova(player, power)
                        startRetribution(player, power)
                    } else {
                        player.sendActionBar(Component.text("§7The line breaks - Taunt ends."))
                    }
                    plugin.refreshClassPlayer(player)
                }
            }
            return
        }
        targetMobsInRadius(player!!)
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

private data class ActiveTaunt(val playerId: UUID, val expiresAt: Long, val rank: Int)

/** Snapshot of an active Taunt for the Paladin's Zeal boss bar. */
data class TauntStatus(
    val zeal: Double,
    val zealThreshold: Double,
    val smiteBonus: Double,
    val pendingRetribution: Double,
    val secondsLeft: Double,
)

enum class ArcaneCastResult { SUCCESS, MANA_LOCKED, WRONG_WEAPON, INSUFFICIENT_MANA, COOLDOWN }

enum class FocusShotResult { SUCCESS, LOCKED, WRONG_WEAPON, NOT_CHARGED }

enum class TauntActivationResult { SUCCESS, LOCKED, NOT_READY, ALREADY_ACTIVE }

enum class BerserkActivationResult { SUCCESS, WRONG_CLASS, LOCKED, NOT_READY, ALREADY_ACTIVE, ON_COOLDOWN }
