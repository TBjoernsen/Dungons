# DungeonPlugin — handover

Merge of **DungeonForge** (`C:\Modding\Plugins\DungeonForge`, was Java, 19.0k
lines) and **ClassSkills** (`..\classskills`, Kotlin, 4.4k lines) into one
Kotlin plugin. The two source plugins are **read-only reference** — never edit
them; they are the revert path. This folder is the only thing being written.

**Status 2026-09-01: the full port compiles clean.**
`build/libs/DungeonPlugin-0.1.0.jar` builds with zero errors and zero
warnings; 66 Kotlin files, ~19.2k lines. Nothing has run on a server yet — see
"Untested" below.

**Update 2026-09-02:** a `.gitignore` bug had silently dropped the whole
`build` package (`BoxBuilder.kt`, `BoxSpec.kt`) from every commit — the
`build/` rule meant for Gradle output also matched
`src/main/kotlin/nl/riddernix/dungeonplugin/build/`, so a fresh checkout was
missing two files and would not compile. Both `.gitignore` files now anchor
the Gradle rule (`/build/`, `/plugins/*/build/`); the two files were
re-ported from the DungeonForge Java originals and the port compiles clean
again (zero errors, zero warnings). The DungeonForge reference had the same
two `build/*.java` files untracked — now committed too. Also: the two extra
`models.themes` entries below are added.

## Ground rules (user decisions, 2026-09-01)

- Everything ported to **Kotlin**, package `nl.riddernix.dungeonplugin`.
- Plugin name **DungeonPlugin**, own data folder `plugins/DungeonPlugin/`.
- **No public API**: the former `api` package became the internal `event`
  package; no ServicesManager registration, no API.md contract, no jar-scan
  rot guard. The event bus stays as internal decoupling (model/fx/class
  listeners use it), and `/dungeon api status|fire|query` still works as
  diagnostics.
- The skill **tree** (panels, skills.yml layout) is DungeonForge's; the
  **functional side** (classes, combat, passives, abilities, kits,
  progression) is ClassSkills', rebuilt against the internal services in the
  `classes` package.
- Never run DungeonPlugin on a server next to DungeonForge or ClassSkills:
  same commands, same `dungeon_` world prefix, doubled listeners.

## Build

```
JAVA_HOME=C:/Modding/Plugins/DungeonForge/.jdk-build-2/jdk-25.0.4+7 ./gradlew build
```

Gradle 9.6.1 (wrapper), Kotlin 2.4.10, jvmTarget 25, paper-api
26.1.2.build.74-stable. The Kotlin stdlib is NOT shaded: `plugin.yml`
declares it under `libraries:`, so the server needs internet once at first
boot (it caches under `libraries/` afterwards). If that is ever unwanted,
switch to the shadow plugin.

## Package map

| Package | Contents | Origin |
|---|---|---|
| `event` | DungeonRecords, DungeonEvents, SkillEvents (19 event types) | api/, made internal |
| `internal` | DungeonEventBus, DungeonSnapshots, DungeonQueries (ex-ApiImpl) | internal/ |
| `util`, `world`, `build`, `generation` | Messages, void worlds, template planner/builder (TemplateConfig, TemplateGeometry, TemplateLayoutGenerator, PlaceholderShell) | rebuilt 2026-09-17 |
| `room` | RoomTypes, RoomEvents, DungeonInstance, registry, marker scanner/definitions, NormalRoomLibrary, CorridorLibrary | 1:1 |
| `party`, `trap`, `door`, `mob`, `completion` | 1:1 | |
| `player`, `model`, `settings`, `menu` (PartyMenu only), `npc`, `fx`, `panel` | 1:1 | |
| `skills` | SkillTreeLibrary (v6: reads per-node `effect.*`), SkillProgressManager, SkillPanelManager, geometry, listener | merged |
| `classes` | ClassType/StatType, ClassesConfig, ClassProgressionService, ItemService, AttributeService, DungeonKitService, PassiveService, AbilityService, FeedbackService, CoreListener, ClassDungeonListener, HolographicClassSelection, ClassCommands | ClassSkills, rebuilt |
| `quest` | QuestCategory, QuestObjective/QuestDefinition, QuestConfig, QuestManager, QuestMenu(+Listener), QuestObjectiveListener, QuestCommand | **new 2026-09-03** |
| `command` | DungeonCommand (all /dungeon subcommands) | 1:1 |

