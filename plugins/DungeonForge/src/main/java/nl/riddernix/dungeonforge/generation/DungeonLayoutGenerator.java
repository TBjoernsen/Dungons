package nl.riddernix.dungeonforge.generation;

import nl.riddernix.dungeonforge.room.DungeonMarker;
import nl.riddernix.dungeonforge.room.NormalRoomLibrary;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Plans a connected, non-overlapping dungeon tree.
 *
 * <p>The critical path leaves the fixed entrance through its configured
 * cardinal face, then grows outward to the boss. Optional rooms branch from that spine. This iteration
 * keeps every room on one level; the critical path itself may turn left or
 * right while continuing to move outward from the entrance.
 * Planning rejects candidates whose room or tunnel would
 * intersect anything except the two rooms it is intentionally joining.</p>
 */
public final class DungeonLayoutGenerator {

    private final Settings settings;

    public DungeonLayoutGenerator(FileConfiguration config) {
        this(config, null);
    }

    /** Uses the loaded prefab envelopes to leave enough space for any room size. */
    public DungeonLayoutGenerator(FileConfiguration config, NormalRoomLibrary roomLibrary) {
        this.settings = Settings.fromConfig(config, roomLibrary);
    }

    public DungeonLayout generate(int difficulty, long seed) throws GenerationException {
        if (difficulty < 1 || difficulty > 9) {
            throw new GenerationException("Difficulty must be between 1 and 9.");
        }
        Random random = new Random(seed);
        Composition composition = settings.composition(difficulty);
        // A composed difficulty derives its room count from the authored path;
        // the rooms-per-difficulty entry is deliberately ignored for it.
        int targetRooms = composition == null ? settings.roomsForDifficulty(difficulty)
                : composition.pathRoles().size() + 2;

        Bounds entranceBounds = new Bounds(settings.originX, settings.originY, settings.originZ,
                settings.originX + settings.entranceWidth - 1,
                settings.originY + settings.entranceHeight - 1,
                settings.originZ + settings.entranceDepth - 1);
        Direction heading = settings.spawnExitDirection;
        RoomNode entrance = new RoomNode(new DungeonLayout.Room("1", DungeonLayout.RoomType.SPAWN, entranceBounds, 0, DungeonLayout.RoomVariant.PLAIN),
                0, false, heading);
        List<RoomNode> nodes = new ArrayList<>();
        nodes.add(entrance);
        List<DungeonLayout.Tunnel> tunnels = new ArrayList<>();
        boolean longBranchUsed = false;

        int criticalRoomCount = composition == null ? settings.criticalRoomCount(targetRooms) : targetRooms;
        List<RoomNode> spine = new ArrayList<>();
        spine.add(entrance);
        int roomsSinceTurn = 0;
        int roomsBeforeTurn = settings.randomRoomsBeforeTurn(random);
        // Composed paths run straight by default, so every role slot needs
        // exactly one authored prefab shape rather than a shape per turn case.
        boolean allowTurns = composition == null || composition.allowTurns();

        for (int index = 1; index < criticalRoomCount; index++) {
            DungeonLayout.RoomType type = index == criticalRoomCount - 1
                    ? DungeonLayout.RoomType.BOSS : DungeonLayout.RoomType.NORMAL;
            // Roles are stamped at placement time so a role pool with its own
            // footprint reserves its own size.
            String role = composition != null && type == DungeonLayout.RoomType.NORMAL
                    ? composition.pathRoles().get(index - 1) : null;
            RoomNode parent = spine.getLast();
            Direction nextHeading = heading;
            Placement placement = null;

            if (allowTurns && roomsSinceTurn >= roomsBeforeTurn) {
                Direction firstTurn = random.nextBoolean() ? heading.left() : heading.right();
                placement = findPlacement(parent, nodes, tunnels, Integer.toString(index + 1), type, role, index, firstTurn, random, true);
                if (placement == null) {
                    Direction secondTurn = firstTurn == heading.left() ? heading.right() : heading.left();
                    placement = findPlacement(parent, nodes, tunnels, Integer.toString(index + 1), type, role, index, secondTurn, random, true);
                }
                if (placement != null) {
                    nextHeading = placement.direction;
                    roomsSinceTurn = 0;
                    roomsBeforeTurn = settings.randomRoomsBeforeTurn(random);
                }
            }
            if (placement == null) {
                placement = findPlacement(parent, nodes, tunnels, Integer.toString(index + 1), type, role, index, heading, random, true);
            }
            if (placement == null) {
                throw new GenerationException("Could not place critical-path room " + (index + 1) + " of " + criticalRoomCount
                        + ". Increase generation.max-placement-attempts, comparison spacing, or reduce room sizes.");
            }
            placement.parent.usedFaces.add(placement.direction);
            placement.node.usedFaces.add(placement.direction.opposite());
            nodes.add(placement.node);
            spine.add(placement.node);
            tunnels.add(placement.tunnel);
            heading = nextHeading;
            roomsSinceTurn++;
        }

        for (int index = criticalRoomCount; index < targetRooms; index++) {
            Placement placement = findSidePlacement(spine, nodes, tunnels, random, longBranchUsed);
            if (placement == null) {
                throw new GenerationException("Could not place side room " + (index - criticalRoomCount + 1)
                        + " of " + (targetRooms - criticalRoomCount)
                        + ". Reduce generation.branching.branch-frequency or room sizes.");
            }
            placement.parent.usedFaces.add(placement.direction);
            placement.node.usedFaces.add(placement.direction.opposite());
            nodes.add(placement.node);
            tunnels.add(placement.tunnel);
            if (spine.contains(placement.parent) && placement.node.longBranch) {
                longBranchUsed = true;
            }
        }

        DungeonLayout.KeyGate keyGate = null;
        if (composition != null && composition.keyBranch() != null) {
            KeyBranch keyBranch = composition.keyBranch();
            int fromIndex = Math.clamp(keyBranch.from(), 1, composition.pathRoles().size());
            RoomNode junction = spine.get(fromIndex);
            Direction side = random.nextBoolean() ? junction.heading.left() : junction.heading.right();
            List<RoomNode> branchNodes = placeKeyBranch(junction, side, keyBranch.roles(), nodes, tunnels, random);
            if (branchNodes == null) {
                branchNodes = placeKeyBranch(junction, side.opposite(), keyBranch.roles(), nodes, tunnels, random);
            }
            if (branchNodes == null) {
                // The branch holds the key, so a dungeon without it would
                // be sealed shut. Refusal beats an unfinishable run.
                throw new GenerationException("Could not place the key branch off path room " + fromIndex
                        + ". Increase generation.max-placement-attempts or reduce room sizes.");
            }
            int doorAfter = Math.clamp(keyBranch.doorAfter(), 1, composition.pathRoles().size());
            keyGate = new DungeonLayout.KeyGate(
                    spine.get(doorAfter).room.id() + "-" + spine.get(doorAfter + 1).room.id(),
                    branchNodes.getLast().room.id());
        }

        Random variants = new Random(seed ^ 0x5041524b4f55524cL);
        List<DungeonLayout.Room> variantRooms = nodes.stream().map(node -> {
            long connections = tunnels.stream().filter(tunnel -> tunnel.firstRoomId().equals(node.room.id()) || tunnel.secondRoomId().equals(node.room.id())).count();
            boolean canBeParkour = composition == null && (node.room.type() == DungeonLayout.RoomType.BRANCH
                    || (node.room.type() == DungeonLayout.RoomType.NORMAL && settings.allowNormalParkour));
            DungeonLayout.RoomVariant variant = canBeParkour && connections == 2 && settings.parkourVariant(variants)
                    ? DungeonLayout.RoomVariant.PARKOUR : DungeonLayout.RoomVariant.PLAIN;
            return new DungeonLayout.Room(node.room.id(), node.room.type(), node.room.bounds(), node.room.depth(), variant,
                    node.room.role(), List.of());
        }).toList();
        Random markers = new Random(seed ^ 0x4d41524b455253L);
        List<DungeonLayout.Room> rooms = variantRooms.stream().map(room -> new DungeonLayout.Room(room.id(), room.type(),
                room.bounds(), room.depth(), room.variant(), room.role(),
                generatedMarkers(room, tunnels, difficulty, markers))).toList();
        Bounds bounds = entranceBounds;
        for (DungeonLayout.Room room : rooms) {
            bounds = Bounds.union(bounds, room.bounds());
        }
        for (DungeonLayout.Tunnel tunnel : tunnels) {
            for (Bounds box : tunnel.occupied()) {
                bounds = Bounds.union(bounds, box);
            }
        }

        return new DungeonLayout(seed, difficulty, rooms, tunnels, bounds,
                entranceBounds.centreX(), entranceBounds.minY() + 2, entranceBounds.centreZ(), keyGate);
    }

