package nl.riddernix.dungeonplugin.fx

import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Rift Devourer's entrance: a tear opens in the floor, shards of the rift
 * turn around it, and the boss is dragged up out of the ground.
 *
 * Every moving part is a display entity. Their transformations are set once
 * per phase with an interpolation duration, so the client tweens between the
 * poses and the server sends a handful of packets instead of one per frame.
 * That is the only way to get genuinely smooth motion from a plugin: the
 * vanilla mob model itself cannot be animated server-side.
 *
 * The shards all live at the tear's centre and are pushed onto their ring by
 * the translation part of their transformation. Orbiting is then a property
 * of the interpolation rather than a teleport, which keeps it smooth at any
 * tick rate.
 */
internal class RiftTearAnimation(
    private val plugin: DungeonPlugin,
    private val boss: LivingEntity,
    private val allowRise: Boolean
) : SpawnAnimation {

    private val shards = ArrayList<BlockDisplay>()

    private val shardCount = plugin.config.getInt(PATH + "shards", 8).coerceIn(1, 32)
    private val radius = maxOf(0.5, plugin.config.getDouble(PATH + "radius", 3.2))
    private val shardWidth = maxOf(0.05, plugin.config.getDouble(PATH + "shard-width", 0.35))
    private val shardHeight = maxOf(0.1, plugin.config.getDouble(PATH + "shard-height", 2.4))
    private val orbitDegreesPerSecond = plugin.config.getDouble(PATH + "orbit-degrees-per-second", 70.0)
    private val riseDepth = maxOf(0.0, plugin.config.getDouble(PATH + "rise-depth", 3.5))
    private val rise = plugin.config.getBoolean(PATH + "rise", true)
    private val shardMaterial = material(PATH + "shard-material", Material.CRYING_OBSIDIAN)
    private val discMaterial = material(PATH + "disc-material", Material.OBSIDIAN)
    private val particle = particle(plugin.config.getString(PATH + "particle", "REVERSE_PORTAL"))
    private val ambientSound = sound(plugin.config.getString(PATH + "ambient-sound", "BLOCK_PORTAL_AMBIENT"))
    private val emergeSound = sound(plugin.config.getString(PATH + "emerge-sound", "ENTITY_WARDEN_EMERGE"))
    private val glow = color(plugin.config.getString(PATH + "glow-colour", "8A2BE2"))
    private val scaleWithBoss = plugin.config.getBoolean(PATH + "scale-with-boss", true)

    private var disc: BlockDisplay? = null
    private var origin: Location? = null

    /** Read once the boss exists, so the tear matches how big it actually is. */
    private var bossScale = 1.0
    private var emerged = false
    private var finished = false
    private var suspended = false
    private var previousGravity = false

    /** The ring has to grow with the boss, or a scaled boss dwarfs its own tear. */
    private fun ringRadius(): Double = radius * bossScale

    /** Likewise the burial depth: a taller boss needs deeper ground to hide in. */
    private fun riseHeight(): Double = riseDepth * bossScale

    // ------------------------------------------------------------------

    override fun begin() {
        val origin = boss.location.clone()
        this.origin = origin
        val world = origin.world ?: return
        val scale = boss.getAttribute(Attribute.SCALE)
        bossScale = if (scaleWithBoss && scale != null) maxOf(0.1, scale.value) else 1.0

        // The disc is the tear itself: a thin slab, wider than the boss, that
        // turns slowly under the shards.
        disc = spawn(world, discMaterial, discPose(0.0, SEED_SCALE))

        for (index in 0 until shardCount) {
            // Every shard starts small at the centre, so the opening is one
            // interpolation away rather than a pop-in.
            val shard = spawn(world, shardMaterial, pose(index * angleStep(), 0.0, 0.0, SEED_SCALE))
            shards.add(shard)
        }

        if (rise && allowRise && riseDepth > 0.0) {
            // Burying the boss is only safe because the sequence holds it
            // invulnerable; SpawnAnimations refuses this otherwise.
            // Gravity has to go with it: a prefab arena floor can have nothing
            // underneath, and a falling boss would drop straight into the
            // void.
            previousGravity = boss.hasGravity()
            suspended = true
            boss.setGravity(false)
            boss.teleport(atHeight(origin.y - riseHeight()))
        }
        play(ambientSound, 1.4F, 0.6F)
    }

    override fun tick(ticks: Int, durationTicks: Int) {
        val world = origin?.world
        if (world == null || finished) {
            return
        }
        val openTicks = maxOf(4, durationTicks / 4)
        val riseStart = maxOf(openTicks + 1, (durationTicks * 0.55).toInt())

        if (ticks == 1) {
            // One set of target poses; the client tweens the whole opening.
            open(openTicks)
        }
        if (ticks > openTicks && ticks % ORBIT_STEP_TICKS == 0) {
            orbit(ticks, ticks >= riseStart)
        }
        if (ticks % 10 == 0) {
            play(ambientSound, 1.0F, 0.5F + ticks.toFloat() / maxOf(1, durationTicks) * 0.5F)
        }
        if (ticks % 2 == 0) {
            particles(world, ticks >= riseStart)
        }
        if (ticks >= riseStart && rise && allowRise && riseDepth > 0.0) {
            if (!emerged) {
                emerged = true
                play(emergeSound, 1.6F, 0.8F)
            }
            // Small steps rather than one teleport: at 20 ticks per second
            // this reads as the boss climbing out of the tear.
            val progress = ((ticks - riseStart).toDouble() / (durationTicks - riseStart)).coerceIn(0.0, 1.0)
            boss.teleport(atHeight(origin!!.y - riseHeight() * (1.0 - progress)))
        }
    }

    override fun finish() {
        if (finished) {
            return
        }
        finished = true
        if (boss.isValid && !boss.isDead) {
            // Never leave the boss buried or hanging above its arena floor.
            boss.teleport(atHeight(origin?.y ?: boss.location.y))
        }
        restoreGravity()
        for (index in shards.indices) {
            val shard = shards[index]
            if (shard.isValid) {
                // Pulled back into the tear and scaled away, so nothing pops
                // out.
                collapse(shard, pose(index * angleStep(), 0.0, 0.0, 0.0))
            }
        }
        disc?.let { if (it.isValid) collapse(it, discPose(0.0, 0.0)) }
        object : BukkitRunnable() {
            override fun run() {
                remove()
            }
        }.runTaskLater(plugin, OUTRO_TICKS.toLong())
    }

    override fun abort() {
        finished = true
        // A boss that was buried for the rise must never be left underground
        // just because the animation was cut short.
        if (suspended && origin != null && boss.isValid && !boss.isDead) {
            boss.teleport(atHeight(origin!!.y))
        }
        restoreGravity()
        remove()
    }

    override fun describe(): String {
        val alive = shards.count { it.isValid }
        val at = origin?.let { "${it.blockX},${it.blockY},${it.blockZ}" } ?: "nowhere"
        return "rift_tear: $alive/$shardCount shards + ${if (disc?.isValid == true) "1" else "0"}" +
            " disc at $at, radius ${ringRadius()}, rise ${if (rise && allowRise) riseHeight() else 0.0}, boss scale $bossScale"
    }

    private fun restoreGravity() {
        if (!suspended) {
            return
        }
        suspended = false
        if (boss.isValid) {
            boss.setGravity(previousGravity)
        }
    }

    // ------------------------------------------------------------------

    /** Grows every shard out of the centre onto its ring in one interpolation. */
    private fun open(openTicks: Int) {
        for (index in shards.indices) {
            val shard = shards[index]
            if (!shard.isValid) {
                continue
            }
            shard.interpolationDelay = 0
            shard.interpolationDuration = openTicks
            shard.transformation = pose(index * angleStep(), ringRadius(), 0.0, 1.0)
        }
        disc?.let {
            if (it.isValid) {
                it.interpolationDelay = 0
                it.interpolationDuration = openTicks
                it.transformation = discPose(0.0, 1.0)
            }
        }
    }

    /**
     * Steps the ring forward. Each update interpolates over exactly the gap
     * to the next one, so the shards turn continuously instead of stuttering.
     */
    private fun orbit(ticks: Int, tilted: Boolean) {
        val turn = Math.toRadians(orbitDegreesPerSecond) * ticks / 20.0
        // Once the boss starts climbing, the shards lean inward over the tear.
        val tilt = if (tilted) Math.toRadians(28.0) else 0.0
        val lift = if (tilted) 0.6 else 0.0
        for (index in shards.indices) {
            val shard = shards[index]
            if (!shard.isValid) {
                continue
            }
            shard.interpolationDelay = 0
            shard.interpolationDuration = ORBIT_STEP_TICKS
            shard.transformation = pose(index * angleStep() + turn, ringRadius(), tilt, 1.0, lift)
        }
        disc?.let {
            if (it.isValid) {
                it.interpolationDelay = 0
                it.interpolationDuration = ORBIT_STEP_TICKS
                it.transformation = discPose(-turn * 0.4, 1.0)
            }
        }
    }

    private fun collapse(display: BlockDisplay, target: Transformation) {
        display.interpolationDelay = 0
        display.interpolationDuration = OUTRO_TICKS - 2
        display.transformation = target
    }

    private fun remove() {
        for (shard in shards) {
            if (shard.isValid) {
                shard.remove()
            }
        }
        shards.clear()
        disc?.let { if (it.isValid) it.remove() }
        disc = null
    }

    // ------------------------------------------------------------------
    //  Poses
    // ------------------------------------------------------------------

    /**
     * Places one shard by transformation only. The entity itself never moves,
     * which is what lets the client interpolate the whole orbit.
     */
    private fun pose(angle: Double, distance: Double, tilt: Double, scale: Double, lift: Double = 0.0): Transformation {
        val width = (shardWidth * scale * bossScale).toFloat()
        val height = (shardHeight * scale * bossScale).toFloat()
        // Turn the flat side outward, then lean it over the tear.
        val rotation = Quaternionf().rotateY((-angle).toFloat()).rotateX(tilt.toFloat())
        val base = pivot(rotation, width, 0.0F)
        return Transformation(Vector3f(
            (cos(angle) * distance).toFloat() - base.x,
            lift.toFloat() - base.y,
            (sin(angle) * distance).toFloat() - base.z),
            rotation, Vector3f(width, height, width), Quaternionf())
    }

    private fun discPose(angle: Double, scale: Double): Transformation {
        val span = (ringRadius() * 2.0 * scale).toFloat()
        val rotation = Quaternionf().rotateY(angle.toFloat())
        val centre = pivot(rotation, span, 0.0F)
        return Transformation(Vector3f(-centre.x, 0.02F - centre.y, -centre.z),
            rotation, Vector3f(span, (0.08 * scale).toFloat(), span), Quaternionf())
    }

    private fun spawn(world: World, material: Material, transformation: Transformation): BlockDisplay {
        return world.spawn(origin!!, BlockDisplay::class.java) { display ->
            display.block = material.createBlockData()
            display.transformation = transformation
            display.brightness = Display.Brightness(15, 15)
            display.isGlowing = true
            display.glowColorOverride = glow
            display.viewRange = 2.0F
            // Never written to disk: these belong to one fight, not a world.
            display.isPersistent = false
        }
    }

    /**
     * Uses the configured count, never `shards.size`: during [begin] the list
     * is still filling up, and dividing by its size handed the first shard an
     * infinite step and a NaN transformation, which the client cannot render
     * at all.
     */
    private fun angleStep(): Double = Math.PI * 2.0 / maxOf(1, shardCount)

    // ------------------------------------------------------------------

    private fun particles(world: World, rising: Boolean) {
        val centre = origin!!.clone().add(0.0, 0.1, 0.0)
        world.spawnParticle(particle, centre, if (rising) 14 else 6, ringRadius() * 0.8, 0.4, ringRadius() * 0.8, 0.02)
        if (rising) {
            world.spawnParticle(Particle.SCULK_CHARGE_POP, centre, 6, ringRadius() * 0.5, 0.2, ringRadius() * 0.5, 0.01)
        }
    }

    private fun atHeight(y: Double): Location {
        val location = origin?.clone() ?: boss.location
        location.y = y
        // The summoning sequence spins the boss every tick; keep that
        // rotation.
        location.yaw = boss.location.yaw
        location.pitch = 0.0F
        return location
    }

    private fun play(sound: Sound, volume: Float, pitch: Float) {
        val origin = origin ?: return
        origin.world?.playSound(origin, sound, volume, pitch)
    }

    private fun material(path: String, fallback: Material): Material {
        val raw = plugin.config.getString(path, fallback.name)
        val material = raw?.let { Material.matchMaterial(it.uppercase(Locale.ROOT)) }
        return if (material == null || !material.isBlock) fallback else material
    }

    companion object {
        private const val PATH = "mobs.boss-summoning.animations.rift_tear."

        /** How often the orbit pose is refreshed; each step interpolates over the gap. */
        private const val ORBIT_STEP_TICKS = 4
        private const val OUTRO_TICKS = 10

        /**
         * Shards are seeded at a quarter size rather than at zero. A
         * zero-scale display is invisible, so if anything ever stops the tick
         * loop there is something on screen to see instead of a silent
         * nothing.
         */
        private const val SEED_SCALE = 0.25

        /**
         * A display rotates around its own 0,0,0 corner, and the translation
         * is added afterwards rather than rotated with it. So the offset that
         * centres a block has to be rotated by hand and subtracted, otherwise
         * a tilted shard swings away from its ring and a turning disc slides
         * off its centre instead of spinning in place.
         */
        private fun pivot(rotation: Quaternionf, width: Float, height: Float): Vector3f =
            rotation.transform(Vector3f(width / 2.0F, height, width / 2.0F))

        private fun particle(raw: String?): Particle = try {
            Particle.valueOf((raw ?: "").uppercase(Locale.ROOT))
        } catch (ignored: IllegalArgumentException) {
            Particle.REVERSE_PORTAL
        }

        private fun sound(raw: String?): Sound {
            if (raw.isNullOrBlank()) {
                return Sound.BLOCK_PORTAL_AMBIENT
            }
            return Registry.SOUNDS.get(NamespacedKey.minecraft(raw.lowercase(Locale.ROOT).replace('_', '.')))
                ?: Sound.BLOCK_PORTAL_AMBIENT
        }

        private fun color(raw: String?): Color = try {
            Color.fromRGB(Integer.parseInt((raw ?: "").replace("#", "").trim(), 16))
        } catch (ignored: IllegalArgumentException) {
            Color.fromRGB(0x8A2BE2)
        }
    }
}
