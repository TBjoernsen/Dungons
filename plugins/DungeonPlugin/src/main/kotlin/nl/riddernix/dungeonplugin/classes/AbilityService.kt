package nl.riddernix.dungeonplugin.classes

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.FluidCollisionMode
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
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.entity.Skeleton
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.util.EnumSet
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.cos

/**
 * Blocks with a real menu or vanilla use worth preserving under a Heal cast.
 * Deliberately narrower than the deprecated [Material.isInteractable], which
 * also flags plain decoration (stairs, slabs, walls, fences) common
 * throughout this plugin's dungeon architecture - that false-positive rate
 * was silently swallowing casts near completely ordinary terrain.
 */
private val BLOCKS_WITH_REAL_INTERACTION = setOf(
    Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.ENDER_CHEST,
    Material.CRAFTING_TABLE, Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
    Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL, Material.ENCHANTING_TABLE,
    Material.BREWING_STAND, Material.GRINDSTONE, Material.SMITHING_TABLE, Material.STONECUTTER,
    Material.LOOM, Material.CARTOGRAPHY_TABLE, Material.BEACON, Material.LEVER,
    Material.LECTERN, Material.JUKEBOX, Material.NOTE_BLOCK, Material.COMPOSTER,
    Material.CAULDRON, Material.RESPAWN_ANCHOR, Material.DRAGON_EGG, Material.COMPARATOR,
    Material.REPEATER, Material.BELL
)

private fun hasRealInteraction(type: Material): Boolean =
    type in BLOCKS_WITH_REAL_INTERACTION ||
        type.name.endsWith("_DOOR") || type.name.endsWith("_TRAPDOOR") || type.name.endsWith("_FENCE_GATE") ||
        type.name.endsWith("_BUTTON") || type.name.endsWith("_BED") || type.name.endsWith("SHULKER_BOX")

/**
 * Vanilla-client ability keybind. Minecraft's Swap Hands key defaults to F
 * and can be rebound by each player in Controls; Paper exposes it through
 * PlayerSwapHandItemsEvent.
 */
class AbilityService(private val plugin: DungeonPlugin) : Listener {

    private val cooldownUntil = HashMap<UUID, Long>()
    private val mageHealCooldownUntil = HashMap<UUID, Long>()
    private val blessingCooldownUntil = HashMap<UUID, Long>()
    private val meteorCooldownUntil = HashMap<UUID, Long>()
    private val deadeyeCooldownUntil = HashMap<UUID, Long>()
    private val tempestCooldownUntil = HashMap<UUID, Long>()
    private val riseCooldownUntil = HashMap<UUID, Long>()
    private val shieldExpiry = HashMap<UUID, Long>()

    /** Necromancer only: caster -> the UUIDs of their currently-alive Rise minions. A fresh Rise refuses to cast while this is non-empty. */
    private val activeMinions = HashMap<UUID, MutableList<UUID>>()

    /** Per Archer: the wall-clock ms until which a Wind Jump still counts for a Skyfall shot. */
    private val windJumpUntil = HashMap<UUID, Long>()

    /**
     * Per max-rank Archer: how many bonus forward Wind Dashes are currently
     * banked - capped by [maxWindDashCharges]. Absent means full (players
     * start with every charge available, not zero). Recharges automatically
     * over real time via [tickWindDashRecharge] - see [windDashChargeReadyAt].
     */
    private val windDashCharges = HashMap<UUID, Int>()

    /** Per Archer below a full bank: the wall-clock ms at which their next Wind Dash charge finishes recharging. */
    private val windDashChargeReadyAt = HashMap<UUID, Long>()
    private val hoveredHealTargets = HashMap<UUID, HoveredHealTarget>()
    private val originalGlowStates = HashMap<UUID, Boolean>()
    private val shieldCapacityKey = NamespacedKey(plugin, "paladin_active_shield_capacity")
    private val healHighlightTeamName = "dp_heal_hover"
    /** Whatever a Deadeye-highlighted mob's glow state was before the aim touched it, restored once the aim closes (fired or cancelled). */
    private val originalMobGlowStates = HashMap<UUID, Boolean>()

    /**
     * Sharpshooter only: shooter -> their currently-open Deadeye aim.
     * Presence as a key means "aiming right now" - [DeadeyeAim.targetId] is
     * itself nullable (no mob was under the crosshair when the aim opened),
     * which is why this isn't just a plain `HashMap<UUID, UUID?>`: removing
     * a null value and removing an absent key both return null, and this
     * class needs to tell those apart.
     */
    private val deadeyeAiming = HashMap<UUID, DeadeyeAim>()

    private class DeadeyeAim(val targetId: UUID?)

    /** Refreshes the Mage's heal-target highlight. Runs several times a second so the glow tracks the crosshair. */
    fun tickHealHover() {
        plugin.server.onlinePlayers.forEach { caster ->
            val canTarget = plugin.queries.isInDungeon(caster) &&
                plugin.classes.activeClass(caster.uniqueId) == ClassType.MAGE &&
                plugin.classItems.isStaff(caster.inventory.itemInMainHand)
            updateHoveredHealTarget(caster, if (canTarget) raycastHealTarget(caster) else null)
        }
    }

    fun shutdown() {
        plugin.server.onlinePlayers.toList().forEach { updateHoveredHealTarget(it, null) }
        hoveredHealTargets.clear()
        originalGlowStates.clear()
        mageHealCooldownUntil.clear()
        blessingCooldownUntil.clear()
        meteorCooldownUntil.clear()
        deadeyeCooldownUntil.clear()
        tempestCooldownUntil.clear()
    }

    /**
     * True while an Archer is still inside the Wind Jump window and off the
     * ground - the condition for a full-Focus bow shot to become a Skyfall
     * AoE arrow.
     */
    @Suppress("DEPRECATION")
    fun isWindJumping(player: Player): Boolean =
        (windJumpUntil[player.uniqueId] ?: 0L) > System.currentTimeMillis() && !player.isOnGround

    /** Stormcaller only: a landed Skyfall buys extra time in the Wind Jump window - never shortens it. */
    fun extendWindJumpWindow(player: Player, seconds: Double) {
        if (seconds <= 0.0) return
        val extra = (seconds * 1000).toLong()
        val current = windJumpUntil[player.uniqueId] ?: 0L
        windJumpUntil[player.uniqueId] = maxOf(current, System.currentTimeMillis()) + extra
    }

    /**
     * Stormcaller only: a Skyfall KILL (not just a landed hit) tops the Wind
     * Dash bank back up by one, capped at the live mastery cap - a multi-kill
     * burst can refill several at once, but never past the cap. Called once
     * per kill from [PassiveService.detonateSkyfall], so a crowded room can
     * fire this several times in the same tick.
     */
    fun grantWindDashCharge(player: Player) {
        val cap = maxWindDashCharges(player)
        val current = currentWindDashCharges(player)
        if (current >= cap) return
        val gained = current + 1
        windDashCharges[player.uniqueId] = gained
        player.sendActionBar(Component.text(
            if (gained >= cap) "§e§lSkyfall kill - Wind Dash charge ready! ($gained/$cap - full)"
            else "§eSkyfall kill - Wind Dash charge ready! ($gained/$cap)",
            NamedTextColor.YELLOW))
        player.world.playSound(player.location, Sound.ENTITY_WIND_CHARGE_THROW, 0.8f, 1.4f)
    }

    fun remove(player: Player) {
        cooldownUntil.remove(player.uniqueId)
        mageHealCooldownUntil.remove(player.uniqueId)
        blessingCooldownUntil.remove(player.uniqueId)
        meteorCooldownUntil.remove(player.uniqueId)
        deadeyeCooldownUntil.remove(player.uniqueId)
        tempestCooldownUntil.remove(player.uniqueId)
        riseCooldownUntil.remove(player.uniqueId)
        windJumpUntil.remove(player.uniqueId)
        windDashCharges.remove(player.uniqueId)
        windDashChargeReadyAt.remove(player.uniqueId)
        updateHoveredHealTarget(player, null)
        cancelDeadeyeAim(player.uniqueId, null)
        dismissMinions(player.uniqueId)
    }

