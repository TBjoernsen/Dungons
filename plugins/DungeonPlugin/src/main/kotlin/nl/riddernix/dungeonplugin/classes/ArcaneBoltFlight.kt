package nl.riddernix.dungeonplugin.classes

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * The Mage's Arcane Bolt: a raycast, not a thrown entity.
 *
 * Each tick it sweeps forward `mage.bolt.speed` blocks with one
 * [org.bukkit.World.rayTrace], so it flies fast and straight with no gravity
 * or drag - it reads as a spell, not a snowball. A glowing [BlockDisplay] orb
 * and a dust trail ride the ray for the visual. On a hit it deals the direct
 * damage and hands the impact point back to [PassiveService] for the splash.
 */
class ArcaneBoltFlight private constructor(
    private val plugin: DungeonPlugin,
    private val shooter: Player,
    private val directDamage: Double,
    private val pierce: Int,
    private val surge: Boolean,
    private val onImpact: (Location, java.util.UUID?) -> Unit
) : BukkitRunnable() {

    private val cfg get() = plugin.classesConfig
    private val direction: Vector = shooter.eyeLocation.direction.normalize()
    private val speed = cfg.getDouble("mage.bolt.speed", 3.0).coerceIn(0.5, 12.0)
    private val maxRange = cfg.getDouble("mage.bolt.max-range", 40.0).coerceIn(4.0, 128.0)
    // Start the bolt out in front of the caster's face so the trail does not
    // erupt across the screen when you fire standing still.
    private val muzzleOffset = cfg.getDouble("mage.bolt.muzzle-offset", 1.4).coerceIn(0.0, 4.0)
    private val trailStartGap = cfg.getDouble("mage.bolt.trail.start-gap", 1.0).coerceIn(0.0, 8.0)
    private var pos: Location = shooter.eyeLocation.clone()
        .add(direction.clone().multiply(muzzleOffset)).apply { y -= 0.15 }
    private var travelled = 0.0
    private var ticksLived = 0
    private val maxTicks = ceil(maxRange / speed).toInt() + 6
    private var spin = 0f
    private val hitIds = HashSet<java.util.UUID>()
    private val orbScale = (cfg.getDouble("mage.bolt.orb.scale", 0.35).coerceIn(0.05, 2.0) *
        (if (surge) 1.9 else 1.0)).toFloat()

    private val orb: BlockDisplay = spawnOrb()

    override fun run() {
        val world = pos.world
        if (world == null || !shooter.isOnline || shooter.world != world) return finish(hit = false)
        if (travelled >= maxRange || ++ticksLived > maxTicks) return finish(hit = false)

        // Only collide with mobs while the caster is in a dungeon - the staff
        // is a dungeon weapon; elsewhere the bolt is cosmetic and flies until
        // it meets a block.
        val canDamage = plugin.queries.isInDungeon(shooter)
        val step = speed.coerceAtMost(maxRange - travelled)
        val hit = world.rayTrace(pos, direction, step, FluidCollisionMode.NEVER, true,
            cfg.getDouble("mage.bolt.ray-size", 0.3).coerceIn(0.05, 1.0)) { entity ->
            canDamage && entity is LivingEntity && entity !is Player &&
                entity.uniqueId != shooter.uniqueId && entity.uniqueId !in hitIds
        }

        val advance = hit?.hitPosition?.toLocation(world)?.let { pos.distance(it) } ?: step
        drawTrail(pos, advance)
        pos.add(direction.clone().multiply(advance))
        travelled += advance
        moveOrb()

        if (hit != null) {
            val target = hit.hitEntity as? LivingEntity
            if (target != null) {
                val keptVelocity = target.velocity.clone()
                target.damage(directDamage, shooter)
                if (!cfg.getBoolean("mage.bolt.direct-knockback", true)) target.velocity = keptVelocity
                hitIds.add(target.uniqueId)
            }
            onImpact(pos.clone(), target?.uniqueId)
            // Pierce: after an entity hit, if there is room to punch through
            // another, nudge past this one and keep flying. A block hit always
            // stops the bolt.
            if (target != null && hitIds.size <= pierce && travelled < maxRange) {
                pos.add(direction.clone().multiply(0.6))
                return
            }
            return finish(hit = true)
        }
    }

    private fun finish(hit: Boolean) {
        cancel()
        orb.remove()
        if (hit) {
            val mul = if (surge) 2.5 else 1.0
            val burst = (maxOf(0, cfg.mageWandInt("impact-particles", 12)) * mul).toInt()
            val world = pos.world
            val fiery = cfg.mageWandBoolean("impact-lava", false)
            val main = runCatching { Particle.valueOf(cfg.mageWandString("impact-particle", "WITCH").uppercase(Locale.ROOT)) }
                .getOrDefault(Particle.WITCH)
            world?.spawnParticle(main, pos, burst, 0.18, 0.18, 0.18, if (fiery) 0.05 else 0.1)
            world?.spawnParticle(Particle.DUST, pos, burst / 2, 0.2, 0.2, 0.2, 0.0, trailDust())
            if (fiery) world?.spawnParticle(Particle.LAVA, pos, maxOf(1, burst / 4), 0.14, 0.14, 0.14, 0.0)
            if (surge) {
                world?.spawnParticle(Particle.FLASH, pos, 1, 0.0, 0.0, 0.0, 0.0)
                if (fiery) world?.spawnParticle(Particle.EXPLOSION, pos, 2, 0.2, 0.2, 0.2, 0.0)
            }
            playSound(cfg.mageWandString("impact-sound", "block_amethyst_block_hit"),
                if (surge) 0.9f else if (fiery) 0.45f else 0.9f, if (surge) 0.7f else 1.1f)
        }
    }

    // ------------------------------------------------------------------

    private fun spawnOrb(): BlockDisplay {
        val material = cfg.mageWandMaterial("orb-block", Material.AMETHYST_BLOCK)
            .takeIf { it.isBlock } ?: Material.AMETHYST_BLOCK
        val glow = cfg.getBoolean("mage.bolt.orb.glow", true)
        return pos.world!!.spawn(pos, BlockDisplay::class.java) { d ->
            d.block = material.createBlockData()
            d.billboard = Display.Billboard.FIXED
            d.transformation = orbTransform(orbScale)
            if (glow) d.brightness = Display.Brightness(15, 15)
            d.teleportDuration = 1
            d.interpolationDelay = 0
            d.interpolationDuration = 1
            d.viewRange = cfg.getDouble("mage.bolt.orb.view-range", 2.0).toFloat()
            d.isPersistent = false
            d.isInvulnerable = true
        }
    }

    private fun orbTransform(scale: Float): Transformation = Transformation(
        Vector3f(-scale / 2f, -scale / 2f, -scale / 2f),
        Quaternionf().rotateY(Math.toRadians(spin.toDouble()).toFloat())
            .rotateX(Math.toRadians(spin * 0.6).toFloat()),
        Vector3f(scale, scale, scale),
        Quaternionf())

    private fun moveOrb() {
        spin += cfg.getDouble("mage.bolt.orb.spin-degrees-per-tick", 22.0).toFloat()
        orb.teleport(Location(pos.world, pos.x, pos.y, pos.z))
        orb.interpolationDelay = 0
        orb.interpolationDuration = 1
        orb.transformation = orbTransform(orbScale)
    }

    private fun drawTrail(from: Location, distance: Double) {
        val world = from.world ?: return
        val dust = trailDust()
        val spacing = cfg.mageWandDouble("trail-spacing", 0.55).coerceIn(0.1, 1.5)
        val accentEvery = maxOf(1, cfg.mageWandInt("trail-accent-every", 4))
        val accent = accentParticle()
        var d = 0.0
        var index = 0
        while (d < distance) {
            // Leave a clear gap right in front of the caster.
            if (travelled + d < trailStartGap) { d += spacing; index++; continue }
            val at = from.clone().add(direction.clone().multiply(d))
            world.spawnParticle(Particle.DUST, at, 1, 0.02, 0.02, 0.02, 0.0, dust)
            if (accent != null && index % accentEvery == 0) {
                world.spawnParticle(accent, at, 1, 0.03, 0.03, 0.03, 0.0)
            }
            d += spacing
            index++
        }
    }

    private fun trailDust(): Particle.DustOptions {
        val leaf = if (surge) "surge-trail-color" else "trail-color"
        val hex = cfg.mageWandString(leaf, if (surge) "D8B4FF" else "B45AFF").trim().removePrefix("#")
        val rgb = runCatching { hex.toInt(16) }.getOrNull() ?: 0xB45AFF
        val size = cfg.mageWandDouble("trail-size", 0.9).coerceIn(0.1, 4.0) * (if (surge) 1.7 else 1.0)
        return Particle.DustOptions(Color.fromRGB((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF), size.toFloat())
    }

    private fun accentParticle(): Particle? {
        val raw = cfg.mageWandString("trail-accent", "none").trim()
        if (raw.isEmpty() || raw.equals("none", true)) return null
        return runCatching { Particle.valueOf(raw.uppercase(Locale.ROOT)) }.getOrNull()
    }

    private fun playSound(name: String, volume: Float, pitch: Float) {
        if (name.isBlank()) return
        val sound = Registry.SOUNDS.get(NamespacedKey.minecraft(name.lowercase(Locale.ROOT).replace('_', '.')))
        if (sound != null) pos.world?.playSound(pos, sound, volume, pitch)
    }

    companion object {
        /**
         * Fires a bolt now. [pierce] is how many extra entities it can punch
         * through; [surge] swaps in the Arcane Surge look (bigger orb, brighter
         * fatter trail, heavier impact). [onImpact] fires per direct entity hit
         * with that entity's id, and once with `null` on a block hit.
         */
        fun launch(plugin: DungeonPlugin, shooter: Player, directDamage: Double,
                   pierce: Int = 0, surge: Boolean = false,
                   onImpact: (Location, java.util.UUID?) -> Unit) {
            val flight = ArcaneBoltFlight(plugin, shooter, max(0.0, directDamage), pierce.coerceAtLeast(0), surge, onImpact)
            flight.runTaskTimer(plugin, 0L, 1L)
        }
    }
}
