package nl.riddernix.dungeonplugin.mob

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import com.destroystokyo.paper.event.entity.EndermanEscapeEvent
import io.papermc.paper.world.WeatheringCopperState
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.event.DungeonMobInfo
import nl.riddernix.dungeonplugin.fx.SpawnAnimation
import nl.riddernix.dungeonplugin.fx.SpawnAnimations
import nl.riddernix.dungeonplugin.generation.Bounds
import nl.riddernix.dungeonplugin.generation.DungeonLayout
import nl.riddernix.dungeonplugin.room.DungeonCorridorEnterEvent
import nl.riddernix.dungeonplugin.room.DungeonInstance
import nl.riddernix.dungeonplugin.room.DungeonMarker
import nl.riddernix.dungeonplugin.room.DungeonMarkerDefinitions
import nl.riddernix.dungeonplugin.room.DungeonRoom
import nl.riddernix.dungeonplugin.room.DungeonRoomEnterEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Ageable
import org.bukkit.entity.Breeze
import org.bukkit.entity.CopperGolem
import org.bukkit.entity.Enderman
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Hoglin
import org.bukkit.entity.IronGolem
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.PiglinAbstract
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Slime
import org.bukkit.entity.Vex
import org.bukkit.entity.Warden
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.EntityTeleportEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.SlimeSplitEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.io.IOException
import java.util.EnumSet
import java.util.Locale
import java.util.Random
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Marker-driven room groups with tagged, persistent dungeon mobs. */
class DungeonMobManager(private val plugin: DungeonPlugin) : Listener {

    private val miniMessage = MiniMessage.miniMessage()
    private val random = Random()
    private val mobs = HashMap<String, HashMap<String, RoomMobs>>()
    private val pendingSplits = HashMap<UUID, MutableList<SplitContext>>()
    private val pendingBossMinions = HashMap<UUID, PendingBossMinions>()
    private val activeBossSummons = HashMap<UUID, BossSummoningSequence>()
    private val bossBars = HashMap<UUID, ActiveBossBar>()
    private val testingMobs = HashMap<UUID, TestMobLocation>()
    private val testingMobsFile = File(plugin.dataFolder, "testing-mobs.yml")

    init {
        loadTestingMobLocations()
    }

    /** Returns every configured marker definition for command and build consumers. */
    fun markerDefinitions(): List<DungeonMarkerDefinitions.Definition> =
        DungeonMarkerDefinitions.read(plugin.config, plugin)

    fun markerCategories(): List<String> = markerDefinitions().map { it.category }

    /** The category names accepted by the combat spawner. */
    fun combatCategories(): List<String> = markerCategories().filter { Role.category(it) != null }

    fun themes(): List<String> {
        val section = plugin.config.getConfigurationSection("mobs.themes") ?: return emptyList()
        return section.getKeys(false).sorted()
    }

    /** Spawns one configured category group for safe inspection outside a dungeon. */
    fun summonTestingGroup(origin: Location, rawCategory: String?, difficulty: Int, requestedTheme: String?): TestSummonResult {
        val category = Role.category(rawCategory) ?: return TestSummonResult(TestSummonStatus.INVALID_CATEGORY, 0, "")
        if (difficulty < 1 || difficulty > 9) return TestSummonResult(TestSummonStatus.INVALID_DIFFICULTY, 0, "")

        val defaultSettings = DifficultySettings.read(plugin.config, difficulty)
        val theme = if (requestedTheme.isNullOrBlank()) defaultSettings.theme else requestedTheme.lowercase(Locale.ROOT)
        if (theme !in themes()) return TestSummonResult(TestSummonStatus.INVALID_THEME, 0, theme)
        val settings = DifficultySettings(plugin.config, difficulty, theme,
            defaultSettings.tier, defaultSettings.scaling)
        val definition = settings.category(category)
        if (definition.type == null) return TestSummonResult(TestSummonStatus.INVALID_CATEGORY, 0, theme)
        val stats = settings.categoryStats(category)
        if (stats.count <= 0) return TestSummonResult(TestSummonStatus.DISABLED_CATEGORY, 0, theme)

        val locations = testingLocations(origin, definition.type, stats, stats.count)
        if (locations.size != stats.count) return TestSummonResult(TestSummonStatus.NO_CLEARANCE, 0, theme)
        var spawned = 0
        for (location in locations) {
            val entity = location.world!!.spawnEntity(location, definition.type)
            if (entity !is LivingEntity) {
                entity.remove()
                continue
            }
            prepareTestingMob(entity, settings, category, stats, definition)
            testingMobs[entity.uniqueId] = TestMobLocation.from(entity.location)
            spawned++
        }
        saveTestingMobLocations()
        return TestSummonResult(TestSummonStatus.SUCCESS, spawned, theme)
    }

