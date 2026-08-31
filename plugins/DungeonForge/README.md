# DungeonForge

A Paper plugin for procedurally generated RPG dungeons. The current prototype
creates an empty void world, plans a seeded multi-level dungeon, builds
colour-coded placeholder rooms and corridors, then lets you inspect the layout.

## Opening it in IntelliJ

1. Install the **Minecraft Development** plugin once from the Marketplace.
2. Open the `DungeonForge` folder containing `build.gradle.kts`.
3. Import the Gradle project. The first run downloads Paper API and a JDK 25.
4. Run `./gradlew build`; the jar is written to `build/libs/DungeonForge-<version>.jar`.

## Java 25

Minecraft 26.x runs on **Java 25**. `settings.gradle.kts` includes the foojay
toolchain resolver, so Gradle downloads that JDK when necessary.

## Running it

Run `./gradlew runServer` to start a Paper server in `run/` with the plugin
installed. Set `eula=true` in `run/eula.txt` once before starting it again.

For a separate server, create a gitignored `local.properties` file:

```properties
serverPluginsDir=C:/path/to/your/server/plugins
```

`./gradlew build` then copies the jar there automatically.

## Commands

Permission for every command: `dungeonforge.use` (default: op).

| Command | What it does |
|---|---|
| `/dungeon test` | Creates a fresh void world, builds the test room, and teleports you in. |
| `/dungeon` | Opens the difficulty-selection menu. |
| `/dungeon generate <1-9> [amount] [seed]` | Builds one or more placeholder dungeons in a padded grid. |
| `/dungeon compare [seed]` | Builds all nine difficulties in a padded 3 by 3 comparison layout. |
| `/dungeon rooms` | Admin-only: reports loaded normal and branch room schematics, their door patterns, markers, missing shapes, and validation problems. |
| `/dungeon models` | Admin-only: reports the detected model engine and whether each configured model exists. |
| `/dungeon animate <animation> [theme]` | Admin-only: plays a boss entrance where you stand, using that theme's real boss, scale and duration. |
| `/dungeon animate stop` | Admin-only: removes your preview immediately. |
| `/dungeon party invite <player>` | Creates a party if needed and sends a 60-second invite with clickable accept/decline buttons. |
| `/dungeon party accept` | Joins the pending party invitation. |
| `/dungeon party decline` | Declines the pending party invitation. |
| `/dungeon party leave` | Leaves the party; leadership transfers when necessary. |
| `/dungeon party kick <player>` | Leader-only removal from the party. |
| `/dungeon party list` | Shows the leader and party members. |
| `/dungeon party end` | Leader-only evacuation and deletion of the party instance. |
| `/dungeon start <1-9> [seed]` | Leader-only party dungeon generation. |
| `/dungeon panel place [x y z [yaw]]` | Admin-only: puts a fixed in-world difficulty panel at your spot, or at the coordinates given. `~` means where you stand, so `~ ~5 ~` is five blocks up. |
| `/dungeon panel move [x y z [yaw]]` | Admin-only: moves the nearest panel, keeping its id. |
| `/dungeon panel remove` | Admin-only: removes the nearest panel. |
| `/dungeon panel removeall` | Admin-only: removes every panel and any orphaned panel entity. |
| `/dungeon panel list` | Admin-only: lists every placed panel with its location. |
| `/dungeon skills place [class] [standard\|big] [x y z [yaw]]` | Admin-only: places a skill panel at your spot or at the coordinates given. |
| `/dungeon skills move [x y z [yaw]]` | Admin-only: moves the nearest skill panel, keeping its class and variant. |
| `/dungeon skills reset [player] [class\|all]` | Admin-only: clears a tree and refunds every point ever paid into it. Defaults to you and your active class. |
| `/dungeon skills points give\|take <player> <amount>` | Admin-only: adjusts a player's available skill points. |
| `/dungeon npc spawn` | Admin-only: creates a persistent Dungeon Lord where you stand. |
| `/dungeon npc remove` | Admin-only: removes the nearest Dungeon Lord. |
| `/dungeon door open` | Admin-only: forces the sealed door of the dungeon you stand in, for a stuck run. |
| `/dungeon tp` | Teleports to your existing dungeon. |
| `/dungeon leave` | Returns you to where you came from. |
| `/dungeon delete` | Deletes your dungeon world and its folder on disk. |
| `/dungeon list` | Shows all loaded dungeon worlds. |
| `/dungeon reload` | Reloads `config.yml` without restarting the server. |

