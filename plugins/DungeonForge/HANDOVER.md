# DungeonForge — handover

Everything a new contributor needs to pick this project up. Assumes Java and
Bukkit knowledge, no knowledge of this plugin.

---

## 1. What it is

A Paper plugin that generates RPG dungeon instances. A player (or party leader)
picks a difficulty 1–9; the plugin creates a private void world, plans a seeded
layout, pastes hand-built room and corridor schematics into it, populates the
rooms with themed mobs, and runs the party through to a boss. When the boss
dies the run completes, players are returned, and the world is deleted.

Around that core sit several subsystems: custom
mob model support, boss entrance animations, a public API for a second plugin
that adds gameplay effects, and in-world UI panels built from display
entities (difficulty selector, skill tree).

Every player gets their own world named `dungeon_<playername>`; parties get
one shared instance. Dungeon worlds are **disposable**: auto-save off, deleted
on completion, and any unloaded folder starting with `world.prefix`
(default `dungeon_`) is purged at startup. **Never give a real world that
prefix.**

---

## 2. Technical setup

| | |
|---|---|
| Server | Paper **26.1.2** (`paperApiVersion=26.1.2.build.74-stable`, `apiVersion=26.1.2`) |
| Java | **25** (toolchain; Minecraft 26.x requires it) |
| Package | `nl.riddernix.dungeonforge` |
| Plugin version | `0.10.0` (`pluginVersion` in `gradle.properties`) |
| Build | Gradle, `xyz.jpenilla.run-paper` 3.0.2 |
| Dependencies | `compileOnly` WorldEdit `7.3.19`, BetterModel `io.github.toxicity188:bettermodel-bukkit-api:3.3.0`. Both `softdepend` |
| Command | `/dungeon` (aliases `/dg`, `/df`), permissions `dungeonforge.use` and `dungeonforge.admin`, both default op |

**Gradle runs on Java 21, compiles with Java 25.** Gradle 8.14.3 cannot run on
JDK 25, so `JAVA_HOME` must point at a JDK 21 while the toolchain resolves 25.
Both JDKs live in `.jdk-build-2/`. The working invocation:

```bash
JAVA_HOME=<repo>/.jdk-build-2/jdk-21.0.12+8 ./gradlew -p <repo> build -Porg.gradle.java.installations.paths=<repo>/.jdk-build-2/jdk-25.0.4+7
```

Always pass `-p <repo>`: without it Gradle uses the shell's working directory
and may build a different project.

`./gradlew runServer` starts a test server in `run/` (needs `eula=true` in
`run/eula.txt`). Put `serverPluginsDir=C:/path/to/server/plugins` in a
gitignored `local.properties` and `build` copies the jar there.

**WorldEdit is used for schematic decoding and encoding only.** DungeonForge
never uses a WorldEdit edit session — it reads a `Clipboard`, converts blocks
with `BukkitAdapter`, and places them itself through its own tick-spread
builder. Without WorldEdit the plugin still loads; prefabs are unavailable and
generation falls back to procedural stone rooms.

---

## 3. Config versioning — read before editing config.yml

`config.yml` carries `config-version` (currently **73**) which must match
`CONFIG_VERSION` in `DungeonForgePlugin`. On startup, if the installed file's
version is **lower**, the file is backed up to
`config.yml.v<n>.<timestamp>.bak` and replaced wholesale by the bundled one.

**Consequences you must respect:**

- Adding or changing any config key means bumping **both** numbers. Bump only
  the file and the migration never runs; bump only the code and it runs but
  ships nothing new.
- The check is `installed >= CONFIG_VERSION → skip`, so numbers must only go
  **up**. To roll a change back, ship the old content under a *higher* number.
- Migration replaces the whole file; user edits survive only in the `.bak`.

`skills.yml` has the identical mechanism: `skills-version` (currently **4**)
against `SKILLS_VERSION` in `SkillTreeLibrary`. This was added after a real
bug — the file was originally only written when absent, so a redesigned tree
shipped in the jar never reached servers that already had one, and it looked
like the redesign had never been done.

---

## 4. Systems

### 4.1 World generation

`DungeonWorldManager` owns world lifecycle. Worlds use `VoidChunkGenerator`
(every `generate*` empty, every `shouldGenerate*` false) and
`VoidBiomeProvider` (THE_VOID). `createFresh(name)` deletes any existing world
of that name first. `createOrLoadPlainWorld(name, spawnY)` is the exception: a
persistent void world with auto-save **on**, not registered as managed.
Currently unused - it existed for the removed schematic editor - but kept
because that distinction is easier to keep than to rebuild.

`DungeonLayoutGenerator` plans a layout from `(difficulty, seed)`, entirely
deterministic — same inputs always give the same dungeon. It places the
entrance at the fixed `generation.entrance.origin`, runs a critical path to
the boss room turning every 2–4 rooms
(`generation.critical-path.min/max-rooms-before-turn`), and converts part of
the budget into side branches (`branch-frequency` 0.55, one long branch up to
2 rooms, others 1). Room counts run 4 (difficulty 1) to 12 (difficulty 9),
including entrance and boss. Candidates that intersect existing geometry are
rejected; up to `max-placement-attempts` (600) tries.

`DungeonLayoutBuilder` places everything: a `BukkitRunnable` with a cursor
consuming `performance.blocks-per-tick` (60000) positions per tick, using
`setBlockData(data, false)` to skip physics. All world writing goes through
`BuildOperation` implementations — `BuildVolume` (a box) and
`BlockListOperation` (a sparse list, used for schematics).

### 4.2 Room and corridor schematics

Live folders: `plugins/DungeonForge/rooms/` and
`plugins/DungeonForge/corridors/`.

A starter set lives in `src/main/resources/rooms|corridors/` and is unpacked
by `extractBundledSchematics` on enable, **only into a folder with no
`.schem` in it**. The jar entries are enumerated rather than listed in code,
so adding a file to the resources is all it takes to ship it. WorldEdit is
still the reader — see §6 for why writing our own was assessed and declined.

**Filename convention** (`NormalRoomLibrary.declaration`, case-insensitive):

```
(normal|branch)[_<role>][_(straight|corner_r|corner_l|tjunction|cross|dead_end)][_<number>]
(spawn|boss)[_<number>]
```

Role and shape are both optional, so `branch_straight`, `branch_parkour` and
`branch_parkour_straight` all parse. A shape in the name is only a
declaration checked against the detected doorways; omit it and the file is
validated on its markers alone.

