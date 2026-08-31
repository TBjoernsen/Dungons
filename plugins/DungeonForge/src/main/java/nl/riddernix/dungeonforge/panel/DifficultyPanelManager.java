package nl.riddernix.dungeonforge.panel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

/**
 * Fixed in-world difficulty selectors built from display entities.
 *
 * <p>A panel is furniture, not a summoned menu: placed once by an admin, always
 * standing there, usable by anyone who walks up. The world save never contains
 * its entities - they are spawned non-persistent from panels.yml whenever their
 * chunk loads, which makes duplication impossible and leaves nothing behind
 * after a crash.</p>
 *
 * <p>This is part one: the shared, static furniture, rendered for everyone on
 * difficulty 1. The per-player number row and the clickable regions build on
 * top of {@link #renderNumberRow}, which already draws the carousel for an
 * arbitrary selection.</p>
 */
public final class DifficultyPanelManager {

    private static final String STORAGE = "panels.yml";
    private static final byte MINIMUM_OPACITY = 26;

    private final DungeonForgePlugin plugin;
    private final File storageFile;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    /** Panel id to its base location; the location's yaw is the panel's facing. */
    private final Map<String, Location> panels = new LinkedHashMap<>();
    /** Entities currently spawned per panel, so re-rendering can clean first. */
    private final Map<String, List<UUID>> spawned = new HashMap<>();
    /** The shared default row per panel, hidden per player once they engage. */
    private final Map<String, List<UUID>> sharedNumbers = new HashMap<>();
    /** The Enter button's fill strips per panel, for the pressed-flash feedback. */
    private final Map<String, List<UUID>> buttons = new HashMap<>();
    /**
     * Each player's chosen difficulty. This is the session state: it survives
     * walking away from the panel and is dropped only on logout.
     */
    private final Map<UUID, Integer> selections = new HashMap<>();
    /** Per player, per panel: their personal number-row entities. */
    private final Map<UUID, Map<String, List<UUID>>> personalRows = new HashMap<>();

