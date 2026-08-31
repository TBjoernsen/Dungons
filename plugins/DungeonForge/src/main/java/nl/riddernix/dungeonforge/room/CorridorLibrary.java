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
import nl.riddernix.dungeonforge.generation.Bounds;
import nl.riddernix.dungeonforge.generation.BuildOperation;
import nl.riddernix.dungeonforge.generation.DungeonLayout;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Loads repeatable corridor segments. WorldEdit is used only as a schematic
 * reader; every transformed block is still written by DungeonForge's cursor
 * builder.
 */
public final class CorridorLibrary {
    private static final List<BlockFace> CARDINAL = List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST);

    private final DungeonForgePlugin plugin;
    private final File folder;
    private List<Prefab> prefabs = List.of();
    private List<Inspection> inspections = List.of();
    private boolean worldEditAvailable;
    private String unavailableReason = "no valid corridor schematic is loaded";

    public CorridorLibrary(DungeonForgePlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "corridors");
    }

    /** Reloads corridor segments and preserves an inspection record for every file. */
    public void reload() {
        if (!folder.isDirectory() && !folder.mkdirs()) {
            plugin.getLogger().severe("Could not create DungeonForge corridor folder: " + folder.getAbsolutePath());
        }
        Plugin worldEdit = plugin.getServer().getPluginManager().getPlugin("WorldEdit");
        worldEditAvailable = worldEdit != null && worldEdit.isEnabled();
        if (!worldEditAvailable) {
            plugin.getLogger().severe("WorldEdit is missing or disabled. Schematic corridors are unavailable; procedural corridors remain active.");
            prefabs = List.of();
            inspections = List.of(new Inspection("(WorldEdit unavailable)", "none", "none", "none", "none", List.of(), false,
                    List.of("WorldEdit is missing or disabled.")));
            unavailableReason = "WorldEdit is missing or disabled";
            return;
        }

        File[] files = folder.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            plugin.getLogger().info("No corridor schematics found in " + folder.getAbsolutePath()
                    + "; procedural corridors remain active.");
            prefabs = List.of();
            inspections = List.of();
            unavailableReason = "the corridor folder is empty";
            return;
        }
        List<File> ordered = new ArrayList<>(List.of(files));
        ordered.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        List<Prefab> loaded = new ArrayList<>();
        List<Inspection> report = new ArrayList<>();
        for (File file : ordered) {
            LoadResult result = load(file);
            report.add(result.inspection);
            if (result.prefab != null) {
                loaded.add(result.prefab);
            } else {
                plugin.getLogger().warning("Rejected corridor " + file.getName() + ": " + result.inspection.displayProblems());
            }
        }
        prefabs = List.copyOf(loaded);
        inspections = List.copyOf(report);
        if (prefabs.isEmpty()) {
            plugin.getLogger().warning("No valid corridor schematics loaded from " + folder.getAbsolutePath()
                    + "; procedural corridors remain active.");
            unavailableReason = "no valid corridor schematic is loaded: " + report.stream()
                    .map(inspection -> inspection.fileName + " (" + inspection.displayProblems() + ")")
                    .reduce((left, right) -> left + "; " + right).orElse("no files found");
        } else {
            plugin.getLogger().info("Loaded " + prefabs.size() + " corridor schematic(s) from " + folder.getAbsolutePath() + ".");
            unavailableReason = null;
        }
    }

    public File folder() { return folder; }
    public List<Inspection> inspections() { return inspections; }

    /** Plans tile operations only for tunnels whose two connectors can be aligned exactly. */
    public CorridorPlan plan(DungeonLayout layout, NormalRoomLibrary.RoomPlan rooms) {
        if (layout.tunnels().isEmpty()) return CorridorPlan.empty();
        if (!worldEditAvailable || prefabs.isEmpty()) {
            logFallback(layout, List.of(unavailableReason == null ? "no valid corridor schematic is loaded" : unavailableReason));
            return CorridorPlan.empty();
        }
        Map<String, DungeonLayout.Room> roomsById = new HashMap<>();
        for (DungeonLayout.Room room : layout.rooms()) roomsById.put(room.id(), room);

        List<BuildOperation> operations = new ArrayList<>();
        Set<String> schematicTunnelIds = new HashSet<>();
        List<String> fallbackReasons = new ArrayList<>();
        for (DungeonLayout.Tunnel tunnel : layout.tunnels()) {
            DungeonLayout.Room firstRoom = roomsById.get(tunnel.firstRoomId());
            DungeonLayout.Room secondRoom = roomsById.get(tunnel.secondRoomId());
            if (firstRoom == null || secondRoom == null) continue;
            Endpoint first = endpoint(firstRoom, tunnel.firstDoorway(), rooms.doorwayMarkers());
            Endpoint second = endpoint(secondRoom, tunnel.secondDoorway(), rooms.doorwayMarkers());
            CandidateSearch search = candidates(tunnel, first, second);
            if (search.candidates.isEmpty()) {
                // The room boxes are printed too: a marker mismatch is almost
                // always a room placed at the wrong distance, not a bad tile.
                fallbackReasons.add("tunnel " + tunnel.id() + ": " + String.join(", ", search.reasons)
                        + " [" + endpointDescription("first", first) + " in room " + firstRoom.id()
                        + " " + box(firstRoom.bounds()) + "; " + endpointDescription("second", second)
                        + " in room " + secondRoom.id() + " " + box(secondRoom.bounds()) + "]");
                continue;
            }
            Candidate candidate = search.candidates.get(new Random(layout.seed() ^ tunnel.id().hashCode() * 0x9e3779b97f4a7c15L)
                    .nextInt(search.candidates.size()));
            operations.addAll(tileOperations(candidate));
            schematicTunnelIds.add(tunnel.id());
        }
        if (!fallbackReasons.isEmpty()) {
            logFallback(layout, fallbackReasons);
        }
        return new CorridorPlan(Set.copyOf(schematicTunnelIds), List.copyOf(operations));
    }

    /** One high-signal entry per dungeon lists every schematic corridor rejection. */
    private void logFallback(DungeonLayout layout, List<String> reasons) {
        plugin.getLogger().severe("Schematic corridor fallback for dungeon seed " + layout.seed() + ": "
                + String.join(" | ", reasons) + ". Procedural corridors will be used for the listed tunnel(s).");
    }

    private Endpoint endpoint(DungeonLayout.Room room, Bounds doorway,
                              Map<String, List<DungeonDoorMarker>> prefabMarkers) {
        BlockFace face = face(room.bounds(), doorway);
        DungeonDoorMarker marker = prefabMarkers.getOrDefault(room.id(), List.of()).stream()
                .filter(candidate -> candidate.facing() == face).findFirst().orElse(null);
        if (marker == null) {
            int heightAboveFloor = Math.max(1, plugin.getConfig().getInt(
                    "generation.corridor.schematic.virtual-door-marker.height-above-floor", 30));
            marker = new DungeonDoorMarker(doorway.centreX(), doorway.minY() - 1 + heightAboveFloor,
                    doorway.centreZ(), face, Math.max(1, plugin.getConfig().getInt(
                    "generation.corridor.schematic.virtual-door-marker.width", 3)) | 1);
        }
        return new Endpoint(marker);
    }

    /** Marker centres, widths, faces, and the tile stride are the only placement constraints. */
    private CandidateSearch candidates(DungeonLayout.Tunnel tunnel, Endpoint first, Endpoint second) {
        List<Candidate> result = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (Prefab prefab : prefabs) {
            boolean faceMatched = false;
            boolean widthMatched = false;
            boolean distanceMatched = false;
            for (int rotation : new int[]{0, 90, 180, 270}) {
                for (Connector from : prefab.connectors) {
                    Connector to = prefab.other(from);
                    if (rotate(from.face, rotation) != opposite(first.marker.facing())
                            || rotate(to.face, rotation) != opposite(second.marker.facing())) continue;
                    faceMatched = true;
                    if (from.width != first.marker.width() || to.width != second.marker.width()) continue;
                    widthMatched = true;
                    Point fromPoint = rotate(from.x, from.z, prefab.width, prefab.depth, rotation);
                    Point toPoint = rotate(to.x, to.z, prefab.width, prefab.depth, rotation);
                    int originX = first.marker.x() - fromPoint.x;
                    int originY = first.marker.y() - from.y;
                    int originZ = first.marker.z() - fromPoint.z;
                    int stepX = toPoint.x - fromPoint.x;
                    int stepZ = toPoint.z - fromPoint.z;
                    int tiles = tilesToJoin(second.marker.x() - first.marker.x(), second.marker.z() - first.marker.z(), stepX, stepZ);
                    if (tiles < 1 || first.marker.y() != second.marker.y()) continue;
                    distanceMatched = true;
                    int lastOriginX = second.marker.x() - toPoint.x;
                    int lastOriginZ = second.marker.z() - toPoint.z;
                    result.add(new Candidate(prefab, rotation, originX, originY, originZ, stepX, stepZ, tiles,
                            lastOriginX, lastOriginZ));
                }
            }
            if (!faceMatched) reasons.add(prefab.fileName + " has no rotation with connector faces matching the room doorways");
            else if (!widthMatched) reasons.add(prefab.fileName + " connector width does not match the room doorway marker width");
            else if (!distanceMatched) {
                if (first.marker.y() != second.marker.y()) {
                    reasons.add(prefab.fileName + " cannot join doorway markers at different world heights ("
                            + first.marker.y() + " and " + second.marker.y() + ") in a flat corridor");
                } else {
                    reasons.add(prefab.fileName + " does not lie on the corridor axis or is too close to join");
                }
            }
        }
        result.sort(Comparator.comparing((Candidate candidate) -> candidate.prefab.fileName, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(candidate -> candidate.rotation));
        if (reasons.isEmpty()) reasons.add("no corridor candidate passed exact connector alignment");
        return new CandidateSearch(List.copyOf(result), List.copyOf(reasons));
    }

    /**
     * Exact connector placement matters more than an exact multiple of the
     * tile stride. The final repeat may overlap the preceding tile slightly,
     * which is safe for a deliberately tileable middle segment and lets room
     * schematics use their natural dimensions.
     */
    private static int tilesToJoin(int deltaX, int deltaZ, int stepX, int stepZ) {
        Integer tilesX = axisTiles(deltaX, stepX);
        Integer tilesZ = axisTiles(deltaZ, stepZ);
        if (tilesX != null && tilesX < 1 || tilesZ != null && tilesZ < 1) return -1;
        if (tilesX != null && tilesZ != null && !tilesX.equals(tilesZ)) return -1;
        Integer result = tilesX != null ? tilesX : tilesZ;
        return result == null ? -1 : result;
    }

    /** A null result means this axis is stationary and therefore imposes no tile count. */
    private static Integer axisTiles(int delta, int step) {
        if (step == 0) return delta == 0 ? null : Integer.valueOf(-1);
        if (Integer.signum(delta) != Integer.signum(step)) return Integer.valueOf(-1);
        int distance = Math.abs(delta);
        int stride = Math.abs(step);
        if (distance < stride) return Integer.valueOf(2);
        return Integer.valueOf((distance + stride - 1) / stride);
    }

    private List<BuildOperation> tileOperations(Candidate candidate) {
        Transform transform = new AffineTransform().rotateY(-candidate.rotation);
        List<BuildOperation> result = new ArrayList<>();
        for (int tile = 0; tile < candidate.tiles; tile++) {
            List<BlockListOperation.Entry> entries = new ArrayList<>(candidate.prefab.blocks.size());
            int offsetX = tile == candidate.tiles - 1 ? candidate.lastOriginX : candidate.originX + candidate.stepX * tile;
            int offsetZ = tile == candidate.tiles - 1 ? candidate.lastOriginZ : candidate.originZ + candidate.stepZ * tile;
            for (SourceBlock block : candidate.prefab.blocks) {
                Point point = rotate(block.x, block.z, candidate.prefab.width, candidate.prefab.depth, candidate.rotation);
                BlockData data = block.replacement == null
                        ? BukkitAdapter.adapt(BlockTransformExtent.transform(block.state, transform))
                        : block.replacement.createBlockData();
                entries.add(new BlockListOperation.Entry(offsetX + point.x, candidate.originY + block.y, offsetZ + point.z, data));
            }
            result.add(new BlockListOperation(entries));
        }
        return List.copyOf(result);
    }

    private LoadResult load(File file) {
        List<String> problems = new ArrayList<>();
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) {
            problems.add("WorldEdit could not detect a supported clipboard format.");
            return result(file, null, null, null, List.of(), List.of(), false, problems, null);
        }
        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            Clipboard clipboard = reader.read();
            BlockVector3 minimum = clipboard.getRegion().getMinimumPoint();
            BlockVector3 dimensions = clipboard.getDimensions();
            int width = dimensions.x();
            int height = dimensions.y();
            int depth = dimensions.z();
            Materials materials = Materials.read(plugin.getConfig());
            List<SourceBlock> source = new ArrayList<>();
            List<MarkerPoint> connectorMarkers = new ArrayList<>();
            List<MarkerPoint> guideMarkers = new ArrayList<>();
            LocalBounds content = null;
            LocalBounds structural = null;
            Set<Material> unknownWool = new HashSet<>();
            for (BlockVector3 position : clipboard.getRegion()) {
                int x = position.x() - minimum.x();
                int y = position.y() - minimum.y();
                int z = position.z() - minimum.z();
                BlockState state = clipboard.getBlock(position);
                Material material = BukkitAdapter.adapt(state).getMaterial();
                if (material.isAir()) continue;
                content = include(content, x, y, z);
                Material replacement = null;
                if (material == materials.connector) {
                    connectorMarkers.add(new MarkerPoint(x, y, z));
                    replacement = materials.connectorReplacement;
                } else if (material == materials.guide) {
                    guideMarkers.add(new MarkerPoint(x, y, z));
                    replacement = materials.guideReplacement;
                } else {
                    structural = include(structural, x, y, z);
                    if (material.name().endsWith("_WOOL")) unknownWool.add(material);
                }
                source.add(new SourceBlock(x, y, z, state, replacement));
            }
            if (content == null || structural == null) {
                problems.add("The schematic has no structural non-air content after marker blocks are removed.");
            }
            if (!unknownWool.isEmpty()) {
                problems.add("Unmapped wool will be placed as decoration: " + unknownWool.stream().map(Material::name).sorted()
                        .reduce((left, right) -> left + ", " + right).orElse("none") + ".");
            }
            List<Connector> connectors = parseConnectors(connectorMarkers, width, depth, problems);
            reportBounds(width, height, depth, content, structural, connectorMarkers, guideMarkers, problems);
            List<Integer> markerOffsets = verticalOffsets(connectors, structural);
            boolean valid = structural != null && connectors.size() == 2 && connectors.getFirst().face == opposite(connectors.getLast().face)
                    && connectors.getFirst().width == connectors.getLast().width && connectors.getFirst().y == connectors.getLast().y;
            if (connectors.size() == 2 && (connectors.getFirst().face != opposite(connectors.getLast().face)
                    || connectors.getFirst().width != connectors.getLast().width || connectors.getFirst().y != connectors.getLast().y)) {
                problems.add("The two purple connector groups must have equal odd widths, one height, and opposite faces.");
            }
            Prefab prefab = valid ? new Prefab(file.getName(), width, height, depth, source, connectors) : null;
            return result(file, new LocalBounds(0, 0, 0, width - 1, height - 1, depth - 1), content, structural,
                    connectors, markerOffsets, valid, problems, prefab);
        } catch (IOException | RuntimeException exception) {
            problems.add("Could not read schematic: " + exception.getMessage());
            plugin.getLogger().warning("Could not load corridor " + file.getName() + ": " + exception.getMessage());
            return result(file, null, null, null, List.of(), List.of(), false, problems, null);
        }
    }

    private static List<Connector> parseConnectors(List<MarkerPoint> markers, int width, int depth,
                                                   List<String> problems) {
        Set<MarkerPoint> unvisited = new HashSet<>(markers);
        List<Connector> connectors = new ArrayList<>();
        while (!unvisited.isEmpty()) {
            MarkerPoint seed = unvisited.iterator().next();
            List<MarkerPoint> group = new ArrayList<>();
            ArrayDeque<MarkerPoint> pending = new ArrayDeque<>();
            pending.add(seed);
            unvisited.remove(seed);
            while (!pending.isEmpty()) {
                MarkerPoint point = pending.removeFirst();
                group.add(point);
                List<MarkerPoint> neighbours = unvisited.stream().filter(candidate -> point.adjacent(candidate)).toList();
                for (MarkerPoint neighbour : neighbours) {
                    unvisited.remove(neighbour);
                    pending.add(neighbour);
                }
            }
            Connector connector = connector(group, width, depth);
            if (connector == null) {
                problems.add("Purple connector markers must form one contiguous odd-width line on one outer side wall.");
            } else {
                connectors.add(connector);
            }
        }
        connectors.sort(Comparator.comparing(connector -> connector.face.name()));
        if (connectors.size() != 2) {
            problems.add("Expected exactly two purple connector groups, but found " + connectors.size() + ".");
        }
        return List.copyOf(connectors);
    }

    private static Connector connector(List<MarkerPoint> group, int width, int depth) {
        int y = group.getFirst().y;
        if (group.stream().anyMatch(point -> point.y != y)) return null;
        BlockFace face = wall(group.getFirst(), width, depth);
        if (face == null || group.stream().anyMatch(point -> wall(point, width, depth) != face)) return null;
        boolean alongX = face == BlockFace.NORTH || face == BlockFace.SOUTH;
        int min = group.stream().mapToInt(point -> alongX ? point.x : point.z).min().orElseThrow();
        int max = group.stream().mapToInt(point -> alongX ? point.x : point.z).max().orElseThrow();
        if (group.size() != max - min + 1 || (group.size() & 1) == 0) return null;
        int centre = (min + max) / 2;
        int x = alongX ? centre : face == BlockFace.WEST ? 0 : width - 1;
        int z = alongX ? face == BlockFace.NORTH ? 0 : depth - 1 : centre;
        return new Connector(x, y, z, face, group.size());
    }

    private static void reportBounds(int width, int height, int depth, LocalBounds content, LocalBounds structural,
                                     List<MarkerPoint> connectors, List<MarkerPoint> guides, List<String> problems) {
        if (content != null && (content.sizeX() != width || content.sizeY() != height || content.sizeZ() != depth)) {
            problems.add("Empty outer padding will be trimmed: content is " + content.dimensions() + " inside the stated "
                    + width + "x" + height + "x" + depth + " selection.");
        }
        // Marker blocks deliberately may sit in a separate authoring layer.
        // Their relative offset is reported, but does not affect validity.
    }

    private static List<Integer> verticalOffsets(List<Connector> connectors, LocalBounds structural) {
        if (structural == null) return List.of();
        return connectors.stream().map(connector -> connector.y - structural.maxY).distinct().sorted().toList();
    }

    private static LocalBounds include(LocalBounds bounds, int x, int y, int z) {
        return bounds == null ? new LocalBounds(x, y, z, x, y, z) : bounds.include(x, y, z);
    }

    private static BlockFace wall(MarkerPoint point, int width, int depth) {
        int walls = (point.x == 0 ? 1 : 0) + (point.x == width - 1 ? 1 : 0)
                + (point.z == 0 ? 1 : 0) + (point.z == depth - 1 ? 1 : 0);
        if (walls != 1) return null;
        if (point.x == 0) return BlockFace.WEST;
        if (point.x == width - 1) return BlockFace.EAST;
        return point.z == 0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    private static BlockFace face(Bounds room, Bounds doorway) {
        if (doorway.minX() == room.minX()) return BlockFace.WEST;
        if (doorway.maxX() == room.maxX()) return BlockFace.EAST;
        if (doorway.minZ() == room.minZ()) return BlockFace.NORTH;
        if (doorway.maxZ() == room.maxZ()) return BlockFace.SOUTH;
        throw new IllegalArgumentException("Doorway does not lie on its room boundary.");
    }

    private static BlockFace opposite(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.SOUTH;
            case EAST -> BlockFace.WEST;
            case SOUTH -> BlockFace.NORTH;
            case WEST -> BlockFace.EAST;
            default -> throw new IllegalArgumentException("Only cardinal connector faces are supported.");
        };
    }

    private static BlockFace rotate(BlockFace face, int rotation) {
        return switch (Math.floorMod(rotation, 360)) {
            case 0 -> face;
            case 90 -> switch (face) {
                case NORTH -> BlockFace.EAST; case EAST -> BlockFace.SOUTH; case SOUTH -> BlockFace.WEST; case WEST -> BlockFace.NORTH;
                default -> face;
            };
            case 180 -> opposite(face);
            case 270 -> switch (face) {
                case NORTH -> BlockFace.WEST; case EAST -> BlockFace.NORTH; case SOUTH -> BlockFace.EAST; case WEST -> BlockFace.SOUTH;
                default -> face;
            };
            default -> throw new IllegalArgumentException("Only right-angle corridor rotations are supported.");
        };
    }

    private static Point rotate(int x, int z, int width, int depth, int rotation) {
        return switch (Math.floorMod(rotation, 360)) {
            case 0 -> new Point(x, z);
            case 90 -> new Point(depth - 1 - z, x);
            case 180 -> new Point(width - 1 - x, depth - 1 - z);
            case 270 -> new Point(z, width - 1 - x);
            default -> throw new IllegalArgumentException("Only right-angle corridor rotations are supported.");
        };
    }

    private static String box(Bounds bounds) {
        return bounds.minX() + ".." + bounds.maxX() + " x " + bounds.minZ() + ".." + bounds.maxZ()
                + " (" + bounds.sizeX() + "x" + bounds.sizeZ() + ")";
    }

    private static String endpointDescription(String label, Endpoint endpoint) {
        return label + " red marker " + endpoint.marker.x() + "," + endpoint.marker.y() + "," + endpoint.marker.z()
                + " facing " + endpoint.marker.facing() + " width " + endpoint.marker.width();
    }

    private static Material material(FileConfiguration config, String path, Material fallback) {
        String raw = config.getString(path, fallback.name());
        Material material = raw == null ? null : Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
        return material == null || !material.isBlock() ? fallback : material;
    }

    private static LoadResult result(File file, LocalBounds stated, LocalBounds content, LocalBounds structural,
                                     List<Connector> connectors, List<Integer> markerOffsets, boolean valid,
                                     List<String> problems, Prefab prefab) {
        return new LoadResult(prefab, new Inspection(file.getName(), stated == null ? "none" : stated.dimensions(),
                content == null ? "none" : content.dimensions(), structural == null || stated == null ? "none"
                : structural.trimDescription(stated.sizeX(), stated.sizeY(), stated.sizeZ()),
                connectors.stream().map(Connector::description).reduce((left, right) -> left + "; " + right).orElse("none"),
                List.copyOf(markerOffsets), valid, List.copyOf(problems)));
    }

    private record Materials(Material connector, Material guide, Material connectorReplacement, Material guideReplacement) {
        private static Materials read(FileConfiguration config) {
            return new Materials(material(config, "generation.corridor.schematic.markers.connector.material", Material.PURPLE_WOOL),
                    material(config, "generation.corridor.schematic.markers.guide.material", Material.CYAN_WOOL),
                    material(config, "generation.corridor.schematic.markers.replacements.connector", Material.AIR),
                    material(config, "generation.corridor.schematic.markers.replacements.guide", Material.AIR));
        }
    }

    private record Prefab(String fileName, int width, int height, int depth, List<SourceBlock> blocks, List<Connector> connectors) {
        private Prefab { blocks = List.copyOf(blocks); connectors = List.copyOf(connectors); }
        private Connector other(Connector connector) { return connectors.getFirst().equals(connector) ? connectors.getLast() : connectors.getFirst(); }
    }
    private record SourceBlock(int x, int y, int z, BlockState state, Material replacement) { }
    private record MarkerPoint(int x, int y, int z) {
        private boolean adjacent(MarkerPoint other) {
            return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z) == 1;
        }
    }
    private record Connector(int x, int y, int z, BlockFace face, int width) {
        private String description() { return face + " centre=" + x + "," + y + "," + z + " width=" + width; }
    }
    private record Endpoint(DungeonDoorMarker marker) { }
    private record Candidate(Prefab prefab, int rotation, int originX, int originY, int originZ, int stepX, int stepZ, int tiles,
                             int lastOriginX, int lastOriginZ) { }
    private record Point(int x, int z) { }
    private record LoadResult(Prefab prefab, Inspection inspection) { }
    private record CandidateSearch(List<Candidate> candidates, List<String> reasons) { }
    private record LocalBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        private LocalBounds include(int x, int y, int z) {
            return new LocalBounds(Math.min(minX, x), Math.min(minY, y), Math.min(minZ, z),
                    Math.max(maxX, x), Math.max(maxY, y), Math.max(maxZ, z));
        }
        private int sizeX() { return maxX - minX + 1; }
        private int sizeY() { return maxY - minY + 1; }
        private int sizeZ() { return maxZ - minZ + 1; }
        private boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
        private String dimensions() { return sizeX() + "x" + sizeY() + "x" + sizeZ(); }
        private String trimDescription(int statedWidth, int statedHeight, int statedDepth) {
            return dimensions() + " (margins x " + minX + "/" + (statedWidth - 1 - maxX)
                    + ", y " + minY + "/" + (statedHeight - 1 - maxY)
                    + ", z " + minZ + "/" + (statedDepth - 1 - maxZ) + ")";
        }
    }

    /** Immutable data used by /dungeon corridors. */
    public record Inspection(String fileName, String statedDimensions, String actualDimensions, String trimmedDimensions,
                             String connectors, List<Integer> markerVerticalOffsets, boolean valid, List<String> problems) {
        public String markerOffsets() {
            return markerVerticalOffsets.isEmpty() ? "none" : markerVerticalOffsets.stream()
                    .map(offset -> "y" + (offset >= 0 ? "+" : "") + offset)
                    .reduce((left, right) -> left + ", " + right).orElse("none");
        }
        public String displayProblems() { return problems.isEmpty() ? "none" : String.join(" | ", problems); }
    }

    /** Tick-spread operations plus the tunnels that should not receive a procedural floor or lip. */
    public record CorridorPlan(Set<String> schematicTunnelIds, List<BuildOperation> operations) {
        public static CorridorPlan empty() { return new CorridorPlan(Set.of(), List.of()); }
    }
}
