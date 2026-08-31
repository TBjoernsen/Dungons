package nl.riddernix.dungeonforge.mob;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.bossbar.BossBar;
import com.destroystokyo.paper.event.entity.EndermanEscapeEvent;
import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.api.DungeonMobInfo;
import nl.riddernix.dungeonforge.fx.SpawnAnimation;
import nl.riddernix.dungeonforge.fx.SpawnAnimations;
import nl.riddernix.dungeonforge.generation.Bounds;
import nl.riddernix.dungeonforge.generation.DungeonLayout;
import nl.riddernix.dungeonforge.room.DungeonCorridorEnterEvent;
import nl.riddernix.dungeonforge.room.DungeonInstance;
import nl.riddernix.dungeonforge.room.DungeonRoom;
import nl.riddernix.dungeonforge.room.DungeonRoomEnterEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Breeze;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Vex;
import org.bukkit.entity.Warden;
import org.bukkit.entity.Player;
import org.bukkit.entity.Mob;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.Projectile;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import io.papermc.paper.world.WeatheringCopperState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.EnumSet;
import nl.riddernix.dungeonforge.room.DungeonMarkerDefinitions;

/** Marker-driven room groups with tagged, persistent dungeon mobs. */
public final class DungeonMobManager implements Listener {
    private final DungeonForgePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Random random = new Random();
    private final Map<String, Map<String, RoomMobs>> mobs = new HashMap<>();
    private final Map<UUID, List<SplitContext>> pendingSplits = new HashMap<>();
    private final Map<UUID, PendingBossMinions> pendingBossMinions = new HashMap<>();
    private final Map<UUID, BossSummoningSequence> activeBossSummons = new HashMap<>();
    private final Map<UUID, ActiveBossBar> bossBars = new HashMap<>();
    private final Map<UUID, TestMobLocation> testingMobs = new HashMap<>();
    private final File testingMobsFile;

    public DungeonMobManager(DungeonForgePlugin plugin) {
        this.plugin = plugin;
        this.testingMobsFile = new File(plugin.getDataFolder(), "testing-mobs.yml");
        loadTestingMobLocations();
    }

    /** Returns every configured marker definition for command and build consumers. */
    public List<DungeonMarkerDefinitions.Definition> markerDefinitions() {
        return DungeonMarkerDefinitions.read(plugin.getConfig(), plugin);
    }

    public List<String> markerCategories() {
        return markerDefinitions().stream().map(DungeonMarkerDefinitions.Definition::category).toList();
    }

    /** The category names accepted by the combat spawner. */
    public List<String> combatCategories() {
        return markerCategories().stream().filter(category -> Role.category(category) != null).toList();
    }

    public List<String> themes() {
        var section = plugin.getConfig().getConfigurationSection("mobs.themes");
        return section == null ? List.of() : section.getKeys(false).stream().sorted().toList();
    }

    /** Spawns one configured category group for safe inspection outside a dungeon. */
    public TestSummonResult summonTestingGroup(Location origin, String rawCategory, int difficulty, String requestedTheme) {
        Role category = Role.category(rawCategory);
        if (category == null) return new TestSummonResult(TestSummonStatus.INVALID_CATEGORY, 0, "");
        if (difficulty < 1 || difficulty > 9) return new TestSummonResult(TestSummonStatus.INVALID_DIFFICULTY, 0, "");

        DifficultySettings defaultSettings = DifficultySettings.read(plugin.getConfig(), difficulty);
        String theme = requestedTheme == null || requestedTheme.isBlank() ? defaultSettings.theme() : requestedTheme.toLowerCase(Locale.ROOT);
        if (!themes().contains(theme)) return new TestSummonResult(TestSummonStatus.INVALID_THEME, 0, theme);
        DifficultySettings settings = new DifficultySettings(plugin.getConfig(), difficulty, theme,
                defaultSettings.tier(), defaultSettings.scaling());
        CategoryDefinition definition = settings.category(category);
        if (definition.type() == null) return new TestSummonResult(TestSummonStatus.INVALID_CATEGORY, 0, theme);
        MobStats stats = settings.categoryStats(category);
        if (stats.count() <= 0) return new TestSummonResult(TestSummonStatus.DISABLED_CATEGORY, 0, theme);

        List<Location> locations = testingLocations(origin, definition.type(), stats, stats.count());
        if (locations.size() != stats.count()) return new TestSummonResult(TestSummonStatus.NO_CLEARANCE, 0, theme);
        int spawned = 0;
        for (Location location : locations) {
            Entity entity = location.getWorld().spawnEntity(location, definition.type());
            if (!(entity instanceof LivingEntity living)) {
                entity.remove();
                continue;
            }
            prepareTestingMob(living, settings, category, stats, definition);
            testingMobs.put(living.getUniqueId(), TestMobLocation.from(living.getLocation()));
            spawned++;
        }
        saveTestingMobLocations();
        return new TestSummonResult(TestSummonStatus.SUCCESS, spawned, theme);
    }

