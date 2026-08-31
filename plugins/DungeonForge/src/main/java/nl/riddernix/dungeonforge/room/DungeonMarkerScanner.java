package nl.riddernix.dungeonforge.room;

import nl.riddernix.dungeonforge.generation.DungeonLayout;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Converts configured structure marker blocks into immutable room metadata.
 *
 * <p>The scanner deliberately runs after the tick-spread block build and also
 * uses a cursor itself. This same layer works for generated placeholders and
 * later for hand-built prefab rooms.</p>
 */
public final class DungeonMarkerScanner extends BukkitRunnable {

    private final World world;
    private final List<DungeonLayout.Room> rooms;
    private final Map<Material, String> categories;
    private final int blocksPerTick;
    private final Consumer<Map<String, List<DungeonMarker>>> onComplete;
    private final Map<String, List<DungeonMarker>> found = new HashMap<>();
    private int roomIndex;
    private int x;
    private int y;
    private int z;

    private DungeonMarkerScanner(World world, List<DungeonLayout.Room> rooms, Map<Material, String> categories,
                                 int blocksPerTick, Consumer<Map<String, List<DungeonMarker>>> onComplete) {
        this.world = world;
        this.rooms = List.copyOf(rooms);
        this.categories = Map.copyOf(categories);
        this.blocksPerTick = Math.max(256, blocksPerTick);
        this.onComplete = onComplete;
        if (!rooms.isEmpty()) resetCursor(rooms.getFirst());
    }

    public static void start(Plugin plugin, World world, List<DungeonLayout> layouts, FileConfiguration config,
                             int blocksPerTick, Consumer<Map<String, List<DungeonMarker>>> onComplete) {
        List<DungeonLayout.Room> rooms = layouts.stream().flatMap(layout -> layout.rooms().stream()).toList();
        Map<Material, String> categories = new HashMap<>();
        for (DungeonMarkerDefinitions.Definition definition : DungeonMarkerDefinitions.read(config, plugin)) {
            categories.put(definition.material(), definition.category());
        }
        new DungeonMarkerScanner(world, rooms, categories, blocksPerTick, onComplete)
                .runTaskTimer(plugin, 1L, 1L);
    }

    @Override
    public void run() {
        int budget = blocksPerTick;
        while (budget-- > 0 && roomIndex < rooms.size()) {
            DungeonLayout.Room room = rooms.get(roomIndex);
            Material material = world.getBlockAt(x, y, z).getType();
            String category = categories.get(material);
            if (category != null) {
                found.computeIfAbsent(room.id(), ignored -> new ArrayList<>())
                        .add(new DungeonMarker(category, x, y, z));
                world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
            advance(room);
        }
        if (roomIndex >= rooms.size()) {
            cancel();
            Map<String, List<DungeonMarker>> immutable = new HashMap<>();
            found.forEach((roomId, markers) -> immutable.put(roomId, List.copyOf(markers)));
            onComplete.accept(Map.copyOf(immutable));
        }
    }

    private void advance(DungeonLayout.Room room) {
        x++;
        if (x <= room.bounds().maxX() - 1) return;
        x = room.bounds().minX() + 1;
        z++;
        if (z <= room.bounds().maxZ() - 1) return;
        z = room.bounds().minZ() + 1;
        y++;
        if (y <= room.bounds().maxY() - 1) return;
        roomIndex++;
        if (roomIndex < rooms.size()) resetCursor(rooms.get(roomIndex));
    }

    private void resetCursor(DungeonLayout.Room room) {
        x = room.bounds().minX() + 1;
        y = room.bounds().minY() + 1;
        z = room.bounds().minZ() + 1;
    }
}