Deliberately NOT ported: DungeonForgeBridge (direct calls now), SkillModel
catalogue (folded into skills.yml `effect.*`), PlayerDataStore (replaced by
ClassProgressionService's players.yml), the dead ClassSkills UI
(HolographicSkillTree, SkillMenus, SkillTreeDialog, ClassOverviewDialog,
ClassScreenDialog, TextLayout, OraxenItemBridge), DungeonForge's dead
menu/DungeonMenu(+Listener), the API service registration and rot guard, and
the uncalled `placeCopperStatues` (dead in the original too; the
BossDefinition scenery accessors were kept).

## Merge decisions taken

- **skills.yml is the single authority on the tree**: skills-version 6 adds
  `effect: { stat, value }` / `effect: { passive-rank }` per node and a
  `requires-difficulty: <tier>` on every node above tier 1 (from ClassSkills'
  tier table), so the panel greys gated nodes instead of a veto refusing the
  click afterwards. `a0` costs 1 point.
- **One authority per fact**: active class lives in SkillProgressManager
  (skill-progress.yml); level/XP/difficulty per class profile live in
  ClassProgressionService (players.yml); points are always derived
  (budget − spent with classes on, granted − spent with classes off). All the
  old sync plumbing (grant/withdraw mirroring, setActiveClass reflection) is
  gone.
- Difficulty unlock stays level-driven ((level−1)/10+1), synced each refresh.
- Point budget formula kept exactly (yields 201 at level 100 incl. the
  2-point start; ClassSkills' docs said 200 — flagged, not silently changed).
- The old selectClass ordering bug is structurally gone: profiles carry no
  point balance, and the snapshot happens before the switch.
- `classes.enabled: false` in classes.yml gives a dungeons-only server: no
  class listeners, no kits, no level gate, points from the granted ledger.
- config.yml restarts its lineage at `config-version: 1` (content = DF v76).
  classes.yml uses per-key default-merging instead of wholesale replacement.
- `/skills reset` (player-facing, costs ceil(spent × bulk-reset-shard-rate)
  Skill Shards) replaces ClassSkills' shard-paid reset paths.

## Quests (added 2026-09-03, first feature past the merge)

Structure and flow only; quest **content is placeholder** and lives in
`quests.yml` (`pool.<category>.<id>`), swappable without touching code.

- **Three categories** (`QuestCategory`), 4 slots each: `daily` rolls at
  midnight, `weekly` at Friday midnight, both in `timing.timezone`
  (default `America/New_York` - tracks EST/EDT; set `-05:00` for hard EST).
  `general` rolls once and never on a timer (its long-term refresh rule is
  deliberately undecided).
- **Refresh** is server-side. `QuestManager`'s constructor rolls a fresh
  install and catches up any boundary crossed while the server was down
  (compares stored `last-refresh` to the most recent boundary); a repeating
  task (`timing.refresh-check-seconds`, default 60) handles the running case.
  A refreshing category **wipes every player's progress for it** (in memory
  and in the file) when it rolls; `general` progress is kept.
- **State**: server-wide - one set of 4 definition ids per category, shared by
  all players. Per-player `counter` + `claimed` per (category, slot), keyed by
  UUID. Both live in `quest-data.yml` (`sets.*` + `players.<uuid>.*`), the
  quest layer's own file alongside players.yml / skill-progress.yml. Plain
  counter ticks are batched to the timer (`dirty` flag / `flushIfDirty`);
  completions, claims and refreshes save immediately.
- **Objectives** are placeholder: `kill_any` (+1 per non-player mob kill) and
  `deal_damage` (+finalDamage per hit on a non-player, melee or projectile),
  in `QuestObjectiveListener`. Real objective types are added as more
  handlers there; `QuestObjective` is the only enum to extend.
- **GUI** (`QuestMenu`): `/quests` opens the single-chest selector
  (paper/Daily, map/Weekly, filled-map/General). Each selector button's lore
  ends with the wait until that category's next refresh (`<time>` in
  `menu.selector.refresh-timer-format`; general shows `no-refresh-text`) -
  computed when the menu opens, not live-ticking. Clicking one opens the
  double-chest list of that category's 4 quests, **ordered lowest
  `required` first** (`roll` sorts the picked set ascending, so slot 0 =
  easiest and the row ramps up to the right). Each quest item shows title /
  objective / `progress`/`required` / reward, with a per-state material
  (`menu.list.state.*`: LIME_DYE in progress, glowing GOLD_INGOT complete,
  GRAY_DYE claimed, BARRIER unresolved). Clicking a complete-unclaimed quest
  claims it (placeholder reward = a chat line). Back arrow (slot 49) returns
  to the selector. An open list redraws in place as progress lands.