Aliases: `/dg` and `/df`. Every player gets their own world,
`dungeon_<playername>`, so multiple players can test simultaneously.

## Configuration

Everything in `config.yml` is live-editable: edit, save, then run
`/dungeon reload`. `/dungeon test` always builds the configurable hollow box.

`/dungeon generate` uses a deterministic seed. Its syntax is
`/dungeon generate <difficulty> [amount] [seed]`: omit both optional values
for one random layout, or use an amount to build a grid. A multi-generation
derives and prints a unique seed and entrance coordinate for every layout, so
each can be reproduced individually. `/dungeon compare` uses the same seed for
every difficulty, so a single generated dungeon can be compared directly with
its matching difficulty in the comparison world.

The `generation` section controls room counts, the fixed entrance position and
size, normal-room size ranges, corridor dimensions, branching, comparison
padding, and placeholder materials. The room count includes the entrance and
final boss room.

### Placeholder layout algorithm

The entrance box is always placed at `generation.entrance.origin` with exactly
the configured dimensions. The generator selects one seeded horizontal
direction and places the critical path sequentially from entrance to boss.
`generation.branching.branch-frequency` converts part of the room budget into
optional side rooms: `0.0` is one straight critical path, while higher values
produce more branches without breaking the entrance-to-boss route.
`generation.branching.long-branch-max-length` limits the one branch allowed to
be longer, while `generation.branching.short-branch-max-length` limits every
other detour. The entrance, its first successor, and the room before the boss
cannot start branches. The generator rejects any candidate room or corridor
that intersects an earlier room or corridor.

`generation.critical-path.min-rooms-before-turn` and
`generation.critical-path.max-rooms-before-turn` control how long the main
path keeps a heading before it may turn left or right. This makes the overall
dungeon shape wander while each individual corridor remains direct. Branch
rooms retain their heading and must end farther from the entrance than their
parent; they may turn left or right but cannot reverse.

### Composed difficulties

A difficulty listed under `generation.composition` abandons the random layout
and marker mix above for an authored sequence. Its `path` names one room role
per main-path room between the spawn room and the boss arena, in walking
order; the room count follows from the list, and its `rooms-per-difficulty`
entry is ignored. What a role spawns is defined once under `mobs.room-roles`
as category-to-group-count pairs (an empty role spawns nothing - the pause is
deliberate). Group sizes and stats still come from
`mobs.difficulties.<d>.categories`. A composed path turns like any other under
`generation.critical-path.*`, so corner and junction rooms appear along it;
set `allow-turns: false` to force one straight run instead.

Roled rooms take no markers from the per-difficulty mix. Instead, a marker of
the matching colour in the chosen prefab anchors a group at its own spot, and
any shortfall gets anchors found on clear floor at runtime, deterministic per
seed.

`key-branch` makes the detour mandatory: the corridor leaving path room
`door-after` is sealed by a barrier until the **guardian** at the end of the
branch dies. The branch leaves path room `from` sideways and its `rooms` are
walked in order, so a `parkour` room sits between the main path and the key.
The key is party state granted the moment the guardian dies - it cannot be
dropped, lost on death, or carried out - and the door opens by itself right
after. The barrier, label, sounds and failsafe live under `door`. If the
guardian stops existing without granting the key, the watchdog revives it up
to `door.watchdog.max-revivals` times and then opens the door rather than
strand the run; `/dungeon door open` is the manual override. Difficulty 1
ships composed: swarm, pack, rest, swarm-champion, approach, with the key
branch off the rest room.

A prefab file is named `(normal|branch)[_role][_shape][_number]`, and both
middle parts are optional: `branch_straight` is a generic branch room,
`branch_parkour` and `branch_parkour_straight` both bind to the parkour role,
and `normal_rest_tjunction` to the rest role. Naming a shape only declares
it - DungeonForge checks it against the doorways it actually finds, and a
file that omits it is validated purely on its markers.

