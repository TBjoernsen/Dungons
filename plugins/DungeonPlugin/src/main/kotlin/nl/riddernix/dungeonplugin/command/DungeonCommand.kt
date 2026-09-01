package nl.riddernix.dungeonplugin.command

import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import nl.riddernix.dungeonplugin.DungeonPlugin
import nl.riddernix.dungeonplugin.build.BoxBuilder
import nl.riddernix.dungeonplugin.build.BoxSpec
import nl.riddernix.dungeonplugin.event.SkillWriteStatus
import nl.riddernix.dungeonplugin.fx.AnimationPreview
import nl.riddernix.dungeonplugin.generation.BuildOperation
import nl.riddernix.dungeonplugin.generation.DungeonLayout
import nl.riddernix.dungeonplugin.generation.DungeonLayoutBuilder
import nl.riddernix.dungeonplugin.generation.DungeonLayoutGenerator
import nl.riddernix.dungeonplugin.generation.GenerationStyle
import nl.riddernix.dungeonplugin.party.DungeonParty
import nl.riddernix.dungeonplugin.party.PartyManager
import nl.riddernix.dungeonplugin.room.CorridorLibrary
import nl.riddernix.dungeonplugin.room.DungeonInstance
import nl.riddernix.dungeonplugin.room.DungeonMarker
import nl.riddernix.dungeonplugin.room.DungeonMarkerScanner
import nl.riddernix.dungeonplugin.room.NormalRoomLibrary
import nl.riddernix.dungeonplugin.skills.SkillPanelGeometry
import nl.riddernix.dungeonplugin.util.Messages
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Locale
import java.util.Random
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Every /dungeon subcommand.
 *
 * Adding one means touching three places: the switch in [onCommand], the list
 * in [onTabComplete], and the usage line in config.yml.
 */
class DungeonCommand(private val plugin: DungeonPlugin) : TabExecutor {

