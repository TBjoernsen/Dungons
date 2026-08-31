package nl.riddernix.dungeonforge.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.build.BoxBuilder;
import nl.riddernix.dungeonforge.build.BoxSpec;
import nl.riddernix.dungeonforge.fx.AnimationPreview;
import nl.riddernix.dungeonforge.generation.DungeonLayout;
import nl.riddernix.dungeonforge.generation.DungeonLayoutBuilder;
import nl.riddernix.dungeonforge.generation.DungeonLayoutGenerator;
import nl.riddernix.dungeonforge.generation.GenerationStyle;
import nl.riddernix.dungeonforge.generation.BuildOperation;
import nl.riddernix.dungeonforge.party.DungeonParty;
import nl.riddernix.dungeonforge.party.PartyManager;
import nl.riddernix.dungeonforge.room.DungeonMarkerScanner;
import nl.riddernix.dungeonforge.room.DungeonMarkerDefinitions;
import nl.riddernix.dungeonforge.room.DungeonInstance;
import nl.riddernix.dungeonforge.room.NormalRoomLibrary;
import nl.riddernix.dungeonforge.room.NormalRoomShape;
import nl.riddernix.dungeonforge.room.CorridorLibrary;
import nl.riddernix.dungeonforge.skills.SkillPanelGeometry;
import nl.riddernix.dungeonforge.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

/**
 * Every /dungeon subcommand.
 *
 * <p>Adding one means touching three places: the switch in
 * {@link #onCommand}, the list in {@link #onTabComplete}, and the usage line
 * in config.yml.</p>
 */
