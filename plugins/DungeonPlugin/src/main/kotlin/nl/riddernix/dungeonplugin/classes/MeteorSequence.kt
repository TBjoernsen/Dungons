package nl.riddernix.dungeonplugin.classes

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Battlemage's Meteor: a telegraph ring shows the landing spot, then a
 * falling display-entity meteor drops onto it, then a radius payoff -
 * full damage to dungeon mobs, a fraction to players (the caster included).
 *
 * Mirrors [ArcaneBoltFlight]'s per-tick [BukkitRunnable] recipe; the timing
 * and the falling visual live here, the particle/sound bursts are
 * [FeedbackService]'s, same split as every other class effect.
 */
class MeteorSequence private constructor(
    private val plugin: DungeonPlugin,
    private val caster: Player,
    private val impact: Location
) : BukkitRunnable() {

    private val cfg get() = plugin.classesConfig
    // Battlemage's mastery quest ladder (mastery-quests.yml) raises Meteor's
    // own numbers directly, on top of whatever the general Bolt/Surge bonus
    // (PassiveService.arcaneBoltDamage) already gives it.
    private val masteryLevel = plugin.classes.masteryLevelFor(caster.uniqueId, "attack")
    private val telegraphTicks = cfg.getInt("abilities.mage.meteor-telegraph-ticks", 24).coerceAtLeast(1)
    private val fallHeight = cfg.getDouble("abilities.mage.meteor-fall-height", 14.0).coerceAtLeast(1.0)
    private val fallTicks = cfg.getInt("abilities.mage.meteor-fall-ticks", 10).coerceAtLeast(1)
    private val radius = (cfg.getDouble("abilities.mage.meteor-radius", 4.0) +
        cfg.getDouble("abilities.mage.meteor-radius-per-mastery-level", 0.15) * masteryLevel).coerceIn(1.0, 16.0)
    private var ticks = 0
    private var meteor: BlockDisplay? = null

    private fun begin() {
        meteor = spawnMeteor()
        runTaskTimer(plugin, 1L, 1L)
    }

    override fun run() {
        if (ticks < telegraphTicks) {
            if (ticks % 5 == 0) plugin.classFeedback.meteorTelegraph(impact, radius)
            ticks++
            return
        }
        val fallTick = ticks - telegraphTicks
        if (fallTick <= fallTicks) {
            val progress = fallTick.toDouble() / fallTicks
            val y = impact.y + fallHeight * (1.0 - progress)
            val at = Location(impact.world, impact.x, y, impact.z)
            meteor?.teleport(at)
            plugin.classFeedback.meteorFallTrail(at)
            ticks++
            return
        }
        cancel()
        meteor?.remove()
        impactNow()
    }

    private fun spawnMeteor(): BlockDisplay {
        val world = impact.world!!
        val spawnLoc = impact.clone().add(0.0, fallHeight, 0.0)
        val scale = 1.6f
        return world.spawn(spawnLoc, BlockDisplay::class.java) { d ->
            d.block = Material.MAGMA_BLOCK.createBlockData()
            d.billboard = Display.Billboard.FIXED
            d.transformation = Transformation(
                Vector3f(-scale / 2f, -scale / 2f, -scale / 2f), Quaternionf(),
                Vector3f(scale, scale, scale), Quaternionf())
            d.brightness = Display.Brightness(15, 15)
            d.teleportDuration = 1
            d.isPersistent = false
            d.isInvulnerable = true
        }
    }

    /** No block damage regardless of any gamerule: this is radius entity damage only, never a vanilla explosion. */
    private fun impactNow() {
        val world = impact.world ?: return
        val damage = (cfg.getDouble("abilities.mage.meteor-damage", 40.0) +
            cfg.getDouble("abilities.mage.meteor-damage-per-mastery-level", 4.0) * masteryLevel).coerceAtLeast(0.0)
        val allyFraction = cfg.getDouble("abilities.mage.meteor-ally-damage-fraction", 0.25).coerceIn(0.0, 1.0)
        val knockback = cfg.getDouble("abilities.mage.meteor-knockback", 0.7).coerceAtLeast(0.0)
        val knockUp = cfg.getDouble("abilities.mage.meteor-knockup", 0.35).coerceIn(0.0, 1.0)
        world.getNearbyEntities(impact, radius, radius, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it is Player || plugin.queries.isDungeonMob(it) }
            .forEach { entity ->
                if (entity is Player) {
                    // Sourceless: a player-vs-player EntityDamageByEntityEvent
                    // is cancelled outright by DungeonPvpListener (on by
                    // default) regardless of amount - this is a designed
                    // splash, not PvP, so it must not carry a damager.
                    val amount = damage * allyFraction
                    if (amount > 0.0) entity.damage(amount)
                } else if (damage > 0.0) {
                    entity.damage(damage, caster)
                }
                val push = entity.location.toVector().subtract(impact.toVector())
                if (push.lengthSquared() > 1e-6) push.normalize() else push.zero()
                entity.velocity = entity.velocity.add(push.multiply(knockback)).setY(knockUp)
            }
        plugin.classFeedback.meteorImpact(impact, radius)
    }

    companion object {
        fun launch(plugin: DungeonPlugin, caster: Player, impact: Location) {
            MeteorSequence(plugin, caster, impact).begin()
        }
    }
}