    /** Players who currently have a build running. */
    private val building = HashSet<UUID>()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("dungeonplugin.use")) {
            plugin.messages.send(sender, "no-permission")
            return true
        }
        if (args.isEmpty()) {
            val player = asPlayer(sender)
            if (player != null) {
                // The chest menu is retired: the difficulty panel standing in
                // the world replaced it, so this points at the nearest one.
                plugin.panels.sendLocator(player)
            }
            return true
        }

        when (args[0].lowercase(Locale.ROOT)) {
            "test" -> handleTest(sender)
            "generate" -> handleGenerate(sender, args)
            "compare" -> handleCompare(sender, args)
            "rooms" -> handleRooms(sender)
            "corridors" -> handleCorridors(sender)
            "models" -> handleModels(sender)
            "animate" -> handleAnimate(sender, args)
            "panel" -> handlePanel(sender, args)
            "skills" -> handleSkills(sender, args)
            "api" -> handleApi(sender, args)
            "party" -> handleParty(sender, args)
            "start" -> handleStart(sender, args)
            "settings" -> handleSettings(sender)
            "npc" -> handleNpc(sender, args)
            "goals" -> handleGoals(sender)
            "markers" -> handleMarkers(sender, args)
            "summon" -> handleSummon(sender, args)
            "door" -> handleDoor(sender, args)
            "room" -> handleRoom(sender, args)
            "tp" -> handleTeleport(sender)
            "leave" -> handleLeave(sender)
            "delete" -> handleDelete(sender)
            "list" -> handleList(sender)
            "reload" -> handleReload(sender)
            else -> plugin.messages.send(sender, "usage")
        }
        return true
    }

    // ------------------------------------------------------------------

    private fun handleTest(sender: CommandSender) {
        val player = asPlayer(sender) ?: return
        if (!building.add(player.uniqueId)) {
            plugin.messages.send(player, "already-building")
            return
        }

        val worldName = plugin.worlds.worldNameFor(player)
        plugin.messages.send(player, "creating", Messages.ph("world", worldName))

        val world = plugin.worlds.createFresh(worldName)
        if (world == null) {
            building.remove(player.uniqueId)
            plugin.messages.send(player, "delete-failed")
            return
        }

        buildBox(player, world)
    }

    private fun buildBox(player: Player, world: World) {
        val spec = BoxSpec.fromConfig(plugin.config, plugin.logger)
        BoxBuilder.start(plugin, world, spec,
            { percent -> sendProgress(player, percent) },
            {
                building.remove(player.uniqueId)
                val blocks = if (spec.hollow) shellBlockCount(spec.size) else spec.volume()
                finish(player, world, blocks, 0L)
            })
    }

    private fun handleGenerate(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return
        if (args.size < 2) {
            plugin.messages.send(player, "generate-usage")
            return
        }
        val difficulty = args[1].toIntOrNull()
        if (difficulty == null || difficulty < 1 || difficulty > 9) {
            plugin.messages.send(player, "invalid-difficulty")
            return
        }
        val amount = if (args.size >= 3) parseAmount(player, args[2]) else 1
        if (amount == null) {
            return
        }
        val baseSeed = if (args.size >= 4) parseSeed(player, args[3]) else ThreadLocalRandom.current().nextLong()
        if (baseSeed == null) {
            return
        }

        try {
            val generator = DungeonLayoutGenerator(plugin.config, plugin.normalRooms)
            var layouts = ArrayList<DungeonLayout>()
            if (amount == 1) {
                layouts.add(generator.generate(difficulty, baseSeed))
            } else {
                val seeds = Random(baseSeed)
                for (index in 0 until amount) {
                    layouts.add(generator.generate(difficulty, seeds.nextLong()))
                }
                layouts = ArrayList(arrangeInGrid(layouts,
                    maxOf(32, plugin.config.getInt("generation.multi.padding", 96))))
            }

            val finalLayouts = layouts
            startLayouts(player, layouts) {
                if (amount == 1) {
                    plugin.messages.send(player, "generation-started",
                        Messages.ph("difficulty", difficulty), Messages.ph("seed", finalLayouts.first().seed))
                    return@startLayouts
                }
                plugin.messages.send(player, "generation-batch-started",
                    Messages.ph("difficulty", difficulty), Messages.ph("amount", amount))
                for (index in finalLayouts.indices) {
                    val layout = finalLayouts[index]
                    plugin.messages.send(player, "generation-batch-entry",
                        Messages.ph("index", index + 1),
                        Messages.ph("seed", layout.seed),
                        Messages.ph("x", layout.spawnX),
                        Messages.ph("y", layout.spawnY),
                        Messages.ph("z", layout.spawnZ))
                }
            }
        } catch (ex: DungeonLayoutGenerator.GenerationException) {
            plugin.logger.warning("Dungeon generation failed: ${ex.message}")
            plugin.messages.send(player, "generation-failed")
        }
    }

    private fun handleCompare(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return
        val seed = if (args.size >= 2) parseSeed(player, args[1]) else ThreadLocalRandom.current().nextLong()
        if (seed == null) {
            return
        }

        try {
            val generator = DungeonLayoutGenerator(plugin.config, plugin.normalRooms)
            val layouts = ArrayList<DungeonLayout>()
            var cellWidth = 0
            var cellDepth = 0
            for (difficulty in 1..9) {
                val layout = generator.generate(difficulty, seed)
                layouts.add(layout)
                cellWidth = maxOf(cellWidth, layout.bounds.sizeX())
                cellDepth = maxOf(cellDepth, layout.bounds.sizeZ())
            }
            val padding = maxOf(16, plugin.config.getInt("generation.comparison.padding", 48))
            cellWidth += padding
            cellDepth += padding

            val translated = ArrayList<DungeonLayout>()
            for (index in layouts.indices) {
                val layout = layouts[index]
                val column = index % 3
                val row = index / 3
                translated.add(layout.translate(column * cellWidth - layout.bounds.minX, 0,
                    row * cellDepth - layout.bounds.minZ))
            }
            startLayouts(player, translated) {
                plugin.messages.send(player, "comparison-started", Messages.ph("seed", seed))
            }
        } catch (ex: DungeonLayoutGenerator.GenerationException) {
            plugin.logger.warning("Dungeon comparison generation failed: ${ex.message}")
            plugin.messages.send(player, "generation-failed")
        }
    }

    private fun handleStart(sender: CommandSender, args: Array<out String>) {
        val leader = asPlayer(sender) ?: return
        if (args.size < 2) {
            plugin.messages.send(leader, "start-usage")
            return
        }
        val difficulty = args[1].toIntOrNull()
        if (difficulty == null || difficulty < 1 || difficulty > 9) {
            plugin.messages.send(leader, "invalid-difficulty")
            return
        }
        val seed = if (args.size >= 3) parseSeed(leader, args[2]) else ThreadLocalRandom.current().nextLong()
        if (seed == null) {
            return
        }

        requestPartyStart(leader, difficulty, seed, true)
    }

    /**
     * Starts a run from the in-world difficulty panel. Unlike the old chest
     * menu there are no item tooltips to explain a refusal, so failures are
     * reported in chat.
     */
    fun startFromPanel(leader: Player, difficulty: Int) {
        requestPartyStart(leader, difficulty, ThreadLocalRandom.current().nextLong(), true)
    }

    /** Returns whether a player can press the start button right now. */
    fun startStatus(player: Player): StartStatus {
        if (plugin.completions.isCleaningUp(player.uniqueId)) {
            return StartStatus.INSTANCE_ACTIVE
        }
        if (player.uniqueId in building) {
            return StartStatus.BUILDING
        }
        val party = plugin.parties.partyOf(player.uniqueId) ?: return StartStatus.READY
        if (!party.isLeader(player.uniqueId)) {
            return StartStatus.NOT_LEADER
        }
        return if (plugin.parties.hasInstance(party)) StartStatus.INSTANCE_ACTIVE else StartStatus.READY
    }

    private fun requestPartyStart(leader: Player, difficulty: Int, seed: Long, reportFailure: Boolean) {
        val status = startStatus(leader)
        if (status != StartStatus.READY) {
            if (reportFailure) {
                when (status) {
                    StartStatus.NOT_LEADER -> plugin.messages.send(leader, "party-not-leader")
                    StartStatus.INSTANCE_ACTIVE -> plugin.messages.send(leader, "party-instance-active")
                    StartStatus.BUILDING -> plugin.messages.send(leader, "already-building")
                    StartStatus.READY -> throw IllegalStateException("Ready status was handled above.")
                }
            }
            return
        }

        val party = plugin.parties.partyForLeader(leader.uniqueId)
        // The one cancellable point in the event layer, and deliberately the
        // earliest: no world, no layout and no mobs exist yet, so refusing
        // here costs nothing and cannot leave anything half-built.
        if (!plugin.events.fireStart(plugin.snapshots.pending(plugin.parties.worldNameFor(party), difficulty,
                seed, party.members.toList()), leader)) {
            plugin.messages.send(leader, "start-refused")
            return
        }
        try {
            val layout = DungeonLayoutGenerator(plugin.config, plugin.normalRooms).generate(difficulty, seed)
            startPartyDungeon(leader, party, layout, difficulty)
        } catch (ex: DungeonLayoutGenerator.GenerationException) {
            plugin.logger.warning("Party dungeon generation failed: ${ex.message}")
            plugin.messages.send(leader, "generation-failed")
        }
    }

    private fun handleNpc(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return
        if (!player.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(player, "no-settings-permission")
            return
        }
        if (args.size < 2) {
            plugin.messages.send(player, "npc-usage")
            return
        }
        when (args[1].lowercase(Locale.ROOT)) {
            "spawn" -> {
                if (plugin.dungeonLords.spawn(player.location) != null) {
                    plugin.messages.send(player, "npc-spawned")
                } else {
                    plugin.messages.send(player, "npc-spawn-failed")
                }
            }
            "remove" -> {
                if (plugin.dungeonLords.removeNearest(player.location)) {
                    plugin.messages.send(player, "npc-removed")
                } else {
                    plugin.messages.send(player, "npc-none-nearby")
                }
            }
            "removeall" -> {
                val report = plugin.dungeonLords.removeAll()
                val locations = report.locations.joinToString(" | ") { location ->
                    "${location.world!!.name} ${location.blockX}, ${location.blockY}, ${location.blockZ}"
                }.ifEmpty { "none" }
                plugin.messages.send(player, "npc-removed-all", Messages.ph("count", report.count), Messages.ph("locations", locations))
            }
            else -> plugin.messages.send(player, "npc-usage")
        }
    }

    private fun handleSettings(sender: CommandSender) {
        val player = asPlayer(sender) ?: return
        if (!player.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(player, "no-admin-permission")
            return
        }
        plugin.settings.openHome(player)
    }

    private fun handleGoals(sender: CommandSender) {
        val player = asPlayer(sender) ?: return
        if (!player.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(player, "no-admin-permission")
            return
        }
        val target = player.getTargetEntity(24)
        if (target == null) {
            plugin.messages.send(player, "goals-no-target")
            return
        }
        val goals = plugin.mobs.goalNames(target)
        plugin.messages.send(player, "goals-header", Messages.ph("entity", target.type.name))
        if (goals.isEmpty()) {
            plugin.messages.send(player, "goals-none")
            return
        }
        for (goal in goals) plugin.messages.send(player, "goals-entry", Messages.ph("goal", goal))
        for (diagnostic in plugin.mobs.diagnostics(target)) plugin.messages.send(player, "goals-entry", Messages.ph("goal", diagnostic))
    }

    private fun handleMarkers(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return
        if (!player.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(player, "no-admin-permission")
            return
        }
        val markers = plugin.mobs.markerDefinitions()
        if (args.size >= 2 && args[1].equals("give", ignoreCase = true)) {
            if (args.size != 3) {
                plugin.messages.send(player, "markers-usage")
                return
            }
            val marker = markers.firstOrNull { it.category.equals(args[2], ignoreCase = true) }
            if (marker == null) {
                plugin.messages.send(player, "marker-unknown")
                return
            }
            giveMarker(player, marker.material)
            plugin.messages.send(player, "marker-given", Messages.ph("block", marker.material.name),
                Messages.ph("category", marker.category))
            return
        }
        if (args.size != 1) {
            plugin.messages.send(player, "markers-usage")
            return
        }
        if (markers.isEmpty()) {
            plugin.messages.send(player, "markers-empty")
            return
        }
        plugin.messages.send(player, "markers-header")
        for (marker in markers) {
            val entry = plugin.messages.get("markers-entry",
                Messages.ph("block", marker.material.name),
                Messages.ph("category", marker.category),
                Messages.ph("description", marker.description))
                .clickEvent(ClickEvent.runCommand("/dungeon markers give ${marker.category}"))
                .hoverEvent(HoverEvent.showText(plugin.messages.bare("markers-hover",
                    Messages.ph("block", marker.material.name))))
            player.sendMessage(entry)
        }
    }

    private fun handleSummon(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return
        if (!player.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(player, "no-admin-permission")
            return
        }
        if (args.size == 2 && args[1].equals("clear", ignoreCase = true)) {
            val removed = plugin.mobs.removeTestingMobs()
            plugin.messages.send(player, "summon-cleared", Messages.ph("count", removed))
            return
        }
        if (args.size < 3 || args.size > 4) {
            plugin.messages.send(player, "summon-usage")
            return
        }
        val difficulty = args[2].toIntOrNull()
        if (difficulty == null) {
            plugin.messages.send(player, "invalid-difficulty")
            return
        }
        val theme = if (args.size == 4) args[3] else null
        val result = plugin.mobs.summonTestingGroup(player.location, args[1], difficulty, theme)
        when (result.status) {
            nl.riddernix.dungeonplugin.mob.DungeonMobManager.TestSummonStatus.SUCCESS ->
                plugin.messages.send(player, "summon-success", Messages.ph("count", result.count),
                    Messages.ph("category", args[1].lowercase(Locale.ROOT)), Messages.ph("difficulty", difficulty),
                    Messages.ph("theme", result.theme))
            nl.riddernix.dungeonplugin.mob.DungeonMobManager.TestSummonStatus.INVALID_CATEGORY ->
                plugin.messages.send(player, "summon-invalid-category")
            nl.riddernix.dungeonplugin.mob.DungeonMobManager.TestSummonStatus.INVALID_DIFFICULTY ->
                plugin.messages.send(player, "invalid-difficulty")
            nl.riddernix.dungeonplugin.mob.DungeonMobManager.TestSummonStatus.INVALID_THEME ->
                plugin.messages.send(player, "summon-invalid-theme", Messages.ph("theme", result.theme))
            nl.riddernix.dungeonplugin.mob.DungeonMobManager.TestSummonStatus.DISABLED_CATEGORY ->
                plugin.messages.send(player, "summon-disabled-category",
                    Messages.ph("category", args[1].lowercase(Locale.ROOT)), Messages.ph("difficulty", difficulty))
            nl.riddernix.dungeonplugin.mob.DungeonMobManager.TestSummonStatus.NO_CLEARANCE ->
                plugin.messages.send(player, "summon-no-clearance")
        }
    }

    /** The stuck-run escape hatch: forces the sealed door of the dungeon you stand in. */
    private fun handleDoor(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return
        if (!player.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(player, "no-admin-permission")
            return
        }
        if (args.size != 2 || !args[1].equals("open", ignoreCase = true)) {
            plugin.messages.send(player, "door-usage")
            return
        }
        val dungeon = plugin.rooms.dungeon(player.world)
        if (dungeon == null) {
            plugin.messages.send(player, "not-in-dungeon")
            return
        }
        when (plugin.doors.forceOpen(dungeon)) {
            nl.riddernix.dungeonplugin.door.DungeonDoorManager.ForceResult.OPENED ->
                plugin.messages.send(player, "door-opened-force")
            nl.riddernix.dungeonplugin.door.DungeonDoorManager.ForceResult.ALREADY_OPEN ->
                plugin.messages.send(player, "door-already-open")
            nl.riddernix.dungeonplugin.door.DungeonDoorManager.ForceResult.NO_DOOR ->
                plugin.messages.send(player, "door-none")
        }
    }

    /** Opens the sealed room gate the sender stands in; the gate stays open for the run. */
    private fun handleRoom(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return
        if (!player.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(player, "no-admin-permission")
            return
        }
        if (args.size != 2 || !args[1].equals("open", ignoreCase = true)) {
            plugin.messages.send(player, "room-usage")
            return
        }
        val dungeon = plugin.rooms.dungeon(player.world)
        if (dungeon == null) {
            plugin.messages.send(player, "not-in-dungeon")
            return
        }
        val room = plugin.rooms.room(player)
        if (room == null) {
            plugin.messages.send(player, "gate-none")
            return
        }
        when (plugin.gates.forceOpen(dungeon, room)) {
            nl.riddernix.dungeonplugin.door.DungeonRoomGateManager.GateResult.OPENED ->
                plugin.messages.send(player, "gate-forced")
            nl.riddernix.dungeonplugin.door.DungeonRoomGateManager.GateResult.NONE ->
                plugin.messages.send(player, "gate-none")
        }
    }

    private fun startPartyDungeon(leader: Player, party: DungeonParty, layout: DungeonLayout, difficulty: Int) {
        val roomPlan = plugin.normalRooms.plan(layout)
        if (roomPlan.hasRequiredPrefabFailures()) {
            plugin.logger.severe("Dungeon generation refused for seed ${layout.seed}: " +
                roomPlan.requiredPrefabFailures.joinToString(" | "))
            plugin.messages.send(leader, "required-prefab-failure")
            return
        }
        if (!building.add(leader.uniqueId)) {
            plugin.messages.send(leader, "already-building")
            return
        }
        val worldName = plugin.parties.worldNameFor(party)
        plugin.messages.send(leader, "creating", Messages.ph("world", worldName))
        val world = plugin.worlds.createFresh(worldName)
        if (world == null) {
            building.remove(leader.uniqueId)
            plugin.messages.send(leader, "delete-failed")
            return
        }
        world.setSpawnLocation(layout.spawnX, layout.spawnY, layout.spawnZ)
        plugin.parties.activateInstance(party, worldName)
        plugin.messages.send(leader, "party-started",
            Messages.ph("difficulty", difficulty), Messages.ph("seed", layout.seed))

        val style = GenerationStyle.fromConfig(plugin.config, plugin.logger)
        val corridorPlan = plugin.corridors.plan(layout, roomPlan)
        val budget = plugin.config.getInt("performance.blocks-per-tick", 60000)
        val operations = ArrayList<BuildOperation>(layout.buildVolumes(style, roomPlan.prefabRoomIds,
            corridorPlan.schematicTunnelIds))
        operations.addAll(roomPlan.operations)
        operations.addAll(corridorPlan.operations)
        DungeonLayoutBuilder.start(plugin, world, operations, budget,
            { percent -> sendProgress(leader, percent) },
            { result ->
                plugin.normalRooms.verifyGenerated(world, layout, roomPlan)
                DungeonMarkerScanner.start(plugin, world, listOf(layout), plugin.config, budget) { markers ->
                    building.remove(leader.uniqueId)
                    val instance = plugin.rooms.register(world, layout, mergeMarkers(markers, roomPlan.markers),
                        roomPlan.doorways, roomPlan.playableBounds, roomPlan.playerSpawns, roomPlan.bossSpawns,
                        roomPlan.traps, roomPlan.prefabFiles)
                    val entrance = instance.playerSpawnLocation() ?: world.spawnLocation
                    world.spawnLocation = entrance
                    val members = party.members.toList()
                    for (index in members.indices) {
                        val memberId = members[index]
                        val member = Bukkit.getPlayer(memberId)
                        if (member != null && member.isOnline) {
                            plugin.parties.markEntered(memberId, member.location)
                            plugin.worlds.enter(member, partyEntrance(entrance, index))
                            plugin.messages.send(member, "party-teleported")
                        }
                    }
                    plugin.parties.markRunStarted(party)
                    plugin.messages.send(leader, "built",
                        Messages.ph("blocks", result.blocksChanged),
                        Messages.ph("seconds", String.format(Locale.ROOT, "%.1f", result.elapsedMillis / 1000.0)))
                }
            })
    }

    private fun handleParty(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return
        if (args.size < 2) {
            // The party UI used to be reached through the chest menu; with
            // that menu retired, the bare command is its front door.
            plugin.partyMenu.open(player)
            return
        }
        when (args[1].lowercase(Locale.ROOT)) {
            "invite" -> handlePartyInvite(player, args)
            "accept" -> handlePartyAccept(player)
            "decline" -> handlePartyDecline(player)
            "leave" -> handlePartyLeave(player)
            "kick" -> handlePartyKick(player, args)
            "list" -> handlePartyList(player)
            "end" -> handlePartyEnd(player)
            else -> plugin.messages.send(player, "party-usage")
        }
    }

    private fun handlePartyInvite(leader: Player, args: Array<out String>) {
        if (args.size < 3) {
            plugin.messages.send(leader, "party-invite-usage")
            return
        }
        val invitee = Bukkit.getPlayerExact(args[2])
        if (invitee == null || !invitee.isOnline) {
            plugin.messages.send(leader, "player-not-found")
            return
        }
        when (plugin.parties.invite(leader, invitee)) {
            PartyManager.InviteResult.OK -> {
                plugin.messages.send(leader, "party-invited", Messages.ph("player", invitee.name))
                plugin.messages.send(invitee, "party-invite", Messages.ph("player", leader.name))
            }
            PartyManager.InviteResult.SELF -> plugin.messages.send(leader, "party-self-invite")
            PartyManager.InviteResult.NOT_LEADER -> plugin.messages.send(leader, "party-not-leader")
            PartyManager.InviteResult.ALREADY_IN_PARTY -> plugin.messages.send(leader, "party-already-member")
            PartyManager.InviteResult.FULL -> plugin.messages.send(leader, "party-full")
            PartyManager.InviteResult.EXPIRED -> throw IllegalStateException("Invite cannot expire while being created.")
        }
    }

    private fun handlePartyAccept(player: Player) {
        val result = plugin.parties.accept(player.uniqueId)
        when (result.result) {
            PartyManager.InviteResult.OK -> {
                plugin.messages.send(player, "party-joined")
                val leader = Bukkit.getPlayer(result.party!!.leader)
                if (leader != null && leader.isOnline) {
                    plugin.messages.send(leader, "party-member-joined", Messages.ph("player", player.name))
                }
            }
            PartyManager.InviteResult.EXPIRED -> plugin.messages.send(player, "party-invite-expired")
            PartyManager.InviteResult.ALREADY_IN_PARTY -> plugin.messages.send(player, "party-already-member")
            PartyManager.InviteResult.FULL -> plugin.messages.send(player, "party-full")
            else -> plugin.messages.send(player, "party-invite-expired")
        }
    }

    private fun handlePartyDecline(player: Player) {
        if (plugin.parties.decline(player.uniqueId)) {
            plugin.messages.send(player, "party-invite-declined")
        } else {
            plugin.messages.send(player, "party-invite-expired")
        }
    }

    private fun handlePartyLeave(player: Player) {
        plugin.parties.partyOf(player.uniqueId) ?: run {
            plugin.messages.send(player, "party-none")
            return
        }
        exitPartyInstance(player)
        val removal = plugin.parties.removeMember(player.uniqueId)!!
        plugin.messages.send(player, "party-left")
        if (removal.leadershipChanged()) {
            val newLeader = Bukkit.getPlayer(removal.newLeader!!)
            if (newLeader != null && newLeader.isOnline) {
                plugin.messages.send(newLeader, "party-new-leader")
            }
        }
    }

    private fun handlePartyKick(leader: Player, args: Array<out String>) {
        val party = plugin.parties.partyOf(leader.uniqueId)
        if (party == null) {
            plugin.messages.send(leader, "party-none")
            return
        }
        if (!party.isLeader(leader.uniqueId)) {
            plugin.messages.send(leader, "party-not-leader")
            return
        }
        if (args.size < 3) {
            plugin.messages.send(leader, "party-kick-usage")
            return
        }
        val targetId = party.members.firstOrNull { memberId ->
            args[2].equals(Bukkit.getOfflinePlayer(memberId).name, ignoreCase = true)
        }
        if (targetId == null) {
            plugin.messages.send(leader, "party-not-member")
            return
        }
        if (targetId == leader.uniqueId) {
            plugin.messages.send(leader, "party-kick-self")
            return
        }
        val target = Bukkit.getPlayer(targetId)
        if (target != null && target.isOnline) {
            exitPartyInstance(target)
        } else {
            plugin.parties.markExited(targetId)?.let(plugin.worlds::deleteWorld)
        }
        plugin.parties.removeMember(targetId)
        plugin.messages.send(leader, "party-kicked", Messages.ph("player", args[2]))
        if (target != null && target.isOnline) {
            plugin.messages.send(target, "party-kicked-notice")
        }
    }

    private fun handlePartyList(player: Player) {
        val party = plugin.parties.partyOf(player.uniqueId)
        if (party == null) {
            plugin.messages.send(player, "party-none")
            return
        }
        val members = party.members.joinToString(", ") { memberId ->
            Bukkit.getOfflinePlayer(memberId).name ?: "unknown"
        }.ifEmpty { "unknown" }
        val leaderName = Bukkit.getOfflinePlayer(party.leader).name
        plugin.messages.send(player, "party-list",
            Messages.ph("leader", leaderName ?: "unknown"),
            Messages.ph("members", members))
    }

    private fun handlePartyEnd(leader: Player) {
        if (plugin.completions.isCleaningUp(leader.uniqueId)) {
            plugin.messages.send(leader, "dungeon-cleaning-up")
            return
        }
        val party = plugin.parties.partyOf(leader.uniqueId)
        if (party == null) {
            plugin.messages.send(leader, "party-none")
            return
        }
        if (!party.isLeader(leader.uniqueId)) {
            plugin.messages.send(leader, "party-not-leader")
            return
        }
        val ended = plugin.parties.endInstance(party)
        if (ended == null) {
            plugin.messages.send(leader, "party-no-instance")
            return
        }
        plugin.worlds.deleteWorld(ended)
        plugin.messages.send(leader, "party-ended")
    }

    private fun handleRooms(sender: CommandSender) {
        if (!sender.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(sender, "no-admin-permission")
            return
        }
        val rooms = plugin.normalRooms.inspections()
        plugin.messages.send(sender, "rooms-header", Messages.ph("folder", plugin.normalRooms.folder().path))
        if (rooms.isEmpty()) {
            plugin.messages.send(sender, "rooms-empty")
        } else {
            val corridorOffsets = plugin.corridors.inspections()
                .filter { it.valid }
                .flatMap { it.markerVerticalOffsets }
                .distinct()
                .sorted()
            val corridorOffsetText = if (corridorOffsets.isEmpty()) "none"
            else corridorOffsets.joinToString(", ") { "y" + (if (it >= 0) "+" else "") + it }
            for (room in rooms) {
                plugin.messages.send(sender, "rooms-entry",
                    Messages.ph("file", room.fileName), Messages.ph("size", room.dimensions()),
                    Messages.ph("actual", room.actualDimensions), Messages.ph("trimmed", room.trimmedDimensions),
                    Messages.ph("marker-offsets", room.markerOffsets()),
                    Messages.ph("doorways", room.displayDoorwayGroups()),
                    Messages.ph("corridor-offsets", corridorOffsetText),
                    Messages.ph("corridor-offset-match", room.corridorOffsetCompatibility(corridorOffsets)),
                    Messages.ph("valid", if (room.valid) "valid" else "invalid"),
                    Messages.ph("type", room.displayType()),
                    Messages.ph("pattern", room.shape.configName()), Messages.ph("name-match", room.filenameMatch),
                    Messages.ph("markers", room.markers()), Messages.ph("special-markers", room.displaySpecialMarkers()),
                    Messages.ph("problems", room.displayProblems()))
            }
        }
        for (type in listOf(NormalRoomLibrary.PrefabType.NORMAL, NormalRoomLibrary.PrefabType.BRANCH)) {
            val missing = plugin.normalRooms.missingUsableShapes(type).map { it.configName() }
            plugin.messages.send(sender, "rooms-missing", Messages.ph("room-type", type.configName()),
                Messages.ph("shapes", if (missing.isEmpty()) "none" else missing.joinToString(", ")))
        }
        val unusable = plugin.normalRooms.unusablePrefabs()
        if (unusable.isNotEmpty()) {
            plugin.messages.send(sender, "rooms-unusable", Messages.ph("rooms", unusable.joinToString(", ")))
        }
    }

    private fun handleCorridors(sender: CommandSender) {
        if (!sender.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(sender, "no-admin-permission")
            return
        }
        val corridors = plugin.corridors.inspections()
        plugin.messages.send(sender, "corridors-header", Messages.ph("folder", plugin.corridors.folder().path))
        if (corridors.isEmpty()) {
            plugin.messages.send(sender, "corridors-empty")
            return
        }
        for (corridor in corridors) {
            plugin.messages.send(sender, "corridors-entry",
                Messages.ph("file", corridor.fileName), Messages.ph("stated", corridor.statedDimensions),
                Messages.ph("actual", corridor.actualDimensions), Messages.ph("trimmed", corridor.trimmedDimensions),
                Messages.ph("connectors", corridor.connectors),
                Messages.ph("marker-offsets", corridor.markerOffsets()),
                Messages.ph("valid", if (corridor.valid) "valid" else "invalid"),
                Messages.ph("problems", corridor.displayProblems()))
        }
    }

    /** The in-world skill tree panels: place, remove, list and render tests. */
    private fun handleSkills(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return
        if (!player.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(player, "no-admin-permission")
            return
        }
        when (if (args.size >= 2) args[1].lowercase(Locale.ROOT) else "") {
            "place" -> {
                // Both arguments are optional and either order reads
                // naturally: "place big" and "place warrior big" both work.
                var classId = "warrior"
                var variant = SkillPanelGeometry.Variant.STANDARD
                val coordinates = ArrayList<String>()
                for (index in 2 until args.size) {
                    when {
                        isVariantName(args[index]) -> variant = SkillPanelGeometry.Variant.parse(args[index])
                        isCoordinate(args[index]) -> coordinates.add(args[index])
                        else -> classId = args[index]
                    }
                }
                val where = readLocation(player, coordinates)
                if (where == null) {
                    plugin.messages.send(player, "location-usage")
                    return
                }
                if (plugin.skillPanels.place(where, classId, variant) != null) {
                    plugin.messages.send(player, "skills-placed",
                        Messages.ph("class", classId.lowercase(Locale.ROOT)),
                        Messages.ph("variant", variant.configName()))
                } else {
                    plugin.messages.send(player, "skills-unknown-class",
                        Messages.ph("class", classId),
                        Messages.ph("classes", plugin.skillTrees.classIds().joinToString(", ")))
                }
            }
            "move" -> {
                val coordinates = ArrayList<String>()
                for (index in 2 until args.size) {
                    if (isCoordinate(args[index])) coordinates.add(args[index])
                }
                val to = readLocation(player, coordinates)
                if (to == null) {
                    plugin.messages.send(player, "location-usage")
                    return
                }
                val moved = plugin.skillPanels.moveNearest(player.location, to)
                if (moved == null) {
                    plugin.messages.send(player, "skills-none-nearby", Messages.ph("radius",
                        plugin.config.getDouble("skill-panel.remove-radius", 5.0).toInt().toString()))
                } else {
                    plugin.messages.send(player, "skills-moved", Messages.ph("x", moved.blockX),
                        Messages.ph("y", moved.blockY), Messages.ph("z", moved.blockZ))
                }
            }
            "remove" -> {
                if (plugin.skillPanels.removeNearest(player.location)) {
                    plugin.messages.send(player, "skills-removed")
                } else {
                    plugin.messages.send(player, "skills-none-nearby", Messages.ph("radius",
                        plugin.config.getDouble("skill-panel.remove-radius", 5.0).toInt().toString()))
                }
            }
            "removeall" -> {
                val report = plugin.skillPanels.removeAll()
                val locations = report.locations
                    .map { "${it.world!!.name} ${it.blockX}, ${it.blockY}, ${it.blockZ}" }
                    .distinct()
                    .joinToString(" | ")
                    .ifEmpty { "none" }
                plugin.messages.send(player, "skills-removed-all",
                    Messages.ph("count", report.count), Messages.ph("locations", locations))
            }
            "points" -> {
                // The admin side of the point economy. With the class layer
                // enabled the pool is level-derived, so this stays inert.
                if (args.size < 5 || (!args[2].equals("give", ignoreCase = true) && !args[2].equals("take", ignoreCase = true))) {
                    plugin.messages.send(player, "skills-points-usage")
                    return
                }
                val target = Bukkit.getPlayerExact(args[3])
                val amount = args[4].toIntOrNull()
                if (amount == null) {
                    plugin.messages.send(player, "skills-points-usage")
                    return
                }
                if (target == null || !target.isOnline) {
                    plugin.messages.send(player, "player-not-found")
                    return
                }
                if (plugin.classes.enabled) {
                    plugin.messages.send(player, "skills-points-external")
                    return
                }
                val balance = if (args[2].equals("give", ignoreCase = true))
                    plugin.skillProgress.grantPoints(target, amount)
                else plugin.skillProgress.withdrawPoints(target, amount)
                plugin.messages.send(player, "skills-points-changed",
                    Messages.ph("player", target.name), Messages.ph("points", balance))
            }
            "reset" -> {
                // The admin door to the same reset the write path exposes:
                // nodes go, and every point ever paid for them comes back.
                val target = if (args.size >= 3) Bukkit.getPlayerExact(args[2]) else player
                if (target == null || !target.isOnline) {
                    plugin.messages.send(player, "player-not-found")
                    return
                }
                val scope = if (args.size >= 4) args[3].lowercase(Locale.ROOT) else null
                val classes: List<String>
                if (scope == null) {
                    classes = plugin.skillProgress.activeClass(target.uniqueId)?.let { listOf(it) } ?: emptyList()
                    if (classes.isEmpty()) {
                        plugin.messages.send(player, "skills-reset-no-class",
                            Messages.ph("player", target.name))
                        return
                    }
                } else if (scope == "all") {
                    classes = plugin.skillTrees.classIds()
                } else {
                    classes = listOf(scope)
                }
                var nodes = 0
                var refunded = 0
                for (classId in classes) {
                    val result = plugin.skillProgress.resetTree(target, classId)
                    if (result.status == SkillWriteStatus.NO_SUCH_CLASS) {
                        plugin.messages.send(player, "skills-unknown-class", Messages.ph("class", classId),
                            Messages.ph("classes", plugin.skillTrees.classIds().joinToString(", ")))
                        return
                    }
                    nodes += result.nodes.size
                    refunded += result.pointsChanged()
                }
                plugin.messages.send(player, "skills-reset", Messages.ph("player", target.name),
                    Messages.ph("nodes", nodes), Messages.ph("refunded", refunded),
                    Messages.ph("points", plugin.skillProgress.points(target.uniqueId)))
            }
            "test" -> {
                // Drives the three render states: unlock any node freely,
                // clear to start over.
                if (args.size >= 4 && args[2].equals("unlock", ignoreCase = true)) {
                    when (plugin.skillPanels.testUnlock(player, args[3])) {
                        nl.riddernix.dungeonplugin.skills.SkillPanelManager.TestResult.UNLOCKED ->
                            plugin.messages.send(player, "skills-test-unlocked", Messages.ph("node", args[3]))
                        nl.riddernix.dungeonplugin.skills.SkillPanelManager.TestResult.NO_PANEL ->
                            plugin.messages.send(player, "skills-test-no-panel")
                        nl.riddernix.dungeonplugin.skills.SkillPanelManager.TestResult.UNKNOWN_NODE ->
                            plugin.messages.send(player, "skills-test-unknown-node", Messages.ph("node", args[3]))
                    }
                } else if (args.size >= 3 && args[2].equals("clear", ignoreCase = true)) {
                    plugin.skillPanels.testClear(player)
                    plugin.messages.send(player, "skills-test-cleared")
                } else {
                    plugin.messages.send(player, "skills-test-usage")
                }
            }
            "list" -> {
                val skillPanels = plugin.skillPanels.list()
                if (skillPanels.isEmpty()) {
                    plugin.messages.send(player, "skills-list-empty")
                    return
                }
                plugin.messages.send(player, "skills-list-header")
                for (panel in skillPanels) {
                    plugin.messages.send(player, "skills-list-entry",
                        Messages.ph("id", panel.id),
                        Messages.ph("class", panel.classId),
                        Messages.ph("variant", panel.variant),
                        Messages.ph("world", panel.location.world!!.name),
                        Messages.ph("x", panel.location.blockX),
                        Messages.ph("y", panel.location.blockY),
                        Messages.ph("z", panel.location.blockZ))
                }
            }
            else -> plugin.messages.send(player, "skills-usage")
        }
    }

    /**
     * Tells in ten seconds where an event problem lives: `status` shows what
     * has fired and who is listening, `fire` sends one of each event, `query`
     * dumps what the query side answers for you right now.
     */
    private fun handleApi(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(sender, "no-admin-permission")
            return
        }
        when (if (args.size >= 2) args[1].lowercase(Locale.ROOT) else "status") {
            "status" -> {
                plugin.messages.send(sender, "api-header",
                    Messages.ph("version", "internal"),
                    Messages.ph("listeners", plugin.events.foreignListenerCount()))
                for (line in plugin.events.status()) {
                    sender.sendMessage(plugin.messages.bare("api-entry", Messages.ph("line", line)))
                }
            }
            "fire" -> {
                val player = asPlayer(sender) ?: return
                for (line in plugin.events.fireAll(player)) {
                    sender.sendMessage(plugin.messages.bare("api-entry", Messages.ph("line", line)))
                }
            }
            "query" -> {
                val player = asPlayer(sender) ?: return
                for (line in plugin.events.queryDump(plugin.queries, player)) {
                    sender.sendMessage(plugin.messages.bare("api-entry", Messages.ph("line", line)))
                }
            }
            else -> plugin.messages.send(sender, "api-usage")
        }
    }

    /** The fixed in-world difficulty selector: place, remove, and find them. */
    private fun handlePanel(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return
        if (!player.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(player, "no-admin-permission")
            return
        }
        when (if (args.size >= 2) args[1].lowercase(Locale.ROOT) else "") {
            "place" -> {
                val where = readLocation(player, args.toList().subList(minOf(2, args.size), args.size))
                if (where == null) {
                    plugin.messages.send(player, "location-usage")
                    return
                }
                plugin.panels.place(where)
                plugin.messages.send(player, "panel-placed")
            }
            "move" -> {
                val to = readLocation(player, args.toList().subList(minOf(2, args.size), args.size))
                if (to == null) {
                    plugin.messages.send(player, "location-usage")
                    return
                }
                val moved = plugin.panels.moveNearest(player.location, to)
                if (moved == null) {
                    plugin.messages.send(player, "panel-none-nearby", Messages.ph("radius",
                        plugin.config.getDouble("difficulty-panel.remove-radius", 5.0).toInt().toString()))
                } else {
                    plugin.messages.send(player, "panel-moved", Messages.ph("x", moved.blockX),
                        Messages.ph("y", moved.blockY), Messages.ph("z", moved.blockZ))
                }
            }
            "remove" -> {
                if (plugin.panels.removeNearest(player.location)) {
                    plugin.messages.send(player, "panel-removed")
                } else {
                    plugin.messages.send(player, "panel-none-nearby", Messages.ph("radius",
                        plugin.config.getDouble("difficulty-panel.remove-radius", 5.0).toInt().toString()))
                }
            }
            "removeall" -> {
                val report = plugin.panels.removeAll()
                val locations = report.locations
                    .map { "${it.world!!.name} ${it.blockX}, ${it.blockY}, ${it.blockZ}" }
                    .distinct()
                    .joinToString(" | ")
                    .ifEmpty { "none" }
                plugin.messages.send(player, "panel-removed-all",
                    Messages.ph("count", report.count), Messages.ph("locations", locations))
            }
            "list" -> {
                val panels = plugin.panels.list()
                if (panels.isEmpty()) {
                    plugin.messages.send(player, "panel-list-empty")
                    return
                }
                plugin.messages.send(player, "panel-list-header")
                for (panel in panels) {
                    plugin.messages.send(player, "panel-list-entry",
                        Messages.ph("id", panel.id),
                        Messages.ph("world", panel.location.world!!.name),
                        Messages.ph("x", panel.location.blockX),
                        Messages.ph("y", panel.location.blockY),
                        Messages.ph("z", panel.location.blockZ))
                }
            }
            else -> plugin.messages.send(player, "panel-usage")
        }
    }

    /** Plays a boss entrance where you stand, for tuning it without a dungeon. */
    private fun handleAnimate(sender: CommandSender, args: Array<out String>) {
        val player = asPlayer(sender) ?: return
        if (!player.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(player, "no-admin-permission")
            return
        }
        val preview = plugin.animations
        if (args.size >= 2 && args[1].equals("stop", ignoreCase = true)) {
            plugin.messages.send(player, if (preview.stop(player)) "animate-stopped" else "animate-none")
            return
        }
        if (args.size < 2) {
            plugin.messages.send(player, "animate-usage",
                Messages.ph("animations", preview.animationNames().joinToString(", ")))
            return
        }
        val result = preview.start(player, args[1], if (args.size >= 3) args[2] else null)
        when (result.status) {
            AnimationPreview.Status.SUCCESS -> {
                plugin.messages.send(player, "animate-started",
                    Messages.ph("animation", args[1].lowercase(Locale.ROOT)),
                    Messages.ph("theme", result.theme ?: "none"),
                    Messages.ph("seconds", String.format(Locale.ROOT, "%.1f", result.durationTicks / 20.0)))
                // Says what actually got created, so an empty screen can be
                // told apart from an animation that never built anything.
                plugin.messages.send(player, "animate-detail", Messages.ph("detail", result.detail ?: "none"))
            }
            AnimationPreview.Status.FAILED -> plugin.messages.send(player, "animate-failed")
            AnimationPreview.Status.UNKNOWN_ANIMATION -> plugin.messages.send(player, "animate-unknown",
                Messages.ph("animations", preview.animationNames().joinToString(", ")))
            AnimationPreview.Status.UNKNOWN_THEME -> plugin.messages.send(player, "animate-unknown-theme",
                Messages.ph("theme", result.theme ?: "none"))
            AnimationPreview.Status.INVALID_BOSS -> plugin.messages.send(player, "animate-invalid-boss",
                Messages.ph("theme", result.theme ?: "none"))
            AnimationPreview.Status.ALREADY_RUNNING -> plugin.messages.send(player, "animate-already-running")
        }
    }

    /** Reports which configured mob models the model engine actually has. */
    private fun handleModels(sender: CommandSender) {
        if (!sender.hasPermission("dungeonplugin.admin")) {
            plugin.messages.send(sender, "no-admin-permission")
            return
        }
        plugin.messages.send(sender, "models-header")
        for (line in plugin.models.diagnostics()) {
            sender.sendMessage(plugin.messages.bare("models-entry", Messages.ph("line", line)))
        }
    }

    private fun exitPartyInstance(player: Player) {
        if (!plugin.parties.isInside(player.uniqueId)) {
            return
        }
        plugin.worlds.exit(player)
        plugin.parties.markExited(player.uniqueId)?.let(plugin.worlds::deleteWorld)
    }

    private fun parseSeed(player: Player, raw: String): Long? {
        return raw.toLongOrNull() ?: run {
            plugin.messages.send(player, "invalid-seed")
            null
        }
    }

    private fun parseAmount(player: Player, raw: String): Int? {
        val maximum = maxOf(1, plugin.config.getInt("generation.multi.max-amount", 64))
        val amount = raw.toIntOrNull()
        if (amount == null || amount < 1 || amount > maximum) {
            plugin.messages.send(player, "invalid-amount", Messages.ph("maximum", maximum))
            return null
        }
        return amount
    }

    private fun startLayouts(player: Player, layouts: List<DungeonLayout>, onStarted: Runnable) {
        if (!building.add(player.uniqueId)) {
            plugin.messages.send(player, "already-building")
            return
        }

        val roomPlans = ArrayList<NormalRoomLibrary.RoomPlan>()
        for (layout in layouts) {
            val roomPlan = plugin.normalRooms.plan(layout)
            if (roomPlan.hasRequiredPrefabFailures()) {
                building.remove(player.uniqueId)
                plugin.logger.severe("Dungeon generation refused for seed ${layout.seed}: " +
                    roomPlan.requiredPrefabFailures.joinToString(" | "))
                plugin.messages.send(player, "required-prefab-failure")
                return
            }
            roomPlans.add(roomPlan)
        }

        val worldName = plugin.worlds.worldNameFor(player)
        plugin.messages.send(player, "creating", Messages.ph("world", worldName))
        val world = plugin.worlds.createFresh(worldName)
        if (world == null) {
            building.remove(player.uniqueId)
            plugin.messages.send(player, "delete-failed")
            return
        }

        val first = layouts.first()
        world.setSpawnLocation(first.spawnX, first.spawnY, first.spawnZ)
        onStarted.run()

        val style = GenerationStyle.fromConfig(plugin.config, plugin.logger)
        val volumes = ArrayList<BuildOperation>()
        for (index in layouts.indices) {
            val layout = layouts[index]
            val roomPlan = roomPlans[index]
            val corridorPlan = plugin.corridors.plan(layout, roomPlan)
            volumes.addAll(layout.buildVolumes(style, roomPlan.prefabRoomIds, corridorPlan.schematicTunnelIds))
            volumes.addAll(roomPlan.operations)
            volumes.addAll(corridorPlan.operations)
        }
        val budget = plugin.config.getInt("performance.blocks-per-tick", 60000)
        DungeonLayoutBuilder.start(plugin, world, volumes, budget,
            { percent -> sendProgress(player, percent) },
            { result ->
                for (index in layouts.indices) {
                    plugin.normalRooms.verifyGenerated(world, layouts[index], roomPlans[index])
                }
                DungeonMarkerScanner.start(plugin, world, layouts, plugin.config, budget) { markers ->
                    building.remove(player.uniqueId)
                    val firstLayout = layouts.first()
                    val firstPlan = roomPlans.first()
                    val preview = DungeonInstance(world, firstLayout, mergeMarkers(markers, firstPlan.markers),
                        firstPlan.doorways, firstPlan.playableBounds, firstPlan.playerSpawns, firstPlan.bossSpawns,
                        firstPlan.traps, firstPlan.prefabFiles)
                    preview.playerSpawnLocation()?.let { world.spawnLocation = it }
                    finish(player, world, result.blocksChanged, result.elapsedMillis)
                }
            })
    }

    private fun sendProgress(player: Player, percent: Int) {
        if (player.isOnline) {
            player.sendActionBar(plugin.messages.bare("building", Messages.ph("percent", percent)))
        }
    }

    private fun finish(player: Player, world: World, blocks: Long, elapsedMillis: Long) {
        if (!player.isOnline) {
            return
        }
        val seconds = String.format(Locale.ROOT, "%.1f", elapsedMillis / 1000.0)
        plugin.messages.send(player, "built",
            Messages.ph("blocks", blocks),
            Messages.ph("seconds", seconds))

        if (plugin.config.getBoolean("teleport.on-create", true)) {
            plugin.worlds.enter(player, world)
            plugin.messages.send(player, "teleported")
        }
    }

    private fun handleTeleport(sender: CommandSender) {
        val player = asPlayer(sender) ?: return
        val partyInstance = plugin.parties.instanceForMember(player.uniqueId)
        if (partyInstance != null) {
            val partyWorld = Bukkit.getWorld(partyInstance.worldName)
            if (partyWorld != null) {
                plugin.worlds.enter(player, partyWorld)
                plugin.parties.markEntered(player.uniqueId)
                plugin.messages.send(player, "teleported")
                return
            }
        }
        val world = Bukkit.getWorld(plugin.worlds.worldNameFor(player))
        if (world == null) {
            plugin.messages.send(player, "no-dungeon")
            return
        }
        plugin.worlds.enter(player, world)
        plugin.messages.send(player, "teleported")
    }

    private fun handleLeave(sender: CommandSender) {
        val player = asPlayer(sender) ?: return
        if (plugin.parties.isInside(player.uniqueId)) {
            exitPartyInstance(player)
            plugin.messages.send(player, "left")
            return
        }
        if (!plugin.worlds.isDungeonWorld(player.world)) {
            plugin.messages.send(player, "not-in-dungeon")
            return
        }
        plugin.worlds.exit(player)
        plugin.messages.send(player, "left")
    }

    private fun handleDelete(sender: CommandSender) {
        val player = asPlayer(sender) ?: return
        val worldName = plugin.worlds.worldNameFor(player)
        if (Bukkit.getWorld(worldName) == null &&
            !Bukkit.getWorldContainer().toPath().resolve(worldName).toFile().exists()) {
            plugin.messages.send(player, "no-dungeon")
            return
        }

        if (plugin.worlds.deleteWorld(worldName)) {
            plugin.messages.send(player, "deleted", Messages.ph("world", worldName))
        } else {
            plugin.messages.send(player, "delete-failed")
        }
    }

    private fun handleList(sender: CommandSender) {
        val worlds = plugin.worlds.loadedDungeonWorlds()
        if (worlds.isEmpty()) {
            plugin.messages.send(sender, "list-empty")
            return
        }
        plugin.messages.send(sender, "list-header")
        for (world in worlds) {
            sender.sendMessage(plugin.messages.bare("list-entry",
                Messages.ph("world", world.name),
                Messages.ph("players", world.players.size)))
        }
    }

    private fun handleReload(sender: CommandSender) {
        plugin.reloadEverything()
        plugin.messages.send(sender, "reloaded")
    }

    // ------------------------------------------------------------------

    private fun asPlayer(sender: CommandSender): Player? {
        if (sender is Player) {
            return sender
        }
        plugin.messages.send(sender, "players-only")
        return null
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val partial = args[0].lowercase(Locale.ROOT)
            return SUBCOMMANDS.filter { it.startsWith(partial) }
        }
        if (args.size == 2 && args[0].equals("generate", ignoreCase = true)) {
            return listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
        }
        if (args.size == 2 && args[0].equals("party", ignoreCase = true)) {
            return matching(args[1], listOf("invite", "accept", "decline", "leave", "kick", "list", "end"))
        }
        if (args.size == 2 && args[0].equals("npc", ignoreCase = true) && sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[1], listOf("spawn", "remove", "removeall"))
        }
        if (args.size == 2 && args[0].equals("markers", ignoreCase = true) && sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[1], listOf("give"))
        }
        if (args.size == 2 && args[0].equals("api", ignoreCase = true) && sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[1], listOf("status", "fire", "query"))
        }
        if (args.size == 2 && args[0].equals("skills", ignoreCase = true) && sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[1], listOf("place", "move", "remove", "removeall", "list", "test", "points", "reset"))
        }
        // Suggests the relative form, which is both the likely intent and a
        // reminder that coordinates are accepted here at all.
        if (args[0].equals("skills", ignoreCase = true) && args[1].equals("move", ignoreCase = true) &&
            args.size <= 5 && sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[args.size - 1], listOf("~"))
        }
        if (args.size == 3 && args[0].equals("skills", ignoreCase = true) && args[1].equals("points", ignoreCase = true) &&
            sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[2], listOf("give", "take"))
        }
        if (args.size == 4 && args[0].equals("skills", ignoreCase = true) && args[1].equals("points", ignoreCase = true) &&
            sender.hasPermission("dungeonplugin.admin")) {
            return Bukkit.getOnlinePlayers().map { it.name }
                .filter { it.lowercase(Locale.ROOT).startsWith(args[3].lowercase(Locale.ROOT)) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
        if (args.size == 3 && args[0].equals("skills", ignoreCase = true) && args[1].equals("place", ignoreCase = true) &&
            sender.hasPermission("dungeonplugin.admin")) {
            val options = ArrayList(plugin.skillTrees.classIds())
            options.addAll(listOf("standard", "big"))
            return matching(args[2], options)
        }
        if (args.size in 4..7 && args[0].equals("skills", ignoreCase = true) &&
            args[1].equals("place", ignoreCase = true) && sender.hasPermission("dungeonplugin.admin")) {
            val options = arrayListOf("~")
            if (args.size == 4) options.addAll(listOf("standard", "big"))
            return matching(args[args.size - 1], options)
        }
        if (args.size == 3 && args[0].equals("skills", ignoreCase = true) && args[1].equals("reset", ignoreCase = true) &&
            sender.hasPermission("dungeonplugin.admin")) {
            return Bukkit.getOnlinePlayers().map { it.name }
                .filter { it.lowercase(Locale.ROOT).startsWith(args[2].lowercase(Locale.ROOT)) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
        if (args.size == 4 && args[0].equals("skills", ignoreCase = true) && args[1].equals("reset", ignoreCase = true) &&
            sender.hasPermission("dungeonplugin.admin")) {
            val options = ArrayList(plugin.skillTrees.classIds())
            options.add("all")
            return matching(args[3], options)
        }
        if (args.size == 3 && args[0].equals("skills", ignoreCase = true) && args[1].equals("test", ignoreCase = true) &&
            sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[2], listOf("unlock", "clear"))
        }
        if (args.size == 4 && args[0].equals("skills", ignoreCase = true) && args[1].equals("test", ignoreCase = true) &&
            args[2].equals("unlock", ignoreCase = true) && sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[3], plugin.skillPanels.allNodeIds())
        }
        if (args.size == 2 && args[0].equals("panel", ignoreCase = true) && sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[1], listOf("place", "move", "remove", "removeall", "list"))
        }
        if (args[0].equals("panel", ignoreCase = true) && args.size in 3..6 &&
            (args[1].equals("place", ignoreCase = true) || args[1].equals("move", ignoreCase = true)) &&
            sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[args.size - 1], listOf("~"))
        }
        if (args.size == 2 && args[0].equals("animate", ignoreCase = true) && sender.hasPermission("dungeonplugin.admin")) {
            val options = ArrayList(plugin.animations.animationNames())
            options.add("stop")
            return matching(args[1], options)
        }
        if (args.size == 3 && args[0].equals("animate", ignoreCase = true) && !args[1].equals("stop", ignoreCase = true) &&
            sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[2], plugin.animations.themes())
        }
        if (args.size == 3 && args[0].equals("markers", ignoreCase = true) && args[1].equals("give", ignoreCase = true) &&
            sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[2], plugin.mobs.markerCategories())
        }
        if (args.size == 2 && args[0].equals("door", ignoreCase = true) && sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[1], listOf("open"))
        }
        if (args.size == 2 && args[0].equals("room", ignoreCase = true) && sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[1], listOf("open"))
        }
        if (args.size == 2 && args[0].equals("summon", ignoreCase = true) && sender.hasPermission("dungeonplugin.admin")) {
            val options = ArrayList(plugin.mobs.combatCategories())
            options.add("clear")
            return matching(args[1], options)
        }
        if (args.size == 3 && args[0].equals("summon", ignoreCase = true) && !args[1].equals("clear", ignoreCase = true) &&
            sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[2], listOf("1", "2", "3", "4", "5", "6", "7", "8", "9"))
        }
        if (args.size == 4 && args[0].equals("summon", ignoreCase = true) && sender.hasPermission("dungeonplugin.admin")) {
            return matching(args[3], plugin.mobs.themes())
        }
        if (args.size == 3 && args[0].equals("party", ignoreCase = true) && args[1].equals("invite", ignoreCase = true)) {
            return Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.lowercase(Locale.ROOT).startsWith(args[2].lowercase(Locale.ROOT)) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
        if (args.size == 3 && args[0].equals("party", ignoreCase = true) && args[1].equals("kick", ignoreCase = true) &&
            sender is Player) {
            val party = plugin.parties.partyOf(sender.uniqueId) ?: return emptyList()
            return party.members
                .filter { it != sender.uniqueId }
                .mapNotNull { Bukkit.getOfflinePlayer(it).name }
                .filter { it.lowercase(Locale.ROOT).startsWith(args[2].lowercase(Locale.ROOT)) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
        return emptyList()
    }

    enum class StartStatus {
        READY,
        NOT_LEADER,
        INSTANCE_ACTIVE,
        BUILDING
    }

    companion object {
        private val SUBCOMMANDS = listOf("test", "generate", "compare", "rooms", "corridors", "models", "animate",
            "panel", "skills", "api", "party", "start", "settings", "npc", "goals", "markers", "summon", "door",
            "room", "tp", "leave", "delete", "list", "reload")

        /**
         * Reads `x y z` and an optional yaw out of whatever tokens are left.
         *
         * Accepts vanilla's `~` and `~offset` relative form, so a spot can be
         * nudged without doing the arithmetic. Anything missing falls back to
         * where the player stands, which is what the commands did before
         * coordinates existed.
         *
         * @return the location, or null when a token was meant as a
         *         coordinate but is not a number
         */
        private fun readLocation(player: Player, tokens: List<String>): Location? {
            if (tokens.isEmpty()) {
                return player.location
            }
            if (tokens.size != 3 && tokens.size != 4) {
                return null
            }
            val origin = player.location
            val base = doubleArrayOf(origin.x, origin.y, origin.z, origin.yaw.toDouble())
            val out = DoubleArray(4)
            out[3] = origin.yaw.toDouble()
            for (index in tokens.indices) {
                val value = readCoordinate(tokens[index], base[index]) ?: return null
                out[index] = value
            }
            return Location(origin.world, out[0], out[1], out[2], out[3].toFloat(), 0.0F)
        }

        private fun readCoordinate(token: String, relativeTo: Double): Double? {
            return try {
                if (token.startsWith("~")) {
                    val offset = token.substring(1)
                    relativeTo + (if (offset.isEmpty()) 0.0 else offset.toDouble())
                } else token.toDouble()
            } catch (exception: NumberFormatException) {
                null
            }
        }

        /** A token the player meant as a coordinate rather than as a name. */
        private fun isCoordinate(raw: String): Boolean =
            raw.startsWith("~") || readCoordinate(raw, 0.0) != null

        private fun isVariantName(raw: String?): Boolean =
            raw != null && raw.lowercase(Locale.ROOT) in listOf("standard", "big", "large")

        private fun giveMarker(player: Player, material: Material) {
            for (remaining in player.inventory.addItem(ItemStack(material)).values) {
                player.world.dropItemNaturally(player.location, remaining)
            }
        }

        /** Packs layouts into a grid using their actual planned bounds plus padding. */
        private fun arrangeInGrid(layouts: List<DungeonLayout>, padding: Int): List<DungeonLayout> {
            var cellWidth = 0
            var cellDepth = 0
            for (layout in layouts) {
                cellWidth = maxOf(cellWidth, layout.bounds.sizeX())
                cellDepth = maxOf(cellDepth, layout.bounds.sizeZ())
            }
            cellWidth += padding
            cellDepth += padding
            val columns = ceil(sqrt(layouts.size.toDouble())).toInt()
            val result = ArrayList<DungeonLayout>()
            for (index in layouts.indices) {
                val layout = layouts[index]
                val column = index % columns
                val row = index / columns
                result.add(layout.translate(column * cellWidth - layout.bounds.minX, 0,
                    row * cellDepth - layout.bounds.minZ))
            }
            return result
        }

        private fun mergeMarkers(
            scanned: Map<String, List<DungeonMarker>>,
            prefab: Map<String, List<DungeonMarker>>
        ): Map<String, List<DungeonMarker>> {
            val merged = HashMap(scanned)
            prefab.forEach { (roomId, markers) -> merged[roomId] = markers }
            return merged.toMap()
        }

        private fun finishSeconds(elapsedMillis: Long): String =
            String.format(Locale.ROOT, "%.1f", elapsedMillis / 1000.0)

        /** Uses nearby walkable floor tiles so a party does not arrive as one entity stack. */
        private fun partyEntrance(base: Location, index: Int): Location {
            val offsets = arrayOf(intArrayOf(0, 0), intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1),
                intArrayOf(0, -1), intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
            val offset = offsets[index % offsets.size]
            val candidate = base.clone().add(offset[0].toDouble(), 0.0, offset[1].toDouble())
            val world = candidate.world
            if (world != null &&
                world.getBlockAt(candidate.blockX, candidate.blockY - 1, candidate.blockZ).type.isSolid &&
                world.getBlockAt(candidate.blockX, candidate.blockY, candidate.blockZ).isPassable &&
                world.getBlockAt(candidate.blockX, candidate.blockY + 1, candidate.blockZ).isPassable) {
                return candidate
            }
            return base
        }

        /** Block count of a hollow n x n x n shell. */
        private fun shellBlockCount(n: Int): Long {
            val full = n.toLong() * n * n
            val inner = (n - 2).toLong() * (n - 2) * (n - 2)
            return full - inner
        }

        private fun matching(partial: String, candidates: List<String>): List<String> {
            val normalized = partial.lowercase(Locale.ROOT)
            return candidates.filter { it.lowercase(Locale.ROOT).startsWith(normalized) }
        }
    }
}