The prefix is a hard contract: `normal_*` can only fill NORMAL slots,
`branch_*` only BRANCH slots. The shape word is checked against the doorways
actually detected; a mismatch loads the file but logs a warning. Numbers let
you have variants (`normal_cross_2`).

**Loading** (`NormalRoomLibrary.load`): reads the clipboard, then for every
block decides marker or structure. Empty outer padding is trimmed — the room's
real footprint is its non-air content bounds, so authoring padding never
changes a room's size.

**Room selection is by exact door match with rotation.** For each room slot the
generator knows which faces need doorways (from the tunnels attached to it).
Every loaded prefab of the right type is tried at 0°, 90°, 180° and 270°; the
rotated doorway face set must be **exactly equal** to the required set — not a
superset. Surviving candidates are sorted by filename then rotation, and one is
picked with `new Random(layout.seed() ^ 0x524f4f4d53454544L ^ (roomId.hashCode() << 32))`,
so selection is reproducible. No exact match for a NORMAL/BRANCH slot means
that room falls back to procedural stone with a `severe` log line; no match for
SPAWN or BOSS **refuses the whole generation** (`required-prefab-failure`).

`CorridorLibrary` handles corridor tiles: repeatable middle segments joined to
fill `generation.corridor.length` (default 18, must be a multiple of the tile's
connector spacing — 6 in the shipped tiles, so 18 = three tiles).

### 4.3 Markers — the colour conventions

| Colour | Meaning | Where | Replaced with |
|---|---|---|---|
| `RED_WOOL` | Doorway declaration | Room outer wall | `WALL_MATCHING` (copies a neighbouring wall block) |
| `GREEN_WOOL` | Entrance doorway: pins rotation so players arrive through it. Max one; needs a red exit; no mixed walls | Room outer wall | `WALL_MATCHING` |
| `YELLOW_WOOL` | Trap **floor only**: drops with everything standing on it (§4.3b), kills deliberately, rebuilds after `trap.floor-return-seconds` | Visible walking floor | `WALL_MATCHING` (trap is invisible) |
| `GRAY_WOOL` | Player spawn | Spawn room, exactly one | `AIR` (forced) |
| `LIGHT_BLUE_WOOL` | Boss spawn (optional) | Boss room, at most one | `AIR` (forced) |
| `LIME_WOOL` | Swarm mob group | Room floor | `AIR` |
| `ORANGE_WOOL` | Pack mob group | Room floor | `AIR` |
| `MAGENTA_WOOL` | Champion group | Room floor | `AIR` |
| `BLACK_WOOL` | Guardian (key holder) | Guardian room floor | `AIR` |
| `PURPLE_WOOL` | Legacy in rooms: reported, replaced | Old room files | `AIR` |
| `PURPLE_WOOL` | Corridor connector | Corridor tiles | `AIR` |
| `CYAN_WOOL` | Corridor alignment guide | Corridor tiles | `AIR` |
| `CYAN_WOOL` | Ignored authoring guide | Rooms — never placed | — |

Any unmapped `*_WOOL` in a room is reported as a problem by `/dungeon rooms`.
`LIGHT_GRAY_WOOL` in a spawn room is specifically called out as "you meant
GRAY_WOOL".

**Doorway marker height convention.** A doorway marker is one centred block or
a contiguous centred strip on an outer wall. It only identifies *which wall and
where*; the actual opening is found as the air gap **below** it, needing at
least `minimum-opening-width` 3 by `minimum-opening-height` 3. This means a
marker may sit in a decorative ceiling band far above the opening. Validation
requires the strip be on exactly one outer wall, at one height, contiguous, and
centred on that wall.

`placementYOffset = 1 - openingBottom`: corridors enter one block above the
room's layout floor, and the prefab is shifted vertically so its opening lands
there. All doorways in one room must share an opening floor height.

**Never derive a standing height from a room's bounds.** Use
`DungeonRoom#floorY`. Every shipped prefab stands on a four-block decorative
foundation, so the placed geometry starts three blocks *below* the layout
floor and `bounds.minY() + 1` lands inside solid stone. That one assumption
silently cost every mob spawn in a prefab room: no clearance ever passed, so
no group was ever placed. Searching downward from a room's ceiling is the
mirror of the same trap — an arena roof has solid blocks below and open sky
above, which is a perfect "clear floor", and that is how the boss ended up
standing on top of the building.

Procedural rooms have no red wool, so they get a *virtual* door marker at
`height-above-floor: 30`, width 3, letting the same corridor tiles connect to
them.

### 4.3b Composed difficulties and the sealed door

A difficulty under `generation.composition` (currently only **1**) replaces
the random layout with an authored one: `path` names one **room role** per
main-path room between spawn and boss (`swarm, pack, rest, swarm-champion,
approach`), and `mobs.room-roles` maps each role to category group counts
(empty = deliberately quiet). The path turns under the normal
`generation.critical-path.*` cadence unless `allow-turns: false`, so corner
and junction rooms appear; room count follows from the list. Roled rooms get **no**
planner markers - a prefab marker of the right colour anchors a group at its
spot, and any shortfall gets runtime anchors on clear floor, seeded
deterministically.

`key-branch` grows a mandatory detour off path room `from` (default the rest
room): `[parkour, guardian]`, a dead end. The corridor after `door-after` is
sealed by `DungeonDoorManager` (`door/` package): a barrier of
`door.material` filling every passable block of the corridor mouth two planes
deep, plus a floating label. The **key is state on `DungeonInstance`**,
granted when the guardian dies - no item, so death/logout/party changes
cannot lose it - and the door opens immediately (`DungeonKeyObtainedEvent`
then `DungeonDoorOpenedEvent` through the bus). Failsafes: a watchdog revives
a guardian that stopped existing without granting the key (twice, then it
force-opens and logs severe), and `/dungeon door open` is the admin override.
Key-branch placement failure **refuses generation**, like a missing spawn or
boss prefab.

Prefabs may carry a role token between type and shape
(`normal_rest_tjunction`, `branch_parkour_straight`,
`branch_guardian_dead_end`); a roled slot prefers its token and falls back to
the generic pool, and roled prefabs never serve other slots. The **guardian**
is a fourth combat category (`BLACK_WOOL` marker, own stats per difficulty,
own theme names) - it is called guardian rather than elite because the API
already uses `elite` for boss minions.

