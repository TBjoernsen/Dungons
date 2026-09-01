package nl.riddernix.dungeonplugin.npc

import net.kyori.adventure.text.minimessage.MiniMessage
import nl.riddernix.dungeonplugin.DungeonPlugin
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/** Stores, restores, and protects persistent Dungeon Lord entities. */
class DungeonLordManager(private val plugin: DungeonPlugin) {

    private val storageFile = File(plugin.dataFolder, "npcs.yml")
    private val lords = HashMap<String, DungeonLord>()
    private val miniMessage = MiniMessage.miniMessage()
    private var storage: YamlConfiguration? = null

    /** Loads saved Dungeon Lords and makes sure each one is present. */
    fun load() {
        storage = YamlConfiguration.loadConfiguration(storageFile)
        lords.clear()
        val section = storage!!.getConfigurationSection("lords") ?: return
        for (id in section.getKeys(false)) {
            val entry = section.getConfigurationSection(id)
            val lord = entry?.let { read(it) }
            if (lord != null) {
                lords[id] = lord
            } else {
                plugin.logger.warning("Ignoring invalid Dungeon Lord entry '$id' in npcs.yml.")
            }
        }
        maintain()
    }

    /** Applies fresh configuration and restores any entity that disappeared. */
    fun reload() {
        maintain()
    }

    /** Creates one Dungeon Lord at the supplied exact location. */
    fun spawn(location: Location): Entity? {
        val entity = spawnConfigured(location) ?: return null
        val id = UUID.randomUUID().toString()
        entity.persistentDataContainer.set(plugin.dungeonLordKey, PersistentDataType.STRING, id)
        lords[id] = DungeonLord(entity.uniqueId, location.clone())
        save()
        return entity
    }

    /** Removes the closest configured Dungeon Lord within the configured radius. */
    fun removeNearest(location: Location): Boolean {
        val radius = maxOf(1.0, plugin.config.getDouble("npc.remove-radius", 5.0))
        val maximumDistanceSquared = radius * radius
        var nearestId: String? = null
        var nearestDistanceSquared = Double.MAX_VALUE
        for ((id, lord) in lords) {
            if (lord.location.world != location.world) {
                continue
            }
            val distanceSquared = lord.location.distanceSquared(location)
            if (distanceSquared <= maximumDistanceSquared && distanceSquared < nearestDistanceSquared) {
                nearestId = id
                nearestDistanceSquared = distanceSquared
            }
        }
        if (nearestId == null) {
            return removeNearestLegacy(location, maximumDistanceSquared)
        }
        val removed = lords.remove(nearestId)
        removed?.let { removeEntityAtSavedLocation(it) }
        save()
        return true
    }