    /**
     * Grows the mandatory key detour room by room off its junction, straight
     * out to one side. Failure rolls every step back so the other side starts
     * from a clean junction.
     */
    private List<RoomNode> placeKeyBranch(RoomNode junction, Direction side, List<String> roles,
                                          List<RoomNode> nodes, List<DungeonLayout.Tunnel> tunnels, Random random) {
        // A turning spine may already occupy this face; rollback must not
        // strip a face this attempt never claimed.
        boolean faceAlreadyUsed = junction.usedFaces.contains(side);
        List<RoomNode> added = new ArrayList<>();
        List<DungeonLayout.Tunnel> addedTunnels = new ArrayList<>();
        RoomNode parent = junction;
        for (int index = 0; index < roles.size(); index++) {
            Placement placement = findPlacement(parent, nodes, tunnels, Integer.toString(nodes.size() + 1),
                    DungeonLayout.RoomType.BRANCH, roles.get(index), parent.room.depth() + 1, side, random, false);
            if (placement == null) {
                nodes.removeAll(added);
                tunnels.removeAll(addedTunnels);
                if (!faceAlreadyUsed) junction.usedFaces.remove(side);
                return null;
            }
            placement.parent.usedFaces.add(placement.direction);
            placement.node.usedFaces.add(placement.direction.opposite());
            nodes.add(placement.node);
            tunnels.add(placement.tunnel);
            added.add(placement.node);
            addedTunnels.add(placement.tunnel);
            parent = placement.node;
        }
        return added;
    }

    /** Generates placeholder markers only; prefab markers are discovered by the same scanner later. */
    private List<DungeonMarker> generatedMarkers(DungeonLayout.Room room, List<DungeonLayout.Tunnel> tunnels,
                                                 int difficulty, Random random) {
        // Roled rooms spawn from their role recipe at runtime, not from the
        // per-difficulty marker mix, so they get no physical markers at all.
        if ((room.type() != DungeonLayout.RoomType.NORMAL && room.type() != DungeonLayout.RoomType.BRANCH)
                || room.variant() == DungeonLayout.RoomVariant.PARKOUR || room.role() != null) {
            return List.of();
        }
        List<DungeonMarker> markers = new ArrayList<>();
        for (String category : List.of("swarm", "pack", "champion")) {
            int groups = settings.markerGroups(difficulty, category);
            for (int group = 0; group < groups; group++) {
                DungeonMarker marker = placeMarker(room, tunnels, category, markers, random);
                if (marker != null) markers.add(marker);
            }
        }
        return List.copyOf(markers);
    }

