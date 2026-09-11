package nl.riddernix.dungeonplugin.classes

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.util.Locale
import java.util.UUID

/** Sidebar, tab prefix, and the audiovisual confirmations of the class layer. */
class FeedbackService(private val plugin: DungeonPlugin) {

    private val boards = HashMap<UUID, Scoreboard>()

    /** The Paladin's Zeal / Taunt boss bar, shown only while their Taunt is active. */
    private val zealBars = HashMap<UUID, BossBar>()

    fun refresh(player: Player) {
        updateTabName(player)
        val board = boards.getOrPut(player.uniqueId) { Bukkit.getScoreboardManager().newScoreboard }
        @Suppress("DEPRECATION")
        val objective = board.getObjective("dungeonplugin")
            ?: board.registerNewObjective("dungeonplugin", "dummy", "§6§lClass Skills")
        objective.displaySlot = DisplaySlot.SIDEBAR
        board.entries.forEach(board::resetScores)

        val data = plugin.classes.data(player.uniqueId)
        val className = plugin.classes.activeClass(player.uniqueId)?.displayName ?: "Unchosen"
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val attack = player.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 1.0
        val armor = player.getAttribute(Attribute.ARMOR)?.value ?: 0.0
        val points = plugin.skillProgress.points(player.uniqueId)
        val lines = mutableListOf(
            "§fClass: §e$className",
            "§fLevel: §e${data.level}/100",
            "§fPoints: §e$points",
            "§fDifficulty: §e${data.unlockedDifficulty}/9",
            " ",
            "§cHealth: §f${number(maxHealth / 2.0)} ♥",
            "§cAttack: §f${number(attack)}",
            "§9Armor: §f${number(armor)}",
            "  ",
            "§d${plugin.classPassives.readout(player)}"
        )
        lines.add(2, "§fXP: §e${data.experience}/${plugin.classPassives.experienceToNextLevel(player)}")
        val xpBonus = plugin.questXpMultiplier(player.uniqueId)
        if (xpBonus > 1.0) {
            val separator = lines.indexOf(" ")
            if (separator > 0) {
                lines.add(separator, "§bXP Bonus: §e×${String.format(Locale.US, "%.2f", xpBonus)}")
            }
        }
        val passiveLines = plugin.classPassives.readoutLines(player)
        if (passiveLines.size > 1) {
            // Replace the normal passive entry with the compact two-line
            // ready prompt.
            lines.removeAt(lines.lastIndex)
            lines.addAll(passiveLines.map { "§d$it" })
        }
        lines.reversed().forEachIndexed { index, text ->
            // The invisible colour suffix keeps otherwise identical
            // scoreboard lines unique.
            objective.getScore("$text§${index.toString(16)}").score = index
        }
        if (player.scoreboard != board) player.scoreboard = board
        updateZealBar(player)
    }

