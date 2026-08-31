package nl.riddernix.dungeonforge.room;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.math.transform.Transform;
import com.sk89q.worldedit.world.block.BlockState;
import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.generation.BlockListOperation;
import nl.riddernix.dungeonforge.generation.BuildOperation;
import nl.riddernix.dungeonforge.generation.Bounds;
import nl.riddernix.dungeonforge.generation.DungeonLayout;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads regular-room prefabs through WorldEdit without ever using a WorldEdit
 * edit session. The resulting block data is placed by DungeonForge's own cursor
 * builder. Filename prefixes keep normal and branch room pools deliberately
 * separate.
 */
public final class NormalRoomLibrary {
    private static final Pattern SPECIAL_FILE_NAME = Pattern.compile("^(spawn|boss)(?:_\\d+)?$", Pattern.CASE_INSENSITIVE);
    private static final List<NormalRoomShape> ALL_SHAPES = List.of(
            NormalRoomShape.STRAIGHT, NormalRoomShape.CORNER, NormalRoomShape.TJUNCTION,
            NormalRoomShape.CROSS, NormalRoomShape.DEAD_END);

    private final DungeonForgePlugin plugin;
    private final File folder;
    private List<Prefab> prefabs = List.of();
    private List<Inspection> inspections = List.of();
    private boolean worldEditAvailable;
    private String unavailableReason = "no valid room schematic is loaded";