    private DungeonMarker placeMarker(DungeonLayout.Room room, List<DungeonLayout.Tunnel> tunnels, String category,
                                      List<DungeonMarker> existing, Random random) {
        Bounds bounds = room.bounds();
        int edge = Math.max(2, settings.markerEdgeClearance);
        int minX = bounds.minX() + edge;
        int maxX = bounds.maxX() - edge;
        int minZ = bounds.minZ() + edge;
        int maxZ = bounds.maxZ() - edge;
        if (minX > maxX || minZ > maxZ) return null;
        int y = bounds.minY() + 1;
        int minimumDistance = settings.markerMinimumDistance;
        for (int attempt = 0; attempt < settings.markerPlacementAttempts; attempt++) {
            int x = random.nextInt(maxX - minX + 1) + minX;
            int z = random.nextInt(maxZ - minZ + 1) + minZ;
            if (nearDoorway(room, tunnels, x, y, z, settings.markerDoorwayClearance)) continue;
            boolean tooClose = existing.stream().anyMatch(marker -> {
                int offsetX = marker.x() - x;
                int offsetZ = marker.z() - z;
                return offsetX * offsetX + offsetZ * offsetZ < minimumDistance * minimumDistance;
            });
            if (!tooClose) return new DungeonMarker(category, x, y, z);
        }
        return null;
    }

    private static boolean nearDoorway(DungeonLayout.Room room, List<DungeonLayout.Tunnel> tunnels,
                                       int x, int y, int z, int clearance) {
        return tunnels.stream().filter(tunnel -> tunnel.firstRoomId().equals(room.id()) || tunnel.secondRoomId().equals(room.id()))
                .map(tunnel -> tunnel.firstRoomId().equals(room.id()) ? tunnel.firstDoorway() : tunnel.secondDoorway())
                .anyMatch(doorway -> doorway.expand(clearance).contains(x, y, z));
    }

    private Placement findPlacement(RoomNode parent, List<RoomNode> nodes, List<DungeonLayout.Tunnel> tunnels,
                                    String roomId, DungeonLayout.RoomType type, String role, int depth, Direction direction,
                                    Random random, boolean mustMoveOutward) {
        if (parent.usedFaces.contains(direction)) {
            return null;
        }
        for (int attempt = 0; attempt < settings.maxPlacementAttempts; attempt++) {
            Dimensions dimensions = randomDimensions(random, type, role);
            Bounds childBounds = childBounds(parent.room.bounds(), direction, dimensions, 0);
            if (mustMoveOutward && !movesFurtherFromEntrance(parent.room.bounds(), childBounds)) {
                continue;
            }
            RoomNode child = new RoomNode(new DungeonLayout.Room(roomId, type, childBounds, depth,
                    DungeonLayout.RoomVariant.PLAIN, role, List.of()), 0, false, direction);
            DungeonLayout.Tunnel tunnel = tunnel(parent.room, child.room, direction);

            if (collides(child.room.bounds(), parent, nodes, tunnels, tunnel)) {
                continue;
            }
            return new Placement(parent, child, direction, tunnel);
        }
        return null;
    }

    private Placement findSidePlacement(List<RoomNode> spine, List<RoomNode> nodes,
                                        List<DungeonLayout.Tunnel> tunnels, Random random, boolean longBranchUsed) {
        for (int attempt = 0; attempt < settings.maxPlacementAttempts; attempt++) {
            List<RoomNode> candidates = nodes.stream()
                    .filter(node -> node.room.type() != DungeonLayout.RoomType.BOSS)
                    .filter(node -> node.usedFaces.size() < Direction.values().length)
                    .filter(node -> node.branchLength < settings.maximumBranchLength(node.longBranch))
                    // A branch may extend away from the main path, but it never forks.
                    // This keeps the configured one-room branches as real dead ends.
                    .filter(node -> node.branchLength == 0 || !hasGeneratedChild(node, tunnels))
                    .filter(node -> !isProtectedSpineNode(node, spine))
                    .toList();
            if (candidates.isEmpty()) {
                return null;
            }
            List<RoomNode> branchParents = candidates.stream()
                    .filter(node -> node.branchLength > 0)
                    .toList();
            RoomNode parent = !branchParents.isEmpty() && random.nextDouble() < 0.40
                    ? branchParents.get(random.nextInt(branchParents.size()))
                    : candidates.get(random.nextInt(candidates.size()));
            Direction direction = spine.contains(parent)
                    ? Direction.randomPerpendicularUnused(parent.usedFaces, parent.heading, random)
                    : Direction.randomForwardOrTurnUnused(parent.usedFaces, parent.heading, random);
            if (direction == null) {
                continue;
            }
            Dimensions dimensions = randomDimensions(random, DungeonLayout.RoomType.BRANCH, null);
            Bounds childBounds = childBounds(parent.room.bounds(), direction, dimensions, 0);
            if (!movesFurtherFromEntrance(parent.room.bounds(), childBounds)) {
                continue;
            }
            boolean parentIsSpine = spine.contains(parent);
            boolean longBranch = parentIsSpine ? !longBranchUsed : parent.longBranch;
            RoomNode child = new RoomNode(new DungeonLayout.Room(Integer.toString(nodes.size() + 1), DungeonLayout.RoomType.BRANCH,
                    childBounds, parent.room.depth() + 1, DungeonLayout.RoomVariant.PLAIN),
                    parent.branchLength + 1, longBranch, direction);
            DungeonLayout.Tunnel tunnel = tunnel(parent.room, child.room, direction);
            if (!collides(child.room.bounds(), parent, nodes, tunnels, tunnel)) {
                return new Placement(parent, child, direction, tunnel);
            }
        }
        return null;
    }