Two authoring features exist for the parkour room but work in any room. A
`GREEN_WOOL` doorway marker pins the entrance: prefab selection only accepts
rotations that put that opening on the face towards the room's parent, so
traversal always runs start-to-exit. `YELLOW_WOOL` floor blocks plus any
pressure plate author a **trap floor** (`trap/DungeonTrapManager`): the
columns are snapshotted from the built world at registration, the plate
drops them to the room bottom after `trap.drop-delay-seconds` (0.4), and the
death follows at `trap.kill-delay-seconds` (3) so the victim falls first.
Whoever stood there is **remembered by UUID** at the drop, because three
seconds later they are far below the room and unfindable by position; a void
fall is never trusted to do the killing. The snapshot is rebuilt
`trap.floor-return-seconds` (4) after the drop, with anything hovering in the
hole lifted out first. One timer per trap; re-presses while open do
nothing.

The marker names the floor, not the whole column. `DungeonTrap.rise` holds
the one definition of what comes with it — straight up through non-air,
stopping at the first gap, capped by `trap.max-column-height` — and both the
runtime snapshot and the `trap-blocks` count in `/dungeon rooms` call it, so
the reported number cannot drift from what actually falls. Air stopping the
climb is deliberate: it makes "leave a gap" the way to keep something above a
trap, and stops a pillar from dragging a ceiling down. There is no sideways
spread; anything meant to fall stands on a marked block, which also keeps a
trap from creeping into an adjacent wall. The plugin validates the contract only - green/red rules, exact
door match, plate present - never whether the jumps are humanly possible.

### 4.3c Room gates (added 2026-08-05, untested in game)

`door/DungeonRoomGateManager`, config `room-gating`. Two rules carry the whole
design: **a room's entrance is never sealed** (nobody is shut in with no way
back), and **a room with nothing alive never seals**. Combat rooms seal their
*exits* — tunnels towards deeper rooms, by depth comparison — the moment the
first player enters while mobs are counted alive; they open on the room clear,
with a message, a sound and per-kill "<mobs> remaining" action bars inside.

**The boss arena is the deliberate exception**: its only doorway is its
entrance. The boss does not spawn on approach any more (with
`room-gating.boss` on, the default): the arena arms itself the moment
**every** living, non-spectator player in the dungeon world stands inside,
seals the entrance and spawns the boss in the same tick
(`DungeonMobManager#spawnBossRoomNow`, which also starts the bars and the
summoning, because the enter events fired while the arena was still empty).
It opens at the boss kill, and also when **no living player remains inside**,
so a wiped party can walk back in — everyone back inside seals it again and
the fight resumes against the boss as they left it. Spectator-mode players
count neither as needed nor as present, or one spectating admin would block
the arena forever. Waiting players see "<present>/<needed> inside".

**Seal shape.** One plane in the room's own wall at the tunnel's doorway
bounds, widened +2, **passable blocks only**, physics on so IRON_BARS join.
Deliberately no second plane: the key door's second plane assumed corridors
have walls, and on today's open-platform corridors it grows a floating fringe
past the platform edge (the key door still works — its first plane sits in
the room wall — but that is why room gates anchor on the room side only).
See-through material on purpose; `room-gating.material` defaults to
IRON_BARS, same as the key door.

**Failsafes**, because a stuck gate now blocks the main path:

- `DungeonMobManager.recount()` (every 2 s) now runs `checkRoomCleared` after
  pruning — before this a room whose last mob vanished *without a death
  event* stayed uncleared forever; the "Defensive recount for future gates"
  comment predicted exactly this consumer. Recount ends with `gates().tick()`.
- `room-gating.timeout-seconds` (300) opens a gate on its own, message plus
  a warning in the log; `/dungeon room open` is the admin override. Both
  **latch** the gate open for the rest of the run.
- `glow-last-mobs`: ≤ 2 mobs alive in a room sealed ≥ 30 s glow, so the
  fight ends at the last mob instead of at a search party.
- A player standing in the doorway as it closes is nudged one block into the
  room; on a reseal, cells occupied by a body simply stay open.
- `RoomMobs` gained a **`visited` flag separate from `spawned`**: visited
  feeds `isRoomVisited` (firstVisit reporting, door watchdog), spawned stays
  "mobs actually created". A deferred boss arena is visited but not spawned,
  so `isClear()` cannot mark an empty arena cleared.

### 4.4 Mobs

`DungeonMobManager` (~1700 lines, the largest class). Four spawn categories —
**swarm**, **pack**, **champion**, **guardian** — each with its own marker
colour, entity type, stats and group size. Every consumed marker requests one complete group
of its category, placed around that marker within `group-radius` 5, widening to
`maximum-group-radius` 12, with `minimum-group-distance` 1.75 between members.
Spawning is spread at `spawn-per-tick` 4.

**`mobs.scaling` multiplies everything in `mobs.difficulties`** (added
2026-08-05) rather than replacing it, so the per-difficulty table stays the
place to shape one band. Base pass: `health` 3.0 on every category and on boss
minions, `champion/guardian/boss-health-extra` 1.5 each on top of that, and
`swarm-count-multiplier` 2.0. Because boss health *is* champion health times
the theme's `health-multiplier` (2.5), the champion's extra already reaches
the boss and `boss-health-extra` compounds on top - a boss ends up 6.75x its
old health. Difficulty 1 and 2 champion health was lowered (70→45, 80→55) to
absorb that, because the multipliers land hardest where the player has the
least damage; that figure also sets those difficulties' boss.

**Party scaling** is `1 + (players - 1) x factor`, `count-per-player` 0.6 and
`health-per-player` 0.75, capped at `max-party-size` 8. Solo is the baseline
at x1.0 with no discount. **Counts reach swarm and pack, health reaches
champion, guardian and boss, and never both to one category** - they multiply
into total effective health, so a champion given both would be roughly nine
times the wall at four players against four times the damage. The size is
**locked once** in `DungeonRoomRegistry.register` (`DungeonInstance#partySize`),
after the world is built and before any room populates: rooms fill ahead of
the party, so a live reading would balance each room for whoever happened to
be in the party when it was built, and could be gamed by logging out before
the boss room fills. A solo run has no party, which is the 1 default.

Room-clear counting is unaffected by the higher numbers: `RoomMobs.isClear()`
counts real spawned entity UUIDs plus `pendingSpawns`, and that counter is
decremented in a `finally`, so a member that cannot be placed still counts
down.

