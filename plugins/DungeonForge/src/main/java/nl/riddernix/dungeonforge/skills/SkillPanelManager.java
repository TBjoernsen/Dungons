package nl.riddernix.dungeonforge.skills;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.skills.SkillTreeLibrary.SkillBranch;
import nl.riddernix.dungeonforge.skills.SkillTreeLibrary.SkillClassTree;
import nl.riddernix.dungeonforge.skills.SkillTreeLibrary.SkillNode;
import nl.riddernix.dungeonforge.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.Sound;
import org.bukkit.World;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fixed in-world skill tree panels.
 *
 * <p>The furniture is shared: every viewer sees the same structure, drawn
 * entirely in the locked (dimmest) style. What a particular player has
 * unlocked, what is available to them and what they have selected is a
 * per-viewer overlay - entities spawned invisible-by-default and shown only to
 * their owner, exactly like the difficulty panel's number row.</p>
 *
 * <p>Every node has a click surface, but a click is re-resolved server-side to
 * the node nearest the player's line of aim, so neighbouring hitboxes can
 * never fight over it: whichever box catches the click, the node being aimed
 * at wins.</p>
 */
public final class SkillPanelManager {

    private static final String STORAGE = "skill-panels.yml";
    /** The vertical centring correction measured on the difficulty panel. */
    private static final double GLYPH_CENTRE_PIXELS = 4.5;

    private final DungeonForgePlugin plugin;
    private final File storageFile;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    /** Panel id to its placement; the location's yaw is the panel's facing. */
    private final Map<String, PlacedSkillPanel> panels = new LinkedHashMap<>();
    private final Map<String, List<UUID>> spawned = new HashMap<>();
    /**
     * Panel to node to its shared entities. A player with their own coloured
     * plates for a node has the shared one hidden instead of covered, so no
     * locked plate can peek out from behind theirs at an angle.
     */
    private final Map<String, Map<String, List<UUID>>> sharedNodes = new HashMap<>();

    /** Per player, per panel: their unlocked/available overlay entities. */
    private final Map<UUID, Map<String, List<UUID>>> stateOverlays = new HashMap<>();
    /** Per player, per panel: their selection ring and detail panel. */
    private final Map<UUID, Map<String, List<UUID>>> selectionOverlays = new HashMap<>();
    /** Per player, per panel: the node they last clicked. */
    private final Map<UUID, Map<String, String>> selections = new HashMap<>();
    /**
     * Per player, per class: nodes treated as unlocked. Deliberately in-memory
     * and command-driven: this is the render test bed until phase three
     * replaces it with real, persistent progression.
     */
    private final Map<UUID, Map<String, Set<String>>> testUnlocked = new HashMap<>();
    /** Per player, per panel: which carousel position they are viewing. */
    private final Map<UUID, Map<String, Integer>> carouselIndex = new HashMap<>();
    /** Per player, per panel: their carousel label entities. */
    private final Map<UUID, Map<String, List<UUID>>> carouselOverlays = new HashMap<>();
    /** Per player, per panel: the open class-info panel, if any. */
    private final Map<UUID, Map<String, List<UUID>>> infoOverlays = new HashMap<>();
    /** Per player, per panel: their own point readout where Confirm used to be. */
    private final Map<UUID, Map<String, List<UUID>>> pointsOverlays = new HashMap<>();