    private static boolean hasGeneratedChild(RoomNode parent, List<DungeonLayout.Tunnel> tunnels) {
        return tunnels.stream().anyMatch(tunnel -> tunnel.firstRoomId().equals(parent.room.id()));
    }

    /** Keeps the entrance, its first successor, and the boss approach branch-free. */
    private static boolean isProtectedSpineNode(RoomNode node, List<RoomNode> spine) {
        int index = spine.indexOf(node);
        return index >= 0 && (index <= 1 || index >= spine.size() - 2);
    }

    private boolean collides(Bounds child, RoomNode parent, List<RoomNode> nodes,
                             List<DungeonLayout.Tunnel> tunnels, DungeonLayout.Tunnel proposed) {
        for (RoomNode node : nodes) {
            if (child.intersects(node.room.bounds().expand(settings.roomPadding))) {
                return true;
            }
            if (node != parent && intersectsAny(node.room.bounds(), proposed.occupied())) {
                return true;
            }
        }
        for (DungeonLayout.Tunnel existing : tunnels) {
            if (intersectsAny(child.expand(settings.roomPadding), existing.occupied())
                    || tunnelsIntersect(proposed, existing)) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersectsAny(Bounds room, List<Bounds> boxes) {
        return boxes.stream().anyMatch(room::intersects);
    }

    private static boolean tunnelsIntersect(DungeonLayout.Tunnel first, DungeonLayout.Tunnel second) {
        for (Bounds left : first.occupied()) {
            if (intersectsAny(left, second.occupied())) {
                return true;
            }
        }
        return false;
    }

    private Dimensions randomDimensions(Random random, DungeonLayout.RoomType type, String role) {
        int minWidth;
        int maxWidth;
        int minHeight;
        int maxHeight;
        int minDepth;
        int maxDepth;
        if (type == DungeonLayout.RoomType.BOSS) {
            minWidth = settings.bossMinWidth;
            maxWidth = settings.bossMaxWidth;
            minHeight = settings.bossMinHeight;
            maxHeight = settings.bossMaxHeight;
            minDepth = settings.bossMinDepth;
            maxDepth = settings.bossMaxDepth;
        } else {
            Dimensions prefab = settings.prefabDimensions(type, role);
            minWidth = maxWidth = prefab.width();
            minHeight = maxHeight = prefab.height();
            minDepth = maxDepth = prefab.depth();
        }
        return new Dimensions(
                randomOdd(random, minWidth, maxWidth),
                random.nextInt(maxHeight - minHeight + 1) + minHeight,
                randomOdd(random, minDepth, maxDepth)
        );
    }

    private static int randomOdd(Random random, int min, int max) {
        int first = (min & 1) == 0 ? min + 1 : min;
        int last = (max & 1) == 0 ? max - 1 : max;
        int choices = Math.max(1, (last - first) / 2 + 1);
        return first + random.nextInt(choices) * 2;
    }

    private Bounds childBounds(Bounds parent, Direction direction, Dimensions size, int yOffset) {
        int minY = parent.minY() + yOffset;
        int maxY = minY + size.height - 1;
        return switch (direction) {
            case EAST -> {
                int minX = parent.maxX() + settings.corridorLength;
                int minZ = parent.centreZ() - size.depth / 2;
                yield new Bounds(minX, minY, minZ, minX + size.width - 1, maxY, minZ + size.depth - 1);
            }
            case WEST -> {
                int maxX = parent.minX() - settings.corridorLength;
                int minZ = parent.centreZ() - size.depth / 2;
                yield new Bounds(maxX - size.width + 1, minY, minZ, maxX, maxY, minZ + size.depth - 1);
            }
            case SOUTH -> {
                int minZ = parent.maxZ() + settings.corridorLength;
                int minX = parent.centreX() - size.width / 2;
                yield new Bounds(minX, minY, minZ, minX + size.width - 1, maxY, minZ + size.depth - 1);
            }
            case NORTH -> {
                int maxZ = parent.minZ() - settings.corridorLength;
                int minX = parent.centreX() - size.width / 2;
                yield new Bounds(minX, minY, maxZ - size.depth + 1, minX + size.width - 1, maxY, maxZ);
            }
        };
    }

    private boolean movesFurtherFromEntrance(Bounds parent, Bounds child) {
        long entranceX = settings.originX + (settings.entranceWidth - 1L) / 2L;
        long entranceZ = settings.originZ + (settings.entranceDepth - 1L) / 2L;
        long parentX = parent.centreX() - entranceX;
        long parentZ = parent.centreZ() - entranceZ;
        long childX = child.centreX() - entranceX;
        long childZ = child.centreZ() - entranceZ;
        return childX * childX + childZ * childZ > parentX * parentX + parentZ * parentZ;
    }

    private DungeonLayout.Tunnel tunnel(DungeonLayout.Room parentRoom, DungeonLayout.Room childRoom, Direction direction) {
        Bounds parent = parentRoom.bounds();
        Bounds child = childRoom.bounds();
        int parentDoorY = parent.minY() + 1;
        int childDoorY = child.minY() + 1;
        int half = settings.corridorInnerWidth / 2;
        List<Bounds> floors = new ArrayList<>();
        List<Bounds> lips = new ArrayList<>();
        List<Bounds> air = new ArrayList<>();

        Bounds parentDoorway;
        Bounds childDoorway;
        if (direction.isX()) {
            int parentX = direction == Direction.EAST ? parent.maxX() : parent.minX();
            int childX = direction == Direction.EAST ? child.minX() : child.maxX();
            int centreZ = parent.centreZ();
            parentDoorway = new Bounds(parentX, parentDoorY, centreZ - half,
                    parentX, parentDoorY + settings.corridorInnerHeight - 1, centreZ + half);
            childDoorway = new Bounds(childX, childDoorY, centreZ - half,
                    childX, childDoorY + settings.corridorInnerHeight - 1, centreZ + half);
            if (parentDoorY == childDoorY) {
                addHorizontalX(floors, lips, air, parentX, childX, parentDoorY, centreZ, half);
            } else {
                int middle = (parentX + childX) / 2;
                addHorizontalX(floors, lips, air, parentX, middle, parentDoorY, centreZ, half);
                addVertical(floors, lips, air, middle, centreZ, parentDoorY, childDoorY, half);
                addHorizontalX(floors, lips, air, middle, childX, childDoorY, centreZ, half);
            }
        } else {
            int parentZ = direction == Direction.SOUTH ? parent.maxZ() : parent.minZ();
            int childZ = direction == Direction.SOUTH ? child.minZ() : child.maxZ();
            int centreX = parent.centreX();
            parentDoorway = new Bounds(centreX - half, parentDoorY, parentZ,
                    centreX + half, parentDoorY + settings.corridorInnerHeight - 1, parentZ);
            childDoorway = new Bounds(centreX - half, childDoorY, childZ,
                    centreX + half, childDoorY + settings.corridorInnerHeight - 1, childZ);
            if (parentDoorY == childDoorY) {
                addHorizontalZ(floors, lips, air, parentZ, childZ, parentDoorY, centreX, half);
            } else {
                int middle = (parentZ + childZ) / 2;
                addHorizontalZ(floors, lips, air, parentZ, middle, parentDoorY, centreX, half);
                addVertical(floors, lips, air, centreX, middle, parentDoorY, childDoorY, half);
                addHorizontalZ(floors, lips, air, middle, childZ, childDoorY, centreX, half);
            }
        }
        return new DungeonLayout.Tunnel(parentRoom.id(), childRoom.id(), parentDoorway, childDoorway, floors, lips, air);
    }


    private void addHorizontalX(List<Bounds> floors, List<Bounds> lips, List<Bounds> air, int firstX, int secondX,
                                int doorY, int centreZ, int half) {
        int minX = Math.min(firstX, secondX);
        int maxX = Math.max(firstX, secondX);
        floors.add(new Bounds(minX, doorY - 1, centreZ - half,
                maxX, doorY - 1, centreZ + half));
        addLipsX(lips, minX, maxX, doorY, centreZ, half);
        air.add(new Bounds(minX, doorY, centreZ - half,
                maxX, doorY + settings.corridorInnerHeight - 1, centreZ + half));
    }

    private void addHorizontalZ(List<Bounds> floors, List<Bounds> lips, List<Bounds> air, int firstZ, int secondZ,
                                int doorY, int centreX, int half) {
        int minZ = Math.min(firstZ, secondZ);
        int maxZ = Math.max(firstZ, secondZ);
        floors.add(new Bounds(centreX - half, doorY - 1, minZ,
                centreX + half, doorY - 1, maxZ));
        addLipsZ(lips, minZ, maxZ, doorY, centreX, half);
        air.add(new Bounds(centreX - half, doorY, minZ,
                centreX + half, doorY + settings.corridorInnerHeight - 1, maxZ));
    }

    private void addVertical(List<Bounds> floors, List<Bounds> lips, List<Bounds> air, int centreX, int centreZ,
                             int firstDoorY, int secondDoorY, int half) {
        int minDoorY = Math.min(firstDoorY, secondDoorY);
        int maxDoorY = Math.max(firstDoorY, secondDoorY);
        floors.add(new Bounds(centreX - half, minDoorY - 1, centreZ - half,
                centreX + half, minDoorY - 1, centreZ + half));
        air.add(new Bounds(centreX - half, minDoorY, centreZ - half,
                centreX + half, maxDoorY + settings.corridorInnerHeight - 1, centreZ + half));
    }

    /** Low edge lips stop ordinary walking and mobs from falling into the void. */
    private void addLipsX(List<Bounds> lips, int minX, int maxX, int doorY, int centreZ, int half) {
        if (!settings.corridorSafetyLips) return;
        lips.add(new Bounds(minX, doorY, centreZ - half - 1, maxX, doorY + settings.corridorSafetyLipHeight - 1, centreZ - half - 1));
        lips.add(new Bounds(minX, doorY, centreZ + half + 1, maxX, doorY + settings.corridorSafetyLipHeight - 1, centreZ + half + 1));
    }

    private void addLipsZ(List<Bounds> lips, int minZ, int maxZ, int doorY, int centreX, int half) {
        if (!settings.corridorSafetyLips) return;
        lips.add(new Bounds(centreX - half - 1, doorY, minZ, centreX - half - 1, doorY + settings.corridorSafetyLipHeight - 1, maxZ));
        lips.add(new Bounds(centreX + half + 1, doorY, minZ, centreX + half + 1, doorY + settings.corridorSafetyLipHeight - 1, maxZ));
    }

    private enum Direction {
        NORTH, SOUTH, EAST, WEST;

        public boolean isX() {
            return this == EAST || this == WEST;
        }

        public Direction opposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case EAST -> WEST;
                case WEST -> EAST;
            };
        }

        public Direction left() {
            return switch (this) {
                case NORTH -> WEST;
                case SOUTH -> EAST;
                case EAST -> NORTH;
                case WEST -> SOUTH;
            };
        }

        public Direction right() {
            return left().opposite();
        }

        /** Reads a cardinal config value, defaulting safely to the south face. */
        public static Direction fromConfig(String value) {
            try {
                return value == null ? SOUTH : valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return SOUTH;
            }
        }

        public static Direction randomUnused(EnumSet<Direction> used, Random random) {
            List<Direction> available = new ArrayList<>();
            for (Direction value : values()) {
                if (!used.contains(value)) {
                    available.add(value);
                }
            }
            return available.get(random.nextInt(available.size()));
        }

        public static Direction randomPerpendicularUnused(EnumSet<Direction> used, Direction spineDirection, Random random) {
            List<Direction> available = new ArrayList<>();
            for (Direction value : values()) {
                if (!used.contains(value) && value != spineDirection && value != spineDirection.opposite()) {
                    available.add(value);
                }
            }
            return available.isEmpty() ? null : available.get(random.nextInt(available.size()));
        }

        public static Direction randomForwardOrTurnUnused(EnumSet<Direction> used, Direction heading, Random random) {
            List<Direction> available = new ArrayList<>();
            for (Direction value : values()) {
                if (!used.contains(value) && (heading == null || value != heading.opposite())) {
                    available.add(value);
                }
            }
            return available.isEmpty() ? null : available.get(random.nextInt(available.size()));
        }
    }

