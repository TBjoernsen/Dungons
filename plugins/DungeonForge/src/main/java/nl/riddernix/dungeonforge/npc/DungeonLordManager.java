package nl.riddernix.dungeonforge.npc;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.Component;
import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Stores, restores, and protects persistent Dungeon Lord entities. */
public final class DungeonLordManager {

    private final DungeonForgePlugin plugin;
    private final File storageFile;
    private final Map<String, DungeonLord> lords = new HashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private YamlConfiguration storage;

    public DungeonLordManager(DungeonForgePlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "npcs.yml");
    }

    /** Loads saved Dungeon Lords and makes sure each one is present. */
    public void load() {
        storage = YamlConfiguration.loadConfiguration(storageFile);
        lords.clear();
        ConfigurationSection section = storage.getConfigurationSection("lords");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            DungeonLord lord = entry == null ? null : read(entry);
            if (lord != null) {
                lords.put(id, lord);
            } else {
                plugin.getLogger().warning("Ignoring invalid Dungeon Lord entry '" + id + "' in npcs.yml.");
            }
        }
        maintain();
    }

    /** Applies fresh configuration and restores any entity that disappeared. */
    public void reload() {
        maintain();
    }

    /** Creates one Dungeon Lord at the supplied exact location. */
    public Optional<Entity> spawn(Location location) {
        Entity entity = spawnConfigured(location);
        if (entity == null) {
            return Optional.empty();
        }
        String id = UUID.randomUUID().toString();
        entity.getPersistentDataContainer().set(plugin.dungeonLordKey(), PersistentDataType.STRING, id);
        lords.put(id, new DungeonLord(entity.getUniqueId(), location.clone()));
        save();
        return Optional.of(entity);
    }

    /** Removes the closest configured Dungeon Lord within the configured radius. */
    public boolean removeNearest(Location location) {
        double radius = Math.max(1.0, plugin.getConfig().getDouble("npc.remove-radius", 5.0));
        double maximumDistanceSquared = radius * radius;
        String nearestId = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (Map.Entry<String, DungeonLord> entry : lords.entrySet()) {
            DungeonLord lord = entry.getValue();
            if (!lord.location().getWorld().equals(location.getWorld())) {
                continue;
            }
            double distanceSquared = lord.location().distanceSquared(location);
            if (distanceSquared <= maximumDistanceSquared && distanceSquared < nearestDistanceSquared) {
                nearestId = entry.getKey();
                nearestDistanceSquared = distanceSquared;
            }
        }
        if (nearestId == null) {
            return removeNearestLegacy(location, maximumDistanceSquared);
        }
        DungeonLord removed = lords.remove(nearestId);
        removeEntityAtSavedLocation(removed);
        save();
        return true;
    }

    /** Removes every saved Dungeon Lord, including entities in previously unloaded chunks. */
    public RemovalReport removeAll() {
        List<Location> removedLocations = new ArrayList<>();
        for (DungeonLord lord : new ArrayList<>(lords.values())) {
            if (removeEntityAtSavedLocation(lord)) removedLocations.add(lord.location().clone());
        }
        lords.clear();

        // Catch tagged or legacy Lords which were never written to npcs.yml.
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                if (!isDungeonLord(entity) && !isLegacyDungeonLord(entity)) continue;
                removedLocations.add(entity.getLocation().clone());
                entity.remove();
            }
        }
        save();
        return new RemovalReport(removedLocations.size(), List.copyOf(removedLocations));
    }

    /** Keeps every saved Dungeon Lord spawned, configured, and at its saved position. */
    public void maintain() {
        boolean changed = false;
        for (Map.Entry<String, DungeonLord> entry : lords.entrySet()) {
            Entity entity = Bukkit.getEntity(entry.getValue().entityId());
            if (entity == null || !entity.isValid()) {
                Entity replacement = spawnConfigured(entry.getValue().location());
                if (replacement == null) {
                    continue;
                }
                replacement.getPersistentDataContainer().set(plugin.dungeonLordKey(), PersistentDataType.STRING, entry.getKey());
                entry.setValue(new DungeonLord(replacement.getUniqueId(), entry.getValue().location()));
                changed = true;
                continue;
            }
            configure(entity);
            if (!sameLocation(entity.getLocation(), entry.getValue().location())) {
                entity.teleport(entry.getValue().location());
            }
        }
        if (changed) {
            save();
        }
    }

    /** Returns whether an entity belongs to this manager. */
    public boolean isDungeonLord(Entity entity) {
        return entity.getPersistentDataContainer().has(plugin.dungeonLordKey(), PersistentDataType.STRING)
                || isLegacyDungeonLord(entity);
    }

    private boolean removeNearestLegacy(Location location, double maximumDistanceSquared) {
        Entity nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (Entity entity : location.getWorld().getEntities()) {
            if (!isLegacyDungeonLord(entity)) continue;
            double distanceSquared = entity.getLocation().distanceSquared(location);
            if (distanceSquared <= maximumDistanceSquared && distanceSquared < nearestDistanceSquared) {
                nearest = entity;
                nearestDistanceSquared = distanceSquared;
            }
        }
        if (nearest == null) return false;
        nearest.remove();
        return true;
    }

    /** Loads the exact saved chunk before resolving the entity UUID or legacy fallback. */
    private boolean removeEntityAtSavedLocation(DungeonLord lord) {
        World world = lord.location().getWorld();
        if (world == null) return false;
        Chunk chunk = world.getChunkAt(lord.location().getBlockX() >> 4, lord.location().getBlockZ() >> 4);
        Entity entity = Bukkit.getEntity(lord.entityId());
        if (entity == null || !entity.getWorld().equals(world)) {
            entity = java.util.Arrays.stream(chunk.getEntities())
                    .filter(this::isLegacyDungeonLord)
                    .min(java.util.Comparator.comparingDouble(candidate -> candidate.getLocation().distanceSquared(lord.location())))
                    .orElse(null);
        }
        if (entity == null) return false;
        entity.remove();
        return true;
    }

    /** Identifies pre-PDC NPCs by the configured entity type and MiniMessage display name. */
    private boolean isLegacyDungeonLord(Entity entity) {
        if (entity.getType() != configuredType()) return false;
        Component expectedName = miniMessage.deserialize(plugin.getConfig().getString("npc.display-name", "<gold>Dungeon Lord"));
        return Objects.equals(entity.customName(), expectedName);
    }

    private Entity spawnConfigured(Location location) {
        if (location.getWorld() == null) {
            return null;
        }
        EntityType type = configuredType();
        try {
            Entity entity = location.getWorld().spawnEntity(location, type);
            configure(entity);
            return entity;
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Could not spawn Dungeon Lord entity type " + type + ": " + ex.getMessage());
            return null;
        }
    }

    private EntityType configuredType() {
        String configured = plugin.getConfig().getString("npc.entity-type", "VILLAGER");
        try {
            EntityType type = EntityType.valueOf(configured.toUpperCase(Locale.ROOT));
            if (type.isSpawnable() && type.isAlive()) {
                return type;
            }
        } catch (IllegalArgumentException ignored) {
            // The warning below covers unknown and unusable types alike.
        }
        plugin.getLogger().warning("npc.entity-type must be a spawnable living entity; using VILLAGER instead.");
        return EntityType.VILLAGER;
    }

    private void configure(Entity entity) {
        String name = plugin.getConfig().getString("npc.display-name", "<gold>Dungeon Lord");
        entity.customName(miniMessage.deserialize(name));
        entity.setCustomNameVisible(plugin.getConfig().getBoolean("npc.name-visible", true));
        entity.setInvulnerable(true);
        entity.setPersistent(true);
        entity.setGravity(false);
        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.setAI(false);
            livingEntity.setRemoveWhenFarAway(false);
            livingEntity.setCollidable(false);
        }
    }

    private DungeonLord read(ConfigurationSection entry) {
        try {
            UUID entityId = UUID.fromString(entry.getString("entity-uuid", ""));
            World world = Bukkit.getWorld(entry.getString("world", ""));
            if (world == null) {
                return null;
            }
            Location location = new Location(world, entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"),
                    (float) entry.getDouble("yaw"), (float) entry.getDouble("pitch"));
            return new DungeonLord(entityId, location);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void save() {
        if (storage == null) {
            storage = new YamlConfiguration();
        }
        storage.set("lords", null);
        for (Map.Entry<String, DungeonLord> entry : lords.entrySet()) {
            String path = "lords." + entry.getKey();
            DungeonLord lord = entry.getValue();
            storage.set(path + ".entity-uuid", lord.entityId().toString());
            storage.set(path + ".world", lord.location().getWorld().getName());
            storage.set(path + ".x", lord.location().getX());
            storage.set(path + ".y", lord.location().getY());
            storage.set(path + ".z", lord.location().getZ());
            storage.set(path + ".yaw", lord.location().getYaw());
            storage.set(path + ".pitch", lord.location().getPitch());
        }
        try {
            storage.save(storageFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save npcs.yml: " + ex.getMessage());
        }
    }

    private static boolean sameLocation(Location first, Location second) {
        return first.getWorld().equals(second.getWorld())
                && first.distanceSquared(second) < 0.0001
                && Math.abs(first.getYaw() - second.getYaw()) < 0.01F
                && Math.abs(first.getPitch() - second.getPitch()) < 0.01F;
    }

    private record DungeonLord(UUID entityId, Location location) {
    }

    /** Summary used by the administrative remove-all command. */
    public record RemovalReport(int count, List<Location> locations) { }
}