    /**
     * Paladin Zeal boss bar: appears while Taunt is up, fills toward the Holy
     * Nova, and its title also states the live Smite bonus and time left -
     * the three "invisible" perks (Zeal, Smite, stance timer) in one place.
     */
    private fun updateZealBar(player: Player) {
        val status = plugin.classPassives.tauntStatus(player)
        if (status == null) {
            zealBars.remove(player.uniqueId)?.let { player.hideBossBar(it) }
            return
        }
        val bar = zealBars.getOrPut(player.uniqueId) {
            BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)
                .also { player.showBossBar(it) }
        }
        val zeal = status.zeal.toInt()
        val threshold = status.zealThreshold.toInt()
        bar.name(Component.text(
            "⚜ Taunt ${status.secondsLeft.toInt()}s   ·   Zeal $zeal / $threshold   ·   " +
                "next Smite +${String.format(Locale.US, "%.1f", status.smiteBonus + status.pendingRetribution)}"))
        bar.progress((status.zeal / status.zealThreshold).toFloat().coerceIn(0f, 1f))
    }

    /** Hides every Zeal bar - called on plugin disable so a reload leaves none orphaned. */
    fun shutdown() {
        zealBars.forEach { (id, bar) -> plugin.server.getPlayer(id)?.hideBossBar(bar) }
        zealBars.clear()
    }

    fun remove(player: Player) {
        boards.remove(player.uniqueId)
        zealBars.remove(player.uniqueId)?.let { player.hideBossBar(it) }
    }

    /** Confirmed post-commit purchase feedback. Particles are deliberately non-damaging. */
    fun nodePurchased(player: Player) {
        val burst = player.location.clone().add(0.0, 0.15, 0.0)
        val green = Particle.DustOptions(Color.fromRGB(80, 255, 110), 1.5f)
        player.world.spawnParticle(Particle.FIREWORK, burst, 14, 0.42, 0.12, 0.42, 0.05)
        player.world.spawnParticle(Particle.DUST, burst, 34, 0.55, 0.14, 0.55, 0.08, green)
        // A Note Block placed on a gold block uses the bell instrument.
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 0.65f)
    }

    /** Dungeon XP level-up feedback. Particle fireworks are visual-only and cannot deal damage. */
    fun levelUp(player: Player) {
        val burst = player.location.clone().add(0.0, 0.15, 0.0)
        val blue = Particle.DustOptions(Color.fromRGB(65, 155, 255), 1.6f)
        player.world.spawnParticle(Particle.FIREWORK, burst, 16, 0.45, 0.13, 0.45, 0.06)
        player.world.spawnParticle(Particle.DUST, burst, 38, 0.58, 0.15, 0.58, 0.09, blue)
    }

    fun rageTriggered(player: Player) {
        val center = player.location.clone().add(0.0, 0.85, 0.0)
        val red = Particle.DustOptions(Color.fromRGB(245, 55, 55), 1.65f)
        player.world.spawnParticle(Particle.DUST, center, 42, 0.52, 0.65, 0.52, 0.1, red)
        player.world.spawnParticle(Particle.FLAME, center, 26, 0.45, 0.55, 0.45, 0.05)
        player.playSound(player.location, Sound.ENTITY_RAVAGER_ROAR, 0.75f, 1.1f)
    }

    /** Warrior Dash launch: the whoosh, plus a short particle streak that rides the player for the lunge. */
    fun warriorDashCast(player: Player, berserk: Boolean) {
        val world = player.world
        player.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, if (berserk) 0.7f else 0.95f)
        if (berserk) player.playSound(player.location, Sound.ENTITY_RAVAGER_ROAR, 0.5f, 1.35f)
        object : BukkitRunnable() {
            private var ticks = 0
            override fun run() {
                if (ticks++ >= 6 || !player.isOnline) {
                    cancel(); return
                }
                val at = player.location.clone().add(0.0, 0.9, 0.0)
                if (berserk) {
                    val red = Particle.DustOptions(Color.fromRGB(245, 70, 45), 1.5f)
                    world.spawnParticle(Particle.DUST, at, 8, 0.28, 0.35, 0.28, 0.0, red)
                    world.spawnParticle(Particle.FLAME, at, 4, 0.2, 0.25, 0.2, 0.01)
                } else {
                    world.spawnParticle(Particle.CLOUD, at, 6, 0.22, 0.3, 0.22, 0.01)
                    world.spawnParticle(Particle.CRIT, at, 5, 0.25, 0.3, 0.25, 0.05)
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    /** Warrior Dash connecting with one or more enemies: a sweep burst and a crunch. */
    fun warriorDashImpact(player: Player, berserk: Boolean) {
        val at = player.location.clone().add(0.0, 1.0, 0.0)
        player.world.spawnParticle(Particle.SWEEP_ATTACK, at, if (berserk) 3 else 1, 0.4, 0.3, 0.4, 0.0)
        player.world.spawnParticle(Particle.CRIT, at, if (berserk) 24 else 14, 0.5, 0.4, 0.5, 0.25)
        player.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, if (berserk) 0.85f else 1.1f)
    }

    /** Berserk's opening Seismic Slam: a ground-pound shockwave around the Warrior. */
    fun warriorSlam(player: Player, shockwave: Boolean) {
        val world = player.world
        val at = player.location
        world.spawnParticle(Particle.EXPLOSION, at, if (shockwave) 4 else 2, 1.0, 0.15, 1.0, 0.0)
        world.spawnParticle(Particle.BLOCK, at.clone().add(0.0, 0.1, 0.0), 70, 1.5, 0.1, 1.5, 0.1,
            Material.NETHERRACK.createBlockData())
        val ember = Particle.DustOptions(Color.fromRGB(205, 45, 20), 1.7f)
        world.spawnParticle(Particle.DUST, at.clone().add(0.0, 0.5, 0.0), 44, 1.7, 0.3, 1.7, 0.0, ember)
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.65f)
        world.playSound(at, Sound.ENTITY_RAVAGER_ROAR, 0.85f, 0.8f)
        world.playSound(at, Sound.BLOCK_ANVIL_LAND, if (shockwave) 0.9f else 0.6f, 0.6f)
    }

    /** Focus Shot leaving the bow: a heavy release and a thin cyan trail on the arrow. */
    fun focusShotFired(player: Player, arrow: Arrow) {
        player.playSound(player.location, Sound.ITEM_CROSSBOW_LOADING_END, 0.9f, 0.8f)
        player.playSound(player.location, Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.6f)
        player.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.4f)
        arrowTrail(arrow, Color.fromRGB(60, 220, 235))
    }

    /** A sparse coloured dust trail that rides a fired arrow until it lands or expires. */
    private fun arrowTrail(arrow: Projectile, color: Color) {
        val dust = Particle.DustOptions(color, 1.0f)
        object : BukkitRunnable() {
            private var ticks = 0
            override fun run() {
                val stuck = (arrow as? AbstractArrow)?.isInBlock == true
                if (ticks++ >= 80 || !arrow.isValid || arrow.isDead || stuck) {
                    cancel(); return
                }
                arrow.world.spawnParticle(Particle.DUST, arrow.location, 1, 0.0, 0.0, 0.0, 0.0, dust)
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    /** Focus Shot connecting: a sharp burst at the impact point. */
    fun focusShotImpact(where: Location) {
        val world = where.world ?: return
        world.spawnParticle(Particle.CRIT, where, 30, 0.25, 0.25, 0.25, 0.35)
        world.spawnParticle(Particle.FIREWORK, where, 10, 0.2, 0.2, 0.2, 0.08)
        world.spawnParticle(Particle.SWEEP_ATTACK, where, 2, 0.1, 0.1, 0.1, 0.0)
        world.playSound(where, Sound.ENTITY_ARROW_HIT_PLAYER, 1.0f, 0.7f)
        world.playSound(where, Sound.ENTITY_GENERIC_EXPLODE, 0.35f, 1.6f)
    }

    /** A Skyfall arrow leaving the bow: a wind-charged loose and a thin lime trail on the arrow. */
    fun skyfallArmed(player: Player, arrow: Projectile) {
        player.playSound(player.location, Sound.ENTITY_WIND_CHARGE_THROW, 1.0f, 0.8f)
        player.playSound(player.location, Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.7f)
        arrowTrail(arrow, Color.fromRGB(120, 230, 60))
    }

    /**
     * Skyfall arrow landing: a loose lime particle poof - a burst, not a
     * blast (no explosion textures) - with an airy chime instead of a bang.
     */
    fun skyfallDetonate(where: Location) {
        val world = where.world ?: return
        val lime = Particle.DustOptions(Color.fromRGB(140, 235, 70), 1.4f)
        world.spawnParticle(Particle.DUST, where, 22, 1.2, 0.35, 1.2, 0.0, lime)
        world.spawnParticle(Particle.POOF, where, 14, 0.9, 0.2, 0.9, 0.02)
        world.spawnParticle(Particle.END_ROD, where, 8, 0.5, 0.15, 0.5, 0.05)
        world.playSound(where, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 0.9f, 1.35f)
        world.playSound(where, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 0.8f)
    }

    /** Holy Nova: a golden burst that damages mobs and mends allies around the Paladin. */
    fun paladinHolyNova(centre: Location, radius: Double) {
        val world = centre.world ?: return
        val gold = Particle.DustOptions(Color.fromRGB(255, 224, 130), 1.8f)
        world.spawnParticle(Particle.DUST, centre.clone().add(0.0, 1.0, 0.0), 60, radius * 0.5, 0.6, radius * 0.5, 0.0, gold)
        world.spawnParticle(Particle.END_ROD, centre.clone().add(0.0, 0.8, 0.0), 30, radius * 0.4, 0.4, radius * 0.4, 0.06)
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, centre.clone().add(0.0, 1.0, 0.0), 24, 0.5, 0.6, 0.5, 0.15)
        world.playSound(centre, Sound.ITEM_TOTEM_USE, 0.55f, 1.35f)
        world.playSound(centre, Sound.BLOCK_BELL_RESONATE, 0.8f, 1.4f)
    }

    /** One pulse of Consecrated Ground - a low ring of light on the floor. */
    fun paladinConsecrationTick(centre: Location, radius: Double) {
        val world = centre.world ?: return
        val gold = Particle.DustOptions(Color.fromRGB(255, 205, 90), 1.3f)
        val points = (radius * 6).toInt().coerceIn(12, 80)
        for (i in 0 until points) {
            val a = Math.PI * 2 * i / points
            val x = centre.x + kotlin.math.cos(a) * radius
            val z = centre.z + kotlin.math.sin(a) * radius
            world.spawnParticle(Particle.DUST, x, centre.y + 0.1, z, 1, 0.0, 0.0, 0.0, 0.0, gold)
        }
        world.spawnParticle(Particle.END_ROD, centre.clone().add(0.0, 0.15, 0.0), 3, radius * 0.35, 0.05, radius * 0.35, 0.0)
    }

    /** Shield / Bless landing on an ally: a golden flash and a soft chime. */
    fun paladinShieldCast(target: Player) {
        val at = target.location.clone().add(0.0, 1.0, 0.0)
        target.world.spawnParticle(Particle.END_ROD, at, 24, 0.35, 0.55, 0.35, 0.04)
        target.world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 18, 0.3, 0.5, 0.3, 0.1)
        target.world.playSound(target.location, Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.7f)
        target.world.playSound(target.location, Sound.ITEM_ARMOR_EQUIP_GOLD, 0.7f, 1.2f)
    }

    /** True when the active Mage wand preset is the fiery kind (Magma Wand). */
    private fun mageFiery(): Boolean = plugin.classesConfig.mageWandBoolean("impact-lava", false)

    /**
     * Arcane Surge's ignition - a bright starburst at the muzzle the instant
     * it fires, telegraphing "this one is not a normal bolt" before the
     * projectile even leaves.
     */
    fun arcaneSurgeCast(player: Player) {
        val at = player.eyeLocation.clone().add(player.eyeLocation.direction.multiply(0.6))
        val fiery = mageFiery()
        val tint = if (fiery) Color.fromRGB(255, 170, 60) else Color.fromRGB(216, 180, 255)
        at.world?.spawnParticle(Particle.DUST, at, 28, 0.3, 0.3, 0.3, 0.0, Particle.DustOptions(tint, 1.7f))
        at.world?.spawnParticle(if (fiery) Particle.FLAME else Particle.END_ROD, at, 20, 0.32, 0.32, 0.32, 0.06)
        at.world?.spawnParticle(Particle.FLASH, at, 1, 0.0, 0.0, 0.0, 0.0)
    }

    /** Arcane Surge's rank-V nova at the bolt's impact point. */
    fun arcaneSurgeNova(centre: Location, radius: Double) {
        val world = centre.world ?: return
        world.spawnParticle(Particle.FLASH, centre, 1, 0.0, 0.0, 0.0, 0.0)
        if (mageFiery()) {
            world.spawnParticle(Particle.DUST, centre, 40, radius * 0.5, 0.4, radius * 0.5, 0.0,
                Particle.DustOptions(Color.fromRGB(255, 150, 45), 1.6f))
            world.spawnParticle(Particle.FLAME, centre, 40, radius * 0.45, 0.35, radius * 0.45, 0.06)
            world.spawnParticle(Particle.LAVA, centre, 10, radius * 0.3, 0.25, radius * 0.3, 0.0)
            world.spawnParticle(Particle.EXPLOSION_EMITTER, centre, 1, 0.0, 0.0, 0.0, 0.0)
            world.playSound(centre, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.7f)
            world.playSound(centre, Sound.ENTITY_BLAZE_SHOOT, 0.7f, 0.6f)
        } else {
            world.spawnParticle(Particle.DUST, centre, 40, radius * 0.5, 0.4, radius * 0.5, 0.0,
                Particle.DustOptions(Color.fromRGB(190, 120, 255), 1.6f))
            world.spawnParticle(Particle.WITCH, centre, 30, radius * 0.4, 0.3, radius * 0.4, 0.1)
            world.playSound(centre, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.7f, 0.8f)
            world.playSound(centre, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.55f)
        }
    }

    /** Mage Blink: a poof at both ends and a fwoosh, tinted to the wand preset. */
    fun mageBlink(origin: Location, destination: Location) {
        val fiery = mageFiery()
        val puff = if (fiery) Particle.FLAME else Particle.WITCH
        origin.world?.let { w ->
            w.spawnParticle(puff, origin.clone().add(0.0, 1.0, 0.0), 26, 0.3, 0.6, 0.3, 0.05)
            w.playSound(origin, if (fiery) Sound.ITEM_FIRECHARGE_USE else Sound.ENTITY_ENDERMAN_TELEPORT, 0.55f, if (fiery) 0.9f else 1.6f)
        }
        destination.world?.let { w ->
            w.spawnParticle(puff, destination.clone().add(0.0, 1.0, 0.0), 26, 0.3, 0.6, 0.3, 0.05)
            w.spawnParticle(if (fiery) Particle.LAVA else Particle.END_ROD, destination.clone().add(0.0, 1.0, 0.0), if (fiery) 6 else 12, 0.25, 0.5, 0.25, 0.03)
            w.playSound(destination, if (fiery) Sound.ENTITY_BLAZE_SHOOT else Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, if (fiery) 1.2f else 1.4f)
        }
    }

    /** Mage Blink departure blast: the space you left detonates. */
    fun mageBlinkBlast(centre: Location, radius: Double) {
        val world = centre.world ?: return
        val fiery = mageFiery()
        val tint = if (fiery) Color.fromRGB(255, 130, 40) else Color.fromRGB(180, 110, 255)
        world.spawnParticle(Particle.DUST, centre.clone().add(0.0, 0.6, 0.0), 28, radius * 0.4, 0.3, radius * 0.4, 0.0, Particle.DustOptions(tint, 1.5f))
        world.spawnParticle(if (fiery) Particle.FLAME else Particle.WITCH, centre.clone().add(0.0, 0.6, 0.0), 22, radius * 0.35, 0.3, radius * 0.35, 0.06)
        world.spawnParticle(Particle.EXPLOSION, centre.clone().add(0.0, 0.5, 0.0), 2, 0.2, 0.1, 0.2, 0.0)
        world.playSound(centre, Sound.ENTITY_GENERIC_EXPLODE, 0.55f, if (fiery) 1.1f else 1.4f)
        world.playSound(centre, if (fiery) Sound.BLOCK_LAVA_POP else Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.7f, 0.9f)
    }

    fun tauntTriggered(player: Player) {
        val center = player.location.clone().add(0.0, 0.85, 0.0)
        val gold = Particle.DustOptions(Color.fromRGB(255, 205, 55), 1.55f)
        player.world.spawnParticle(Particle.DUST, center, 40, 0.58, 0.7, 0.58, 0.08, gold)
        player.world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 26, 0.42, 0.6, 0.42, 0.08)
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 0.85f, 1.15f)
    }

    private fun updateTabName(player: Player) {
        val classType = plugin.classes.activeClass(player.uniqueId)
        @Suppress("DEPRECATION")
        player.setPlayerListName((classType?.tabPrefix ?: "") + player.name)
    }

    private fun number(value: Double): String = String.format(Locale.US, "%.1f", value)
}