    /** Removes all recorded testing mobs, loading their stored chunks first. */
    fun removeTestingMobs(): Int {
        var removed = 0
        for ((id, stored) in testingMobs.toMap()) {
            val world = Bukkit.getWorld(stored.worldName) ?: continue
            world.getChunkAt(stored.x shr 4, stored.z shr 4)
            val entity = Bukkit.getEntity(id)
            if (entity != null && isTestingMob(entity)) {
                entity.remove()
                removed++
            }
        }
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities.toList()) {
                if (!isTestingMob(entity)) continue
                entity.remove()
                removed++
            }
        }
        testingMobs.clear()
        saveTestingMobLocations()
        return removed
    }

    /** Saves the latest known locations so testing mobs remain removable after a restart. */
    fun saveTestingMobLocations() {
        refreshTestingMobLocations()
        val saved = YamlConfiguration()
        for ((id, location) in testingMobs) {
            val path = id.toString()
            saved.set("$path.world", location.worldName)
            saved.set("$path.x", location.x)
            saved.set("$path.y", location.y)
            saved.set("$path.z", location.z)
        }
        try {
            saved.save(testingMobsFile)
        } catch (exception: IOException) {
            plugin.logger.warning("Could not save testing mob locations: ${exception.message}")
        }
    }

    private fun loadTestingMobLocations() {
        if (!testingMobsFile.isFile) return
        val saved = YamlConfiguration.loadConfiguration(testingMobsFile)
        for (rawId in saved.getKeys(false)) {
            try {
                val id = UUID.fromString(rawId)
                val path = "$rawId."
                val world = saved.getString(path + "world")
                if (world != null) testingMobs[id] = TestMobLocation(world, saved.getInt(path + "x"),
                    saved.getInt(path + "y"), saved.getInt(path + "z"))
            } catch (ignored: IllegalArgumentException) {
                plugin.logger.warning("Ignoring invalid testing mob entry '$rawId'.")
            }
        }
    }

    private fun refreshTestingMobLocations() {
        testingMobs.entries.removeIf { entry ->
            val entity = Bukkit.getEntity(entry.key) ?: return@removeIf false
            if (!isTestingMob(entity) || entity.isDead || !entity.isValid) return@removeIf true
            entry.setValue(TestMobLocation.from(entity.location))
            false
        }
    }

    @EventHandler
    fun onRoomEnter(event: DungeonRoomEnterEvent) {
        spawnRoom(event.dungeon, event.room)
        if (event.room.type == DungeonLayout.RoomType.BOSS) {
            // The arena entry is the first committed moment of the fight, so
            // the boss bar appears together with the summoning sequence.
            showBossBars(event.dungeon, event.room)
            startBossSummoning(event.dungeon, event.room)
        }
    }

    @EventHandler
    fun onCorridorEnter(event: DungeonCorridorEnterEvent) {
        val comingFrom = event.comingFrom ?: return
        val targetId = event.corridor.otherRoom(comingFrom.id)
        val target = targetId?.let { event.dungeon.room(it) }
        if (target != null) spawnRoom(event.dungeon, target)
    }

    private fun spawnRoom(dungeon: DungeonInstance, room: DungeonRoom) {
        if (dungeon.isCompleted) return
        if (room.type == DungeonLayout.RoomType.SPAWN && !plugin.config.getBoolean("mobs.spawn-room", false)) return
        val state = state(dungeon, room)
        // Visited is recorded even when spawning is deferred, so first-visit
        // reporting and the door watchdog keep their meaning.
        state.visited = true
        if (state.spawned) return

        val settings = DifficultySettings.read(plugin.config, dungeon.difficulty, dungeon.partySize)
        if (room.type == DungeonLayout.RoomType.BOSS) {
            // The gate manager arms the arena once every player stands inside;
            // spawning here would start the fight ahead of the party.
            if (plugin.gates.defersBossSpawn()) return
            state.spawned = true
            spawnBoss(dungeon, room, settings)
            return
        }
        state.spawned = true
        if (!isCombatRoom(room)) return
        if (room.variant == DungeonLayout.RoomVariant.PARKOUR &&
            !plugin.config.getBoolean("mobs.parkour-rooms.spawn-mobs", false)) return
        if (room.role != null) {
            spawnGroups(dungeon, room, settings, roleAnchors(dungeon, room, settings))
            return
        }
        spawnGroups(dungeon, room, settings, room.markers)
    }

    private fun isCombatRoom(room: DungeonRoom): Boolean =
        room.type == DungeonLayout.RoomType.NORMAL || room.type == DungeonLayout.RoomType.BRANCH

    /**
     * Turns mobs.room-roles.<role> into one anchor per requested group.
     * Authored markers of the right colour anchor groups at their own spots;
     * any shortfall gets deterministic anchors found on clear floor at
     * runtime.
     */
    private fun roleAnchors(dungeon: DungeonInstance, room: DungeonRoom,
                            settings: DifficultySettings): List<DungeonMarker> {
        val recipe = plugin.config.getConfigurationSection("mobs.room-roles.${room.role}")
        if (recipe == null) {
            plugin.logger.warning("Room ${room.id} carries role '${room.role}'" +
                " but mobs.room-roles.${room.role} is not configured; nothing spawns there.")
            return emptyList()
        }
        val anchors = ArrayList<DungeonMarker>()
        val positions = Random(dungeon.seed xor 0x524f4c45414e43L xor (room.id.hashCode().toLong() shl 32))
        for (rawCategory in recipe.getKeys(false)) {
            val groups = maxOf(0, recipe.getInt(rawCategory, 0))
            val category = Role.category(rawCategory)
            if (category == null) {
                if (groups > 0) plugin.logger.warning("mobs.room-roles.${room.role}.$rawCategory" +
                    " is not a spawnable mob category and was skipped.")
                continue
            }
            val authored = room.markers.filter { category.configName() == it.category }
            for (group in 0 until groups) {
                if (group < authored.size) {
                    anchors.add(authored[group])
                    continue
                }
                val generated = generatedAnchor(dungeon, room, settings, category, anchors, positions)
                if (generated != null) {
                    anchors.add(generated)
                } else {
                    plugin.logger.warning("No clear anchor found for a ${category.configName()}" +
                        " group in room ${room.id} of dungeon ${dungeon.id}.")
                }
            }
        }
        return anchors
    }

    private fun generatedAnchor(dungeon: DungeonInstance, room: DungeonRoom,
                                settings: DifficultySettings, category: Role,
                                existing: List<DungeonMarker>, positions: Random): DungeonMarker? {
        val bounds = room.bounds
        val definition = settings.category(category)
        if (definition.type == null) return null
        val clearance = Clearance.read(plugin.config, definition.type)
        // The keyholder stands in the middle of its room, like the boss. Its
        // room is a dead end built around it, and a corner spawn reads as the
        // key having failed to appear. Centre first, random anchor only if the
        // centre has no floor that fits it.
        if (category == Role.GUARDIAN) {
            val stats = settings.categoryStats(category)
            val scaled = Clearance(ceil(clearance.width * stats.scale).toInt(),
                ceil(clearance.height * stats.scale).toInt())
            val centre = automaticBossCentre(dungeon.world, room, scaled)
            if (centre != null) {
                return DungeonMarker(category.configName(), centre.blockX, centre.blockY, centre.blockZ)
            }
        }
        val y = room.floorY
        val edge = maxOf(2, plugin.config.getInt("mobs.markers.generated.edge-clearance", 3))
        val minimumDistance = maxOf(1, plugin.config.getInt("mobs.markers.generated.minimum-distance", 8))
        val attempts = maxOf(1, plugin.config.getInt("mobs.markers.generated.placement-attempts", 100))
        val minX = bounds.minX + edge
        val maxX = bounds.maxX - edge
        val minZ = bounds.minZ + edge
        val maxZ = bounds.maxZ - edge
        if (minX <= maxX && minZ <= maxZ) {
            for (attempt in 0 until attempts) {
                val x = positions.nextInt(maxX - minX + 1) + minX
                val z = positions.nextInt(maxZ - minZ + 1) + minZ
                if (nearDoorway(dungeon, room, x, y, z) || !hasClearance(dungeon.world, x, y, z, clearance)) continue
                val tooClose = existing.any { marker ->
                    val offsetX = marker.x - x
                    val offsetZ = marker.z - z
                    offsetX * offsetX + offsetZ * offsetZ < minimumDistance * minimumDistance
                }
                if (!tooClose) return DungeonMarker(category.configName(), x, y, z)
            }
        }
        return null
    }

    /** Each consumed marker requests one complete category group. */
    private fun spawnGroups(dungeon: DungeonInstance, room: DungeonRoom, settings: DifficultySettings,
                            anchors: List<DungeonMarker>) {
        val queue = ArrayList<MarkerMember>()
        for (marker in anchors) {
            val category = Role.category(marker.category) ?: continue
            val definition = settings.category(category)
            val stats = settings.categoryStats(category)
            if (definition.type == null || stats.count <= 0) continue
            for (count in 0 until stats.count) {
                queue.add(MarkerMember(marker, category, definition, stats))
            }
        }
        if (queue.isEmpty()) return
        val state = state(dungeon, room)
        state.pendingSpawns += queue.size
        val perTick = maxOf(1, plugin.config.getInt("mobs.spawn-per-tick", 4))
        val positions = Random(dungeon.seed xor 0x535741524d504f53L xor (room.id.hashCode().toLong() shl 32))
        val placed = HashMap<String, MutableList<Location>>()
        object : BukkitRunnable() {
            private var cursor = 0
            override fun run() {
                if (dungeon.isCompleted || plugin.rooms.dungeon(dungeon.world) !== dungeon) {
                    state.pendingSpawns = 0
                    cancel()
                    return
                }
                var spawned = 0
                while (spawned < perTick && cursor < queue.size) {
                    val member = queue[cursor]
                    try {
                        spawnMarkerMember(dungeon, room, settings, member,
                            placed.getOrPut(markerKey(member.marker)) { ArrayList() }, positions)
                    } finally {
                        state.pendingSpawns--
                    }
                    spawned++
                    cursor++
                }
                if (cursor >= queue.size) cancel()
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    private fun spawnMarkerMember(dungeon: DungeonInstance, room: DungeonRoom, settings: DifficultySettings,
                                  member: MarkerMember, placed: MutableList<Location>, positions: Random) {
        val location = findMarkerSpawn(dungeon, room, member.marker, member.definition.type!!, placed, positions) ?: return
        val entity = location.world!!.spawnEntity(location, member.definition.type)
        if (entity is LivingEntity) {
            prepareDungeonMob(entity, dungeon, room, settings, member.category, member.stats, false, null,
                member.definition.name, member.definition.nameVisible)
            placed.add(location)
        } else {
            entity.remove()
        }
    }

    private fun spawnBoss(dungeon: DungeonInstance, room: DungeonRoom, settings: DifficultySettings) {
        val boss = settings.boss()
        val centre = bossCentre(dungeon, room, boss.type(), boss.stats) ?: return
        val entity = dungeon.world.spawnEntity(centre, boss.type())
        if (entity !is LivingEntity) {
            entity.remove()
            return
        }
        prepareDungeonMob(entity, dungeon, room, settings, Role.ELITE, boss.stats, true, settings.theme, boss.name(), boss.nameVisible())
        createBossBar(entity, dungeon, room, boss)
        if (boss.minionCount() > 0) pendingBossMinions[entity.uniqueId] =
            PendingBossMinions(entity.uniqueId, dungeon, room, settings, boss, entity.location, boss.type())
    }

    /** Begins the boss's one-shot call after a player has committed to entering its arena. */
    private fun startBossSummoning(dungeon: DungeonInstance, room: DungeonRoom) {
        if (dungeon.isCompleted) return
        for ((bossId, pending) in pendingBossMinions) {
            if (pending.dungeon !== dungeon || pending.room !== room || bossId in activeBossSummons) continue
            val entity = Bukkit.getEntity(bossId)
            if (entity is LivingEntity && !entity.isDead && entity.isValid) {
                val sequence = BossSummoningSequence(bossId, entity, pending)
                activeBossSummons[bossId] = sequence
                plugin.events.fireBossSummon(plugin.snapshots.of(dungeon), entity, pending.settings.theme,
                    pending.boss.summoning().durationTicks)
                sequence.begin()
            } else {
                pendingBossMinions.remove(bossId)
                pending.spawnNow()
            }
            return
        }
    }

    private fun bossCentre(dungeon: DungeonInstance, room: DungeonRoom, type: EntityType, stats: MobStats): Location? {
        val marker = dungeon.bossSpawnLocation()
        val base = Clearance.read(plugin.config, type)
        val scaled = Clearance(ceil(base.width * stats.scale).toInt(), ceil(base.height * stats.scale).toInt())
        if (marker == null) {
            val automatic = automaticBossCentre(dungeon.world, room, scaled)
            if (automatic == null) {
                plugin.logger.severe("Boss room ${room.id} has no clear solid floor near its centre for a " +
                    "${scaled.width}x${scaled.height} scaled boss footprint; the boss was not spawned.")
            }
            return automatic
        }
        val x = marker.blockX
        val y = marker.blockY
        val z = marker.blockZ
        if (hasClearance(dungeon.world, x, y, z, scaled)) return Location(dungeon.world, x + 0.5, y.toDouble(), z + 0.5)
        val available = availableHeight(dungeon.world, x, y, z, scaled)
        plugin.logger.severe("Boss marker clearance failed in dungeon ${dungeon.id} at $x,$y,$z" +
            ": requires ${scaled.height} clear blocks of height and has $available. Boss was not spawned.")
        return null
    }

    /** Creates the native Adventure boss bar but does not expose it until arena entry. */
    private fun createBossBar(boss: LivingEntity, dungeon: DungeonInstance, room: DungeonRoom, definition: BossDefinition) {
        if (!plugin.config.getBoolean("mobs.boss-bars.enabled", true)) return
        val name = miniMessage.deserialize(definition.name())
        val bar = BossBar.bossBar(name, 1.0F, definition.barColor(), BossBar.Overlay.PROGRESS)
        bossBars[boss.uniqueId] = ActiveBossBar(dungeon.id, room.id, dungeon.world.name, bar)
    }

    /** Shows the boss bar only after somebody has entered the boss arena. */
    private fun showBossBars(dungeon: DungeonInstance, room: DungeonRoom) {
        for (state in bossBars.values) {
            if (state.dungeonId != dungeon.id || state.roomId != room.id) continue
            state.shown = true
            for (player in dungeon.world.players) showBossBar(player, state)
        }
    }

    private fun showBossBar(player: Player, state: ActiveBossBar) {
        if (state.viewers.add(player.uniqueId)) player.showBossBar(state.bar)
    }

    private fun updateBossBar(boss: LivingEntity) {
        val state = bossBars[boss.uniqueId] ?: return
        val maximumHealth = boss.getAttribute(Attribute.MAX_HEALTH)
        val maximum = maximumHealth?.value?.coerceAtLeast(1.0) ?: 1.0
        state.bar.progress((boss.health / maximum).coerceIn(0.0, 1.0).toFloat())
    }

    private fun syncBossBars() {
        for ((bossId, state) in bossBars.toList()) {
            val entity = Bukkit.getEntity(bossId)
            if (entity !is LivingEntity || entity.isDead || !entity.isValid) {
                removeBossBar(bossId)
                continue
            }
            updateBossBar(entity)
            for (viewer in state.viewers.toSet()) {
                val player = Bukkit.getPlayer(viewer)
                if (player == null || player.world.name != state.worldName) {
                    player?.hideBossBar(state.bar)
                    state.viewers.remove(viewer)
                }
            }
            if (state.shown) {
                val world = Bukkit.getWorld(state.worldName)
                if (world != null) for (player in world.players) showBossBar(player, state)
            }
        }
    }

    private fun removeBossBar(bossId: UUID) {
        val state = bossBars.remove(bossId) ?: return
        for (viewer in state.viewers) {
            Bukkit.getPlayer(viewer)?.hideBossBar(state.bar)
        }
    }

    private fun removeBossBars(dungeonId: String) {
        for ((bossId, state) in bossBars.toList()) {
            if (state.dungeonId == dungeonId) removeBossBar(bossId)
        }
    }

    /** Applies the plugin's baseline before other listeners receive the spawn event. */
    private fun prepareDungeonMob(entity: LivingEntity, dungeon: DungeonInstance, room: DungeonRoom,
                                  settings: DifficultySettings, role: Role, stats: MobStats, boss: Boolean,
                                  bossTheme: String?, displayName: String?, displayNameVisible: Boolean) {
        preventBabyVariant(entity)
        preventZombification(entity)
        val category = if (boss) "boss" else role.configName()
        tag(entity, dungeon.id, room.id, settings.tier, dungeon.difficulty, boss, settings.theme, category)
        entity.setRemoveWhenFarAway(false)
        if (entity is CopperGolem) configureCopperGolem(entity, stats.damage, stats.attackReach)
        if (entity is IronGolem && boss) configureIronGolem(entity, stats.damage, stats.attackReach)
        applyStats(entity, stats)
        applyWeapon(entity, stats.weapon)
        setDisplayName(entity, settings, role, displayName, displayNameVisible)
        clearArmor(entity)
        // Fired once the mob is fully set up, so anything a listener changes
        // here is the last word on it.
        plugin.events.fireMobSpawn(plugin.snapshots.of(dungeon), entity,
            DungeonMobInfo(settings.tier, dungeon.difficulty, category, settings.theme, boss))
        zeroEquipmentDropChances(entity)
        state(dungeon, room).entities.add(entity.uniqueId)
    }

    /** Applies the same baseline and spawn event without assigning a dungeon room. */
    private fun prepareTestingMob(entity: LivingEntity, settings: DifficultySettings, category: Role, stats: MobStats,
                                  definition: CategoryDefinition) {
        preventBabyVariant(entity)
        preventZombification(entity)
        tagTestingMob(entity, settings.tier, settings.difficulty, category.configName())
        entity.setRemoveWhenFarAway(false)
        if (entity is CopperGolem) configureCopperGolem(entity, stats.damage, stats.attackReach)
        applyStats(entity, stats)
        applyWeapon(entity, stats.weapon)
        setDisplayName(entity, settings, category, definition.name, definition.nameVisible)
        clearArmor(entity)
        // A test mob belongs to no dungeon, so its snapshot is null and
        // listeners are told as much through isTestMob.
        plugin.events.fireMobSpawn(null, entity, DungeonMobInfo(settings.tier, settings.difficulty,
            category.configName(), settings.theme, false))
        zeroEquipmentDropChances(entity)
    }

    private fun configureCopperGolem(copper: CopperGolem, damage: Double, reach: Double) {
        copper.setOxidizing(CopperGolem.Oxidizing.waxed())
        copper.weatheringState = WeatheringCopperState.UNAFFECTED
        Bukkit.getMobGoals().removeAllGoals(copper)
        Bukkit.getMobGoals().addGoal(copper, 1, HostileMeleeGoal(copper, damage, reach,
            NamespacedKey(plugin, "copper_golem_attack"), CopperGolem::class.java))
    }

    private fun configureIronGolem(golem: IronGolem, damage: Double, reach: Double) {
        golem.isPlayerCreated = false
        Bukkit.getMobGoals().removeAllGoals(golem)
        Bukkit.getMobGoals().addGoal(golem, 1, HostileMeleeGoal(golem, damage, reach,
            NamespacedKey(plugin, "iron_golem_boss_attack"), IronGolem::class.java))
    }

    private fun setDisplayName(entity: LivingEntity, settings: DifficultySettings, role: Role,
                               configuredName: String?, nameVisible: Boolean) {
        if (!configuredName.isNullOrBlank()) {
            entity.customName(miniMessage.deserialize(configuredName))
            entity.isCustomNameVisible = nameVisible
            return
        }
        val format = plugin.config.getString("mobs.name-format", "")
        if (format.isNullOrBlank()) return
        entity.customName(miniMessage.deserialize(format,
            Placeholder.unparsed("tier", settings.tier.toString()),
            Placeholder.unparsed("role", role.configName()),
            Placeholder.unparsed("mob", entity.type.name.lowercase(Locale.ROOT).replace('_', ' '))))
        entity.isCustomNameVisible = plugin.config.getBoolean("mobs.name-visible", false)
    }

    private fun preventBabyVariant(entity: LivingEntity) {
        if (entity is Ageable) entity.setAdult()
    }

    private fun tag(entity: LivingEntity, dungeonId: String, roomId: String, tier: Int, difficulty: Int, boss: Boolean,
                    bossTheme: String?, category: String?) {
        val data = entity.persistentDataContainer
        data.set(plugin.dungeonMobDungeonKey, PersistentDataType.STRING, dungeonId)
        data.set(plugin.dungeonMobRoomKey, PersistentDataType.STRING, roomId)
        data.set(plugin.dungeonMobTierKey, PersistentDataType.INTEGER, tier)
        data.set(plugin.dungeonMobDifficultyKey, PersistentDataType.INTEGER, difficulty)
        data.set(plugin.dungeonMobBossKey, PersistentDataType.BYTE, if (boss) 1.toByte() else 0.toByte())
        if (bossTheme != null) data.set(plugin.dungeonMobBossThemeKey, PersistentDataType.STRING, bossTheme)
        // Stored so a mob's category survives to its death event too.
        if (category != null) data.set(plugin.dungeonMobCategoryKey, PersistentDataType.STRING, category)
    }

    private fun tagTestingMob(entity: LivingEntity, tier: Int, difficulty: Int, category: String?) {
        val data = entity.persistentDataContainer
        data.set(plugin.dungeonMobTierKey, PersistentDataType.INTEGER, tier)
        data.set(plugin.dungeonMobDifficultyKey, PersistentDataType.INTEGER, difficulty)
        data.set(plugin.dungeonMobBossKey, PersistentDataType.BYTE, 0.toByte())
        data.set(plugin.dungeonMobTestKey, PersistentDataType.BYTE, 1.toByte())
        if (category != null) data.set(plugin.dungeonMobCategoryKey, PersistentDataType.STRING, category)
    }

    private fun applyStats(entity: LivingEntity, stats: MobStats) {
        setAttribute(entity, Attribute.MAX_HEALTH, stats.health)
        entity.health = minOf(stats.health, entity.getAttribute(Attribute.MAX_HEALTH)!!.value)
        setAttribute(entity, Attribute.ATTACK_DAMAGE, stats.damage)
        if (stats.speed > 0.0) setAttribute(entity, Attribute.MOVEMENT_SPEED, stats.speed)
        if (stats.attackSpeed > 0.0) setAttribute(entity, Attribute.ATTACK_SPEED, stats.attackSpeed)
        if (stats.knockbackResistance > 0.0) setAttribute(entity, Attribute.KNOCKBACK_RESISTANCE, stats.knockbackResistance)
        if (stats.scale > 0.0) setAttribute(entity, Attribute.SCALE, stats.scale)
    }

    private fun applyWeapon(entity: LivingEntity, path: String?) {
        val equipment = entity.equipment ?: return
        equipment.setItemInMainHand(item(path))
        equipment.setItemInOffHand(null)
        clearArmor(entity)
    }

    private fun item(path: String?): org.bukkit.inventory.ItemStack? {
        if (path == null || path.equals("AIR", ignoreCase = true)) return null
        val material = Material.matchMaterial(path)
        return if (material == null || material.isAir) null else org.bukkit.inventory.ItemStack(material)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: EntityDeathEvent) {
        val entity: Entity = event.entity
        if (isTestingMob(entity)) {
            event.drops.clear()
            event.droppedExp = 0
            testingMobs.remove(entity.uniqueId)
            return
        }
        val identity = identity(entity) ?: return
        event.drops.clear()
        event.droppedExp = maxOf(0, plugin.config.getInt("mobs.difficulties.${identity.difficulty}.experience", 0))
        val dungeon = plugin.rooms.dungeon(entity.world)?.takeIf { it.id == identity.dungeonId }
        // Count the kill and drop it from its room before anything is fired,
        // so a listener never sees a dungeon that still counts a dead mob.
        dungeon?.recordMobKill()
        val state = mobs[identity.dungeonId]?.get(identity.roomId)
        state?.entities?.remove(entity.uniqueId)
        if (dungeon != null && entity is LivingEntity) {
            val room = dungeon.room(identity.roomId)
            plugin.events.fireMobDeath(plugin.snapshots.of(dungeon), entity, event, identity.toInfo(),
                room?.let { plugin.snapshots.of(it) })
            checkRoomCleared(dungeon, identity.roomId)
            plugin.gates.notifyKill(dungeon, identity.roomId)
        }
        if (dungeon != null && !identity.boss && "guardian" == identity.category &&
            dungeon.keyGate != null && dungeon.keyGate.guardianRoomId == identity.roomId) {
            plugin.doors.onGuardianDeath(dungeon, entity.location)
        }
        if (dungeon != null && identity.boss) {
            // The arena bars drop at the kill itself, before the completion
            // grace period moves anyone.
            plugin.gates.onBossDeath(dungeon, identity.roomId)
            // Last, because completing the dungeon ends it: the boss death and
            // its room clearing both belong to a dungeon that is still active.
            removeBossBar(entity.uniqueId)
            plugin.completions.complete(dungeon)
        }
        val pending = pendingBossMinions.remove(entity.uniqueId)
        val sequence = activeBossSummons.remove(entity.uniqueId)
        if (sequence != null) sequence.completeNow()
        else pending?.spawnNow()
    }

    /** Applies the configured Warden boss damage to both melee and sonic-boom hits. */
    @EventHandler
    fun onBossDamage(event: EntityDamageByEntityEvent) {
        if (identity(event.damager) != null && identity(event.entity) != null) {
            event.isCancelled = true
            return
        }
        val identity = identity(event.damager)
        if (identity == null || !identity.boss || event.damager.type != EntityType.WARDEN) return
        event.damage = DifficultySettings.read(plugin.config, identity.difficulty).boss().stats.damage
    }

    /** Covers damage sources that do not use a living entity as their damager. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSummoningDamage(event: EntityDamageEvent) {
        val sequence = activeBossSummons[event.entity.uniqueId]
        if (sequence != null && sequence.blocksDamage()) event.isCancelled = true
    }

    /** Updates the HUD after Paper has applied the final damage calculation. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBossHealthChange(event: EntityDamageEvent) {
        if (event.entity.uniqueId !in bossBars) return
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val entity = Bukkit.getEntity(event.entity.uniqueId)
            if (entity is LivingEntity && !entity.isDead && entity.isValid) updateBossBar(entity)
        })
    }

    @EventHandler
    fun onTarget(event: EntityTargetLivingEntityEvent) {
        identity(event.entity) ?: return
        if (event.target != null && identity(event.target!!) != null) {
            event.isCancelled = true
            return
        }
        if (event.entity is Warden && event.target != null && event.target !is Player) event.isCancelled = true
    }

    /** Keeps a dungeon enderman's normal room-confined movement, but never lets damage become an escape. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEndermanEscape(event: EndermanEscapeEvent) {
        if (!isDungeonOrTestingMob(event.entity) ||
            !plugin.config.getBoolean("mobs.safety.prevent-enderman-damage-escape", true)) return
        if (event.reason == EndermanEscapeEvent.Reason.INDIRECT ||
            event.reason == EndermanEscapeEvent.Reason.CRITICAL_HIT) {
            event.isCancelled = true
        }
    }

    /**
     * Replaces the two vanilla ranged immunities with a normal player-caused
     * hit. Endermen avoid vanilla projectile damage even when their escape is
     * cancelled; breezes instead deflect the projectile before that damage
     * occurs.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onRiftProjectileHit(event: ProjectileHitEvent) {
        val target = event.hitEntity as? LivingEntity ?: return
        if (target !is Enderman && target !is Breeze) return
        if (!isDungeonOrTestingMob(target)) return
        val isEnderman = target is Enderman
        val isBreeze = target is Breeze
        if (isEnderman && !plugin.config.getBoolean("mobs.safety.prevent-enderman-damage-escape", true)) return
        if (isBreeze && !plugin.config.getBoolean("mobs.safety.disable-breeze-projectile-deflection", true)) return
        val projectile = event.entity
        val player = projectile.shooter as? Player ?: return
        if (!isDamagingProjectile(projectile)) return

        // Cancelling stops the breeze's deflection. Damage is supplied
        // directly by the player because vanilla endermen reject projectile
        // damage even when their escape event is cancelled.
        event.isCancelled = true
        target.damage(projectileDamage(projectile), player)
        projectile.remove()
    }

    /** Copies metadata to slime and magma-cube children before room counting sees them. */
    @EventHandler
    fun onSlimeSplit(event: SlimeSplitEvent) {
        val slime = event.entity
        val identity = identity(slime) ?: return
        if (event.count <= 0) return
        pendingSplits.getOrPut(slime.world.uid) { ArrayList() }
            .add(SplitContext(slime.location, identity, slime.customName(), slime.isCustomNameVisible,
                event.count, Bukkit.getCurrentTick() + 2L))
    }

    @EventHandler
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (event.entity is Vex && plugin.worlds.isDungeonWorld(event.location.world) &&
            plugin.config.getBoolean("mobs.safety.cancel-vex-spawns", true)) {
            event.isCancelled = true
            return
        }
        if (event.spawnReason != CreatureSpawnEvent.SpawnReason.SLIME_SPLIT) return
        val slime = event.entity
        val contexts = pendingSplits[slime.world.uid] ?: return
        val context = contexts.firstOrNull { it.remaining > 0 && it.location.distanceSquared(slime.location) <= 9.0 }
            ?: return
        context.remaining--
        val dungeon = plugin.rooms.dungeon(slime.world)?.takeIf { it.id == context.identity.dungeonId }
        val room = dungeon?.room(context.identity.roomId)
        if (dungeon == null || room == null) return
        val settings = DifficultySettings.read(plugin.config, context.identity.difficulty)
        prepareDungeonMob(slime, dungeon, room, settings, Role.SWARM, settings.categoryStats(Role.SWARM), false, null, null, false)
        if (context.name != null) {
            slime.customName(context.name)
            slime.isCustomNameVisible = context.nameVisible
        }
    }

    @EventHandler
    fun onTeleport(event: EntityTeleportEvent) {
        val identity = identity(event.entity) ?: return
        // A boss being raised out of its own entrance animation is scripted
        // movement, not an escape, so the containment rules step aside for it.
        if (event.entity.uniqueId in activeBossSummons) return
        if (event.entity is Enderman &&
            plugin.config.getBoolean("mobs.safety.constrain-enderman-teleport", true)) {
            val dungeon = plugin.rooms.dungeon(event.entity.world)
            val room = dungeon?.room(identity.roomId)
            val destination = event.to
            if (room == null || destination == null ||
                !room.bounds.contains(destination.blockX, destination.blockY, destination.blockZ)) {
                event.isCancelled = true
            }
            return
        }
        if (plugin.config.getBoolean("mobs.safety.prevent-dungeon-mob-teleport", true)) event.isCancelled = true
    }

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        hideBossBars(event.player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        hideBossBars(event.player)
    }

    private fun hideBossBars(player: Player) {
        for (state in bossBars.values) {
            if (state.viewers.remove(player.uniqueId)) player.hideBossBar(state.bar)
        }
    }

    @EventHandler
    fun onBlockChange(event: EntityChangeBlockEvent) {
        if (event.entity is Enderman && identity(event.entity) != null &&
            plugin.config.getBoolean("mobs.safety.prevent-enderman-block-changes", true)) {
            event.isCancelled = true
        }
    }

    private fun isDungeonOrTestingMob(entity: Entity): Boolean =
        identity(entity) != null || isTestingMob(entity)

    @EventHandler
    fun onTransform(event: EntityTransformEvent) {
        if (identity(event.entity) != null && plugin.config.getBoolean("mobs.safety.prevent-zombification", true)) {
            event.isCancelled = true
        }
    }

    /** Defensive recount for gates and for mobs removed by external plugins. */
    fun recount() {
        refreshTestingMobLocations()
        val tick = Bukkit.getCurrentTick().toLong()
        pendingSplits.values.forEach { contexts -> contexts.removeIf { it.expiresAtTick < tick || it.remaining <= 0 } }
        pendingSplits.entries.removeIf { it.value.isEmpty() }
        for (dungeon in mobs.values) {
            for (room in dungeon.values) {
                room.entities.removeIf { id ->
                    val entity = Bukkit.getEntity(id)
                    entity !is LivingEntity || entity.isDead || !entity.isValid
                }
            }
        }
        // Pruning is what notices a mob that vanished without a death event;
        // running the clear check here is what turns that into an opened gate
        // rather than a room sealed until something else happens to die in it.
        for (world in plugin.worlds.loadedDungeonWorlds()) {
            val dungeon = plugin.rooms.dungeon(world) ?: continue
            for (roomId in (mobs[dungeon.id]?.keys ?: emptySet()).toSet()) {
                checkRoomCleared(dungeon, roomId)
            }
        }
        guardWardens()
        syncBossBars()
        plugin.gates.tick()
    }

    /** A boss Warden is kept angry while players are present and frozen while none are. */
    private fun guardWardens() {
        for (world in plugin.worlds.loadedDungeonWorlds()) {
            for (entity in world.entities) {
                val identity = identity(entity)
                if (entity !is Warden || identity == null || !identity.boss) continue
                if (entity.uniqueId in activeBossSummons) continue
                val target = world.players.firstOrNull()
                if (target == null) {
                    entity.setAI(false)
                } else {
                    for (other in world.entities) if (other !is Player) entity.clearAnger(other)
                    entity.setAI(true)
                    entity.target = target
                    entity.setAnger(target, 150)
                    entity.setRemoveWhenFarAway(false)
                }
            }
        }
    }

    /** Rewinds one roled room so its recipe spawns afresh; the door watchdog's revival path. */
    fun reviveRoleRoom(dungeon: DungeonInstance, room: DungeonRoom) {
        val state = state(dungeon, room)
        state.spawned = false
        state.cleared = false
        state.entities.clear()
        spawnRoom(dungeon, room)
    }

    /** Rooms whose mobs have all been killed, for the progress figure. */
    fun clearedRoomCount(dungeonId: String): Int =
        mobs[dungeonId]?.values?.count { it.cleared } ?: 0

    /** Whether every mob spawned for that room has been killed. */
    fun isRoomCleared(dungeonId: String, roomId: String): Boolean =
        mobs[dungeonId]?.get(roomId)?.cleared ?: false

    /** Whether anyone has been far enough into the dungeon to wake this room. */
    fun isRoomVisited(dungeonId: String, roomId: String): Boolean =
        mobs[dungeonId]?.get(roomId)?.visited ?: false

    /**
     * Fires the room-cleared event once per room, after the kill that emptied
     * it has already been counted, so the dungeon snapshot the listener gets
     * includes this room in its cleared total.
     */
    private fun checkRoomCleared(dungeon: DungeonInstance, roomId: String) {
        val state = mobs[dungeon.id]?.get(roomId)
        val room = dungeon.room(roomId)
        if (state == null || room == null || state.cleared || !state.isClear()) return
        state.cleared = true
        val inside = dungeon.world.players.map { it.uniqueId }
        plugin.events.fireRoomCleared(plugin.snapshots.of(dungeon), plugin.snapshots.of(room), inside)
        plugin.gates.onRoomCleared(dungeon, room)
    }

    fun livingCount(dungeonId: String, roomId: String): Int {
        val state = mobs[dungeonId]?.get(roomId)
        val pending = pendingBossMinions.values.count { it.dungeon.id == dungeonId && it.room.id == roomId }
        return (state?.let { it.entities.size + it.pendingSpawns } ?: 0) + pending
    }

    fun goalNames(entity: Entity): List<String> {
        if (entity !is Mob) return emptyList()
        return Bukkit.getMobGoals().getAllGoals(entity).map { "${it.key.namespacedKey} ${it.types}" }
    }

    fun diagnostics(entity: Entity): List<String> {
        if (entity !is LivingEntity) return listOf("Not a living entity.")
        val target = if (entity is Mob && entity.target != null) entity.target!!.type.name else "none"
        return listOf("target=$target", "attack-damage=${attribute(entity, Attribute.ATTACK_DAMAGE)}",
            "movement-speed=${attribute(entity, Attribute.MOVEMENT_SPEED)}", "scale=${attribute(entity, Attribute.SCALE)}",
            "colliding=${collidesWithBlocks(entity)}")
    }

    fun removeWorld(worldName: String) {
        val dungeon = plugin.rooms.dungeon(worldName) ?: return
        removeBossBars(dungeon.id)
        for (entity in dungeon.world.entities.toList()) {
            val identity = identity(entity)
            if (identity != null && dungeon.id == identity.dungeonId) entity.remove()
        }
        mobs.remove(dungeon.id)
        pendingSplits.remove(dungeon.world.uid)
        pendingBossMinions.entries.removeIf { it.value.dungeon.id == dungeon.id }
        activeBossSummons.entries.removeIf { entry ->
            if (entry.value.pending.dungeon.id != dungeon.id) return@removeIf false
            entry.value.abort()
            true
        }
    }

    /** Immediately removes all tagged mobs and delayed spawn work for a completed dungeon. */
    fun despawnDungeonMobs(dungeon: DungeonInstance) {
        removeBossBars(dungeon.id)
        for (entity in dungeon.world.entities.toList()) {
            val identity = identity(entity)
            if (identity != null && dungeon.id == identity.dungeonId) entity.remove()
        }
        mobs.remove(dungeon.id)
        pendingSplits.remove(dungeon.world.uid)
        pendingBossMinions.entries.removeIf { it.value.dungeon.id == dungeon.id }
        activeBossSummons.entries.removeIf { entry ->
            if (entry.value.pending.dungeon.id != dungeon.id) return@removeIf false
            entry.value.abort()
            true
        }
    }

    /** The gate manager's arming path: the whole party is inside, start now. */
    fun spawnBossRoomNow(dungeon: DungeonInstance, room: DungeonRoom) {
        if (dungeon.isCompleted) return
        val state = state(dungeon, room)
        if (state.spawned) return
        state.visited = true
        state.spawned = true
        val settings = DifficultySettings.read(plugin.config, dungeon.difficulty, dungeon.partySize)
        spawnBoss(dungeon, room, settings)
        // The enter events already fired while the arena was empty, so the
        // bars and the summoning start here rather than from onRoomEnter.
        showBossBars(dungeon, room)
        startBossSummoning(dungeon, room)
    }

    /** The living mobs of one room, for the gate manager's glow failsafe. */
    fun livingEntities(dungeonId: String, roomId: String): List<LivingEntity> {
        val state = mobs[dungeonId]?.get(roomId) ?: return emptyList()
        val living = ArrayList<LivingEntity>()
        for (id in state.entities.toSet()) {
            val entity = Bukkit.getEntity(id)
            if (entity is LivingEntity && !entity.isDead && entity.isValid) {
                living.add(entity)
            }
        }
        return living
    }

    private fun state(dungeon: DungeonInstance, room: DungeonRoom): RoomMobs =
        mobs.getOrPut(dungeon.id) { HashMap() }.getOrPut(room.id) { RoomMobs() }

    /** Finds a clear, separated point around one authored or generated marker. */
    private fun findMarkerSpawn(dungeon: DungeonInstance, room: DungeonRoom, marker: DungeonMarker,
                                type: EntityType, placed: List<Location>, positions: Random): Location? {
        val bounds = room.bounds
        val clearance = Clearance.read(plugin.config, type)
        // A spawn marker sits on the floor it belongs to, so its own height is
        // the group's standing height even in a prefab with a raised platform.
        val y = marker.y
        val requestedRadius = maxOf(0, plugin.config.getInt("mobs.markers.group-radius", 5))
        val maximumRadius = maxOf(requestedRadius, plugin.config.getInt("mobs.markers.maximum-group-radius", 9))
        val outwardStep = maxOf(1, plugin.config.getInt("mobs.markers.outward-search-step", 2))
        val attemptsPerRing = maxOf(1, plugin.config.getInt("mobs.markers.attempts-per-ring", 20))
        val minimumDistance = maxOf(0.0, plugin.config.getDouble("mobs.markers.minimum-group-distance", 1.75))
        val minimumDistanceSquared = minimumDistance * minimumDistance

        var radius = requestedRadius
        while (radius <= maximumRadius) {
            for (attempt in 0 until attemptsPerRing) {
                val angle = positions.nextDouble() * Math.PI * 2.0
                val distance = if (radius == 0) 0.0 else positions.nextDouble() * radius
                val x = Math.round(marker.x + cos(angle) * distance).toInt()
                val z = Math.round(marker.z + sin(angle) * distance).toInt()
                if (x < bounds.minX + 2 || x > bounds.maxX - 2 || z < bounds.minZ + 2 || z > bounds.maxZ - 2) continue
                if (nearDoorway(dungeon, room, x, y, z) || !hasClearance(dungeon.world, x, y, z, clearance)) continue
                val location = Location(dungeon.world, x + 0.5, y.toDouble(), z + 0.5)
                if (placed.any { it.distanceSquared(location) < minimumDistanceSquared }) continue
                return location
            }
            if (radius + outwardStep > maximumRadius && radius != maximumRadius) radius = maximumRadius - outwardStep
            radius += outwardStep
        }
        return null
    }

    private fun testingLocations(origin: Location, type: EntityType, stats: MobStats, count: Int): List<Location> {
        val world = origin.world ?: return emptyList()
        val base = Clearance.read(plugin.config, type)
        val clearance = Clearance(ceil(base.width * stats.scale).toInt(), ceil(base.height * stats.scale).toInt())
        val requestedRadius = maxOf(0, plugin.config.getInt("mobs.markers.group-radius", 5))
        val minimumRadius = maxOf(2, ceil(clearance.width / 2.0).toInt() + 1)
        val maximumRadius = maxOf(minimumRadius, plugin.config.getInt("mobs.markers.maximum-group-radius", 9))
        val outwardStep = maxOf(1, plugin.config.getInt("mobs.markers.outward-search-step", 2))
        val attemptsPerRing = maxOf(1, plugin.config.getInt("mobs.markers.attempts-per-ring", 20))
        val minimumDistance = maxOf(0.0, plugin.config.getDouble("mobs.markers.minimum-group-distance", 1.75))
        val minimumDistanceSquared = minimumDistance * minimumDistance
        val y = origin.blockY
        val positions = ArrayList<Location>()
        val locationRandom = Random(origin.world!!.fullTime xor (origin.blockX * 73471L)
            xor (origin.blockY * 9127L) xor (origin.blockZ * 314159L) xor type.ordinal.toLong())
        for (member in 0 until count) {
            var found: Location? = null
            var radius = maxOf(requestedRadius, minimumRadius)
            while (radius <= maximumRadius && found == null) {
                for (attempt in 0 until attemptsPerRing) {
                    val angle = locationRandom.nextDouble() * Math.PI * 2.0
                    val distance = minimumRadius + locationRandom.nextDouble() * maxOf(0, radius - minimumRadius)
                    val x = Math.round(origin.x + cos(angle) * distance).toInt()
                    val z = Math.round(origin.z + sin(angle) * distance).toInt()
                    if (!hasClearance(world, x, y, z, clearance)) continue
                    val candidate = Location(world, x + 0.5, y.toDouble(), z + 0.5)
                    if (positions.any { it.distanceSquared(candidate) < minimumDistanceSquared }) continue
                    found = candidate
                    break
                }
                if (radius + outwardStep > maximumRadius && radius != maximumRadius) radius = maximumRadius - outwardStep
                radius += outwardStep
            }
            if (found == null) return emptyList()
            positions.add(found)
        }
        return positions.toList()
    }

    private fun identity(entity: Entity): MobIdentity? {
        val data = entity.persistentDataContainer
        val dungeonId = data.get(plugin.dungeonMobDungeonKey, PersistentDataType.STRING) ?: return null
        val roomId = data.get(plugin.dungeonMobRoomKey, PersistentDataType.STRING) ?: return null
        val tier = data.get(plugin.dungeonMobTierKey, PersistentDataType.INTEGER) ?: return null
        val difficulty = data.get(plugin.dungeonMobDifficultyKey, PersistentDataType.INTEGER) ?: return null
        val boss = data.get(plugin.dungeonMobBossKey, PersistentDataType.BYTE) == 1.toByte()
        val bossTheme = data.get(plugin.dungeonMobBossThemeKey, PersistentDataType.STRING)
        val category = data.get(plugin.dungeonMobCategoryKey, PersistentDataType.STRING)
        return MobIdentity(dungeonId, roomId, tier, difficulty, boss, bossTheme, category)
    }

    private fun isTestingMob(entity: Entity): Boolean =
        entity.persistentDataContainer.get(plugin.dungeonMobTestKey, PersistentDataType.BYTE) == 1.toByte()

    private enum class Role {
        SWARM, PACK, CHAMPION, GUARDIAN, RANGED, BRUISER, ELITE;

        fun configName(): String = name.lowercase(Locale.ROOT)

        companion object {
            fun category(raw: String?): Role? = try {
                val value = valueOf((raw ?: "").trim().uppercase(Locale.ROOT))
                if (value == SWARM || value == PACK || value == CHAMPION || value == GUARDIAN) value else null
            } catch (exception: IllegalArgumentException) {
                null
            }
        }
    }

    private data class MobIdentity(val dungeonId: String, val roomId: String, val tier: Int, val difficulty: Int,
                                   val boss: Boolean, val bossTheme: String?, val category: String?) {
        /** The public metadata shape, so no listener ever sees this class. */
        fun toInfo(): DungeonMobInfo = DungeonMobInfo(tier, difficulty, category ?: "", bossTheme ?: "", boss)
    }

    enum class TestSummonStatus {
        SUCCESS,
        INVALID_CATEGORY,
        INVALID_DIFFICULTY,
        INVALID_THEME,
        DISABLED_CATEGORY,
        NO_CLEARANCE
    }

    data class TestSummonResult(val status: TestSummonStatus, val count: Int, val theme: String)

    private data class TestMobLocation(val worldName: String, val x: Int, val y: Int, val z: Int) {
        companion object {
            fun from(location: Location): TestMobLocation =
                TestMobLocation(location.world!!.name, location.blockX, location.blockY, location.blockZ)
        }
    }

    private class RoomMobs {
        /** Woken by approach or entry, even while spawning is still deferred. */
        var visited = false
        var spawned = false
        var pendingSpawns = 0
        var cleared = false
        val entities = HashSet<UUID>()

        /** Spawned, nothing still queued, and nothing left alive. */
        fun isClear(): Boolean = spawned && pendingSpawns <= 0 && entities.isEmpty()
    }

    /** Native boss-bar state, kept separate from room counts and mob metadata. */
    private class ActiveBossBar(val dungeonId: String, val roomId: String, val worldName: String, val bar: BossBar) {
        val viewers = HashSet<UUID>()
        var shown = false
    }

    private class SplitContext(
        val location: Location,
        val identity: MobIdentity,
        val name: Component?,
        val nameVisible: Boolean,
        var remaining: Int,
        val expiresAtTick: Long
    )

    private data class Clearance(val width: Int, val height: Int) {
        companion object {
            fun read(config: FileConfiguration, type: EntityType): Clearance {
                val base = "mobs.entity-clearance.${type.name}."
                val width = config.getInt(base + "width", config.getInt("mobs.entity-clearance.default.width", 1))
                val height = config.getInt(base + "height", config.getInt("mobs.entity-clearance.default.height", 2))
                return Clearance(maxOf(1, width), maxOf(2, height))
            }
        }
    }

    private class DifficultySettings(val config: FileConfiguration, val difficulty: Int, val theme: String,
                                     val tier: Int, val scaling: Scaling) {

        fun categoryStats(category: Role): MobStats =
            MobStats.read(config, difficultyPath() + "categories." + category.configName() + ".")
                .scaled(scaling.healthFor(category), scaling.countFor(category))

        fun category(category: Role): CategoryDefinition {
            val path = "mobs.themes.$theme.categories.${category.configName()}."
            return CategoryDefinition(entity(config.getString(path + "entity", "ZOMBIE")),
                config.getString(path + "name", "") ?: "", config.getBoolean(path + "name-visible", true))
        }

        fun boss(): BossDefinition {
            val path = "mobs.themes.$theme.boss."
            val champion = categoryStats(Role.CHAMPION)
            val multiplier = maxOf(0.1, config.getDouble(path + "health-multiplier", 2.5))
            // Boss health is champion health times the theme's multiplier, so
            // it already carries the global health scaling, the champion's own
            // extra and the party multiplier. boss-health-extra compounds on
            // top of all three - that is exactly what it is for, and why it is
            // a separate number rather than folded into the champion's.
            val health = champion.health * multiplier * scaling.bossExtra
            return BossDefinition(config, path,
                MobStats.read(config, difficultyPath() + "boss.").withHealth(health),
                MobStats.read(config, difficultyPath() + "boss.minions.").scaled(scaling.health, 1.0))
        }

        private fun difficultyPath(): String = "mobs.difficulties.$difficulty."

        companion object {
            /** For paths with no dungeon behind them: test summons and damage lookups. */
            @JvmOverloads
            fun read(config: FileConfiguration, difficulty: Int, partySize: Int = 1): DifficultySettings {
                val path = "mobs.difficulties.$difficulty."
                return DifficultySettings(config, difficulty, config.getString(path + "theme", "crypt")!!,
                    maxOf(1, config.getInt(path + "tier", difficulty)), Scaling.read(config, partySize))
            }

            fun entity(value: String?): EntityType? = try {
                val type = EntityType.valueOf((value ?: "ZOMBIE").trim().uppercase(Locale.ROOT))
                if (type.entityClass != null && LivingEntity::class.java.isAssignableFrom(type.entityClass!!)) type else null
            } catch (exception: IllegalArgumentException) {
                null
            }
        }
    }

    /**
     * Every multiplier laid on top of the numbers in `mobs.difficulties`.
     *
     * Two halves. The base figures are the balance pass and are the same for
     * every run; the party figures depend on who is running and are fixed at
     * dungeon start.
     *
     * Health and count deliberately never both reach one category. They
     * multiply into total effective health, so a champion given both would be
     * roughly nine times the wall at four players against four times the
     * damage. Swarm and pack scale in numbers, champion, guardian and boss
     * scale in health, and each category has exactly one lever.
     */
    private class Scaling(val health: Double, val championExtra: Double, val guardianExtra: Double,
                          val bossExtra: Double, val swarmCount: Double, val partyCount: Double,
                          val partyHealth: Double) {

        fun healthFor(category: Role): Double = health * when (category) {
            Role.CHAMPION -> championExtra * partyHealth
            Role.GUARDIAN -> guardianExtra * partyHealth
            else -> 1.0
        }

        fun countFor(category: Role): Double = when (category) {
            Role.SWARM -> swarmCount * partyCount
            Role.PACK -> partyCount
            else -> 1.0
        }

        companion object {
            fun read(config: FileConfiguration, partySize: Int): Scaling {
                val path = "mobs.scaling."
                val capped = partySize.coerceIn(1, maxOf(1, config.getInt(path + "party.max-party-size", 8)))
                // 1 + (extra players x factor): a factor of 1.0 is fully
                // linear, below that every extra player counts for less than a
                // whole one.
                val count = 1.0 + (capped - 1) * maxOf(0.0, config.getDouble(path + "party.count-per-player", 0.6))
                val health = 1.0 + (capped - 1) * maxOf(0.0, config.getDouble(path + "party.health-per-player", 0.75))
                return Scaling(maxOf(0.01, config.getDouble(path + "health", 3.0)),
                    maxOf(0.01, config.getDouble(path + "champion-health-extra", 1.5)),
                    maxOf(0.01, config.getDouble(path + "guardian-health-extra", 1.5)),
                    maxOf(0.01, config.getDouble(path + "boss-health-extra", 1.5)),
                    maxOf(0.0, config.getDouble(path + "swarm-count-multiplier", 2.0)),
                    count, health)
            }
        }
    }

    private class CategoryDefinition(val type: EntityType?, val name: String, val nameVisible: Boolean)

    private class MarkerMember(val marker: DungeonMarker, val category: Role,
                               val definition: CategoryDefinition, val stats: MobStats)

    private class MobStats(val health: Double, val damage: Double, val speed: Double, val scale: Double,
                           val count: Int, val attackSpeed: Double, val knockbackResistance: Double,
                           val attackReach: Double, val weapon: String?) {

        fun withHealth(newHealth: Double): MobStats = MobStats(maxOf(1.0, newHealth), damage, speed, scale, count,
            attackSpeed, knockbackResistance, attackReach, weapon)

        /**
         * A count of 0 stays 0: an empty category is an authoring decision (a
         * deliberately quiet room), not a number to scale up from nothing.
         */
        fun scaled(healthMultiplier: Double, countMultiplier: Double): MobStats =
            MobStats(maxOf(1.0, health * healthMultiplier), damage, speed, scale,
                if (count <= 0) 0 else maxOf(1, Math.round(count * countMultiplier).toInt()),
                attackSpeed, knockbackResistance, attackReach, weapon)

        companion object {
            fun read(config: FileConfiguration, path: String): MobStats = MobStats(
                maxOf(1.0, config.getDouble(path + "health", 20.0)),
                maxOf(0.0, config.getDouble(path + "damage", 2.0)),
                maxOf(0.0, config.getDouble(path + "speed", 0.0)),
                maxOf(0.1, config.getDouble(path + "scale", 1.0)), maxOf(0, config.getInt(path + "count", 1)),
                maxOf(0.0, config.getDouble(path + "attack-speed", 0.0)),
                config.getDouble(path + "knockback-resistance", 0.0).coerceIn(0.0, 1.0),
                maxOf(2.5, config.getDouble(path + "attack-reach", config.getDouble(path + "scale", 1.0) * 2.5)),
                config.getString(path + "weapon", "AIR"))
        }
    }

    private class BossDefinition(val config: FileConfiguration, val path: String, val stats: MobStats,
                                 val minionStats: MobStats) {
        fun type(): EntityType = entity(config.getString(path + "type", "ZOMBIE"))
        fun name(): String = config.getString(path + "name", "") ?: ""
        fun nameVisible(): Boolean = config.getBoolean(path + "name-visible", true)
        fun barColor(): BossBar.Color = try {
            BossBar.Color.valueOf(config.getString(path + "bar-color", "WHITE")!!.uppercase(Locale.ROOT))
        } catch (ignored: IllegalArgumentException) {
            BossBar.Color.WHITE
        } catch (ignored: NullPointerException) {
            BossBar.Color.WHITE
        }
        fun minionCount(): Int = maxOf(0, config.getInt(path + "minion-count", 0))
        fun minionName(): String = config.getString(path + "minion-name", "") ?: ""
        fun minionNameVisible(): Boolean = config.getBoolean(path + "minion-name-visible", true)
        fun pickMinion(random: Random): EntityType? {
            val values = config.getStringList(path + "minions")
            return if (values.isEmpty()) null else entity(values[random.nextInt(values.size)])
        }
        fun copperStatues(): Boolean = config.getBoolean(path + "scenery.copper-golem-statues", false)
        fun statueMaterial(): String = config.getString(path + "scenery.material", "WAXED_OXIDIZED_COPPER")!!
        fun statueCount(): Int = maxOf(0, config.getInt(path + "scenery.count", 0))
        fun minionGap(): Double = maxOf(0.0, config.getDouble(path + "minion-gap", 0.35))
        fun minionMinimumSeparation(): Double = maxOf(0.1, config.getDouble(path + "minion-minimum-separation", 2.0))
        fun summoning(): BossSummoningSettings = BossSummoningSettings.read(config, path + "summoning.")

        companion object {
            fun entity(value: String?): EntityType = try {
                EntityType.valueOf(value!!.uppercase(Locale.ROOT))
            } catch (exception: RuntimeException) {
                EntityType.ZOMBIE
            }
        }
    }

    /** Configured audiovisual sequence used before a boss's delayed minions arrive. */
    private class BossSummoningSettings(
        val durationTicks: Int, val holdTicks: Int, val pulseInterval: Int, val particle: Particle,
        val particleCount: Int, val particleRadius: Double, val particleHeight: Double,
        val startSound: Sound, val peakSound: Sound, val soundVolume: Float, val soundPitch: Float,
        val invulnerable: Boolean, val animation: String?
    ) {
        companion object {
            fun read(config: FileConfiguration, bossPath: String): BossSummoningSettings {
                val common = "mobs.boss-summoning."
                return BossSummoningSettings(
                    maxOf(1, config.getInt(bossPath + "duration-ticks", config.getInt(common + "duration-ticks", 50))),
                    maxOf(0, config.getInt(bossPath + "hold-ticks", config.getInt(common + "hold-ticks", 12))),
                    maxOf(1, config.getInt(bossPath + "pulse-interval-ticks", config.getInt(common + "pulse-interval-ticks", 5))),
                    particle(config.getString(bossPath + "particle", config.getString(common + "particle", "SOUL_FIRE_FLAME"))),
                    maxOf(0, config.getInt(bossPath + "particle-count", config.getInt(common + "particle-count", 16))),
                    maxOf(0.0, config.getDouble(bossPath + "particle-radius", config.getDouble(common + "particle-radius", 1.5))),
                    maxOf(0.0, config.getDouble(bossPath + "particle-height", config.getDouble(common + "particle-height", 1.5))),
                    sound(config.getString(bossPath + "start-sound", config.getString(common + "start-sound", "ENTITY_WITHER_AMBIENT"))),
                    sound(config.getString(bossPath + "peak-sound", config.getString(common + "peak-sound", "ENTITY_WITHER_SPAWN"))),
                    maxOf(0.0, config.getDouble(bossPath + "sound-volume", config.getDouble(common + "sound-volume", 2.0))).toFloat(),
                    maxOf(0.0, config.getDouble(bossPath + "sound-pitch", config.getDouble(common + "sound-pitch", 1.0))).toFloat(),
                    config.getBoolean(bossPath + "invulnerable", config.getBoolean(common + "invulnerable", true)),
                    config.getString(bossPath + "animation", config.getString(common + "animation", "")))
            }

            private fun particle(raw: String?): Particle = try {
                Particle.valueOf(raw!!.uppercase(Locale.ROOT))
            } catch (ignored: RuntimeException) {
                Particle.SOUL_FIRE_FLAME
            }

            private fun sound(raw: String?): Sound {
                if (raw.isNullOrBlank()) return Sound.ENTITY_WITHER_AMBIENT
                return Registry.SOUNDS.get(NamespacedKey.minecraft(raw.lowercase(Locale.ROOT).replace('_', '.')))
                    ?: Sound.ENTITY_WITHER_AMBIENT
            }
        }
    }

    /** Runs once after arena entry. It completes even when the arena becomes empty. */
    private inner class BossSummoningSequence(
        private val bossId: UUID,
        private val boss: LivingEntity,
        val pending: PendingBossMinions
    ) : BukkitRunnable() {

        private val settings = pending.boss.summoning()
        private val animation: SpawnAnimation?
        private val previousAi = boss.hasAI()
        private val previousInvulnerability = boss.isInvulnerable
        private var ticks = 0
        private var completed = false
        private var animationFailed = false

        init {
            // Burying and raising the boss is only safe while it cannot be hurt.
            animation = SpawnAnimations.create(plugin, boss, settings.animation, settings.invulnerable)
        }

        fun begin() {
            boss.setAI(false)
            if (settings.invulnerable) boss.isInvulnerable = true
            play(settings.startSound)
            if (animation != null && !SpawnAnimations.beginSafely(plugin, animation)) {
                // A broken entrance must not take the fight down with it: the
                // sequence carries on and the boss simply arrives plainly.
                animationFailed = true
            }
            runTaskTimer(plugin, 1L, 1L)
        }

        override fun run() {
            if (completed) return
            if (boss.isDead || !boss.isValid) {
                completeNow()
                return
            }
            val yaw = boss.location.yaw + 12.0F
            boss.setRotation(yaw, 0.0F)
            if (animation != null && !animationFailed) {
                // A scripted entrance replaces the default pulse entirely; it
                // draws its own particles and moves the boss itself.
                animationFailed = !SpawnAnimations.tickSafely(plugin, animation, ticks, settings.durationTicks)
            } else if (ticks >= settings.holdTicks && ticks % settings.pulseInterval == 0) {
                val progress = minOf(1.0, (ticks + 1).toDouble() / settings.durationTicks)
                val count = maxOf(1, ceil(settings.particleCount * progress).toInt())
                val at = boss.location.add(0.0, settings.particleHeight, 0.0)
                boss.world.spawnParticle(settings.particle, at, count, settings.particleRadius,
                    settings.particleRadius * 0.5, settings.particleRadius, 0.01)
                boss.swingMainHand()
            }
            ticks++
            if (ticks >= settings.durationTicks) {
                play(settings.peakSound)
                completeNow()
            }
        }

        private fun play(sound: Sound) {
            boss.world.playSound(boss.location, sound, settings.soundVolume, settings.soundPitch)
        }

        fun blocksDamage(): Boolean = settings.invulnerable

        fun completeNow() {
            if (completed) return
            completed = true
            cancel()
            activeBossSummons.remove(bossId)
            pendingBossMinions.remove(bossId)
            // Runs before the AI is restored, so the boss is put back on its
            // arena floor before it can walk anywhere.
            if (animation != null && !animationFailed) animation.finish()
            if (!boss.isDead && boss.isValid) {
                boss.setAI(previousAi)
                boss.isInvulnerable = previousInvulnerability
            }
            pending.spawnNow()
        }

        /** Tears the sequence down without its outro, for a dungeon being removed. */
        fun abort() {
            completed = true
            cancel()
            animation?.abort()
        }
    }

    private inner class PendingBossMinions(
        private val bossId: UUID,
        val dungeon: DungeonInstance,
        val room: DungeonRoom,
        val settings: DifficultySettings,
        val boss: BossDefinition,
        private val centre: Location,
        private val bossType: EntityType
    ) {
        fun spawnNow() {
            val placed = ArrayList<Location>()
            for (index in 0 until boss.minionCount()) {
                val type = boss.pickMinion(random)
                val location = type?.let { ringLocation(it, index, placed) }
                if (location == null) {
                    plugin.logger.severe("Could not find any solid, clear minion location in boss room ${room.id}" +
                        " for $type; this arena needs more walkable space.")
                    continue
                }
                spawnAt(dungeon, room, settings, type, location, boss.minionStats, boss.minionName(), boss.minionNameVisible())
                placed.add(location)
            }
        }

        private fun ringLocation(type: EntityType, index: Int, placed: List<Location>): Location? {
            val minionClearance = Clearance.read(plugin.config, type)
            val entity = Bukkit.getEntity(bossId)
            val bossRadius = if (entity == null)
                Clearance.read(plugin.config, bossType).width * boss.stats.scale / 2.0
            else maxOf(entity.boundingBox.widthX, entity.boundingBox.widthZ) / 2.0
            val minionRadius = minionClearance.width * boss.minionStats.scale / 2.0
            val minions = maxOf(1, boss.minionCount())
            val separationRadius = boss.minionMinimumSeparation() / (2.0 * sin(Math.PI / minions))
            val radius = maxOf(bossRadius + minionRadius + boss.minionGap(), separationRadius)
            val scaled = Clearance(ceil(minionClearance.width * boss.minionStats.scale).toInt(),
                ceil(minionClearance.height * boss.minionStats.scale).toInt())
            val searchRadius = maxOf(1, plugin.config.getInt("mobs.boss-minions.alternate-search-radius", 16))
            for (nudge in 0..searchRadius) {
                // Keep the first choices on the intended retinue ring; broader
                // searching only happens when a plinth edge or wall blocks one.
                val angle = 2 * Math.PI * index / minions +
                    (if (nudge == 0) 0.0 else (if (nudge % 2 == 0) 1 else -1) * nudge * 0.16)
                val x = floor(centre.x + cos(angle) * (radius + nudge * 0.75)).toInt()
                val z = floor(centre.z + sin(angle) * (radius + nudge * 0.75)).toInt()
                val floor = floorBelow(x, z, scaled)
                if (floor != null && separated(floor, placed)) return floor
            }
            return nearestClearFloor(scaled, placed)
        }

        private fun floorBelow(x: Int, z: Int, clearance: Clearance): Location? {
            val bounds = room.bounds
            if (x < bounds.minX || x > bounds.maxX || z < bounds.minZ || z > bounds.maxZ) return null
            val down = maxOf(1, plugin.config.getInt("mobs.boss-minions.floor-search-down", 12))
            val lowest = maxOf(bounds.minY, centre.blockY - down)
            for (floorY in centre.blockY - 1 downTo lowest) {
                val feetY = floorY + 1
                if (hasClearance(dungeon.world, x, feetY, z, clearance)) {
                    return Location(dungeon.world, x + .5, feetY.toDouble(), z + .5)
                }
            }
            return null
        }

        private fun nearestClearFloor(clearance: Clearance, placed: List<Location>): Location? {
            val bounds = room.bounds
            var best: Location? = null
            var bestDistance = Double.MAX_VALUE
            for (x in bounds.minX + 1 until bounds.maxX) {
                for (z in bounds.minZ + 1 until bounds.maxZ) {
                    val floor = floorBelow(x, z, clearance) ?: continue
                    if (!separated(floor, placed)) continue
                    val distance = floor.distanceSquared(centre)
                    if (distance < bestDistance) {
                        best = floor
                        bestDistance = distance
                    }
                }
            }
            return best
        }

        private fun separated(location: Location, placed: List<Location>): Boolean {
            val minimum = maxOf(0.5, boss.minionMinimumSeparation())
            return placed.all { it.distanceSquared(location) >= minimum * minimum }
        }
    }

    private fun spawnAt(dungeon: DungeonInstance, room: DungeonRoom, settings: DifficultySettings, type: EntityType,
                        location: Location, stats: MobStats, name: String?, nameVisible: Boolean) {
        val entity = dungeon.world.spawnEntity(location, type)
        if (entity is LivingEntity) prepareDungeonMob(entity, dungeon, room, settings, Role.ELITE, stats, false, null, name, nameVisible)
        else entity.remove()
    }

    private class HostileMeleeGoal<T : Mob>(
        private val mob: T,
        private val damage: Double,
        reach: Double,
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
            if (distance > reachSquared) mob.pathfinder.moveTo(target, 1.0)
            else if (cooldown-- <= 0) {
                target.damage(damage, mob)
                cooldown = 20
            }
        }

        private fun nearest(): Player? =
            mob.world.players.minByOrNull { it.location.distanceSquared(mob.location) }

        override fun getKey(): GoalKey<T> = key
        override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.LOOK, GoalType.TARGET)
    }

    companion object {
        /** How far above the layout floor an arena may raise a platform the boss can stand on. */
        private const val BOSS_FLOOR_SEARCH_UP = 6

        /** Stops the vanilla conversion timer itself, avoiding the visual shiver it causes. */
        private fun preventZombification(entity: LivingEntity) {
            if (entity is Hoglin) entity.isImmuneToZombification = true
            if (entity is PiglinAbstract) entity.isImmuneToZombification = true
        }

        /**
         * Finds the clear floor nearest arena centre when an explicit marker
         * is intentionally absent.
         *
         * The search runs upward from the room's own floor and takes the
         * lowest standing spot in each column. Searching downward from the
         * ceiling looks equivalent but is not: an arena roof has solid blocks
         * below it and open sky above, which satisfies every clearance test,
         * so the boss would be placed on top of the building.
         */
        private fun automaticBossCentre(world: World, room: DungeonRoom, clearance: Clearance): Location? {
            val bounds = room.bounds
            val highest = minOf(bounds.maxY, room.floorY + BOSS_FLOOR_SEARCH_UP)
            var best: Location? = null
            var bestDistance = Int.MAX_VALUE
            var bestY = Int.MAX_VALUE
            for (x in bounds.minX + 1 until bounds.maxX) {
                for (z in bounds.minZ + 1 until bounds.maxZ) {
                    val distance = Math.abs(x - bounds.centreX()) + Math.abs(z - bounds.centreZ())
                    if (distance > bestDistance) continue
                    for (feetY in room.floorY..highest) {
                        if (!hasClearance(world, x, feetY, z, clearance)) continue
                        if (distance < bestDistance || feetY < bestY) {
                            best = Location(world, x + 0.5, feetY.toDouble(), z + 0.5)
                            bestDistance = distance
                            bestY = feetY
                        }
                        break
                    }
                }
            }
            return best
        }

        private fun setAttribute(entity: LivingEntity, attribute: Attribute, value: Double) {
            entity.getAttribute(attribute)?.baseValue = value
        }

        private fun zeroEquipmentDropChances(entity: LivingEntity) {
            val equipment = entity.equipment ?: return
            equipment.itemInMainHandDropChance = 0.0F
            equipment.itemInOffHandDropChance = 0.0F
            equipment.helmetDropChance = 0.0F
            equipment.chestplateDropChance = 0.0F
            equipment.leggingsDropChance = 0.0F
            equipment.bootsDropChance = 0.0F
        }

        private fun clearArmor(entity: LivingEntity) {
            val equipment = entity.equipment ?: return
            equipment.setHelmet(null)
            equipment.setChestplate(null)
            equipment.setLeggings(null)
            equipment.setBoots(null)
        }

        private fun isDamagingProjectile(projectile: Projectile): Boolean = when (projectile.type) {
            EntityType.ARROW, EntityType.SPECTRAL_ARROW, EntityType.TRIDENT, EntityType.FIREBALL,
            EntityType.SMALL_FIREBALL, EntityType.WITHER_SKULL, EntityType.WIND_CHARGE -> true
            else -> false
        }

        private fun projectileDamage(projectile: Projectile): Double {
            if (projectile is AbstractArrow) return maxOf(0.0, projectile.damage)
            // Non-arrow projectiles do not expose a single native damage
            // value. This keeps their direct, cancellable damage hook useful
            // to gameplay code while providing a sensible vanilla-style
            // fallback.
            return 2.0
        }

        private fun hasClearance(world: World, x: Int, y: Int, z: Int, clearance: Clearance): Boolean {
            val minimumOffset = -(clearance.width / 2)
            val maximumOffset = (clearance.width - 1) / 2
            for (offsetX in minimumOffset..maximumOffset) {
                for (offsetZ in minimumOffset..maximumOffset) {
                    if (!world.getBlockAt(x + offsetX, y - 1, z + offsetZ).type.isSolid) return false
                    for (offsetY in 0 until clearance.height) {
                        if (!world.getBlockAt(x + offsetX, y + offsetY, z + offsetZ).isPassable) return false
                    }
                }
            }
            return true
        }

        /** Lowest available headroom across the entire scaled footprint. */
        private fun availableHeight(world: World, x: Int, y: Int, z: Int, clearance: Clearance): Int {
            val minimumOffset = -(clearance.width / 2)
            val maximumOffset = (clearance.width - 1) / 2
            var available = Int.MAX_VALUE
            for (offsetX in minimumOffset..maximumOffset) {
                for (offsetZ in minimumOffset..maximumOffset) {
                    if (!world.getBlockAt(x + offsetX, y - 1, z + offsetZ).type.isSolid) return 0
                    var column = 0
                    while (column < clearance.height && world.getBlockAt(x + offsetX, y + column, z + offsetZ).isPassable) column++
                    available = minOf(available, column)
                }
            }
            return if (available == Int.MAX_VALUE) 0 else available
        }

        private fun nearDoorway(dungeon: DungeonInstance, room: DungeonRoom, x: Int, y: Int, z: Int): Boolean =
            dungeon.tunnels
                .filter { it.firstRoomId == room.id || it.secondRoomId == room.id }
                .map { if (it.firstRoomId == room.id) it.firstDoorway else it.secondDoorway }
                .any { it.expand(3).contains(x, y, z) }

        private fun markerKey(marker: DungeonMarker): String =
            "${marker.category}:${marker.x}:${marker.y}:${marker.z}"

        private fun attribute(entity: LivingEntity, attribute: Attribute): Double =
            entity.getAttribute(attribute)?.value ?: 0.0

        private fun collidesWithBlocks(entity: Entity): Boolean {
            val box = entity.boundingBox
            for (x in floor(box.minX).toInt()..floor(box.maxX).toInt())
                for (y in floor(box.minY).toInt()..floor(box.maxY).toInt())
                    for (z in floor(box.minZ).toInt()..floor(box.maxZ).toInt()) {
                        val block = entity.world.getBlockAt(x, y, z)
                        if (block.type.isSolid && block.boundingBox.overlaps(box)) return true
                    }
            return false
        }
    }
}