A roled slot prefers prefabs with its token and falls back to the generic
pool, so a role you have not authored yet still builds. When that fallback
happens it is logged as `severe`, naming the role, the doorways the slot
needed, and every rotation each roled file offers - a composed room quietly
wearing a generic prefab is otherwise easy to miss. Roled prefabs never serve
other slots. The guardian room takes one `BLACK_WOOL` marker for its spot.

Each role pool plans at its own footprint: a parkour room larger than the
ordinary branch rooms reserves its real size, without stretching any other
reservation. Within one pool, keep sizes identical - mixed sizes inside a
pool still leave a gap between corridor and wall, which the doorway audit
reports as severe.

### Hand-built regular rooms

A starter set of schematics ships inside the jar and is unpacked into
`plugins/DungeonForge/rooms/` and `corridors/` on first start. That only
happens while a folder holds no `.schem` files at all: once a server has its
own, those are the truth and the bundled copies stay in the jar. Dropping a
new room into the folder still needs no rebuild, and deleting every file
restores the shipped set on the next start.

**NORMAL** and **BRANCH** rooms use files from the live server folder
`plugins/DungeonForge/rooms/`. The filename prefix is part of the contract:
`normal_*` files can only fill normal slots, while `branch_*` files can only
fill branch slots. `/dungeon test` is intentionally only the standalone stone
test box; test prefabs with `/dungeon generate <difficulty>` or a party dungeon
instead.

`generation.prefab-room.size` supplies a fallback envelope when a matching
prefab is unavailable. Normal and branch files may use any dimensions;
DungeonForge trims empty export padding, plans against the largest loaded
footprint, and places each selected room at its real size. Spawn and boss rooms
keep their own dimensions.

Use `RED_WOOL` as a doorway declaration: place either one centred block or one
contiguous centred strip on the outer wall above each real doorway. DungeonForge
finds the suitably large air opening below that marker and vertically aligns
the flat corridor platform with it. This supports raised, decorative doorway
builds as well as a simple marker immediately above an opening. The marker is
replaced with `generation.rooms.markers.replacements.doorway` during placement.

`GREEN_WOOL` follows the red convention exactly but pins the room's
orientation: the generator only uses rotations where players arrive through
that opening, which is how a parkour room guarantees its start is the start.
At most one green doorway per room, it needs at least one red doorway as the
exit, and a wall cannot mix red and green - `/dungeon rooms` reports all
three mistakes and marks the file invalid.

`YELLOW_WOOL` marks the **floor** of a trap, and only the floor: each marked
block is swapped for a copy of the floor around it at build time, and any
pressure plate in the room becomes the trigger.

What collapses is derived from the marker. Downwards, everything to the
room's foundation, so the hole opens into the void. Upwards, straight up
through anything that is not air, stopping at the first gap - so a pillar, a
step or a plate standing on a marked block comes down with it without being
marked, while a platform floating above the trap keeps its gap and stays put.
`trap.max-column-height` (default 8) caps how much of a tall pillar can be
dragged along, so one reaching the ceiling cannot take the ceiling with it.
Columns from several markers form one falling set, so overlapping structures
resolve to a single collapse. `/dungeon rooms` reports `trap-blocks=<n>` per
file: the number that will actually vanish, worked out with the same rule the
trap uses, so an over-eager column shows up before testing in game.

Stepping on a plate starts two timers. The floor goes after
`trap.drop-delay-seconds` (0.4), and `trap.kill-delay-seconds` (3) later the
death lands, so a victim falls for a moment first. Whoever stood on the
floor when it went is remembered and killed wherever they have fallen to,
and anyone who walks in afterwards is caught at the same moment - in a void
world a fall has no ending of its own, so it is never left to do the
killing. The floor is rebuilt exactly as authored
`trap.floor-return-seconds` (4) after the drop, and the trap re-arms after the
rebuild; while it is open, further plate presses do nothing and cannot
stretch the timer. Entities hovering in the hole at rebuild time are lifted
onto the floor rather than sealed in. Trap deaths cost the walk back from
the entrance and nothing else - the door key is party state and survives
death. A room with yellow markers but no plate loads with a warning and
never fires.

Prefab selection is exact: after trying every right-angle rotation, a room is
eligible only when its complete set of doorway faces exactly matches the
corridors planned for that slot and its filename prefix matches the slot type.
No extra doorway is sealed. If no exact room is available, DungeonForge logs
the rejected candidates and uses a procedural stone fallback so the dungeon can
still be inspected.