    private static final class RoomNode {
        private final DungeonLayout.Room room;
        /** Zero is the critical spine; positive values are distance along a side branch. */
        private final int branchLength;
        /** Exactly one branch root may receive the longer configured allowance. */
        private final boolean longBranch;
        /** The direction this node was reached from its parent. */
        private final Direction heading;
        private final EnumSet<Direction> usedFaces = EnumSet.noneOf(Direction.class);

        private RoomNode(DungeonLayout.Room room) {
            this(room, 0, false, null);
        }

        private RoomNode(DungeonLayout.Room room, int branchLength, boolean longBranch, Direction heading) {
            this.room = room;
            this.branchLength = branchLength;
            this.longBranch = longBranch;
            this.heading = heading;
        }
    }

    private record Dimensions(int width, int height, int depth) {
    }

    private record Placement(RoomNode parent, RoomNode node, Direction direction, DungeonLayout.Tunnel tunnel) {
    }

    /** An authored difficulty: role per path room plus the optional key detour. */
    private record Composition(List<String> pathRoles, boolean allowTurns, KeyBranch keyBranch) {
    }

    /** {@code from} and {@code doorAfter} are one-based indexes into the path. */
    private record KeyBranch(int from, int doorAfter, List<String> roles) {
    }

    public static final class GenerationException extends Exception {
        public GenerationException(String message) {
            super(message);
        }
    }

