package nl.riddernix.dungeonforge.model;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.EntityHideOption;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.ModelScaler;
import org.bukkit.entity.LivingEntity;

import java.util.Set;

/**
 * The only class in DungeonForge that references BetterModel.
 *
 * <p>BetterModel is a {@code compileOnly} dependency, so loading this class
 * without the plugin installed would throw {@link NoClassDefFoundError}.
 * {@link ModelIntegration} therefore instantiates it only after confirming
 * BetterModel is enabled, and never names the type in a field or signature.</p>
 */
final class BetterModelApplier implements ModelApplier {

    @Override
    public String engineName() {
        return "BetterModel";
    }

    @Override
    public Set<String> modelNames() {
        return BetterModel.modelKeys();
    }

    @Override
    public boolean apply(LivingEntity entity, String modelName, String animation, Options options) {
        EntityTracker tracker = BetterModel.model(modelName)
                .map(renderer -> renderer.getOrCreate(BukkitAdapter.adapt(entity)))
                .orElse(null);
        if (tracker == null) {
            return false;
        }
        // The vanilla mob keeps doing the work - AI, pathfinding, hitbox and
        // damage - while the model is what players actually see.
        tracker.hideOption(options.hideVanillaEntity() ? EntityHideOption.DEFAULT : EntityHideOption.FALSE);
        if (options.scaleWithEntity()) {
            // Follows Attribute.SCALE, which DungeonMobManager already sets per
            // difficulty, so a scaled boss keeps its model in proportion.
            tracker.scaler(ModelScaler.entity());
        }
        if (animation != null && !animation.isBlank()) {
            tracker.animate(animation);
        }
        return true;
    }
}