`LIME_WOOL`, `ORANGE_WOOL`, `MAGENTA_WOOL`, and `BLACK_WOOL` are consumed as
swarm, pack, champion, and guardian spawn markers respectively. One marker
produces a group, not one mob. `PURPLE_WOOL` in a room file is a legacy marker:
it is reported, never interpreted, and replaced with air - purple belongs to
the corridor connector convention.
After copying or changing room files, run `/dungeon reload` and then
`/dungeon rooms`; the latter reports exactly why a file was rejected.

## Parties and instances

Players can belong to one party at a time. Party worlds use a party UUID rather
than a player name, so several parties can run independent instances at once.
Only the leader can invite, kick, end, or start a party dungeon. If the leader
leaves, leadership transfers to the longest-standing remaining member.

`/dungeon start <difficulty> [seed]` is separate from `/dungeon generate`:
the former builds one shared party instance and teleports online members in
together; the latter remains a solo layout-testing command. A disconnected
occupant is restored to their saved dungeon location on rejoin. If every
occupant is offline for `party.offline-instance-timeout-seconds`, the instance
is cleaned up.

The current prototype is deliberately flat. The layout and tunnel models retain
the information needed for a later vertical extension, but no vertical setting
is exposed or used yet.

The planner completes this collision validation before the builder changes any
blocks. The builder fills all room and corridor shells first, then carves the
interiors and tunnel passages across ticks using
`performance.blocks-per-tick` and `setBlockData(data, false)`.

Any valid vanilla gamerule may go under `world.gamerules`. Unknown names log a
warning instead of crashing.

## Custom mob models

Vanilla mob models cannot be animated from a plugin: the client owns them, and
the server can only trigger animations that already exist (arm swings, poses,
hurt flashes) or move the entity itself. A model engine works around that by
hiding the real mob and rendering an animated rig of display entities in its
place, which is what `DungeonMobManager`'s summoning sequence stops short of.

