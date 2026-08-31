package nl.riddernix.dungeonforge.fx;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Rift Devourer's entrance: a tear opens in the floor, shards of the rift
 * turn around it, and the boss is dragged up out of the ground.
 *
 * <p>Every moving part is a display entity. Their transformations are set once
 * per phase with an interpolation duration, so the client tweens between the
 * poses and the server sends a handful of packets instead of one per frame.
 * That is the only way to get genuinely smooth motion from a plugin: the
 * vanilla mob model itself cannot be animated server-side.</p>
 *
 * <p>The shards all live at the tear's centre and are pushed onto their ring by
 * the translation part of their transformation. Orbiting is then a property of
 * the interpolation rather than a teleport, which keeps it smooth at any tick
 * rate.</p>
 */
final class RiftTearAnimation implements SpawnAnimation {

    private static final String PATH = "mobs.boss-summoning.animations.rift_tear.";
    /** How often the orbit pose is refreshed; each step interpolates over the gap. */
    private static final int ORBIT_STEP_TICKS = 4;
    private static final int OUTRO_TICKS = 10;
    /**
     * Shards are seeded at a quarter size rather than at zero. A zero-scale
     * display is invisible, so if anything ever stops the tick loop there is
     * something on screen to see instead of a silent nothing.
     */
    private static final double SEED_SCALE = 0.25;

    private final DungeonForgePlugin plugin;
    private final LivingEntity boss;
    private final boolean allowRise;
    private final List<BlockDisplay> shards = new ArrayList<>();

    private final int shardCount;
    private final double radius;
    private final double shardWidth;
    private final double shardHeight;
    private final double orbitDegreesPerSecond;
    private final double riseDepth;
    private final boolean rise;
    private final Material shardMaterial;
    private final Material discMaterial;
    private final Particle particle;
    private final Sound ambientSound;
    private final Sound emergeSound;
    private final Color glow;

    private final boolean scaleWithBoss;

    private BlockDisplay disc;
    private Location origin;
    /** Read once the boss exists, so the tear matches how big it actually is. */
    private double bossScale = 1.0;
    private boolean emerged;
    private boolean finished;
    private boolean suspended;
    private boolean previousGravity;

    RiftTearAnimation(DungeonForgePlugin plugin, LivingEntity boss, boolean allowRise) {
        this.plugin = plugin;
        this.boss = boss;
        this.allowRise = allowRise;
        this.shardCount = Math.clamp(plugin.getConfig().getInt(PATH + "shards", 8), 1, 32);
        this.radius = Math.max(0.5, plugin.getConfig().getDouble(PATH + "radius", 3.2));
        this.shardWidth = Math.max(0.05, plugin.getConfig().getDouble(PATH + "shard-width", 0.35));
        this.shardHeight = Math.max(0.1, plugin.getConfig().getDouble(PATH + "shard-height", 2.4));
        this.orbitDegreesPerSecond = plugin.getConfig().getDouble(PATH + "orbit-degrees-per-second", 70.0);
        this.riseDepth = Math.max(0.0, plugin.getConfig().getDouble(PATH + "rise-depth", 3.5));
        this.rise = plugin.getConfig().getBoolean(PATH + "rise", true);
        this.shardMaterial = material(PATH + "shard-material", Material.CRYING_OBSIDIAN);
        this.discMaterial = material(PATH + "disc-material", Material.OBSIDIAN);
        this.particle = particle(plugin.getConfig().getString(PATH + "particle", "REVERSE_PORTAL"));
        this.ambientSound = sound(plugin.getConfig().getString(PATH + "ambient-sound", "BLOCK_PORTAL_AMBIENT"));
        this.emergeSound = sound(plugin.getConfig().getString(PATH + "emerge-sound", "ENTITY_WARDEN_EMERGE"));
        this.glow = color(plugin.getConfig().getString(PATH + "glow-colour", "8A2BE2"));
        this.scaleWithBoss = plugin.getConfig().getBoolean(PATH + "scale-with-boss", true);
    }

