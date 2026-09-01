# DungeonPlugin — handover

Merge of **DungeonForge** (`C:\Modding\Plugins\DungeonForge`, was Java, 19.0k
lines) and **ClassSkills** (`..\classskills`, Kotlin, 4.4k lines) into one
Kotlin plugin. The two source plugins are **read-only reference** — never edit
them; they are the revert path. This folder is the only thing being written.

**Status 2026-09-01: the full port compiles clean.**
`build/libs/DungeonPlugin-0.1.0.jar` builds with zero errors and zero
warnings; 66 Kotlin files, ~19.2k lines. Nothing has run on a server yet — see
"Untested" below.

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
| `util`, `world`, `build`, `generation` | Messages, void worlds, layout planner/builder | 1:1 |
| `room` | RoomTypes, RoomEvents, DungeonInstance, registry, marker scanner/definitions, NormalRoomLibrary, CorridorLibrary | 1:1 |
| `party`, `trap`, `door`, `mob`, `completion` | 1:1 | |
| `player`, `model`, `settings`, `menu` (PartyMenu only), `npc`, `fx`, `panel` | 1:1 | |
| `skills` | SkillTreeLibrary (v6: reads per-node `effect.*`), SkillProgressManager, SkillPanelManager, geometry, listener | merged |
| `classes` | ClassType/StatType, ClassesConfig, ClassProgressionService, ItemService, AttributeService, DungeonKitService, PassiveService, AbilityService, FeedbackService, CoreListener, ClassDungeonListener, HolographicClassSelection, ClassCommands | ClassSkills, rebuilt |
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

## Untested — read before first run

Nothing has ever run on a server; DungeonForge's own handover already said
that about most of ITS systems. On top of that, this port has never been
loaded at all. First-run checklist:

1. Server needs internet once (kotlin-stdlib via `libraries:`).
2. Fresh `plugins/DungeonPlugin/` appears with config.yml (v1), classes.yml,
   skills.yml (v6), rooms/, corridors/.
3. `/dungeon start 1` end-to-end: gates, key door, guardian, arena, boss.
4. `/class`, kit swap on enter, passives, F-abilities, sidebar.
5. Skill panel: gated nodes grey, buy on double click, points update.
6. `/dungeon api status` should list 19 event types.

## Open items

- **Player-data migration is not built.** Old `plugins/DungeonForge/
  skill-progress.yml` (nodes/spent/class) is read compatibly if copied into
  `plugins/DungeonPlugin/` (same file name and shape). ClassSkills'
  `players.yml` is NOT compatible (different root key and fields) — a one-time
  importer is future work; decide whether live progress must survive.
- The two DungeonForge inherited gaps still stand: `models.themes` lacks
  `nether-redoubt` and `illager-citadel`; prefab pool sizes must match per
  pool (see DF HANDOVER §6).
- DF's open prefab list (yellow wool in branch_parkour, four roled prefabs,
  three 68→69-deep branch fixes) applies unchanged — the same .schem files
  are bundled.
- The skill-panel carousel still browses other classes read-only (DF §7.6
  question 1 was never answered); unlocks in a browsed class are refused with
  `skills-not-your-class`, exactly as before.
- The folder is committed to the Dungons repo — the revert path is git history, plus the untouched DungeonForge and classskills folders beside it.