    /**
     * No ignoreCancelled here, deliberately: Bukkit/Paper delivers a plain
     * RIGHT_CLICK_AIR as already cancelled by default whenever the held item
     * has no vanilla "use" action (a Blaze/Breeze Rod does nothing in
     * vanilla) - that is not another plugin or another listener, it is how
     * the event is constructed for "nothing was targeted, nothing to do" the
     * moment it exists, before any listener runs. ignoreCancelled=true was
     * silently discarding every one of those casts. A real block click never
     * has this problem (it arrives uncancelled), which is why this only ever
     * broke aiming at open space.
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onMageHealAirClick(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || !event.action.isRightClick) return
        // Shift is the caster deliberately overriding "interact with the
        // block" (the same vanilla convention that lets a sneaking player
        // place a block against a chest instead of opening it) - the mastery
        // ability always fires, regardless of what is underfoot or in reach.
        // Only intercepted when there's actually a Shift+Right-click ability
        // to trigger, though: a Bow (unlike the Mage's staff) has real
        // vanilla right-click behaviour - drawing and, on release, firing a
        // live arrow - and Sharpshooter no longer has anything bound here
        // (Deadeye moved to Left-Click). Cancelling this unconditionally for
        // every sneaking Archer blocked that vanilla draw outright, so a
        // sneaking Right-click could never become a Skyfall shot.
        if (event.player.isSneaking && hasShiftRightClickAbility(event.player)) {
            event.isCancelled = true
            castMasteryAbility(event.player)
            return
        }
        // Right-clicking a normal dungeon wall, floor, stair or slab should
        // still cast the heal; only blocks with a real menu/use retain their
        // normal interaction.
        val clicked = event.clickedBlock?.type
        if (event.action == Action.RIGHT_CLICK_BLOCK && clicked != null && hasRealInteraction(clicked)) return
        castMageHeal(event.player)
    }

    /**
     * A right-click resolves to THIS event, not [onMageHealAirClick], the
     * instant any entity - a mob included - is within vanilla's short
     * interact reach along the crosshair. Heal and the mastery ability both
     * pick their own target independently (a player-only cone search, or a
     * dedicated raycast) and never read [PlayerInteractEntityEvent.getRightClicked],
     * so gating on "clicked a Player" was silently eating every cast made
     * anywhere near a mob - a zombie horde in melee range being the worst of it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMageHealPlayerClick(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.player.isSneaking && hasShiftRightClickAbility(event.player)) {
            event.isCancelled = true
            castMasteryAbility(event.player)
        } else castMageHeal(event.player)
    }

    /**
     * Whether Shift+Right-click currently triggers a real ability for this
     * player - if not, the click must NOT be cancelled/intercepted, or a
     * real weapon's own vanilla behaviour (an Archer's bow draw, chiefly)
     * gets silently blocked for nothing. Neither Archer mastery lives here
     * any more - Deadeye and Tempest are both a double-Left-Click now -
     * only the Mage's still does.
     */
    private fun hasShiftRightClickAbility(player: Player): Boolean =
        plugin.classes.activeClass(player.uniqueId) == ClassType.MAGE

    /** A Rise minion's death: no vanilla loot/exp (it was never really a mob), and its slot frees up immediately rather than waiting for the batch's duration timer. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onMinionDeath(event: EntityDeathEvent) {
        if (!plugin.queries.isAllyMinion(event.entity)) return
        event.drops.clear()
        event.droppedExp = 0
        for (batch in activeMinions.values) batch.remove(event.entity.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        if (!plugin.queries.isInDungeon(player)) return
        val classType = plugin.classes.activeClass(player.uniqueId) ?: return
        if (!plugin.classItems.isAllowedWeapon(classType, player.inventory.itemInMainHand)) return
        event.isCancelled = true

        // A max-Focus Archer's banked charges: a forward Wind Dash spendable
        // ahead of (and ignoring) the normal cooldown. Priority always goes
        // to the updraft first - a charge is only spendable inside an
        // active Wind Jump window (isWindJumping), so merely falling or
        // jumping off a ledge never lets you dash; you have to Wind Jump
        // first. Players start with a full bank; mastery raises the cap
        // (see maxWindDashCharges), and spent charges recharge automatically
        // over time (see tickWindDashRecharge) - no manual "banking" needed.
        if (classType == ClassType.ARCHER && isWindJumping(player)) {
            val charges = currentWindDashCharges(player)
            if (charges > 0) {
                val remaining = charges - 1
                if (archerDoubleJump(player, forward = true, chargesRemaining = remaining)) {
                    windDashCharges[player.uniqueId] = remaining
                    windDashChargeReadyAt.putIfAbsent(player.uniqueId, System.currentTimeMillis() + windDashRechargeMillis(player))
                    cooldownUntil[player.uniqueId] = System.currentTimeMillis() + cooldownMillis(classType)
                }
                return
            }
        }

        val remaining = (cooldownUntil[player.uniqueId] ?: 0L) - System.currentTimeMillis()
        if (remaining > 0) {
            player.sendActionBar(Component.text("Ability ready in ${ceil(remaining / 1000.0).toInt()}s.", NamedTextColor.GRAY))
            return
        }
        val activated = when (classType) {
            ClassType.WARRIOR -> warriorDash(player)
            ClassType.ARCHER -> archerDoubleJump(player, forward = false)
            ClassType.PALADIN -> paladinShield(player)
            ClassType.MAGE -> mageBlink(player)
        }
        if (activated) cooldownUntil[player.uniqueId] = System.currentTimeMillis() + cooldownMillis(classType)
    }

    private fun warriorDash(player: Player): Boolean {
        val cfg = plugin.classesConfig
        // Berserk (a spent Rage bar) empowers the lunge: further, harder, with
        // a real knock-up. The Dash also feeds Rage on a connect, so the loop
        // is sword -> dash to top off -> Berserk -> empowered dash.
        val berserk = plugin.classPassives.isBerserk(player)
        val direction = horizontalDirection(player)

        val speed = cfg.getDouble("abilities.warrior.dash-speed", 1.5) *
            if (berserk) cfg.getDouble("abilities.warrior.berserk-speed-multiplier", 1.35) else 1.0
        player.velocity = direction.clone().multiply(speed).setY(if (berserk) 0.22 else 0.16)

        val damage = cfg.getDouble("abilities.warrior.bonus-damage", 4.0) *
            if (berserk) cfg.getDouble("abilities.warrior.berserk-damage-multiplier", 1.8) else 1.0
        val radius = cfg.getDouble("abilities.warrior.dash-radius", 2.6)

        val hits = player.getNearbyEntities(radius, 1.6, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it != player && it !is Player && !plugin.queries.isAllyMinion(it) &&
                (plugin.queries.isDungeonMob(it) || it is Monster) }
        hits.forEach { enemy ->
            enemy.damage(damage, player)
            val push = direction.clone().multiply(if (berserk) 0.55 else 0.35)
            enemy.velocity = enemy.velocity.add(push).setY(if (berserk) 0.42 else 0.16)
        }

        plugin.classFeedback.warriorDashCast(player, berserk)
        if (hits.isNotEmpty()) {
            plugin.classFeedback.warriorDashImpact(player, berserk)
            plugin.classPassives.feedRage(player, cfg.getDouble("warrior.dash-rage-on-hit", 25.0))
        }
        player.sendActionBar(Component.text(if (berserk) "Berserk Dash!" else "Dash!", NamedTextColor.RED))
        return true
    }

    /**
     * The Archer F-key. [forward] `false` is the vertical Wind Jump; `true` is
     * the forward Wind Dash - same air-only mechanics and cues, horizontal
     * launch instead of lift. At max Focus rank, [forward] Dashes are also
     * spendable from a banked charge pool (see [maxWindDashCharges]) ahead of
     * the normal cooldown.
     */
    private fun archerDoubleJump(player: Player, forward: Boolean, chargesRemaining: Int? = null): Boolean {
        @Suppress("DEPRECATION")
        if (player.isOnGround) {
            player.sendActionBar(Component.text(
                if (forward) "Wind Dash needs you airborne." else "Double Jump can only be used in the air.",
                NamedTextColor.GRAY))
            return false
        }
        val cfg = plugin.classesConfig
        val power = cfg.getDouble("abilities.archer.jump-velocity", 0.9)
        player.world.spawnParticle(Particle.CLOUD, player.location.clone().add(0.0, 0.12, 0.0), 20, 0.28, 0.05, 0.28, 0.08)
        if (forward) {
            val push = cfg.getDouble("abilities.archer.wind-dash-forward-multiplier", 1.7)
            player.velocity = horizontalDirection(player).multiply(power * push).setY(0.3)
            plugin.classes.addMasteryProgress(player, MasteryObjective.WIND_DASH_USES, 1)
            // Stormcaller only: the dash itself shoves nearby enemies aside -
            // mobility that also buys room, not just repositioning.
            if (plugin.classes.subclass(player.uniqueId) == "stormcaller") {
                windDashGust(player)
            }
        } else {
            player.velocity = player.velocity.clone().setY(power)
        }
        player.world.playSound(player.location, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, if (forward) 0.85f else 1.1f)

        val windowSeconds = cfg.getDouble("abilities.archer.wind-jump-window-seconds", 4.0).coerceAtLeast(0.0)
        windJumpUntil[player.uniqueId] = System.currentTimeMillis() + (windowSeconds * 1000).toLong()

        // Always show where the charge bank stands (once the feature is
        // unlocked at all) - not just at the moment one is gained or spent -
        // so the player never has to wonder.
        val cap = maxWindDashCharges(player)
        val chargeNote = if (cap > 1) {
            val current = if (forward) chargesRemaining ?: 0 else currentWindDashCharges(player)
            " §e(dash charges: $current/$cap)"
        } else ""
        val focused = plugin.classPassives.focusFull(player)
        player.sendActionBar(Component.text(
            (if (forward) "Wind Dash!" else "Wind Jump!") +
                (if (focused) " §b§lSkyfall armed" else "") + chargeNote,
            NamedTextColor.GREEN))
        return true
    }