    /** Removes every saved Dungeon Lord, including entities in previously unloaded chunks. */
    fun removeAll(): RemovalReport {
        val removedLocations = ArrayList<Location>()
        for (lord in lords.values.toList()) {
            if (removeEntityAtSavedLocation(lord)) removedLocations.add(lord.location.clone())
        }
        lords.clear()

        // Catch tagged or legacy Lords which were never written to npcs.yml.
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities.toList()) {
                if (!isDungeonLord(entity) && !isLegacyDungeonLord(entity)) continue
                removedLocations.add(entity.location.clone())
                entity.remove()
            }
        }
        save()
        return RemovalReport(removedLocations.size, removedLocations.toList())
    }

    /** Keeps every saved Dungeon Lord spawned, configured, and at its saved position. */
    fun maintain() {
        var changed = false
        for (entry in lords.entries) {
            val entity = Bukkit.getEntity(entry.value.entityId)
            if (entity == null || !entity.isValid) {
                val replacement = spawnConfigured(entry.value.location) ?: continue
                replacement.persistentDataContainer.set(plugin.dungeonLordKey, PersistentDataType.STRING, entry.key)
                entry.setValue(DungeonLord(replacement.uniqueId, entry.value.location))
                changed = true
                continue
            }
            configure(entity)
            if (!sameLocation(entity.location, entry.value.location)) {
                entity.teleport(entry.value.location)
            }
        }
        if (changed) {
            save()
        }
    }

    /** Returns whether an entity belongs to this manager. */
    fun isDungeonLord(entity: Entity): Boolean =
        entity.persistentDataContainer.has(plugin.dungeonLordKey, PersistentDataType.STRING) ||
            isLegacyDungeonLord(entity)

    private fun removeNearestLegacy(location: Location, maximumDistanceSquared: Double): Boolean {
        var nearest: Entity? = null
        var nearestDistanceSquared = Double.MAX_VALUE
        for (entity in location.world!!.entities) {
            if (!isLegacyDungeonLord(entity)) continue
            val distanceSquared = entity.location.distanceSquared(location)
            if (distanceSquared <= maximumDistanceSquared && distanceSquared < nearestDistanceSquared) {
                nearest = entity
                nearestDistanceSquared = distanceSquared
            }
        }
        if (nearest == null) return false
        nearest.remove()
        return true
    }

    /** Loads the exact saved chunk before resolving the entity UUID or legacy fallback. */
    private fun removeEntityAtSavedLocation(lord: DungeonLord): Boolean {
        val world = lord.location.world ?: return false
        val chunk = world.getChunkAt(lord.location.blockX shr 4, lord.location.blockZ shr 4)
        var entity = Bukkit.getEntity(lord.entityId)
        if (entity == null || entity.world != world) {
            entity = chunk.entities
                .filter { isLegacyDungeonLord(it) }
                .minByOrNull { it.location.distanceSquared(lord.location) }
        }
        if (entity == null) return false
        entity.remove()
        return true
    }

    /** Identifies pre-PDC NPCs by the configured entity type and MiniMessage display name. */
    private fun isLegacyDungeonLord(entity: Entity): Boolean {
        if (entity.type != configuredType()) return false
        val expectedName = miniMessage.deserialize(plugin.config.getString("npc.display-name", "<gold>Dungeon Lord")!!)
        return entity.customName() == expectedName
    }

    private fun spawnConfigured(location: Location): Entity? {
        if (location.world == null) {
            return null
        }
        val type = configuredType()
        return try {
            val entity = location.world!!.spawnEntity(location, type)
            configure(entity)
            entity
        } catch (ex: IllegalArgumentException) {
            plugin.logger.warning("Could not spawn Dungeon Lord entity type $type: ${ex.message}")
            null
        }
    }

    private fun configuredType(): EntityType {
        val configured = plugin.config.getString("npc.entity-type", "VILLAGER")!!
        try {
            val type = EntityType.valueOf(configured.uppercase(Locale.ROOT))
            if (type.isSpawnable && type.isAlive) {
                return type
            }
        } catch (ignored: IllegalArgumentException) {
            // The warning below covers unknown and unusable types alike.
        }
        plugin.logger.warning("npc.entity-type must be a spawnable living entity; using VILLAGER instead.")
        return EntityType.VILLAGER
    }

    private fun configure(entity: Entity) {
        val name = plugin.config.getString("npc.display-name", "<gold>Dungeon Lord")!!
        entity.customName(miniMessage.deserialize(name))
        entity.isCustomNameVisible = plugin.config.getBoolean("npc.name-visible", true)
        entity.isInvulnerable = true
        entity.isPersistent = true
        entity.setGravity(false)
        if (entity is LivingEntity) {
            entity.setAI(false)
            entity.setRemoveWhenFarAway(false)
            entity.isCollidable = false
        }
    }

    private fun read(entry: ConfigurationSection): DungeonLord? {
        return try {
            val entityId = UUID.fromString(entry.getString("entity-uuid", "")!!)
            val world = Bukkit.getWorld(entry.getString("world", "")!!) ?: return null
            val location = Location(world, entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"),
                entry.getDouble("yaw").toFloat(), entry.getDouble("pitch").toFloat())
            DungeonLord(entityId, location)
        } catch (ex: IllegalArgumentException) {
            null
        }
    }

    private fun save() {
        val storage = storage ?: YamlConfiguration().also { storage = it }
        storage.set("lords", null)
        for ((id, lord) in lords) {
            val path = "lords.$id"
            storage.set("$path.entity-uuid", lord.entityId.toString())
            storage.set("$path.world", lord.location.world!!.name)
            storage.set("$path.x", lord.location.x)
            storage.set("$path.y", lord.location.y)
            storage.set("$path.z", lord.location.z)
            storage.set("$path.yaw", lord.location.yaw)
            storage.set("$path.pitch", lord.location.pitch)
        }
        try {
            storage.save(storageFile)
        } catch (ex: IOException) {
            plugin.logger.severe("Could not save npcs.yml: ${ex.message}")
        }
    }

    private data class DungeonLord(val entityId: UUID, val location: Location)

    /** Summary used by the administrative remove-all command. */
    data class RemovalReport(val count: Int, val locations: List<Location>)

    companion object {
        private fun sameLocation(first: Location, second: Location): Boolean =
            first.world == second.world &&
                first.distanceSquared(second) < 0.0001 &&
                abs(first.yaw - second.yaw) < 0.01F &&
                abs(first.pitch - second.pitch) < 0.01F
    }
}

/** Points players at the difficulty panel and blocks all damage to the Lord. */
class DungeonLordListener(private val plugin: DungeonPlugin) : Listener {

    @EventHandler
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (!plugin.dungeonLords.isDungeonLord(event.rightClicked)) {
            return
        }
        event.isCancelled = true
        // The chest menu is retired; the Lord now directs players to the
        // fixed difficulty panel standing in the world.
        plugin.panels.sendLocator(event.player)
    }

    @EventHandler
    fun onDamage(event: EntityDamageEvent) {
        if (plugin.dungeonLords.isDungeonLord(event.entity)) {
            event.isCancelled = true
        }
    }
}