    /** The ring has to grow with the boss, or a scaled boss dwarfs its own tear. */
    private double ringRadius() {
        return radius * bossScale;
    }

    /** Likewise the burial depth: a taller boss needs deeper ground to hide in. */
    private double riseHeight() {
        return riseDepth * bossScale;
    }

    // ------------------------------------------------------------------

    @Override
    public void begin() {
        this.origin = boss.getLocation().clone();
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        AttributeInstance scale = boss.getAttribute(Attribute.SCALE);
        this.bossScale = scaleWithBoss && scale != null ? Math.max(0.1, scale.getValue()) : 1.0;

        // The disc is the tear itself: a thin slab, wider than the boss, that
        // turns slowly under the shards.
        disc = spawn(world, discMaterial, discPose(0.0, SEED_SCALE));

        for (int index = 0; index < shardCount; index++) {
            // Every shard starts small at the centre, so the opening is one
            // interpolation away rather than a pop-in.
            BlockDisplay shard = spawn(world, shardMaterial, pose(index * angleStep(), 0.0, 0.0, SEED_SCALE));
            shards.add(shard);
        }

        if (rise && allowRise && riseDepth > 0.0) {
            // Burying the boss is only safe because the sequence holds it
            // invulnerable; SpawnAnimations refuses this otherwise.
            // Gravity has to go with it: a prefab arena floor can have nothing
            // underneath, and a falling boss would drop straight into the void.
            previousGravity = boss.hasGravity();
            suspended = true;
            boss.setGravity(false);
            boss.teleport(atHeight(origin.getY() - riseHeight()));
        }
        play(ambientSound, 1.4F, 0.6F);
    }

    @Override
    public void tick(int ticks, int durationTicks) {
        World world = origin == null ? null : origin.getWorld();
        if (world == null || finished) {
            return;
        }
        int openTicks = Math.max(4, durationTicks / 4);
        int riseStart = Math.max(openTicks + 1, (int) (durationTicks * 0.55));

        if (ticks == 1) {
            // One set of target poses; the client tweens the whole opening.
            open(openTicks);
        }
        if (ticks > openTicks && ticks % ORBIT_STEP_TICKS == 0) {
            orbit(ticks, ticks >= riseStart);
        }
        if (ticks % 10 == 0) {
            play(ambientSound, 1.0F, 0.5F + (float) ticks / Math.max(1, durationTicks) * 0.5F);
        }
        if (ticks % 2 == 0) {
            particles(world, ticks >= riseStart);
        }
        if (ticks >= riseStart && rise && allowRise && riseDepth > 0.0) {
            if (!emerged) {
                emerged = true;
                play(emergeSound, 1.6F, 0.8F);
            }
            // Small steps rather than one teleport: at 20 ticks per second this
            // reads as the boss climbing out of the tear.
            double progress = Math.clamp((double) (ticks - riseStart) / (durationTicks - riseStart), 0.0, 1.0);
            boss.teleport(atHeight(origin.getY() - riseHeight() * (1.0 - progress)));
        }
    }