    /**
     * How many Wind Dash charges can be banked at once. Locked entirely (0)
     * below abilities.archer.wind-jump-double-charge-min-rank Focus rank.
     * Once unlocked, both Archer mastery ladders raise the cap as they're
     * claimed - Stormcaller grows fastest since mobility is its focus (up to
     * +2 at full mastery), Sharpshooter gets a smaller capstone perk (up to
     * +1). No subclass, or subclass with no mastery yet, caps at the base 1.
     */
    private fun maxWindDashCharges(player: Player): Int {
        val cfg = plugin.classesConfig
        if (plugin.classes.signatureRank(player.uniqueId) <
            cfg.getInt("abilities.archer.wind-jump-double-charge-min-rank", 4)) return 0
        return when (plugin.classes.subclass(player.uniqueId)) {
            "stormcaller" -> {
                val masteryLevel = plugin.classes.masteryLevelFor(player.uniqueId, "stormcaller")
                val levelsPerCharge = cfg.getInt("abilities.archer.wind-dash-charge-per-mastery-levels", 4).coerceAtLeast(1)
                1 + masteryLevel / levelsPerCharge
            }
            "precision" -> {
                val masteryLevel = plugin.classes.masteryLevelFor(player.uniqueId, "precision")
                val levelsPerCharge = cfg.getInt("abilities.archer.wind-dash-charge-per-mastery-levels-precision", 10).coerceAtLeast(1)
                1 + masteryLevel / levelsPerCharge
            }
            else -> 1
        }
    }

    /** Current banked Wind Dash charges, clamped to the live cap - absent/over-cap (e.g. a rank/mastery loss) both read as full. */
    private fun currentWindDashCharges(player: Player): Int {
        val cap = maxWindDashCharges(player)
        return (windDashCharges[player.uniqueId] ?: cap).coerceIn(0, cap)
    }

    private fun windDashRechargeMillis(player: Player): Long {
        val cfg = plugin.classesConfig
        return (cfg.getDouble("abilities.archer.wind-dash-charge-recharge-seconds", 20.0)
            .coerceAtLeast(1.0) * 1000).toLong()
    }