- **Command**: `/quests` (perm `dungeonplugin.quests`, default true). Admin
  (`dungeonplugin.admin`): `/quests refresh <cat>`, `/quests progress
  <kill|damage> <n>` (test without grinding), `/quests info`.
- **Untested on a server** like the rest of the plugin. Watch especially: the
  timezone/boundary math and the offline catch-up on the first real midnight
  and Friday; progress wipes hitting the right players; the double-chest slot
  layout rendering as intended.

## Template room generation (2026-09-17, replaces the old planner)

The exact door-pattern matching (normal_straight, branch_corner_l, ...) is
gone. A dungeon is now planned from `generation.template` in config.yml: an
ordered flow of slots (role, pool, size class), stretched per difficulty by
three numbers under `generation.template.difficulties` — `combat-rooms`
(3 at diff 1 to 7 at diff 9), `keys` (1–3, each key a corridor + parkour +
key-room branch off a different combat room) and `minibosses` (0–2, combat
rooms whose champion arrives through the boss summoning sequence; recipe
`mobs.room-roles.miniboss`, presentation `mobs.miniboss.summoning`). Config
version is **2**; the old composition/branching/critical-path sections died.

**Placement is anchor-chained**: each room is chosen from its pool during
planning and placed flush against the previous room's exit door — red marker
against red marker, a corridor of length zero. Alignment is per door:
horizontally on the marker strip's own centre (strips no longer need to be
centred on their wall), vertically on that door's own opening floor. Doors on
one file may sit at different floors, which is the whole stairs mechanism.
A 3D AABB check rejects overlap; a slot that cannot be placed fails the
generation loudly with the slot and every candidate's reason (backtracking
runs first). The only silent path is a **placeholder shell** for a pool with
no usable file: class-exact checkerboard box, doorways where the layout needs
them, a sign naming the role above each entrance, runtime mob anchors — a
fresh install plays end to end (proven by `TemplateLayoutGeneratorTest`,
which runs the real config with empty pools for all nine difficulties).

**Size classes** (`generation.size-classes`): spawn 35x18x35, large 67x34x67,
small 15x12x15 (provisional), great_hall 35x41x91 (provisional), boss
75x41x75. Validation is exact per class, loud otherwise. Pools by filename:
`spawn`, `link` (variants straight/corner/stairs), `combat`, `rest`,
`great_hall`, `boss`, `parkour`, `key`. Legacy `normal_*`/`branch_*` files
load into the combat pool (`branch_parkour*` into parkour) with a rename
hint; their shape suffix means nothing. A room with more doors than its slot
needs gets the extras filled with its own sampled wall block.

**The three authoring answers** the spec asked to be told, not guessed:

1. **A stairs link declares nothing.** Build a `link_stairs*.schem` (small
   class) with two doors whose air openings sit at different heights; the
   loader reads each door's own opening floor and the planner enters through
   the higher one for a `stairs: down` slot. Markers float exactly as
   always: a red strip on the outer wall with a ≥3x3 air opening below it —
   each door's walking floor is simply the bottom of its own opening.
   A `link_stairs` file whose doors share one floor logs a warning.