    @Override
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (boss.isValid() && !boss.isDead()) {
            // Never leave the boss buried or hanging above its arena floor.
            boss.teleport(atHeight(origin == null ? boss.getLocation().getY() : origin.getY()));
        }
        restoreGravity();
        for (int index = 0; index < shards.size(); index++) {
            BlockDisplay shard = shards.get(index);
            if (shard.isValid()) {
                // Pulled back into the tear and scaled away, so nothing pops out.
                collapse(shard, pose(index * angleStep(), 0.0, 0.0, 0.0));
            }
        }
        if (disc != null && disc.isValid()) {
            collapse(disc, discPose(0.0, 0.0));
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                remove();
            }
        }.runTaskLater(plugin, OUTRO_TICKS);
    }

    @Override
    public void abort() {
        finished = true;
        // A boss that was buried for the rise must never be left underground
        // just because the animation was cut short.
        if (suspended && origin != null && boss.isValid() && !boss.isDead()) {
            boss.teleport(atHeight(origin.getY()));
        }
        restoreGravity();
        remove();
    }

    @Override
    public String describe() {
        long alive = shards.stream().filter(BlockDisplay::isValid).count();
        String at = origin == null ? "nowhere" : origin.getBlockX() + "," + origin.getBlockY() + "," + origin.getBlockZ();
        return "rift_tear: " + alive + "/" + shardCount + " shards + " + (disc != null && disc.isValid() ? "1" : "0")
                + " disc at " + at + ", radius " + ringRadius() + ", rise " + (rise && allowRise ? riseHeight() : 0.0) + ", boss scale " + bossScale;
    }

    private void restoreGravity() {
        if (!suspended) {
            return;
        }
        suspended = false;
        if (boss.isValid()) {
            boss.setGravity(previousGravity);
        }
    }

    // ------------------------------------------------------------------

    /** Grows every shard out of the centre onto its ring in one interpolation. */
    private void open(int openTicks) {
        for (int index = 0; index < shards.size(); index++) {
            BlockDisplay shard = shards.get(index);
            if (!shard.isValid()) {
                continue;
            }
            shard.setInterpolationDelay(0);
            shard.setInterpolationDuration(openTicks);
            shard.setTransformation(pose(index * angleStep(), ringRadius(), 0.0, 1.0));
        }
        if (disc != null && disc.isValid()) {
            disc.setInterpolationDelay(0);
            disc.setInterpolationDuration(openTicks);
            disc.setTransformation(discPose(0.0, 1.0));
        }
    }

    /**
     * Steps the ring forward. Each update interpolates over exactly the gap to
     * the next one, so the shards turn continuously instead of stuttering.
     */
    private void orbit(int ticks, boolean tilted) {
        double turn = Math.toRadians(orbitDegreesPerSecond) * ticks / 20.0;
        // Once the boss starts climbing, the shards lean inward over the tear.
        double tilt = tilted ? Math.toRadians(28.0) : 0.0;
        double lift = tilted ? 0.6 : 0.0;
        for (int index = 0; index < shards.size(); index++) {
            BlockDisplay shard = shards.get(index);
            if (!shard.isValid()) {
                continue;
            }
            shard.setInterpolationDelay(0);
            shard.setInterpolationDuration(ORBIT_STEP_TICKS);
            shard.setTransformation(pose(index * angleStep() + turn, ringRadius(), tilt, 1.0, lift));
        }
        if (disc != null && disc.isValid()) {
            disc.setInterpolationDelay(0);
            disc.setInterpolationDuration(ORBIT_STEP_TICKS);
            disc.setTransformation(discPose(-turn * 0.4, 1.0));
        }
    }

    private void collapse(BlockDisplay display, Transformation target) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(OUTRO_TICKS - 2);
        display.setTransformation(target);
    }

    private void remove() {
        for (BlockDisplay shard : shards) {
            if (shard.isValid()) {
                shard.remove();
            }
        }
        shards.clear();
        if (disc != null && disc.isValid()) {
            disc.remove();
        }
        disc = null;
    }

    // ------------------------------------------------------------------
    //  Poses
    // ------------------------------------------------------------------

    private Transformation pose(double angle, double distance, double tilt, double scale) {
        return pose(angle, distance, tilt, scale, 0.0);
    }

    /**
     * Places one shard by transformation only. The entity itself never moves,
     * which is what lets the client interpolate the whole orbit.
     */
    private Transformation pose(double angle, double distance, double tilt, double scale, double lift) {
        float width = (float) (shardWidth * scale * bossScale);
        float height = (float) (shardHeight * scale * bossScale);
        // Turn the flat side outward, then lean it over the tear.
        Quaternionf rotation = new Quaternionf().rotateY((float) -angle).rotateX((float) tilt);
        Vector3f base = pivot(rotation, width, 0.0F);
        return new Transformation(new Vector3f(
                (float) (Math.cos(angle) * distance) - base.x,
                (float) lift - base.y,
                (float) (Math.sin(angle) * distance) - base.z),
                rotation, new Vector3f(width, height, width), new Quaternionf());
    }

    private Transformation discPose(double angle, double scale) {
        float span = (float) (ringRadius() * 2.0 * scale);
        Quaternionf rotation = new Quaternionf().rotateY((float) angle);
        Vector3f centre = pivot(rotation, span, 0.0F);
        return new Transformation(new Vector3f(-centre.x, 0.02F - centre.y, -centre.z),
                rotation, new Vector3f(span, (float) (0.08 * scale), span), new Quaternionf());
    }

    /**
     * A display rotates around its own 0,0,0 corner, and the translation is
     * added afterwards rather than rotated with it. So the offset that centres
     * a block has to be rotated by hand and subtracted, otherwise a tilted
     * shard swings away from its ring and a turning disc slides off its centre
     * instead of spinning in place.
     */
    private static Vector3f pivot(Quaternionf rotation, float width, float height) {
        return rotation.transform(new Vector3f(width / 2.0F, height, width / 2.0F));
    }

    private BlockDisplay spawn(World world, Material material, Transformation transformation) {
        return world.spawn(origin, BlockDisplay.class, display -> {
            display.setBlock(material.createBlockData());
            display.setTransformation(transformation);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setGlowing(true);
            display.setGlowColorOverride(glow);
            display.setViewRange(2.0F);
            // Never written to disk: these belong to one fight, not to a world.
            display.setPersistent(false);
        });
    }

    /**
     * Uses the configured count, never {@code shards.size()}: during
     * {@link #begin()} the list is still filling up, and dividing by its size
     * handed the first shard an infinite step and a NaN transformation, which
     * the client cannot render at all.
     */
    private double angleStep() {
        return Math.PI * 2.0 / Math.max(1, shardCount);
    }

    // ------------------------------------------------------------------

    private void particles(World world, boolean rising) {
        Location centre = origin.clone().add(0.0, 0.1, 0.0);
        world.spawnParticle(particle, centre, rising ? 14 : 6, ringRadius() * 0.8, 0.4, ringRadius() * 0.8, 0.02);
        if (rising) {
            world.spawnParticle(Particle.SCULK_CHARGE_POP, centre, 6, ringRadius() * 0.5, 0.2, ringRadius() * 0.5, 0.01);
        }
    }

    private Location atHeight(double y) {
        Location location = origin == null ? boss.getLocation() : origin.clone();
        location.setY(y);
        // The summoning sequence spins the boss every tick; keep that rotation.
        location.setYaw(boss.getLocation().getYaw());
        location.setPitch(0.0F);
        return location;
    }

    private void play(Sound sound, float volume, float pitch) {
        if (origin != null && origin.getWorld() != null) {
            origin.getWorld().playSound(origin, sound, volume, pitch);
        }
    }

    private Material material(String path, Material fallback) {
        String raw = plugin.getConfig().getString(path, fallback.name());
        Material material = raw == null ? null : Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
        return material == null || !material.isBlock() ? fallback : material;
    }

    private static Particle particle(String raw) {
        try {
            return Particle.valueOf(raw == null ? "" : raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Particle.REVERSE_PORTAL;
        }
    }

    private static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) {
            return Sound.BLOCK_PORTAL_AMBIENT;
        }
        Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(raw.toLowerCase(Locale.ROOT).replace('_', '.')));
        return sound == null ? Sound.BLOCK_PORTAL_AMBIENT : sound;
    }

    private static Color color(String raw) {
        try {
            return Color.fromRGB(Integer.parseInt(raw == null ? "" : raw.replace("#", "").trim(), 16));
        } catch (IllegalArgumentException ignored) {
            return Color.fromRGB(0x8A2BE2);
        }
    }
}