    /**
     * Regenerates banked Wind Dash charges over real time, independent of
     * being airborne or pressing anything - the whole point is that a spent
     * charge quietly comes back on its own. Called once a second from the
     * main plugin loop.
     */
    fun tickWindDashRecharge() {
        if (windDashChargeReadyAt.isEmpty()) return
        val now = System.currentTimeMillis()
        val iterator = windDashChargeReadyAt.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now < entry.value) continue
            val player = plugin.server.getPlayer(entry.key)
            if (player == null || plugin.classes.activeClass(entry.key) != ClassType.ARCHER) {
                iterator.remove()
                continue
            }
            val cap = maxWindDashCharges(player)
            val current = currentWindDashCharges(player)
            if (current >= cap) {
                iterator.remove()
                continue
            }
            val gained = current + 1
            windDashCharges[player.uniqueId] = gained
            player.sendActionBar(Component.text(
                if (gained >= cap) "§e§lWind Dash charge ready! ($gained/$cap - full)"
                else "§eWind Dash charge ready! ($gained/$cap)",
                NamedTextColor.YELLOW))
            player.world.playSound(player.location, Sound.ENTITY_WIND_CHARGE_THROW, 0.8f, 1.4f)
            if (gained < cap) entry.setValue(now + windDashRechargeMillis(player)) else iterator.remove()
        }
    }

    /** Stormcaller's Wind Dash gust: a shove, not damage - the point is room to breathe/reposition, not a weapon. */
    private fun windDashGust(player: Player) {
        val cfg = plugin.classesConfig
        val radius = cfg.getDouble("abilities.archer.wind-dash-knockback-radius", 3.0).coerceAtLeast(0.5)
        val knockback = cfg.getDouble("abilities.archer.wind-dash-knockback", 0.5).coerceAtLeast(0.0)
        val knockUp = cfg.getDouble("abilities.archer.wind-dash-knockup", 0.2).coerceAtLeast(0.0)
        val origin = player.location
        player.getNearbyEntities(radius, radius, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it != player && it !is Player && !plugin.queries.isAllyMinion(it) &&
                (plugin.queries.isDungeonMob(it) || it is Monster) }
            .forEach { mob ->
                val away = mob.location.toVector().subtract(origin.toVector())
                if (away.lengthSquared() > 1e-6) away.normalize() else away.zero()
                mob.velocity = mob.velocity.add(away.multiply(knockback)).setY(knockUp)
            }
        plugin.classFeedback.windDashGust(origin, radius)
    }

    private fun paladinShield(player: Player): Boolean {
        val cfg = plugin.classesConfig
        val rank = plugin.classes.signatureRank(player.uniqueId).coerceAtLeast(1)
        val target = (player.getTargetEntity(12) as? Player)?.takeIf { it.world == player.world } ?: player
        val seconds = cfg.getDouble("abilities.paladin.shield-seconds", 4.0)
        val hearts = cfg.getDouble("abilities.paladin.shield-hearts", 5.0) +
            cfg.getDouble("abilities.paladin.shield-hearts-per-rank", 1.0) * (rank - 1)
        val shieldHealth = hearts * 2.0
        val absorbAmp = cfg.getInt("abilities.paladin.absorption-amplifier", 1).coerceIn(0, 4)
        val capacity = target.getAttribute(Attribute.MAX_ABSORPTION)
        capacity?.removeModifier(shieldCapacityKey)
        capacity?.addTransientModifier(AttributeModifier(shieldCapacityKey, shieldHealth, AttributeModifier.Operation.ADD_NUMBER))
        // The native effect makes the client render yellow hearts
        // consistently; the amount is immediately limited to the shield size.
        target.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, (seconds * 20).toInt(), absorbAmp, true, false, true))
        target.absorptionAmount = maxOf(target.absorptionAmount, shieldHealth)

        // Bless (high rank): scrub the ally's Slowness / Weakness and grant a
        // brief Resistance on top of the shield.
        var blessed = false
        if (rank >= cfg.getInt("abilities.paladin.shield-bless-min-rank", 3)) {
            target.removePotionEffect(PotionEffectType.SLOWNESS)
            target.removePotionEffect(PotionEffectType.WEAKNESS)
            val blessSeconds = cfg.getDouble("abilities.paladin.shield-bless-seconds", 3.0).coerceAtLeast(0.0)
            val blessTicks = (blessSeconds * 20).toInt()
            if (blessTicks > 0) {
                target.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, blessTicks, 0, true, false, true))
            }
            blessed = true
            if (target != player) {
                target.sendActionBar(Component.text(
                    "§6✦ Blessed §7- Slowness/Weakness cleansed, Resistance ${blessSeconds.toInt()}s", NamedTextColor.GOLD))
            }
        }

        val expiresAt = System.currentTimeMillis() + (seconds * 1000).toLong()
        shieldExpiry[target.uniqueId] = expiresAt
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (target.isOnline && shieldExpiry[target.uniqueId] == expiresAt) {
                target.absorptionAmount = shieldHealth
            }
        })
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!target.isOnline || shieldExpiry[target.uniqueId] != expiresAt) return@Runnable
            target.getAttribute(Attribute.MAX_ABSORPTION)?.removeModifier(shieldCapacityKey)
            target.removePotionEffect(PotionEffectType.ABSORPTION)
            if (target.absorptionAmount <= shieldHealth) target.absorptionAmount = 0.0
            shieldExpiry.remove(target.uniqueId)
        }, (seconds * 20).toLong())
        plugin.classFeedback.paladinShieldCast(target)
        val recipient = if (target == player) "yourself" else target.name
        player.sendActionBar(Component.text(
            (if (blessed) "Blessed & shielded " else "Shielded ") + recipient + ".", NamedTextColor.GOLD))
        return true
    }

    private fun mageBlink(player: Player): Boolean {
        val cfg = plugin.classesConfig
        val data = plugin.classes.data(player.uniqueId)
        val rank = plugin.classes.signatureRank(player.uniqueId).coerceAtLeast(1)
        val cost = cfg.getDouble("abilities.mage.blink-mana-cost", 35.0)
        if (data.mana < cost) {
            player.sendActionBar(Component.text("Not enough Mana (${cost.toInt()} required).", NamedTextColor.RED))
            return false
        }
        val distance = cfg.getDouble("abilities.mage.blink-distance", 10.0) +
            cfg.getDouble("abilities.mage.blink-distance-per-rank", 1.5) * (rank - 1)
        val vertical = cfg.getBoolean("abilities.mage.blink-vertical", true)
        val origin = player.location.clone()
        val destination = safeBlinkDestination(player, distance, vertical)
        if (destination == null || destination.distanceSquared(origin) < 0.75) {
            // A blink blocked from the start costs nothing.
            player.sendActionBar(Component.text("Blink fizzled - no room.", NamedTextColor.RED))
            return false
        }

        data.mana -= cost
        val momentum = player.velocity.clone()
        player.teleport(destination)
        // Teleports normally clear velocity. Reapply it next tick so Blink
        // repositions without killing a sprint, jump, or fall trajectory.
        plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) player.velocity = momentum })
        val iframeTicks = (cfg.getDouble("abilities.mage.blink-invuln-seconds", 0.4).coerceAtLeast(0.0) * 20).toInt()
        if (iframeTicks > 0) player.noDamageTicks = maxOf(player.noDamageTicks, iframeTicks)

        // Departure blast (rank-gated): the space you left detonates.
        if (rank >= cfg.getInt("abilities.mage.blink-blast-min-rank", 2)) {
            val r = cfg.getDouble("abilities.mage.blink-blast-radius", 3.5).coerceIn(1.0, 10.0)
            val dmg = cfg.getDouble("abilities.mage.blink-blast-damage", 4.0) +
                cfg.getDouble("abilities.mage.blink-blast-damage-per-rank", 1.5) * (rank - 1)
            var hits = 0
            origin.world?.getNearbyEntities(origin, r, r, r)?.forEach { entity ->
                val mob = entity as? LivingEntity ?: return@forEach
                if (mob is Player || !plugin.queries.isDungeonMob(mob)) return@forEach
                if (dmg > 0.0) mob.damage(dmg, player)
                val push = mob.location.toVector().subtract(origin.toVector())
                if (push.lengthSquared() > 1e-6) mob.velocity = mob.velocity.add(push.normalize().multiply(0.4))
                hits++
            }
            if (hits > 0) {
                plugin.classPassives.addArcaneChargeFromBlink(player, cfg.getDouble("abilities.mage.blink-blast-charge", 2.0))
            }
            plugin.classFeedback.mageBlinkBlast(origin, r, plugin.classes.subclass(player.uniqueId))
        }

        plugin.classFeedback.mageBlink(origin, player.location, plugin.classes.subclass(player.uniqueId))
        player.sendActionBar(Component.text("Blink! (-${cost.toInt()} Mana)", NamedTextColor.LIGHT_PURPLE))
        return true
    }

    private fun castMageHeal(caster: Player) {
        if (!plugin.queries.isInDungeon(caster)) return
        if (plugin.classes.activeClass(caster.uniqueId) != ClassType.MAGE) return
        if (!plugin.classItems.isStaff(caster.inventory.itemInMainHand)) return

        val now = System.currentTimeMillis()
        val remaining = (mageHealCooldownUntil[caster.uniqueId] ?: 0L) - now
        if (remaining > 0) {
            caster.sendActionBar(Component.text("Healing spell ready in ${ceil(remaining / 1000.0).toInt()}s.", NamedTextColor.GRAY))
            return
        }

        val cfg = plugin.classesConfig
        val data = plugin.classes.data(caster.uniqueId)
        val cost = cfg.getDouble("abilities.mage.heal-mana-cost", 50.0).coerceAtLeast(0.0)
        if (data.mana < cost) {
            caster.sendActionBar(Component.text("Not enough Mana (${cost.toInt()} required).", NamedTextColor.RED))
            return
        }

        val target = currentHealTarget(caster) ?: caster
        data.mana -= cost
        mageHealCooldownUntil[caster.uniqueId] = now + mageHealCooldownMillis()
        // Enchanter's mastery quest ladder raises both directly, not just Blessing.
        val masteryLevel = plugin.classes.masteryLevelFor(caster.uniqueId, "support")
        val duration = (cfg.getDouble("abilities.mage.heal-duration-ticks", 100.0) +
            cfg.getDouble("abilities.mage.heal-duration-per-mastery-level", 10.0) * masteryLevel).toInt()
        val amplifierLevels = cfg.getInt("abilities.mage.heal-amplifier-per-mastery-levels", 5).coerceAtLeast(1)
        val amplifier = cfg.getInt("abilities.mage.heal-amplifier", 1) + masteryLevel / amplifierLevels
        target.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, duration, amplifier, true, true, true))
        val effectLocation = target.location.clone().add(0.0, 1.0, 0.0)
        target.world.spawnParticle(Particle.HEART, effectLocation, 10, 0.35, 0.45, 0.35, 0.02)
        target.world.spawnParticle(Particle.HAPPY_VILLAGER, effectLocation, 16, 0.38, 0.5, 0.38, 0.05)
        target.world.playSound(effectLocation, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.25f)

        val tier = romanNumeral(amplifier + 1)
        if (target == caster) {
            caster.sendMessage(Component.text("You healed yourself with Regeneration $tier. (-${cost.toInt()} Mana)", NamedTextColor.LIGHT_PURPLE))
        } else {
            caster.sendMessage(Component.text("You healed ${target.name} with Regeneration $tier. (-${cost.toInt()} Mana)", NamedTextColor.LIGHT_PURPLE))
            target.sendMessage(Component.text("${caster.name} healed you with Regeneration $tier.", NamedTextColor.GREEN))
        }
        plugin.classes.addMasteryProgress(caster, MasteryObjective.HEAL_AMOUNT,
            cfg.getInt("abilities.mage.heal-mastery-points-per-cast", 40))
        plugin.refreshClassPlayer(caster)
    }

    private fun romanNumeral(value: Int): String = when (value.coerceIn(1, 8)) {
        1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; 5 -> "V"; 6 -> "VI"; 7 -> "VII"; else -> "VIII"
    }

    /**
     * Shift + Right-click with the class weapon: the Mage's mastery-specific
     * ability, gated on having chosen one. The only mastery still bound
     * here - both Archer masteries are a double-Left-Click now (Deadeye,
     * Tempest), so this is never even called for an Archer any more (see
     * hasShiftRightClickAbility): a sneaking Right-click needs to reach a
     * REAL vanilla bow draw uninterrupted, or Skyfall could never trigger.
     */
    private fun castMasteryAbility(caster: Player) {
        if (!plugin.queries.isInDungeon(caster)) return
        if (plugin.classes.activeClass(caster.uniqueId) != ClassType.MAGE) return
        if (!plugin.classItems.isStaff(caster.inventory.itemInMainHand)) return
        when (plugin.classes.subclass(caster.uniqueId)) {
            "support" -> castBlessing(caster)
            "attack" -> castMeteor(caster)
            "necromancer" -> castRise(caster)
            else -> noMasteryYet(caster)
        }
    }

    private fun noMasteryYet(caster: Player) =
        caster.sendActionBar(Component.text("Requires a mastery - visit your skill tree at Level 100.", NamedTextColor.GRAY))

    private val blessingPool = listOf(
        PotionEffectType.STRENGTH, PotionEffectType.SPEED, PotionEffectType.RESISTANCE,
        PotionEffectType.REGENERATION, PotionEffectType.ABSORPTION
    )

    /** Enchanter's Blessing: random positive effect(s) on whoever Heal would target (ally under the crosshair, else self). */
    private fun castBlessing(caster: Player) {
        val cfg = plugin.classesConfig
        val now = System.currentTimeMillis()
        val remaining = (blessingCooldownUntil[caster.uniqueId] ?: 0L) - now
        if (remaining > 0) {
            caster.sendActionBar(Component.text("Blessing ready in ${ceil(remaining / 1000.0).toInt()}s.", NamedTextColor.GRAY))
            return
        }
        val data = plugin.classes.data(caster.uniqueId)
        val cost = cfg.getDouble("abilities.mage.blessing-mana-cost", 40.0).coerceAtLeast(0.0)
        if (data.mana < cost) {
            caster.sendActionBar(Component.text("Not enough Mana (${cost.toInt()} required).", NamedTextColor.RED))
            return
        }
        val target = currentHealTarget(caster) ?: caster
        val masteryLevel = plugin.classes.masteryLevelFor(caster.uniqueId, "support")
        val duration = ((cfg.getDouble("abilities.mage.blessing-duration-seconds", 20.0).coerceAtLeast(0.0) +
            cfg.getDouble("abilities.mage.blessing-duration-per-mastery-level", 1.5) * masteryLevel) * 20).toInt()
        val amplifierLevels = cfg.getInt("abilities.mage.blessing-amplifier-per-mastery-levels", 3).coerceAtLeast(1)
        val amplifier = (cfg.getInt("abilities.mage.blessing-amplifier", 0) + masteryLevel / amplifierLevels).coerceAtLeast(0)
        val countLevels = cfg.getInt("abilities.mage.blessing-effect-count-per-mastery-levels", 4).coerceAtLeast(1)
        val count = (cfg.getInt("abilities.mage.blessing-effect-count", 1) + masteryLevel / countLevels).coerceIn(1, blessingPool.size)
        data.mana -= cost
        val cooldownMillis = (cfg.getDouble("abilities.mage.blessing-cooldown-seconds", 12.0).coerceAtLeast(0.0) * 1000).toLong()
        blessingCooldownUntil[caster.uniqueId] = now + cooldownMillis
        val chosen = blessingPool.shuffled().take(count)
        chosen.forEach { target.addPotionEffect(PotionEffect(it, duration, amplifier, true, true, true)) }
        plugin.classFeedback.mageBlessing(target)
        val names = chosen.joinToString(", ") { it.name.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercase) }
        if (target == caster) {
            caster.sendMessage(Component.text("You blessed yourself with $names. (-${cost.toInt()} Mana)", NamedTextColor.LIGHT_PURPLE))
        } else {
            caster.sendMessage(Component.text("You blessed ${target.name} with $names. (-${cost.toInt()} Mana)", NamedTextColor.LIGHT_PURPLE))
            target.sendMessage(Component.text("${caster.name} blessed you with $names.", NamedTextColor.GREEN))
        }
        plugin.refreshClassPlayer(caster)
    }

    /** Battlemage's Meteor: aim at a spot, a telegraph ring shows it, then it falls and explodes - mobs full damage, players a fraction. */
    private fun castMeteor(caster: Player) {
        val cfg = plugin.classesConfig
        val now = System.currentTimeMillis()
        val remaining = (meteorCooldownUntil[caster.uniqueId] ?: 0L) - now
        if (remaining > 0) {
            caster.sendActionBar(Component.text("Meteor ready in ${ceil(remaining / 1000.0).toInt()}s.", NamedTextColor.GRAY))
            return
        }
        val data = plugin.classes.data(caster.uniqueId)
        val cost = cfg.getDouble("abilities.mage.meteor-mana-cost", 80.0).coerceAtLeast(0.0)
        if (data.mana < cost) {
            caster.sendActionBar(Component.text("Not enough Mana (${cost.toInt()} required).", NamedTextColor.RED))
            return
        }
        val range = cfg.getDouble("abilities.mage.meteor-max-range", 256.0).coerceAtLeast(1.0)
        val eye = caster.eyeLocation
        val hit = caster.world.rayTraceBlocks(eye, eye.direction, range, FluidCollisionMode.NEVER, true)
        val impact = hit?.hitPosition?.toLocation(caster.world) ?: run {
            caster.sendActionBar(Component.text("No clear ground in range.", NamedTextColor.GRAY))
            return
        }
        data.mana -= cost
        val cooldownMillis = (cfg.getDouble("abilities.mage.meteor-cooldown-seconds", 14.0).coerceAtLeast(0.0) * 1000).toLong()
        meteorCooldownUntil[caster.uniqueId] = now + cooldownMillis
        MeteorSequence.launch(plugin, caster, impact)
        caster.sendActionBar(Component.text("Meteor! (-${cost.toInt()} Mana)", NamedTextColor.GOLD))
        plugin.refreshClassPlayer(caster)
    }

    /**
     * Necromancer's Rise: raises a small batch of skeletal allies that fight
     * for the caster until they fall or the batch's duration runs out.
     * Refuses to cast at all while the caster's last batch is still alive -
     * see the class doc on [riseCooldownUntil]'s neighbour, [activeMinions],
     * for why the cooldown itself is deliberately left fixed instead of
     * scaling with mastery.
     */
    private fun castRise(caster: Player) {
        val cfg = plugin.classesConfig
        val now = System.currentTimeMillis()
        val remaining = (riseCooldownUntil[caster.uniqueId] ?: 0L) - now
        if (remaining > 0) {
            caster.sendActionBar(Component.text("Rise ready in ${ceil(remaining / 1000.0).toInt()}s.", NamedTextColor.GRAY))
            return
        }
        if (!activeMinions[caster.uniqueId].isNullOrEmpty()) {
            caster.sendActionBar(Component.text("Your risen minions still fight - wait for them to fall.", NamedTextColor.GRAY))
            return
        }
        val data = plugin.classes.data(caster.uniqueId)
        val cost = cfg.getDouble("abilities.mage.rise-mana-cost", 60.0).coerceAtLeast(0.0)
        if (data.mana < cost) {
            caster.sendActionBar(Component.text("Not enough Mana (${cost.toInt()} required).", NamedTextColor.RED))
            return
        }
        data.mana -= cost
        val cooldownMillis = (cfg.getDouble("abilities.mage.rise-cooldown-seconds", 45.0).coerceAtLeast(0.0) * 1000).toLong()
        riseCooldownUntil[caster.uniqueId] = now + cooldownMillis

        val masteryLevel = plugin.classes.masteryLevelFor(caster.uniqueId, "necromancer")
        val levelsPerMinion = cfg.getInt("abilities.mage.rise-minion-count-per-mastery-levels", 3).coerceAtLeast(1)
        val count = (cfg.getInt("abilities.mage.rise-minion-count", 2) + masteryLevel / levelsPerMinion).coerceAtLeast(1)
        val durationTicks = ((cfg.getDouble("abilities.mage.rise-minion-duration-seconds", 15.0) +
            cfg.getDouble("abilities.mage.rise-minion-duration-per-mastery-level", 1.5) * masteryLevel) * 20).toLong().coerceAtLeast(20L)
        val damage = cfg.getDouble("abilities.mage.rise-minion-damage", 3.0) +
            cfg.getDouble("abilities.mage.rise-minion-damage-per-mastery-level", 0.4) * masteryLevel
        val health = cfg.getDouble("abilities.mage.rise-minion-health", 20.0) +
            cfg.getDouble("abilities.mage.rise-minion-health-per-mastery-level", 2.0) * masteryLevel
        val speed = cfg.getDouble("abilities.mage.rise-minion-speed", 0.25) +
            cfg.getDouble("abilities.mage.rise-minion-speed-per-mastery-level", 0.01) * masteryLevel
        val reach = cfg.getDouble("abilities.mage.rise-minion-reach", 2.5).coerceAtLeast(1.0)
        val searchRadius = cfg.getDouble("abilities.mage.rise-minion-search-radius", 16.0).coerceAtLeast(1.0)
        val spawnRadius = cfg.getDouble("abilities.mage.rise-spawn-radius", 2.0).coerceAtLeast(0.0)

        val casterId = caster.uniqueId
        val batch = activeMinions.getOrPut(casterId) { ArrayList() }
        repeat(count) {
            val angle = Math.random() * 2 * Math.PI
            val offset = Vector(kotlin.math.cos(angle) * spawnRadius, 0.0, kotlin.math.sin(angle) * spawnRadius)
            val at = caster.location.clone().add(offset)
            val minion = caster.world.spawn(at, Skeleton::class.java) { skeleton ->
                skeleton.persistentDataContainer.set(plugin.allyMinionKey, PersistentDataType.BYTE, 1)
                skeleton.customName(Component.text("Risen Skeleton", NamedTextColor.GRAY))
                skeleton.isCustomNameVisible = true
                skeleton.canPickupItems = false
                skeleton.removeWhenFarAway = true
                skeleton.equipment?.setItemInMainHand(ItemStack(Material.STONE_SWORD))
                skeleton.equipment?.itemInMainHandDropChance = 0f
                skeleton.addPotionEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, Int.MAX_VALUE, 0, true, false, false))
                setAttributeIfPresent(skeleton, Attribute.MAX_HEALTH, health)
                skeleton.health = health
                setAttributeIfPresent(skeleton, Attribute.MOVEMENT_SPEED, speed)
            }
            Bukkit.getMobGoals().removeAllGoals(minion)
            Bukkit.getMobGoals().addGoal(minion, 1, AllyMeleeGoal(minion, plugin, casterId, damage, reach, searchRadius,
                NamespacedKey(plugin, "necromancer_minion_attack"), Skeleton::class.java))
            batch.add(minion.uniqueId)
            plugin.classFeedback.necromancerRiseSpawn(minion)

            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (minion.isValid && !minion.isDead) {
                    plugin.classFeedback.necromancerMinionExpire(minion.location)
                    minion.remove()
                }
                activeMinions[casterId]?.remove(minion.uniqueId)
            }, durationTicks)
        }
        plugin.classes.addMasteryProgress(caster, MasteryObjective.MINIONS_SUMMONED, count)
        caster.sendActionBar(Component.text("§8§lRISE §7(-${cost.toInt()} Mana)", NamedTextColor.GRAY))
        plugin.refreshClassPlayer(caster)
    }

    /** Force-removes every minion this player currently has up - class switch, hard reset, or disconnect. */
    private fun dismissMinions(playerId: UUID) {
        val batch = activeMinions.remove(playerId) ?: return
        for (id in batch) {
            (Bukkit.getEntity(id) as? Skeleton)?.remove()
        }
    }

    private fun setAttributeIfPresent(entity: LivingEntity, attribute: Attribute, value: Double) {
        entity.getAttribute(attribute)?.baseValue = value
    }

    /**
     * A Necromancer minion's targeting: the nearest hostile dungeon mob
     * within [range], not the nearest player - the mirror image of the
     * boss-facing HostileMeleeGoal in DungeonMobManager. isAllyMinion is
     * checked so minions never attack each other.
     */
    private class AllyMeleeGoal<T : Mob>(
        private val mob: T,
        private val plugin: DungeonPlugin,
        private val ownerId: UUID,
        private val damage: Double,
        reach: Double,
        private val range: Double,
        key: NamespacedKey,
        type: Class<T>
    ) : Goal<T> {
        private val key: GoalKey<T> = GoalKey.of(type, key)
        private var cooldown = 0
        private val reachSquared = reach * reach

        override fun shouldActivate(): Boolean = nearest() != null
        override fun shouldStayActive(): Boolean = nearest() != null

        override fun tick() {
            val target = nearest() ?: return
            mob.target = target
            mob.isAggressive = true
            mob.lookAt(target)
            val distance = mob.location.distanceSquared(target.location)
            if (distance > reachSquared) mob.pathfinder.moveTo(target, 1.15)
            else if (cooldown-- <= 0) {
                target.damage(damage, mob)
                if (target.isDead) {
                    Bukkit.getPlayer(ownerId)?.let { plugin.classes.addMasteryProgress(it, MasteryObjective.MINION_KILLS, 1) }
                }
                cooldown = 20
            }
        }

        private fun nearest(): LivingEntity? =
            mob.world.getNearbyEntities(mob.location, range, range, range)
                .filterIsInstance<LivingEntity>()
                .filter { it !is Player && !plugin.queries.isAllyMinion(it) &&
                    (plugin.queries.isDungeonMob(it) || it is Monster) }
                .minByOrNull { it.location.distanceSquared(mob.location) }

        override fun getKey(): GoalKey<T> = key
        override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.LOOK, GoalType.TARGET)
    }

    /** Whether Deadeye is off cooldown - CoreListener checks this (with [isWindJumping]) before routing a Left-Click to [startDeadeyeAim] instead of Focus Shot. */
    fun isDeadeyeReady(playerId: UUID): Boolean = (deadeyeCooldownUntil[playerId] ?: 0L) <= System.currentTimeMillis()

    /** Whether the caster currently has an open Deadeye aim - CoreListener checks this to route their NEXT Left-Click to [fireDeadeyeAimedShot] instead of Focus Shot. */
    fun isDeadeyeAiming(playerId: UUID): Boolean = deadeyeAiming.containsKey(playerId)

    /**
     * Sharpshooter's Deadeye, opening half: the FIRST of two Left-Clicks.
     * Only reachable off the ground, right after a Wind Jump ([isWindJumping])
     * - that is what keeps it from ever clashing with Focus Shot, which stays
     * fully usable everywhere else (see CoreListener.castRangedAttack). The
     * cooldown commits here, not on the second click. Slowness (heavier than
     * Scope's own effect) and a crossbow wind-up start immediately; whatever
     * mob is under the crosshair right now (same cone-and-range approach as
     * the Mage's heal target) glows. The aim resolves on the caster's NEXT
     * Left-Click ([fireDeadeyeAimedShot]) or is cancelled the instant they
     * land ([tickDeadeyeAimGroundCheck]), whichever comes first -
     * deadeye-aim-seconds is only a safety cap for if neither happens.
     */
    fun startDeadeyeAim(caster: Player) {
        val cfg = plugin.classesConfig
        val cooldownMillis = (cfg.getDouble("abilities.archer.deadeye-cooldown-seconds", 12.0).coerceAtLeast(0.0) * 1000).toLong()
        deadeyeCooldownUntil[caster.uniqueId] = System.currentTimeMillis() + cooldownMillis

        val maxAimTicks = (cfg.getDouble("abilities.archer.deadeye-aim-seconds", 3.0).coerceIn(0.5, 10.0) * 20).toLong().coerceAtLeast(1L)
        val slownessAmplifier = cfg.getInt("abilities.archer.deadeye-aim-slowness-amplifier", 3).coerceIn(0, 10)
        caster.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, maxAimTicks.toInt() + 5, slownessAmplifier, true, false, true))
        plugin.classFeedback.deadeyeAimStart(caster)
        caster.sendActionBar(Component.text("§6§lDEADEYE §7- aiming, Left-Click again to fire..."))

        val targetId = raycastDeadeyeTarget(caster)?.let { target ->
            originalMobGlowStates.putIfAbsent(target.uniqueId, target.isGlowing)
            target.isGlowing = true
            target.uniqueId
        }
        val casterId = caster.uniqueId
        deadeyeAiming[casterId] = DeadeyeAim(targetId)

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (isDeadeyeAiming(casterId)) {
                cancelDeadeyeAim(casterId, "Deadeye aim timed out.")
            }
        }, maxAimTicks)
    }

    /** Sharpshooter's Deadeye, closing half: the SECOND Left-Click while an aim is open - fires the one arrow at whatever's still locked, then closes the aim. */
    fun fireDeadeyeAimedShot(caster: Player) {
        val aim = deadeyeAiming.remove(caster.uniqueId) ?: return
        aim.targetId?.let { releaseAimGlow(it) }
        caster.removePotionEffect(PotionEffectType.SLOWNESS)
        @Suppress("DEPRECATION")
        if (caster.isOnGround) return // landed between the two clicks - already effectively cancelled
        val target = aim.targetId
            ?.let { Bukkit.getEntity(it) as? LivingEntity }
            ?.takeIf { !it.isDead && it.isValid }
        fireDeadeyeShot(caster, target)
    }

    /** Closes an open Deadeye aim WITHOUT firing - the caster landed, or the safety cap in [startDeadeyeAim] ran out. */
    private fun cancelDeadeyeAim(playerId: UUID, reason: String?) {
        val aim = deadeyeAiming.remove(playerId) ?: return
        aim.targetId?.let { releaseAimGlow(it) }
        val player = plugin.server.getPlayer(playerId)
        player?.removePotionEffect(PotionEffectType.SLOWNESS)
        if (reason != null) player?.sendActionBar(Component.text("§7$reason", NamedTextColor.GRAY))
    }

    /** Runs a few times a second: cancels any open Deadeye aim the instant its caster is back on the ground. */
    fun tickDeadeyeAimGroundCheck() {
        if (deadeyeAiming.isEmpty()) return
        for (playerId in deadeyeAiming.keys.toList()) {
            val player = plugin.server.getPlayer(playerId)
            if (player == null || !player.isOnline) {
                cancelDeadeyeAim(playerId, null)
                continue
            }
            @Suppress("DEPRECATION")
            if (player.isOnGround) {
                cancelDeadeyeAim(playerId, "Deadeye cancelled - you landed.")
            }
        }
    }

    /**
     * The mob under the caster's crosshair right now, within range and line
     * of sight. A real ray-vs-hitbox trace (World.rayTraceEntities), not an
     * angle check against a single eye point - the old cone approach got
     * proportionally HARDER to land on a big, scaled-up mob (a Key Guardian
     * spawns at 1.45-1.65x scale) since its eye point sits further from its
     * visual centre, exactly backwards from what a soft-lock should feel
     * like. deadeye-aim-forgiveness pads every hitbox by a flat margin on
     * top of its real size, same idea as a controller's aim assist.
     */
    private fun raycastDeadeyeTarget(caster: Player): LivingEntity? {
        val cfg = plugin.classesConfig
        val range = cfg.getDouble("abilities.archer.deadeye-aim-range", 40.0).coerceAtLeast(1.0)
        val forgiveness = cfg.getDouble("abilities.archer.deadeye-aim-forgiveness", 0.6).coerceIn(0.0, 3.0)
        val eye = caster.eyeLocation
        val hit = caster.world.rayTraceEntities(eye, eye.direction, range, forgiveness) { entity ->
            entity is LivingEntity && entity !== caster && entity !is Player && !entity.isDead &&
                !plugin.queries.isAllyMinion(entity) && (plugin.queries.isDungeonMob(entity) || entity is Monster)
        } ?: return null
        val mob = hit.hitEntity as? LivingEntity ?: return null
        val distance = eye.distance(mob.eyeLocation)
        if (caster.world.rayTraceBlocks(eye, eye.direction, distance, FluidCollisionMode.NEVER, true) != null) return null
        return mob
    }

    private fun releaseAimGlow(targetId: UUID) {
        val wasGlowing = originalMobGlowStates.remove(targetId) ?: return
        (Bukkit.getEntity(targetId) as? LivingEntity)?.isGlowing = wasGlowing
    }

    /** The one arrow Deadeye ever fires - launched straight at the locked target's current position so it can't miss, or straight ahead with nothing locked. */
    private fun fireDeadeyeShot(caster: Player, target: LivingEntity?) {
        val cfg = plugin.classesConfig
        val direction = if (target != null)
            target.eyeLocation.toVector().subtract(caster.eyeLocation.toVector()).normalize()
        else caster.eyeLocation.direction.normalize()
        val arrow = caster.launchProjectile(Arrow::class.java)
        arrow.velocity = direction.multiply(cfg.getDouble("abilities.archer.deadeye-speed", 3.6))
        arrow.isCritical = true
        arrow.isGlowing = true
        plugin.classItems.markDeadeyeShot(arrow)
        plugin.classFeedback.deadeyeFired(caster, arrow)
        caster.sendActionBar(Component.text("§6§lDEADEYE!"))
    }

    /** Whether Tempest is off cooldown - CoreListener checks this before routing a double-Left-Click to Tempest instead of Focus Shot. */
    fun isTempestReady(playerId: UUID): Boolean = (tempestCooldownUntil[playerId] ?: 0L) <= System.currentTimeMillis()

    /**
     * Stormcaller's Tempest: a ground-usable fan of arrows - unlike Skyfall,
     * no airborne or spent-Focus requirement. Fires on the SECOND of two
     * Left-Clicks (see CoreListener.castRangedAttack), taking priority over
     * Focus Shot on that second click whenever it's off cooldown; it never
     * reads or spends the Focus bar. Unlike Deadeye it has no aim/channel to
     * open first - the double-click itself is what's deliberate enough, and
     * a lone Left-Click, or the first of a pair, always just tries Focus
     * Shot like before.
     */
    fun castTempestVolley(caster: Player) {
        val cfg = plugin.classesConfig
        val now = System.currentTimeMillis()
        val remaining = (tempestCooldownUntil[caster.uniqueId] ?: 0L) - now
        if (remaining > 0) {
            caster.sendActionBar(Component.text("Tempest ready in ${ceil(remaining / 1000.0).toInt()}s.", NamedTextColor.GRAY))
            return
        }
        val cooldownMillis = (cfg.getDouble("abilities.archer.tempest-cooldown-seconds", 10.0).coerceAtLeast(0.0) * 1000).toLong()
        tempestCooldownUntil[caster.uniqueId] = now + cooldownMillis

        val masteryLevel = plugin.classes.masteryLevelFor(caster.uniqueId, "stormcaller")
        val levelsPerArrow = cfg.getInt("abilities.archer.tempest-arrow-count-per-mastery-levels", 5).coerceAtLeast(1)
        val count = (cfg.getInt("abilities.archer.tempest-arrow-count", 5) + masteryLevel / levelsPerArrow).coerceAtLeast(1)
        val spreadDegrees = cfg.getDouble("abilities.archer.tempest-spread-degrees", 30.0).coerceAtLeast(0.0)
        val speed = cfg.getDouble("archer.focus-shot-speed", 3.4)

        val baseDirection = caster.eyeLocation.direction.normalize()
        // A reference axis to fan around - fall back to world X when aiming
        // near-vertical, where crossing with world-up would degenerate to zero.
        val worldUp = Vector(0.0, 1.0, 0.0)
        val right = (if (kotlin.math.abs(baseDirection.dot(worldUp)) > 0.999)
            baseDirection.clone().crossProduct(Vector(1.0, 0.0, 0.0))
        else baseDirection.clone().crossProduct(worldUp)).normalize()

        plugin.classFeedback.tempestCast(caster)
        for (i in 0 until count) {
            val t = if (count == 1) 0.0 else (i.toDouble() / (count - 1)) - 0.5
            val angle = Math.toRadians(t * spreadDegrees)
            val direction = baseDirection.clone().add(right.clone().multiply(kotlin.math.sin(angle))).normalize()
            val arrow = caster.launchProjectile(Arrow::class.java)
            arrow.velocity = direction.multiply(speed)
            plugin.classItems.markTempestArrow(arrow)
            plugin.classFeedback.tempestFired(arrow)
        }
        caster.sendActionBar(Component.text("§b§lTEMPEST"))
    }

    private fun safeBlinkDestination(player: Player, maxDistance: Double, vertical: Boolean): Location? {
        val start = player.location
        val direction = (if (vertical) start.direction else horizontalDirection(player)).clone().normalize()
        var result: Location? = null
        // Check every part of the route, rather than only checking the final
        // spot. Otherwise a valid space on the far side of a wall would let
        // Blink skip it.
        var distance = 0.25
        while (distance <= maxDistance) {
            val candidate = start.clone().add(direction.clone().multiply(distance))
            val feetClear = candidate.block.isPassable
            val headClear = candidate.clone().add(0.0, 1.0, 0.0).block.isPassable
            if (!feetClear || !headClear) break
            result = candidate
            distance += 0.25
        }
        return result
    }

    /**
     * Who a Heal cast actually lands on: commit the ally currently under the
     * highlight ([tickHealHover] validated cone + line of sight for it a few
     * ticks ago), as long as they are still online, alive and in the same
     * world - no distance check against the caster here, deliberately: once
     * a target is locked, casting heals them no matter how far apart you and
     * they now are. Only if there is no live highlight does it fall back to
     * a fresh cone check (still range-limited - that is target *selection*,
     * not this commit step). Returns null -> [castMageHeal] heals the caster.
     */
    private fun currentHealTarget(caster: Player): Player? {
        val hovered = hoveredHealTargets[caster.uniqueId]?.let { plugin.server.getPlayer(it.playerId) }
        if (hovered != null && hovered.isOnline && !hovered.isDead && hovered.world == caster.world) {
            return hovered
        }
        return raycastHealTarget(caster)
    }

    /**
     * The ally the caster is aiming at: the player nearest their crosshair
     * within `heal-range` and inside a `heal-aim-cone-degrees` cone, so it
     * does not need a pixel-perfect ray. Line of sight is still required
     * unless `heal-require-line-of-sight` is off - no healing through walls.
     */
    private fun raycastHealTarget(caster: Player): Player? {
        val range = plugin.classesConfig.getDouble("abilities.mage.heal-range", 50.0).coerceAtLeast(0.0)
        if (range <= 0.0) return null
        val minCos = cos(Math.toRadians(
            plugin.classesConfig.getDouble("abilities.mage.heal-aim-cone-degrees", 12.0).coerceIn(1.0, 60.0)))
        val requireLos = plugin.classesConfig.getBoolean("abilities.mage.heal-require-line-of-sight", true)
        val eye = caster.eyeLocation
        val look = eye.direction
        var best: Player? = null
        var bestAlignment = minCos
        for (other in caster.world.players) {
            if (other === caster || !other.isOnline || other.isDead) continue
            val toTarget = other.eyeLocation.toVector().subtract(eye.toVector())
            val distance = toTarget.length()
            if (distance < 0.1 || distance > range) continue
            val alignment = toTarget.clone().normalize().dot(look)
            if (alignment < bestAlignment) continue
            if (requireLos && caster.world.rayTraceBlocks(
                    eye, toTarget, distance, FluidCollisionMode.NEVER, true) != null) continue
            bestAlignment = alignment
            best = other
        }
        return best
    }

    private fun updateHoveredHealTarget(caster: Player, target: Player?) {
        val casterId = caster.uniqueId
        val previous = hoveredHealTargets[casterId]
        if (previous?.playerId == target?.uniqueId) {
            target?.let {
                it.isGlowing = true
                addHealHighlight(caster, it)
            }
            return
        }

        previous?.let { removeHealHighlight(caster, it.entryName) }
        hoveredHealTargets.remove(casterId)
        previous?.let { releaseGlow(it.playerId) }
        if (target == null) {
            return
        }

        hoveredHealTargets[casterId] = HoveredHealTarget(target.uniqueId, target.name)
        originalGlowStates.putIfAbsent(target.uniqueId, target.isGlowing)
        target.isGlowing = true
        addHealHighlight(caster, target)
    }

    private fun addHealHighlight(caster: Player, target: Player) {
        val team = caster.scoreboard.getTeam(healHighlightTeamName)
            ?: caster.scoreboard.registerNewTeam(healHighlightTeamName).also {
                @Suppress("DEPRECATION")
                it.color = ChatColor.GREEN
            }
        team.addEntry(target.name)
    }

    private fun removeHealHighlight(caster: Player, entryName: String) {
        caster.scoreboard.getTeam(healHighlightTeamName)?.removeEntry(entryName)
    }

    private fun releaseGlow(targetId: UUID) {
        if (hoveredHealTargets.values.any { it.playerId == targetId }) return
        val wasGlowing = originalGlowStates.remove(targetId) ?: return
        plugin.server.getPlayer(targetId)?.isGlowing = wasGlowing
    }

    private fun cooldownMillis(type: ClassType): Long =
        (plugin.classesConfig.getDouble("abilities.${type.id}.cooldown-seconds", 5.0) * 1000).toLong()

    private fun mageHealCooldownMillis(): Long =
        (plugin.classesConfig.getDouble("abilities.mage.heal-cooldown-seconds", 30.0).coerceAtLeast(0.0) * 1000.0).toLong()

    private fun horizontalDirection(player: Player): Vector =
        player.location.direction.clone().setY(0.0).normalize()

    private data class HoveredHealTarget(val playerId: UUID, val entryName: String)
}