    public SkillPanelManager(DungeonForgePlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), STORAGE);
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    public void load() {
        panels.clear();
        YamlConfiguration storage = YamlConfiguration.loadConfiguration(storageFile);
        ConfigurationSection section = storage.getConfigurationSection("panels");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                World world = Bukkit.getWorld(section.getString(id + ".world", ""));
                if (world == null) {
                    plugin.getLogger().warning("Ignoring skill panel '" + id + "': its world is not loaded.");
                    continue;
                }
                panels.put(id, new PlacedSkillPanel(
                        new Location(world, section.getDouble(id + ".x"), section.getDouble(id + ".y"),
                                section.getDouble(id + ".z"), (float) section.getDouble(id + ".yaw"), 0.0F),
                        section.getString(id + ".class", "warrior"),
                        SkillPanelGeometry.Variant.parse(section.getString(id + ".variant"))));
            }
        }
        renderLoadedPanels();
        sweepOrphans();
    }

    public void reload() {
        renderLoadedPanels();
    }

    public void despawnAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateClickReach(player, false);
        }
        for (Map<String, List<UUID>> overlays : stateOverlays.values()) {
            overlays.values().forEach(SkillPanelManager::removeEntities);
        }
        for (Map<String, List<UUID>> overlays : selectionOverlays.values()) {
            overlays.values().forEach(SkillPanelManager::removeEntities);
        }
        stateOverlays.clear();
        selectionOverlays.clear();
        for (Map.Entry<String, PlacedSkillPanel> entry : panels.entrySet()) {
            clearEntities(entry.getKey(), entry.getValue().base());
        }
    }

    private void renderLoadedPanels() {
        for (Map.Entry<String, PlacedSkillPanel> entry : panels.entrySet()) {
            render(entry.getKey(), entry.getValue());
        }
    }

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
            plugin.getLogger().info("Removed " + removed + " orphaned skill panel entit(ies).");
        }
    }

    /** Respawns a panel when its chunk comes back, one tick later. */
    public void handleChunkLoad(Chunk chunk) {
        for (Map.Entry<String, PlacedSkillPanel> entry : panels.entrySet()) {
            Location base = entry.getValue().base();
            if (!chunk.getWorld().equals(base.getWorld())
                    || base.getBlockX() >> 4 != chunk.getX() || base.getBlockZ() >> 4 != chunk.getZ()) {
                continue;
            }
            String id = entry.getKey();
            Bukkit.getScheduler().runTask(plugin, () -> {
                PlacedSkillPanel current = panels.get(id);
                if (current != null) {
                    render(id, current);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    //  Placement and removal
    // ------------------------------------------------------------------

    /** @return the panel id, or empty when the class has no tree */
    public Optional<String> place(Location where, String classId, SkillPanelGeometry.Variant variant) {
        if (plugin.skillTrees().tree(classId).isEmpty()) {
            return Optional.empty();
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        Location base = where.clone();
        base.setPitch(0.0F);
        PlacedSkillPanel panel = new PlacedSkillPanel(base, classId.toLowerCase(Locale.ROOT), variant);
        panels.put(id, panel);
        render(id, panel);
        save();
        return Optional.of(id);
    }

    /**
     * Moves the panel nearest {@code from} to {@code to}, keeping its id,
     * class and variant.
     *
     * <p>Rebuilt rather than nudged: the tree is dozens of display entities
     * whose offsets are all computed from the base, so recreating them at the
     * new spot is both simpler and the only way a rotation lands right.
     * Everyone's per-viewer overlays are dropped with it and come back on the
     * next proximity sweep.</p>
     *
     * @return the panel's new location, empty when nothing was near enough
     */
    public Optional<Location> moveNearest(Location from, Location to) {
        double radius = Math.max(1.0, plugin.getConfig().getDouble("skill-panel.remove-radius", 5.0));
        String nearest = nearestPanel(from, radius * radius).orElse(null);
        if (nearest == null) {
            return Optional.empty();
        }
        PlacedSkillPanel existing = panels.get(nearest);
        clearEntities(nearest, existing.base());
        Location base = to.clone();
        base.setPitch(0.0F);
        PlacedSkillPanel moved = new PlacedSkillPanel(base, existing.classId(), existing.variant());
        panels.put(nearest, moved);
        render(nearest, moved);
        save();
        return Optional.of(base);
    }

    public boolean removeNearest(Location from) {
        double radius = Math.max(1.0, plugin.getConfig().getDouble("skill-panel.remove-radius", 5.0));
        String nearest = nearestPanel(from, radius * radius).orElse(null);
        if (nearest == null) {
            return false;
        }
        PlacedSkillPanel panel = panels.remove(nearest);
        clearEntities(nearest, panel.base());
        save();
        return true;
    }

    public RemovalReport removeAll() {
        List<Location> locations = new ArrayList<>();
        for (Map.Entry<String, PlacedSkillPanel> entry : new ArrayList<>(panels.entrySet())) {
            clearEntities(entry.getKey(), entry.getValue().base());
            locations.add(entry.getValue().base().clone());
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
        for (Map.Entry<String, PlacedSkillPanel> entry : panels.entrySet()) {
            result.add(new PanelInfo(entry.getKey(), entry.getValue().classId(),
                    entry.getValue().variant().configName(), entry.getValue().base().clone()));
        }
        return List.copyOf(result);
    }

    public boolean isPanelEntity(Entity entity) {
        return panelId(entity) != null;
    }

    private Optional<String> nearestPanel(Location from, double maximumDistanceSquared) {
        String nearest = null;
        double nearestDistanceSquared = maximumDistanceSquared;
        for (Map.Entry<String, PlacedSkillPanel> entry : panels.entrySet()) {
            Location base = entry.getValue().base();
            if (!base.getWorld().equals(from.getWorld())) {
                continue;
            }
            double distanceSquared = base.distanceSquared(from);
            if (distanceSquared <= nearestDistanceSquared) {
                nearest = entry.getKey();
                nearestDistanceSquared = distanceSquared;
            }
        }
        return Optional.ofNullable(nearest);
    }

    // ------------------------------------------------------------------
    //  Interaction
    // ------------------------------------------------------------------

    /**
     * A click on any node hitbox. The hitbox that caught it only tells us the
     * player clicked at the tree; the node is chosen by their line of aim.
     */
    public void handleClick(Player player, Entity clicked) {
        String panelId = panelId(clicked);
        String role = clicked.getPersistentDataContainer().get(plugin.skillPanelRoleKey(), PersistentDataType.STRING);
        if (panelId == null || role == null || !role.startsWith("hit-")) {
            return;
        }
        PlacedSkillPanel panel = panels.get(panelId);
        if (panel == null) {
            return;
        }
        switch (role) {
            case "hit-class-left" -> { shiftClass(player, panelId, panel, -1); return; }
            case "hit-class-right" -> { shiftClass(player, panelId, panel, 1); return; }
            default -> {
                // Falls through to node handling below.
            }
        }
        SkillClassTree tree = plugin.skillTrees().tree(viewedClass(player, panelId, panel)).orElse(null);
        if (tree == null) {
            return;
        }
        String nodeId = resolveByAim(player, panel, tree)
                .orElse(role.substring("hit-node-".length()));
        if (!tree.nodes().containsKey(nodeId)) {
            return;
        }
        String previousSelection = selections.getOrDefault(player.getUniqueId(), Map.of()).get(panelId);
        selections.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>()).put(panelId, nodeId);
        if (nodeId.equals(previousSelection)) {
            // The second click on the same node is the commitment; the first
            // one only selected it, so browsing can never spend a point.
            attemptUnlock(player, panelId, panel, tree, nodeId);
            return;
        }
        playSound(player, "select");
        // Selecting is not unlocking: only the ring and the detail panel move.
        refreshSelectionOverlay(player, panelId, panel, tree);
    }

    private void attemptUnlock(Player player, String panelId, PlacedSkillPanel panel,
                               SkillClassTree tree, String nodeId) {
        // Points are always spent in the player's own class, never in one they
        // happen to be looking at. Nothing on this panel decides which class
        // that is any more - that lives elsewhere entirely.
        String classId = plugin.skillProgress().activeClass(player.getUniqueId()).orElse(null);
        if (classId == null) {
            plugin.messages().send(player, "skills-no-active-class");
            return;
        }
        if (!classId.equals(viewedClass(player, panelId, panel))) {
            plugin.messages().send(player, "skills-not-your-class", Messages.ph("class", classId));
            return;
        }
        SkillProgressManager.UnlockResult result = plugin.skillProgress().unlock(player, classId, nodeId);
        switch (result) {
            case UNLOCKED -> {
                playSound(player, "select");
                plugin.messages().send(player, "skills-unlocked", Messages.ph("node", nodeId),
                        Messages.ph("points", plugin.skillProgress().points(player.getUniqueId())));
                rebuildStateOverlay(player, panelId, panel, tree);
                refreshSelectionOverlay(player, panelId, panel, tree);
                rebuildPointsDisplay(player, panelId, panel);
            }
            case ALREADY_UNLOCKED -> plugin.messages().send(player, "skills-unlock-already", Messages.ph("node", nodeId));
            case LOCKED -> plugin.messages().send(player, "skills-unlock-locked", Messages.ph("node", nodeId));
            case DIFFICULTY_LOCKED -> plugin.messages().send(player, "skills-unlock-difficulty",
                    Messages.ph("node", nodeId),
                    Messages.ph("difficulty", tree.nodes().get(nodeId).requiresDifficulty()));
            case NOT_ENOUGH_POINTS -> plugin.messages().send(player, "skills-unlock-no-points",
                    Messages.ph("node", nodeId), Messages.ph("points", plugin.skillProgress().points(player.getUniqueId())));
            case REFUSED -> plugin.messages().send(player, "skills-unlock-refused", Messages.ph("node", nodeId));
            case UNKNOWN_NODE -> {
                // The aim resolver only offers nodes from this tree, so this
                // cannot normally happen; stay silent rather than confuse.
            }
        }
    }

    /** The node whose centre lies nearest the player's aim line. */
    private Optional<String> resolveByAim(Player player, PlacedSkillPanel panel, SkillClassTree tree) {
        SkillPanelGeometry geometry = geometry(panel);
        Placement placement = placement(panel);
        double rootHeight = geometry.rootHeight();
        double step = geometry.radiusStep();
        Vector origin = player.getEyeLocation().toVector();
        Vector direction = player.getEyeLocation().getDirection().normalize();

        String best = null;
        double bestOffset = geometry.aimTolerance();
        for (SkillNode node : tree.nodes().values()) {
            double[] at = point(node, rootHeight, step);
            Vector centre = placement.base().clone()
                    .add(placement.rightward().clone().multiply(at[0]))
                    .add(0.0, at[1], 0.0)
                    .add(placement.facing().clone().multiply(geometry.zNode()))
                    .toVector();
            Vector toNode = centre.subtract(origin);
            double along = toNode.dot(direction);
            if (along <= 0.0) {
                continue;
            }
            double offset = toNode.subtract(direction.clone().multiply(along)).length();
            if (offset < bestOffset) {
                best = node.id();
                bestOffset = offset;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The proximity sweep: overlays follow players in and out of range, and
     * overlays of players who left disappear rather than lingering unseen.
     */
    public void tick() {
        double radius = Math.max(4.0, plugin.getConfig().getDouble("skill-panel.activation-radius", 30.0));
        double radiusSquared = radius * radius;
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean nearAnyPanel = false;
            for (Map.Entry<String, PlacedSkillPanel> entry : panels.entrySet()) {
                Location base = entry.getValue().base();
                boolean inRange = base.getWorld().equals(player.getWorld())
                        && base.distanceSquared(player.getLocation()) <= radiusSquared;
                if (inRange) {
                    nearAnyPanel = true;
                    ensureOverlays(player, entry.getKey(), entry.getValue());
                } else {
                    removeOverlays(player.getUniqueId(), entry.getKey(), player);
                }
            }
            updateClickReach(player, nearAnyPanel);
        }
        for (UUID playerId : new ArrayList<>(stateOverlays.keySet())) {
            if (Bukkit.getPlayer(playerId) == null) {
                clearPlayer(playerId);
            }
        }
    }

    /**
     * The vanilla client only sends a click on an entity that is inside the
     * player's entity-interaction-range attribute - about three blocks. That
     * is the hard limit on clicking, and it cannot be worked around: it can
     * only be moved, because since 1.20.5 the attribute is synchronised and
     * the client honours a raised value. Raising it also extends melee reach
     * on mobs, which is why the boost exists only while the player stands
     * inside a skill panel's activation radius, and is removed the moment
     * they leave it.
     */
    private void updateClickReach(Player player, boolean nearPanel) {
        AttributeInstance attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (attribute == null) {
            return;
        }
        NamespacedKey key = new NamespacedKey(plugin, "skill_panel_reach");
        AttributeModifier existing = attribute.getModifier(key);
        double target = plugin.getConfig().getDouble("skill-panel.click-range", 20.0);
        boolean wanted = nearPanel && target > attribute.getBaseValue();
        if (!wanted) {
            if (existing != null) {
                attribute.removeModifier(existing);
            }
            return;
        }
        double amount = target - attribute.getBaseValue();
        if (existing != null) {
            if (Math.abs(existing.getAmount() - amount) < 0.001) {
                return;
            }
            attribute.removeModifier(existing);
        }
        // Transient: never written to the player file, so a crash cannot
        // leave anyone with permanently extended reach.
        attribute.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER));
    }

    /** Logout, world change or death: the rendered overlays go; state stays. */
    public void clearOverlaysFor(Player player) {
        for (String panelId : new ArrayList<>(stateOverlays.getOrDefault(player.getUniqueId(), Map.of()).keySet())) {
            removeOverlays(player.getUniqueId(), panelId, player);
        }
        for (String panelId : new ArrayList<>(selectionOverlays.getOrDefault(player.getUniqueId(), Map.of()).keySet())) {
            removeOverlays(player.getUniqueId(), panelId, player);
        }
    }

    /** Logout proper: the test state and selection are session things. */
    public void handleQuit(Player player) {
        updateClickReach(player, false);
        clearPlayer(player.getUniqueId());
    }

    private void clearPlayer(UUID playerId) {
        Map<String, List<UUID>> state = stateOverlays.remove(playerId);
        if (state != null) {
            state.values().forEach(SkillPanelManager::removeEntities);
        }
        Map<String, List<UUID>> selection = selectionOverlays.remove(playerId);
        if (selection != null) {
            selection.values().forEach(SkillPanelManager::removeEntities);
        }
        Map<String, List<UUID>> carousel = carouselOverlays.remove(playerId);
        if (carousel != null) {
            carousel.values().forEach(SkillPanelManager::removeEntities);
        }
        Map<String, List<UUID>> info = infoOverlays.remove(playerId);
        if (info != null) {
            info.values().forEach(SkillPanelManager::removeEntities);
        }
        Map<String, List<UUID>> points = pointsOverlays.remove(playerId);
        if (points != null) {
            points.values().forEach(SkillPanelManager::removeEntities);
        }
        selections.remove(playerId);
        testUnlocked.remove(playerId);
        carouselIndex.remove(playerId);
    }

    // ------------------------------------------------------------------
    //  Class carousel and Info (big variant)
    // ------------------------------------------------------------------

    /** Which class this player's carousel is pointing at on this panel. */
    private String viewedClass(Player player, String panelId, PlacedSkillPanel panel) {
        if (geometry(panel).controls() == null) {
            return panel.classId();
        }
        List<String> ids = plugin.skillTrees().classIds();
        if (ids.isEmpty()) {
            return panel.classId();
        }
        return ids.get(carouselIndexFor(player, panelId, panel, ids));
    }

    /** Starts on the player's active class, falling back to the placed one. */
    private int carouselIndexFor(Player player, String panelId, PlacedSkillPanel panel, List<String> ids) {
        Map<String, Integer> byPanel = carouselIndex.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        Integer index = byPanel.get(panelId);
        if (index == null) {
            String start = plugin.skillProgress().activeClass(player.getUniqueId()).orElse(panel.classId());
            index = Math.max(0, ids.indexOf(start));
            byPanel.put(panelId, index);
        }
        return Math.clamp(index, 0, Math.max(0, ids.size() - 1));
    }

    /** One carousel step. Clamps at the ends, like the difficulty panel. */
    private void shiftClass(Player player, String panelId, PlacedSkillPanel panel, int direction) {
        SkillPanelGeometry geometry = geometry(panel);
        if (geometry.controls() == null) {
            return;
        }
        List<String> ids = plugin.skillTrees().classIds();
        int current = carouselIndexFor(player, panelId, panel, ids);
        int next = Math.clamp(current + direction, 0, Math.max(0, ids.size() - 1));
        if (next == current) {
            playSound(player, "select");
            return;
        }
        carouselIndex.get(player.getUniqueId()).put(panelId, next);
        playSound(player, "select");
        rebuildCarousel(player, panelId, panel, geometry, true);
        // The tree structure is shared, but everything personal - unlocked
        // overlay, selection detail, open info panel - is per class.
        SkillClassTree tree = plugin.skillTrees().tree(ids.get(next)).orElse(null);
        if (tree != null) {
            rebuildStateOverlay(player, panelId, panel, tree);
            refreshSelectionOverlay(player, panelId, panel, tree);
        }
    }

    private static void setBlocks(List<UUID> ids, Material material) {
        for (UUID id : ids) {
            if (Bukkit.getEntity(id) instanceof BlockDisplay display) {
                display.setBlock(material.createBlockData());
            }
        }
    }

    /**
     * The standing Info area under the tree's left side. Not a button: it is
     * always there, showing the selected node's details, or - when nothing is
     * selected - the class the carousel points at, whether it is the player's
     * active one and how many of its skills they have unlocked.
     */
    private void rebuildInfoPanel(Player player, String panelId, PlacedSkillPanel panel, SkillClassTree tree) {
        SkillPanelGeometry geometry = geometry(panel);
        SkillPanelGeometry.Controls controls = geometry.controls();
        if (controls == null) {
            return;
        }
        Map<String, List<UUID>> byPanel = infoOverlays.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        List<UUID> old = byPanel.remove(panelId);
        if (old != null) {
            removeEntities(old);
        }
        World world = panel.base().getWorld();
        if (world == null || !world.equals(player.getWorld())) {
            return;
        }
        String classId = viewedClass(player, panelId, panel);
        Set<String> unlocked = unlockedFor(player.getUniqueId(), classId);
        String nodeId = selections.getOrDefault(player.getUniqueId(), Map.of()).get(panelId);
        SkillNode node = nodeId == null ? null : tree.nodes().get(nodeId);
        Component content = node != null
                ? nodeDetailContent(node, unlocked)
                : miniMessage.deserialize(
                        plugin.getConfig().getString("skill-panel.info.format",
                                "<name><newline><color:#b3a577><description><newline>"
                                        + "<color:#c9a227>Active class: <active><newline>"
                                        + "<color:#c9a227>Unlocked here: <unlocked>"),
                        Messages.ph("name", plugin.skillTrees().displayName(classId)),
                        Messages.ph("description", plugin.skillTrees().description(classId)),
                        Messages.ph("active", classId.equals(plugin.skillProgress()
                                .activeClass(player.getUniqueId()).orElse(null)) ? "yes" : "no"),
                        Messages.ph("unlocked", Integer.toString(unlocked.size())));
        byPanel.put(panelId, List.of(spawnInfoText(placement(panel), geometry, controls, panelId, content, player)));
    }

    /**
     * The player's own point balance, standing where Confirm used to be.
     *
     * <p>Per viewer rather than shared furniture, for the obvious reason: a
     * balance belongs to one player. Rebuilt from the same places that redraw
     * the tree, so spending a point, being granted one, or having a tree
     * reset all move the number the moment they happen.</p>
     */
    private void rebuildPointsDisplay(Player player, String panelId, PlacedSkillPanel panel) {
        SkillPanelGeometry geometry = geometry(panel);
        SkillPanelGeometry.Controls controls = geometry.controls();
        if (controls == null) {
            return;
        }
        Map<String, List<UUID>> byPanel = pointsOverlays.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        List<UUID> old = byPanel.remove(panelId);
        if (old != null) {
            removeEntities(old);
        }
        World world = panel.base().getWorld();
        if (world == null || !world.equals(player.getWorld())) {
            return;
        }
        // Zero is shown as plainly as any other number: a blank where a
        // figure used to be reads as broken rather than as empty.
        Component content = miniMessage.deserialize(
                plugin.getConfig().getString("skill-panel.points.format",
                        "<color:#b3a577>Skill Points<newline><gradient:#c9a227:#f2e39b><bold><points>"),
                Messages.ph("points", plugin.skillProgress().points(player.getUniqueId())),
                Messages.ph("spent", plugin.skillProgress().spentPoints(player.getUniqueId())),
                Messages.ph("budget", plugin.skillProgress().budget(player.getUniqueId())));
        byPanel.put(panelId, List.of(spawnPointsText(placement(panel), controls, panelId, content, player)));
    }

    private UUID spawnPointsText(Placement placement, SkillPanelGeometry.Controls controls,
                                 String panelId, Component content, Player owner) {
        FileConfiguration config = plugin.getConfig();
        float scale = (float) config.getDouble("skill-panel.points.scale", 1.4);
        Location at = placement.base().clone()
                .add(placement.rightward().clone().multiply(controls.confirmX()))
                .add(0.0, controls.buttonsY(), 0.0)
                .add(placement.facing().clone().multiply(0.30));
        at.setYaw(placement.yaw());
        at.setPitch(0.0F);
        TextDisplay display = placement.world().spawn(at, TextDisplay.class, text -> {
            text.text(content);
            text.setBillboard(Display.Billboard.FIXED);
            text.setAlignment(TextDisplay.TextAlignment.CENTER);
            text.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            text.setShadowed(false);
            text.setSeeThrough(false);
            text.setBackgroundColor(argb(config.getString("skill-panel.points.background", "00000000")));
            text.setBrightness(new Display.Brightness(placement.brightness(), placement.brightness()));
            text.setViewRange((float) config.getDouble("skill-panel.view-range", 2.0));
            text.setPersistent(false);
            text.setInvulnerable(true);
            text.setVisibleByDefault(false);
            tag(text, panelId, "personal-points");
        });
        owner.showEntity(plugin, display);
        return display.getUniqueId();
    }

    /** The node details, shared by the Info area and the standard detail panel. */
    private Component nodeDetailContent(SkillNode node, Set<String> unlocked) {
        return miniMessage.deserialize(
                plugin.getConfig().getString("skill-panel.detail.format",
                        "<gradient:#c9a227:#f2e39b><bold><name></bold></gradient><newline>"
                                + "<color:#c9a227>Cost: <cost> point(s)<newline>"
                                + "<color:#b3a577><description><newline>"
                                + "<color:#c9a227>Level <level>/1"),
                Messages.ph("name", node.name()),
                Messages.ph("cost", Integer.toString(node.cost())),
                Messages.ph("description", node.description()),
                Messages.ph("level", unlocked.contains(node.id()) ? "1" : "0"));
    }

    /** The Info area's text display, anchored where the mockup puts it. */
    private UUID spawnInfoText(Placement placement, SkillPanelGeometry geometry,
                               SkillPanelGeometry.Controls controls, String panelId,
                               Component content, Player owner) {
        FileConfiguration config = plugin.getConfig();
        float scale = controls.infoScale();
        Location at = placement.base().clone()
                .add(placement.rightward().clone().multiply(controls.infoX()))
                .add(0.0, controls.infoY(), 0.0)
                .add(placement.facing().clone().multiply(0.30));
        at.setYaw(placement.yaw());
        at.setPitch(0.0F);
        TextDisplay display = placement.world().spawn(at, TextDisplay.class, text -> {
            text.text(content);
            text.setBillboard(Display.Billboard.FIXED);
            text.setAlignment(TextDisplay.TextAlignment.LEFT);
            text.setLineWidth(controls.infoLineWidth());
            text.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            text.setShadowed(false);
            text.setSeeThrough(false);
            text.setBackgroundColor(argb(config.getString("skill-panel.detail.background", "A0140F05")));
            text.setBrightness(new Display.Brightness(placement.brightness(), placement.brightness()));
            text.setViewRange((float) config.getDouble("skill-panel.view-range", 2.0));
            text.setPersistent(false);
            text.setInvulnerable(true);
            text.setVisibleByDefault(false);
            tag(text, panelId, "personal-info");
        });
        owner.showEntity(plugin, display);
        return display.getUniqueId();
    }

    /** The shared furniture below the tree: just the carousel arrows now. */
    private List<UUID> spawnControls(Placement placement, SkillPanelGeometry geometry,
                                     SkillPanelGeometry.Controls controls, String id) {
        SkillPanelGeometry.PlateStyle plates = geometry.plates();
        FileConfiguration config = plugin.getConfig();
        List<UUID> ids = new ArrayList<>();
        ids.add(spawnText(placement, id, "carousel-arrow-left",
                miniMessage.deserialize(config.getString("difficulty-panel.arrows.left", "<color:#c9a227><bold><")),
                -controls.arrowX(), controls.carouselY(), geometry.zRing(), controls.arrowScale(), (byte) 255, null, null));
        ids.add(spawnText(placement, id, "carousel-arrow-right",
                miniMessage.deserialize(config.getString("difficulty-panel.arrows.right", "<color:#c9a227><bold>>")),
                controls.arrowX(), controls.carouselY(), geometry.zRing(), controls.arrowScale(), (byte) 255, null, null));
        ids.add(spawnHitboxAt(placement, id, "hit-class-left",
                -controls.arrowX(), controls.carouselY(), 1.8, 1.6, geometry.zHitbox()));
        ids.add(spawnHitboxAt(placement, id, "hit-class-right",
                controls.arrowX(), controls.carouselY(), 1.8, 1.6, geometry.zHitbox()));

        // Where Confirm used to stand there is now a points readout, and it
        // is not furniture: a balance belongs to one player, so it is built
        // per viewer by rebuildPointsDisplay alongside the Info area.
        return ids;
    }

    /**
     * The per-player class row: every class name spawned once, positioned by
     * its distance from the viewed one - the difficulty carousel's recipe.
     * Sliding teleports each label to its new spot and lets teleport and
     * transformation interpolation carry the motion.
     */
    private void rebuildCarousel(Player player, String panelId, PlacedSkillPanel panel,
                                 SkillPanelGeometry geometry, boolean slide) {
        SkillPanelGeometry.Controls controls = geometry.controls();
        if (controls == null) {
            return;
        }
        List<String> classIds = plugin.skillTrees().classIds();
        if (classIds.isEmpty()) {
            return;
        }
        int selected = carouselIndexFor(player, panelId, panel, classIds);
        Placement placement = placement(panel);
        Map<String, List<UUID>> byPanel = carouselOverlays.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        List<UUID> existing = byPanel.get(panelId);

        if (slide && existing != null && existing.size() == classIds.size()
                && Bukkit.getEntity(existing.getFirst()) != null) {
            for (int index = 0; index < classIds.size(); index++) {
                if (!(Bukkit.getEntity(existing.get(index)) instanceof TextDisplay label)) {
                    continue;
                }
                double[] pose = carouselPose(controls, index - selected);
                label.teleport(carouselLocation(placement, controls, pose[0], (float) pose[1]));
                label.setInterpolationDelay(0);
                label.setInterpolationDuration(controls.slideTicks());
                label.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f((float) pose[1], (float) pose[1], (float) pose[1]), new Quaternionf()));
                label.setTextOpacity((byte) (int) pose[2]);
            }
            return;
        }

        if (existing != null) {
            removeEntities(existing);
        }
        List<UUID> row = new ArrayList<>();
        for (int index = 0; index < classIds.size(); index++) {
            double[] pose = carouselPose(controls, index - selected);
            Location at = carouselLocation(placement, controls, pose[0], (float) pose[1]);
            float scale = (float) pose[1];
            byte opacity = (byte) (int) pose[2];
            Component name = miniMessage.deserialize(plugin.skillTrees().displayName(classIds.get(index)));
            TextDisplay display = placement.world().spawn(at, TextDisplay.class, text -> {
                text.text(name);
                text.setBillboard(Display.Billboard.FIXED);
                text.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(scale, scale, scale), new Quaternionf()));
                text.setTextOpacity(opacity);
                text.setShadowed(false);
                text.setSeeThrough(false);
                text.setLineWidth(400);
                text.setBackgroundColor(Color.fromARGB(0));
                text.setBrightness(new Display.Brightness(placement.brightness(), placement.brightness()));
                text.setViewRange((float) plugin.getConfig().getDouble("skill-panel.view-range", 2.0));
                text.setTeleportDuration(controls.slideTicks());
                text.setPersistent(false);
                text.setInvulnerable(true);
                text.setVisibleByDefault(false);
                tag(text, panelId, "personal-class");
            });
            player.showEntity(plugin, display);
            row.add(display.getUniqueId());
        }
        byPanel.put(panelId, row);
    }

    /** x offset, scale and opacity for one carousel step from the centre. */
    private static double[] carouselPose(SkillPanelGeometry.Controls controls, int step) {
        int magnitude = Math.abs(step);
        if (magnitude == 0) {
            return new double[]{0.0, controls.centreScale(), 255.0};
        }
        if (magnitude == 1) {
            return new double[]{step * controls.carouselSpacing(), controls.sideScale(), controls.sideOpacity()};
        }
        // Off the edge: shrunk away rather than removed, so it can slide back.
        return new double[]{step * controls.carouselSpacing(), 0.05, 26.0};
    }

    private Location carouselLocation(Placement placement, SkillPanelGeometry.Controls controls,
                                      double x, float scale) {
        Location at = placement.base().clone()
                .add(placement.rightward().clone().multiply(x))
                .add(0.0, controls.carouselY() - GLYPH_CENTRE_PIXELS * scale / 40.0, 0.0)
                .add(placement.facing().clone().multiply(0.30));
        at.setYaw(placement.yaw());
        at.setPitch(0.0F);
        return at;
    }

    // ------------------------------------------------------------------
    //  Test state (phase three replaces this with real progression)
    // ------------------------------------------------------------------

    /**
     * Redraws one player's own overlays on every panel they can see.
     *
     * <p>Called after a write from outside this plugin, so a revoked node
     * goes dark on screen at once rather than on the next reload. Only this
     * player's entities are touched - the tree itself is shared furniture and
     * everyone's progress is a per-viewer overlay on top of it, so two people
     * at one panel never see each other's change.</p>
     */
    public void refreshFor(Player player) {
        for (Map.Entry<String, PlacedSkillPanel> entry : panels.entrySet()) {
            if (!stateOverlays.getOrDefault(player.getUniqueId(), Map.of()).containsKey(entry.getKey())) {
                continue;
            }
            SkillClassTree tree = plugin.skillTrees().tree(viewedClass(player, entry.getKey(), entry.getValue()))
                    .orElse(null);
            if (tree == null) continue;
            rebuildStateOverlay(player, entry.getKey(), entry.getValue(), tree);
            refreshSelectionOverlay(player, entry.getKey(), entry.getValue(), tree);
            rebuildInfoPanel(player, entry.getKey(), entry.getValue(), tree);
            rebuildPointsDisplay(player, entry.getKey(), entry.getValue());
        }
    }

    public TestResult testUnlock(Player player, String nodeId) {
        double radius = Math.max(4.0, plugin.getConfig().getDouble("skill-panel.activation-radius", 30.0));
        String panelId = nearestPanel(player.getLocation(), radius * radius).orElse(null);
        PlacedSkillPanel panel = panelId == null ? null : panels.get(panelId);
        String classId = panel == null ? null : viewedClass(player, panelId, panel);
        SkillClassTree tree = classId == null ? null : plugin.skillTrees().tree(classId).orElse(null);
        if (tree == null) {
            return TestResult.NO_PANEL;
        }
        if (!tree.nodes().containsKey(nodeId)) {
            return TestResult.UNKNOWN_NODE;
        }
        // Deliberately ungated: the point is checking how all three states
        // render, including combinations real progression would forbid.
        testUnlocked.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                .computeIfAbsent(classId, ignored -> new HashSet<>()).add(nodeId);
        rebuildStateOverlay(player, panelId, panel, tree);
        refreshSelectionOverlay(player, panelId, panel, tree);
        return TestResult.UNLOCKED;
    }

    public void testClear(Player player) {
        testUnlocked.remove(player.getUniqueId());
        for (Map.Entry<String, PlacedSkillPanel> entry : panels.entrySet()) {
            SkillClassTree tree = plugin.skillTrees().tree(entry.getValue().classId()).orElse(null);
            if (tree != null && stateOverlays.getOrDefault(player.getUniqueId(), Map.of()).containsKey(entry.getKey())) {
                rebuildStateOverlay(player, entry.getKey(), entry.getValue(), tree);
                refreshSelectionOverlay(player, entry.getKey(), entry.getValue(), tree);
            }
        }
    }

    /** Every node id across every tree, for tab completion. */
    public List<String> allNodeIds() {
        Set<String> ids = new HashSet<>();
        for (String classId : plugin.skillTrees().classIds()) {
            plugin.skillTrees().tree(classId).ifPresent(tree -> ids.addAll(tree.nodes().keySet()));
        }
        return ids.stream().sorted().toList();
    }

    // ------------------------------------------------------------------
    //  Node and line states
    // ------------------------------------------------------------------

    private enum NodeState {
        LOCKED, AVAILABLE, UNLOCKED
    }

    private Set<String> unlockedFor(UUID playerId, String classId) {
        // Real progression first; the test harness remains as a render-check
        // overlay on top of it, deliberately free and ungated.
        Set<String> combined = new HashSet<>(plugin.skillProgress().unlockedNodes(playerId, classId).keySet());
        combined.addAll(testUnlocked.getOrDefault(playerId, Map.of()).getOrDefault(classId, Set.of()));
        return combined;
    }

    private NodeState stateOf(Player player, SkillNode node, Set<String> unlocked) {
        if (unlocked.contains(node.id())) {
            return NodeState.UNLOCKED;
        }
        // any-of nodes are where paths rejoin: one unlocked route suffices.
        boolean reachable = node.anyOf()
                ? node.requires().stream().anyMatch(unlocked::contains)
                : node.requires().stream().allMatch(unlocked::contains);
        // A node whose difficulty gate is not met reads as locked rather than
        // available: offering it and then refusing the click is worse than
        // showing it as out of reach, which is what it is.
        return reachable && plugin.skillProgress().difficultyMet(player, node)
                ? NodeState.AVAILABLE : NodeState.LOCKED;
    }

    // ------------------------------------------------------------------
    //  Rendering: shared furniture
    // ------------------------------------------------------------------

    private void render(String id, PlacedSkillPanel panel) {
        World world = panel.base().getWorld();
        if (world == null || !world.isChunkLoaded(panel.base().getBlockX() >> 4, panel.base().getBlockZ() >> 4)) {
            return;
        }
        SkillClassTree tree = plugin.skillTrees().tree(panel.classId()).orElse(null);
        if (tree == null) {
            plugin.getLogger().warning("Skill panel '" + id + "' refers to class '" + panel.classId()
                    + "', which skills.yml no longer defines. The panel stays empty until it does.");
            clearEntities(id, panel.base());
            return;
        }
        clearEntities(id, panel.base());
        SkillPanelGeometry geometry = geometry(panel);
        Placement placement = placement(panel);
        double rootHeight = geometry.rootHeight();
        double step = geometry.radiusStep();
        List<UUID> ids = new ArrayList<>();

        Map<String, List<UUID>> byNode = new HashMap<>();
        // The shared base draws everything locked; brighter states are each
        // viewer's personal overlay on top of it.
        for (SkillNode node : tree.nodes().values()) {
            for (String requiredId : node.requires()) {
                SkillNode required = tree.nodes().get(requiredId);
                if (required != null) {
                    ids.addAll(spawnEdge(placement, geometry, id, required, node, rootHeight, step,
                            NodeState.LOCKED, false, null));
                }
            }
        }
        for (SkillNode node : tree.nodes().values()) {
            double[] at = point(node, rootHeight, step);
            List<UUID> nodeIds = spawnNodeVisual(placement, geometry, id, node, at, NodeState.LOCKED, null);
            ids.addAll(nodeIds);
            byNode.put(node.id(), nodeIds);
            ids.add(spawnNodeHitbox(placement, geometry, id, node, at));
        }

        for (SkillBranch branch : tree.branches().values()) {
            double[] at = {branch.unitX() * step, rootHeight + branch.unitY() * step};
            ids.add(spawnText(placement, id, "branch-" + branch.id(),
                    miniMessage.deserialize(branch.label()), at[0], at[1], geometry.zNode(),
                    geometry.branchLabelScale(), (byte) 255, null, null));
        }

        if (geometry.controls() == null) {
            double top = tree.nodes().values().stream().mapToDouble(SkillNode::unitY).max().orElse(4.0);
            ids.add(spawnText(placement, id, "heading", miniMessage.deserialize(tree.displayName()),
                    0.0, rootHeight + top * step + geometry.headingHeightExtra(), geometry.zNode(),
                    geometry.headingScale(), (byte) 255, null, null));
        } else {
            // The mockup's lower third: class carousel, then Info and points.
            // The class name lives in the carousel, so there is no heading.
            ids.addAll(spawnControls(placement, geometry, geometry.controls(), id));
        }

        spawned.put(id, ids);
        sharedNodes.put(id, byNode);
        // Re-rendering swept every tagged entity, personal overlays included;
        // the proximity tick rebuilds them for players still in range.
        for (Map<String, List<UUID>> overlays : stateOverlays.values()) {
            overlays.remove(id);
        }
        for (Map<String, List<UUID>> overlays : selectionOverlays.values()) {
            overlays.remove(id);
        }
    }

    // ------------------------------------------------------------------
    //  Rendering: per-player overlays
    // ------------------------------------------------------------------

    private void ensureOverlays(Player player, String panelId, PlacedSkillPanel panel) {
        SkillClassTree tree = plugin.skillTrees().tree(viewedClass(player, panelId, panel)).orElse(null);
        if (tree == null) {
            return;
        }
        SkillPanelGeometry geometry = geometry(panel);
        if (geometry.controls() != null) {
            List<UUID> row = carouselOverlays.getOrDefault(player.getUniqueId(), Map.of()).get(panelId);
            if (row == null || row.isEmpty() || Bukkit.getEntity(row.getFirst()) == null) {
                rebuildCarousel(player, panelId, panel, geometry, false);
            }
            List<UUID> info = infoOverlays.getOrDefault(player.getUniqueId(), Map.of()).get(panelId);
            if (info == null || info.isEmpty() || Bukkit.getEntity(info.getFirst()) == null) {
                rebuildInfoPanel(player, panelId, panel, tree);
            }
        }
        List<UUID> state = stateOverlays.getOrDefault(player.getUniqueId(), Map.of()).get(panelId);
        if (state == null || (!state.isEmpty() && Bukkit.getEntity(state.getFirst()) == null)) {
            rebuildStateOverlay(player, panelId, panel, tree);
        }
        String selected = selections.getOrDefault(player.getUniqueId(), Map.of()).get(panelId);
        List<UUID> selection = selectionOverlays.getOrDefault(player.getUniqueId(), Map.of()).get(panelId);
        if (selected != null && (selection == null || selection.isEmpty()
                || Bukkit.getEntity(selection.getFirst()) == null)) {
            refreshSelectionOverlay(player, panelId, panel, tree);
        }
    }

    /** Everything brighter than locked, drawn just in front of the base. */
    private void rebuildStateOverlay(Player player, String panelId, PlacedSkillPanel panel, SkillClassTree tree) {
        Map<String, List<UUID>> byPanel = stateOverlays.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        List<UUID> old = byPanel.remove(panelId);
        if (old != null) {
            removeEntities(old);
        }
        // Every shared node comes back before the new overlay hides its own.
        showAllSharedNodes(player, panelId);
        World world = panel.base().getWorld();
        if (world == null || !world.equals(player.getWorld())
                || !world.isChunkLoaded(panel.base().getBlockX() >> 4, panel.base().getBlockZ() >> 4)) {
            return;
        }
        SkillPanelGeometry geometry = geometry(panel);
        Placement placement = placement(panel);
        double rootHeight = geometry.rootHeight();
        double step = geometry.radiusStep();
        Set<String> unlocked = unlockedFor(player.getUniqueId(), viewedClass(player, panelId, panel));

        List<UUID> ids = new ArrayList<>();
        for (SkillNode node : tree.nodes().values()) {
            NodeState nodeState = stateOf(player, node, unlocked);
            for (String requiredId : node.requires()) {
                SkillNode required = tree.nodes().get(requiredId);
                if (required == null || !unlocked.contains(requiredId)) {
                    continue; // prerequisite still locked: the base line stands
                }
                NodeState lineState = nodeState == NodeState.UNLOCKED ? NodeState.UNLOCKED : NodeState.AVAILABLE;
                ids.addAll(spawnEdge(placement, geometry, panelId, required, node, rootHeight, step,
                        lineState, true, player));
            }
            if (nodeState != NodeState.LOCKED) {
                ids.addAll(spawnNodeVisual(placement, geometry, panelId, node,
                        point(node, rootHeight, step), nodeState, player));
                setSharedNodeHidden(player, panelId, node.id(), true);
            }
        }
        byPanel.put(panelId, ids);
    }

    /** The ring on the selected node plus the detail panel beside the tree. */
    private void refreshSelectionOverlay(Player player, String panelId, PlacedSkillPanel panel, SkillClassTree tree) {
        Map<String, List<UUID>> byPanel = selectionOverlays.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        List<UUID> old = byPanel.remove(panelId);
        if (old != null) {
            removeEntities(old);
        }
        String nodeId = selections.getOrDefault(player.getUniqueId(), Map.of()).get(panelId);
        SkillNode node = nodeId == null ? null : tree.nodes().get(nodeId);
        World world = panel.base().getWorld();
        if (node == null || world == null || !world.equals(player.getWorld())) {
            if (geometry(panel).controls() != null) {
                rebuildInfoPanel(player, panelId, panel, tree);
            }
            return;
        }
        FileConfiguration config = plugin.getConfig();
        SkillPanelGeometry geometry = geometry(panel);
        Placement placement = placement(panel);
        double[] at = point(node, geometry.rootHeight(), geometry.radiusStep());
        Set<String> unlocked = unlockedFor(player.getUniqueId(), viewedClass(player, panelId, panel));

        List<UUID> ids = new ArrayList<>();
        SkillPanelGeometry.PlateStyle plates = geometry.plates();
        if (plates == null) {
            ids.add(spawnText(placement, panelId, "personal-ring",
                    miniMessage.deserialize(config.getString("skill-panel.nodes.ring-format", "<color:#f2e39b>○")),
                    at[0], at[1], geometry.zRing(), geometry.ringScale(), (byte) 255, player, null));
        } else {
            // A glyph ring would land on top of a solid plate and read as
            // noise. The selection is a larger plate sitting behind the node
            // instead, showing as a bright border all the way around it.
            ids.add(spawnPlate(placement, panelId, "personal-ring", at,
                    plates.selectionWidth(), plates.selectionWidth(), plates.selectionDepth(), plates.zSelection(),
                    material(config.getString("skill-panel.nodes.plates.selection.block"), Material.GOLD_BLOCK),
                    config.getInt("skill-panel.nodes.plates.selection.brightness", 15), player));
        }

        if (geometry.controls() == null) {
            // The standard variant keeps its detail panel beside the tree;
            // the big variant's Info area takes that role instead.
            ids.add(spawnDetail(placement, geometry, panelId, nodeDetailContent(node, unlocked), player));
        }
        byPanel.put(panelId, ids);
        if (geometry.controls() != null) {
            rebuildInfoPanel(player, panelId, panel, tree);
        }
    }

    private void removeOverlays(UUID playerId, String panelId, Player player) {
        Map<String, List<UUID>> carousel = carouselOverlays.get(playerId);
        if (carousel != null) {
            List<UUID> removedRow = carousel.remove(panelId);
            if (removedRow != null) {
                removeEntities(removedRow);
            }
        }
        Map<String, List<UUID>> info = infoOverlays.get(playerId);
        if (info != null) {
            List<UUID> removedInfo = info.remove(panelId);
            if (removedInfo != null) {
                removeEntities(removedInfo);
            }
        }
        Map<String, List<UUID>> state = stateOverlays.get(playerId);
        if (state != null) {
            List<UUID> ids = state.remove(panelId);
            if (ids != null) {
                removeEntities(ids);
                if (player != null) {
                    showAllSharedNodes(player, panelId);
                }
            }
        }
        Map<String, List<UUID>> selection = selectionOverlays.get(playerId);
        if (selection != null) {
            List<UUID> ids = selection.remove(panelId);
            if (ids != null) {
                removeEntities(ids);
            }
        }
        Map<String, List<UUID>> points = pointsOverlays.get(playerId);
        if (points != null) {
            List<UUID> ids = points.remove(panelId);
            if (ids != null) {
                removeEntities(ids);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Spawning
    // ------------------------------------------------------------------

    private SkillPanelGeometry geometry(PlacedSkillPanel panel) {
        return SkillPanelGeometry.of(panel.variant(), plugin.getConfig());
    }

    private Placement placement(PlacedSkillPanel panel) {
        float yaw = panel.base().getYaw()
                + (plugin.getConfig().getBoolean("skill-panel.flip-facing", false) ? 180.0F : 0.0F);
        double radians = Math.toRadians(yaw);
        Vector facing = new Vector(-Math.sin(radians), 0.0, Math.cos(radians));
        Vector rightward = new Vector(facing.getZ(), 0.0, -facing.getX());
        return new Placement(panel.base().getWorld(), panel.base(), yaw, facing, rightward,
                Math.clamp(plugin.getConfig().getInt("skill-panel.brightness", 15), 0, 15));
    }

    /** Grid units to panel-plane blocks: x rightward, y up from the root. */
    private static double[] point(SkillNode node, double rootHeight, double step) {
        return new double[]{node.unitX() * step, rootHeight + node.unitY() * step};
    }

    /**
     * One node, in whichever style the variant uses: a single font glyph, or
     * a raised plate built from a wide base and a smaller, thicker top.
     */
    private List<UUID> spawnNodeVisual(Placement placement, SkillPanelGeometry geometry, String id,
                                       SkillNode node, double[] at, NodeState state, Player owner) {
        SkillPanelGeometry.PlateStyle plates = geometry.plates();
        if (plates == null) {
            return List.of(spawnNode(placement, geometry, id, node, at, state, owner));
        }
        FileConfiguration config = plugin.getConfig();
        String path = "skill-panel.nodes.plates." + state.name().toLowerCase(Locale.ROOT) + ".";
        String prefix = owner == null ? "node-" : "personal-node-";
        // Base first and furthest back: it is the rim showing around the top.
        return List.of(
                spawnPlate(placement, id, prefix + "base-" + node.id(), at,
                        plates.baseWidth(), plates.baseWidth(), plates.baseDepth(), plates.zBase(),
                        material(config.getString(path + "base-block"), defaultPlateBlock(state, true)),
                        config.getInt(path + "base-brightness", defaultPlateBrightness(state, true)), owner),
                spawnPlate(placement, id, prefix + "top-" + node.id(), at,
                        plates.topWidth(), plates.topWidth(), plates.topDepth(), plates.zTop(),
                        material(config.getString(path + "top-block"), defaultPlateBlock(state, false)),
                        config.getInt(path + "top-brightness", defaultPlateBrightness(state, false)), owner));
    }

    /**
     * The rim is a duller relative of the face rather than a different hue, so
     * a plate reads as one object with a border. Unlocked and available share
     * their blocks and differ only in brightness, which is the same ladder the
     * rest of the panel uses: hue separates locked from the rest, lightness
     * separates unlocked from available.
     */
    private static Material defaultPlateBlock(NodeState state, boolean base) {
        return switch (state) {
            case UNLOCKED, AVAILABLE -> base ? Material.RAW_GOLD_BLOCK : Material.GOLD_BLOCK;
            case LOCKED -> base ? Material.DEEPSLATE_TILES : Material.GRAY_CONCRETE;
        };
    }

    private static int defaultPlateBrightness(NodeState state, boolean base) {
        return switch (state) {
            case UNLOCKED -> base ? 11 : 15;
            case AVAILABLE -> base ? 5 : 8;
            case LOCKED -> base ? 3 : 6;
        };
    }

    /** A flat, upright slab centred on a node position, facing the viewer. */
    private UUID spawnPlate(Placement placement, String id, String role, double[] at,
                            double width, double height, double depth, double z,
                            Material material, int brightness, Player owner) {
        Location location = placement.base().clone()
                .add(placement.rightward().clone().multiply(at[0]))
                .add(0.0, at[1], 0.0)
                .add(placement.facing().clone().multiply(z));
        location.setYaw(placement.yaw());
        location.setPitch(0.0F);
        int light = Math.clamp(brightness, 0, 15);
        float viewRange = (float) plugin.getConfig().getDouble("skill-panel.view-range", 2.0);
        BlockDisplay display = placement.world().spawn(location, BlockDisplay.class, block -> {
            block.setBlock(material.createBlockData());
            // A block display fills the unit cube from its corner; the
            // translation pulls it back by half its size so the node position
            // is its centre.
            block.setTransformation(new Transformation(
                    new Vector3f((float) (-width / 2.0), (float) (-height / 2.0), (float) (-depth / 2.0)),
                    new Quaternionf(),
                    new Vector3f((float) width, (float) height, (float) depth),
                    new Quaternionf()));
            block.setBrightness(new Display.Brightness(light, light));
            block.setViewRange(viewRange);
            block.setPersistent(false);
            block.setInvulnerable(true);
            if (owner != null) {
                block.setVisibleByDefault(false);
            }
            tag(block, id, role);
        });
        if (owner != null) {
            owner.showEntity(plugin, display);
        }
        return display.getUniqueId();
    }

    /** Hides or restores one shared node for a single player. */
    private void setSharedNodeHidden(Player player, String panelId, String nodeId, boolean hidden) {
        for (UUID id : sharedNodes.getOrDefault(panelId, Map.of()).getOrDefault(nodeId, List.of())) {
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

    private void showAllSharedNodes(Player player, String panelId) {
        for (String nodeId : sharedNodes.getOrDefault(panelId, Map.of()).keySet()) {
            setSharedNodeHidden(player, panelId, nodeId, false);
        }
    }

    private UUID spawnNode(Placement placement, SkillPanelGeometry geometry, String id, SkillNode node,
                           double[] at, NodeState state, Player owner) {
        FileConfiguration config = plugin.getConfig();
        String path = "skill-panel.nodes." + state.name().toLowerCase(Locale.ROOT);
        String fallback = switch (state) {
            case UNLOCKED -> "<color:#f2e39b>●";
            case AVAILABLE -> "<color:#c9a227>●";
            case LOCKED -> "<color:#6b6353>●";
        };
        int fallbackOpacity = switch (state) {
            case UNLOCKED -> 255;
            case AVAILABLE -> 230;
            case LOCKED -> 140;
        };
        byte opacity = (byte) Math.clamp(config.getInt(path + "-opacity", fallbackOpacity), 26, 255);
        double z = owner == null ? geometry.zNode() : geometry.zOverlayNode();
        return spawnText(placement, id, owner == null ? "node-" + node.id() : "personal-node-" + node.id(),
                miniMessage.deserialize(config.getString(path + "-format", fallback)),
                at[0], at[1], z, geometry.nodeScale(), opacity, owner, null);
    }

    /**
     * The click surface over one node, comfortably larger than the dot it
     * covers. Overlap with a neighbour is harmless: the click is re-resolved
     * to the node nearest the player's aim anyway.
     */
    private UUID spawnNodeHitbox(Placement placement, SkillPanelGeometry geometry, String id,
                                 SkillNode node, double[] at) {
        return spawnHitboxAt(placement, id, "hit-node-" + node.id(), at[0], at[1],
                geometry.hitboxWidth(), geometry.hitboxHeight(), geometry.zHitbox());
    }

    /** An invisible clickable box centred on a panel-plane position. */
    private UUID spawnHitboxAt(Placement placement, String id, String role, double x, double y,
                               double width, double height, double z) {
        Location location = placement.base().clone()
                .add(placement.rightward().clone().multiply(x))
                .add(0.0, y - height / 2.0, 0.0)
                .add(placement.facing().clone().multiply(z));
        location.setYaw(placement.yaw());
        location.setPitch(0.0F);
        Interaction hitbox = placement.world().spawn(location, Interaction.class, interaction -> {
            interaction.setInteractionWidth((float) width);
            interaction.setInteractionHeight((float) height);
            interaction.setResponsive(true);
            interaction.setPersistent(false);
            interaction.setInvulnerable(true);
            tag(interaction, id, role);
        });
        return hitbox.getUniqueId();
    }

    /**
     * One connection bar. Overlay bars are slightly thicker than the base bar
     * they cover, so their faces sit outside it instead of z-fighting.
     */
    /**
     * One connection, possibly bent: the required node, any route corner
     * points, then the dependent node, drawn as one bar per leg. Only the
     * outermost legs are trimmed back to a plate rim - corners meet fully.
     */
    private List<UUID> spawnEdge(Placement placement, SkillPanelGeometry geometry, String id,
                                 SkillNode from, SkillNode to, double rootHeight, double step,
                                 NodeState state, boolean overlay, Player owner) {
        List<double[]> points = new ArrayList<>();
        points.add(point(from, rootHeight, step));
        for (double[] corner : to.routes().getOrDefault(from.id(), List.of())) {
            points.add(new double[]{corner[0] * step, rootHeight + corner[1] * step});
        }
        points.add(point(to, rootHeight, step));
        List<UUID> ids = new ArrayList<>();
        for (int leg = 0; leg < points.size() - 1; leg++) {
            ids.add(spawnLine(placement, geometry, id, points.get(leg), points.get(leg + 1), state, overlay, owner,
                    leg == 0, leg == points.size() - 2));
        }
        return ids;
    }

    private UUID spawnLine(Placement placement, SkillPanelGeometry geometry, String id, double[] from, double[] to,
                           NodeState state, boolean overlay, Player owner, boolean trimStart, boolean trimEnd) {
        FileConfiguration config = plugin.getConfig();
        String path = "skill-panel.lines." + state.name().toLowerCase(Locale.ROOT);
        Material fallback = switch (state) {
            case UNLOCKED -> Material.GOLD_BLOCK;
            case AVAILABLE -> Material.SMOOTH_QUARTZ;
            case LOCKED -> Material.GRAY_CONCRETE;
        };
        int fallbackBrightness = switch (state) {
            case UNLOCKED -> 15;
            case AVAILABLE -> 11;
            case LOCKED -> 5;
        };
        Material material = material(config.getString(path + ".block"), fallback);
        int brightness = Math.clamp(config.getInt(path + ".brightness", fallbackBrightness), 0, 15);

        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double length = Math.hypot(dx, dy);
        SkillPanelGeometry.PlateStyle plates = geometry.plates();
        if (plates != null && length > 0.0 && (trimStart || trimEnd)) {
            // Plates stand in front of the lines, so a bar reaching a plate
            // centre would vanish under it. Only plate ends are trimmed; a
            // route corner is not a plate and meets its neighbour fully.
            double unitX = dx / length;
            double unitY = dy / length;
            double inset = Math.min(plates.edgeInset(unitX, unitY), length / 2.0 - 0.02);
            if (inset > 0.0) {
                if (trimStart) {
                    from = new double[]{from[0] + unitX * inset, from[1] + unitY * inset};
                }
                if (trimEnd) {
                    to = new double[]{to[0] - unitX * inset, to[1] - unitY * inset};
                }
                dx = to[0] - from[0];
                dy = to[1] - from[1];
                length = Math.hypot(dx, dy);
            }
        }
        // Fixed after any trimming, so the lambda below can close over it.
        final double barLength = length;
        double angle = Math.atan2(dy, dx) * (config.getBoolean("skill-panel.lines.mirror", false) ? -1.0 : 1.0);
        float thickness = (float) (geometry.lineThickness() * (overlay ? geometry.overlayThicknessFactor() : 1.0));
        float depth = (float) geometry.lineDepth();
        double z = overlay ? geometry.zOverlayLine() : geometry.zBaseLine();

        Location at = placement.base().clone()
                .add(placement.rightward().clone().multiply((from[0] + to[0]) / 2.0))
                .add(0.0, (from[1] + to[1]) / 2.0, 0.0)
                .add(placement.facing().clone().multiply(z));
        at.setYaw(placement.yaw());
        at.setPitch(0.0F);
        Quaternionf rotation = new Quaternionf().rotateZ((float) angle);
        Vector3f pivot = rotation.transform(new Vector3f((float) (barLength / 2.0), thickness / 2.0F, depth / 2.0F));
        float viewRange = (float) config.getDouble("skill-panel.view-range", 2.0);
        BlockDisplay display = placement.world().spawn(at, BlockDisplay.class, block -> {
            block.setBlock(material.createBlockData());
            block.setTransformation(new Transformation(new Vector3f(-pivot.x, -pivot.y, -pivot.z), rotation,
                    new Vector3f((float) barLength, thickness, depth), new Quaternionf()));
            block.setBrightness(new Display.Brightness(brightness, brightness));
            block.setViewRange(viewRange);
            block.setPersistent(false);
            block.setInvulnerable(true);
            if (owner != null) {
                block.setVisibleByDefault(false);
            }
            tag(block, id, owner == null ? "line" : "personal-line");
        });
        if (owner != null) {
            owner.showEntity(plugin, display);
        }
        return display.getUniqueId();
    }

    /** The detail panel: name, cost, description and level beside the tree. */
    private UUID spawnDetail(Placement placement, SkillPanelGeometry geometry, String panelId,
                             Component content, Player owner) {
        return spawnPanelText(placement, geometry, panelId, content, owner, geometry.detailX(), "personal-detail");
    }

    /** A wrapped, backed text panel beside the tree, shown to one player. */
    private UUID spawnPanelText(Placement placement, SkillPanelGeometry geometry, String panelId,
                                Component content, Player owner, double x, String role) {
        FileConfiguration config = plugin.getConfig();
        float scale = geometry.detailScale();
        Location at = placement.base().clone()
                .add(placement.rightward().clone().multiply(x))
                .add(0.0, geometry.detailHeight(), 0.0)
                .add(placement.facing().clone().multiply(geometry.zNode()));
        at.setYaw(placement.yaw());
        at.setPitch(0.0F);
        TextDisplay display = placement.world().spawn(at, TextDisplay.class, text -> {
            text.text(content);
            text.setBillboard(Display.Billboard.FIXED);
            text.setAlignment(TextDisplay.TextAlignment.LEFT);
            text.setLineWidth(geometry.detailLineWidth());
            text.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            text.setShadowed(false);
            text.setSeeThrough(false);
            text.setBackgroundColor(argb(config.getString("skill-panel.detail.background", "A0140F05")));
            text.setBrightness(new Display.Brightness(placement.brightness(), placement.brightness()));
            text.setViewRange((float) config.getDouble("skill-panel.view-range", 2.0));
            text.setPersistent(false);
            text.setInvulnerable(true);
            text.setVisibleByDefault(false);
            tag(text, panelId, role);
        });
        owner.showEntity(plugin, display);
        return display.getUniqueId();
    }

    private UUID spawnText(Placement placement, String id, String role, Component content,
                           double x, double y, double z, float scale, byte opacity, Player owner, Color background) {
        Location at = placement.base().clone()
                .add(placement.rightward().clone().multiply(x))
                // The same half-line correction the difficulty panel needed:
                // a text display draws its line upwards from its position.
                .add(0.0, y - GLYPH_CENTRE_PIXELS * scale / 40.0, 0.0)
                .add(placement.facing().clone().multiply(z));
        at.setYaw(placement.yaw());
        at.setPitch(0.0F);
        TextDisplay display = placement.world().spawn(at, TextDisplay.class, text -> {
            text.text(content);
            text.setBillboard(Display.Billboard.FIXED);
            text.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            text.setTextOpacity(opacity);
            text.setShadowed(false);
            text.setSeeThrough(false);
            text.setLineWidth(400);
            text.setBackgroundColor(background == null ? Color.fromARGB(0) : background);
            text.setBrightness(new Display.Brightness(placement.brightness(), placement.brightness()));
            text.setViewRange((float) plugin.getConfig().getDouble("skill-panel.view-range", 2.0));
            text.setPersistent(false);
            text.setInvulnerable(true);
            if (owner != null) {
                text.setVisibleByDefault(false);
            }
            tag(text, id, role);
        });
        if (owner != null) {
            owner.showEntity(plugin, display);
        }
        return display.getUniqueId();
    }

    private void playSound(Player player, String key) {
        String raw = plugin.getConfig().getString("skill-panel.sounds." + key, "UI_BUTTON_CLICK");
        Sound sound = raw == null || raw.isBlank() ? null
                : Registry.SOUNDS.get(NamespacedKey.minecraft(raw.toLowerCase(Locale.ROOT).replace('_', '.')));
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 0.6F, 1.3F);
        }
    }

    private void tag(Entity entity, String panelId, String role) {
        entity.getPersistentDataContainer().set(plugin.skillPanelIdKey(), PersistentDataType.STRING, panelId);
        entity.getPersistentDataContainer().set(plugin.skillPanelRoleKey(), PersistentDataType.STRING, role);
    }

    private void clearEntities(String id, Location base) {
        List<UUID> ids = spawned.remove(id);
        if (ids != null) {
            removeEntities(ids);
        }
        World world = base.getWorld();
        if (world == null) {
            return;
        }
        double reach = Math.max(8.0, plugin.getConfig().getDouble("skill-panel.cleanup-radius", 14.0));
        for (int chunkX = (int) (base.getX() - reach) >> 4; chunkX <= (int) (base.getX() + reach) >> 4; chunkX++) {
            for (int chunkZ = (int) (base.getZ() - reach) >> 4; chunkZ <= (int) (base.getZ() + reach) >> 4; chunkZ++) {
                world.getChunkAt(chunkX, chunkZ);
            }
        }
        for (Entity entity : world.getNearbyEntities(base, reach, reach, reach)) {
            String entityPanel = panelId(entity);
            if (entityPanel != null && (entityPanel.equals(id) || !panels.containsKey(entityPanel))) {
                entity.remove();
            }
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

    private String panelId(Entity entity) {
        return entity.getPersistentDataContainer().get(plugin.skillPanelIdKey(), PersistentDataType.STRING);
    }

    private void save() {
        YamlConfiguration storage = new YamlConfiguration();
        for (Map.Entry<String, PlacedSkillPanel> entry : panels.entrySet()) {
            String path = "panels." + entry.getKey();
            Location base = entry.getValue().base();
            storage.set(path + ".world", base.getWorld().getName());
            storage.set(path + ".x", base.getX());
            storage.set(path + ".y", base.getY());
            storage.set(path + ".z", base.getZ());
            storage.set(path + ".yaw", base.getYaw());
            storage.set(path + ".class", entry.getValue().classId());
            storage.set(path + ".variant", entry.getValue().variant().configName());
        }
        try {
            storage.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save " + STORAGE + ": " + exception.getMessage());
        }
    }

    private static Material material(String raw, Material fallback) {
        Material material = raw == null ? null : Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
        return material == null || !material.isBlock() ? fallback : material;
    }

    private static Color argb(String raw) {
        try {
            String hex = raw == null ? "" : raw.replace("#", "").trim();
            long value = Long.parseLong(hex, 16);
            return hex.length() > 6 ? Color.fromARGB((int) value) : Color.fromRGB((int) value);
        } catch (NumberFormatException ignored) {
            return Color.fromARGB(0xA0140F05);
        }
    }

    // ------------------------------------------------------------------

    private record PlacedSkillPanel(Location base, String classId, SkillPanelGeometry.Variant variant) {
    }

    private record Placement(World world, Location base, float yaw, Vector facing, Vector rightward, int brightness) {
    }

    public record PanelInfo(String id, String classId, String variant, Location location) {
    }

    public record RemovalReport(int count, List<Location> locations) {
    }

    public enum TestResult {
        UNLOCKED, NO_PANEL, UNKNOWN_NODE
    }
}
