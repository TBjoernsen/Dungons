package nl.riddernix.dungeonplugin.model

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter
import kr.toxicity.model.api.tracker.EntityHideOption
import kr.toxicity.model.api.tracker.ModelScaler
import org.bukkit.entity.LivingEntity

/**
 * The only class in the plugin that references BetterModel.
 *
 * BetterModel is a `compileOnly` dependency, so loading this class without
 * the plugin installed would throw [NoClassDefFoundError]. [ModelIntegration]
 * therefore instantiates it only after confirming BetterModel is enabled, and
 * never names the type in a field or signature.
 */
internal class BetterModelApplier : ModelApplier {

    override fun engineName(): String = "BetterModel"

    override fun modelNames(): Set<String> = BetterModel.modelKeys()

    override fun apply(entity: LivingEntity, modelName: String, animation: String?, options: ModelApplier.Options): Boolean {
        val tracker = BetterModel.model(modelName)
            .map { renderer -> renderer.getOrCreate(BukkitAdapter.adapt(entity)) }
            .orElse(null) ?: return false
        // The vanilla mob keeps doing the work - AI, pathfinding, hitbox and
        // damage - while the model is what players actually see.
        tracker.hideOption(if (options.hideVanillaEntity) EntityHideOption.DEFAULT else EntityHideOption.FALSE)
        if (options.scaleWithEntity) {
            // Follows Attribute.SCALE, which DungeonMobManager already sets
            // per difficulty, so a scaled boss keeps its model in proportion.
            tracker.scaler(ModelScaler.entity())
        }
        if (!animation.isNullOrBlank()) {
            tracker.animate(animation)
        }
        return true
    }
}