**Five themes**, bound to difficulty bands:

| Difficulty | Theme | Tier | swarm / pack / champion | Boss |
|---|---|---|---|---|
| 1–2 | `crypt` | 1–2 | ZOMBIE / SKELETON / ZOMBIE | ZOMBIE |
| 3–4 | `nest` | 3–4 | CAVE_SPIDER / SPIDER / SPIDER | SPIDER |
| 5–6 | `nether-redoubt` | 5–6 | MAGMA_CUBE / PIGLIN_BRUTE / PIGLIN_BRUTE | HOGLIN |
| 7–8 | `illager-citadel` | 7–8 | PILLAGER / VINDICATOR / RAVAGER | IRON_GOLEM |
| 9 | `rift` | 9 | ZOMBIE / BREEZE / ENDERMAN | WARDEN |

Every dungeon mob is tagged in its PDC: dungeon id, room id, tier, difficulty,
boss flag, theme, category. Stats come from
`mobs.difficulties.<d>.categories.<cat>` — health, damage, speed, `SCALE`
attribute, count, attack speed, knockback resistance, attack reach, weapon.
Drops are cleared on death; XP comes from `mobs.difficulties.<d>.experience`.

Safety rules under `mobs.safety`, all on by default: Vex spawns cancelled,
dungeon-mob teleports blocked, enderman teleports confined to their room,
enderman damage-escape prevented, breeze projectile deflection disabled
(replaced with direct player damage), enderman block changes blocked,
zombification prevented. Copper and Iron golems get their vanilla goals removed
and a custom `HostileMeleeGoal`.

**Bosses.** Spawned at the boss marker or the nearest clear floor to the arena
centre, with clearance checked against the scaled footprint. Since the room
gates (§4.3c) the spawn is **deferred** while `room-gating.boss` is on: the
arena arms when the whole party stands inside, and the mob manager's
`spawnRoom` deliberately skips the boss room then. **The guardian
now uses the same centring** (2026-08-05): its room is a dead end built around
it and a corner spawn reads as the key having failed to appear, so
`generatedAnchor` tries `automaticBossCentre` first and only falls back to the
random anchor search. A `BLACK_WOOL` marker in the prefab still wins, or
authoring one would be pointless. Its clearance is scaled by the mob's `scale`
now, which it was not before. Note the search direction: `automaticBossCentre`
runs **upward** from `floorY`, never downward - see §4.3 for why a downward
search puts a mob on the arena roof. On arena entry a
`BossSummoningSequence` runs: AI off, invulnerable, rotating, particles, then
minions arrive. A boss may have a scripted entrance instead — `rift` uses
`rift_tear` (`RiftTearAnimation`): a tear of block displays opens, shards orbit
and lean in, and the boss rises out of the floor. Gravity is switched off while
it is buried, because a prefab arena can have nothing under its floor. Boss
death → `DungeonCompletionManager.complete()` → titles, a grace period
(`completion.grace-period-ticks` 160), return teleports, world deletion.

### 4.5 Parties

`PartyManager` handles invites (60s), leadership transfer, and one
`PartyInstance` per party. `/dungeon start <1-9> [seed]` is leader-only.
Members are teleported to spread-out entrance tiles. Return points are
remembered per player. Offline members' worlds are cleaned up after a timeout.

### 4.6 The API

`nl.riddernix.dungeonforge.api` — **API version 2**, additive from here on.
Documented for consumers in **`API.md`**; read that before changing anything in
the package.

Obtained via `getServer().getServicesManager().load(DungeonForgeApi.class)`.
Queries return `Optional`/`OptionalInt`/empty list rather than null when
something is not in a dungeon.

Eleven events, all extending `DungeonEvent` (which carries `DungeonInfo`):
start, player enter/leave/death, room enter, room cleared, mob spawn, mob
death, boss summon, boss death, end. `DungeonCompletedEvent` is kept from v1.
**Only `DungeonStartEvent` is cancellable**, fired before any world exists —
cancelling anything mid-generation or mid-clear could strand a dungeon.

Every event is fired from **`DungeonEventBus`** (`internal`), the single choke
point. It counts firings per type, and `/dungeon api status` shows counts plus
which plugins are listening per event. That exists because the API silently
rotted once: rewrites dropped call sites and nothing failed visibly.
`/dungeon api fire` sends one of each event; `/dungeon api query` dumps query
results. **A listener count of 0 for an event the other plugin claims to
handle almost always means it shaded a copy of the API classes into its own
jar** — Bukkit's handler lists are per class object.

`DungeonSnapshots` is the only place internals are converted to API records,
so no internal type ever reaches a listener.

### 4.7 Display-entity panels

Two in-world UIs, no resource pack, no client mod. Both spawn **non-persistent**
display entities respawned from their own storage file on chunk load, so the
world save never contains them and they cannot duplicate. Both PDC-tag their
entities under distinct keys so each one's orphan sweep cannot touch the
other's.

**Difficulty panel** (`panel/`, storage `panels.yml`, `/dungeon panel place`).
Heading, a carousel of difficulties 1–9, two arrows, an "Enter Dungeon" button.
The carousel is shared furniture with a per-viewer number row: scales
2.8/1.6/1.0 and opacities 255/165/85 outward from centre, sliding by teleport
and transformation interpolation. Scrolling **stops at 1 and 9** rather than
wrapping. Selection persists for the session, clears on logout. The button is a
square-cornered plate whose padding and border are computed in font pixels from
the label's measured width.

**Skill panel** (`skills/`, storage `skill-panels.yml`, definitions
`skills.yml`, `/dungeon skills place [class] [standard|big]`). Two variants:
`standard` (~7×5 blocks, glyph nodes, all sizes from config) and `big`
(~23×16 blocks, raised block-display plates, sizes fixed in
`SkillPanelGeometry`). The big variant is the real design: 40-node tree, class
carousel (Warrior/Archer/Paladin/Mage), a standing Info area, and the viewer's
own point balance where a Confirm button used to be.

**Nothing on this panel picks a class** (changed 2026-08-05). Confirm is gone
and class switching lives outside DungeonForge entirely; the panel shows the
tree of whatever class the player already has. Points are therefore always
spent in the *active* class, never in the one the carousel happens to be
showing — `attemptUnlock` refuses with `skills-not-your-class` rather than
buying into a browsed tree, which is the trap that removing Confirm opened.
The balance is a per-viewer TextDisplay rebuilt from every path that can move
it, `changePoints` included: without that a reward granted through the API sat
invisible until the player walked away and back.