    public DifficultyPanelManager(DungeonForgePlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), STORAGE);
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    /** Loads saved panels, renders the reachable ones, and sweeps for orphans. */
    public void load() {
        panels.clear();
        YamlConfiguration storage = YamlConfiguration.loadConfiguration(storageFile);
        ConfigurationSection section = storage.getConfigurationSection("panels");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                World world = Bukkit.getWorld(section.getString(id + ".world", ""));
                if (world == null) {
                    plugin.getLogger().warning("Ignoring difficulty panel '" + id + "': its world is not loaded.");
                    continue;
                }
                panels.put(id, new Location(world, section.getDouble(id + ".x"), section.getDouble(id + ".y"),
                        section.getDouble(id + ".z"), (float) section.getDouble(id + ".yaw"), 0.0F));
            }
        }
        renderLoadedPanels();
        sweepOrphans();
    }

    /** Re-renders every reachable panel with fresh styling after a reload. */
    public void reload() {
        renderLoadedPanels();
    }

    /** Removes every spawned entity; panels return on the next enable. */
    public void despawnAll() {
        for (Map<String, List<UUID>> rows : personalRows.values()) {
            for (List<UUID> ids : rows.values()) {
                removeEntities(ids);
            }
        }
        personalRows.clear();
        for (Map.Entry<String, Location> entry : panels.entrySet()) {
            clearEntities(entry.getKey(), entry.getValue());
        }
    }

    private void renderLoadedPanels() {
        for (Map.Entry<String, Location> entry : panels.entrySet()) {
            render(entry.getKey(), entry.getValue());
        }
    }

    /** Entities whose panel no longer exists, left by an older session. */
    private void sweepOrphans() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                String id = panelId(entity);
                if (id != null && !panels.containsKey(id)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Removed " + removed + " orphaned difficulty panel entit(ies).");
        }
    }

    /** Respawns a panel when its chunk comes back, one tick later to stay clear of the load itself. */
    public void handleChunkLoad(Chunk chunk) {
        for (Map.Entry<String, Location> entry : panels.entrySet()) {
            Location base = entry.getValue();
            if (!chunk.getWorld().equals(base.getWorld())
                    || base.getBlockX() >> 4 != chunk.getX() || base.getBlockZ() >> 4 != chunk.getZ()) {
                continue;
            }
            String id = entry.getKey();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Location current = panels.get(id);
                if (current != null) {
                    render(id, current);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    //  Placement and removal
    // ------------------------------------------------------------------

    /** Places a panel at this exact spot, facing the way the location faces. */
    public String place(Location where) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Location base = where.clone();
        base.setPitch(0.0F);
        panels.put(id, base);
        render(id, base);
        save();
        return id;
    }

    /**
     * Moves the closest panel to a new spot, keeping its id.
     *
     * <p>Rebuilt rather than nudged: every part of a panel sits at an offset
     * computed from its base, so recreating it there is both simpler and the
     * only way a new facing lands right.</p>
     *
     * @return the new location, empty when nothing was near enough
     */
    public Optional<Location> moveNearest(Location from, Location to) {
        String nearest = nearestPanel(from).orElse(null);
        if (nearest == null) {
            return Optional.empty();
        }
        clearEntities(nearest, panels.get(nearest));
        Location base = to.clone();
        base.setPitch(0.0F);
        panels.put(nearest, base);
        render(nearest, base);
        save();
        return Optional.of(base);
    }

    /** Removes the closest panel within the configured radius. */
    public boolean removeNearest(Location from) {
        String nearest = nearestPanel(from).orElse(null);
        if (nearest == null) {
            return false;
        }
        Location base = panels.remove(nearest);
        clearEntities(nearest, base);
        save();
        return true;
    }

    private Optional<String> nearestPanel(Location from) {
        double radius = Math.max(1.0, plugin.getConfig().getDouble("difficulty-panel.remove-radius", 5.0));
        String nearest = null;
        double nearestDistanceSquared = radius * radius;
        for (Map.Entry<String, Location> entry : panels.entrySet()) {
            if (!entry.getValue().getWorld().equals(from.getWorld())) {
                continue;
            }
            double distanceSquared = entry.getValue().distanceSquared(from);
            if (distanceSquared <= nearestDistanceSquared) {
                nearest = entry.getKey();
                nearestDistanceSquared = distanceSquared;
            }
        }
        return Optional.ofNullable(nearest);
    }

    /** Removes every panel, loading their chunks first, plus any tagged stray. */
    public RemovalReport removeAll() {
        List<Location> locations = new ArrayList<>();
        for (Map.Entry<String, Location> entry : new ArrayList<>(panels.entrySet())) {
            clearEntities(entry.getKey(), entry.getValue());
            locations.add(entry.getValue().clone());
        }
        panels.clear();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                if (panelId(entity) != null) {
                    locations.add(entity.getLocation().clone());
                    entity.remove();
                }
            }
        }
        save();
        return new RemovalReport(locations.size(), List.copyOf(locations));
    }

    public List<PanelInfo> list() {
        List<PanelInfo> result = new ArrayList<>();
        for (Map.Entry<String, Location> entry : panels.entrySet()) {
            result.add(new PanelInfo(entry.getKey(), entry.getValue().clone()));
        }
        return List.copyOf(result);
    }

    /** Whether an entity is part of any difficulty panel. */
    public boolean isPanelEntity(Entity entity) {
        return panelId(entity) != null;
    }

    /** Points a player at the nearest panel, replacing the retired chest menu. */
    public void sendLocator(Player player) {
        Location nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (Location base : panels.values()) {
            if (!base.getWorld().equals(player.getWorld())) {
                continue;
            }
            double distanceSquared = base.distanceSquared(player.getLocation());
            if (distanceSquared < nearestDistanceSquared) {
                nearest = base;
                nearestDistanceSquared = distanceSquared;
            }
        }
        if (nearest == null && !panels.isEmpty()) {
            nearest = panels.values().iterator().next();
        }
        if (nearest == null) {
            plugin.messages().send(player, "menu-replaced-none");
            return;
        }
        plugin.messages().send(player, "menu-replaced",
                Messages.ph("world", nearest.getWorld().getName()),
                Messages.ph("x", nearest.getBlockX()),
                Messages.ph("y", nearest.getBlockY()),
                Messages.ph("z", nearest.getBlockZ()));
    }

    // ------------------------------------------------------------------
    //  Interaction
    // ------------------------------------------------------------------

    /**
     * One click from one player. All state involved is keyed by that player's
     * UUID, so two people clicking in the same tick cannot interfere: each
     * click reads and writes only its own entry.
     */
    public void handleClick(Player player, Entity clicked) {
        String panelId = panelId(clicked);
        String role = clicked.getPersistentDataContainer().get(plugin.panelRoleKey(), PersistentDataType.STRING);
        if (panelId == null || role == null || !panels.containsKey(panelId)) {
            return;
        }
        switch (role) {
            case "hit-arrow-left" -> shift(player, panelId, -1);
            case "hit-arrow-right" -> shift(player, panelId, 1);
            case "hit-button" -> enter(player, panelId);
            default -> {
                // Text displays have no hitbox; nothing else is clickable.
            }
        }
    }

    private void shift(Player player, String panelId, int direction) {
        int current = selections.getOrDefault(player.getUniqueId(), 1);
        // The row stops at 1 and 9. Wrapping would make 9-to-1 a one-click
        // accident, and the carousel fade already reads as a real edge.
        int next = Math.clamp(current + direction, 1, 9);
        if (next == current) {
            playSound(player, "deny", 0.6F);
            return;
        }
        selections.put(player.getUniqueId(), next);
        playSound(player, "click", 1.0F + next * 0.05F);
        Location base = panels.get(panelId);
        if (base == null) {
            return;
        }
        if (ensurePersonalRow(player, panelId, base, current)) {
            // A freshly spawned row already stands at the old selection, so
            // this slide is visible for the very first click too.
            animateRow(player, panelId, base, next);
        }
    }

    private void enter(Player player, String panelId) {
        playSound(player, "enter", 1.2F);
        // The flash is on the shared button, so everyone at the panel sees it
        // being pressed - it is a physical button on a physical installation.
        flashButton(panelId);
        plugin.command().startFromPanel(player, selections.getOrDefault(player.getUniqueId(), 1));
    }

    /** Hover cannot be detected server-side; being used is shown instead. */
    private void flashButton(String panelId) {
        List<UUID> strips = buttons.getOrDefault(panelId, List.of());
        if (strips.isEmpty()) {
            return;
        }
        FileConfiguration config = plugin.getConfig();
        // Block displays hold a block, not a colour, so a press swaps the
        // fill bars for a brighter block and swaps them back.
        Material normal = blockMaterial(config.getString("difficulty-panel.button.plate.fill-block"),
                Material.BLACK_CONCRETE);
        Material pressed = blockMaterial(config.getString("difficulty-panel.button.plate.flash-block"),
                Material.BROWN_CONCRETE);
        int ticks = Math.clamp(config.getInt("difficulty-panel.button.flash-ticks", 3), 1, 20);
        setBlocks(strips, pressed);
        Bukkit.getScheduler().runTaskLater(plugin, () -> setBlocks(strips, normal), ticks);
    }

    private static void setBlocks(List<UUID> ids, Material material) {
        BlockData data = material.createBlockData();
        for (UUID id : ids) {
            if (Bukkit.getEntity(id) instanceof BlockDisplay display) {
                display.setBlock(data);
            }
        }
    }

    /**
     * The per-viewer half of the panel. The furniture is one shared set of
     * entities; only the number row exists once per engaged player, spawned
     * invisible-by-default and shown exclusively to its owner, while the
     * shared default row is hidden for that one player. Cost: nine text
     * displays per engaged player standing near the panel.
     */
    private boolean ensurePersonalRow(Player player, String panelId, Location base, int selection) {
        Map<String, List<UUID>> rows = personalRows.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        List<UUID> existing = rows.get(panelId);
        if (existing != null && !existing.isEmpty() && Bukkit.getEntity(existing.getFirst()) != null) {
            return true;
        }
        World world = base.getWorld();
        if (world == null || !world.equals(player.getWorld())
                || !world.isChunkLoaded(base.getBlockX() >> 4, base.getBlockZ() >> 4)) {
            return false;
        }
        FileConfiguration config = plugin.getConfig();
        float yaw = base.getYaw() + (config.getBoolean("difficulty-panel.flip-facing", false) ? 180.0F : 0.0F);
        double radians = Math.toRadians(yaw);
        Vector facing = new Vector(-Math.sin(radians), 0.0, Math.cos(radians));
        Vector rightward = new Vector(facing.getZ(), 0.0, -facing.getX());
        Placement placement = new Placement(world, base, yaw, facing, rightward,
                Math.clamp(config.getInt("difficulty-panel.brightness", 15), 0, 15));
        int slideTicks = Math.clamp(config.getInt("difficulty-panel.slide-ticks", 4), 0, 40);
        String format = config.getString("difficulty-panel.numbers.format", "<color:#f2e39b><bold><n>");

        List<UUID> ids = new ArrayList<>();
        for (int number = 1; number <= 9; number++) {
            NumberPose pose = numberPose(number, selection);
            Location at = numberLocation(placement, pose);
            TextDisplay display = world.spawn(at, TextDisplay.class, text -> {
                text.text(miniMessage.deserialize(format, Messages.ph("n", Integer.toString(pose.number()))));
                text.setBillboard(Display.Billboard.FIXED);
                text.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(pose.scale(), pose.scale(), pose.scale()), new Quaternionf()));
                text.setTextOpacity(pose.opacity());
                text.setShadowed(false);
                text.setSeeThrough(false);
                text.setLineWidth(400);
                text.setBackgroundColor(Color.fromARGB(0));
                text.setBrightness(new Display.Brightness(placement.brightness(), placement.brightness()));
                text.setTeleportDuration(slideTicks);
                text.setViewRange((float) config.getDouble("difficulty-panel.view-range", 2.0));
                text.setPersistent(false);
                text.setInvulnerable(true);
                // Invisible to the world; only the owner is shown this row.
                text.setVisibleByDefault(false);
                text.getPersistentDataContainer().set(plugin.panelIdKey(), PersistentDataType.STRING, panelId);
                text.getPersistentDataContainer().set(plugin.panelRoleKey(), PersistentDataType.STRING, "personal-number");
            });
            player.showEntity(plugin, display);
            ids.add(display.getUniqueId());
        }
        rows.put(panelId, ids);
        setSharedRowHidden(player, panelId, true);
        return true;
    }

    /** Slides the player's row: teleport interpolation moves, scale interpolates. */
    private void animateRow(Player player, String panelId, Location base, int selection) {
        Map<String, List<UUID>> rows = personalRows.get(player.getUniqueId());
        List<UUID> ids = rows == null ? null : rows.get(panelId);
        if (ids == null) {
            return;
        }
        FileConfiguration config = plugin.getConfig();
        float yaw = base.getYaw() + (config.getBoolean("difficulty-panel.flip-facing", false) ? 180.0F : 0.0F);
        double radians = Math.toRadians(yaw);
        Vector facing = new Vector(-Math.sin(radians), 0.0, Math.cos(radians));
        Vector rightward = new Vector(facing.getZ(), 0.0, -facing.getX());
        Placement placement = new Placement(base.getWorld(), base, yaw, facing, rightward, 15);
        int slideTicks = Math.clamp(config.getInt("difficulty-panel.slide-ticks", 4), 0, 40);
        for (int index = 0; index < ids.size() && index < 9; index++) {
            Entity entity = Bukkit.getEntity(ids.get(index));
            if (!(entity instanceof TextDisplay display)) {
                continue;
            }
            NumberPose pose = numberPose(index + 1, selection);
            display.teleport(numberLocation(placement, pose));
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(slideTicks);
            display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(pose.scale(), pose.scale(), pose.scale()), new Quaternionf()));
            display.setTextOpacity(pose.opacity());
        }
    }

    /** Where number {@code number} sits and how it looks for a given selection. */
    private NumberPose numberPose(int number, int selection) {
        FileConfiguration config = plugin.getConfig();
        int visible = Math.clamp(config.getInt("difficulty-panel.numbers.visible-each-side", 2), 1, 4);
        double spacing = config.getDouble("difficulty-panel.numbers.spacing", 0.55);
        double centreExtra = config.getDouble("difficulty-panel.numbers.centre-extra", 0.18);
        List<Double> scales = withFallback(config.getDoubleList("difficulty-panel.numbers.scales"),
                List.of(2.8, 1.6, 1.0));
        List<Integer> opacities = withFallback(config.getIntegerList("difficulty-panel.numbers.opacities"),
                List.of(255, 165, 85));
        int step = number - selection;
        int magnitude = Math.abs(step);
        double x = step * spacing + Math.signum(step) * centreExtra;
        if (magnitude > visible) {
            // Off the carousel's edge: shrunk away rather than removed, so it
            // can grow back in with the same interpolated slide.
            return new NumberPose(number, x, 0.05F, MINIMUM_OPACITY);
        }
        float scale = scales.get(Math.min(magnitude, scales.size() - 1)).floatValue();
        byte opacity = (byte) Math.clamp(opacities.get(Math.min(magnitude, opacities.size() - 1)),
                MINIMUM_OPACITY, 255);
        return new NumberPose(number, x, scale, opacity);
    }

    private Location numberLocation(Placement placement, NumberPose pose) {
        Location at = placement.base().clone()
                .add(placement.rightward().clone().multiply(pose.x()))
                .add(0.0, plugin.getConfig().getDouble("difficulty-panel.numbers.height", 1.72), 0.0)
                .add(placement.facing().clone().multiply(0.05));
        at.setYaw(placement.yaw());
        at.setPitch(0.0F);
        return at;
    }

    /** Hides or restores the shared default row for one player. */
    private void setSharedRowHidden(Player player, String panelId, boolean hidden) {
        for (UUID id : sharedNumbers.getOrDefault(panelId, List.of())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null) {
                continue;
            }
            if (hidden) {
                player.hideEntity(plugin, entity);
            } else {
                player.showEntity(plugin, entity);
            }
        }
    }

    /**
     * The proximity sweep, run a few times per second. It gives engaged
     * players their row back when they return to a panel, and takes rows away
     * from players who wandered off, changed world, or logged out.
     */
    public void tick() {
        double radius = Math.max(4.0, plugin.getConfig().getDouble("difficulty-panel.activation-radius", 24.0));
        double radiusSquared = radius * radius;
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean engaged = selections.containsKey(player.getUniqueId());
            Map<String, List<UUID>> rows = personalRows.get(player.getUniqueId());
            for (Map.Entry<String, Location> panel : panels.entrySet()) {
                Location base = panel.getValue();
                boolean inRange = base.getWorld().equals(player.getWorld())
                        && base.distanceSquared(player.getLocation()) <= radiusSquared;
                boolean hasRow = rows != null && rows.containsKey(panel.getKey());
                if (engaged && inRange) {
                    ensurePersonalRow(player, panel.getKey(), base,
                            selections.getOrDefault(player.getUniqueId(), 1));
                } else if (hasRow) {
                    removePersonalRow(player.getUniqueId(), panel.getKey());
                }
            }
        }
        // Rows whose owner is gone would otherwise stand invisible forever.
        personalRows.entrySet().removeIf(entry -> {
            if (Bukkit.getPlayer(entry.getKey()) != null) {
                return false;
            }
            for (List<UUID> ids : entry.getValue().values()) {
                removeEntities(ids);
            }
            return true;
        });
    }

    /** Logout: the session is over, so both the row and the selection go. */
    public void handleQuit(Player player) {
        clearPersonalRows(player);
        selections.remove(player.getUniqueId());
    }

    /** Death or a world change: the row goes, the session selection stays. */
    public void clearPersonalRows(Player player) {
        Map<String, List<UUID>> rows = personalRows.remove(player.getUniqueId());
        if (rows == null) {
            return;
        }
        for (Map.Entry<String, List<UUID>> entry : rows.entrySet()) {
            removeEntities(entry.getValue());
            if (player.isOnline()) {
                setSharedRowHidden(player, entry.getKey(), false);
            }
        }
    }

    private void removePersonalRow(UUID playerId, String panelId) {
        Map<String, List<UUID>> rows = personalRows.get(playerId);
        if (rows == null) {
            return;
        }
        List<UUID> ids = rows.remove(panelId);
        if (ids != null) {
            removeEntities(ids);
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            setSharedRowHidden(player, panelId, false);
        }
    }

    private static void removeEntities(List<UUID> ids) {
        for (UUID id : ids) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    private void playSound(Player player, String key, float pitch) {
        String raw = plugin.getConfig().getString("difficulty-panel.sounds." + key, "UI_BUTTON_CLICK");
        Sound sound = raw == null || raw.isBlank() ? null
                : Registry.SOUNDS.get(NamespacedKey.minecraft(raw.toLowerCase(java.util.Locale.ROOT).replace('_', '.')));
        if (sound != null) {
            // Only the clicking player hears it; the panel is used by several
            // people at once and their clicks are not each other's business.
            player.playSound(player.getLocation(), sound, 0.7F, pitch);
        }
    }

    // ------------------------------------------------------------------
    //  Rendering
    // ------------------------------------------------------------------

    /**
     * Rebuilds a panel's entities from scratch. Always cleans first, so calling
     * it twice can never leave two panels standing on top of each other.
     */
    private void render(String id, Location base) {
        World world = base.getWorld();
        if (world == null || !world.isChunkLoaded(base.getBlockX() >> 4, base.getBlockZ() >> 4)) {
            return;
        }
        clearEntities(id, base);
        FileConfiguration config = plugin.getConfig();
        // The panel can be turned around in place when a placement reads
        // mirrored, without editing panels.yml.
        float yaw = base.getYaw() + (config.getBoolean("difficulty-panel.flip-facing", false) ? 180.0F : 0.0F);
        double radians = Math.toRadians(yaw);
        Vector facing = new Vector(-Math.sin(radians), 0.0, Math.cos(radians));
        // The viewer stands on the facing side looking back, so their right
        // hand points along this vector; numbers and ">" grow that way.
        Vector rightward = new Vector(facing.getZ(), 0.0, -facing.getX());
        Placement placement = new Placement(world, base, yaw, facing, rightward,
                Math.clamp(config.getInt("difficulty-panel.brightness", 15), 0, 15));
        List<UUID> ids = new ArrayList<>();

        float headingScale = (float) config.getDouble("difficulty-panel.heading.scale", 1.5);
        ids.add(spawnText(placement, id, "heading", 0.0,
                config.getDouble("difficulty-panel.heading.height", 2.7), 0.03,
                miniMessage.deserialize(config.getString("difficulty-panel.heading.text",
                        "<gradient:#c9a227:#f2e39b><bold>Select Difficulty")),
                headingScale, headingScale, (byte) 255, null));

        // The shared default row: what a player sees before their first click.
        // Once they engage, this row is hidden for them personally and their
        // own carousel takes its place.
        List<UUID> numberIds = renderNumberRow(placement, id, 1);
        ids.addAll(numberIds);
        sharedNumbers.put(id, List.copyOf(numberIds));
        // Re-rendering removed every tagged entity, including personal rows;
        // the proximity tick rebuilds those for engaged players still nearby.
        for (Map<String, List<UUID>> rows : personalRows.values()) {
            rows.remove(id);
        }

        float arrowScale = (float) config.getDouble("difficulty-panel.arrows.scale", 2.4);
        double arrowOffset = config.getDouble("difficulty-panel.arrows.offset", 2.2);
        double arrowHeight = config.getDouble("difficulty-panel.arrows.height", 1.75);
        ids.add(spawnText(placement, id, "arrow-left", -arrowOffset, arrowHeight, 0.03,
                miniMessage.deserialize(config.getString("difficulty-panel.arrows.left", "<color:#c9a227><bold><")),
                arrowScale, arrowScale, (byte) 255, null));
        ids.add(spawnText(placement, id, "arrow-right", arrowOffset, arrowHeight, 0.03,
                miniMessage.deserialize(config.getString("difficulty-panel.arrows.right", "<color:#c9a227><bold>>")),
                arrowScale, arrowScale, (byte) 255, null));

        // The button: the gradient lives in the label, exactly like the chat
        // prefix, over a dark fill inside a gold border.
        float buttonScale = (float) config.getDouble("difficulty-panel.button.scale", 1.4);
        double buttonHeight = config.getDouble("difficulty-panel.button.height", 1.0);
        Component label = miniMessage.deserialize(config.getString("difficulty-panel.button.text",
                "<gradient:#c9a227:#f2e39b><bold>Enter Dungeon"));
        buttons.put(id, spawnButtonPlate(placement, id, ids, buttonHeight, buttonScale, label));
        // A text display draws its line upwards from its own position while
        // the plate is centred on it, so the label has to come down by half a
        // line to sit in the middle. Sideways it moves half a pixel right,
        // cancelling the trailing gap that a centred line includes.
        double labelUnit = buttonScale
                / Math.max(1.0, config.getDouble("difficulty-panel.button.plate.pixels-per-block", 40.0));
        double labelX = config.getDouble("difficulty-panel.button.plate.label-x-pixels", 0.5) * labelUnit;
        double labelY = buttonHeight + config.getDouble("difficulty-panel.button.plate.y-offset", 0.0)
                - config.getDouble("difficulty-panel.button.plate.label-y-pixels", 4.5) * labelUnit;
        // Derived, never a fixed number: the label has to clear the front face
        // of the fill bar, which is a solid block that writes depth. Sitting
        // inside it, the label was hidden up close and only broke through at
        // range, where depth precision falls apart.
        double labelZ = config.getDouble("difficulty-panel.button.plate.fill-z", 0.031)
                + config.getDouble("difficulty-panel.button.plate.depth", 0.02) / 2.0
                + config.getDouble("difficulty-panel.button.plate.label-clearance", 0.015);
        ids.add(spawnText(placement, id, "button", labelX, labelY, labelZ, label,
                buttonScale, buttonScale, (byte) 255, null));

        // Clickable regions, deliberately larger than the text they cover:
        // a crosshair is less precise than a mouse pointer.
        double arrowHitWidth = config.getDouble("difficulty-panel.hitboxes.arrow-width", 1.3);
        double arrowHitHeight = config.getDouble("difficulty-panel.hitboxes.arrow-height", 1.3);
        ids.add(spawnHitbox(placement, id, "hit-arrow-left", -arrowOffset, arrowHeight, arrowHitWidth, arrowHitHeight));
        ids.add(spawnHitbox(placement, id, "hit-arrow-right", arrowOffset, arrowHeight, arrowHitWidth, arrowHitHeight));
        ids.add(spawnHitbox(placement, id, "hit-button", 0.0, buttonHeight,
                config.getDouble("difficulty-panel.hitboxes.button-width", 2.6),
                config.getDouble("difficulty-panel.hitboxes.button-height", 1.0)));

        spawned.put(id, ids);
    }

    /** The carousel: the selection dead centre, neighbours shrinking and fading. */
    private List<UUID> renderNumberRow(Placement placement, String id, int selection) {
        FileConfiguration config = plugin.getConfig();
        int visible = Math.clamp(config.getInt("difficulty-panel.numbers.visible-each-side", 2), 1, 4);
        double spacing = config.getDouble("difficulty-panel.numbers.spacing", 0.55);
        double centreExtra = config.getDouble("difficulty-panel.numbers.centre-extra", 0.18);
        double height = config.getDouble("difficulty-panel.numbers.height", 1.72);
        String format = config.getString("difficulty-panel.numbers.format", "<color:#f2e39b><bold><n>");
        List<Double> scales = withFallback(config.getDoubleList("difficulty-panel.numbers.scales"),
                List.of(2.8, 1.6, 1.0));
        List<Integer> opacities = withFallback(config.getIntegerList("difficulty-panel.numbers.opacities"),
                List.of(255, 165, 85));

        List<UUID> ids = new ArrayList<>();
        for (int step = -visible; step <= visible; step++) {
            int number = selection + step;
            // The row stops at the real ends: nothing is drawn beyond 1 and 9,
            // which is also what makes "stops, does not wrap" visible.
            if (number < 1 || number > 9) {
                continue;
            }
            int magnitude = Math.abs(step);
            float scale = scales.get(Math.min(magnitude, scales.size() - 1)).floatValue();
            byte opacity = (byte) Math.clamp(opacities.get(Math.min(magnitude, opacities.size() - 1)),
                    MINIMUM_OPACITY, 255);
            double x = step * spacing + Math.signum(step) * centreExtra;
            ids.add(spawnText(placement, id, "number-" + number, x, height, 0.03,
                    miniMessage.deserialize(format, Messages.ph("n", Integer.toString(number))),
                    scale, scale, opacity, null));
        }
        return ids;
    }

    /**
     * The plate behind the Enter label: a stepped-corner shape in block
     * displays, a gold border layer behind a dark fill layer.
     *
     * <p>Block displays carry no text, which is the point. The previous plates
     * were text displays rendering the label dyed to their own background
     * colour as a width reference, and that was only invisible by
     * coincidence: at range the enlarged border plate's gold copy of the label
     * showed through and doubled the text. Nothing here can do that.</p>
     *
     * <p>Sizes are computed from the label's measured pixel width, so the
     * shape tracks whatever the label is set to in config, and the border is
     * an exact even ring around the fill by construction.</p>
     *
     * @return the fill bars, which the pressed-flash swaps to another block
     */
    private List<UUID> spawnButtonPlate(Placement placement, String id, List<UUID> ids,
                                        double buttonHeight, float labelScale, Component label) {
        FileConfiguration config = plugin.getConfig();
        String path = "difficulty-panel.button.plate.";
        Material fillBlock = blockMaterial(config.getString(path + "fill-block"), Material.BLACK_CONCRETE);
        Material borderBlock = blockMaterial(config.getString(path + "border-block"), Material.YELLOW_CONCRETE);
        String plain = PlainTextComponentSerializer.plainText().serialize(label);

        // One font pixel, in blocks, at the label's own scale. Everything is
        // measured in these so the shape stays proportional to the text.
        double unit = labelScale / Math.max(1.0, config.getDouble(path + "pixels-per-block", 40.0));
        double labelWidth = labelPixelWidth(plain, true) * unit;
        double labelHeight = config.getDouble(path + "line-height-pixels", 11.0) * unit;
        double padding = config.getDouble(path + "padding-pixels", 4.0) * unit;
        double thickness = config.getDouble(path + "border-pixels", 2.0) * unit;
        double step = config.getDouble(path + "corner-step-pixels", 2.0) * unit;
        double depth = config.getDouble(path + "depth", 0.02);
        double y = buttonHeight + config.getDouble(path + "y-offset", 0.0);

        double fillWidth = labelWidth + 2.0 * padding;
        double fillHeight = labelHeight + 2.0 * padding;
        ids.addAll(steppedPlate(placement, id, "button-border", y,
                config.getDouble(path + "border-z", 0.010), depth,
                fillWidth + 2.0 * thickness, fillHeight + 2.0 * thickness, step, borderBlock));
        List<UUID> fillBars = steppedPlate(placement, id, "button-fill", y,
                config.getDouble(path + "fill-z", 0.031), depth, fillWidth, fillHeight, step, fillBlock);
        ids.addAll(fillBars);
        return fillBars;
    }

    /**
     * Three overlapping bars forming a rectangle whose corners are stepped in
     * twice: full width but shortest, then one step in and one step taller,
     * then narrowest and full height.
     */
    private List<UUID> steppedPlate(Placement placement, String id, String role, double y, double z,
                                    double depth, double width, double height, double step, Material material) {
        double[][] bars = {
                {width, height - 4.0 * step},
                {width - 2.0 * step, height - 2.0 * step},
                {width - 4.0 * step, height},
        };
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < bars.length; index++) {
            ids.add(spawnBar(placement, id, role + "-" + index, y, z, depth,
                    Math.max(0.01, bars[index][0]), Math.max(0.01, bars[index][1]), material));
        }
        return ids;
    }

    /** One bar of the plate, centred on the panel's vertical middle line. */
    private UUID spawnBar(Placement placement, String id, String role, double y, double z, double depth,
                          double width, double height, Material material) {
        Location at = placement.base().clone()
                .add(0.0, y, 0.0)
                .add(placement.facing().clone().multiply(z));
        at.setYaw(placement.yaw());
        at.setPitch(0.0F);
        float viewRange = (float) plugin.getConfig().getDouble("difficulty-panel.view-range", 2.0);
        BlockDisplay display = placement.world().spawn(at, BlockDisplay.class, block -> {
            block.setBlock(material.createBlockData());
            // A block display fills the unit cube from its own corner, so the
            // translation pulls it back by half its size to centre it. The
            // entity's yaw already puts local x along the panel's width.
            block.setTransformation(new Transformation(
                    new Vector3f((float) (-width / 2.0), (float) (-height / 2.0), (float) (-depth / 2.0)),
                    new Quaternionf(),
                    new Vector3f((float) width, (float) height, (float) depth),
                    new Quaternionf()));
            block.setBrightness(new Display.Brightness(placement.brightness(), placement.brightness()));
            block.setViewRange(viewRange);
            block.setPersistent(false);
            block.setInvulnerable(true);
            block.getPersistentDataContainer().set(plugin.panelIdKey(), PersistentDataType.STRING, id);
            block.getPersistentDataContainer().set(plugin.panelRoleKey(), PersistentDataType.STRING, role);
        });
        return display.getUniqueId();
    }

    private static Material blockMaterial(String raw, Material fallback) {
        Material material = raw == null ? null : Material.matchMaterial(raw.toUpperCase(java.util.Locale.ROOT));
        return material == null || !material.isBlock() ? fallback : material;
    }

    /**
     * Width of a string in font pixels, including the one pixel of spacing
     * that follows every glyph.
     *
     * <p>That trailing pixel is also why a centred label looks a touch right
     * of centre: the line is centred including the gap after its last glyph,
     * so the visible text sits half a pixel to the left. {@link #render}
     * nudges the label back by that half pixel.</p>
     */
    private static int labelPixelWidth(String text, boolean bold) {
        int width = 0;
        for (char character : text.toCharArray()) {
            width += glyphWidth(character) + (bold ? 1 : 0) + 1;
        }
        return width;
    }

    /** Advance widths of Minecraft's default font. */
    private static int glyphWidth(char character) {
        return switch (character) {
            case 'i', '!', ',', '.', ':', ';', '|', '\'' -> 1;
            case 'l', '`' -> 2;
            case ' ', 't', 'I', '[', ']', '{', '}', '(', ')', '"', '*' -> 3;
            case 'f', 'k', '<', '>' -> 4;
            case '@', '~' -> 6;
            default -> 5;
        };
    }

    /** An invisible clickable box, vertically centred on the text it covers. */
    private UUID spawnHitbox(Placement placement, String panelId, String role, double x, double y,
                             double width, double height) {
        Location at = placement.base.clone()
                .add(placement.rightward.clone().multiply(x))
                // Interaction entities anchor at their feet; the visual glyph
                // centre sits a little above the element's base height.
                .add(0.0, y + 0.15 - height / 2.0, 0.0)
                .add(placement.facing.clone().multiply(0.05));
        Interaction hitbox = placement.world.spawn(at, Interaction.class, interaction -> {
            interaction.setInteractionWidth((float) width);
            interaction.setInteractionHeight((float) height);
            interaction.setResponsive(true);
            interaction.setPersistent(false);
            interaction.setInvulnerable(true);
            interaction.getPersistentDataContainer().set(plugin.panelIdKey(), PersistentDataType.STRING, panelId);
            interaction.getPersistentDataContainer().set(plugin.panelRoleKey(), PersistentDataType.STRING, role);
        });
        return hitbox.getUniqueId();
    }

    private UUID spawnText(Placement placement, String panelId, String role, double x, double y, double z,
                           Component content, float scaleX, float scaleY, byte opacity, Color background) {
        Location at = placement.base.clone()
                .add(placement.rightward.clone().multiply(x))
                .add(0.0, y, 0.0)
                .add(placement.facing.clone().multiply(z));
        at.setYaw(placement.yaw);
        at.setPitch(0.0F);
        TextDisplay display = placement.world.spawn(at, TextDisplay.class, text -> {
            text.text(content);
            text.setBillboard(Display.Billboard.FIXED);
            text.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scaleX, scaleY, scaleX), new Quaternionf()));
            text.setTextOpacity(opacity);
            text.setShadowed(false);
            text.setSeeThrough(false);
            text.setLineWidth(400);
            text.setBackgroundColor(background == null ? Color.fromARGB(0) : background);
            text.setBrightness(new Display.Brightness(placement.brightness, placement.brightness));
            text.setViewRange((float) plugin.getConfig().getDouble("difficulty-panel.view-range", 2.0));
            // Never written to the world save: panels.yml is the only truth,
            // so a restart cannot duplicate what the chunk already had.
            text.setPersistent(false);
            text.setInvulnerable(true);
            text.getPersistentDataContainer().set(plugin.panelIdKey(), PersistentDataType.STRING, panelId);
            text.getPersistentDataContainer().set(plugin.panelRoleKey(), PersistentDataType.STRING, role);
        });
        return display.getUniqueId();
    }

    /** Removes this panel's entities: the tracked ones plus any tagged stray nearby. */
    private void clearEntities(String id, Location base) {
        buttons.remove(id);
        List<UUID> ids = spawned.remove(id);
        if (ids != null) {
            for (UUID entityId : ids) {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity != null) {
                    entity.remove();
                }
            }
        }
        World world = base.getWorld();
        if (world == null) {
            return;
        }
        // Load every chunk the panel can reach before sweeping: an entity in
        // an unloaded neighbour is invisible to getNearbyEntities and would
        // survive the rebuild to stand alongside its replacement.
        double reach = Math.max(8.0, plugin.getConfig().getDouble("difficulty-panel.cleanup-radius", 12.0));
        for (int chunkX = (int) (base.getX() - reach) >> 4; chunkX <= (int) (base.getX() + reach) >> 4; chunkX++) {
            for (int chunkZ = (int) (base.getZ() - reach) >> 4; chunkZ <= (int) (base.getZ() + reach) >> 4; chunkZ++) {
                world.getChunkAt(chunkX, chunkZ);
            }
        }
        for (Entity entity : world.getNearbyEntities(base, reach, reach, reach)) {
            String entityPanel = panelId(entity);
            // This panel's own entities, and strays whose panel is long gone -
            // including any left by an older version of the panel layout.
            if (entityPanel != null && (id.equals(entityPanel) || !panels.containsKey(entityPanel))) {
                entity.remove();
            }
        }
    }

    private String panelId(Entity entity) {
        return entity.getPersistentDataContainer().get(plugin.panelIdKey(), PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------

    private void save() {
        YamlConfiguration storage = new YamlConfiguration();
        for (Map.Entry<String, Location> entry : panels.entrySet()) {
            String path = "panels." + entry.getKey();
            Location base = entry.getValue();
            storage.set(path + ".world", base.getWorld().getName());
            storage.set(path + ".x", base.getX());
            storage.set(path + ".y", base.getY());
            storage.set(path + ".z", base.getZ());
            storage.set(path + ".yaw", base.getYaw());
        }
        try {
            storage.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save " + STORAGE + ": " + exception.getMessage());
        }
    }

    private static <T> List<T> withFallback(List<T> configured, List<T> fallback) {
        return configured == null || configured.isEmpty() ? fallback : configured;
    }

    private static Color argb(String raw) {
        try {
            String hex = raw == null ? "" : raw.replace("#", "").trim();
            long value = Long.parseLong(hex, 16);
            return hex.length() > 6 ? Color.fromARGB((int) value) : Color.fromRGB((int) value);
        } catch (NumberFormatException ignored) {
            return Color.fromARGB(0xE66A1FB0);
        }
    }

    private record Placement(World world, Location base, float yaw, Vector facing, Vector rightward, int brightness) {
    }

    /** One number's spot on the carousel for a particular selection. */
    private record NumberPose(int number, double x, float scale, byte opacity) {
    }


    public record PanelInfo(String id, Location location) {
    }

    /** Summary used by the administrative remove-all command. */
    public record RemovalReport(int count, List<Location> locations) {
    }
}