    public NormalRoomLibrary(DungeonForgePlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "rooms");
    }

    /** Reloads the folder, preserving a report for every room file including failures. */
    public void reload() {
        if (!folder.isDirectory() && !folder.mkdirs()) {
            plugin.getLogger().severe("Could not create DungeonForge room folder: " + folder.getAbsolutePath());
        }
        Plugin worldEdit = plugin.getServer().getPluginManager().getPlugin("WorldEdit");
        worldEditAvailable = worldEdit != null && worldEdit.isEnabled();
        if (!worldEditAvailable) {
            plugin.getLogger().severe("WorldEdit is missing or disabled. Room prefabs are unavailable; "
                    + "DungeonForge will use procedural stone rooms.");
            prefabs = List.of();
            inspections = List.of(new Inspection("(WorldEdit unavailable)", 0, 0, 0, "none", "none", List.of(), false,
                    PrefabType.UNKNOWN, null, NormalRoomShape.UNKNOWN, "not parsed", Map.of(), List.of(), List.of(),
                    List.of("WorldEdit is missing or disabled.")));
            unavailableReason = "WorldEdit is missing or disabled";
            return;
        }

        File[] files = folder.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            plugin.getLogger().info("No room schematics found in " + folder.getAbsolutePath()
                    + "; procedural stone rooms remain active.");
            prefabs = List.of();
            inspections = List.of();
            unavailableReason = "the room folder is empty";
            return;
        }
        List<File> ordered = new ArrayList<>(List.of(files));
        ordered.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        List<Prefab> loaded = new ArrayList<>();
        List<Inspection> report = new ArrayList<>();
        for (File file : ordered) {
            LoadResult result = load(file);
            report.add(result.inspection());
            if (result.prefab() != null) {
                loaded.add(result.prefab());
                if (!"matches".equals(result.inspection().filenameMatch())) {
                    plugin.getLogger().warning("Room prefab " + file.getName() + " loaded, but " + result.inspection().displayProblems());
                }
            } else {
                plugin.getLogger().warning("Rejected room prefab " + file.getName() + ": " + result.inspection().displayProblems());
            }
        }
        prefabs = List.copyOf(loaded);
        inspections = List.copyOf(report);
        if (prefabs.isEmpty()) {
            plugin.getLogger().warning("No valid room schematics loaded from " + folder.getAbsolutePath()
                    + "; procedural stone rooms remain active.");
            unavailableReason = "no valid room schematic is loaded: " + report.stream()
                    .map(inspection -> inspection.fileName + " (" + inspection.displayProblems() + ")")
                    .reduce((left, right) -> left + "; " + right).orElse("no files found");
        } else {
            plugin.getLogger().info("Loaded " + prefabs.size() + " room schematic(s) from " + folder.getAbsolutePath() + ".");
            unavailableReason = null;
        }
        reportPoolFootprints();
    }

    /**
     * The planner reserves one envelope per pool - the largest loaded member -
     * so a pool whose files differ in size leaves every smaller room standing
     * inside an oversized reservation: the corridor stops at the reservation
     * edge while the room's wall is a block further in. That gap is invisible
     * in the files and obvious in game, so it is called out by name here.
     */
    private void reportPoolFootprints() {
        Map<String, List<Prefab>> pools = new HashMap<>();
        for (Prefab prefab : prefabs) {
            if (prefab.type != PrefabType.NORMAL && prefab.type != PrefabType.BRANCH) continue;
            pools.computeIfAbsent(prefab.type.configName() + (prefab.role == null ? "" : " " + prefab.role),
                    ignored -> new ArrayList<>()).add(prefab);
        }
        for (Map.Entry<String, List<Prefab>> pool : new java.util.TreeMap<>(pools).entrySet()) {
            List<String> sizes = pool.getValue().stream()
                    .map(prefab -> prefab.width + "x" + prefab.height + "x" + prefab.depth).distinct().toList();
            if (sizes.size() < 2) continue;
            plugin.getLogger().severe("Room pool '" + pool.getKey() + "' mixes " + sizes.size()
                    + " footprints, so every room smaller than the largest will leave a gap between its wall and its"
                    + " corridors. Resize them to one size. Loaded: " + pool.getValue().stream()
                    .map(prefab -> prefab.fileName + " " + prefab.width + "x" + prefab.height + "x" + prefab.depth)
                    .sorted(String.CASE_INSENSITIVE_ORDER).reduce((left, right) -> left + ", " + right).orElse("none"));
        }
    }

    public File folder() {
        return folder;
    }

    public List<Inspection> inspections() {
        return inspections;
    }

    /**
     * Largest loaded footprint for collision-safe layout planning. This is not
     * a validation limit: every selected schematic still uses its own size.
     */
    public PlanningDimensions planningDimensions(PrefabType type) {
        return planningDimensions(type, null);
    }

    /**
     * The envelope a slot reserves, covering every prefab that slot could
     * receive.
     *
     * <p>A roled slot prefers its own pool but falls back to the generic one,
     * so the reservation spans both. Reserving only the role's size is what
     * makes an oversized fallback catastrophic rather than merely untidy: a
     * prefab wider than its reservation overhangs into the corridor space on
     * both sides, and once the overhang reaches the corridor length the two
     * rooms end up sharing a wall with no gap left to build a corridor in.</p>
     */
    public PlanningDimensions planningDimensions(PrefabType type, String role) {
        List<Prefab> pool = prefabs.stream()
                .filter(prefab -> prefab.type == type)
                .filter(prefab -> prefab.role == null || (role != null && role.equals(prefab.role)))
                .toList();
        String fallbackPath = switch (type) {
            case SPAWN -> "generation.entrance.size";
            case BOSS -> "generation.boss-room.max-size";
            case NORMAL, BRANCH, UNKNOWN -> "generation.prefab-room.size";
        };
        int fallbackWidth = Math.max(1, plugin.getConfig().getInt(fallbackPath + ".x", 67));
        int fallbackHeight = Math.max(1, plugin.getConfig().getInt(fallbackPath + ".y", 34));
        int fallbackDepth = Math.max(1, plugin.getConfig().getInt(fallbackPath + ".z", 67));
        return new PlanningDimensions(
                pool.stream().mapToInt(prefab -> prefab.width).max().orElse(fallbackWidth),
                pool.stream().mapToInt(prefab -> prefab.height).max().orElse(fallbackHeight),
                pool.stream().mapToInt(prefab -> prefab.depth).max().orElse(fallbackDepth));
    }

    /** Lists eligible shapes with no valid file for one explicit layout room type. */
    public List<NormalRoomShape> missingUsableShapes(PrefabType type) {
        // Roled prefabs cannot serve generic slots, so they cannot satisfy a
        // generic shape either.
        return usableShapes(type).stream()
                .filter(shape -> prefabs.stream().noneMatch(prefab -> prefab.type == type
                        && prefab.role == null && prefab.shape == shape))
                .toList();
    }

    /** Valid files that the present layout rules can never choose. */
    public List<String> unusablePrefabs() {
        // Roled prefabs answer to their composition, not to the legacy branch
        // rules, so those limits say nothing about their usability.
        return prefabs.stream()
                .filter(prefab -> prefab.role == null
                        && (prefab.type == PrefabType.NORMAL || prefab.type == PrefabType.BRANCH)
                        && !usableShapes(prefab.type).contains(prefab.shape))
                .map(prefab -> prefab.fileName + " (" + prefab.type.configName() + " " + prefab.shape.configName() + ")")
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** Plans the deterministic prefab selection and all tick-spread placement operations for one layout. */
    public RoomPlan plan(DungeonLayout layout) {
        List<DungeonLayout.Room> requestedRooms = layout.rooms().stream().filter(NormalRoomLibrary::usesPrefabSlot).toList();
        if (requestedRooms.isEmpty()) return RoomPlan.empty();
        if (!worldEditAvailable || prefabs.isEmpty()) {
            logFallback(layout, List.of(unavailableReason == null ? "no valid room schematic is loaded" : unavailableReason));
            List<String> requiredFailures = requestedRooms.stream()
                    .filter(room -> room.type() == DungeonLayout.RoomType.SPAWN || room.type() == DungeonLayout.RoomType.BOSS)
                    .map(room -> "room " + room.id() + " (" + room.type().name().toLowerCase(Locale.ROOT) + ") requires a valid schematic: "
                            + (unavailableReason == null ? "no valid room schematic is loaded" : unavailableReason))
                    .toList();
            return RoomPlan.withRequiredFailures(requiredFailures);
        }
        List<BuildOperation> operations = new ArrayList<>();
        Set<String> prefabRooms = new HashSet<>();
        Map<String, List<DungeonMarker>> markers = new HashMap<>();
        Map<String, List<DungeonDoorway>> doorways = new HashMap<>();
        Map<String, List<DungeonDoorMarker>> doorwayMarkers = new HashMap<>();
        Map<String, Bounds> playableBounds = new HashMap<>();
        Map<String, DungeonSpecialMarker> playerSpawns = new HashMap<>();
        Map<String, DungeonSpecialMarker> bossSpawns = new HashMap<>();
        Map<String, DungeonTrap> traps = new HashMap<>();
        Map<String, String> chosen = new HashMap<>();
        Map<String, String> prefabFiles = new HashMap<>();
        List<String> fallbackReasons = new ArrayList<>();
        List<String> roleFallbacks = new ArrayList<>();
        List<String> requiredPrefabFailures = new ArrayList<>();

        for (DungeonLayout.Room room : layout.rooms()) {
            if (!usesPrefabSlot(room)) continue;
            EnumSet<BlockFace> required = requiredFaces(room, layout.tunnels());
            BlockFace arrivalFace = arrivalFace(room, layout.tunnels());
            PrefabType wantedType = PrefabType.from(room.type());
            List<Candidate> candidates = List.of();
            if (room.role() != null) {
                candidates = candidates(wantedType, room.role(), required, arrivalFace);
                if (candidates.isEmpty()) {
                    // A composed room silently wearing a generic prefab is the
                    // kind of thing nobody notices until the run feels wrong.
                    roleFallbacks.add("room " + room.id() + " wants role '" + room.role() + "' but no "
                            + wantedType.configName() + "_" + room.role() + "* prefab has exactly the doorways "
                            + faces(required) + (arrivalFace == null ? "" : " (entered from " + arrivalFace + ")")
                            + "; loaded for that role: " + rolePoolDescription(wantedType, room.role()));
                }
            }
            if (candidates.isEmpty()) candidates = candidates(wantedType, null, required, arrivalFace);
            if (candidates.isEmpty()) {
                String reason = "room " + room.id() + " requires " + faces(required)
                        + (arrivalFace == null ? "" : " entering from " + arrivalFace) + "; "
                        + candidateDiagnostics(room, required);
                if (room.type() == DungeonLayout.RoomType.SPAWN || room.type() == DungeonLayout.RoomType.BOSS) {
                    requiredPrefabFailures.add(reason);
                } else {
                    fallbackReasons.add(reason);
                }
                continue;
            }
            Candidate candidate = candidates.get(new Random(layout.seed() ^ 0x524f4f4d53454544L
                    ^ ((long) room.id().hashCode() << 32)).nextInt(candidates.size()));
            PlacementOrigin origin = origin(room, candidate);
            Point placedSize = rotatedDimensions(candidate.prefab.width, candidate.prefab.depth, candidate.rotation);
            prefabFiles.put(room.id(), candidate.prefab.fileName);
            chosen.put(room.id(), candidate.prefab.fileName + " rot" + candidate.rotation
                    + " origin " + origin.x + "," + origin.z + " size " + placedSize.x + "x" + placedSize.z);
            prefabRooms.add(room.id());
            operations.add(blocks(candidate, origin));
            playableBounds.put(room.id(), playableBounds(candidate, origin));

            List<DungeonMarker> roomMarkers = new ArrayList<>();
            for (PrefabMarker marker : candidate.prefab.markers) {
                Point point = rotate(marker.x, marker.z, candidate.prefab.width, candidate.prefab.depth, candidate.rotation);
                roomMarkers.add(new DungeonMarker(marker.category, origin.x + point.x,
                        origin.y + marker.y, origin.z + point.z));
            }
            markers.put(room.id(), List.copyOf(roomMarkers));

            List<DungeonDoorway> roomDoors = new ArrayList<>();
            List<DungeonDoorMarker> roomDoorMarkers = new ArrayList<>();
            for (PrefabDoorway doorway : candidate.prefab.doorways) {
                Point point = rotate(doorway.opening.centreX(), doorway.opening.centreZ(),
                        candidate.prefab.width, candidate.prefab.depth, candidate.rotation);
                BlockFace face = rotate(doorway.facing, candidate.rotation);
                DungeonDoorway placed = new DungeonDoorway(origin.x + point.x,
                        origin.y + doorway.opening.minY(), origin.z + point.z, face, face);
                roomDoors.add(placed);

                Point markerPoint = rotate(doorway.marker.centreX, doorway.marker.centreZ,
                        candidate.prefab.width, candidate.prefab.depth, candidate.rotation);
                BlockFace markerFace = rotate(doorway.marker.facing, candidate.rotation);
                roomDoorMarkers.add(new DungeonDoorMarker(origin.x + markerPoint.x,
                        origin.y + doorway.marker.y, origin.z + markerPoint.z, markerFace, doorway.marker.width));
            }
            doorways.put(room.id(), List.copyOf(roomDoors));
            doorwayMarkers.put(room.id(), List.copyOf(roomDoorMarkers));

            for (PrefabSpecialMarker marker : candidate.prefab.specialMarkers) {
                Point point = rotate(marker.x, marker.z, candidate.prefab.width, candidate.prefab.depth, candidate.rotation);
                DungeonSpecialMarker placed = new DungeonSpecialMarker(marker.kind,
                        origin.x + point.x, origin.y + marker.y, origin.z + point.z);
                if (marker.kind == SpecialMarkerKind.PLAYER_SPAWN) playerSpawns.put(room.id(), placed);
                if (marker.kind == SpecialMarkerKind.BOSS_SPAWN) bossSpawns.put(room.id(), placed);
            }

            if (!candidate.prefab.trapColumns.isEmpty() && !candidate.prefab.pressurePlates.isEmpty()) {
                List<DungeonTrap.Column> columns = new ArrayList<>();
                for (PrefabPoint column : candidate.prefab.trapColumns) {
                    Point point = rotate(column.x, column.z, candidate.prefab.width, candidate.prefab.depth, candidate.rotation);
                    columns.add(new DungeonTrap.Column(origin.x + point.x, origin.y + column.y, origin.z + point.z));
                }
                Set<org.bukkit.util.BlockVector> plates = new HashSet<>();
                for (PrefabPoint plate : candidate.prefab.pressurePlates) {
                    Point point = rotate(plate.x, plate.z, candidate.prefab.width, candidate.prefab.depth, candidate.rotation);
                    plates.add(new org.bukkit.util.BlockVector(origin.x + point.x, origin.y + plate.y, origin.z + point.z));
                }
                traps.put(room.id(), new DungeonTrap(room.id(), columns, plates));
            }
        }
        if (!roleFallbacks.isEmpty()) {
            plugin.getLogger().severe("Composed room role fallback for dungeon seed " + layout.seed() + ": "
                    + String.join(" | ", roleFallbacks) + ". A generic room of the right shape was used instead.");
        }
        if (!fallbackReasons.isEmpty()) {
            logFallback(layout, fallbackReasons);
        }
        logLayout(layout, chosen);
        return new RoomPlan(Set.copyOf(prefabRooms), List.copyOf(operations), immutable(markers), immutable(doorways),
                immutable(doorwayMarkers), Map.copyOf(playableBounds), Map.copyOf(playerSpawns), Map.copyOf(bossSpawns),
                Map.copyOf(traps), Map.copyOf(prefabFiles), List.copyOf(requiredPrefabFailures));
    }

    /**
     * One line per room and per corridor gap. A layout fault - a room at the
     * wrong distance, a prefab that does not fill its reservation - is
     * invisible in the world but obvious side by side here.
     */
    private void logLayout(DungeonLayout layout, Map<String, String> chosen) {
        List<String> lines = new ArrayList<>();
        for (DungeonLayout.Room room : layout.rooms()) {
            Bounds bounds = room.bounds();
            lines.add("room " + room.id() + " " + room.type().name().toLowerCase(Locale.ROOT)
                    + (room.role() == null ? "" : "/" + room.role())
                    + " x " + bounds.minX() + ".." + bounds.maxX() + " z " + bounds.minZ() + ".." + bounds.maxZ()
                    + " (" + bounds.sizeX() + "x" + bounds.sizeZ() + ") "
                    + chosen.getOrDefault(room.id(), "procedural"));
        }
        for (DungeonLayout.Tunnel tunnel : layout.tunnels()) {
            Bounds first = tunnel.firstDoorway();
            Bounds second = tunnel.secondDoorway();
            int gap = Math.max(Math.abs(second.minX() - first.minX()), Math.abs(second.minZ() - first.minZ()));
            lines.add("tunnel " + tunnel.id() + " doorways " + first.minX() + "," + first.minZ()
                    + " -> " + second.minX() + "," + second.minZ() + " wall gap " + gap);
        }
        plugin.getLogger().info("Layout for seed " + layout.seed() + ": " + String.join(" | ", lines));
    }

    /** One high-signal line per generated dungeon avoids hiding a bad prefab in console noise. */
    private void logFallback(DungeonLayout layout, List<String> reasons) {
        plugin.getLogger().severe("Schematic room fallback for dungeon seed " + layout.seed() + ": "
                + String.join(" | ", reasons) + ". Procedural stone rooms will be used for the listed slot(s).");
    }

    private static boolean usesPrefabSlot(DungeonLayout.Room room) {
        return PrefabType.from(room.type()) != PrefabType.UNKNOWN && room.variant() != DungeonLayout.RoomVariant.PARKOUR;
    }

    /**
     * Branches are deliberately linear: a side room can extend a branch, but
     * cannot split into another side branch. A two-door branch schematic is
     * therefore useful only if either configured branch-length limit exceeds one.
     */
    private List<NormalRoomShape> usableShapes(PrefabType type) {
        return switch (type) {
            case NORMAL -> List.of(NormalRoomShape.STRAIGHT, NormalRoomShape.CORNER,
                    NormalRoomShape.TJUNCTION, NormalRoomShape.CROSS);
            case BRANCH -> {
                int shortLimit = Math.max(1, plugin.getConfig().getInt("generation.branching.short-branch-max-length", 1));
                int longLimit = Math.max(shortLimit,
                        plugin.getConfig().getInt("generation.branching.long-branch-max-length", 2));
                if (Math.max(shortLimit, longLimit) > 1) {
                    yield List.of(NormalRoomShape.STRAIGHT, NormalRoomShape.CORNER, NormalRoomShape.DEAD_END);
                }
                yield List.of(NormalRoomShape.DEAD_END);
            }
            case SPAWN, BOSS, UNKNOWN -> List.of();
        };
    }

    /**
     * Audits the finished world, not just the plan. This catches a malformed
     * schematic that physically overwrote a corridor opening after validation.
     */
    public void verifyGenerated(World world, DungeonLayout layout, RoomPlan plan) {
        Map<String, DungeonLayout.Room> roomsById = new HashMap<>();
        for (DungeonLayout.Room room : layout.rooms()) roomsById.put(room.id(), room);

        for (DungeonLayout.Room room : layout.rooms()) {
            List<RoomPortal> portals = portals(room, layout.tunnels());
            if (plan.prefabRoomIds.contains(room.id())) {
                List<DungeonDoorway> prefabDoors = plan.doorways.getOrDefault(room.id(), List.of());
                for (DungeonDoorway doorway : prefabDoors) {
                    boolean connected = portals.stream().anyMatch(portal -> portal.facing == doorway.facing());
                    if (!connected) {
                        plugin.getLogger().severe("Dungeon doorway audit: room " + room.id() + " at "
                                + position(doorway.x(), doorway.y(), doorway.z()) + " facing " + doorway.facing()
                                + " has no connected corridor.");
                    }
                }
                for (RoomPortal portal : portals) {
                    boolean declared = prefabDoors.stream().anyMatch(doorway -> doorway.facing() == portal.facing);
                    if (!declared) {
                        plugin.getLogger().severe("Dungeon doorway audit: room " + room.id() + " at "
                                + position(portal.doorway.centreX(), portal.doorway.minY(), portal.doorway.centreZ())
                                + " facing " + portal.facing + " has a corridor but the placed prefab has no doorway.");
                    }
                }
            }
            for (RoomPortal portal : portals) auditPortalBlocks(world, room, portal);
        }

        for (DungeonLayout.Tunnel tunnel : layout.tunnels()) {
            if (!roomsById.containsKey(tunnel.firstRoomId()) || !roomsById.containsKey(tunnel.secondRoomId())) {
                plugin.getLogger().severe("Dungeon corridor audit: tunnel " + tunnel.firstRoomId() + "-"
                        + tunnel.secondRoomId() + " refers to a room that does not exist.");
            }
        }
    }

    private static List<RoomPortal> portals(DungeonLayout.Room room, List<DungeonLayout.Tunnel> tunnels) {
        List<RoomPortal> portals = new ArrayList<>();
        for (DungeonLayout.Tunnel tunnel : tunnels) {
            if (tunnel.firstRoomId().equals(room.id())) {
                portals.add(new RoomPortal(tunnel.firstDoorway(), face(room.bounds(), tunnel.firstDoorway())));
            } else if (tunnel.secondRoomId().equals(room.id())) {
                portals.add(new RoomPortal(tunnel.secondDoorway(), face(room.bounds(), tunnel.secondDoorway())));
            }
        }
        return List.copyOf(portals);
    }

    private void auditPortalBlocks(World world, DungeonLayout.Room room, RoomPortal portal) {
        Bounds doorway = portal.doorway;
        for (int y = doorway.minY(); y <= doorway.maxY(); y++) {
            for (int z = doorway.minZ(); z <= doorway.maxZ(); z++) {
                for (int x = doorway.minX(); x <= doorway.maxX(); x++) {
                    Material material = world.getBlockAt(x, y, z).getType();
                    if (!world.getBlockAt(x, y, z).isPassable()) {
                        plugin.getLogger().severe("Dungeon corridor audit: room " + room.id() + " at "
                                + position(x, y, z) + " facing " + portal.facing + " is blocked by " + material
                                + "; the corridor has no usable doorway.");
                        return;
                    }
                }
            }
        }
    }

    private static String position(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static void validateSpecialMarkers(PrefabType type, List<PrefabSpecialMarker> markers, Clipboard clipboard,
                                               BlockVector3 minimum, List<String> problems) {
        long playerSpawns = markers.stream().filter(marker -> marker.kind == SpecialMarkerKind.PLAYER_SPAWN).count();
        long bossSpawns = markers.stream().filter(marker -> marker.kind == SpecialMarkerKind.BOSS_SPAWN).count();
        if (type == PrefabType.SPAWN) {
            if (playerSpawns == 0) problems.add("Spawn room requires exactly one player-spawn marker, but found none.");
            if (playerSpawns > 1) problems.add("Spawn room requires exactly one player-spawn marker, but found " + playerSpawns + ".");
        }
        if (type == PrefabType.BOSS) {
            if (bossSpawns > 1) problems.add("Boss room may contain at most one optional boss-spawn marker, but found " + bossSpawns + ".");
        }
        for (PrefabSpecialMarker marker : markers) {
            boolean solidBelow = marker.y > 0 && BukkitAdapter.adapt(clipboard.getBlock(minimum.add(marker.x, marker.y - 1, marker.z)))
                    .getMaterial().isSolid();
            if (!solidBelow) {
                String problem = marker.kind == SpecialMarkerKind.PLAYER_SPAWN
                        ? "Player-spawn marker at " + marker.x + "," + marker.y + "," + marker.z + " needs solid ground directly below it."
                        : "Boss-spawn marker at " + marker.x + "," + marker.y + "," + marker.z + " needs solid ground directly below it.";
                problems.add(problem);
            }
        }
    }

    private LoadResult load(File file) {
        List<String> problems = new ArrayList<>();
        NameDeclaration declaration = declaration(file.getName());
        if (!declaration.parsed) {
            problems.add("Filename must start with normal_, branch_, spawn, or boss. After the prefix you may name a"
                    + " composed role, a shape, or both: branch_parkour, branch_parkour_straight, normal_cross.");
        }
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) {
            problems.add("WorldEdit could not detect a supported clipboard format.");
            return result(file, 0, 0, 0, null, null, List.of(), false, declaration.type, declaration.role,
                    NormalRoomShape.UNKNOWN, false, Map.of(), problems, null);
        }
        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            Clipboard clipboard = reader.read();
            BlockVector3 minimum = clipboard.getRegion().getMinimumPoint();
            BlockVector3 dimensions = clipboard.getDimensions();
            int width = dimensions.x();
            int height = dimensions.y();
            int depth = dimensions.z();
            FileConfiguration config = plugin.getConfig();
            MarkerMaterials markerMaterials = MarkerMaterials.read(config);
            List<PrefabBlock> blocks = new ArrayList<>();
            List<PrefabMarker> markers = new ArrayList<>();
            List<PrefabSpecialMarker> specialMarkers = new ArrayList<>();
            List<DoorMarker> doorwayMarkers = new ArrayList<>();
            List<PrefabPoint> trapColumns = new ArrayList<>();
            List<PrefabPoint> pressurePlates = new ArrayList<>();
            Map<String, Integer> markerCounts = new HashMap<>();
            LocalBounds contentBounds = null;
            LocalBounds structuralBounds = null;
            boolean legacyPurpleFound = false;
            for (BlockVector3 position : clipboard.getRegion()) {
                int x = position.x() - minimum.x();
                int y = position.y() - minimum.y();
                int z = position.z() - minimum.z();
                BlockState state = clipboard.getBlock(position);
                BlockData data = BukkitAdapter.adapt(state);
                Material material = data.getMaterial();
                // Cyan is a temporary authoring guide. It is neither a
                // marker nor structure content, and is never placed.
                if (material == Material.CYAN_WOOL) {
                    markerCounts.merge("ignored-cyan", 1, Integer::sum);
                    continue;
                }
                if (!material.isAir()) {
                    contentBounds = include(contentBounds, x, y, z);
                }
                BlockState replacementState = null;
                Material replacementMaterial = null;
                if (material == markerMaterials.doorway) {
                    markerCounts.merge("doorway", 1, Integer::sum);
                    doorwayMarkers.add(new DoorMarker(x, y, z, false));
                    if (markerMaterials.wallMatchDoorway) {
                        replacementState = sampleWallMaterial(clipboard, minimum, width, height, depth, x, y, z, material);
                        if (replacementState == null) {
                            problems.add("Doorway marker at " + x + "," + y + "," + z
                                    + " has no non-air neighbouring wall block to copy.");
                            replacementMaterial = Material.AIR;
                        }
                    } else {
                        replacementMaterial = markerMaterials.doorwayReplacement;
                    }
                } else if (material == markerMaterials.entrance) {
                    // Green follows the red convention exactly, but pins the
                    // room's rotation: players must come in through it.
                    markerCounts.merge("entrance-doorway", 1, Integer::sum);
                    doorwayMarkers.add(new DoorMarker(x, y, z, true));
                    if (markerMaterials.wallMatchEntrance) {
                        replacementState = sampleWallMaterial(clipboard, minimum, width, height, depth, x, y, z, material);
                        if (replacementState == null) {
                            problems.add("Doorway marker (entrance) at " + x + "," + y + "," + z
                                    + " has no non-air neighbouring wall block to copy.");
                            replacementMaterial = Material.AIR;
                        }
                    } else {
                        replacementMaterial = markerMaterials.entranceReplacement;
                    }
                } else if (material == markerMaterials.trapFloor) {
                    // The wool is the visible floor block that drops; it is
                    // swapped for a copy of the floor around it, so the trap
                    // cannot be read from inside the room.
                    markerCounts.merge("trap-floor", 1, Integer::sum);
                    trapColumns.add(new PrefabPoint(x, y, z));
                    if (markerMaterials.wallMatchTrapFloor) {
                        replacementState = sampleWallMaterial(clipboard, minimum, width, height, depth, x, y, z, material);
                        if (replacementState == null) {
                            problems.add("Trap-floor marker at " + x + "," + y + "," + z
                                    + " has no non-air neighbouring floor block to copy.");
                            replacementMaterial = Material.AIR;
                        }
                    } else {
                        replacementMaterial = markerMaterials.trapFloorReplacement;
                    }
                } else if (material == Material.PURPLE_WOOL) {
                    markerCounts.merge("legacy-purple", 1, Integer::sum);
                    replacementMaterial = markerMaterials.legacyPurpleReplacement;
                    legacyPurpleFound = true;
                } else {
                    SpecialMarkerKind special = markerMaterials.specialMarkers.get(material);
                    String category = markerMaterials.spawnCategories.get(material);
                    if (special != null) {
                        markerCounts.merge(special.configName(), 1, Integer::sum);
                        specialMarkers.add(new PrefabSpecialMarker(special, x, y, z));
                        // Special positions become entity feet locations, so
                        // they must never remain visible or solid after build.
                        replacementMaterial = Material.AIR;
                    } else if (category != null) {
                        markerCounts.merge(category, 1, Integer::sum);
                        markers.add(new PrefabMarker(category, x, y, z));
                        replacementMaterial = markerMaterials.spawnReplacement;
                    } else if (material == Material.LIGHT_GRAY_WOOL && declaration.type == PrefabType.SPAWN) {
                        markerCounts.merge("incorrect-player-spawn-light-gray", 1, Integer::sum);
                        problems.add("Player-spawn marker at " + x + "," + y + "," + z
                                + " uses LIGHT_GRAY_WOOL; use GRAY_WOOL instead.");
                    } else if (material.name().endsWith("_WOOL")) {
                        markerCounts.merge("unmapped-" + material.name().toLowerCase(Locale.ROOT), 1, Integer::sum);
                        problems.add("Unmapped wool marker " + material + " at " + x + "," + y + "," + z + ".");
                    } else if (org.bukkit.Tag.PRESSURE_PLATES.isTagged(material)) {
                        // Recorded for the trap system; the plate itself stays
                        // in the build exactly as authored.
                        markerCounts.merge("pressure-plate", 1, Integer::sum);
                        pressurePlates.add(new PrefabPoint(x, y, z));
                    }
                }
                if (!material.isAir() && material != markerMaterials.doorway && material != markerMaterials.entrance
                        && material != markerMaterials.trapFloor && material != Material.PURPLE_WOOL
                        && !markerMaterials.spawnCategories.containsKey(material)
                        && !markerMaterials.specialMarkers.containsKey(material)) {
                    structuralBounds = include(structuralBounds, x, y, z);
                }
                if (!material.isAir()) {
                    blocks.add(new PrefabBlock(x, y, z, state, replacementState, replacementMaterial));
                }
            }
            if (legacyPurpleFound) {
                problems.add("Found purple wool left from an older doorway convention; it is ignored as a spawn marker. Resave this room with red doorway markers.");
            }
            reportBounds(width, height, depth, contentBounds, structuralBounds, doorwayMarkers, problems);
            List<Integer> markerOffsets = verticalOffsets(doorwayMarkers, structuralBounds);
            if (contentBounds == null) {
                return result(file, width, height, depth, contentBounds, structuralBounds, markerOffsets, false, declaration.type,
                        declaration.role, NormalRoomShape.UNKNOWN, false, markerCounts, problems, null);
            }

            // WorldEdit selections often include an empty border. Rebase every
            // non-air block and marker to its real extent before validating or
            // placing it, so authoring padding never changes a room's footprint.
            LocalBounds content = contentBounds;
            BlockVector3 contentMinimum = minimum.add(content.minX, content.minY, content.minZ);
            int prefabWidth = content.sizeX();
            int prefabHeight = content.sizeY();
            int prefabDepth = content.sizeZ();
            blocks = blocks.stream().map(block -> new PrefabBlock(
                    block.x - content.minX, block.y - content.minY, block.z - content.minZ,
                    block.state, block.replacementState, block.replacementMaterial)).toList();
            markers = markers.stream().map(marker -> new PrefabMarker(marker.category,
                    marker.x - content.minX, marker.y - content.minY, marker.z - content.minZ)).toList();
            specialMarkers = specialMarkers.stream().map(marker -> new PrefabSpecialMarker(marker.kind,
                    marker.x - content.minX, marker.y - content.minY, marker.z - content.minZ)).toList();
            doorwayMarkers = doorwayMarkers.stream().map(marker -> new DoorMarker(
                    marker.x - content.minX, marker.y - content.minY, marker.z - content.minZ, marker.entrance)).toList();
            trapColumns = trapColumns.stream().map(point -> new PrefabPoint(
                    point.x - content.minX, point.y - content.minY, point.z - content.minZ)).toList();
            pressurePlates = pressurePlates.stream().map(point -> new PrefabPoint(
                    point.x - content.minX, point.y - content.minY, point.z - content.minZ)).toList();

            List<String> doorwayGroups = doorwayGroupDescriptions(doorwayMarkers, prefabWidth, prefabDepth);
            if (doorwayMarkers.isEmpty()) {
                problems.add("No red doorway marker blocks were found.");
            }
            List<PrefabDoorway> doors = parseDoorways(clipboard, contentMinimum, prefabWidth, prefabHeight, prefabDepth,
                    doorwayMarkers, config, problems);
            if (!doorwayMarkers.isEmpty() && doors.isEmpty()
                    && problems.stream().noneMatch(problem -> problem.startsWith("Doorway"))) {
                problems.add("No valid doorway marker group could be created from the red doorway markers.");
            }
            int placementYOffset = placementYOffset(doors, problems);
            Set<BlockFace> faces = new HashSet<>();
            for (PrefabDoorway door : doors) {
                if (!faces.add(door.facing)) problems.add("Doorway markers produce more than one doorway on the " + door.facing + " wall.");
            }
            NormalRoomShape shape = shape(faces);
            boolean nameMatches = declaration.parsed && (!declaration.shapeDeclared || declaration.shape == shape);
            if (declaration.parsed && declaration.shapeDeclared && !nameMatches) problems.add("Filename declares " + declaration.label
                    + " but the markers detect " + shape.configName() + ".");
            validateSpecialMarkers(declaration.type, specialMarkers, clipboard, contentMinimum, problems);
            if (!trapColumns.isEmpty()) {
                // Counted with the runtime's own rule, so the number here is
                // what will actually vanish - the point is spotting a column
                // that grabbed more than intended before testing in game.
                markerCounts.put("trap-blocks", trapBlockCount(trapColumns, blocks));
            }
            if (!trapColumns.isEmpty() && pressurePlates.isEmpty()) {
                // Deliberately non-fatal: the room still places, the trap
                // simply never arms, and this line says why.
                problems.add("Trap-floor markers found but no pressure plate; the trap can never fire.");
            }
            boolean specialMarkerError = problems.stream().anyMatch(problem -> problem.startsWith("Spawn room")
                    || problem.startsWith("Boss room") || problem.startsWith("Player-spawn") || problem.startsWith("Boss-spawn"));
            boolean valid = declaration.parsed && !doors.isEmpty() && doors.size() == faces.size()
                    && !specialMarkerError && problems.stream().noneMatch(problem -> problem.startsWith("Doorway"));
            Prefab prefab = valid ? new Prefab(file.getName(), prefabWidth, prefabHeight, prefabDepth, placementYOffset,
                    blocks, markers, specialMarkers, doors, trapColumns, pressurePlates,
                    declaration.type, declaration.role, shape) : null;
            return result(file, width, height, depth, contentBounds, structuralBounds, markerOffsets, valid, declaration.type,
                    declaration.role, shape, nameMatches, markerCounts, doorwayGroups, specialMarkers, problems, prefab);
        } catch (IOException | RuntimeException exception) {
            problems.add("Could not read schematic: " + exception.getMessage());
            plugin.getLogger().warning("Could not load room prefab " + file.getName() + ": " + exception.getMessage());
            return result(file, 0, 0, 0, null, null, List.of(), false, declaration.type, declaration.role,
                    NormalRoomShape.UNKNOWN, false, Map.of(), problems, null);
        }
    }

    /**
     * Treats one red block or a contiguous red strip as one doorway declaration.
     * The marker need only identify the wall: the actual air opening is found below it.
     * That accepts both a traditional marker directly above the opening and a marker
     * embedded in a ceiling band, without ever guessing a passage that is not present.
     */
    private List<PrefabDoorway> parseDoorways(Clipboard clipboard, BlockVector3 minimum, int width, int height, int depth,
                                               List<DoorMarker> markerBlocks, FileConfiguration config, List<String> problems) {
        Map<BlockFace, List<DoorMarker>> byFace = new EnumMap<>(BlockFace.class);
        for (DoorMarker marker : markerBlocks) {
            BlockFace face = wall(marker, width, depth);
            if (face == null) {
                problems.add("Doorway marker at " + marker.x + "," + marker.y + "," + marker.z
                        + " must be on exactly one outer wall.");
                continue;
            }
            byFace.computeIfAbsent(face, unused -> new ArrayList<>()).add(marker);
        }

        int minimumOpeningWidth = Math.max(1, config.getInt("generation.rooms.markers.doorway.minimum-opening-width", 3));
        int minimumOpeningHeight = Math.max(2, config.getInt("generation.rooms.markers.doorway.minimum-opening-height", 3));
        List<PrefabDoorway> doors = new ArrayList<>();
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
            List<DoorMarker> group = byFace.get(face);
            if (group == null || group.isEmpty()) continue;
            boolean entrance = group.getFirst().entrance;
            if (group.stream().anyMatch(marker -> marker.entrance != entrance)) {
                problems.add("Doorway markers on the " + face + " wall mix red and green;"
                        + " a wall is either the entrance or an exit.");
                continue;
            }
            group.sort(Comparator.comparingInt(marker -> cross(marker, face)));
            int markerY = group.getFirst().y;
            int minimumCross = cross(group.getFirst(), face);
            int maximumCross = cross(group.getLast(), face);
            boolean sameRow = group.stream().allMatch(marker -> marker.y == markerY);
            boolean contiguous = group.size() == maximumCross - minimumCross + 1;
            int expectedCentre = expectedCentre(face, width, depth);
            if (!sameRow || !contiguous || minimumCross + maximumCross != expectedCentre * 2) {
                List<String> issues = new ArrayList<>();
                if (!sameRow) issues.add("they are on more than one height");
                if (!contiguous) issues.add("there is a gap in the strip");
                if (minimumCross + maximumCross != expectedCentre * 2) {
                    issues.add("the strip is off-centre (centre " + (minimumCross + maximumCross) / 2
                            + ", expected " + expectedCentre + ")");
                }
                problems.add("Doorway markers on the " + face + " wall are invalid: " + String.join(", ", issues) + ".");
                continue;
            }
            Bounds opening = findOpening(clipboard, minimum, width, height, depth, face, expectedCentre, markerY,
                    minimumOpeningWidth, minimumOpeningHeight);
            if (opening == null) {
                problems.add("Doorway markers on the " + face + " wall have no air opening below them that is at least "
                        + minimumOpeningWidth + " blocks wide and " + minimumOpeningHeight + " blocks high.");
                continue;
            }
            doors.add(new PrefabDoorway(opening, face, entrance,
                    DoorMarkerGroup.of(face, markerY, minimumCross, maximumCross, width, depth)));
        }
        long entranceCount = doors.stream().filter(door -> door.entrance).count();
        if (entranceCount > 1) {
            problems.add("Doorway markers declare " + entranceCount + " green entrances; at most one is allowed.");
        }
        if (entranceCount > 0 && entranceCount == doors.size()) {
            problems.add("Doorway markers declare a green entrance but no red exit doorway.");
        }
        return List.copyOf(doors);
    }

    /** A human-readable summary keeps /dungeon rooms useful even when validation rejects a strip. */
    private static List<String> doorwayGroupDescriptions(List<DoorMarker> markers, int width, int depth) {
        if (markers.isEmpty()) return List.of();
        Map<BlockFace, List<DoorMarker>> byFace = new EnumMap<>(BlockFace.class);
        List<String> descriptions = new ArrayList<>();
        for (DoorMarker marker : markers) {
            BlockFace face = wall(marker, width, depth);
            if (face == null) {
                descriptions.add("unattached marker=" + marker.x + "," + marker.y + "," + marker.z
                        + " (not on exactly one outer wall)");
                continue;
            }
            byFace.computeIfAbsent(face, unused -> new ArrayList<>()).add(marker);
        }
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
            List<DoorMarker> group = byFace.get(face);
            if (group == null || group.isEmpty()) continue;
            group.sort(Comparator.comparingInt(marker -> cross(marker, face)));
            int markerY = group.getFirst().y;
            int minimumCross = cross(group.getFirst(), face);
            int maximumCross = cross(group.getLast(), face);
            boolean sameRow = group.stream().allMatch(marker -> marker.y == markerY);
            boolean contiguous = group.size() == maximumCross - minimumCross + 1;
            int expectedCentre = expectedCentre(face, width, depth);
            boolean centred = minimumCross + maximumCross == expectedCentre * 2;
            int centre = (minimumCross + maximumCross) / 2;
            int centreX = face == BlockFace.NORTH || face == BlockFace.SOUTH ? centre : face == BlockFace.WEST ? 0 : width - 1;
            int centreZ = face == BlockFace.NORTH ? 0 : face == BlockFace.SOUTH ? depth - 1 : centre;
            List<String> status = new ArrayList<>();
            long greens = group.stream().filter(marker -> marker.entrance).count();
            if (greens == group.size()) status.add("entrance");
            else if (greens > 0) status.add("MIXED red/green");
            status.add(sameRow ? "one height" : "mixed heights");
            status.add(contiguous ? "contiguous" : "gap in strip");
            status.add(centred ? "centred" : "off-centre (expected " + expectedCentre + ")");
            descriptions.add(face + " width=" + group.size() + " centre=" + centreX + ","
                    + (sameRow ? Integer.toString(markerY) : "mixed") + "," + centreZ
                    + " facing=" + face + " (" + String.join(", ", status) + ")");
        }
        return List.copyOf(descriptions);
    }

    private static BlockFace wall(DoorMarker marker, int width, int depth) {
        int walls = (marker.x == 0 ? 1 : 0) + (marker.x == width - 1 ? 1 : 0)
                + (marker.z == 0 ? 1 : 0) + (marker.z == depth - 1 ? 1 : 0);
        if (walls != 1) return null;
        if (marker.x == 0) return BlockFace.WEST;
        if (marker.x == width - 1) return BlockFace.EAST;
        return marker.z == 0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    private static int cross(DoorMarker marker, BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH ? marker.x : marker.z;
    }

    private static int expectedCentre(BlockFace face, int width, int depth) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH ? (width - 1) / 2 : (depth - 1) / 2;
    }

    private static Bounds findOpening(Clipboard clipboard, BlockVector3 minimum, int width, int height, int depth,
                                      BlockFace face, int centre, int markerY, int minimumWidth, int minimumHeight) {
        OpeningRun best = null;
        int y = 0;
        while (y < Math.min(markerY, height)) {
            while (y < Math.min(markerY, height) && !isAir(clipboard, minimum, width, depth, face, centre, y)) y++;
            int bottom = y;
            while (y < Math.min(markerY, height) && isAir(clipboard, minimum, width, depth, face, centre, y)) y++;
            int top = y - 1;
            if (top < bottom || top - bottom + 1 < minimumHeight) continue;
            Span base = airSpan(clipboard, minimum, width, depth, face, centre, bottom);
            if (base == null || base.width() < minimumWidth) continue;
            OpeningRun candidate = new OpeningRun(bottom, top, base);
            if (best == null || candidate.height() > best.height()
                    || (candidate.height() == best.height() && candidate.bottom < best.bottom)) {
                best = candidate;
            }
        }
        if (best == null) return null;

        int minimumCross = best.base.minimum;
        int maximumCross = best.base.maximum;
        for (int openingY = best.bottom; openingY <= best.top; openingY++) {
            Span span = airSpan(clipboard, minimum, width, depth, face, centre, openingY);
            if (span == null) continue;
            minimumCross = Math.min(minimumCross, span.minimum);
            maximumCross = Math.max(maximumCross, span.maximum);
        }
        return switch (face) {
            case NORTH -> new Bounds(minimumCross, best.bottom, 0, maximumCross, best.top, 0);
            case SOUTH -> new Bounds(minimumCross, best.bottom, depth - 1, maximumCross, best.top, depth - 1);
            case WEST -> new Bounds(0, best.bottom, minimumCross, 0, best.top, maximumCross);
            case EAST -> new Bounds(width - 1, best.bottom, minimumCross, width - 1, best.top, maximumCross);
            default -> throw new IllegalArgumentException("Doorways must face a cardinal direction.");
        };
    }

    private static Span airSpan(Clipboard clipboard, BlockVector3 minimum, int width, int depth,
                                BlockFace face, int centre, int y) {
        if (!isAir(clipboard, minimum, width, depth, face, centre, y)) return null;
        int limit = face == BlockFace.NORTH || face == BlockFace.SOUTH ? width : depth;
        int low = centre;
        int high = centre;
        while (low > 0 && isAir(clipboard, minimum, width, depth, face, low - 1, y)) low--;
        while (high < limit - 1 && isAir(clipboard, minimum, width, depth, face, high + 1, y)) high++;
        return new Span(low, high);
    }

    private static boolean isAir(Clipboard clipboard, BlockVector3 minimum, int width, int depth,
                                 BlockFace face, int cross, int y) {
        BlockVector3 position = switch (face) {
            case NORTH -> minimum.add(cross, y, 0);
            case SOUTH -> minimum.add(cross, y, depth - 1);
            case WEST -> minimum.add(0, y, cross);
            case EAST -> minimum.add(width - 1, y, cross);
            default -> throw new IllegalArgumentException("Doorways must face a cardinal direction.");
        };
        return BukkitAdapter.adapt(clipboard.getBlock(position)).getMaterial().isAir();
    }

    /**
     * A doorway marker replaces itself with a neighbouring wall block. Looking
     * along the wall first avoids copying the air of the actual doorway below it.
     */
    private static BlockState sampleWallMaterial(Clipboard clipboard, BlockVector3 minimum, int width, int height, int depth,
                                                 int x, int y, int z, Material marker) {
        List<BlockVector3> candidates = List.of(
                BlockVector3.at(x - 1, y, z), BlockVector3.at(x + 1, y, z),
                BlockVector3.at(x, y - 1, z), BlockVector3.at(x, y + 1, z),
                BlockVector3.at(x, y, z - 1), BlockVector3.at(x, y, z + 1));
        Map<BlockState, Integer> counts = new HashMap<>();
        for (BlockVector3 local : candidates) {
            if (local.x() < 0 || local.x() >= width || local.y() < 0 || local.y() >= height
                    || local.z() < 0 || local.z() >= depth) continue;
            BlockState state = clipboard.getBlock(minimum.add(local));
            Material material = BukkitAdapter.adapt(state).getMaterial();
            if (!material.isAir() && material != marker) {
                counts.merge(state, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static LocalBounds include(LocalBounds bounds, int x, int y, int z) {
        return bounds == null ? new LocalBounds(x, y, z, x, y, z) : bounds.include(x, y, z);
    }

    /** Adds validation information without rejecting a room prefab merely for harmless padding. */
    private static void reportBounds(int width, int height, int depth, LocalBounds content, LocalBounds structural,
                                     List<DoorMarker> markerBlocks, List<String> problems) {
        if (content == null) {
            problems.add("The schematic contains no non-air blocks.");
            return;
        }
        // Doorway markers may deliberately live in a separate authoring layer.
        // Their vertical convention is reported separately and never makes a room invalid.
        if (content.sizeX() != width || content.sizeY() != height || content.sizeZ() != depth) {
            problems.add("Empty outer padding will be trimmed: content is " + content.dimensions()
                    + " inside the stated " + width + "x" + height + "x" + depth + " selection.");
        }
    }

    private static List<Integer> verticalOffsets(List<DoorMarker> markers, LocalBounds structural) {
        if (structural == null) return List.of();
        return markers.stream().map(marker -> marker.y - structural.maxY).distinct().sorted().toList();
    }

    private static int placementYOffset(List<PrefabDoorway> doors, List<String> problems) {
        if (doors.isEmpty()) return 0;
        int openingBottom = doors.getFirst().opening.minY();
        if (doors.stream().anyMatch(doorway -> doorway.opening.minY() != openingBottom)) {
            problems.add("Doorway openings do not share one floor height, so a flat dungeon corridor cannot align them.");
        }
        // DungeonLayout corridors enter at one block above a room's layout floor.
        return 1 - openingBottom;
    }

    /**
     * A roled slot prefers prefabs carrying its role token and falls back to
     * the generic pool; roled prefabs never serve any other slot, so an
     * authored guardian lair cannot appear as an ordinary side room.
     */
    private List<Candidate> candidates(DungeonLayout.Room room, Set<BlockFace> required, BlockFace arrivalFace) {
        PrefabType wantedType = PrefabType.from(room.type());
        if (room.role() != null) {
            List<Candidate> roled = candidates(wantedType, room.role(), required, arrivalFace);
            if (!roled.isEmpty()) return roled;
        }
        return candidates(wantedType, null, required, arrivalFace);
    }

    private List<Candidate> candidates(PrefabType wantedType, String role, Set<BlockFace> required, BlockFace arrivalFace) {
        List<Candidate> candidates = new ArrayList<>();
        for (Prefab prefab : prefabs) {
            if (prefab.type != wantedType || !java.util.Objects.equals(prefab.role, role)) continue;
            BlockFace pinnedEntrance = prefab.entranceFace();
            for (int rotation : new int[]{0, 90, 180, 270}) {
                Set<BlockFace> faces = new HashSet<>();
                for (PrefabDoorway doorway : prefab.doorways) faces.add(rotate(doorway.facing, rotation));
                if (!faces.equals(required)) continue;
                // A green entrance restricts the exact-match rotations further:
                // players must arrive through that specific opening.
                if (pinnedEntrance != null && arrivalFace != null
                        && rotate(pinnedEntrance, rotation) != arrivalFace) continue;
                candidates.add(new Candidate(prefab, rotation));
            }
        }
        candidates.sort(Comparator.comparing((Candidate candidate) -> candidate.prefab.fileName, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(candidate -> candidate.rotation));
        return candidates;
    }

    /**
     * How many blocks a trap floor will take with it: everything below each
     * marked block down to the room's foundation, plus whatever stands on it.
     * Only non-air positions count, because only those are ever removed.
     */
    private int trapBlockCount(List<PrefabPoint> columns, List<PrefabBlock> blocks) {
        int maximumRise = Math.max(0, plugin.getConfig().getInt("trap.max-column-height", 8));
        Set<Long> filled = new HashSet<>();
        Set<Long> wanted = new HashSet<>();
        for (PrefabPoint column : columns) {
            wanted.add(((long) column.x << 32) | (column.z & 0xFFFFFFFFL));
        }
        for (PrefabBlock block : blocks) {
            if (wanted.contains(((long) block.x << 32) | (block.z & 0xFFFFFFFFL))) {
                filled.add(packed(block.x, block.y, block.z));
            }
        }
        int total = 0;
        for (PrefabPoint column : columns) {
            for (int y = column.y; y >= 0; y--) {
                if (filled.contains(packed(column.x, y, column.z))) total++;
            }
            total += DungeonTrap.rise(y -> filled.contains(packed(column.x, y, column.z)),
                    column.y, maximumRise);
        }
        return total;
    }

    private static long packed(int x, int y, int z) {
        return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF);
    }

    /** Every loaded file for one role with the doorways each rotation offers. */
    private String rolePoolDescription(PrefabType type, String role) {
        List<Prefab> pool = prefabs.stream()
                .filter(prefab -> prefab.type == type && role.equals(prefab.role)).toList();
        if (pool.isEmpty()) return "no file with that role is loaded (check /dungeon rooms for a rejected one)";
        List<String> descriptions = new ArrayList<>();
        for (Prefab prefab : pool) {
            List<String> rotations = new ArrayList<>();
            for (int rotation : new int[]{0, 90, 180, 270}) {
                Set<BlockFace> rotated = new HashSet<>();
                for (PrefabDoorway doorway : prefab.doorways) rotated.add(rotate(doorway.facing, rotation));
                rotations.add(rotation + "° " + faces(rotated));
            }
            descriptions.add(prefab.fileName + " [" + String.join(", ", rotations) + "]");
        }
        return String.join("; ", descriptions);
    }

    /** The face this room is entered through: the doorway towards its parent. */
    private static BlockFace arrivalFace(DungeonLayout.Room room, List<DungeonLayout.Tunnel> tunnels) {
        for (DungeonLayout.Tunnel tunnel : tunnels) {
            if (tunnel.secondRoomId().equals(room.id())) {
                return face(room.bounds(), tunnel.secondDoorway());
            }
        }
        return null;
    }

    /** Detailed, deterministic report emitted only when an exact door match is missing. */
    private String candidateDiagnostics(DungeonLayout.Room room, Set<BlockFace> required) {
        PrefabType wantedType = PrefabType.from(room.type());
        List<Prefab> matchingType = prefabs.stream().filter(prefab -> prefab.type == wantedType).toList();
        if (matchingType.isEmpty()) return "no valid " + wantedType.configName() + " prefabs are loaded";
        List<String> descriptions = new ArrayList<>();
        for (Prefab prefab : matchingType) {
            List<String> rotations = new ArrayList<>();
            for (int rotation : new int[]{0, 90, 180, 270}) {
                Point dimensions = rotatedDimensions(prefab.width, prefab.depth, rotation);
                Set<BlockFace> rotatedFaces = new HashSet<>();
                for (PrefabDoorway doorway : prefab.doorways) rotatedFaces.add(rotate(doorway.facing, rotation));
                BlockFace entrance = prefab.entranceFace();
                rotations.add(rotation + "° doors " + faces(rotatedFaces)
                        + (entrance == null ? "" : " entrance " + rotate(entrance, rotation)));
            }
            descriptions.add(prefab.fileName + " [" + String.join(", ", rotations) + "]");
        }
        return String.join("; ", descriptions);
    }

    private static PlacementOrigin origin(DungeonLayout.Room room, Candidate candidate) {
        Point dimensions = rotatedDimensions(candidate.prefab.width, candidate.prefab.depth, candidate.rotation);
        return new PlacementOrigin(room.bounds().centreX() - (dimensions.x - 1) / 2,
                room.bounds().minY() + candidate.prefab.placementYOffset,
                room.bounds().centreZ() - (dimensions.z - 1) / 2);
    }

    private BuildOperation blocks(Candidate candidate, PlacementOrigin origin) {
        Transform transform = new AffineTransform().rotateY(-candidate.rotation);
        List<BlockListOperation.Entry> entries = new ArrayList<>(candidate.prefab.blocks.size());
        for (PrefabBlock block : candidate.prefab.blocks) {
            Point point = rotate(block.x, block.z, candidate.prefab.width, candidate.prefab.depth, candidate.rotation);
            BlockData data = block.replacementState != null
                    ? BukkitAdapter.adapt(BlockTransformExtent.transform(block.replacementState, transform))
                    : block.replacementMaterial != null
                    ? block.replacementMaterial.createBlockData()
                    : BukkitAdapter.adapt(BlockTransformExtent.transform(block.state, transform));
            entries.add(new BlockListOperation.Entry(origin.x + point.x,
                    origin.y + block.y, origin.z + point.z, data));
        }
        return new BlockListOperation(entries);
    }

    /**
     * Runtime containment begins at the logical corridor floor, not at a decorative
     * foundation below it. Its ceiling is the prefab's real ceiling after the
     * doorway-alignment offset, so constrained endermen cannot teleport into the
     * empty space above a raised prefab roof.
     */
    private static Bounds playableBounds(Candidate candidate, PlacementOrigin origin) {
        Point dimensions = rotatedDimensions(candidate.prefab.width, candidate.prefab.depth, candidate.rotation);
        int physicalMinimumY = origin.y;
        int physicalMaximumY = physicalMinimumY + candidate.prefab.height - 1;
        return new Bounds(origin.x, physicalMinimumY, origin.z,
                origin.x + dimensions.x - 1, physicalMaximumY, origin.z + dimensions.z - 1);
    }

    private static EnumSet<BlockFace> requiredFaces(DungeonLayout.Room room, List<DungeonLayout.Tunnel> tunnels) {
        EnumSet<BlockFace> faces = EnumSet.noneOf(BlockFace.class);
        for (DungeonLayout.Tunnel tunnel : tunnels) {
            if (tunnel.firstRoomId().equals(room.id())) faces.add(face(room.bounds(), tunnel.firstDoorway()));
            if (tunnel.secondRoomId().equals(room.id())) faces.add(face(room.bounds(), tunnel.secondDoorway()));
        }
        return faces;
    }

    private static BlockFace face(Bounds room, Bounds doorway) {
        if (doorway.minX() == room.minX()) return BlockFace.WEST;
        if (doorway.maxX() == room.maxX()) return BlockFace.EAST;
        if (doorway.minZ() == room.minZ()) return BlockFace.NORTH;
        if (doorway.maxZ() == room.maxZ()) return BlockFace.SOUTH;
        throw new IllegalArgumentException("Doorway does not lie on its room boundary.");
    }

    private static NormalRoomShape shape(Set<BlockFace> faces) {
        return switch (faces.size()) {
            case 1 -> NormalRoomShape.DEAD_END;
            case 2 -> opposite(faces) ? NormalRoomShape.STRAIGHT : NormalRoomShape.CORNER;
            case 3 -> NormalRoomShape.TJUNCTION;
            case 4 -> NormalRoomShape.CROSS;
            default -> NormalRoomShape.UNKNOWN;
        };
    }

    private static boolean opposite(Set<BlockFace> faces) {
        return (faces.contains(BlockFace.NORTH) && faces.contains(BlockFace.SOUTH))
                || (faces.contains(BlockFace.EAST) && faces.contains(BlockFace.WEST));
    }

    private static BlockFace rotate(BlockFace face, int rotation) {
        return switch (Math.floorMod(rotation, 360)) {
            case 0 -> face;
            case 90 -> switch (face) {
                case NORTH -> BlockFace.EAST; case EAST -> BlockFace.SOUTH; case SOUTH -> BlockFace.WEST; case WEST -> BlockFace.NORTH;
                default -> face;
            };
            case 180 -> switch (face) {
                case NORTH -> BlockFace.SOUTH; case EAST -> BlockFace.WEST; case SOUTH -> BlockFace.NORTH; case WEST -> BlockFace.EAST;
                default -> face;
            };
            case 270 -> switch (face) {
                case NORTH -> BlockFace.WEST; case EAST -> BlockFace.NORTH; case SOUTH -> BlockFace.EAST; case WEST -> BlockFace.SOUTH;
                default -> face;
            };
            default -> throw new IllegalArgumentException("Only right-angle room rotations are supported.");
        };
    }

    private static Point rotate(int x, int z, int width, int depth, int rotation) {
        return switch (Math.floorMod(rotation, 360)) {
            case 0 -> new Point(x, z);
            case 90 -> new Point(depth - 1 - z, x);
            case 180 -> new Point(width - 1 - x, depth - 1 - z);
            case 270 -> new Point(z, width - 1 - x);
            default -> throw new IllegalArgumentException("Only right-angle room rotations are supported.");
        };
    }

    private static Point rotatedDimensions(int width, int depth, int rotation) {
        return Math.floorMod(rotation, 180) == 0 ? new Point(width, depth) : new Point(depth, width);
    }

    private static String faces(Set<BlockFace> faces) {
        return faces.stream().map(Enum::name).sorted().reduce((left, right) -> left + ", " + right).orElse("none");
    }

    private static Material material(FileConfiguration config, String path, Material fallback) {
        String raw = config.getString(path, fallback.name());
        Material material = raw == null ? null : Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
        return material == null || !material.isBlock() ? fallback : material;
    }

    private static <T> Map<String, List<T>> immutable(Map<String, List<T>> input) {
        Map<String, List<T>> result = new HashMap<>();
        input.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    /**
     * Reads {@code (normal|branch)[_role][_shape][_number]}.
     *
     * <p>Both middle parts are optional, so {@code branch_straight} keeps its
     * old meaning while {@code branch_parkour} and
     * {@code branch_parkour_straight} both bind to the parkour role. The shape
     * is only ever a declaration: it is checked against the doorways actually
     * found, and a file that omits it is validated purely on its markers.</p>
     */
    private static NameDeclaration declaration(String fileName) {
        int extension = fileName.lastIndexOf('.');
        String stem = extension < 0 ? fileName : fileName.substring(0, extension);
        Matcher special = SPECIAL_FILE_NAME.matcher(stem);
        if (special.matches()) {
            return new NameDeclaration(true, PrefabType.fromPrefix(special.group(1)), null,
                    NormalRoomShape.UNKNOWN, special.group(1).toLowerCase(Locale.ROOT), false);
        }
        List<String> tokens = new ArrayList<>(List.of(stem.toLowerCase(Locale.ROOT).split("_")));
        PrefabType type = tokens.isEmpty() ? PrefabType.UNKNOWN : PrefabType.fromPrefix(tokens.removeFirst());
        if (type != PrefabType.NORMAL && type != PrefabType.BRANCH) {
            return new NameDeclaration(false, PrefabType.UNKNOWN, null, NormalRoomShape.UNKNOWN, "unparsed", false);
        }
        if (!tokens.isEmpty() && tokens.getLast().chars().allMatch(Character::isDigit)) {
            tokens.removeLast();
        }
        NormalRoomShape shape = NormalRoomShape.UNKNOWN;
        // Two shape words are spelled with an underscore, so they are matched
        // before the single-token ones.
        if (tokens.size() >= 2) {
            NormalRoomShape paired = shape(tokens.get(tokens.size() - 2) + "_" + tokens.getLast());
            if (paired != NormalRoomShape.UNKNOWN) {
                shape = paired;
                tokens.removeLast();
                tokens.removeLast();
            }
        }
        if (shape == NormalRoomShape.UNKNOWN && !tokens.isEmpty()) {
            NormalRoomShape single = shape(tokens.getLast());
            if (single != NormalRoomShape.UNKNOWN) {
                shape = single;
                tokens.removeLast();
            }
        }
        String role = tokens.isEmpty() ? null : String.join("_", tokens);
        String label = (role == null ? "" : role) + (shape == NormalRoomShape.UNKNOWN ? "" :
                (role == null ? "" : "_") + shape.configName());
        return new NameDeclaration(true, type, role, shape, label.isEmpty() ? type.configName() : label,
                shape != NormalRoomShape.UNKNOWN);
    }

    private static NormalRoomShape shape(String declared) {
        return switch (declared) {
            case "straight" -> NormalRoomShape.STRAIGHT;
            case "corner_r", "corner_l", "corner" -> NormalRoomShape.CORNER;
            case "tjunction" -> NormalRoomShape.TJUNCTION;
            case "cross" -> NormalRoomShape.CROSS;
            case "dead_end" -> NormalRoomShape.DEAD_END;
            default -> NormalRoomShape.UNKNOWN;
        };
    }

    private static LoadResult result(File file, int width, int height, int depth, LocalBounds content, LocalBounds structural,
                                     List<Integer> markerOffsets, boolean valid, PrefabType type, String role, NormalRoomShape shape,
                                     boolean nameMatches, Map<String, Integer> markers, List<String> problems, Prefab prefab) {
        return result(file, width, height, depth, content, structural, markerOffsets, valid, type, role, shape, nameMatches,
                markers, List.of(), List.of(), problems, prefab);
    }

    private static LoadResult result(File file, int width, int height, int depth, LocalBounds content, LocalBounds structural,
                                     List<Integer> markerOffsets, boolean valid, PrefabType type, String role, NormalRoomShape shape,
                                     boolean nameMatches, Map<String, Integer> markers, List<String> doorwayGroups,
                                     List<PrefabSpecialMarker> specialMarkers, List<String> problems, Prefab prefab) {
        String actual = content == null ? "none" : content.dimensions();
        String trimmed = structural == null ? "none" : structural.trimDescription(width, height, depth);
        String filenameMatch = type == PrefabType.SPAWN || type == PrefabType.BOSS
                ? "not checked" : nameMatches ? "matches" : "does not match";
        List<String> reportedProblems = new ArrayList<>(problems);
        if (!valid && reportedProblems.isEmpty()) {
            reportedProblems.add("Rejected without a recorded validation reason; this is a DungeonForge reporting bug.");
        }
        return new LoadResult(prefab, new Inspection(file.getName(), width, height, depth, actual, trimmed,
                List.copyOf(markerOffsets), valid, type, role, shape, filenameMatch, Map.copyOf(markers),
                List.copyOf(doorwayGroups), specialMarkerPositions(specialMarkers), List.copyOf(reportedProblems)));
    }

    private static List<String> specialMarkerPositions(List<PrefabSpecialMarker> markers) {
        return markers.stream().map(marker -> marker.kind.configName() + "=" + marker.x + "," + marker.y + "," + marker.z).toList();
    }

    private record Prefab(String fileName, int width, int height, int depth, int placementYOffset, List<PrefabBlock> blocks,
                          List<PrefabMarker> markers, List<PrefabSpecialMarker> specialMarkers, List<PrefabDoorway> doorways,
                          List<PrefabPoint> trapColumns, List<PrefabPoint> pressurePlates,
                          PrefabType type, String role, NormalRoomShape shape) {
        private Prefab {
            blocks = List.copyOf(blocks);
            markers = List.copyOf(markers);
            specialMarkers = List.copyOf(specialMarkers);
            doorways = List.copyOf(doorways);
            trapColumns = List.copyOf(trapColumns);
            pressurePlates = List.copyOf(pressurePlates);
        }

        /** The green-pinned entrance face, or null when the room is free to rotate. */
        private BlockFace entranceFace() {
            return doorways.stream().filter(PrefabDoorway::entrance).map(PrefabDoorway::facing).findFirst().orElse(null);
        }
    }

    private record PrefabBlock(int x, int y, int z, BlockState state, BlockState replacementState,
                               Material replacementMaterial) { }
    private record PrefabMarker(String category, int x, int y, int z) { }
    private record PrefabSpecialMarker(SpecialMarkerKind kind, int x, int y, int z) { }
    private record PrefabPoint(int x, int y, int z) { }
    private record DoorMarker(int x, int y, int z, boolean entrance) { }
    private record Span(int minimum, int maximum) {
        private int width() { return maximum - minimum + 1; }
    }
    private record OpeningRun(int bottom, int top, Span base) {
        private int height() { return top - bottom + 1; }
    }
    private record DoorMarkerGroup(int centreX, int y, int centreZ, BlockFace facing, int width) {
        private static DoorMarkerGroup of(BlockFace face, int y, int minimumCross, int maximumCross, int width, int depth) {
            int centre = (minimumCross + maximumCross) / 2;
            return switch (face) {
                case NORTH -> new DoorMarkerGroup(centre, y, 0, face, maximumCross - minimumCross + 1);
                case SOUTH -> new DoorMarkerGroup(centre, y, depth - 1, face, maximumCross - minimumCross + 1);
                case WEST -> new DoorMarkerGroup(0, y, centre, face, maximumCross - minimumCross + 1);
                case EAST -> new DoorMarkerGroup(width - 1, y, centre, face, maximumCross - minimumCross + 1);
                default -> throw new IllegalArgumentException("Doorway markers must be cardinal.");
            };
        }
    }
    private record PrefabDoorway(Bounds opening, BlockFace facing, boolean entrance, DoorMarkerGroup marker) { }
    private record RoomPortal(Bounds doorway, BlockFace facing) { }
    private record Candidate(Prefab prefab, int rotation) { }
    private record PlacementOrigin(int x, int y, int z) { }
    private record Point(int x, int z) { }
    private record NameDeclaration(boolean parsed, PrefabType type, String role, NormalRoomShape shape, String label,
                                   boolean shapeDeclared) { }
    private record LoadResult(Prefab prefab, Inspection inspection) { }

    private record MarkerMaterials(Material doorway, boolean wallMatchDoorway, Material doorwayReplacement,
                                    Material entrance, boolean wallMatchEntrance, Material entranceReplacement,
                                    Material trapFloor, boolean wallMatchTrapFloor, Material trapFloorReplacement,
                                    Material spawnReplacement, Material legacyPurpleReplacement,
                                    Map<Material, String> spawnCategories, Map<Material, SpecialMarkerKind> specialMarkers,
                                    Map<SpecialMarkerKind, Material> specialReplacements) {
        private Material specialReplacement(SpecialMarkerKind kind) {
            return specialReplacements.getOrDefault(kind, Material.AIR);
        }
        private static boolean wallMatching(FileConfiguration config, String path) {
            String raw = config.getString(path, "WALL_MATCHING");
            return raw != null && raw.equalsIgnoreCase("WALL_MATCHING");
        }
        private static MarkerMaterials read(FileConfiguration config) {
            Material doorway = material(config, "generation.rooms.markers.doorway.material", Material.RED_WOOL);
            boolean wallMatchDoorway = wallMatching(config, "generation.rooms.markers.replacements.doorway");
            Material doorwayReplacement = wallMatchDoorway ? Material.AIR : material(config,
                    "generation.rooms.markers.replacements.doorway", Material.AIR);
            Material entrance = material(config, "generation.rooms.markers.entrance.material", Material.GREEN_WOOL);
            boolean wallMatchEntrance = wallMatching(config, "generation.rooms.markers.replacements.entrance");
            Material entranceReplacement = wallMatchEntrance ? Material.AIR : material(config,
                    "generation.rooms.markers.replacements.entrance", Material.AIR);
            Material trapFloor = material(config, "generation.rooms.markers.trap-floor.material", Material.YELLOW_WOOL);
            boolean wallMatchTrapFloor = wallMatching(config, "generation.rooms.markers.replacements.trap-floor");
            Material trapFloorReplacement = wallMatchTrapFloor ? Material.AIR : material(config,
                    "generation.rooms.markers.replacements.trap-floor", Material.AIR);
            Material spawnReplacement = material(config, "generation.rooms.markers.replacements.spawn", Material.AIR);
            Material legacyPurpleReplacement = material(config, "generation.rooms.markers.replacements.legacy-purple", Material.AIR);
            Map<Material, String> categories = new HashMap<>();
            var section = config.getConfigurationSection("mobs.markers.materials");
            if (section != null) for (String category : section.getKeys(false)) {
                Material marker = material(config, "mobs.markers.materials." + category, Material.WHITE_WOOL);
                categories.put(marker, category.toLowerCase(Locale.ROOT));
            }
            categories.remove(doorway);
            categories.remove(entrance);
            categories.remove(trapFloor);
            categories.remove(Material.PURPLE_WOOL);
            Map<Material, SpecialMarkerKind> specialMarkers = new HashMap<>();
            Map<SpecialMarkerKind, Material> specialReplacements = new EnumMap<>(SpecialMarkerKind.class);
            for (SpecialMarkerKind kind : SpecialMarkerKind.values()) {
                Material marker = material(config, "generation.rooms.markers." + kind.configName() + ".material",
                        kind == SpecialMarkerKind.PLAYER_SPAWN ? Material.GRAY_WOOL : Material.LIGHT_BLUE_WOOL);
                specialMarkers.put(marker, kind);
                specialReplacements.put(kind, material(config, "generation.rooms.markers.replacements." + kind.configName(), Material.AIR));
            }
            specialMarkers.remove(doorway);
            specialMarkers.remove(entrance);
            specialMarkers.remove(trapFloor);
            specialMarkers.remove(Material.PURPLE_WOOL);
            categories.keySet().removeAll(specialMarkers.keySet());
            return new MarkerMaterials(doorway, wallMatchDoorway, doorwayReplacement,
                    entrance, wallMatchEntrance, entranceReplacement,
                    trapFloor, wallMatchTrapFloor, trapFloorReplacement, spawnReplacement,
                    legacyPurpleReplacement, Map.copyOf(categories), Map.copyOf(specialMarkers), Map.copyOf(specialReplacements));
        }
    }

    /** The filename-declared pool a prefab may serve. */
    public enum PrefabType {
        NORMAL("normal"),
        BRANCH("branch"),
        SPAWN("spawn"),
        BOSS("boss"),
        UNKNOWN("unknown");

        private final String configName;

        PrefabType(String configName) {
            this.configName = configName;
        }

        public String configName() {
            return configName;
        }

        private static PrefabType fromPrefix(String prefix) {
            return switch (prefix.toLowerCase(Locale.ROOT)) {
                case "normal" -> NORMAL;
                case "branch" -> BRANCH;
                case "spawn" -> SPAWN;
                case "boss" -> BOSS;
                default -> UNKNOWN;
            };
        }

        private static PrefabType from(DungeonLayout.RoomType type) {
            return switch (type) {
                case NORMAL -> NORMAL;
                case BRANCH -> BRANCH;
                case SPAWN -> SPAWN;
                case BOSS -> BOSS;
            };
        }
    }

    /** Maximum envelope used only while the generator keeps rooms apart. */
    public record PlanningDimensions(int width, int height, int depth) { }

    /** Immutable data used by /dungeon rooms. */
    public record Inspection(String fileName, int width, int height, int depth, String actualDimensions,
                             String trimmedDimensions, List<Integer> markerVerticalOffsets, boolean valid, PrefabType type, String role,
                             NormalRoomShape shape, String filenameMatch, Map<String, Integer> markerCounts, List<String> doorwayGroups,
                             List<String> specialMarkers, List<String> problems) {
        public String dimensions() { return width + "x" + height + "x" + depth; }
        /** The pool label shown by /dungeon rooms: the type plus any role token. */
        public String displayType() { return role == null ? type.configName() : type.configName() + " " + role; }
        public String markers() {
            return markerCounts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).reduce((left, right) -> left + ", " + right).orElse("none");
        }
        public String markerOffsets() {
            return markerVerticalOffsets.isEmpty() ? "none" : markerVerticalOffsets.stream()
                    .map(offset -> "y" + (offset >= 0 ? "+" : "") + offset)
                    .reduce((left, right) -> left + ", " + right).orElse("none");
        }
        public String displayDoorwayGroups() { return doorwayGroups.isEmpty() ? "none" : String.join("; ", doorwayGroups); }
        public String corridorOffsetCompatibility(List<Integer> corridorOffsets) {
            if (markerVerticalOffsets.isEmpty()) return "cannot compare: no room doorway-marker offset";
            if (corridorOffsets.isEmpty()) return "cannot compare: no valid corridor marker offset is loaded";
            return markerVerticalOffsets.equals(corridorOffsets)
                    ? "matches the reported structural-top convention"
                    : "different structural-top convention (informational; exact red-to-purple marker matching controls placement)";
        }
        public String displaySpecialMarkers() { return specialMarkers.isEmpty() ? "none" : String.join(", ", specialMarkers); }
        public String displayProblems() { return problems.isEmpty() ? "none" : String.join(" | ", problems); }
    }

    /** Tick-spread blocks plus marker metadata for selected prefab rooms. */
    public record RoomPlan(Set<String> prefabRoomIds, List<BuildOperation> operations,
                           Map<String, List<DungeonMarker>> markers, Map<String, List<DungeonDoorway>> doorways,
                           Map<String, List<DungeonDoorMarker>> doorwayMarkers, Map<String, Bounds> playableBounds,
                           Map<String, DungeonSpecialMarker> playerSpawns, Map<String, DungeonSpecialMarker> bossSpawns,
                           Map<String, DungeonTrap> traps, Map<String, String> prefabFiles,
                           List<String> requiredPrefabFailures) {
        public boolean hasRequiredPrefabFailures() { return !requiredPrefabFailures.isEmpty(); }
        public static RoomPlan empty() { return new RoomPlan(Set.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of()); }
        public static RoomPlan withRequiredFailures(List<String> failures) {
            return new RoomPlan(Set.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.copyOf(failures));
        }
    }

    private record LocalBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        private LocalBounds include(int x, int y, int z) {
            return new LocalBounds(Math.min(minX, x), Math.min(minY, y), Math.min(minZ, z),
                    Math.max(maxX, x), Math.max(maxY, y), Math.max(maxZ, z));
        }
        private int sizeX() { return maxX - minX + 1; }
        private int sizeY() { return maxY - minY + 1; }
        private int sizeZ() { return maxZ - minZ + 1; }
        private String dimensions() { return sizeX() + "x" + sizeY() + "x" + sizeZ(); }
        private String trimDescription(int statedWidth, int statedHeight, int statedDepth) {
            return dimensions() + " (margins x " + minX + "/" + (statedWidth - 1 - maxX)
                    + ", y " + minY + "/" + (statedHeight - 1 - maxY)
                    + ", z " + minZ + "/" + (statedDepth - 1 - maxZ) + ")";
        }
    }
}