Connections are a **DAG, not a tree**: `requires` may list several nodes, and
`any-of: true` means any one unlocked prerequisite suffices (that is how paths
rejoin). `routes:` bends an edge through corner points, drawn one bar per leg.
Node positions are cartesian grid units (`x`, `y`) scaled by the variant —
moving a node is two numbers.

Three states everywhere — **unlocked / available / locked** — shared furniture
drawn entirely locked, everything brighter a per-viewer overlay
(`setVisibleByDefault(false)` + `showEntity`). A player's own plates *hide* the
shared ones rather than covering them.

Clicking: every node has an `Interaction` hitbox, but a click is re-resolved
server-side to the node nearest the player's **line of aim**, so overlapping
hitboxes cannot fight over it. Two range limits, both configurable:
`view-range` 2.0 (a multiplier, stamped identically on the shared tree and all
overlays so there is never a distance where you see the tree but not your own
progress) and `click-range` 20.0. **The client only registers clicks within the
`ENTITY_INTERACTION_RANGE` attribute, ~3 blocks by default** — that is a hard
client limit, worked around by raising the attribute with a *transient*
modifier while the player is within `activation-radius` 30 of a panel. Honest
side effect: their melee reach on mobs is raised too, so keep panels out of
combat areas.

### 4.8 Smaller systems


- **Custom models** (`model/`): optional BetterModel integration listening to
  `DungeonMobSpawnEvent`. `BetterModelApplier` is the only class touching
  BetterModel types and is loaded only after confirming the plugin is enabled.
- **Dungeon Lord** (`npc/`): persistent NPC, storage `npcs.yml`, points players
  at the nearest difficulty panel.
- **Boss animations** (`fx/`): `SpawnAnimations` registry; `/dungeon animate
  rift_tear` previews one without generating a dungeon.

---

## 5. File layout

```
src/main/java/nl/riddernix/dungeonforge/
├─ DungeonForgePlugin.java     entry point, all wiring, CONFIG_VERSION
├─ api/            (23 files)  public events, records, DungeonForgeApi
├─ internal/                   DungeonEventBus, DungeonForgeApiImpl, DungeonSnapshots
├─ command/DungeonCommand.java every subcommand + tab completion
├─ generation/                 DungeonLayoutGenerator, DungeonLayoutBuilder, BuildOperation
├─ room/           (18 files)  NormalRoomLibrary, CorridorLibrary, DungeonRoomRegistry,
│                              DungeonInstance, marker types, internal room events
├─ door/                       DungeonDoorManager: sealed corridor, key state, watchdog
├─ mob/DungeonMobManager.java  spawning, stats, safety rules, boss sequence
├─ completion/                 DungeonCompletionManager
├─ party/                      PartyManager, DungeonParty, PartyListener
├─ world/                      DungeonWorldManager, VoidChunkGenerator, VoidBiomeProvider
├─ panel/                      difficulty panel
├─ skills/                     skill panel + SkillTreeLibrary + SkillPanelGeometry
├─ fx/                         boss entrance animations + preview
├─ model/                      BetterModel integration
├─ npc/, menu/, player/, settings/, build/, util/
└─ resources/                  config.yml, skills.yml, plugin.yml
```

Live server data in `plugins/DungeonForge/`: `config.yml`, `skills.yml`,
`rooms/`, `corridors/`, `panels.yml`, `skill-panels.yml`, `npcs.yml`,
`testing-mobs.yml`, `skill-progress.yml`.

**The in-world schematic editor was removed on request (2026-08-04)** — the
`editor/` package, `/dungeon schematics`, its config section and messages,
and the clickable schematic link in the room-entered message (the file name
is still shown, as plain text). It was a build-rooms-in-game workflow that
kept costing more than it returned: nearly every round tripped over stale
state — edits not yet written to disk, a grid pasted on top of its own
previous layout, a world whose spacing no longer matched the config. Rooms
are authored in an external editor and dropped into `rooms/` instead.
Nothing else references it; `createOrLoadPlainWorld` is its one surviving
trace. Leftover `schematic-editor.yml`, `schematic-backups/` and any
`df_schematics*` world folders on a server are inert and can be deleted.

Docs in repo root: `README.md` (full reference), `API.md` (for the API
consumer), `STYLE.md` (colours, font metrics — hand to another AI doing visual
work), `PANEL.md` (how an in-world display-entity panel is built: anchor and
orientation maths, the depth budget, per-viewer overlays, client-side
interpolation, the five orphan-cleanup layers, and what to change to build a
different panel — read it before writing a third one), this file.

**Style conventions in this codebase:** comments explain *why*, never *what*;
no comment states where code came from or that a change is correct. British
spelling in prose. Every user-facing string lives in `config.yml` under
`messages:` and goes through MiniMessage via `Messages`.

---

## 6. Broken and unfinished — honest list

**~~`PURPLE_WOOL` double-booking~~ — fixed.** Champion is `MAGENTA_WOOL` now.
The legacy-purple special case was deliberately **kept**, not deleted as this
file once suggested: every existing room file contains purple wool, so purple
in a room is still reported and replaced with air rather than placed.

**`CYAN_WOOL` is double-booked.** It is the corridor alignment guide *and*
silently ignored in rooms (counted as `ignored-cyan`, never placed). Harmless
today but the same trap as purple: one colour, two meanings, no validation
across the two loaders.

**Room dimension mismatches.** The planner reserves the **maximum** footprint
across every prefab a slot could receive — for a roled slot that means its
role pool *and* the generic pool, because selection falls back to generic.
Reserving only the role's size caused a hard failure once and is worth
understanding: a prefab **wider than its reservation** overhangs on both
sides, and once the overhang reaches `generation.corridor.length` the two
rooms share a wall, both doorway markers land on the same block, and no
corridor can be built at all — not even the procedural stone one, because
the tunnel had already been accepted as schematic. A 31-wide parkour
reservation with a 69-wide generic fallback produced exactly that.