2. **The great hall is one schematic of its own class.** 35x41x91 is ~130k
   blocks — the cursor builder does that in a couple of ticks (the boss
   arena is already ~230k). Resize the class in config when the real room
   is built; nothing else cares about its length.
3. **The locked door is placed by the plugin at the connection point**, not
   authored into the schematic: `DungeonDoorManager` seals the flush doorway
   into the great hall (both wall planes, passable blocks only) exactly as
   it sealed the old corridor mouth, and opens it when the last key is in.
   Keys stay party state; `door-key-progress` reports x/y keys, the
   watchdog watches every key room separately, and `/dungeon door open`
   still overrides.

`/dungeon rooms` now ends with a template coverage report (every role, pool
and class → files or PLACEHOLDER); each generation logs a real/placeholder
summary per slot. `/dungeon settings` edits the per-difficulty template
numbers instead of the dead room counts. `verifyGenerated` still audits every
passage for passability after the build.

## Untested — read before first run

Nothing has ever run on a server; DungeonForge's own handover already said
that about most of ITS systems. On top of that, this port has never been
loaded at all. First-run checklist:

1. Server needs internet once (kotlin-stdlib via `libraries:`).
2. Fresh `plugins/DungeonPlugin/` appears with config.yml (v1), classes.yml,
   skills.yml (v6), rooms/, corridors/.
3. `/dungeon start 1` end-to-end (all rooms will be placeholder shells on a
   fresh install until new-format rooms exist): spawn shell -> hall ->
   combat -> stairs down -> combat -> rest -> combat -> great hall with
   sealed entrance -> corridor branch -> parkour -> key room; guardian drops
   the key, door opens, boss arena. Watch the flush doorways (wall against
   wall, no gap) and the stairs floor transition in particular.
4. `/class`, kit swap on enter, passives, F-abilities, sidebar.
5. Skill panel: gated nodes grey, buy on double click, points update.
6. `/dungeon api status` should list 19 event types.
7. `quests.yml` + `quest-data.yml` appear; `/quests` opens the selector;
   `/quests progress kill 100` completes a quest and lets it be claimed;
   `/quests refresh daily` rolls a new set and clears progress.

## Open items

- **Player-data migration is not built.** Old `plugins/DungeonForge/
  skill-progress.yml` (nodes/spent/class) is read compatibly if copied into
  `plugins/DungeonPlugin/` (same file name and shape). ClassSkills'
  `players.yml` is NOT compatible (different root key and fields) — a one-time
  importer is future work; decide whether live progress must survive.
- `models.themes` now has all five theme keys (`nether-redoubt` and
  `illager-citadel` added 2026-09-02, blank entries like the others, so they
  fall through to `models.defaults` until a model name is filled in). The
  other DungeonForge inherited gap still stands: prefab pool sizes must match
  per pool (see DF HANDOVER §6).
- DF's open prefab list (yellow wool in branch_parkour, four roled prefabs,
  three 68→69-deep branch fixes) applies unchanged — the same .schem files
  are bundled.
- The skill-panel carousel browses other classes read-only, and that state
  is now explicit (2026-09-02, "option 1" of the three sketched in DF §7.6
  question 1): while the carousel points at a non-active class the BIG
  panel's Info area is prefixed with a `PREVIEW - switch to <class>` banner
  (`skill-panel.info.preview-format`, blank to disable), a buy click there
  plays `skill-panel.sounds.deny` and the `skills-not-your-class` message now
  reads as guidance (carousel / `/class`) rather than a flat refusal. The
  carousel still does not *change* the active class - that stays in `/class`
  and the holographic selector (options 2 "drop the carousel" and 3 "carousel
  = class selection" were the roads not taken).
- The old 67-class room files (bundled and live) still load as combat rooms,
  but the bundled spawn.schem (35x18x35) is the only file matching its class;
  every generic branch_* file that is not 67x34x67 is now rejected loudly by
  class validation, exactly as specified. The parkour pool is empty until a
  67-class parkour room is built (the old 31x34x111 file no longer fits).
- The folder is committed to the Dungons repo — the revert path is git history, plus the untouched DungeonForge and classskills folders beside it.