DungeonForge supports [BetterModel](https://hangar.papermc.io/toxicity188/BetterModel)
(free, Paper, Minecraft 1.21.4-26.1.x, Java 25). It is a `softdepend`: without
it installed, every mob keeps its vanilla appearance and nothing else changes.

The integration listens to the plugin's own public `DungeonMobSpawnEvent`
rather than reaching into the spawner, so the mob pipeline stays untouched.
That event now also carries the theme and spawn category of every mob, not
just bosses. Model names go under `models.themes.<theme>` in `config.yml`, with
`models.defaults` as the fallback; a blank value means "stay vanilla". Run
`/dungeon models` to see which of them the engine actually has loaded.

BetterModel generates and serves its own resource pack. If you already serve a
pack of your own, the two must be merged into one - a server can only push a
single pack.

## The difficulty panel

The chest-based difficulty menu is retired. In its place stands a fixed panel
in the world, built entirely from text display entities - no resource pack, no
client mod. `/dungeon panel place` installs one at your spot facing your way;
`/dungeon` and the Dungeon Lord now point players at the nearest panel, and the
bare `/dungeon party` opens the party menu directly.

Its entities are never written into the world save. They respawn from
`panels.yml` whenever their chunk loads, which makes duplication impossible and
means a crash leaves nothing behind; a startup sweep removes tagged strays from
older sessions all the same. Everything - colours, sizes, heights, spacing,
fade - lives under `difficulty-panel` in config.yml. The text uses Minecraft's
own font, the same one chat renders with, and every string is MiniMessage, so
the DungeonForge chat gradient can be used on the heading directly.

Interaction runs through three Interaction entities - left arrow, right arrow,
enter button - with hitboxes deliberately larger than the text they cover
(`difficulty-panel.hitboxes`), reacting to both left and right click. The
furniture is one shared set of entities; the number row is per viewer. A player
who has never clicked sees the shared default row on difficulty 1. Their first
click spawns a personal row of nine numbers, invisible by default and shown
only to them (`setVisibleByDefault(false)` + `Player#showEntity`) while the
shared row is hidden for them alone - nine text displays per engaged player.
Scrolling slides the row across using teleport and transformation
interpolation, stops at 1 and 9 rather than wrapping (a wrap would make 9-to-1
a one-click accident), and each click is keyed by player UUID, so simultaneous
users cannot interfere. The selection persists for the session and clears on
logout; the rendered row follows the player in and out of
`activation-radius`, and is cleaned up on death, world change and logout.
Enter starts the run through the same path the chest menu used, with refusals
reported in chat.

The whole panel is drawn in the gold of the chat prefix: gradient heading and
button label, light-gold numbers, darker-gold arrows. The text floats against
the world without a backing plate; only the Enter button carries one - a dark
square-cornered fill inside a gold border. Both plate quads render the label's
own text dyed to their colour, so their width tracks the label at any length
and the padding stays even by construction. A text-display background is one
flat rectangle drawn by the client, so it cannot hold a gradient (the gradient
lives in the label) and cannot be rounded at all.

The button's plate is therefore not a text background but block displays: a
dark fill layer over a gold border layer, each three overlapping bars so every
corner steps inwards twice. All of it is measured in font pixels from the
label's width through the default font's advance table, so the shape tracks
whatever the label is set to and the border is an exactly even ring - 0.07
blocks per side whether the label reads "Go" or "Start the Dungeon Run Now".
Block displays hold a block rather than a colour, so concrete stands in for
flat fills and a press swaps the fill bars to a brighter block for a few
ticks; hover cannot be detected server-side, so the button responds to being
used instead.

An earlier version built the plate from text displays rendering the label
itself, dyed to their own background colour, as a width reference. That was
only invisible by coincidence: at range the enlarged border plate's gold copy
of the label showed through and the text appeared doubled. Block displays carry
no text, which removes the failure mode rather than tuning around it.

At distance the panel does not degrade, it disappears: display and interaction
entities stop being sent past the tracking range, scaled by
`difficulty-panel.view-range` and by each player's own Entity Distance video
setting. Nothing about it resizes, thins out or re-lays itself with distance.

## The skill tree panel (phase one)

`/dungeon skills place [class]` installs a fixed in-world skill tree, built
with the same machinery as the difficulty panel: non-persistent display
entities respawned from `skill-panels.yml` on chunk load, PDC-tagged under
their own keys so the two panels' orphan sweeps cannot touch each other's
entities. Nodes are circle glyphs from Minecraft's own font, connections are
thin block-display bars rotated in the panel plane, and the layout is a fan of
labelled branches around a root - polar positions authored per node in
`skills.yml`, which also carries each node's name, cost, description and
requirement edges. Those ids are the API contract for phase three: another
plugin sees a class id, node id and level, never what the node does.

Phase two makes nodes clickable. Every node carries an interaction hitbox
larger than its visible dot, but the hitbox only reports that the tree was
clicked: the node is chosen server-side as the one nearest the player's line
of aim, so overlapping boxes can never fight over a click. Clicking selects -
a ring on the node and a detail panel beside the tree with name, cost,
description and level - and selecting is deliberately not unlocking, which is
phase three.

Two variants exist. `standard` is about 7 x 5 blocks, draws its nodes as font
glyphs and takes every size from config; `big` is about 19 x 12 blocks (plus roughly 5 more for the detail
panel) with its sizes fixed in `SkillPanelGeometry` - spacing, scales, line
thickness, hitboxes, layer depths and the detail panel all baked in, ignoring
the config size keys. `/dungeon skills place [class] [standard|big]` accepts
either argument in either order, and the variant is stored with the panel so it
returns the same after a restart. Colours, blocks, glyphs and the state ladder
are shared by both. The layer depths scale with the variant deliberately: at
big sizes the bars are deeper, so the standard gaps would put node glyphs
inside a bar's front face and hide them close up.

The big variant is the full mockup: the tree in the upper two thirds, a class
carousel underneath (Warrior, Archer, Paladin, Mage - the difficulty panel's
recipe: centre large, neighbours faded, slide by interpolation, one position
per viewer), and the Info area left, Confirm right below that. Confirm commits
the viewed class as the player's active class; switching never touches what
was spent in other classes. Info is a standing display, not a button: the
selected node's details, or - with nothing selected - the viewed class,
whether it is active and how many of its skills are unlocked. skills.yml
carries a skills-version and is backed up and replaced on startup when the
plugin ships a newer tree design, mirroring config.yml. Classes and trees are
separate in skills.yml: all four classes currently share the `core` tree, so
giving them their own layouts later is data, not code. Connections form a DAG
rather than a tree - `any-of: true` marks nodes where paths rejoin and any one
unlocked prerequisite suffices - and edges may bend through `routes` corner
points, drawn as one bar per leg with only the plate ends trimmed.

The big variant draws each node as a raised plate rather than a glyph: a wide
base standing behind a smaller, thicker top, so it reads as a button with a
rim. Both plates face the viewer, which means the rim only shows when base and
top differ - identical blocks at identical brightness render as one flat
square - so the base is always a duller relative of the face. Connection lines
are trimmed back to the plate edge instead of running underneath it, and the
selection is a larger plate behind the node rather than a glyph ring, which
would otherwise land on top of a solid face. A player's own plates replace the
shared ones by hiding them, so no locked plate shows behind a gold one at an
angle.

Nodes and connections render in three states, tuned per state in config:
unlocked brightest, available (prerequisite unlocked) mid, locked dimmest.
The shared furniture draws the whole tree locked; everything brighter is a
per-viewer overlay, spawned invisible-by-default and shown only to its owner,
so two players at the same tree see their own progression on one structure.
Until real progression exists, `/dungeon skills test unlock <node>` and
`/dungeon skills test clear` drive a temporary in-memory state for checking
all three states render correctly.

## Boss entrance animations

A boss's `summoning.animation` replaces the default particle pulse with a
scripted entrance. `rift_tear` ships with the plugin and is wired to the Rift
Devourer: a tear of block displays opens in the arena floor, its shards orbit
and lean inward, and the boss is dragged up out of the ground.

Everything moving is a display entity. Each phase sets a target transformation
once together with an interpolation duration, and the client tweens between the
poses - that is what makes it smooth without a packet per frame, and it is as
far as animation goes without a model engine. The shards never move: they sit
at the tear's centre and are pushed onto their ring by the translation part of
their transformation, so orbiting is interpolated rather than teleported.

Two safety rules are built in. The boss is only buried and raised while the
sequence holds it invulnerable, and its gravity is switched off for the
duration, because a prefab arena can have nothing at all underneath its floor.
`SpawnAnimations` is the registry: add a class there to add an entrance.

Tune one without generating a dungeon:

```
/dungeon animate rift_tear
```

The stand-in is the theme's real boss entity at its configured scale, on the
theme's own summoning duration, so radius and rise depth can be judged against
the actual fight. It is tagged as a testing mob, so `/dungeon summon clear`
sweeps up a stray one, and every preview is removed on shutdown.


## Project layout

```
src/main/java/nl/riddernix/dungeonforge/
|- DungeonForgePlugin.java             startup, cleanup, command registration
|- command/DungeonCommand.java         all subcommands and tab completion
|- util/Messages.java                  MiniMessage text from config.yml
|- world/VoidChunkGenerator.java       generates nothing at all
|- world/VoidBiomeProvider.java        THE_VOID everywhere
|- world/DungeonWorldManager.java      creates, evacuates, and deletes worlds
|- panel/DifficultyPanelManager.java   the fixed in-world difficulty selector
|- fx/SpawnAnimations.java             registry of scripted boss entrances
|- fx/AnimationPreview.java            plays one entrance without a dungeon
|- fx/RiftTearAnimation.java           the Rift Devourer's rift-tear entrance
|- model/ModelIntegration.java         assigns custom models on mob spawn
|- model/BetterModelApplier.java       the only class touching BetterModel
|- build/BoxSpec.java                  config-to-data record
`- build/BoxBuilder.java               tick-spread test-room builder
```

`BoxBuilder` keeps a cursor and processes only
`performance.blocks-per-tick` positions on each tick. It uses
`setBlockData(data, false)` to skip physics updates. This is the same pattern
the future room-by-room procedural generator can use.

Worlds are disposable: autosaving is disabled, and on startup any unloaded
folder matching `world.prefix` is removed. Do not give a real world that
prefix.

## Next steps

1. Prefab loader: room-sized pieces with doorway and marker metadata.
2. Generation loop: match open doorways, validate collisions, and seal dead ends.
3. Marker pass: loot tables and mob spawns.
4. Party instances: enter, exit, and cleanup.

`DungeonWorldManager` remains the single entry point for world lifecycle work,
so future instance support can extend it without changing the command flow.