What remains: **within one pool**, sizes should match. A prefab smaller than
its pool's maximum sits centred inside an oversized reservation, so its wall
falls short of the reservation edge and the corridor markers end up further
apart than `corridor.length` — the tiling absorbs that (extra overlapping
tiles) but it reads as a seam, and `verifyGenerated` may log a `severe`
doorway audit. Live files (2026-08-02): all `normal_*` 67x34x67, all generic
`branch_*` 69x34x68, the parkour room deliberately 31x34x111. Proper full fix
remains reserving per *selected* prefab, i.e. selecting during layout
planning.

**Gamerules were never applied at all** (found 2026-08-03, fixed). The
lookup built an index from `Registry.GAME_RULE.getKey(rule)` plus the legacy
`GameRule.values()`; on Paper 26.1 both come back empty, so every key logged
"Unknown gamerule in config" and no dungeon world ever got `keepInventory`,
`doMobSpawning: false` or the rest. `DungeonWorldManager#lookupGameRule` now
asks the registry for `minecraft:<snake_case>` directly, which needs neither
reverse lookup nor the removed API. If those warnings ever return, that is
the first thing to check - and it is worth checking, because a run without
keepInventory quietly changes what dying costs.

**Writing our own schematic reader was assessed and declined (2026-08-04).**
Dropping WorldEdit would cost an NBT reader *and writer*, a Sponge v2/v3
parser and serializer, and — the real work — a block-state rotation table
(`facing`, `axis`, `rotation` 0-15, the four-way properties of panes, fences,
walls, vines, redstone and rails). Reading block states themselves is free:
the palette holds vanilla strings, so `Bukkit.createBlockData` handles
stairs, slabs and waterlogging exactly. Estimate 600-900 lines whose bugs are
subtle and only visible in game. The gain (no softdepend, no load order, no
update risk) does not pay for that while WorldEdit works. Revisit only if a
WorldEdit build ever blocks a Minecraft upgrade; all WorldEdit use is already
confined to the load edges of `NormalRoomLibrary` and `CorridorLibrary`, so a
later swap touches two files (the third, `SchematicEditor`, is gone). Note
either way:
tile-entity data (chest contents, sign text) is *not* placed today.

**Nothing has ever been verified on a running server.** Everything in this
project was compile-verified only; the assistant never accepted the Mojang EULA
to run `runServer`. Several bugs found this way were real and only surfaced
when the user tested: node glyphs rendering as missing-character boxes, a label
buried inside a block display's front face, a plate rotating around its corner,
a silently-cancelled tick loop. **Assume anything visual is unverified until
someone looks at it in game.**

**`models.themes` is missing two themes.** It has `crypt`, `nest` and `rift`
but not `nether-redoubt` or `illager-citadel`, so those two can never be given
custom models per theme (they fall back to `models.defaults`). Caused by a grep
that missed hyphenated names. Add both sections.

**~~Skill tree phase 3~~ — built (2026-08-03).** `SkillProgressManager` owns
persistent per-player state in `skill-progress.yml`: active class, node
levels per class, points (available and spent). All hot queries are hash
lookups. Unlocking: select a node on the panel, **click it again** to commit
— validation order is exists → already → prerequisites → points → the
cancellable `DungeonSkillNodeUnlockEvent` (fires only for attempts that would
succeed, before any state changes, so a veto costs nothing). API v3 exposes
getActiveClass / hasSkillNode / getSkillNodeLevel / getUnlockedSkillNodes /
getSkillPoints / getSpentSkillPoints / grantSkillPoints / withdrawSkillPoints
plus `DungeonSkillClassChangeEvent` and `DungeonSkillPointsChangeEvent`, all
through the bus. **Points come from the API** (`grantSkillPoints`) — that
answers one old open question; the admin side is `/dungeon skills points
give|take <player> <amount>`. The test harness (`/dungeon skills test`)
remains as a free in-memory render overlay merged on top of real state.

**API v4 added the skill write API** (2026-08-04): `grantSkillNode`,
`revokeSkillNode`, `resetSkillTree` (active class or named), `setActiveClass`,
`getSkillClasses`, all returning `SkillWriteResult` — never throwing — plus
`DungeonSkillNodesRevokedEvent`. Decisions worth not relitigating:

- **Revoking cascades**, recomputed to a fixpoint so `any-of` nodes with a
  surviving route stay. Orphans would corrupt the tree permanently; refusing
  would push tree knowledge onto the consumer.
