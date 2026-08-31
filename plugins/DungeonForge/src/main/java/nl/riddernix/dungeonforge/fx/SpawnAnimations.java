package nl.riddernix.dungeonforge.fx;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/** Looks up the scripted entrance a boss's {@code summoning.animation} asks for. */
public final class SpawnAnimations {

    private static final List<String> NAMES = List.of("rift_tear");

    private SpawnAnimations() {
    }

    public static List<String> names() {
        return NAMES;
    }

    /**
     * @param allowRise whether the boss may be buried and raised, which is only
     *                  safe while the summoning sequence keeps it invulnerable
     * @return the animation, or {@code null} for "none" and for unknown names
     */
    /**
     * Runs one step and reports whether the animation is still healthy.
     *
     * <p>An exception thrown inside a repeating task cancels that task. For an
     * animation that means the shards freeze mid-pose and the boss stays
     * wherever it was put, with nothing on screen explaining why. Catching it
     * here turns that silence into a console entry and a clean removal.</p>
     *
     * @return false when the animation failed and was removed
     */
    public static boolean tickSafely(DungeonForgePlugin plugin, SpawnAnimation animation, int ticks, int durationTicks) {
        try {
            animation.tick(ticks, durationTicks);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Boss entrance animation failed on tick " + ticks
                    + " and was removed (" + describe(animation) + ").", exception);
            abortQuietly(plugin, animation);
            return false;
        }
    }

    /** @return false when the animation could not start and was removed */
    public static boolean beginSafely(DungeonForgePlugin plugin, SpawnAnimation animation) {
        try {
            animation.begin();
            plugin.getLogger().info("Boss entrance started - " + describe(animation));
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Boss entrance animation failed to start and was removed.", exception);
            abortQuietly(plugin, animation);
            return false;
        }
    }

    public static String describe(SpawnAnimation animation) {
        try {
            return animation.describe();
        } catch (RuntimeException exception) {
            return "description unavailable: " + exception;
        }
    }

    private static void abortQuietly(DungeonForgePlugin plugin, SpawnAnimation animation) {
        try {
            animation.abort();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Boss entrance animation could not be cleaned up.", exception);
        }
    }

    public static SpawnAnimation create(DungeonForgePlugin plugin, LivingEntity boss, String name, boolean allowRise) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (key) {
            case "rift_tear" -> new RiftTearAnimation(plugin, boss, allowRise);
            case "none" -> null;
            default -> {
                plugin.getLogger().warning("Unknown boss summoning animation '" + name + "'. Known animations: "
                        + String.join(", ", NAMES) + ". The default particle pulse is used instead.");
                yield null;
            }
        };
    }
}