    private static final class Settings {
        private final FileConfiguration config;
        private final NormalRoomLibrary roomLibrary;
        private final Map<String, Dimensions> roleDimensions = new HashMap<>();
        private final int originX;
        private final int originY;
        private final int originZ;
        private final int entranceWidth;
        private final int entranceHeight;
        private final int entranceDepth;
        private final Direction spawnExitDirection;
        private final int prefabNormalWidth;
        private final int prefabNormalHeight;
        private final int prefabNormalDepth;
        private final int prefabBranchWidth;
        private final int prefabBranchHeight;
        private final int prefabBranchDepth;
        private final int bossMinWidth;
        private final int bossMaxWidth;
        private final int bossMinHeight;
        private final int bossMaxHeight;
        private final int bossMinDepth;
        private final int bossMaxDepth;
        private final int corridorLength;
        private final int corridorInnerWidth;
        private final int corridorInnerHeight;
        private final boolean corridorSafetyLips;
        private final int corridorSafetyLipHeight;
        private final int roomPadding;
        private final int maxPlacementAttempts;
        private final double branchFrequency;
        private final int shortBranchMaximumLength;
        private final int longBranchMaximumLength;
        private final int minimumCriticalPathRooms;
        private final int minimumRoomsBeforeTurn;
        private final int maximumRoomsBeforeTurn;
        private final Map<Integer, Integer> roomsPerDifficulty;
        private final Map<Integer, Composition> compositions;
        private final int markerMinimumDistance;
        private final int markerDoorwayClearance;
        private final int markerEdgeClearance;
        private final int markerPlacementAttempts;
        private final boolean allowNormalParkour;