    /** Removes all recorded testing mobs, loading their stored chunks first. */
    public int removeTestingMobs() {
        int removed = 0;
        for (Map.Entry<UUID, TestMobLocation> entry : new ArrayList<>(testingMobs.entrySet())) {
            TestMobLocation stored = entry.getValue();
            World world = Bukkit.getWorld(stored.worldName());
            if (world == null) continue;
            world.getChunkAt(stored.x() >> 4, stored.z() >> 4);
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity != null && isTestingMob(entity)) {
                entity.remove();
                removed++;
            }
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                if (!isTestingMob(entity)) continue;
                entity.remove();
                removed++;
            }
        }
        testingMobs.clear();
        saveTestingMobLocations();
        return removed;
    }

    /** Saves the latest known locations so testing mobs remain removable after a restart. */
    public void saveTestingMobLocations() {
        refreshTestingMobLocations();
        YamlConfiguration saved = new YamlConfiguration();
        for (Map.Entry<UUID, TestMobLocation> entry : testingMobs.entrySet()) {
            String path = entry.getKey().toString();
            TestMobLocation location = entry.getValue();
            saved.set(path + ".world", location.worldName());
            saved.set(path + ".x", location.x());
            saved.set(path + ".y", location.y());
            saved.set(path + ".z", location.z());
        }
        try {
            saved.save(testingMobsFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save testing mob locations: " + exception.getMessage());
        }
    }

    private void loadTestingMobLocations() {
        if (!testingMobsFile.isFile()) return;
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(testingMobsFile);
        for (String rawId : saved.getKeys(false)) {
            try {
                UUID id = UUID.fromString(rawId);
                String path = rawId + ".";
                String world = saved.getString(path + "world");
                if (world != null) testingMobs.put(id, new TestMobLocation(world, saved.getInt(path + "x"),
                        saved.getInt(path + "y"), saved.getInt(path + "z")));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid testing mob entry '" + rawId + "'.");
            }
        }
    }

    private void refreshTestingMobLocations() {
        testingMobs.entrySet().removeIf(entry -> {
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity == null) return false;
            if (!isTestingMob(entity) || entity.isDead() || !entity.isValid()) return true;
            entry.setValue(TestMobLocation.from(entity.getLocation()));
            return false;
        });
    }

    @EventHandler
    public void onRoomEnter(DungeonRoomEnterEvent event) {
        spawnRoom(event.getDungeon(), event.getRoom());
        if (event.getRoom().type() == DungeonLayout.RoomType.BOSS) {
            // The arena entry is the first committed moment of the fight, so
            // the boss bar appears together with the summoning sequence.
            showBossBars(event.getDungeon(), event.getRoom());
            startBossSummoning(event.getDungeon(), event.getRoom());
        }
    }

    @EventHandler
    public void onCorridorEnter(DungeonCorridorEnterEvent event) {
        if (event.getComingFrom() == null) return;
        String targetId = event.getCorridor().otherRoom(event.getComingFrom().id());
        DungeonRoom target = targetId == null ? null : event.getDungeon().room(targetId);
        if (target != null) spawnRoom(event.getDungeon(), target);
    }

    private void spawnRoom(DungeonInstance dungeon, DungeonRoom room) {
        if (dungeon.isCompleted()) return;
        if (room.type() == DungeonLayout.RoomType.SPAWN && !plugin.getConfig().getBoolean("mobs.spawn-room", false)) return;
        RoomMobs state = state(dungeon, room);
        // Visited is recorded even when spawning is deferred, so first-visit
        // reporting and the door watchdog keep their meaning.
        state.visited = true;
        if (state.spawned) return;

        DifficultySettings settings = DifficultySettings.read(plugin.getConfig(), dungeon.difficulty(),
                dungeon.partySize());
        if (room.type() == DungeonLayout.RoomType.BOSS) {
            // The gate manager arms the arena once every player stands inside;
            // spawning here would start the fight ahead of the party.
            if (plugin.gates().defersBossSpawn()) return;
            state.spawned = true;
            spawnBoss(dungeon, room, settings);
            return;
        }
        state.spawned = true;
        if (!isCombatRoom(room)) return;
        if (room.variant() == DungeonLayout.RoomVariant.PARKOUR
                && !plugin.getConfig().getBoolean("mobs.parkour-rooms.spawn-mobs", false)) return;
        if (room.role() != null) {
            spawnGroups(dungeon, room, settings, roleAnchors(dungeon, room, settings));
            return;
        }
        spawnGroups(dungeon, room, settings, room.markers());
    }

    private boolean isCombatRoom(DungeonRoom room) {
        return room.type() == DungeonLayout.RoomType.NORMAL || room.type() == DungeonLayout.RoomType.BRANCH;
    }

    /**
     * Turns mobs.room-roles.&lt;role&gt; into one anchor per requested group.
     * Authored markers of the right colour anchor groups at their own spots;
     * any shortfall gets deterministic anchors found on clear floor at runtime.
     */
    private List<nl.riddernix.dungeonforge.room.DungeonMarker> roleAnchors(DungeonInstance dungeon, DungeonRoom room,
                                                                           DifficultySettings settings) {
        var recipe = plugin.getConfig().getConfigurationSection("mobs.room-roles." + room.role());
        if (recipe == null) {
            plugin.getLogger().warning("Room " + room.id() + " carries role '" + room.role()
                    + "' but mobs.room-roles." + room.role() + " is not configured; nothing spawns there.");
            return List.of();
        }
        List<nl.riddernix.dungeonforge.room.DungeonMarker> anchors = new ArrayList<>();
        Random positions = new Random(dungeon.seed() ^ 0x524f4c45414e43L ^ ((long) room.id().hashCode() << 32));
        for (String rawCategory : recipe.getKeys(false)) {
            int groups = Math.max(0, recipe.getInt(rawCategory, 0));
            Role category = Role.category(rawCategory);
            if (category == null) {
                if (groups > 0) plugin.getLogger().warning("mobs.room-roles." + room.role() + "." + rawCategory
                        + " is not a spawnable mob category and was skipped.");
                continue;
            }
            List<nl.riddernix.dungeonforge.room.DungeonMarker> authored = room.markers().stream()
                    .filter(marker -> category.configName().equals(marker.category())).toList();
            for (int group = 0; group < groups; group++) {
                if (group < authored.size()) {
                    anchors.add(authored.get(group));
                    continue;
                }
                nl.riddernix.dungeonforge.room.DungeonMarker generated =
                        generatedAnchor(dungeon, room, settings, category, anchors, positions);
                if (generated != null) {
                    anchors.add(generated);
                } else {
                    plugin.getLogger().warning("No clear anchor found for a " + category.configName()
                            + " group in room " + room.id() + " of dungeon " + dungeon.id() + ".");
                }
            }
        }
        return anchors;
    }

    private nl.riddernix.dungeonforge.room.DungeonMarker generatedAnchor(DungeonInstance dungeon, DungeonRoom room,
                                                                         DifficultySettings settings, Role category,
                                                                         List<nl.riddernix.dungeonforge.room.DungeonMarker> existing,
                                                                         Random positions) {
        Bounds bounds = room.bounds();
        CategoryDefinition definition = settings.category(category);
        if (definition.type() == null) return null;
        Clearance clearance = Clearance.read(plugin.getConfig(), definition.type());
        // The keyholder stands in the middle of its room, like the boss. Its
        // room is a dead end built around it, and a corner spawn reads as the
        // key having failed to appear. Centre first, random anchor only if the
        // centre has no floor that fits it.
        if (category == Role.GUARDIAN) {
            MobStats stats = settings.categoryStats(category);
            Clearance scaled = new Clearance((int) Math.ceil(clearance.width() * stats.scale()),
                    (int) Math.ceil(clearance.height() * stats.scale()));
            Location centre = automaticBossCentre(dungeon.world(), room, scaled);
            if (centre != null) {
                return new nl.riddernix.dungeonforge.room.DungeonMarker(category.configName(),
                        centre.getBlockX(), centre.getBlockY(), centre.getBlockZ());
            }
        }
        int y = room.floorY();
        int edge = Math.max(2, plugin.getConfig().getInt("mobs.markers.generated.edge-clearance", 3));
        int minimumDistance = Math.max(1, plugin.getConfig().getInt("mobs.markers.generated.minimum-distance", 8));
        int attempts = Math.max(1, plugin.getConfig().getInt("mobs.markers.generated.placement-attempts", 100));
        int minX = bounds.minX() + edge;
        int maxX = bounds.maxX() - edge;
        int minZ = bounds.minZ() + edge;
        int maxZ = bounds.maxZ() - edge;
        if (minX <= maxX && minZ <= maxZ) {
            for (int attempt = 0; attempt < attempts; attempt++) {
                int x = positions.nextInt(maxX - minX + 1) + minX;
                int z = positions.nextInt(maxZ - minZ + 1) + minZ;
                if (nearDoorway(dungeon, room, x, y, z) || !hasClearance(dungeon.world(), x, y, z, clearance)) continue;
                boolean tooClose = existing.stream().anyMatch(marker -> {
                    int offsetX = marker.x() - x;
                    int offsetZ = marker.z() - z;
                    return offsetX * offsetX + offsetZ * offsetZ < minimumDistance * minimumDistance;
                });
                if (!tooClose) return new nl.riddernix.dungeonforge.room.DungeonMarker(category.configName(), x, y, z);
            }
        }
        return null;
    }

    /** Each consumed marker requests one complete category group. */
    private void spawnGroups(DungeonInstance dungeon, DungeonRoom room, DifficultySettings settings,
                             List<nl.riddernix.dungeonforge.room.DungeonMarker> anchors) {
        List<MarkerMember> queue = new ArrayList<>();
        for (nl.riddernix.dungeonforge.room.DungeonMarker marker : anchors) {
            Role category = Role.category(marker.category());
            if (category == null) continue;
            CategoryDefinition definition = settings.category(category);
            MobStats stats = settings.categoryStats(category);
            if (definition.type() == null || stats.count() <= 0) continue;
            for (int count = 0; count < stats.count(); count++) {
                queue.add(new MarkerMember(marker, category, definition, stats));
            }
        }
        if (queue.isEmpty()) return;
        RoomMobs state = state(dungeon, room);
        state.pendingSpawns += queue.size();
        int perTick = Math.max(1, plugin.getConfig().getInt("mobs.spawn-per-tick", 4));
        Random positions = new Random(dungeon.seed() ^ 0x535741524d504f53L
                ^ ((long) room.id().hashCode() << 32));
        Map<String, List<Location>> placed = new HashMap<>();
        new BukkitRunnable() {
            private int cursor;
            @Override public void run() {
                if (dungeon.isCompleted() || plugin.rooms().dungeon(dungeon.world()).orElse(null) != dungeon) {
                    state.pendingSpawns = 0;
                    cancel();
                    return;
                }
                for (int spawned = 0; spawned < perTick && cursor < queue.size(); spawned++, cursor++) {
                    MarkerMember member = queue.get(cursor);
                    try {
                        spawnMarkerMember(dungeon, room, settings, member,
                                placed.computeIfAbsent(markerKey(member.marker()), ignored -> new ArrayList<>()), positions);
                    } finally {
                        state.pendingSpawns--;
                    }
                }
                if (cursor >= queue.size()) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static String markerKey(nl.riddernix.dungeonforge.room.DungeonMarker marker) {
        return marker.category() + ':' + marker.x() + ':' + marker.y() + ':' + marker.z();
    }

    private void spawnMarkerMember(DungeonInstance dungeon, DungeonRoom room, DifficultySettings settings, MarkerMember member,
                             List<Location> placed, Random positions) {
        Location location = findMarkerSpawn(dungeon, room, member.marker(), member.definition().type(), placed, positions);
        if (location == null) return;
        Entity entity = location.getWorld().spawnEntity(location, member.definition().type());
        if (entity instanceof LivingEntity living) {
            prepareDungeonMob(living, dungeon, room, settings, member.category(), member.stats(), false, null,
                    member.definition().name(), member.definition().nameVisible());
            placed.add(location);
        } else {
            entity.remove();
        }
    }

    private void spawnBoss(DungeonInstance dungeon, DungeonRoom room, DifficultySettings settings) {
        BossDefinition boss = settings.boss();
        Location centre = bossCentre(dungeon, room, boss.type(), boss.stats());
        if (centre == null) return;
        Entity entity = dungeon.world().spawnEntity(centre, boss.type());
        if (!(entity instanceof LivingEntity living)) { entity.remove(); return; }
        prepareDungeonMob(living, dungeon, room, settings, Role.ELITE, boss.stats(), true, settings.theme(), boss.name(), boss.nameVisible());
        createBossBar(living, dungeon, room, boss);
        if (boss.minionCount() > 0) pendingBossMinions.put(living.getUniqueId(), new PendingBossMinions(living.getUniqueId(), dungeon, room, settings, boss, living.getLocation(), boss.type()));
    }

    /** Begins the boss's one-shot call after a player has committed to entering its arena. */
    private void startBossSummoning(DungeonInstance dungeon, DungeonRoom room) {
        if (dungeon.isCompleted()) return;
        for (Map.Entry<UUID, PendingBossMinions> entry : pendingBossMinions.entrySet()) {
            PendingBossMinions pending = entry.getValue();
            if (pending.dungeon != dungeon || pending.room != room || activeBossSummons.containsKey(entry.getKey())) continue;
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof LivingEntity boss && !boss.isDead() && boss.isValid()) {
                BossSummoningSequence sequence = new BossSummoningSequence(entry.getKey(), boss, pending);
                activeBossSummons.put(entry.getKey(), sequence);
                plugin.events().fireBossSummon(plugin.snapshots().of(dungeon), boss, pending.settings.theme(),
                        pending.boss.summoning().durationTicks());
                sequence.begin();
            } else {
                pendingBossMinions.remove(entry.getKey());
                pending.spawnNow();
            }
            return;
        }
    }

    private Location bossCentre(DungeonInstance dungeon, DungeonRoom room, EntityType type, MobStats stats) {
        Location marker = dungeon.bossSpawnLocation().orElse(null);
        Clearance base = Clearance.read(plugin.getConfig(), type);
        Clearance scaled = new Clearance((int) Math.ceil(base.width() * stats.scale()), (int) Math.ceil(base.height() * stats.scale()));
        if (marker == null) {
            Location automatic = automaticBossCentre(dungeon.world(), room, scaled);
            if (automatic == null) {
                plugin.getLogger().severe("Boss room " + room.id() + " has no clear solid floor near its centre for a "
                        + scaled.width() + "x" + scaled.height() + " scaled boss footprint; the boss was not spawned.");
            }
            return automatic;
        }
        int x = marker.getBlockX(), y = marker.getBlockY(), z = marker.getBlockZ();
        if (hasClearance(dungeon.world(), x, y, z, scaled)) return new Location(dungeon.world(), x + 0.5, y, z + 0.5);
        int available = availableHeight(dungeon.world(), x, y, z, scaled);
        plugin.getLogger().severe("Boss marker clearance failed in dungeon " + dungeon.id() + " at " + x + "," + y + "," + z
                + ": requires " + scaled.height() + " clear blocks of height and has " + available + ". Boss was not spawned.");
        return null;
    }

    /** How far above the layout floor an arena may raise a platform the boss can stand on. */
    private static final int BOSS_FLOOR_SEARCH_UP = 6;

    /**
     * Finds the clear floor nearest arena centre when an explicit marker is
     * intentionally absent.
     *
     * <p>The search runs upward from the room's own floor and takes the lowest
     * standing spot in each column. Searching downward from the ceiling looks
     * equivalent but is not: an arena roof has solid blocks below it and open
     * sky above, which satisfies every clearance test, so the boss would be
     * placed on top of the building.</p>
     */
    private static Location automaticBossCentre(World world, DungeonRoom room, Clearance clearance) {
        Bounds bounds = room.bounds();
        int highest = Math.min(bounds.maxY(), room.floorY() + BOSS_FLOOR_SEARCH_UP);
        Location best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestY = Integer.MAX_VALUE;
        for (int x = bounds.minX() + 1; x < bounds.maxX(); x++) {
            for (int z = bounds.minZ() + 1; z < bounds.maxZ(); z++) {
                int distance = Math.abs(x - bounds.centreX()) + Math.abs(z - bounds.centreZ());
                if (distance > bestDistance) continue;
                for (int feetY = room.floorY(); feetY <= highest; feetY++) {
                    if (!hasClearance(world, x, feetY, z, clearance)) continue;
                    if (distance < bestDistance || feetY < bestY) {
                        best = new Location(world, x + 0.5, feetY, z + 0.5);
                        bestDistance = distance;
                        bestY = feetY;
                    }
                    break;
                }
            }
        }
        return best;
    }

    /** Creates the native Adventure boss bar but does not expose it until arena entry. */
    private void createBossBar(LivingEntity boss, DungeonInstance dungeon, DungeonRoom room, BossDefinition definition) {
        if (!plugin.getConfig().getBoolean("mobs.boss-bars.enabled", true)) return;
        Component name = miniMessage.deserialize(definition.name());
        BossBar bar = BossBar.bossBar(name, 1.0F, definition.barColor(), BossBar.Overlay.PROGRESS);
        bossBars.put(boss.getUniqueId(), new ActiveBossBar(dungeon.id(), room.id(), dungeon.world().getName(), bar));
    }

    /** Shows the boss bar only after somebody has entered the boss arena. */
    private void showBossBars(DungeonInstance dungeon, DungeonRoom room) {
        for (Map.Entry<UUID, ActiveBossBar> entry : bossBars.entrySet()) {
            ActiveBossBar state = entry.getValue();
            if (!state.dungeonId.equals(dungeon.id()) || !state.roomId.equals(room.id())) continue;
            state.shown = true;
            for (Player player : dungeon.world().getPlayers()) showBossBar(player, state);
        }
    }

    private void showBossBar(Player player, ActiveBossBar state) {
        if (state.viewers.add(player.getUniqueId())) player.showBossBar(state.bar);
    }

    private void updateBossBar(LivingEntity boss) {
        ActiveBossBar state = bossBars.get(boss.getUniqueId());
        if (state == null) return;
        AttributeInstance maximumHealth = boss.getAttribute(Attribute.MAX_HEALTH);
        double maximum = maximumHealth == null ? 1.0 : Math.max(1.0, maximumHealth.getValue());
        state.bar.progress((float) Math.clamp(boss.getHealth() / maximum, 0.0, 1.0));
    }

    private void syncBossBars() {
        for (Map.Entry<UUID, ActiveBossBar> entry : new ArrayList<>(bossBars.entrySet())) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            ActiveBossBar state = entry.getValue();
            if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
                removeBossBar(entry.getKey());
                continue;
            }
            updateBossBar(living);
            for (UUID viewer : new HashSet<>(state.viewers)) {
                Player player = Bukkit.getPlayer(viewer);
                if (player == null || !player.getWorld().getName().equals(state.worldName)) {
                    if (player != null) player.hideBossBar(state.bar);
                    state.viewers.remove(viewer);
                }
            }
            if (state.shown) {
                World world = Bukkit.getWorld(state.worldName);
                if (world != null) for (Player player : world.getPlayers()) showBossBar(player, state);
            }
        }
    }

    private void removeBossBar(UUID bossId) {
        ActiveBossBar state = bossBars.remove(bossId);
        if (state == null) return;
        for (UUID viewer : state.viewers) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) player.hideBossBar(state.bar);
        }
    }

    private void removeBossBars(String dungeonId) {
        for (Map.Entry<UUID, ActiveBossBar> entry : new ArrayList<>(bossBars.entrySet())) {
            if (entry.getValue().dungeonId.equals(dungeonId)) removeBossBar(entry.getKey());
        }
    }

    /** Places optional block statues over several ticks, never as one blocking edit. */
    private void placeCopperStatues(DungeonInstance dungeon, DungeonRoom room, String materialName, int count) {
        Material material = Material.matchMaterial(materialName);
        if (material == null || material.isAir() || count == 0) return;
        List<Location> blocks = new ArrayList<>();
        Bounds bounds = room.bounds();
        for (int index = 0; index < count; index++) {
            int x = index % 2 == 0 ? bounds.minX() + 3 : bounds.maxX() - 3;
            int z = index < 2 ? bounds.minZ() + 3 : bounds.maxZ() - 3;
            for (int y = room.floorY(); y < room.floorY() + 3; y++) blocks.add(new Location(dungeon.world(), x, y, z));
        }
        int perTick = Math.max(1, Math.min(8, plugin.getConfig().getInt("performance.blocks-per-tick", 60000)));
        new BukkitRunnable() {
            private int cursor;
            @Override public void run() {
                for (int placed = 0; placed < perTick && cursor < blocks.size(); placed++, cursor++) {
                    blocks.get(cursor).getBlock().setType(material, false);
                }
                if (cursor >= blocks.size()) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Applies DungeonForge's baseline before other plugins receive the public spawn event. */
    private void prepareDungeonMob(LivingEntity entity, DungeonInstance dungeon, DungeonRoom room,
                                   DifficultySettings settings, Role role, MobStats stats, boolean boss, String bossTheme,
                                   String displayName, boolean displayNameVisible) {
        preventBabyVariant(entity);
        preventZombification(entity);
        String category = boss ? "boss" : role.configName();
        tag(entity, dungeon.id(), room.id(), settings.tier(), dungeon.difficulty(), boss, settings.theme(), category);
        entity.setRemoveWhenFarAway(false);
        if (entity instanceof CopperGolem copper) configureCopperGolem(copper, stats.damage(), stats.attackReach());
        if (entity instanceof IronGolem golem && boss) configureIronGolem(golem, stats.damage(), stats.attackReach());
        applyStats(entity, stats);
        applyWeapon(entity, stats.weapon());
        setDisplayName(entity, settings, role, displayName, displayNameVisible);
        clearArmor(entity);
        // Fired once the mob is fully set up, so anything a listener changes
        // here is the last word on it.
        plugin.events().fireMobSpawn(plugin.snapshots().of(dungeon), entity,
                new DungeonMobInfo(settings.tier(), dungeon.difficulty(), category, settings.theme(), boss));
        zeroEquipmentDropChances(entity);
        state(dungeon, room).entities.add(entity.getUniqueId());
    }

    /** Applies the same baseline and public spawn event without assigning a dungeon room. */
    private void prepareTestingMob(LivingEntity entity, DifficultySettings settings, Role category, MobStats stats,
                                   CategoryDefinition definition) {
        preventBabyVariant(entity);
        preventZombification(entity);
        tagTestingMob(entity, settings.tier(), settings.difficulty(), category.configName());
        entity.setRemoveWhenFarAway(false);
        if (entity instanceof CopperGolem copper) configureCopperGolem(copper, stats.damage(), stats.attackReach());
        applyStats(entity, stats);
        applyWeapon(entity, stats.weapon());
        setDisplayName(entity, settings, category, definition.name(), definition.nameVisible());
        clearArmor(entity);
        // A test mob belongs to no dungeon, so its snapshot is null and
        // listeners are told as much through isTestMob().
        plugin.events().fireMobSpawn(null, entity, new DungeonMobInfo(settings.tier(), settings.difficulty(),
                category.configName(), settings.theme(), false));
        zeroEquipmentDropChances(entity);
    }

    private void configureCopperGolem(CopperGolem copper, double damage, double reach) {
        copper.setOxidizing(CopperGolem.Oxidizing.waxed());
        copper.setWeatheringState(WeatheringCopperState.UNAFFECTED);
        Bukkit.getMobGoals().removeAllGoals(copper);
        Bukkit.getMobGoals().addGoal(copper, 1, new HostileMeleeGoal<>(copper, damage, reach, new NamespacedKey(plugin, "copper_golem_attack"), CopperGolem.class));
    }

    private void configureIronGolem(IronGolem golem, double damage, double reach) {
        golem.setPlayerCreated(false);
        Bukkit.getMobGoals().removeAllGoals(golem);
        Bukkit.getMobGoals().addGoal(golem, 1, new HostileMeleeGoal<>(golem, damage, reach, new NamespacedKey(plugin, "iron_golem_boss_attack"), IronGolem.class));
    }

    private void setDisplayName(LivingEntity entity, DifficultySettings settings, Role role, String configuredName, boolean nameVisible) {
        if (configuredName != null && !configuredName.isBlank()) {
            entity.customName(miniMessage.deserialize(configuredName));
            entity.setCustomNameVisible(nameVisible);
            return;
        }
        String format = plugin.getConfig().getString("mobs.name-format", "");
        if (format == null || format.isBlank()) return;
        entity.customName(miniMessage.deserialize(format,
                Placeholder.unparsed("tier", Integer.toString(settings.tier())),
                Placeholder.unparsed("role", role.configName()),
                Placeholder.unparsed("mob", entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '))));
        entity.setCustomNameVisible(plugin.getConfig().getBoolean("mobs.name-visible", false));
    }

    private void preventBabyVariant(LivingEntity entity) {
        if (entity instanceof Ageable ageable) ageable.setAdult();
    }

    /** Stops the vanilla conversion timer itself, avoiding the visual shiver it causes. */
    private static void preventZombification(LivingEntity entity) {
        if (entity instanceof Hoglin hoglin) hoglin.setImmuneToZombification(true);
        if (entity instanceof PiglinAbstract piglin) piglin.setImmuneToZombification(true);
    }

    private void tag(LivingEntity entity, String dungeonId, String roomId, int tier, int difficulty, boolean boss,
                     String bossTheme, String category) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(plugin.dungeonMobDungeonKey(), PersistentDataType.STRING, dungeonId);
        data.set(plugin.dungeonMobRoomKey(), PersistentDataType.STRING, roomId);
        data.set(plugin.dungeonMobTierKey(), PersistentDataType.INTEGER, tier);
        data.set(plugin.dungeonMobDifficultyKey(), PersistentDataType.INTEGER, difficulty);
        data.set(plugin.dungeonMobBossKey(), PersistentDataType.BYTE, (byte) (boss ? 1 : 0));
        if (bossTheme != null) data.set(plugin.dungeonMobBossThemeKey(), PersistentDataType.STRING, bossTheme);
        // Stored so a mob's category survives to its death event too.
        if (category != null) data.set(plugin.dungeonMobCategoryKey(), PersistentDataType.STRING, category);
    }

    private void tagTestingMob(LivingEntity entity, int tier, int difficulty, String category) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(plugin.dungeonMobTierKey(), PersistentDataType.INTEGER, tier);
        data.set(plugin.dungeonMobDifficultyKey(), PersistentDataType.INTEGER, difficulty);
        data.set(plugin.dungeonMobBossKey(), PersistentDataType.BYTE, (byte) 0);
        data.set(plugin.dungeonMobTestKey(), PersistentDataType.BYTE, (byte) 1);
        if (category != null) data.set(plugin.dungeonMobCategoryKey(), PersistentDataType.STRING, category);
    }

    private void applyStats(LivingEntity entity, MobStats stats) {
        setAttribute(entity, Attribute.MAX_HEALTH, stats.health());
        entity.setHealth(Math.min(stats.health(), entity.getAttribute(Attribute.MAX_HEALTH).getValue()));
        setAttribute(entity, Attribute.ATTACK_DAMAGE, stats.damage());
        if (stats.speed() > 0.0) setAttribute(entity, Attribute.MOVEMENT_SPEED, stats.speed());
        if (stats.attackSpeed() > 0.0) setAttribute(entity, Attribute.ATTACK_SPEED, stats.attackSpeed());
        if (stats.knockbackResistance() > 0.0) setAttribute(entity, Attribute.KNOCKBACK_RESISTANCE, stats.knockbackResistance());
        if (stats.scale() > 0.0) setAttribute(entity, Attribute.SCALE, stats.scale());
    }

    private static void setAttribute(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    private void applyWeapon(LivingEntity entity, String path) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) return;
        equipment.setItemInMainHand(item(path));
        equipment.setItemInOffHand(null);
        clearArmor(entity);
    }

    private ItemStack item(String path) {
        String raw = path;
        if (raw == null || raw.equalsIgnoreCase("AIR")) return null;
        Material material = Material.matchMaterial(raw);
        return material == null || material.isAir() ? null : new ItemStack(material);
    }

    private static void zeroEquipmentDropChances(LivingEntity entity) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) return;
        equipment.setItemInMainHandDropChance(0.0F);
        equipment.setItemInOffHandDropChance(0.0F);
        equipment.setHelmetDropChance(0.0F);
        equipment.setChestplateDropChance(0.0F);
        equipment.setLeggingsDropChance(0.0F);
        equipment.setBootsDropChance(0.0F);
    }

    private static void clearArmor(LivingEntity entity) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) return;
        equipment.setHelmet(null); equipment.setChestplate(null); equipment.setLeggings(null); equipment.setBoots(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (isTestingMob(entity)) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            testingMobs.remove(entity.getUniqueId());
            return;
        }
        MobIdentity identity = identity(entity);
        if (identity == null) return;
        event.getDrops().clear();
        event.setDroppedExp(Math.max(0, plugin.getConfig().getInt("mobs.difficulties." + identity.difficulty() + ".experience", 0)));
        DungeonInstance dungeon = plugin.rooms().dungeon(entity.getWorld())
                .filter(instance -> instance.id().equals(identity.dungeonId())).orElse(null);
        // Count the kill and drop it from its room before anything is fired,
        // so a listener never sees a dungeon that still counts a dead mob.
        if (dungeon != null) dungeon.recordMobKill();
        RoomMobs state = mobs.getOrDefault(identity.dungeonId(), Map.of()).get(identity.roomId());
        if (state != null) state.entities.remove(entity.getUniqueId());
        if (dungeon != null && entity instanceof LivingEntity dead) {
            DungeonRoom room = dungeon.room(identity.roomId());
            plugin.events().fireMobDeath(plugin.snapshots().of(dungeon), dead, event, identity.toInfo(),
                    room == null ? null : plugin.snapshots().of(room));
            checkRoomCleared(dungeon, identity.roomId());
            plugin.gates().notifyKill(dungeon, identity.roomId());
        }
        if (dungeon != null && !identity.boss() && "guardian".equals(identity.category())
                && dungeon.keyGate() != null && dungeon.keyGate().guardianRoomId().equals(identity.roomId())) {
            plugin.doors().onGuardianDeath(dungeon, entity.getLocation());
        }
        if (dungeon != null && identity.boss()) {
            // The arena bars drop at the kill itself, before the completion
            // grace period moves anyone.
            plugin.gates().onBossDeath(dungeon, identity.roomId());
            // Last, because completing the dungeon ends it: the boss death and
            // its room clearing both belong to a dungeon that is still active.
            removeBossBar(entity.getUniqueId());
            plugin.completions().complete(dungeon);
        }
        PendingBossMinions pending = pendingBossMinions.remove(entity.getUniqueId());
        BossSummoningSequence sequence = activeBossSummons.remove(entity.getUniqueId());
        if (sequence != null) sequence.completeNow();
        else if (pending != null) pending.spawnNow();
    }

    /** Applies the configured Warden boss damage to both melee and sonic-boom hits. */
    @EventHandler
    public void onBossDamage(EntityDamageByEntityEvent event) {
        if (identity(event.getDamager()) != null && identity(event.getEntity()) != null) { event.setCancelled(true); return; }
        MobIdentity identity = identity(event.getDamager());
        if (identity == null || !identity.boss() || event.getDamager().getType() != EntityType.WARDEN) return;
        event.setDamage(DifficultySettings.read(plugin.getConfig(), identity.difficulty()).boss().stats().damage());
    }

    /** Covers damage sources that do not use a living entity as their damager. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSummoningDamage(EntityDamageEvent event) {
        BossSummoningSequence sequence = activeBossSummons.get(event.getEntity().getUniqueId());
        if (sequence != null && sequence.blocksDamage()) event.setCancelled(true);
    }

    /** Updates the HUD after Paper has applied the final damage calculation. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossHealthChange(EntityDamageEvent event) {
        if (!bossBars.containsKey(event.getEntity().getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Entity entity = Bukkit.getEntity(event.getEntity().getUniqueId());
            if (entity instanceof LivingEntity living && !living.isDead() && living.isValid()) updateBossBar(living);
        });
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent event) {
        MobIdentity source = identity(event.getEntity());
        if (source == null) return;
        if (event.getTarget() != null && identity(event.getTarget()) != null) { event.setCancelled(true); return; }
        if (event.getEntity() instanceof Warden && event.getTarget() != null && !(event.getTarget() instanceof Player)) event.setCancelled(true);
    }

    /** Keeps a dungeon enderman's normal room-confined movement, but never lets damage become an escape. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEndermanEscape(EndermanEscapeEvent event) {
        if (!isDungeonOrTestingMob(event.getEntity())
                || !plugin.getConfig().getBoolean("mobs.safety.prevent-enderman-damage-escape", true)) return;
        if (event.getReason() == EndermanEscapeEvent.Reason.INDIRECT
                || event.getReason() == EndermanEscapeEvent.Reason.CRITICAL_HIT) {
            event.setCancelled(true);
        }
    }

    /**
     * Replaces the two vanilla ranged immunities with a normal player-caused hit.
     * Endermen avoid vanilla projectile damage even when their escape is cancelled;
     * breezes instead deflect the projectile before that damage occurs.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRiftProjectileHit(ProjectileHitEvent event) {
        if (!(event.getHitEntity() instanceof LivingEntity target)
                || !(target instanceof Enderman || target instanceof Breeze)
                || !isDungeonOrTestingMob(target)) return;
        boolean isEnderman = target instanceof Enderman;
        boolean isBreeze = target instanceof Breeze;
        if (isEnderman && !plugin.getConfig().getBoolean("mobs.safety.prevent-enderman-damage-escape", true)) return;
        if (isBreeze && !plugin.getConfig().getBoolean("mobs.safety.disable-breeze-projectile-deflection", true)) return;
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player) || !isDamagingProjectile(projectile)) return;

        // Cancelling stops the breeze's deflection. Damage is supplied directly
        // by the player because vanilla endermen reject projectile damage even
        // when their escape event is cancelled.
        event.setCancelled(true);
        target.damage(projectileDamage(projectile), player);
        projectile.remove();
    }

    /** Copies metadata to slime and magma-cube children before room counting sees them. */
    @EventHandler
    public void onSlimeSplit(SlimeSplitEvent event) {
        if (!(event.getEntity() instanceof Slime slime)) return;
        MobIdentity identity = identity(slime);
        if (identity == null || event.getCount() <= 0) return;
        pendingSplits.computeIfAbsent(slime.getWorld().getUID(), ignored -> new ArrayList<>())
                .add(new SplitContext(slime.getLocation(), identity, slime.customName(), slime.isCustomNameVisible(), event.getCount(), Bukkit.getCurrentTick() + 2));
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Vex && plugin.worlds().isDungeonWorld(event.getLocation().getWorld())
                && plugin.getConfig().getBoolean("mobs.safety.cancel-vex-spawns", true)) {
            event.setCancelled(true);
            return;
        }
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SLIME_SPLIT || !(event.getEntity() instanceof Slime slime)) return;
        List<SplitContext> contexts = pendingSplits.get(slime.getWorld().getUID());
        if (contexts == null) return;
        SplitContext context = contexts.stream().filter(candidate -> candidate.remaining > 0
                        && candidate.location.distanceSquared(slime.getLocation()) <= 9.0)
                .findFirst().orElse(null);
        if (context == null) return;
        context.remaining--;
        DungeonInstance dungeon = plugin.rooms().dungeon(slime.getWorld()).filter(instance -> instance.id().equals(context.identity.dungeonId())).orElse(null);
        DungeonRoom room = dungeon == null ? null : dungeon.room(context.identity.roomId());
        if (dungeon == null || room == null) return;
        DifficultySettings settings = DifficultySettings.read(plugin.getConfig(), context.identity.difficulty());
        prepareDungeonMob(slime, dungeon, room, settings, Role.SWARM, settings.categoryStats(Role.SWARM), false, null, null, false);
        if (context.name != null) {
            slime.customName(context.name);
            slime.setCustomNameVisible(context.nameVisible);
        }
    }

    @EventHandler
    public void onTeleport(EntityTeleportEvent event) {
        MobIdentity identity = identity(event.getEntity());
        if (identity == null) return;
        // A boss being raised out of its own entrance animation is scripted
        // movement, not an escape, so the containment rules step aside for it.
        if (activeBossSummons.containsKey(event.getEntity().getUniqueId())) return;
        if (event.getEntity() instanceof Enderman
                && plugin.getConfig().getBoolean("mobs.safety.constrain-enderman-teleport", true)) {
            DungeonInstance dungeon = plugin.rooms().dungeon(event.getEntity().getWorld()).orElse(null);
            DungeonRoom room = dungeon == null ? null : dungeon.room(identity.roomId());
            Location destination = event.getTo();
            if (room == null || destination == null || !room.bounds().contains(destination.getBlockX(), destination.getBlockY(), destination.getBlockZ())) {
                event.setCancelled(true);
            }
            return;
        }
        if (plugin.getConfig().getBoolean("mobs.safety.prevent-dungeon-mob-teleport", true)) event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        hideBossBars(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        hideBossBars(event.getPlayer());
    }

    private void hideBossBars(Player player) {
        for (ActiveBossBar state : bossBars.values()) {
            if (state.viewers.remove(player.getUniqueId())) player.hideBossBar(state.bar);
        }
    }

    @EventHandler
    public void onBlockChange(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Enderman && identity(event.getEntity()) != null
                && plugin.getConfig().getBoolean("mobs.safety.prevent-enderman-block-changes", true)) {
            event.setCancelled(true);
        }
    }

    private boolean isDungeonOrTestingMob(Entity entity) {
        return identity(entity) != null || isTestingMob(entity);
    }

    private static boolean isDamagingProjectile(Projectile projectile) {
        return switch (projectile.getType()) {
            case ARROW, SPECTRAL_ARROW, TRIDENT, FIREBALL, SMALL_FIREBALL, WITHER_SKULL, WIND_CHARGE -> true;
            default -> false;
        };
    }

    private static double projectileDamage(Projectile projectile) {
        if (projectile instanceof AbstractArrow arrow) return Math.max(0.0, arrow.getDamage());
        // Non-arrow projectiles do not expose a single native damage value.
        // This keeps their direct, cancellable damage hook useful to gameplay
        // plugins while providing a sensible vanilla-style fallback.
        return 2.0;
    }

    @EventHandler
    public void onTransform(EntityTransformEvent event) {
        if (identity(event.getEntity()) != null && plugin.getConfig().getBoolean("mobs.safety.prevent-zombification", true)) {
            event.setCancelled(true);
        }
    }

    /** Defensive recount for future gates and for mobs removed by external plugins. */
    public void recount() {
        refreshTestingMobLocations();
        long tick = Bukkit.getCurrentTick();
        pendingSplits.values().forEach(contexts -> contexts.removeIf(context -> context.expiresAtTick < tick || context.remaining <= 0));
        pendingSplits.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        for (Map.Entry<String, Map<String, RoomMobs>> dungeon : mobs.entrySet()) {
            for (RoomMobs room : dungeon.getValue().values()) {
                room.entities.removeIf(id -> {
                    Entity entity = Bukkit.getEntity(id);
                    return !(entity instanceof LivingEntity living) || living.isDead() || !living.isValid();
                });
            }
        }
        // Pruning is what notices a mob that vanished without a death event;
        // running the clear check here is what turns that into an opened gate
        // rather than a room sealed until something else happens to die in it.
        for (World world : plugin.worlds().loadedDungeonWorlds()) {
            DungeonInstance dungeon = plugin.rooms().dungeon(world).orElse(null);
            if (dungeon == null) continue;
            for (String roomId : Set.copyOf(mobs.getOrDefault(dungeon.id(), Map.of()).keySet())) {
                checkRoomCleared(dungeon, roomId);
            }
        }
        guardWardens();
        syncBossBars();
        plugin.gates().tick();
    }

    /** A boss Warden is kept angry while players are present and frozen while none are. */
    private void guardWardens() {
        for (World world : plugin.worlds().loadedDungeonWorlds()) {
            for (Entity entity : world.getEntities()) {
                MobIdentity identity = identity(entity);
                if (!(entity instanceof Warden warden) || identity == null || !identity.boss()) continue;
                if (activeBossSummons.containsKey(warden.getUniqueId())) continue;
                Player target = world.getPlayers().stream().findFirst().orElse(null);
                if (target == null) {
                    warden.setAI(false);
                } else {
                    for (Entity other : world.getEntities()) if (!(other instanceof Player)) warden.clearAnger(other);
                    warden.setAI(true);
                    warden.setTarget(target);
                    warden.setAnger(target, 150);
                    warden.setRemoveWhenFarAway(false);
                }
            }
        }
    }

    /** Rewinds one roled room so its recipe spawns afresh; the door watchdog's revival path. */
    public void reviveRoleRoom(DungeonInstance dungeon, DungeonRoom room) {
        RoomMobs state = state(dungeon, room);
        state.spawned = false;
        state.cleared = false;
        state.entities.clear();
        spawnRoom(dungeon, room);
    }

    /** Rooms whose mobs have all been killed, for the API's progress figure. */
    public int clearedRoomCount(String dungeonId) {
        return (int) mobs.getOrDefault(dungeonId, Map.of()).values().stream().filter(room -> room.cleared).count();
    }

    /** Whether every mob spawned for that room has been killed. */
    public boolean isRoomCleared(String dungeonId, String roomId) {
        RoomMobs state = mobs.getOrDefault(dungeonId, Map.of()).get(roomId);
        return state != null && state.cleared;
    }

    /** Whether anyone has been far enough into the dungeon to wake this room. */
    public boolean isRoomVisited(String dungeonId, String roomId) {
        RoomMobs state = mobs.getOrDefault(dungeonId, Map.of()).get(roomId);
        return state != null && state.visited;
    }

    /**
     * Fires the room-cleared event once per room, after the kill that emptied
     * it has already been counted, so the dungeon snapshot the listener gets
     * includes this room in its cleared total.
     */
    private void checkRoomCleared(DungeonInstance dungeon, String roomId) {
        RoomMobs state = mobs.getOrDefault(dungeon.id(), Map.of()).get(roomId);
        DungeonRoom room = dungeon.room(roomId);
        if (state == null || room == null || state.cleared || !state.isClear()) return;
        state.cleared = true;
        List<UUID> inside = dungeon.world().getPlayers().stream().map(Player::getUniqueId).toList();
        plugin.events().fireRoomCleared(plugin.snapshots().of(dungeon), plugin.snapshots().of(room), inside);
        plugin.gates().onRoomCleared(dungeon, room);
    }

    public int livingCount(String dungeonId, String roomId) {
        RoomMobs state = mobs.getOrDefault(dungeonId, Map.of()).get(roomId);
        int pending = (int) pendingBossMinions.values().stream().filter(value -> value.dungeon.id().equals(dungeonId) && value.room.id().equals(roomId)).count();
        return (state == null ? 0 : state.entities.size() + state.pendingSpawns) + pending;
    }

    public List<String> goalNames(Entity entity) {
        if (!(entity instanceof Mob mob)) return List.of();
        return Bukkit.getMobGoals().getAllGoals(mob).stream().map(goal -> goal.getKey().getNamespacedKey().toString() + " " + goal.getTypes()).toList();
    }

    public List<String> diagnostics(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return List.of("Not a living entity.");
        String target = entity instanceof Mob mob && mob.getTarget() != null ? mob.getTarget().getType().name() : "none";
        return List.of("target=" + target, "attack-damage=" + attribute(living, Attribute.ATTACK_DAMAGE),
                "movement-speed=" + attribute(living, Attribute.MOVEMENT_SPEED), "scale=" + attribute(living, Attribute.SCALE),
                "colliding=" + collidesWithBlocks(entity));
    }

    private static double attribute(LivingEntity entity, Attribute attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? 0.0 : instance.getValue();
    }

    private static boolean collidesWithBlocks(Entity entity) {
        org.bukkit.util.BoundingBox box = entity.getBoundingBox();
        for (int x = (int) Math.floor(box.getMinX()); x <= Math.floor(box.getMaxX()); x++) for (int y = (int) Math.floor(box.getMinY()); y <= Math.floor(box.getMaxY()); y++) for (int z = (int) Math.floor(box.getMinZ()); z <= Math.floor(box.getMaxZ()); z++) {
            Block block = entity.getWorld().getBlockAt(x, y, z);
            if (block.getType().isSolid() && block.getBoundingBox().overlaps(box)) return true;
        }
        return false;
    }

    public void removeWorld(String worldName) {
        plugin.rooms().dungeon(worldName).ifPresent(dungeon -> {
            removeBossBars(dungeon.id());
            for (Entity entity : new ArrayList<>(dungeon.world().getEntities())) {
                MobIdentity identity = identity(entity);
                if (identity != null && dungeon.id().equals(identity.dungeonId())) entity.remove();
            }
            mobs.remove(dungeon.id());
            pendingSplits.remove(dungeon.world().getUID());
            pendingBossMinions.entrySet().removeIf(entry -> entry.getValue().dungeon.id().equals(dungeon.id()));
            activeBossSummons.entrySet().removeIf(entry -> {
                if (!entry.getValue().pending.dungeon.id().equals(dungeon.id())) return false;
                entry.getValue().abort();
                return true;
            });
        });
    }

    /** Immediately removes all tagged mobs and delayed spawn work for a completed dungeon. */
    public void despawnDungeonMobs(DungeonInstance dungeon) {
        removeBossBars(dungeon.id());
        for (Entity entity : new ArrayList<>(dungeon.world().getEntities())) {
            MobIdentity identity = identity(entity);
            if (identity != null && dungeon.id().equals(identity.dungeonId())) entity.remove();
        }
        mobs.remove(dungeon.id());
        pendingSplits.remove(dungeon.world().getUID());
        pendingBossMinions.entrySet().removeIf(entry -> entry.getValue().dungeon.id().equals(dungeon.id()));
        activeBossSummons.entrySet().removeIf(entry -> {
            if (!entry.getValue().pending.dungeon.id().equals(dungeon.id())) return false;
            entry.getValue().abort();
            return true;
        });
    }

    /** The gate manager's arming path: the whole party is inside, start now. */
    public void spawnBossRoomNow(DungeonInstance dungeon, DungeonRoom room) {
        if (dungeon.isCompleted()) return;
        RoomMobs state = state(dungeon, room);
        if (state.spawned) return;
        state.visited = true;
        state.spawned = true;
        DifficultySettings settings = DifficultySettings.read(plugin.getConfig(), dungeon.difficulty(),
                dungeon.partySize());
        spawnBoss(dungeon, room, settings);
        // The enter events already fired while the arena was empty, so the
        // bars and the summoning start here rather than from onRoomEnter.
        showBossBars(dungeon, room);
        startBossSummoning(dungeon, room);
    }

    /** The living mobs of one room, for the gate manager's glow failsafe. */
    public List<LivingEntity> livingEntities(String dungeonId, String roomId) {
        RoomMobs state = mobs.getOrDefault(dungeonId, Map.of()).get(roomId);
        if (state == null) return List.of();
        List<LivingEntity> living = new ArrayList<>();
        for (UUID id : Set.copyOf(state.entities)) {
            if (Bukkit.getEntity(id) instanceof LivingEntity entity && !entity.isDead() && entity.isValid()) {
                living.add(entity);
            }
        }
        return living;
    }

    private RoomMobs state(DungeonInstance dungeon, DungeonRoom room) {
        return mobs.computeIfAbsent(dungeon.id(), ignored -> new HashMap<>())
                .computeIfAbsent(room.id(), ignored -> new RoomMobs());
    }

    /** Finds a clear, separated point around one authored or generated marker. */
    private Location findMarkerSpawn(DungeonInstance dungeon, DungeonRoom room,
                                     nl.riddernix.dungeonforge.room.DungeonMarker marker, EntityType type,
                                     List<Location> placed, Random positions) {
        Bounds bounds = room.bounds();
        Clearance clearance = Clearance.read(plugin.getConfig(), type);
        // A spawn marker sits on the floor it belongs to, so its own height is
        // the group's standing height even in a prefab with a raised platform.
        int y = marker.y();
        int requestedRadius = Math.max(0, plugin.getConfig().getInt("mobs.markers.group-radius", 5));
        int maximumRadius = Math.max(requestedRadius, plugin.getConfig().getInt("mobs.markers.maximum-group-radius", 9));
        int outwardStep = Math.max(1, plugin.getConfig().getInt("mobs.markers.outward-search-step", 2));
        int attemptsPerRing = Math.max(1, plugin.getConfig().getInt("mobs.markers.attempts-per-ring", 20));
        double minimumDistance = Math.max(0.0, plugin.getConfig().getDouble("mobs.markers.minimum-group-distance", 1.75));
        double minimumDistanceSquared = minimumDistance * minimumDistance;

        for (int radius = requestedRadius; radius <= maximumRadius; radius += outwardStep) {
            for (int attempt = 0; attempt < attemptsPerRing; attempt++) {
                double angle = positions.nextDouble() * Math.PI * 2.0;
                double distance = radius == 0 ? 0.0 : positions.nextDouble() * radius;
                int x = (int) Math.round(marker.x() + Math.cos(angle) * distance);
                int z = (int) Math.round(marker.z() + Math.sin(angle) * distance);
                if (x < bounds.minX() + 2 || x > bounds.maxX() - 2 || z < bounds.minZ() + 2 || z > bounds.maxZ() - 2) continue;
                if (nearDoorway(dungeon, room, x, y, z) || !hasClearance(dungeon.world(), x, y, z, clearance)) continue;
                Location location = new Location(dungeon.world(), x + 0.5, y, z + 0.5);
                if (placed.stream().anyMatch(other -> other.distanceSquared(location) < minimumDistanceSquared)) continue;
                return location;
            }
            if (radius + outwardStep > maximumRadius && radius != maximumRadius) radius = maximumRadius - outwardStep;
        }
        return null;
    }

    private static boolean hasClearance(World world, int x, int y, int z, Clearance clearance) {
        int minimumOffset = -(clearance.width() / 2);
        int maximumOffset = (clearance.width() - 1) / 2;
        for (int offsetX = minimumOffset; offsetX <= maximumOffset; offsetX++) {
            for (int offsetZ = minimumOffset; offsetZ <= maximumOffset; offsetZ++) {
                if (!world.getBlockAt(x + offsetX, y - 1, z + offsetZ).getType().isSolid()) return false;
                for (int offsetY = 0; offsetY < clearance.height(); offsetY++) {
                    if (!world.getBlockAt(x + offsetX, y + offsetY, z + offsetZ).isPassable()) return false;
                }
            }
        }
        return true;
    }

    /** Lowest available headroom across the entire scaled footprint. */
    private static int availableHeight(World world, int x, int y, int z, Clearance clearance) {
        int minimumOffset = -(clearance.width() / 2);
        int maximumOffset = (clearance.width() - 1) / 2;
        int available = Integer.MAX_VALUE;
        for (int offsetX = minimumOffset; offsetX <= maximumOffset; offsetX++) {
            for (int offsetZ = minimumOffset; offsetZ <= maximumOffset; offsetZ++) {
                if (!world.getBlockAt(x + offsetX, y - 1, z + offsetZ).getType().isSolid()) return 0;
                int column = 0;
                while (column < clearance.height() && world.getBlockAt(x + offsetX, y + column, z + offsetZ).isPassable()) column++;
                available = Math.min(available, column);
            }
        }
        return available == Integer.MAX_VALUE ? 0 : available;
    }

    private List<Location> testingLocations(Location origin, EntityType type, MobStats stats, int count) {
        World world = origin.getWorld();
        if (world == null) return List.of();
        Clearance base = Clearance.read(plugin.getConfig(), type);
        Clearance clearance = new Clearance((int) Math.ceil(base.width() * stats.scale()),
                (int) Math.ceil(base.height() * stats.scale()));
        int requestedRadius = Math.max(0, plugin.getConfig().getInt("mobs.markers.group-radius", 5));
        int minimumRadius = Math.max(2, (int) Math.ceil(clearance.width() / 2.0) + 1);
        int maximumRadius = Math.max(minimumRadius, plugin.getConfig().getInt("mobs.markers.maximum-group-radius", 9));
        int outwardStep = Math.max(1, plugin.getConfig().getInt("mobs.markers.outward-search-step", 2));
        int attemptsPerRing = Math.max(1, plugin.getConfig().getInt("mobs.markers.attempts-per-ring", 20));
        double minimumDistance = Math.max(0.0, plugin.getConfig().getDouble("mobs.markers.minimum-group-distance", 1.75));
        double minimumDistanceSquared = minimumDistance * minimumDistance;
        int y = origin.getBlockY();
        List<Location> positions = new ArrayList<>();
        Random locationRandom = new Random(origin.getWorld().getFullTime() ^ origin.getBlockX() * 73471L
                ^ origin.getBlockY() * 9127L ^ origin.getBlockZ() * 314159L ^ type.ordinal());
        for (int member = 0; member < count; member++) {
            Location found = null;
            for (int radius = Math.max(requestedRadius, minimumRadius); radius <= maximumRadius && found == null; radius += outwardStep) {
                for (int attempt = 0; attempt < attemptsPerRing; attempt++) {
                    double angle = locationRandom.nextDouble() * Math.PI * 2.0;
                    double distance = minimumRadius + locationRandom.nextDouble() * Math.max(0, radius - minimumRadius);
                    int x = (int) Math.round(origin.getX() + Math.cos(angle) * distance);
                    int z = (int) Math.round(origin.getZ() + Math.sin(angle) * distance);
                    if (!hasClearance(world, x, y, z, clearance)) continue;
                    Location candidate = new Location(world, x + 0.5, y, z + 0.5);
                    if (positions.stream().anyMatch(other -> other.distanceSquared(candidate) < minimumDistanceSquared)) continue;
                    found = candidate;
                    break;
                }
                if (radius + outwardStep > maximumRadius && radius != maximumRadius) radius = maximumRadius - outwardStep;
            }
            if (found == null) return List.of();
            positions.add(found);
        }
        return List.copyOf(positions);
    }

    private static boolean nearDoorway(DungeonInstance dungeon, DungeonRoom room, int x, int y, int z) {
        return dungeon.tunnels().stream()
                .filter(tunnel -> tunnel.firstRoomId().equals(room.id()) || tunnel.secondRoomId().equals(room.id()))
                .map(tunnel -> tunnel.firstRoomId().equals(room.id()) ? tunnel.firstDoorway() : tunnel.secondDoorway())
                .anyMatch(doorway -> doorway.expand(3).contains(x, y, z));
    }

    private MobIdentity identity(Entity entity) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        String dungeonId = data.get(plugin.dungeonMobDungeonKey(), PersistentDataType.STRING);
        String roomId = data.get(plugin.dungeonMobRoomKey(), PersistentDataType.STRING);
        Integer tier = data.get(plugin.dungeonMobTierKey(), PersistentDataType.INTEGER);
        Integer difficulty = data.get(plugin.dungeonMobDifficultyKey(), PersistentDataType.INTEGER);
        boolean boss = Byte.valueOf((byte) 1).equals(data.get(plugin.dungeonMobBossKey(), PersistentDataType.BYTE));
        String bossTheme = data.get(plugin.dungeonMobBossThemeKey(), PersistentDataType.STRING);
        String category = data.get(plugin.dungeonMobCategoryKey(), PersistentDataType.STRING);
        return dungeonId == null || roomId == null || tier == null || difficulty == null ? null
                : new MobIdentity(dungeonId, roomId, tier, difficulty, boss, bossTheme, category);
    }

    private boolean isTestingMob(Entity entity) {
        return Byte.valueOf((byte) 1).equals(entity.getPersistentDataContainer()
                .get(plugin.dungeonMobTestKey(), PersistentDataType.BYTE));
    }

    private enum Role {
        SWARM, PACK, CHAMPION, GUARDIAN, RANGED, BRUISER, ELITE;

        private static Role category(String raw) {
            try {
                Role value = valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
                return value == SWARM || value == PACK || value == CHAMPION || value == GUARDIAN ? value : null;
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        private String configName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private record MobIdentity(String dungeonId, String roomId, int tier, int difficulty, boolean boss,
                               String bossTheme, String category) {
        /** The public metadata shape, so the API never sees this record. */
        private DungeonMobInfo toInfo() {
            return new DungeonMobInfo(tier, difficulty, category == null ? "" : category,
                    bossTheme == null ? "" : bossTheme, boss);
        }
    }

    public enum TestSummonStatus {
        SUCCESS,
        INVALID_CATEGORY,
        INVALID_DIFFICULTY,
        INVALID_THEME,
        DISABLED_CATEGORY,
        NO_CLEARANCE
    }

    public record TestSummonResult(TestSummonStatus status, int count, String theme) {
    }

    private record TestMobLocation(String worldName, int x, int y, int z) {
        private static TestMobLocation from(Location location) {
            return new TestMobLocation(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    private static final class RoomMobs {
        /** Woken by approach or entry, even while spawning is still deferred. */
        private boolean visited;
        private boolean spawned;
        private int pendingSpawns;
        private boolean cleared;
        private final Set<UUID> entities = new HashSet<>();

        /** Spawned, nothing still queued, and nothing left alive. */
        private boolean isClear() {
            return spawned && pendingSpawns <= 0 && entities.isEmpty();
        }
    }

    /** Native boss-bar state, kept separate from room counts and mob metadata. */
    private static final class ActiveBossBar {
        private final String dungeonId;
        private final String roomId;
        private final String worldName;
        private final BossBar bar;
        private final Set<UUID> viewers = new HashSet<>();
        private boolean shown;

        private ActiveBossBar(String dungeonId, String roomId, String worldName, BossBar bar) {
            this.dungeonId = dungeonId;
            this.roomId = roomId;
            this.worldName = worldName;
            this.bar = bar;
        }
    }

    private static final class SplitContext {
        private final Location location;
        private final MobIdentity identity;
        private final Component name;
        private final boolean nameVisible;
        private int remaining;
        private final long expiresAtTick;

        private SplitContext(Location location, MobIdentity identity, Component name, boolean nameVisible, int remaining, long expiresAtTick) {
            this.location = location;
            this.identity = identity;
            this.name = name;
            this.nameVisible = nameVisible;
            this.remaining = remaining;
            this.expiresAtTick = expiresAtTick;
        }
    }

    private record Clearance(int width, int height) {
        private static Clearance read(FileConfiguration config, EntityType type) {
            String base = "mobs.entity-clearance." + type.name() + ".";
            int width = config.getInt(base + "width", config.getInt("mobs.entity-clearance.default.width", 1));
            int height = config.getInt(base + "height", config.getInt("mobs.entity-clearance.default.height", 2));
            return new Clearance(Math.max(1, width), Math.max(2, height));
        }
    }

    private record DifficultySettings(FileConfiguration config, int difficulty, String theme, int tier,
                                      Scaling scaling) {
        /** For paths with no dungeon behind them: test summons and damage lookups. */
        private static DifficultySettings read(FileConfiguration config, int difficulty) {
            return read(config, difficulty, 1);
        }

        private static DifficultySettings read(FileConfiguration config, int difficulty, int partySize) {
            String path = "mobs.difficulties." + difficulty + ".";
            return new DifficultySettings(config, difficulty, config.getString(path + "theme", "crypt"),
                    Math.max(1, config.getInt(path + "tier", difficulty)), Scaling.read(config, partySize));
        }

        private MobStats categoryStats(Role category) {
            return MobStats.read(config, difficultyPath() + "categories." + category.configName() + ".")
                    .scaled(scaling.healthFor(category), scaling.countFor(category));
        }

        private CategoryDefinition category(Role category) {
            String path = "mobs.themes." + theme + ".categories." + category.configName() + ".";
            return new CategoryDefinition(entity(config.getString(path + "entity", "ZOMBIE")),
                    config.getString(path + "name", ""), config.getBoolean(path + "name-visible", true));
        }

        private static EntityType entity(String value) {
            try {
                EntityType type = EntityType.valueOf(value == null ? "ZOMBIE" : value.trim().toUpperCase(Locale.ROOT));
                return type.getEntityClass() != null && LivingEntity.class.isAssignableFrom(type.getEntityClass()) ? type : null;
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        private BossDefinition boss() {
            String path = "mobs.themes." + theme + ".boss.";
            MobStats champion = categoryStats(Role.CHAMPION);
            double multiplier = Math.max(0.1, config.getDouble(path + "health-multiplier", 2.5));
            // Boss health is champion health times the theme's multiplier, so
            // it already carries the global health scaling, the champion's own
            // extra and the party multiplier. boss-health-extra compounds on
            // top of all three - that is exactly what it is for, and why it is
            // a separate number rather than folded into the champion's.
            double health = champion.health() * multiplier * scaling.bossExtra();
            return new BossDefinition(config, path,
                    MobStats.read(config, difficultyPath() + "boss.").withHealth(health),
                    MobStats.read(config, difficultyPath() + "boss.minions.").scaled(scaling.health(), 1.0));
        }

        private String difficultyPath() {
            return "mobs.difficulties." + difficulty + ".";
        }
    }

    /**
     * Every multiplier laid on top of the numbers in {@code mobs.difficulties}.
     *
     * <p>Two halves. The base figures are the balance pass and are the same
     * for every run; the party figures depend on who is running and are fixed
     * at dungeon start.</p>
     *
     * <p>Health and count deliberately never both reach one category. They
     * multiply into total effective health, so a champion given both would be
     * roughly nine times the wall at four players against four times the
     * damage. Swarm and pack scale in numbers, champion, guardian and boss
     * scale in health, and each category has exactly one lever.</p>
     */
    private record Scaling(double health, double championExtra, double guardianExtra, double bossExtra,
                           double swarmCount, double partyCount, double partyHealth) {

        private static Scaling read(FileConfiguration config, int partySize) {
            String path = "mobs.scaling.";
            int capped = Math.clamp(partySize, 1,
                    Math.max(1, config.getInt(path + "party.max-party-size", 8)));
            // 1 + (extra players x factor): a factor of 1.0 is fully linear,
            // below that every extra player counts for less than a whole one.
            double count = 1.0 + (capped - 1) * Math.max(0.0, config.getDouble(path + "party.count-per-player", 0.6));
            double health = 1.0 + (capped - 1) * Math.max(0.0, config.getDouble(path + "party.health-per-player", 0.75));
            return new Scaling(Math.max(0.01, config.getDouble(path + "health", 3.0)),
                    Math.max(0.01, config.getDouble(path + "champion-health-extra", 1.5)),
                    Math.max(0.01, config.getDouble(path + "guardian-health-extra", 1.5)),
                    Math.max(0.01, config.getDouble(path + "boss-health-extra", 1.5)),
                    Math.max(0.0, config.getDouble(path + "swarm-count-multiplier", 2.0)),
                    count, health);
        }

        private double healthFor(Role category) {
            return health * switch (category) {
                case CHAMPION -> championExtra * partyHealth;
                case GUARDIAN -> guardianExtra * partyHealth;
                default -> 1.0;
            };
        }

        private double countFor(Role category) {
            return switch (category) {
                case SWARM -> swarmCount * partyCount;
                case PACK -> partyCount;
                default -> 1.0;
            };
        }
    }

    private record CategoryDefinition(EntityType type, String name, boolean nameVisible) { }

    private record MarkerMember(nl.riddernix.dungeonforge.room.DungeonMarker marker, Role category,
                                CategoryDefinition definition, MobStats stats) { }

    private record MobStats(double health, double damage, double speed, double scale, int count, double attackSpeed,
                            double knockbackResistance, double attackReach, String weapon) {
        private MobStats withHealth(double newHealth) {
            return new MobStats(Math.max(1.0, newHealth), damage, speed, scale, count, attackSpeed,
                    knockbackResistance, attackReach, weapon);
        }

        /**
         * A count of 0 stays 0: an empty category is an authoring decision
         * (a deliberately quiet room), not a number to scale up from nothing.
         */
        private MobStats scaled(double healthMultiplier, double countMultiplier) {
            return new MobStats(Math.max(1.0, health * healthMultiplier), damage, speed, scale,
                    count <= 0 ? 0 : Math.max(1, (int) Math.round(count * countMultiplier)),
                    attackSpeed, knockbackResistance, attackReach, weapon);
        }

        private static MobStats read(FileConfiguration config, String path) {
            return new MobStats(Math.max(1.0, config.getDouble(path + "health", 20.0)),
                    Math.max(0.0, config.getDouble(path + "damage", 2.0)),
                    Math.max(0.0, config.getDouble(path + "speed", 0.0)),
                    Math.max(0.1, config.getDouble(path + "scale", 1.0)), Math.max(0, config.getInt(path + "count", 1)),
                    Math.max(0.0, config.getDouble(path + "attack-speed", 0.0)), Math.clamp(config.getDouble(path + "knockback-resistance", 0.0), 0.0, 1.0),
                    Math.max(2.5, config.getDouble(path + "attack-reach", config.getDouble(path + "scale", 1.0) * 2.5)), config.getString(path + "weapon", "AIR"));
        }
    }

    private record BossDefinition(FileConfiguration config, String path, MobStats stats, MobStats minionStats) {
        private EntityType type() { return entity(config.getString(path + "type", "ZOMBIE")); }
        private String name() { return config.getString(path + "name", ""); }
        private boolean nameVisible() { return config.getBoolean(path + "name-visible", true); }
        private BossBar.Color barColor() {
            try { return BossBar.Color.valueOf(config.getString(path + "bar-color", "WHITE").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException | NullPointerException ignored) { return BossBar.Color.WHITE; }
        }
        private int minionCount() { return Math.max(0, config.getInt(path + "minion-count", 0)); }
        private String minionName() { return config.getString(path + "minion-name", ""); }
        private boolean minionNameVisible() { return config.getBoolean(path + "minion-name-visible", true); }
        private EntityType pickMinion(Random random) {
            List<String> values = config.getStringList(path + "minions");
            return values.isEmpty() ? null : entity(values.get(random.nextInt(values.size())));
        }
        private boolean copperStatues() { return config.getBoolean(path + "scenery.copper-golem-statues", false); }
        private String statueMaterial() { return config.getString(path + "scenery.material", "WAXED_OXIDIZED_COPPER"); }
        private int statueCount() { return Math.max(0, config.getInt(path + "scenery.count", 0)); }
        private double minionGap() { return Math.max(0.0, config.getDouble(path + "minion-gap", 0.35)); }
        private double minionMinimumSeparation() { return Math.max(0.1, config.getDouble(path + "minion-minimum-separation", 2.0)); }
        private BossSummoningSettings summoning() { return BossSummoningSettings.read(config, path + "summoning."); }
        private static EntityType entity(String value) {
            try { return EntityType.valueOf(value.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException exception) { return EntityType.ZOMBIE; }
        }
    }

    /** Configured audiovisual sequence used before a boss's delayed minions arrive. */
    private record BossSummoningSettings(int durationTicks, int holdTicks, int pulseInterval, Particle particle,
                                         int particleCount, double particleRadius, double particleHeight,
                                         Sound startSound, Sound peakSound, float soundVolume, float soundPitch,
                                         boolean invulnerable, String animation) {
        private static BossSummoningSettings read(FileConfiguration config, String bossPath) {
            String common = "mobs.boss-summoning.";
            return new BossSummoningSettings(
                    Math.max(1, config.getInt(bossPath + "duration-ticks", config.getInt(common + "duration-ticks", 50))),
                    Math.max(0, config.getInt(bossPath + "hold-ticks", config.getInt(common + "hold-ticks", 12))),
                    Math.max(1, config.getInt(bossPath + "pulse-interval-ticks", config.getInt(common + "pulse-interval-ticks", 5))),
                    particle(config.getString(bossPath + "particle", config.getString(common + "particle", "SOUL_FIRE_FLAME"))),
                    Math.max(0, config.getInt(bossPath + "particle-count", config.getInt(common + "particle-count", 16))),
                    Math.max(0.0, config.getDouble(bossPath + "particle-radius", config.getDouble(common + "particle-radius", 1.5))),
                    Math.max(0.0, config.getDouble(bossPath + "particle-height", config.getDouble(common + "particle-height", 1.5))),
                    sound(config.getString(bossPath + "start-sound", config.getString(common + "start-sound", "ENTITY_WITHER_AMBIENT"))),
                    sound(config.getString(bossPath + "peak-sound", config.getString(common + "peak-sound", "ENTITY_WITHER_SPAWN"))),
                    (float) Math.max(0.0, config.getDouble(bossPath + "sound-volume", config.getDouble(common + "sound-volume", 2.0))),
                    (float) Math.max(0.0, config.getDouble(bossPath + "sound-pitch", config.getDouble(common + "sound-pitch", 1.0))),
                    config.getBoolean(bossPath + "invulnerable", config.getBoolean(common + "invulnerable", true)),
                    config.getString(bossPath + "animation", config.getString(common + "animation", "")));
        }

        private static Particle particle(String raw) {
            try { return Particle.valueOf(raw.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException | NullPointerException ignored) { return Particle.SOUL_FIRE_FLAME; }
        }

        private static Sound sound(String raw) {
            if (raw == null || raw.isBlank()) return Sound.ENTITY_WITHER_AMBIENT;
            Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(raw.toLowerCase(Locale.ROOT).replace('_', '.')));
            return sound == null ? Sound.ENTITY_WITHER_AMBIENT : sound;
        }
    }

    /** Runs once after arena entry. It completes even when the arena becomes empty. */
    private final class BossSummoningSequence extends BukkitRunnable {
        private final UUID bossId;
        private final LivingEntity boss;
        private final PendingBossMinions pending;
        private final BossSummoningSettings settings;
        private final SpawnAnimation animation;
        private final boolean previousAi;
        private final boolean previousInvulnerability;
        private int ticks;
        private boolean completed;
        private boolean animationFailed;

        private BossSummoningSequence(UUID bossId, LivingEntity boss, PendingBossMinions pending) {
            this.bossId = bossId;
            this.boss = boss;
            this.pending = pending;
            this.settings = pending.boss.summoning();
            this.previousAi = boss.hasAI();
            this.previousInvulnerability = boss.isInvulnerable();
            // Burying and raising the boss is only safe while it cannot be hurt.
            this.animation = SpawnAnimations.create(plugin, boss, settings.animation(), settings.invulnerable());
        }

        private void begin() {
            boss.setAI(false);
            if (settings.invulnerable()) boss.setInvulnerable(true);
            play(settings.startSound());
            if (animation != null && !SpawnAnimations.beginSafely(plugin, animation)) {
                // A broken entrance must not take the fight down with it: the
                // sequence carries on and the boss simply arrives plainly.
                animationFailed = true;
            }
            runTaskTimer(plugin, 1L, 1L);
        }

        @Override public void run() {
            if (completed) return;
            if (boss.isDead() || !boss.isValid()) {
                completeNow();
                return;
            }
            float yaw = boss.getLocation().getYaw() + 12.0F;
            boss.setRotation(yaw, 0.0F);
            if (animation != null && !animationFailed) {
                // A scripted entrance replaces the default pulse entirely; it
                // draws its own particles and moves the boss itself.
                animationFailed = !SpawnAnimations.tickSafely(plugin, animation, ticks, settings.durationTicks());
            } else if (ticks >= settings.holdTicks() && ticks % settings.pulseInterval() == 0) {
                double progress = Math.min(1.0, (double) (ticks + 1) / settings.durationTicks());
                int count = Math.max(1, (int) Math.ceil(settings.particleCount() * progress));
                Location at = boss.getLocation().add(0.0, settings.particleHeight(), 0.0);
                boss.getWorld().spawnParticle(settings.particle(), at, count, settings.particleRadius(), settings.particleRadius() * 0.5, settings.particleRadius(), 0.01);
                boss.swingMainHand();
            }
            ticks++;
            if (ticks >= settings.durationTicks()) {
                play(settings.peakSound());
                completeNow();
            }
        }

        private void play(Sound sound) {
            boss.getWorld().playSound(boss.getLocation(), sound, settings.soundVolume(), settings.soundPitch());
        }

        private boolean blocksDamage() {
            return settings.invulnerable();
        }

        private void completeNow() {
            if (completed) return;
            completed = true;
            cancel();
            activeBossSummons.remove(bossId);
            pendingBossMinions.remove(bossId);
            // Runs before the AI is restored, so the boss is put back on its
            // arena floor before it can walk anywhere.
            if (animation != null && !animationFailed) animation.finish();
            if (!boss.isDead() && boss.isValid()) {
                boss.setAI(previousAi);
                boss.setInvulnerable(previousInvulnerability);
            }
            pending.spawnNow();
        }

        /** Tears the sequence down without its outro, for a dungeon being removed. */
        private void abort() {
            completed = true;
            cancel();
            if (animation != null) animation.abort();
        }
    }

    private final class PendingBossMinions {
        private final UUID bossId;
        private final DungeonInstance dungeon;
        private final DungeonRoom room;
        private final DifficultySettings settings;
        private final BossDefinition boss; private final Location centre; private final EntityType bossType;
        private PendingBossMinions(UUID bossId, DungeonInstance dungeon, DungeonRoom room, DifficultySettings settings, BossDefinition boss, Location centre, EntityType bossType) {
            this.bossId = bossId; this.dungeon = dungeon; this.room = room; this.settings = settings; this.boss = boss; this.centre = centre; this.bossType = bossType;
        }
        private void spawnNow() {
            List<Location> placed = new ArrayList<>();
            for (int index = 0; index < boss.minionCount(); index++) {
                EntityType type = boss.pickMinion(random);
                Location location = type == null ? null : ringLocation(type, index, placed);
                if (location == null) {
                    plugin.getLogger().severe("Could not find any solid, clear minion location in boss room " + room.id()
                            + " for " + type + "; this arena needs more walkable space.");
                    continue;
                }
                spawnAt(dungeon, room, settings, type, location, boss.minionStats(), boss.minionName(), boss.minionNameVisible());
                placed.add(location);
            }
        }
        private Location ringLocation(EntityType type, int index, List<Location> placed) {
            Clearance minionClearance = Clearance.read(plugin.getConfig(), type);
            Entity entity = Bukkit.getEntity(bossId);
            double bossRadius = entity == null ? Clearance.read(plugin.getConfig(), bossType).width() * boss.stats().scale() / 2.0
                    : Math.max(entity.getBoundingBox().getWidthX(), entity.getBoundingBox().getWidthZ()) / 2.0;
            double minionRadius = minionClearance.width() * boss.minionStats().scale() / 2.0;
            int minions = Math.max(1, boss.minionCount());
            double separationRadius = boss.minionMinimumSeparation() / (2.0 * Math.sin(Math.PI / minions));
            double radius = Math.max(bossRadius + minionRadius + boss.minionGap(), separationRadius);
            Clearance scaled = new Clearance((int) Math.ceil(minionClearance.width() * boss.minionStats().scale()),
                    (int) Math.ceil(minionClearance.height() * boss.minionStats().scale()));
            int searchRadius = Math.max(1, plugin.getConfig().getInt("mobs.boss-minions.alternate-search-radius", 16));
            for (int nudge = 0; nudge <= searchRadius; nudge++) {
                // Keep the first choices on the intended retinue ring; broader
                // searching only happens when a plinth edge or wall blocks one.
                double angle = 2 * Math.PI * index / minions + (nudge == 0 ? 0.0 : (nudge % 2 == 0 ? 1 : -1) * nudge * 0.16);
                int x = (int) Math.floor(centre.getX() + Math.cos(angle) * (radius + nudge * 0.75));
                int z = (int) Math.floor(centre.getZ() + Math.sin(angle) * (radius + nudge * 0.75));
                Location floor = floorBelow(x, z, scaled);
                if (floor != null && separated(floor, placed)) return floor;
            }
            return nearestClearFloor(scaled, placed);
        }

        private Location floorBelow(int x, int z, Clearance clearance) {
            Bounds bounds = room.bounds();
            if (x < bounds.minX() || x > bounds.maxX() || z < bounds.minZ() || z > bounds.maxZ()) return null;
            int down = Math.max(1, plugin.getConfig().getInt("mobs.boss-minions.floor-search-down", 12));
            int lowest = Math.max(bounds.minY(), centre.getBlockY() - down);
            for (int floorY = centre.getBlockY() - 1; floorY >= lowest; floorY--) {
                int feetY = floorY + 1;
                if (hasClearance(dungeon.world(), x, feetY, z, clearance)) {
                    return new Location(dungeon.world(), x + .5, feetY, z + .5);
                }
            }
            return null;
        }

        private Location nearestClearFloor(Clearance clearance, List<Location> placed) {
            Bounds bounds = room.bounds();
            Location best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int x = bounds.minX() + 1; x < bounds.maxX(); x++) {
                for (int z = bounds.minZ() + 1; z < bounds.maxZ(); z++) {
                    Location floor = floorBelow(x, z, clearance);
                    if (floor == null || !separated(floor, placed)) continue;
                    double distance = floor.distanceSquared(centre);
                    if (distance < bestDistance) {
                        best = floor;
                        bestDistance = distance;
                    }
                }
            }
            return best;
        }

        private boolean separated(Location location, List<Location> placed) {
            double minimum = Math.max(0.5, boss.minionMinimumSeparation());
            return placed.stream().allMatch(other -> other.distanceSquared(location) >= minimum * minimum);
        }

    }

    private <T extends Mob> void spawnAt(DungeonInstance dungeon, DungeonRoom room, DifficultySettings settings, EntityType type,
                                         Location location, MobStats stats, String name, boolean nameVisible) {
        Entity entity = dungeon.world().spawnEntity(location, type);
        if (entity instanceof LivingEntity living) prepareDungeonMob(living, dungeon, room, settings, Role.ELITE, stats, false, null, name, nameVisible); else entity.remove();
    }

    private static final class HostileMeleeGoal<T extends Mob> implements Goal<T> {
        private final T mob;
        private final double damage;
        private final GoalKey<T> key;
        private int cooldown;
        private final double reachSquared;
        private HostileMeleeGoal(T mob, double damage, double reach, NamespacedKey key, Class<T> type) {
            this.mob = mob; this.damage = damage; this.reachSquared = reach * reach; this.key = GoalKey.of(type, key);
        }
        @Override public boolean shouldActivate() { return nearest() != null; }
        @Override public boolean shouldStayActive() { return nearest() != null; }
        @Override public void tick() {
            Player target = nearest();
            if (target == null) return;
            mob.setTarget(target);
            mob.setAggressive(true);
            mob.lookAt(target);
            double distance = mob.getLocation().distanceSquared(target.getLocation());
            if (distance > reachSquared) mob.getPathfinder().moveTo(target, 1.0);
            else if (cooldown-- <= 0) { target.damage(damage, mob); cooldown = 20; }
        }
        private Player nearest() { return mob.getWorld().getPlayers().stream().min(java.util.Comparator.comparingDouble(player -> player.getLocation().distanceSquared(mob.getLocation()))).orElse(null); }
        @Override public GoalKey<T> getKey() { return key; }
        @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE, GoalType.LOOK, GoalType.TARGET); }
    }

}