- **Each held node records what was paid** (`unlocked.<class>.<node>.paid`
  beside `.level`; a bare int from before is read as "paid the configured
  cost"). `grantSkillNode` is free and therefore refunds nothing, so
  grant/revoke cannot mint points.
- **A depth-4 re-entrancy guard** per player: a listener may answer an event
  with a related write, but a plugin answering its own event returns
  `REENTRANT` instead of hanging the server.
- `SkillPanelManager#refreshFor(player)` redraws that player's overlays after
  an outside write; the tree is shared furniture and progress is per-viewer,
  so nobody sees anyone else's change.

**API v5 added `DungeonSkillNodesGainedEvent`** (2026-08-05), fired after the
write from both the panel's `unlock` and `grantNode`, carrying a `Source` of
`PURCHASED` or `GRANTED`. It exists because a consumer had no safe moment to
re-read the tree: `DungeonSkillNodeUnlockEvent` fires *before* the write and
is cancellable, so refreshing there reads state without the node and
sometimes refreshes for an unlock that is then vetoed - and a grant is free,
so no `DungeonSkillPointsChangeEvent` carried it either. That second half hit
`a0` exactly, the one node handed out rather than bought. The rule now stated
in `API.md`: the unlock event is a veto hook, the gained and revoked events
are "read it again" pings, and effects are derived from
`getUnlockedSkillNodes` rather than mirrored.

**`hasSkillNode` and `getSkillNodeLevel` now log an unknown node id once**
(`SkillTreeLibrary#knowsNode` checks every tree, not one player's, so a query
before a class is set cannot raise a false alarm). Both used to answer "no"
and "0" for a nonexistent id exactly as for an unbought one, which is how an
id scheme that drifted between two plugins stayed invisible: everything simply
behaved as though nothing was ever unlocked.

**Still open on skills:** node levels above 1 and per-level costs; grant and
revoke only reach the active class (an overload taking a class id is the
obvious additive next step if it is ever needed). The
startup rot guard gained a second half: the plugin scans its own jar for api
event classes and severe-logs any missing from the bus inventory.

**All four classes share one tree** (`core` in `skills.yml`). Deliberate — the
data model separates classes from trees so giving each its own layout is a data
change, not a rewrite.

**The tree carries ClassSkills' node contract** (mapped 2026-08-05). The 40
positions, connections, `routes` and names are this project's own and were
kept byte-identical; only ids, `requires` targets, costs and descriptions were
rewritten to the other plugin's specification. Two things a future editor must
not undo by accident:

- **`a0` is the old `root`.** Passive Rank I, and all three branches require
  it, so **nothing in the tree is buyable until it is held**. It cost 0 and
  was meant to be handed out with `grantSkillNode` on clearing Difficulty 3 -
  but nothing ever granted it, on either side, so it was simply a free first
  click. Since 2026-08-05 it **costs 1 point** and the Difficulty 3 gate is a
  real one on `a1` instead (see the `requires-difficulty` entry below).
- **The passive ranks sit on the one strictly linear chain the ability branch
  has**: `a0` is Rank I and `a1`-`a5` are Ranks II-VI, each requiring the one
  before it, so a rank cannot be skipped. `a6`, `a7` and `a8` are stat nodes
  filling the positions around that ladder. An earlier draft of this file
  claimed the ranks were on a1, a2, a4, a7, a8 - they are not, and were not
  at the time; corrected 2026-08-05 against the live file. What still holds is
  the reason the layout was chosen: a mapping that puts two ranks on sibling
  positions lets a player reach Rank VI having skipped Rank IV. Re-check that
  chain before moving any ability id.

Whole tree costs 209 points: `a0` 1, weapon 55, ability 84, survival 69.
ClassSkills' budget is 200 at level 100, so a maxed player still cannot buy
everything.

**`requires-difficulty` gates a node behind a cleared dungeon difficulty**
(added 2026-08-05, `skills-version 5`). Today only `a1` uses it, at 3, which
gates the entire ability branch because everything there flows from `a1`. The
split is deliberate: the *rule* is tree data and lives in `skills.yml`, the
*answer* is ClassSkills' and is read through its `hasUnlockedDifficulty`,
which returns true when ClassSkills is absent - so a server running
DungeonForge alone sees the tree it always saw. The panel draws a gated node
as **locked**, not available, because offering a node and then refusing the
click is worse than showing it as out of reach. `grantSkillNode` ignores the
gate, the same way it ignores cost; prerequisites remain the only thing a
grant still honours, because those are structural.

This reverses what the header of `skills.yml` used to state - that gating
belonged entirely to the consuming plugin through the veto event. That is
still possible and still supported, but it cannot grey a node out, and
ClassSkills' `canUnlockNode` does not exist yet.

**ClassSkills owns the class and the budget** (`skills/ClassSkillsIntegration`,
added 2026-08-05). The division: it owns class, level and the level-derived
point budget; DungeonForge owns which nodes were bought, and therefore
`spent`. Available points are **derived**, never stored: `budget - spent` with
ClassSkills present, `granted - spent` without it. Two things follow that are easy to break:

- **Never call its `getAvailableSkillPoints`.** That method is documented to
  return *our* balance when DungeonForge is installed, so reading it here
  would be a loop. This integration reads `getTotalSkillPointBudget`,
  `getPlayerProfile().classId()` and `hasUnlockedDifficulty`, nothing else.
- **Nothing on this side hands out points** while ClassSkills is installed:
  they are earned by levelling there. `grantPoints`/`withdrawPoints` and
  `/dungeon skills points` return the unchanged balance and log one line,
  rather than minting points outside the level system. Both still work on a
  server without ClassSkills.
- **`spent` is the only figure a purchase moves.** The balance follows by
  arithmetic, so a refund lowers `spent` and nothing else — adding it back to
  a stored total as well would hand the points over twice.

Reflection, not a compile-time dependency, because the ClassSkills jar is not
in this build; swap it for `compileOnly` when the jar is available. Class and
budget are cached for 20 ticks, because `hasSkillNode` runs on every hit.
Without ClassSkills everything falls back to the stored `granted` figure and
behaves exactly as before. Old `skill-progress.yml` files are migrated on
load: `granted = points + spent`.

**The skill carousel duplicates the difficulty carousel's logic** rather than
sharing a component. The user asked for reuse; the recipe and behaviour match
but the code is a second implementation. Extracting a shared component was
deferred to avoid rebuilding the working difficulty panel mid-feature.

**The chest-based difficulty menu is dead code.** `menu/DungeonMenu.java` and
`DungeonMenuListener` are still registered but no longer reachable — `/dungeon`
and the Dungeon Lord now point at the difficulty panel. `PartyMenu` is still
live via `/dungeon party`. Safe to delete along with the `menu:` config
section.

**Minor:** the standard skill panel's base line depth (0.015, depth 0.02) and
overlay line depth (0.032) overlap slightly and can z-fight; the big variant's
stack was fixed but standard was left alone on request. `GLYPH_CENTRE_PIXELS`
(4.5) and pixels-per-block (40) are hardcoded in `SkillPanelManager` while the
difficulty panel exposes the same two numbers in config.

---

## 7. Where the last session ended

**State on 2026-08-05: config-version 76, skills-version 5, API v5 with 19
bus-wired event types, builds clean.** Latest jar
`build/libs/DungeonForge-0.10.0.jar`.

The session did five things, in this order.

### 7.1 Difficulty 1 as the template for composed difficulties (§4.3b)

1. `generation.composition.1`: path `swarm, pack, rest, swarm-champion,
   approach`, `allow-turns: true`, key branch `[parkour, guardian]` off the
   rest room, door sealing corridor 3→4. Difficulty 1 is now spawn + 5 path
   rooms + boss + 2 branch rooms.
2. Room roles (`mobs.room-roles`) drive spawning in composed rooms; anchors
   come from matching prefab markers, topped up procedurally per seed. This
   matters because the live room files contain **no lime/orange markers at
   all** — runtime anchors are the primary path.
3. **Guardian** added as the fourth mob category (all 9 difficulties, all 5
   themes); champion marker fixed to `MAGENTA_WOOL`, guardian is `BLACK_WOOL`.
4. `door/DungeonDoorManager`: barrier + label + deny handling + key-on-death
   + watchdog + `/dungeon door open`. The key is **party state, not an item**,
   so it cannot be dropped, traded or lost on death.
5. Prefab filename contract extended with role tokens; the random PARKOUR
   room variant retired (weight 0) in favour of the key branch.

Generation and the layout were confirmed in game, which found three bugs, all
fixed: **no mobs spawned in prefab rooms** (standing heights came from room
bounds, three blocks below the real floor, because every prefab has a
four-block foundation — `DungeonRoom` now carries `floorY`); **the boss
spawned on the arena roof** (`automaticBossCentre` searched downward and a
roof qualifies as clear floor — it now searches upward from `floorY`, capped
by `BOSS_FLOOR_SEARCH_UP`); and **gamerules never applied at all** (§6), which
had quietly been running every dungeon without `keepInventory`.

### 7.2 Parkour-room authoring: green entrance and trap floor

Both untested in game.

- **`GREEN_WOOL` pins the entrance.** One green marker fixes which face the
  player arrives through, so a hand-built room keeps its intended orientation
  instead of being rotated freely. Validation: more than one green is invalid,
  green without a red exit is invalid.
- **`YELLOW_WOOL` marks the floor only.** Everything structurally connected
  *above* a marked block falls with it — the rule is
  `DungeonTrap.rise(solidAbove, topY, maximumRise)`, deliberately the single
  function shared by the runtime and the `/dungeon rooms` `trap-blocks`
  report, so the reported count is the count that will actually fall.
  `trap.max-column-height` (8) caps the rise.
- **Timings** (`trap:` in config): plate pressed → **0.4 s** until the floor
  drops → **3 s** until anyone still falling dies → **4 s** until the floor
  respawns. Victims are recorded by UUID at drop time because three seconds
  later they are far below the room.

**The user still has to add yellow wool to `branch_parkour.schem`.** The
pressure plates are in it; the trap-floor markers are not, so today the trap
does nothing.

### 7.3 WorldEdit kept, schematics bundled in the jar

Writing an own schematic reader was assessed and declined (§6). Instead
`extractBundledSchematics()` ships the room and corridor files inside the jar
and unpacks them on first start — **only into empty folders**, so a server's
edited rooms are never overwritten.

### 7.4 The in-world schematic editor was deleted

Reasons and leftovers are in §5. The one thing to add: there is **no version
control on this project**, so the deleted `editor/` package survives only in
that session's scratchpad and should be treated as lost. If schematic editing
is ever wanted again, build it fresh. The same absence of VCS is why a careless
overwrite of `skills.yml` in this session could only be recovered from the
user's live server — **read a resource file before writing it**.

### 7.5 Skills: phase 3, the write API, and the ClassSkills split

§6 has the durable detail. In short: persistent progression, click-again
unlocking behind a cancellable veto event, the v4 write API
(`grantSkillNode` / `revokeSkillNode` / `resetSkillTree` / `setActiveClass`),
cascade revoke to a fixpoint, and the jar-scan rot guard. Then, on the panel
and the tree:

- **Confirm is gone.** That furniture now shows the player's **skill points**
  as text (`skill-panel.points`), per viewer. Class switching left the panel
  entirely.
- **The 39-node ClassSkills spec was mapped onto the existing 40 positions.**
  Positions, connections, `routes` and names were kept byte-identical on the
  user's explicit instruction ("my connections win"); only ids, `requires`,
  costs and descriptions changed. `root` became **`a0`** (cost 0, Passive
  Rank I) — the user chose this knowing it gates the entire tree.
- **ClassSkills owns the class and the point budget**, DungeonForge owns
  `spent`. Available points are derived, never stored. Never call ClassSkills'
  `getAvailableSkillPoints` — it returns *our* balance and would loop.

**Nothing from 7.2, 7.4 or 7.5 has run on a live server.** The ten-second
check after a restart is `/dungeon api status` (expect **19** event types and
API v5) plus `config-version 76` in the log; if either is wrong the jar did
not deploy, which has already happened once in this project ("er is niks
veranderd" — the build had reported `deploy SKIPPED`).

### 7.6 Open items for the next session

1. **One question the user asked and never got an answer to.** Verbatim:
   *"Tell me whether the arrows should stay as a way to look at another
   class's tree without switching to it, or whether they should go too and the
   panel only ever shows the active one. I lean toward keeping them as a
   read-only browse, but say what you think."* The recommendation was to
   remove them — browsing fires nothing at the friend's plugin either way, so
   this is purely about whether an inert control is worth its confusion. It is
   still unanswered and nothing was changed.
2. **Yellow wool in `branch_parkour.schem`** (7.2).
3. **Four prefabs the user still owes**: `normal_rest_tjunction`,
   `normal_approach_straight`, `branch_parkour_straight`,
   `branch_guardian_dead_end` (one `BLACK_WOOL` marker). Rest, approach and
   guardian must be sized to match their generic pools (§6). Until they exist
   roled slots fall back to generic prefabs, so difficulty 1 already plays end
   to end.
4. **Three branch prefabs were rejected** — `branch_corner_l`,
   `branch_corner_r`, `branch_tjunction`: their doorway strip is off-centre
   (centre 34, expected 33) because the rooms are 68 deep. Making them **69**
   deep fixes it.
5. **Friend's side (ClassSkills), settled 2026-08-05 but not yet built.**
   Their ids (`warrior_weapon_01`) are not ours (`w1`); the numbering is
   otherwise identical, so the mapping is mechanical - strip the class, take
   the branch's first letter, drop the leading zero. **`a0` is the one
   exception: it has no counterpart in their 39-id scheme** and gates the
   whole tree. Underneath that was a bigger problem: ClassSkills kept its own
   `purchasedNodeIds` (mirrored from our v4 events) and derived
   `getSpentSkillPoints`, `signatureRank` and all three stat bonuses from it,
   which is a second authority, not a cache. Agreed: **DungeonForge is the
   sole authority for purchased nodes**; ClassSkills stops persisting them,
   queries `getUnlockedSkillNodes`, maps ids into its own catalogue only to
   compute effects, and treats the node events as refresh pings. Their budget
   is still 200 at level 100 against a tree costing 208.
6. **Swap `ClassSkillsIntegration`'s reflection for a `compileOnly`
   dependency** once the ClassSkills jar is available.

The first live test should be `/dungeon start 1`: the first combat room's
exit should seal in iron bars behind you with a message and open at the last
kill; then the path to the sealed key door, the parkour room, the guardian
(centre of its room), the door opening — and at the arena the boss should
appear only once you step inside, with the bars closing behind you. The door
label's facing, the key-rise visual and every part of the room gates have
never rendered once.