public final class DungeonCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS =
            List.of("test", "generate", "compare", "rooms", "corridors", "models", "animate", "panel", "skills", "api", "party", "start", "settings", "npc", "goals", "markers", "summon", "door", "room", "tp", "leave", "delete", "list", "reload");

    private final DungeonForgePlugin plugin;

    /** Players who currently have a build running. */
    private final Set<UUID> building = new HashSet<>();

    public DungeonCommand(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("dungeonforge.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            Player player = asPlayer(sender);
            if (player != null) {
                // The chest menu is retired: the difficulty panel standing in
                // the world replaced it, so this points at the nearest one.
                plugin.panels().sendLocator(player);
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "test" -> handleTest(sender);
            case "generate" -> handleGenerate(sender, args);
            case "compare" -> handleCompare(sender, args);
            case "rooms" -> handleRooms(sender);
            case "corridors" -> handleCorridors(sender);
            case "models" -> handleModels(sender);
            case "animate" -> handleAnimate(sender, args);
            case "panel" -> handlePanel(sender, args);
            case "skills" -> handleSkills(sender, args);
            case "api" -> handleApi(sender, args);
            case "party" -> handleParty(sender, args);
            case "start" -> handleStart(sender, args);
            case "settings" -> handleSettings(sender);
            case "npc" -> handleNpc(sender, args);
            case "goals" -> handleGoals(sender);
            case "markers" -> handleMarkers(sender, args);
            case "summon" -> handleSummon(sender, args);
            case "door" -> handleDoor(sender, args);
            case "room" -> handleRoom(sender, args);
            case "tp" -> handleTeleport(sender);
            case "leave" -> handleLeave(sender);
            case "delete" -> handleDelete(sender);
            case "list" -> handleList(sender);
            case "reload" -> handleReload(sender);
            default -> plugin.messages().send(sender, "usage");
        }
        return true;
    }

    // ------------------------------------------------------------------

    private void handleTest(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (!building.add(player.getUniqueId())) {
            plugin.messages().send(player, "already-building");
            return;
        }

        String worldName = plugin.worlds().worldNameFor(player);
        plugin.messages().send(player, "creating", Messages.ph("world", worldName));

        World world = plugin.worlds().createFresh(worldName);
        if (world == null) {
            building.remove(player.getUniqueId());
            plugin.messages().send(player, "delete-failed");
            return;
        }

        buildBox(player, world);
    }

    private void buildBox(Player player, World world) {
        BoxSpec spec = BoxSpec.fromConfig(plugin.getConfig(), plugin.getLogger());
        BoxBuilder.start(plugin, world, spec,
                percent -> sendProgress(player, percent),
                () -> {
                    building.remove(player.getUniqueId());
                    long blocks = spec.hollow() ? shellBlockCount(spec.size()) : spec.volume();
                    finish(player, world, blocks, 0L);
                });
    }

    private void handleGenerate(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "generate-usage");
            return;
        }
        int difficulty;
        try {
            difficulty = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            plugin.messages().send(player, "invalid-difficulty");
            return;
        }
        if (difficulty < 1 || difficulty > 9) {
            plugin.messages().send(player, "invalid-difficulty");
            return;
        }
        Integer amount = args.length >= 3 ? parseAmount(player, args[2]) : 1;
        if (amount == null) {
            return;
        }
        Long baseSeed = args.length >= 4 ? parseSeed(player, args[3]) : ThreadLocalRandom.current().nextLong();
        if (baseSeed == null) {
            return;
        }

        try {
            DungeonLayoutGenerator generator = new DungeonLayoutGenerator(plugin.getConfig(), plugin.normalRooms());
            List<DungeonLayout> layouts = new ArrayList<>();
            if (amount == 1) {
                layouts.add(generator.generate(difficulty, baseSeed));
            } else {
                Random seeds = new Random(baseSeed);
                for (int index = 0; index < amount; index++) {
                    layouts.add(generator.generate(difficulty, seeds.nextLong()));
                }
                layouts = arrangeInGrid(layouts,
                        Math.max(32, plugin.getConfig().getInt("generation.multi.padding", 96)));
            }

            List<DungeonLayout> finalLayouts = layouts;
            startLayouts(player, layouts, () -> {
                if (amount == 1) {
                    plugin.messages().send(player, "generation-started",
                            Messages.ph("difficulty", difficulty), Messages.ph("seed", finalLayouts.getFirst().seed()));
                    return;
                }
                plugin.messages().send(player, "generation-batch-started",
                        Messages.ph("difficulty", difficulty), Messages.ph("amount", amount));
                for (int index = 0; index < finalLayouts.size(); index++) {
                    DungeonLayout layout = finalLayouts.get(index);
                    plugin.messages().send(player, "generation-batch-entry",
                            Messages.ph("index", index + 1),
                            Messages.ph("seed", layout.seed()),
                            Messages.ph("x", layout.spawnX()),
                            Messages.ph("y", layout.spawnY()),
                            Messages.ph("z", layout.spawnZ()));
                }
            });
        } catch (DungeonLayoutGenerator.GenerationException ex) {
            plugin.getLogger().warning("Dungeon generation failed: " + ex.getMessage());
            plugin.messages().send(player, "generation-failed");
        }
    }

    private void handleCompare(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Long seed = args.length >= 2 ? parseSeed(player, args[1]) : ThreadLocalRandom.current().nextLong();
        if (seed == null) {
            return;
        }

        try {
            DungeonLayoutGenerator generator = new DungeonLayoutGenerator(plugin.getConfig(), plugin.normalRooms());
            List<DungeonLayout> layouts = new ArrayList<>();
            int cellWidth = 0;
            int cellDepth = 0;
            for (int difficulty = 1; difficulty <= 9; difficulty++) {
                DungeonLayout layout = generator.generate(difficulty, seed);
                layouts.add(layout);
                cellWidth = Math.max(cellWidth, layout.bounds().sizeX());
                cellDepth = Math.max(cellDepth, layout.bounds().sizeZ());
            }
            int padding = Math.max(16, plugin.getConfig().getInt("generation.comparison.padding", 48));
            cellWidth += padding;
            cellDepth += padding;

            List<DungeonLayout> translated = new ArrayList<>();
            for (int index = 0; index < layouts.size(); index++) {
                DungeonLayout layout = layouts.get(index);
                int column = index % 3;
                int row = index / 3;
                translated.add(layout.translate(column * cellWidth - layout.bounds().minX(), 0,
                        row * cellDepth - layout.bounds().minZ()));
            }
            startLayouts(player, translated,
                    () -> plugin.messages().send(player, "comparison-started", Messages.ph("seed", seed)));
        } catch (DungeonLayoutGenerator.GenerationException ex) {
            plugin.getLogger().warning("Dungeon comparison generation failed: " + ex.getMessage());
            plugin.messages().send(player, "generation-failed");
        }
    }

    private void handleStart(CommandSender sender, String[] args) {
        Player leader = asPlayer(sender);
        if (leader == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(leader, "start-usage");
            return;
        }
        int difficulty;
        try {
            difficulty = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            plugin.messages().send(leader, "invalid-difficulty");
            return;
        }
        if (difficulty < 1 || difficulty > 9) {
            plugin.messages().send(leader, "invalid-difficulty");
            return;
        }
        Long seed = args.length >= 3 ? parseSeed(leader, args[2]) : ThreadLocalRandom.current().nextLong();
        if (seed == null) {
            return;
        }

        requestPartyStart(leader, difficulty, seed, true);
    }

    /** Starts a randomly seeded party dungeon from the difficulty selection menu. */
    public void startFromMenu(Player leader, int difficulty) {
        requestPartyStart(leader, difficulty, ThreadLocalRandom.current().nextLong(), false);
    }

    /**
     * Starts a run from the in-world difficulty panel. Unlike the old chest
     * menu there are no item tooltips to explain a refusal, so failures are
     * reported in chat.
     */
    public void startFromPanel(Player leader, int difficulty) {
        requestPartyStart(leader, difficulty, ThreadLocalRandom.current().nextLong(), true);
    }

    /** Returns whether a player can press the start button right now. */
    public StartStatus startStatus(Player player) {
        if (plugin.completions().isCleaningUp(player.getUniqueId())) {
            return StartStatus.INSTANCE_ACTIVE;
        }
        if (building.contains(player.getUniqueId())) {
            return StartStatus.BUILDING;
        }
        DungeonParty party = plugin.parties().partyOf(player.getUniqueId()).orElse(null);
        if (party == null) {
            return StartStatus.READY;
        }
        if (!party.isLeader(player.getUniqueId())) {
            return StartStatus.NOT_LEADER;
        }
        return plugin.parties().hasInstance(party) ? StartStatus.INSTANCE_ACTIVE : StartStatus.READY;
    }

    private void requestPartyStart(Player leader, int difficulty, long seed, boolean reportFailure) {
        StartStatus status = startStatus(leader);
        if (status != StartStatus.READY) {
            if (reportFailure) {
                switch (status) {
                    case NOT_LEADER -> plugin.messages().send(leader, "party-not-leader");
                    case INSTANCE_ACTIVE -> plugin.messages().send(leader, "party-instance-active");
                    case BUILDING -> plugin.messages().send(leader, "already-building");
                    case READY -> throw new IllegalStateException("Ready status was handled above.");
                }
            }
            return;
        }

        DungeonParty party = plugin.parties().partyForLeader(leader.getUniqueId());
        // The one cancellable point in the API, and deliberately the earliest:
        // no world, no layout and no mobs exist yet, so refusing here costs
        // nothing and cannot leave anything half-built.
        if (!plugin.events().fireStart(plugin.snapshots().pending(plugin.parties().worldNameFor(party), difficulty,
                seed, List.copyOf(party.members())), leader)) {
            plugin.messages().send(leader, "start-refused");
            return;
        }
        try {
            DungeonLayout layout = new DungeonLayoutGenerator(plugin.getConfig(), plugin.normalRooms()).generate(difficulty, seed);
            startPartyDungeon(leader, party, layout, difficulty);
        } catch (DungeonLayoutGenerator.GenerationException ex) {
            plugin.getLogger().warning("Party dungeon generation failed: " + ex.getMessage());
            plugin.messages().send(leader, "generation-failed");
        }
    }

    private void handleNpc(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (!player.hasPermission("dungeonforge.admin")) {
            plugin.messages().send(player, "no-settings-permission");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "npc-usage");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "spawn" -> plugin.dungeonLords().spawn(player.getLocation()).ifPresentOrElse(
                    ignored -> plugin.messages().send(player, "npc-spawned"),
                    () -> plugin.messages().send(player, "npc-spawn-failed"));
            case "remove" -> {
                if (plugin.dungeonLords().removeNearest(player.getLocation())) {
                    plugin.messages().send(player, "npc-removed");
                } else {
                    plugin.messages().send(player, "npc-none-nearby");
                }
            }
            case "removeall" -> {
                var report = plugin.dungeonLords().removeAll();
                String locations = report.locations().stream().map(location -> location.getWorld().getName() + " "
                        + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ())
                        .reduce((left, right) -> left + " | " + right).orElse("none");
                plugin.messages().send(player, "npc-removed-all", Messages.ph("count", report.count()), Messages.ph("locations", locations));
            }
            default -> plugin.messages().send(player, "npc-usage");
        }
    }

    private void handleSettings(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (!player.hasPermission("dungeonforge.admin")) {
            plugin.messages().send(player, "no-admin-permission");
            return;
        }
        plugin.settings().openHome(player);
    }

    private void handleGoals(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) return;
        if (!player.hasPermission("dungeonforge.admin")) { plugin.messages().send(player, "no-admin-permission"); return; }
        org.bukkit.entity.Entity target = player.getTargetEntity(24);
        if (target == null) { plugin.messages().send(player, "goals-no-target"); return; }
        List<String> goals = plugin.mobs().goalNames(target);
        plugin.messages().send(player, "goals-header", Messages.ph("entity", target.getType().name()));
        if (goals.isEmpty()) { plugin.messages().send(player, "goals-none"); return; }
        for (String goal : goals) plugin.messages().send(player, "goals-entry", Messages.ph("goal", goal));
        for (String diagnostic : plugin.mobs().diagnostics(target)) plugin.messages().send(player, "goals-entry", Messages.ph("goal", diagnostic));
    }

    private void handleMarkers(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) return;
        if (!player.hasPermission("dungeonforge.admin")) {
            plugin.messages().send(player, "no-admin-permission");
            return;
        }
        List<DungeonMarkerDefinitions.Definition> markers = plugin.mobs().markerDefinitions();
        if (args.length >= 2 && args[1].equalsIgnoreCase("give")) {
            if (args.length != 3) {
                plugin.messages().send(player, "markers-usage");
                return;
            }
            DungeonMarkerDefinitions.Definition marker = markers.stream()
                    .filter(definition -> definition.category().equalsIgnoreCase(args[2]))
                    .findFirst().orElse(null);
            if (marker == null) {
                plugin.messages().send(player, "marker-unknown");
                return;
            }
            giveMarker(player, marker.material());
            plugin.messages().send(player, "marker-given", Messages.ph("block", marker.material().name()),
                    Messages.ph("category", marker.category()));
            return;
        }
        if (args.length != 1) {
            plugin.messages().send(player, "markers-usage");
            return;
        }
        if (markers.isEmpty()) {
            plugin.messages().send(player, "markers-empty");
            return;
        }
        plugin.messages().send(player, "markers-header");
        for (DungeonMarkerDefinitions.Definition marker : markers) {
            Component entry = plugin.messages().get("markers-entry",
                    Messages.ph("block", marker.material().name()),
                    Messages.ph("category", marker.category()),
                    Messages.ph("description", marker.description()))
                    .clickEvent(ClickEvent.runCommand("/dungeon markers give " + marker.category()))
                    .hoverEvent(HoverEvent.showText(plugin.messages().bare("markers-hover",
                            Messages.ph("block", marker.material().name()))));
            player.sendMessage(entry);
        }
    }

    private static void giveMarker(Player player, Material material) {
        for (ItemStack remaining : player.getInventory().addItem(new ItemStack(material)).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), remaining);
        }
    }

    private void handleSummon(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) return;
        if (!player.hasPermission("dungeonforge.admin")) {
            plugin.messages().send(player, "no-admin-permission");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("clear")) {
            int removed = plugin.mobs().removeTestingMobs();
            plugin.messages().send(player, "summon-cleared", Messages.ph("count", removed));
            return;
        }
        if (args.length < 3 || args.length > 4) {
            plugin.messages().send(player, "summon-usage");
            return;
        }
        int difficulty;
        try {
            difficulty = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            plugin.messages().send(player, "invalid-difficulty");
            return;
        }
        String theme = args.length == 4 ? args[3] : null;
        var result = plugin.mobs().summonTestingGroup(player.getLocation(), args[1], difficulty, theme);
        switch (result.status()) {
            case SUCCESS -> plugin.messages().send(player, "summon-success", Messages.ph("count", result.count()),
                    Messages.ph("category", args[1].toLowerCase(Locale.ROOT)), Messages.ph("difficulty", difficulty),
                    Messages.ph("theme", result.theme()));
            case INVALID_CATEGORY -> plugin.messages().send(player, "summon-invalid-category");
            case INVALID_DIFFICULTY -> plugin.messages().send(player, "invalid-difficulty");
            case INVALID_THEME -> plugin.messages().send(player, "summon-invalid-theme", Messages.ph("theme", result.theme()));
            case DISABLED_CATEGORY -> plugin.messages().send(player, "summon-disabled-category",
                    Messages.ph("category", args[1].toLowerCase(Locale.ROOT)), Messages.ph("difficulty", difficulty));
            case NO_CLEARANCE -> plugin.messages().send(player, "summon-no-clearance");
        }
    }

    /** The stuck-run escape hatch: forces the sealed door of the dungeon you stand in. */
    private void handleDoor(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) return;
        if (!player.hasPermission("dungeonforge.admin")) {
            plugin.messages().send(player, "no-admin-permission");
            return;
        }
        if (args.length != 2 || !args[1].equalsIgnoreCase("open")) {
            plugin.messages().send(player, "door-usage");
            return;
        }
        DungeonInstance dungeon = plugin.rooms().dungeon(player.getWorld()).orElse(null);
        if (dungeon == null) {
            plugin.messages().send(player, "not-in-dungeon");
            return;
        }
        switch (plugin.doors().forceOpen(dungeon)) {
            case OPENED -> plugin.messages().send(player, "door-opened-force");
            case ALREADY_OPEN -> plugin.messages().send(player, "door-already-open");
            case NO_DOOR -> plugin.messages().send(player, "door-none");
        }
    }

    /** Opens the sealed room gate the sender stands in; the gate stays open for the run. */
    private void handleRoom(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) return;
        if (!player.hasPermission("dungeonforge.admin")) {
            plugin.messages().send(player, "no-admin-permission");
            return;
        }
        if (args.length != 2 || !args[1].equalsIgnoreCase("open")) {
            plugin.messages().send(player, "room-usage");
            return;
        }
        DungeonInstance dungeon = plugin.rooms().dungeon(player.getWorld()).orElse(null);
        if (dungeon == null) {
            plugin.messages().send(player, "not-in-dungeon");
            return;
        }
        nl.riddernix.dungeonforge.room.DungeonRoom room = plugin.rooms().room(player).orElse(null);
        if (room == null) {
            plugin.messages().send(player, "gate-none");
            return;
        }
        switch (plugin.gates().forceOpen(dungeon, room)) {
            case OPENED -> plugin.messages().send(player, "gate-forced");
            case NONE -> plugin.messages().send(player, "gate-none");
        }
    }

    private void startPartyDungeon(Player leader, DungeonParty party, DungeonLayout layout, int difficulty) {
        NormalRoomLibrary.RoomPlan roomPlan = plugin.normalRooms().plan(layout);
        if (roomPlan.hasRequiredPrefabFailures()) {
            plugin.getLogger().severe("Dungeon generation refused for seed " + layout.seed() + ": "
                    + String.join(" | ", roomPlan.requiredPrefabFailures()));
            plugin.messages().send(leader, "required-prefab-failure");
            return;
        }
        if (!building.add(leader.getUniqueId())) {
            plugin.messages().send(leader, "already-building");
            return;
        }
        String worldName = plugin.parties().worldNameFor(party);
        plugin.messages().send(leader, "creating", Messages.ph("world", worldName));
        World world = plugin.worlds().createFresh(worldName);
        if (world == null) {
            building.remove(leader.getUniqueId());
            plugin.messages().send(leader, "delete-failed");
            return;
        }
        world.setSpawnLocation(layout.spawnX(), layout.spawnY(), layout.spawnZ());
        plugin.parties().activateInstance(party, worldName);
        plugin.messages().send(leader, "party-started",
                Messages.ph("difficulty", difficulty), Messages.ph("seed", layout.seed()));

        GenerationStyle style = GenerationStyle.fromConfig(plugin.getConfig(), plugin.getLogger());
        CorridorLibrary.CorridorPlan corridorPlan = plugin.corridors().plan(layout, roomPlan);
        int budget = plugin.getConfig().getInt("performance.blocks-per-tick", 60000);
        List<BuildOperation> operations = new ArrayList<>(layout.buildVolumes(style, roomPlan.prefabRoomIds(),
                corridorPlan.schematicTunnelIds()));
        operations.addAll(roomPlan.operations());
        operations.addAll(corridorPlan.operations());
        DungeonLayoutBuilder.start(plugin, world, operations, budget,
                percent -> sendProgress(leader, percent),
                result -> {
                    plugin.normalRooms().verifyGenerated(world, layout, roomPlan);
                    DungeonMarkerScanner.start(plugin, world, List.of(layout), plugin.getConfig(), budget, markers -> {
                        building.remove(leader.getUniqueId());
                        DungeonInstance instance = plugin.rooms().register(world, layout, mergeMarkers(markers, roomPlan.markers()),
                                roomPlan.doorways(), roomPlan.playableBounds(), roomPlan.playerSpawns(), roomPlan.bossSpawns(),
                                roomPlan.traps(), roomPlan.prefabFiles());
                        Location entrance = instance.playerSpawnLocation().orElse(world.getSpawnLocation());
                        world.setSpawnLocation(entrance);
                        List<UUID> members = new ArrayList<>(party.members());
                        for (int index = 0; index < members.size(); index++) {
                            UUID memberId = members.get(index);
                            Player member = Bukkit.getPlayer(memberId);
                            if (member != null && member.isOnline()) {
                                plugin.parties().markEntered(memberId, member.getLocation());
                                plugin.worlds().enter(member, partyEntrance(entrance, index));
                                plugin.messages().send(member, "party-teleported");
                            }
                        }
                        plugin.parties().markRunStarted(party);
                        plugin.messages().send(leader, "built",
                                Messages.ph("blocks", result.blocksChanged()),
                                Messages.ph("seconds", String.format(Locale.ROOT, "%.1f", result.elapsedMillis() / 1000.0)));
                    });
                });
    }

    private void handleParty(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            // The party UI used to be reached through the chest menu; with
            // that menu retired, the bare command is its front door.
            plugin.partyMenu().open(player);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "invite" -> handlePartyInvite(player, args);
            case "accept" -> handlePartyAccept(player);
            case "decline" -> handlePartyDecline(player);
            case "leave" -> handlePartyLeave(player);
            case "kick" -> handlePartyKick(player, args);
            case "list" -> handlePartyList(player);
            case "end" -> handlePartyEnd(player);
            default -> plugin.messages().send(player, "party-usage");
        }
    }

    private void handlePartyInvite(Player leader, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(leader, "party-invite-usage");
            return;
        }
        Player invitee = Bukkit.getPlayerExact(args[2]);
        if (invitee == null || !invitee.isOnline()) {
            plugin.messages().send(leader, "player-not-found");
            return;
        }
        PartyManager.InviteResult result = plugin.parties().invite(leader, invitee);
        switch (result) {
            case OK -> {
                plugin.messages().send(leader, "party-invited", Messages.ph("player", invitee.getName()));
                plugin.messages().send(invitee, "party-invite", Messages.ph("player", leader.getName()));
            }
            case SELF -> plugin.messages().send(leader, "party-self-invite");
            case NOT_LEADER -> plugin.messages().send(leader, "party-not-leader");
            case ALREADY_IN_PARTY -> plugin.messages().send(leader, "party-already-member");
            case FULL -> plugin.messages().send(leader, "party-full");
            case EXPIRED -> throw new IllegalStateException("Invite cannot expire while being created.");
        }
    }

    private void handlePartyAccept(Player player) {
        PartyManager.AcceptResult result = plugin.parties().accept(player.getUniqueId());
        switch (result.result()) {
            case OK -> {
                plugin.messages().send(player, "party-joined");
                Player leader = Bukkit.getPlayer(result.party().leader());
                if (leader != null && leader.isOnline()) {
                    plugin.messages().send(leader, "party-member-joined", Messages.ph("player", player.getName()));
                }
            }
            case EXPIRED -> plugin.messages().send(player, "party-invite-expired");
            case ALREADY_IN_PARTY -> plugin.messages().send(player, "party-already-member");
            case FULL -> plugin.messages().send(player, "party-full");
            default -> plugin.messages().send(player, "party-invite-expired");
        }
    }

    private void handlePartyDecline(Player player) {
        if (plugin.parties().decline(player.getUniqueId())) {
            plugin.messages().send(player, "party-invite-declined");
        } else {
            plugin.messages().send(player, "party-invite-expired");
        }
    }

    private void handlePartyLeave(Player player) {
        DungeonParty party = plugin.parties().partyOf(player.getUniqueId()).orElse(null);
        if (party == null) {
            plugin.messages().send(player, "party-none");
            return;
        }
        exitPartyInstance(player);
        PartyManager.Removal removal = plugin.parties().removeMember(player.getUniqueId()).orElseThrow();
        plugin.messages().send(player, "party-left");
        if (removal.leadershipChanged()) {
            Player newLeader = Bukkit.getPlayer(removal.newLeader());
            if (newLeader != null && newLeader.isOnline()) {
                plugin.messages().send(newLeader, "party-new-leader");
            }
        }
    }

    private void handlePartyKick(Player leader, String[] args) {
        DungeonParty party = plugin.parties().partyOf(leader.getUniqueId()).orElse(null);
        if (party == null) {
            plugin.messages().send(leader, "party-none");
            return;
        }
        if (!party.isLeader(leader.getUniqueId())) {
            plugin.messages().send(leader, "party-not-leader");
            return;
        }
        if (args.length < 3) {
            plugin.messages().send(leader, "party-kick-usage");
            return;
        }
        UUID targetId = party.members().stream()
                .filter(memberId -> args[2].equalsIgnoreCase(Bukkit.getOfflinePlayer(memberId).getName()))
                .findFirst().orElse(null);
        if (targetId == null) {
            plugin.messages().send(leader, "party-not-member");
            return;
        }
        if (targetId.equals(leader.getUniqueId())) {
            plugin.messages().send(leader, "party-kick-self");
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target != null && target.isOnline()) {
            exitPartyInstance(target);
        } else {
            plugin.parties().markExited(targetId).ifPresent(plugin.worlds()::deleteWorld);
        }
        plugin.parties().removeMember(targetId);
        plugin.messages().send(leader, "party-kicked", Messages.ph("player", args[2]));
        if (target != null && target.isOnline()) {
            plugin.messages().send(target, "party-kicked-notice");
        }
    }

    private void handlePartyList(Player player) {
        DungeonParty party = plugin.parties().partyOf(player.getUniqueId()).orElse(null);
        if (party == null) {
            plugin.messages().send(player, "party-none");
            return;
        }
        String members = party.members().stream()
                .map(memberId -> Bukkit.getOfflinePlayer(memberId).getName())
                .map(name -> name == null ? "unknown" : name)
                .reduce((left, right) -> left + ", " + right).orElse("unknown");
        String leaderName = Bukkit.getOfflinePlayer(party.leader()).getName();
        plugin.messages().send(player, "party-list",
                Messages.ph("leader", leaderName == null ? "unknown" : leaderName),
                Messages.ph("members", members));
    }

    private void handlePartyEnd(Player leader) {
        if (plugin.completions().isCleaningUp(leader.getUniqueId())) {
            plugin.messages().send(leader, "dungeon-cleaning-up");
            return;
        }
        DungeonParty party = plugin.parties().partyOf(leader.getUniqueId()).orElse(null);
        if (party == null) {
            plugin.messages().send(leader, "party-none");
            return;
        }
        if (!party.isLeader(leader.getUniqueId())) {
            plugin.messages().send(leader, "party-not-leader");
            return;
        }
        var ended = plugin.parties().endInstance(party);
        if (ended.isEmpty()) {
            plugin.messages().send(leader, "party-no-instance");
            return;
        }
        ended.ifPresent(worldName -> {
            plugin.worlds().deleteWorld(worldName);
            plugin.messages().send(leader, "party-ended");
        });
    }

    private void handleRooms(CommandSender sender) {
        if (!sender.hasPermission("dungeonforge.admin")) {
            plugin.messages().send(sender, "no-admin-permission");
            return;
        }
        List<NormalRoomLibrary.Inspection> rooms = plugin.normalRooms().inspections();
        plugin.messages().send(sender, "rooms-header", Messages.ph("folder", plugin.normalRooms().folder().getPath()));
        if (rooms.isEmpty()) {
            plugin.messages().send(sender, "rooms-empty");
        } else {
            List<Integer> corridorOffsets = plugin.corridors().inspections().stream()
                    .filter(CorridorLibrary.Inspection::valid)
                    .flatMap(inspection -> inspection.markerVerticalOffsets().stream())
                    .distinct()
                    .sorted()
                    .toList();
            String corridorOffsetText = corridorOffsets.isEmpty() ? "none" : corridorOffsets.stream()
                    .map(offset -> "y" + (offset >= 0 ? "+" : "") + offset)
                    .reduce((left, right) -> left + ", " + right).orElse("none");
            for (NormalRoomLibrary.Inspection room : rooms) {
                plugin.messages().send(sender, "rooms-entry",
                        Messages.ph("file", room.fileName()), Messages.ph("size", room.dimensions()),
                        Messages.ph("actual", room.actualDimensions()), Messages.ph("trimmed", room.trimmedDimensions()),
                        Messages.ph("marker-offsets", room.markerOffsets()),
                        Messages.ph("doorways", room.displayDoorwayGroups()),
                        Messages.ph("corridor-offsets", corridorOffsetText),
                        Messages.ph("corridor-offset-match", room.corridorOffsetCompatibility(corridorOffsets)),
                        Messages.ph("valid", room.valid() ? "valid" : "invalid"),
                        Messages.ph("type", room.displayType()),
                        Messages.ph("pattern", room.shape().configName()), Messages.ph("name-match", room.filenameMatch()),
                        Messages.ph("markers", room.markers()), Messages.ph("special-markers", room.displaySpecialMarkers()),
                        Messages.ph("problems", room.displayProblems()));
            }
        }
        for (NormalRoomLibrary.PrefabType type : List.of(NormalRoomLibrary.PrefabType.NORMAL, NormalRoomLibrary.PrefabType.BRANCH)) {
            List<String> missing = plugin.normalRooms().missingUsableShapes(type).stream().map(NormalRoomShape::configName).toList();
            plugin.messages().send(sender, "rooms-missing", Messages.ph("room-type", type.configName()),
                    Messages.ph("shapes", missing.isEmpty() ? "none" : String.join(", ", missing)));
        }
        List<String> unusable = plugin.normalRooms().unusablePrefabs();
        if (!unusable.isEmpty()) {
            plugin.messages().send(sender, "rooms-unusable", Messages.ph("rooms", String.join(", ", unusable)));
        }
    }

    private void handleCorridors(CommandSender sender) {
        if (!sender.hasPermission("dungeonforge.admin")) {
            plugin.messages().send(sender, "no-admin-permission");
            return;
        }
        List<CorridorLibrary.Inspection> corridors = plugin.corridors().inspections();
        plugin.messages().send(sender, "corridors-header", Messages.ph("folder", plugin.corridors().folder().getPath()));
        if (corridors.isEmpty()) {
            plugin.messages().send(sender, "corridors-empty");
            return;
        }
        for (CorridorLibrary.Inspection corridor : corridors) {
            plugin.messages().send(sender, "corridors-entry",
                    Messages.ph("file", corridor.fileName()), Messages.ph("stated", corridor.statedDimensions()),
                    Messages.ph("actual", corridor.actualDimensions()), Messages.ph("trimmed", corridor.trimmedDimensions()),
                    Messages.ph("connectors", corridor.connectors()),
                    Messages.ph("marker-offsets", corridor.markerOffsets()),
                    Messages.ph("valid", corridor.valid() ? "valid" : "invalid"),
                    Messages.ph("problems", corridor.displayProblems()));
        }
    }

    /**
     * Reads {@code x y z} and an optional yaw out of whatever tokens are left.
     *
     * <p>Accepts vanilla's {@code ~} and {@code ~offset} relative form, so a
     * spot can be nudged without doing the arithmetic. Anything missing falls
     * back to where the player stands, which is what the commands did before
     * coordinates existed.</p>
     *
     * @return the location, or empty when a token was meant as a coordinate
     *         but is not a number
     */
    private static Optional<Location> readLocation(Player player, List<String> tokens) {
        if (tokens.isEmpty()) {
            return Optional.of(player.getLocation());
        }
        if (tokens.size() != 3 && tokens.size() != 4) {
            return Optional.empty();
        }
        Location origin = player.getLocation();
        double[] base = {origin.getX(), origin.getY(), origin.getZ(), origin.getYaw()};
        double[] out = new double[4];
        out[3] = origin.getYaw();
        for (int index = 0; index < tokens.size(); index++) {
            Double value = readCoordinate(tokens.get(index), base[index]);
            if (value == null) {
                return Optional.empty();
            }
            out[index] = value;
        }
        return Optional.of(new Location(origin.getWorld(), out[0], out[1], out[2], (float) out[3], 0.0F));
    }

    private static Double readCoordinate(String token, double relativeTo) {
        try {
            if (token.startsWith("~")) {
                String offset = token.substring(1);
                return relativeTo + (offset.isEmpty() ? 0.0 : Double.parseDouble(offset));
            }
            return Double.parseDouble(token);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** A token the player meant as a coordinate rather than as a name. */
    private static boolean isCoordinate(String raw) {
        return raw.startsWith("~") || readCoordinate(raw, 0.0) != null;
    }

    private static boolean isVariantName(String raw) {
        return raw != null && List.of("standard", "big", "large").contains(raw.toLowerCase(Locale.ROOT));
    }

    /** The in-world skill tree panels: place, remove, list and render tests. */
    private void handleSkills(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (!player.hasPermission("dungeonforge.admin")) {
            plugin.messages().send(player, "no-admin-permission");
            return;
        }
        switch (args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "") {
            case "place" -> {
                // Both arguments are optional and either order reads
                // naturally: "place big" and "place warrior big" both work.
                String classId = "warrior";
                var variant = SkillPanelGeometry.Variant.STANDARD;
                List<String> coordinates = new ArrayList<>();
                for (int index = 2; index < args.length; index++) {
                    if (isVariantName(args[index])) {
                        variant = SkillPanelGeometry.Variant.parse(args[index]);
                    } else if (isCoordinate(args[index])) {
                        coordinates.add(args[index]);
                    } else {
                        classId = args[index];
                    }
                }
                Location where = readLocation(player, coordinates).orElse(null);
                if (where == null) {
                    plugin.messages().send(player, "location-usage");
                    return;
                }
                if (plugin.skillPanels().place(where, classId, variant).isPresent()) {
                    plugin.messages().send(player, "skills-placed",
                            Messages.ph("class", classId.toLowerCase(Locale.ROOT)),
                            Messages.ph("variant", variant.configName()));
                } else {
                    plugin.messages().send(player, "skills-unknown-class",
                            Messages.ph("class", classId),
                            Messages.ph("classes", String.join(", ", plugin.skillTrees().classIds())));
                }
            }
            case "move" -> {
                List<String> coordinates = new ArrayList<>();
                for (int index = 2; index < args.length; index++) {
                    if (isCoordinate(args[index])) coordinates.add(args[index]);
                }
                Location to = readLocation(player, coordinates).orElse(null);
                if (to == null) {
                    plugin.messages().send(player, "location-usage");
                    return;
                }
                Location moved = plugin.skillPanels().moveNearest(player.getLocation(), to).orElse(null);
                if (moved == null) {
                    plugin.messages().send(player, "skills-none-nearby", Messages.ph("radius",
                            Integer.toString((int) plugin.getConfig().getDouble("skill-panel.remove-radius", 5.0))));
                } else {
                    plugin.messages().send(player, "skills-moved", Messages.ph("x", moved.getBlockX()),
                            Messages.ph("y", moved.getBlockY()), Messages.ph("z", moved.getBlockZ()));
                }
            }
            case "remove" -> {
                if (plugin.skillPanels().removeNearest(player.getLocation())) {
                    plugin.messages().send(player, "skills-removed");
                } else {
                    plugin.messages().send(player, "skills-none-nearby", Messages.ph("radius",
                            Integer.toString((int) plugin.getConfig().getDouble("skill-panel.remove-radius", 5.0))));
                }
            }
            case "removeall" -> {
                var report = plugin.skillPanels().removeAll();
                String locations = report.locations().stream()
                        .map(location -> location.getWorld().getName() + " " + location.getBlockX() + ", "
                                + location.getBlockY() + ", " + location.getBlockZ())
                        .distinct()
                        .reduce((left, right) -> left + " | " + right).orElse("none");
                plugin.messages().send(player, "skills-removed-all",
                        Messages.ph("count", report.count()), Messages.ph("locations", locations));
            }
            case "points" -> {
                // The admin side of the point economy; the intended everyday
                // source is the API's grantSkillPoints.
                if (args.length < 5 || (!args[2].equalsIgnoreCase("give") && !args[2].equalsIgnoreCase("take"))) {
                    plugin.messages().send(player, "skills-points-usage");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[3]);
                int amount;
                try {
                    amount = Integer.parseInt(args[4]);
                } catch (NumberFormatException exception) {
                    plugin.messages().send(player, "skills-points-usage");
                    return;
                }
                if (target == null || !target.isOnline()) {
                    plugin.messages().send(player, "player-not-found");
                    return;
                }
                if (plugin.classSkills().isAvailable()) {
                    plugin.messages().send(player, "skills-points-external");
                    return;
                }
                int balance = args[2].equalsIgnoreCase("give")
                        ? plugin.skillProgress().grantPoints(target, amount)
                        : plugin.skillProgress().withdrawPoints(target, amount);
                plugin.messages().send(player, "skills-points-changed",
                        Messages.ph("player", target.getName()), Messages.ph("points", balance));
            }
            case "reset" -> {
                // The admin door to the same reset the API exposes: nodes go,
                // and every point ever paid for them comes back.
                Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : player;
                if (target == null || !target.isOnline()) {
                    plugin.messages().send(player, "player-not-found");
                    return;
                }
                String scope = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : null;
                List<String> classes;
                if (scope == null) {
                    classes = plugin.skillProgress().activeClass(target.getUniqueId())
                            .map(List::of).orElseGet(List::of);
                    if (classes.isEmpty()) {
                        plugin.messages().send(player, "skills-reset-no-class",
                                Messages.ph("player", target.getName()));
                        return;
                    }
                } else if (scope.equals("all")) {
                    classes = plugin.skillTrees().classIds();
                } else {
                    classes = List.of(scope);
                }
                int nodes = 0;
                int refunded = 0;
                for (String classId : classes) {
                    var result = plugin.skillProgress().resetTree(target, classId);
                    if (result.status() == nl.riddernix.dungeonforge.api.SkillWriteStatus.NO_SUCH_CLASS) {
                        plugin.messages().send(player, "skills-unknown-class", Messages.ph("class", classId),
                                Messages.ph("classes", String.join(", ", plugin.skillTrees().classIds())));
                        return;
                    }
                    nodes += result.nodes().size();
                    refunded += result.pointsChanged();
                }
                plugin.messages().send(player, "skills-reset", Messages.ph("player", target.getName()),
                        Messages.ph("nodes", nodes), Messages.ph("refunded", refunded),
                        Messages.ph("points", plugin.skillProgress().points(target.getUniqueId())));
            }
            case "test" -> {
                // Drives the three render states before real progression
                // exists: unlock any node freely, clear to start over.
                if (args.length >= 4 && args[2].equalsIgnoreCase("unlock")) {
                    switch (plugin.skillPanels().testUnlock(player, args[3])) {
                        case UNLOCKED -> plugin.messages().send(player, "skills-test-unlocked",
                                Messages.ph("node", args[3]));
                        case NO_PANEL -> plugin.messages().send(player, "skills-test-no-panel");
                        case UNKNOWN_NODE -> plugin.messages().send(player, "skills-test-unknown-node",
                                Messages.ph("node", args[3]));
                    }
                } else if (args.length >= 3 && args[2].equalsIgnoreCase("clear")) {
                    plugin.skillPanels().testClear(player);
                    plugin.messages().send(player, "skills-test-cleared");
                } else {
                    plugin.messages().send(player, "skills-test-usage");
                }
            }
            case "list" -> {
                var skillPanels = plugin.skillPanels().list();
                if (skillPanels.isEmpty()) {
                    plugin.messages().send(player, "skills-list-empty");
                    return;
                }
                plugin.messages().send(player, "skills-list-header");
                for (var panel : skillPanels) {
                    plugin.messages().send(player, "skills-list-entry",
                            Messages.ph("id", panel.id()),
                            Messages.ph("class", panel.classId()),
                            Messages.ph("variant", panel.variant()),
                            Messages.ph("world", panel.location().getWorld().getName()),
                            Messages.ph("x", panel.location().getBlockX()),
                            Messages.ph("y", panel.location().getBlockY()),
                            Messages.ph("z", panel.location().getBlockZ()));
                }
            }
            default -> plugin.messages().send(player, "skills-usage");
        }
    }

    /**
     * Tells in ten seconds whether an API problem is this plugin's or the
     * other plugin's: {@code status} shows what has fired and who is
     * listening, {@code fire} sends one of each event, {@code query} dumps
     * what the query side answers for you right now.
     */
    private void handleApi(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dungeonforge.admin")) {
            plugin.messages().send(sender, "no-admin-permission");
            return;
        }
        switch (args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status") {
            case "status" -> {
                plugin.messages().send(sender, "api-header",
                        Messages.ph("version", nl.riddernix.dungeonforge.api.DungeonForgeApi.API_VERSION),
                        Messages.ph("listeners", plugin.events().foreignListenerCount()));
                for (String line : plugin.events().status()) {
                    sender.sendMessage(plugin.messages().bare("api-entry", Messages.ph("line", line)));
                }
            }
            case "fire" -> {
                Player player = asPlayer(sender);
                if (player == null) {
                    return;
                }
                for (String line : plugin.events().fireAll(player)) {
                    sender.sendMessage(plugin.messages().bare("api-entry", Messages.ph("line", line)));
                }
            }
            case "query" -> {
                Player player = asPlayer(sender);
                if (player == null) {
                    return;
                }
                for (String line : plugin.events().queryDump(plugin.api(), player)) {
                    sender.sendMessage(plugin.messages().bare("api-entry", Messages.ph("line", line)));
                }
            }
            default -> plugin.messages().send(sender, "api-usage");
        }
    }

    /** The fixed in-world difficulty selector: place, remove, and find them. */
    private void handlePanel(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (!player.hasPermission("dungeonforge.admin")) {
            plugin.messages().send(player, "no-admin-permission");
            return;
        }
        switch (args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "") {
            case "place" -> {
                Location where = readLocation(player, List.of(args).subList(Math.min(2, args.length), args.length))
                        .orElse(null);
                if (where == null) {
                    plugin.messages().send(player, "location-usage");
                    return;
                }
                plugin.panels().place(where);
                plugin.messages().send(player, "panel-placed");
            }
            case "move" -> {
                Location to = readLocation(player, List.of(args).subList(Math.min(2, args.length), args.length))
                        .orElse(null);
                if (to == null) {
                    plugin.messages().send(player, "location-usage");
                    return;
                }
                Location moved = plugin.panels().moveNearest(player.getLocation(), to).orElse(null);
                if (moved == null) {
                    plugin.messages().send(player, "panel-none-nearby", Messages.ph("radius",
                            Integer.toString((int) plugin.getConfig().getDouble("difficulty-panel.remove-radius", 5.0))));
                } else {
                    plugin.messages().send(player, "panel-moved", Messages.ph("x", moved.getBlockX()),
                            Messages.ph("y", moved.getBlockY()), Messages.ph("z", moved.getBlockZ()));
                }
            }
            case "remove" -> {
                if (plugin.panels().removeNearest(player.getLocation())) {
                    plugin.messages().send(player, "panel-removed");
                } else {
                    plugin.messages().send(player, "panel-none-nearby", Messages.ph("radius",
                            Integer.toString((int) plugin.getConfig().getDouble("difficulty-panel.remove-radius", 5.0))));
                }
            }
            case "removeall" -> {
                var report = plugin.panels().removeAll();
                String locations = report.locations().stream()
                        .map(location -> location.getWorld().getName() + " " + location.getBlockX() + ", "
                                + location.getBlockY() + ", " + location.getBlockZ())
                        .distinct()
                        .reduce((left, right) -> left + " | " + right).orElse("none");
                plugin.messages().send(player, "panel-removed-all",
                        Messages.ph("count", report.count()), Messages.ph("locations", locations));
            }
            case "list" -> {
                var panels = plugin.panels().list();
                if (panels.isEmpty()) {
                    plugin.messages().send(player, "panel-list-empty");
                    return;
                }
                plugin.messages().send(player, "panel-list-header");
                for (var panel : panels) {
                    plugin.messages().send(player, "panel-list-entry",
                            Messages.ph("id", panel.id()),
                            Messages.ph("world", panel.location().getWorld().getName()),
                            Messages.ph("x", panel.location().getBlockX()),
                            Messages.ph("y", panel.location().getBlockY()),
                            Messages.ph("z", panel.location().getBlockZ()));
                }
            }
            default -> plugin.messages().send(player, "panel-usage");
        }
    }

    /** Plays a boss entrance where you stand, for tuning it without a dungeon. */
    private void handleAnimate(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (!player.hasPermission("dungeonforge.admin")) {
            plugin.messages().send(player, "no-admin-permission");
            return;
        }
        AnimationPreview preview = plugin.animations();
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            plugin.messages().send(player, preview.stop(player) ? "animate-stopped" : "animate-none");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "animate-usage",
                    Messages.ph("animations", String.join(", ", preview.animationNames())));
            return;
        }
        AnimationPreview.Result result = preview.start(player, args[1], args.length >= 3 ? args[2] : null);
        switch (result.status()) {
            case SUCCESS -> {
                plugin.messages().send(player, "animate-started",
                        Messages.ph("animation", args[1].toLowerCase(Locale.ROOT)),
                        Messages.ph("theme", result.theme()),
                        Messages.ph("seconds", String.format(Locale.ROOT, "%.1f", result.durationTicks() / 20.0)));
                // Says what actually got created, so an empty screen can be told
                // apart from an animation that never built anything.
                plugin.messages().send(player, "animate-detail", Messages.ph("detail", result.detail()));
            }
            case FAILED -> plugin.messages().send(player, "animate-failed");
            case UNKNOWN_ANIMATION -> plugin.messages().send(player, "animate-unknown",
                    Messages.ph("animations", String.join(", ", preview.animationNames())));
            case UNKNOWN_THEME -> plugin.messages().send(player, "animate-unknown-theme",
                    Messages.ph("theme", result.theme() == null ? "none" : result.theme()));
            case INVALID_BOSS -> plugin.messages().send(player, "animate-invalid-boss",
                    Messages.ph("theme", result.theme()));
            case ALREADY_RUNNING -> plugin.messages().send(player, "animate-already-running");
        }
    }

    /** Reports which configured mob models the model engine actually has. */
    private void handleModels(CommandSender sender) {
        if (!sender.hasPermission("dungeonforge.admin")) {
            plugin.messages().send(sender, "no-admin-permission");
            return;
        }
        plugin.messages().send(sender, "models-header");
        for (String line : plugin.models().diagnostics()) {
            sender.sendMessage(plugin.messages().bare("models-entry", Messages.ph("line", line)));
        }
    }

    private void exitPartyInstance(Player player) {
        if (!plugin.parties().isInside(player.getUniqueId())) {
            return;
        }
        plugin.worlds().exit(player);
        plugin.parties().markExited(player.getUniqueId()).ifPresent(plugin.worlds()::deleteWorld);
    }

    private Long parseSeed(Player player, String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            plugin.messages().send(player, "invalid-seed");
            return null;
        }
    }

    private Integer parseAmount(Player player, String raw) {
        try {
            int amount = Integer.parseInt(raw);
            int maximum = Math.max(1, plugin.getConfig().getInt("generation.multi.max-amount", 64));
            if (amount < 1 || amount > maximum) {
                plugin.messages().send(player, "invalid-amount", Messages.ph("maximum", maximum));
                return null;
            }
            return amount;
        } catch (NumberFormatException ex) {
            plugin.messages().send(player, "invalid-amount",
                    Messages.ph("maximum", plugin.getConfig().getInt("generation.multi.max-amount", 64)));
            return null;
        }
    }

    /** Packs layouts into a grid using their actual planned bounds plus padding. */
    private static List<DungeonLayout> arrangeInGrid(List<DungeonLayout> layouts, int padding) {
        int cellWidth = 0;
        int cellDepth = 0;
        for (DungeonLayout layout : layouts) {
            cellWidth = Math.max(cellWidth, layout.bounds().sizeX());
            cellDepth = Math.max(cellDepth, layout.bounds().sizeZ());
        }
        cellWidth += padding;
        cellDepth += padding;
        int columns = (int) Math.ceil(Math.sqrt(layouts.size()));
        List<DungeonLayout> result = new ArrayList<>();
        for (int index = 0; index < layouts.size(); index++) {
            DungeonLayout layout = layouts.get(index);
            int column = index % columns;
            int row = index / columns;
            result.add(layout.translate(column * cellWidth - layout.bounds().minX(), 0,
                    row * cellDepth - layout.bounds().minZ()));
        }
        return result;
    }

    private void startLayouts(Player player, List<DungeonLayout> layouts, Runnable onStarted) {
        if (!building.add(player.getUniqueId())) {
            plugin.messages().send(player, "already-building");
            return;
        }

        List<NormalRoomLibrary.RoomPlan> roomPlans = new ArrayList<>();
        for (DungeonLayout layout : layouts) {
            NormalRoomLibrary.RoomPlan roomPlan = plugin.normalRooms().plan(layout);
            if (roomPlan.hasRequiredPrefabFailures()) {
                building.remove(player.getUniqueId());
                plugin.getLogger().severe("Dungeon generation refused for seed " + layout.seed() + ": "
                        + String.join(" | ", roomPlan.requiredPrefabFailures()));
                plugin.messages().send(player, "required-prefab-failure");
                return;
            }
            roomPlans.add(roomPlan);
        }

        String worldName = plugin.worlds().worldNameFor(player);
        plugin.messages().send(player, "creating", Messages.ph("world", worldName));
        World world = plugin.worlds().createFresh(worldName);
        if (world == null) {
            building.remove(player.getUniqueId());
            plugin.messages().send(player, "delete-failed");
            return;
        }

        DungeonLayout first = layouts.getFirst();
        world.setSpawnLocation(first.spawnX(), first.spawnY(), first.spawnZ());
        onStarted.run();

        GenerationStyle style = GenerationStyle.fromConfig(plugin.getConfig(), plugin.getLogger());
        List<BuildOperation> volumes = new ArrayList<>();
        for (int index = 0; index < layouts.size(); index++) {
            DungeonLayout layout = layouts.get(index);
            NormalRoomLibrary.RoomPlan roomPlan = roomPlans.get(index);
            CorridorLibrary.CorridorPlan corridorPlan = plugin.corridors().plan(layout, roomPlan);
            roomPlans.add(roomPlan);
            volumes.addAll(layout.buildVolumes(style, roomPlan.prefabRoomIds(), corridorPlan.schematicTunnelIds()));
            volumes.addAll(roomPlan.operations());
            volumes.addAll(corridorPlan.operations());
        }
        int budget = plugin.getConfig().getInt("performance.blocks-per-tick", 60000);
        DungeonLayoutBuilder.start(plugin, world, volumes, budget,
                percent -> sendProgress(player, percent),
                result -> {
                    for (int index = 0; index < layouts.size(); index++) {
                        plugin.normalRooms().verifyGenerated(world, layouts.get(index), roomPlans.get(index));
                    }
                    DungeonMarkerScanner.start(plugin, world, layouts, plugin.getConfig(), budget, markers -> {
                        building.remove(player.getUniqueId());
                        DungeonLayout firstLayout = layouts.getFirst();
                        NormalRoomLibrary.RoomPlan firstPlan = roomPlans.getFirst();
                        DungeonInstance preview = new DungeonInstance(world, firstLayout, mergeMarkers(markers, firstPlan.markers()),
                                firstPlan.doorways(), firstPlan.playableBounds(), firstPlan.playerSpawns(), firstPlan.bossSpawns(),
                                firstPlan.traps(), firstPlan.prefabFiles());
                        preview.playerSpawnLocation().ifPresent(world::setSpawnLocation);
                        finish(player, world, result.blocksChanged(), result.elapsedMillis());
                    });
                });
    }

    private void sendProgress(Player player, int percent) {
        if (player.isOnline()) {
            player.sendActionBar(plugin.messages().bare("building", Messages.ph("percent", percent)));
        }
    }

    private static Map<String, List<nl.riddernix.dungeonforge.room.DungeonMarker>> mergeMarkers(
            Map<String, List<nl.riddernix.dungeonforge.room.DungeonMarker>> scanned,
            Map<String, List<nl.riddernix.dungeonforge.room.DungeonMarker>> prefab) {
        Map<String, List<nl.riddernix.dungeonforge.room.DungeonMarker>> merged = new java.util.HashMap<>(scanned);
        prefab.forEach(merged::put);
        return Map.copyOf(merged);
    }

    private void finish(Player player, World world, long blocks, long elapsedMillis) {
        if (!player.isOnline()) {
            return;
        }
        String seconds = String.format(Locale.ROOT, "%.1f", elapsedMillis / 1000.0);
        plugin.messages().send(player, "built",
                Messages.ph("blocks", blocks),
                Messages.ph("seconds", seconds));

        if (plugin.getConfig().getBoolean("teleport.on-create", true)) {
            plugin.worlds().enter(player, world);
            plugin.messages().send(player, "teleported");
        }
    }

    /** Uses nearby walkable floor tiles so a party does not arrive as one entity stack. */
    private static Location partyEntrance(Location base, int index) {
        int[][] offsets = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        int[] offset = offsets[index % offsets.length];
        Location candidate = base.clone().add(offset[0], 0.0, offset[1]);
        World world = candidate.getWorld();
        if (world != null && world.getBlockAt(candidate.getBlockX(), candidate.getBlockY() - 1, candidate.getBlockZ()).getType().isSolid()
                && world.getBlockAt(candidate.getBlockX(), candidate.getBlockY(), candidate.getBlockZ()).isPassable()
                && world.getBlockAt(candidate.getBlockX(), candidate.getBlockY() + 1, candidate.getBlockZ()).isPassable()) {
            return candidate;
        }
        return base;
    }

    /** Block count of a hollow n x n x n shell. */
    private static long shellBlockCount(int n) {
        long full = (long) n * n * n;
        long inner = (long) (n - 2) * (n - 2) * (n - 2);
        return full - inner;
    }

    private void handleTeleport(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        var partyInstance = plugin.parties().instanceForMember(player.getUniqueId()).orElse(null);
        if (partyInstance != null) {
            World partyWorld = Bukkit.getWorld(partyInstance.worldName());
            if (partyWorld != null) {
                plugin.worlds().enter(player, partyWorld);
                plugin.parties().markEntered(player.getUniqueId());
                plugin.messages().send(player, "teleported");
                return;
            }
        }
        World world = Bukkit.getWorld(plugin.worlds().worldNameFor(player));
        if (world == null) {
            plugin.messages().send(player, "no-dungeon");
            return;
        }
        plugin.worlds().enter(player, world);
        plugin.messages().send(player, "teleported");
    }

    private void handleLeave(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (plugin.parties().isInside(player.getUniqueId())) {
            exitPartyInstance(player);
            plugin.messages().send(player, "left");
            return;
        }
        if (!plugin.worlds().isDungeonWorld(player.getWorld())) {
            plugin.messages().send(player, "not-in-dungeon");
            return;
        }
        plugin.worlds().exit(player);
        plugin.messages().send(player, "left");
    }

    private void handleDelete(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        String worldName = plugin.worlds().worldNameFor(player);
        if (Bukkit.getWorld(worldName) == null
                && !Bukkit.getWorldContainer().toPath().resolve(worldName).toFile().exists()) {
            plugin.messages().send(player, "no-dungeon");
            return;
        }

        if (plugin.worlds().deleteWorld(worldName)) {
            plugin.messages().send(player, "deleted", Messages.ph("world", worldName));
        } else {
            plugin.messages().send(player, "delete-failed");
        }
    }

    private void handleList(CommandSender sender) {
        List<World> worlds = plugin.worlds().loadedDungeonWorlds();
        if (worlds.isEmpty()) {
            plugin.messages().send(sender, "list-empty");
            return;
        }
        plugin.messages().send(sender, "list-header");
        for (World world : worlds) {
            sender.sendMessage(plugin.messages().bare("list-entry",
                    Messages.ph("world", world.getName()),
                    Messages.ph("players", world.getPlayers().size())));
        }
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadEverything();
        plugin.messages().send(sender, "reloaded");
    }

    // ------------------------------------------------------------------

    private Player asPlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.messages().send(sender, "players-only");
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(partial)) {
                    matches.add(sub);
                }
            }
            return matches;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("generate")) {
            return List.of("1", "2", "3", "4", "5", "6", "7", "8", "9");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("party")) {
            return matching(args[1], List.of("invite", "accept", "decline", "leave", "kick", "list", "end"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("npc") && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[1], List.of("spawn", "remove", "removeall"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("markers") && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[1], List.of("give"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("api") && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[1], List.of("status", "fire", "query"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("skills") && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[1], List.of("place", "move", "remove", "removeall", "list", "test", "points", "reset"));
        }
        // Suggests the relative form, which is both the likely intent and a
        // reminder that coordinates are accepted here at all.
        if (args[0].equalsIgnoreCase("skills") && args[1].equalsIgnoreCase("move")
                && args.length <= 5 && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[args.length - 1], List.of("~"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("skills") && args[1].equalsIgnoreCase("points")
                && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[2], List.of("give", "take"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("skills") && args[1].equalsIgnoreCase("points")
                && sender.hasPermission("dungeonforge.admin")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[3].toLowerCase(Locale.ROOT)))
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("skills") && args[1].equalsIgnoreCase("place")
                && sender.hasPermission("dungeonforge.admin")) {
            List<String> options = new ArrayList<>(plugin.skillTrees().classIds());
            options.addAll(List.of("standard", "big"));
            return matching(args[2], options);
        }
        if (args.length >= 4 && args.length <= 7 && args[0].equalsIgnoreCase("skills")
                && args[1].equalsIgnoreCase("place") && sender.hasPermission("dungeonforge.admin")) {
            List<String> options = new ArrayList<>(List.of("~"));
            if (args.length == 4) options.addAll(List.of("standard", "big"));
            return matching(args[args.length - 1], options);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("skills") && args[1].equalsIgnoreCase("reset")
                && sender.hasPermission("dungeonforge.admin")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("skills") && args[1].equalsIgnoreCase("reset")
                && sender.hasPermission("dungeonforge.admin")) {
            List<String> options = new ArrayList<>(plugin.skillTrees().classIds());
            options.add("all");
            return matching(args[3], options);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("skills") && args[1].equalsIgnoreCase("test")
                && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[2], List.of("unlock", "clear"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("skills") && args[1].equalsIgnoreCase("test")
                && args[2].equalsIgnoreCase("unlock") && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[3], plugin.skillPanels().allNodeIds());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("panel") && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[1], List.of("place", "move", "remove", "removeall", "list"));
        }
        if (args[0].equalsIgnoreCase("panel") && args.length >= 3 && args.length <= 6
                && (args[1].equalsIgnoreCase("place") || args[1].equalsIgnoreCase("move"))
                && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[args.length - 1], List.of("~"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("animate") && sender.hasPermission("dungeonforge.admin")) {
            List<String> options = new ArrayList<>(plugin.animations().animationNames());
            options.add("stop");
            return matching(args[1], options);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("animate") && !args[1].equalsIgnoreCase("stop")
                && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[2], plugin.animations().themes());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("markers") && args[1].equalsIgnoreCase("give")
                && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[2], plugin.mobs().markerCategories());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("door") && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[1], List.of("open"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("room") && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[1], List.of("open"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("summon") && sender.hasPermission("dungeonforge.admin")) {
            List<String> options = new ArrayList<>(plugin.mobs().combatCategories());
            options.add("clear");
            return matching(args[1], options);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("summon") && !args[1].equalsIgnoreCase("clear")
                && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[2], List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("summon") && sender.hasPermission("dungeonforge.admin")) {
            return matching(args[3], plugin.mobs().themes());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("party") && args[1].equalsIgnoreCase("invite")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("party") && args[1].equalsIgnoreCase("kick")
                && sender instanceof Player player) {
            return plugin.parties().partyOf(player.getUniqueId()).stream()
                    .flatMap(party -> party.members().stream())
                    .filter(memberId -> !memberId.equals(player.getUniqueId()))
                    .map(Bukkit::getOfflinePlayer)
                    .map(offlinePlayer -> offlinePlayer.getName())
                    .filter(name -> name != null && name.toLowerCase(Locale.ROOT)
                            .startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        return List.of();
    }

    private static List<String> matching(String partial, List<String> candidates) {
        String normalized = partial.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(normalized))
                .toList();
    }

    public enum StartStatus {
        READY,
        NOT_LEADER,
        INSTANCE_ACTIVE,
        BUILDING
    }
}