        private Settings(FileConfiguration config, NormalRoomLibrary roomLibrary) {
            this.config = config;
            this.roomLibrary = roomLibrary;
            originX = config.getInt("generation.entrance.origin.x", 0);
            originY = config.getInt("generation.entrance.origin.y", 64);
            originZ = config.getInt("generation.entrance.origin.z", 0);
            NormalRoomLibrary.PlanningDimensions spawnDimensions = roomLibrary == null
                    ? configuredDimensions(config, "generation.entrance.size")
                    : roomLibrary.planningDimensions(NormalRoomLibrary.PrefabType.SPAWN);
            entranceWidth = oddAtLeast(spawnDimensions.width(), 7);
            entranceHeight = Math.max(6, spawnDimensions.height());
            entranceDepth = oddAtLeast(spawnDimensions.depth(), 7);
            spawnExitDirection = Direction.fromConfig(config.getString("generation.entrance.exit-direction", "SOUTH"));
            corridorInnerWidth = oddAtLeast(config.getInt("generation.corridor.platform-width",
                    config.getInt("generation.corridor.inner-width", 3)), 1);
            corridorInnerHeight = Math.max(2, config.getInt("generation.corridor.inner-height", 4));
            corridorSafetyLips = config.getBoolean("generation.corridor.safety-lips.enabled", true);
            corridorSafetyLipHeight = Math.max(1, config.getInt("generation.corridor.safety-lips.height", 1));
            NormalRoomLibrary.PlanningDimensions normalDimensions = roomLibrary == null
                    ? configuredPrefabDimensions(config)
                    : roomLibrary.planningDimensions(NormalRoomLibrary.PrefabType.NORMAL);
            NormalRoomLibrary.PlanningDimensions branchDimensions = roomLibrary == null
                    ? configuredPrefabDimensions(config)
                    : roomLibrary.planningDimensions(NormalRoomLibrary.PrefabType.BRANCH);
            prefabNormalWidth = oddAtLeast(normalDimensions.width(), corridorInnerWidth + 4);
            prefabNormalHeight = Math.max(corridorInnerHeight + 3, normalDimensions.height());
            prefabNormalDepth = oddAtLeast(normalDimensions.depth(), corridorInnerWidth + 4);
            prefabBranchWidth = oddAtLeast(branchDimensions.width(), corridorInnerWidth + 4);
            prefabBranchHeight = Math.max(corridorInnerHeight + 3, branchDimensions.height());
            prefabBranchDepth = oddAtLeast(branchDimensions.depth(), corridorInnerWidth + 4);
            double largestBossScale = 1.0;
            for (int difficulty = 1; difficulty <= 9; difficulty++) {
                largestBossScale = Math.max(largestBossScale, config.getDouble("mobs.difficulties." + difficulty + ".boss.scale", 1.0));
                largestBossScale = Math.max(largestBossScale, config.getDouble("mobs.difficulties." + difficulty + ".boss.minions.scale", 1.0));
            }
            NormalRoomLibrary.PlanningDimensions bossDimensions = roomLibrary == null
                    ? configuredDimensions(config, "generation.boss-room.max-size")
                    : roomLibrary.planningDimensions(NormalRoomLibrary.PrefabType.BOSS);
            bossMinWidth = bossMaxWidth = oddAtLeast(bossDimensions.width(), corridorInnerWidth + 4);
            bossMinHeight = bossMaxHeight = Math.max(corridorInnerHeight + 3, bossDimensions.height());
            bossMinDepth = bossMaxDepth = oddAtLeast(bossDimensions.depth(), corridorInnerWidth + 4);
            corridorLength = Math.max(7, config.getInt("generation.corridor.length", 9));
            roomPadding = Math.max(0, config.getInt("generation.room-padding", 2));
            maxPlacementAttempts = Math.max(50, config.getInt("generation.max-placement-attempts", 600));
            branchFrequency = Math.clamp(config.getDouble("generation.branching.branch-frequency", 0.55), 0.0, 1.0);
            shortBranchMaximumLength = Math.max(1,
                    config.getInt("generation.branching.short-branch-max-length", 1));
            longBranchMaximumLength = Math.max(shortBranchMaximumLength,
                    config.getInt("generation.branching.long-branch-max-length", 2));
            minimumCriticalPathRooms = Math.max(2, config.getInt("generation.branching.minimum-critical-path-rooms", 4));
            minimumRoomsBeforeTurn = Math.max(1, config.getInt("generation.critical-path.min-rooms-before-turn", 2));
            maximumRoomsBeforeTurn = Math.max(minimumRoomsBeforeTurn,
                    config.getInt("generation.critical-path.max-rooms-before-turn", 4));
            roomsPerDifficulty = new HashMap<>();
            for (int difficulty = 1; difficulty <= 9; difficulty++) {
                roomsPerDifficulty.put(difficulty, Math.max(2,
                        config.getInt("generation.rooms-per-difficulty." + difficulty, 4 + difficulty * 3)));
            }
            compositions = readCompositions(config);
            markerMinimumDistance = Math.max(1, config.getInt("mobs.markers.generated.minimum-distance", 8));
            markerDoorwayClearance = Math.max(0, config.getInt("mobs.markers.generated.doorway-clearance", 5));
            markerEdgeClearance = Math.max(2, config.getInt("mobs.markers.generated.edge-clearance", 3));
            markerPlacementAttempts = Math.max(1, config.getInt("mobs.markers.generated.placement-attempts", 100));
            allowNormalParkour = config.getBoolean("generation.room-variants.allow-normal-parkour", false);
        }

        private static Settings fromConfig(FileConfiguration config, NormalRoomLibrary roomLibrary) {
            return new Settings(config, roomLibrary);
        }

