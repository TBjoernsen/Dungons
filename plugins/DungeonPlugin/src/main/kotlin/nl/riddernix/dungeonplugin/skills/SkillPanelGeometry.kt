package nl.riddernix.dungeonplugin.skills

import org.bukkit.configuration.file.FileConfiguration
import java.util.Locale

/**
 * Every measurement a skill panel needs, for one variant.
 *
 * [Variant.STANDARD] reads the `skill-panel` config section, so the panel
 * that already exists keeps behaving exactly as it did and stays tunable.
 * [Variant.BIG] carries its own fixed numbers and ignores those keys: it is a
 * second design, not a size slider.
 *
 * Colours, blocks, glyphs and the three-state ladder are *not* here - both
 * variants share them from config, so the two panels always look like the
 * same family.
 *
 * The depth fields matter more than they look. Each visual layer has to
 * clear the front face of the one behind it: a connection bar is a solid
 * block that writes depth, so a node glyph level with it is hidden close up
 * and only breaks through at range. The gaps are therefore scaled with the
 * panel rather than left at their small standard values.
 */
data class SkillPanelGeometry(
    val variant: Variant,
    val radiusStep: Double, val rootHeight: Double, val headingHeightExtra: Double,
    val nodeScale: Float, val ringScale: Float, val headingScale: Float, val branchLabelScale: Float,
    val lineThickness: Double, val lineDepth: Double, val overlayThicknessFactor: Double,
    val hitboxWidth: Double, val hitboxHeight: Double, val aimTolerance: Double,
    val detailX: Double, val detailHeight: Double, val detailScale: Float, val detailLineWidth: Int,
    val zBaseLine: Double, val zOverlayLine: Double, val zNode: Double, val zOverlayNode: Double,
    val zRing: Double, val zHitbox: Double,
    val plates: PlateStyle?, val controls: Controls?
) {

    /**
     * The class carousel and the Info and points areas under the tree. Null
     * on a variant means that variant has no controls: the standard panel
     * stays a single-class display with a heading.
     */
    data class Controls(val carouselY: Double, val carouselSpacing: Double, val centreScale: Float,
                        val sideScale: Float, val sideOpacity: Int, val arrowX: Double, val arrowScale: Float,
                        val buttonsY: Double, val confirmX: Double, val infoX: Double, val slideTicks: Int,
                        val infoY: Double, val infoScale: Float, val infoLineWidth: Int)

    /**
     * Raised node plates: a wide, thin base standing behind a smaller,
     * thicker top, so a node reads as a physical button with a rim around it.
     *
     * Both plates face the viewer, so what you see of each is its front face.
     * Two plates of the same block would therefore render as one flat square
     * with no visible rim - the rim only reads when the base uses a different
     * block or brightness from the top. That is a property of the upright
     * orientation, not a styling choice.
     *
     * `null` on a variant means that variant draws its nodes as font glyphs
     * instead.
     */
    data class PlateStyle(val baseWidth: Double, val baseDepth: Double, val topWidth: Double, val topDepth: Double,
                          val selectionWidth: Double, val selectionDepth: Double,
                          val zBase: Double, val zTop: Double, val zSelection: Double) {

        /**
         * Half the distance from a plate's centre to its edge along a
         * direction, used to stop connection lines at the rim instead of
         * running underneath it. A square's edge is further away diagonally
         * than straight on, which is what the divisor accounts for.
         */
        fun edgeInset(unitX: Double, unitY: Double): Double =
            baseWidth / 2.0 / maxOf(Math.abs(unitX), Math.abs(unitY))
    }

    /** Which physical size a placed panel was built at. */
    enum class Variant {
        STANDARD,
        BIG;

        fun configName(): String = name.lowercase(Locale.ROOT)

        companion object {
            fun parse(raw: String?): Variant {
                if (raw == null) {
                    return STANDARD
                }
                return when (raw.trim().lowercase(Locale.ROOT)) {
                    "big", "large" -> BIG
                    else -> STANDARD
                }
            }
        }
    }

    companion object {
        fun of(variant: Variant, config: FileConfiguration): SkillPanelGeometry =
            if (variant == Variant.BIG) big() else standard(config)

        /** The original panel: every size still comes from config. */
        private fun standard(config: FileConfiguration): SkillPanelGeometry = SkillPanelGeometry(
            Variant.STANDARD,
            maxOf(0.2, config.getDouble("skill-panel.radius-step", 0.75)),
            config.getDouble("skill-panel.root-height", 0.9),
            config.getDouble("skill-panel.heading.height-extra", 0.9),
            config.getDouble("skill-panel.nodes.scale", 1.6).toFloat(),
            config.getDouble("skill-panel.nodes.ring-scale", 2.2).toFloat(),
            config.getDouble("skill-panel.heading.scale", 1.5).toFloat(),
            config.getDouble("skill-panel.branch-labels.scale", 0.9).toFloat(),
            config.getDouble("skill-panel.lines.thickness", 0.06),
            config.getDouble("skill-panel.lines.depth", 0.02),
            1.2,
            config.getDouble("skill-panel.hitboxes.node-width", 0.5),
            config.getDouble("skill-panel.hitboxes.node-height", 0.5),
            maxOf(0.1, config.getDouble("skill-panel.hitboxes.aim-tolerance", 0.8)),
            config.getDouble("skill-panel.detail.x", 3.9),
            config.getDouble("skill-panel.detail.height", 2.4),
            config.getDouble("skill-panel.detail.scale", 0.55).toFloat(),
            maxOf(60, config.getInt("skill-panel.detail.line-width", 170)),
            config.getDouble("skill-panel.lines.z", 0.015), 0.032, 0.05, 0.065, 0.058, 0.06,
            null,    // the standard variant keeps its font-glyph nodes
            null)    // ... and has no carousel or buttons

        /**
         * The big variant, for a wall you stand back from. Roughly two and a
         * half times the standard in every visual dimension, with the ring
         * spacing a little wider still so the enlarged glyphs do not crowd
         * each other.
         *
         * Footprint: about 19.3 blocks wide and 11.7 tall for the shipped
         * warrior tree, before the detail panel, which adds roughly 5 more to
         * the right.
         */
        private fun big(): SkillPanelGeometry = SkillPanelGeometry(
            Variant.BIG,
            2.0,    // radius step: 2.67x, so enlarged nodes keep their air
            3.6,    // root height: the tree sits high so the bottom row
                    // (info area and points, which do not move) gets air
            2.0,    // heading clearance above the outermost ring
            4.0f,   // node glyphs
            5.5f,   // selection ring
            3.75f,  // heading
            2.25f,  // branch labels
            0.16,   // line thickness, in step with the node size
            0.05,   // line depth: thicker bars need a deeper body
            1.2,    // overlay lines stay 20% thicker than the base
            1.3, 1.3, 2.0,          // hitboxes and aim tolerance, all 2.6x
            11.5, 6.5, 1.4f, 170,   // detail panel clear of the wider tree
            // Layer depths. Lines sit furthest back, then the selection rim,
            // then the node plates; each clears the front face of whatever is
            // behind it rather than z-fighting it. zNode and zOverlayNode are
            // unused in plate mode but still describe where a node's face
            // sits, for labels and aim resolution.
            0.06, 0.12, 0.30, 0.30, 0.185, 0.46,
            PlateStyle(
                0.90, 0.06,     // base plate: the rim
                0.62, 0.12,     // top plate: the face, thicker
                1.14, 0.06,     // selection rim, larger and behind
                0.25, 0.35, 0.185),
            // Below the tree, mockup order: carousel, then the Info area left
            // and the point balance right. The carousel reuses the difficulty
            // panel's recipe - centre large, neighbours faded, slide by
            // teleport interpolation. Info is a standing display, not a
            // button: the selected node's details, or the class when nothing
            // is selected.
            Controls(2.2, 3.2, 2.2f, 1.3f, 120, 6.2, 2.4f,
                0.6, 3.4, -3.4, 4,
                0.75, 0.95f, 200))
    }
}
