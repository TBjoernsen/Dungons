package nl.riddernix.dungeonforge.model;

import org.bukkit.entity.LivingEntity;

import java.util.Set;

/**
 * Attaches a custom model to a live dungeon mob.
 *
 * <p>The interface exists so {@link ModelIntegration} never mentions a model
 * engine's classes. Only the implementation does, and that implementation is
 * loaded exclusively when its plugin is present.</p>
 */
public interface ModelApplier {

    /** Names the engine behind this applier for console reporting. */
    String engineName();

    /** Every model name the engine currently has loaded. */
    Set<String> modelNames();

    /**
     * Attaches {@code modelName} to {@code entity}.
     *
     * @param animation optional animation to start immediately, blank for none
     * @return true when the model existed and was attached
     */
    boolean apply(LivingEntity entity, String modelName, String animation, Options options);

    /** Per-server display choices, read once from config.yml. */
    record Options(boolean hideVanillaEntity, boolean scaleWithEntity) {
    }
}