        private static NormalRoomLibrary.PlanningDimensions configuredPrefabDimensions(FileConfiguration config) {
            return new NormalRoomLibrary.PlanningDimensions(
                    config.getInt("generation.prefab-room.size.x", 67),
                    config.getInt("generation.prefab-room.size.y", 34),
                    config.getInt("generation.prefab-room.size.z", 67));
        }

        private static NormalRoomLibrary.PlanningDimensions configuredDimensions(FileConfiguration config, String path) {
            return new NormalRoomLibrary.PlanningDimensions(
                    config.getInt(path + ".x", 67), config.getInt(path + ".y", 34), config.getInt(path + ".z", 67));
        }

        private int roomsForDifficulty(int difficulty) {
            return roomsPerDifficulty.get(difficulty);
        }

        private Composition composition(int difficulty) {
            return compositions.get(difficulty);
        }

        /**
         * Role pools reserve their own footprint - one oversized parkour room
         * must not stretch every ordinary reservation, nor be squeezed into
         * one. Falls back to the generic envelope when the pool is empty,
         * matching how prefab selection falls back.
         */
        private Dimensions prefabDimensions(DungeonLayout.RoomType type, String role) {
            boolean branch = type == DungeonLayout.RoomType.BRANCH;
            if (role == null || roomLibrary == null) {
                return rotatable(branch ? prefabBranchWidth : prefabNormalWidth,
                        branch ? prefabBranchHeight : prefabNormalHeight,
                        branch ? prefabBranchDepth : prefabNormalDepth);
            }
            return roleDimensions.computeIfAbsent((branch ? "branch:" : "normal:") + role, ignored -> {
                NormalRoomLibrary.PlanningDimensions dimensions = roomLibrary.planningDimensions(
                        branch ? NormalRoomLibrary.PrefabType.BRANCH : NormalRoomLibrary.PrefabType.NORMAL, role);
                return rotatable(oddAtLeast(dimensions.width(), corridorInnerWidth + 4),
                        Math.max(corridorInnerHeight + 3, dimensions.height()),
                        oddAtLeast(dimensions.depth(), corridorInnerWidth + 4));
            });
        }

        /**
         * A square reservation, because the rotation is chosen long after the
         * space is booked. A 31x111 room turned sideways needs 111 where only
         * 31 was reserved, and a prefab wider than its own box overhangs into
         * the corridor until the two rooms share a wall.
         */
        private static Dimensions rotatable(int width, int height, int depth) {
            int side = Math.max(width, depth);
            return new Dimensions(side, height, side);
        }

        private static Map<Integer, Composition> readCompositions(FileConfiguration config) {
            Map<Integer, Composition> result = new HashMap<>();
            var section = config.getConfigurationSection("generation.composition");
            if (section == null) return result;
            for (String key : section.getKeys(false)) {
                int difficulty;
                try {
                    difficulty = Integer.parseInt(key.trim());
                } catch (NumberFormatException exception) {
                    continue;
                }
                if (difficulty < 1 || difficulty > 9) continue;
                String base = "generation.composition." + key + ".";
                List<String> path = normalisedRoles(config.getStringList(base + "path"));
                if (path.isEmpty()) continue;
                boolean allowTurns = config.getBoolean(base + "allow-turns", false);
                KeyBranch keyBranch = null;
                if (config.getBoolean(base + "key-branch.enabled", false)) {
                    List<String> branchRoles = normalisedRoles(config.getStringList(base + "key-branch.rooms"));
                    if (!branchRoles.isEmpty()) {
                        keyBranch = new KeyBranch(config.getInt(base + "key-branch.from", path.size()),
                                config.getInt(base + "key-branch.door-after", path.size()), branchRoles);
                    }
                }
                result.put(difficulty, new Composition(path, allowTurns, keyBranch));
            }
            return result;
        }

        private static List<String> normalisedRoles(List<String> raw) {
            return raw.stream().filter(role -> role != null && !role.isBlank())
                    .map(role -> role.trim().toLowerCase(Locale.ROOT)).toList();
        }

        private int criticalRoomCount(int totalRooms) {
            int protectedSpineMinimum = totalRooms > 5 ? 5 : totalRooms;
            int minimum = Math.min(totalRooms, Math.max(minimumCriticalPathRooms, protectedSpineMinimum));
            int optionalRooms = totalRooms - minimum;
            int sideRooms = (int) Math.round(optionalRooms * branchFrequency);
            return totalRooms - sideRooms;
        }

        private int maximumBranchLength(boolean longBranch) {
            return longBranch ? longBranchMaximumLength : shortBranchMaximumLength;
        }

        private int randomRoomsBeforeTurn(Random random) {
            return random.nextInt(maximumRoomsBeforeTurn - minimumRoomsBeforeTurn + 1) + minimumRoomsBeforeTurn;
        }

        private boolean parkourVariant(Random random) {
            int plain = Math.max(0, config.getInt("generation.room-variants.plain.weight", 1));
            int parkour = Math.max(0, config.getInt("generation.room-variants.parkour.weight", 0));
            return parkour > 0 && random.nextInt(Math.max(1, plain + parkour)) >= plain;
        }

        private int markerGroups(int difficulty, String category) {
            return Math.max(0, config.getInt("mobs.markers.generated.per-difficulty." + difficulty + "." + category, 0));
        }

        private static int oddAtLeast(int value, int minimum) {
            int result = Math.max(value, minimum);
            return (result & 1) == 0 ? result + 1 : result;
        }
    }
}
