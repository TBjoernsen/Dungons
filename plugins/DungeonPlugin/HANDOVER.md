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
| `util`, `world`, `build`, `generation` | Messages, void worlds, layout planner/builder | 1:1 |
| `room` | RoomTypes, RoomEvents, DungeonInstance, registry, marker scanner/definitions, NormalRoomLibrary, CorridorLibrary | 1:1 |
| `party`, `trap`, `door`, `mob`, `completion` | 1:1 | |
| `player`, `model`, `settings`, `menu` (PartyMenu only), `npc`, `fx`, `panel` | 1:1 | |
| `skills` | SkillTreeLibrary (v6: reads per-node `effect.*`), SkillProgressManager, SkillPanelManager, geometry, listener | merged |
| `classes` | ClassType/StatType, ClassesConfig, ClassProgressionService, ItemService, AttributeService, DungeonKitService, PassiveService, AbilityService, ArcaneBoltFlight, FeedbackService, CoreListener, ClassDungeonListener, HolographicClassSelection, ClassCommands | ClassSkills, rebuilt |
| `quest` | QuestCategory, QuestObjective/QuestDefinition, QuestConfig, QuestManager, QuestMenu(+Listener), QuestObjectiveListener, QuestBoardManager(+Listener), QuestCommand | **new 2026-09-03** |
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

## Mage Arcane Bolt: raycast, not a snowball (2026-09-08)

- `castArcaneBolt` no longer throws a `Snowball`. `ArcaneBoltFlight` is a
  per-tick `BukkitRunnable` that sweeps forward `mage.bolt.speed` blocks
  with one `World.rayTrace` (blocks + non-player LivingEntities, gated to
  `isInDungeon` for damage) - fast, straight, no gravity. A glowing
  `BlockDisplay` orb (`mage.bolt.orb.*`, AMETHYST_BLOCK, emissive, spinning)
  and a `Particle.DUST` trail (`mage.bolt.trail.*`, purple + END_ROD accent)
  ride the ray. Direct hit → `entity.damage`; impact point handed to
  `PassiveService.arcaneBoltSplash` (the old `handleArcaneBoltHit` splash
  body, unchanged).
- Removed: `PassiveService.handleArcaneBoltDamage` / `handleArcaneBoltHit`,
  the two `isArcaneBolt` branches in `CoreListener`, and
  `ItemService.markArcaneBolt` / `isArcaneBolt` / `arcaneBoltKey` (all dead
  once the projectile entity is gone).
- Behaviour change: outside a dungeon the bolt is now purely cosmetic
  (flies, no entity collision) where before the snowball simply did
  nothing. All damage/mana/cooldown/splash numbers are unchanged; new
  `mage.bolt.*` block in classes.yml also surfaces the previously
  code-only `mage.arcane-bolt-*` keys.

## Dungeon lives + loss XP (added 2026-09-08)

- **Shared party life pool.** `DungeonInstance` gains `livesRemaining` /
  `isFailed`; `DungeonRoomRegistry.register` seeds it from `classes.yml`
  `lives.*` (`count` + `per-player-bonus * (partySize - 1)`, default
  5 + 2·n). `DungeonLivesListener` (in `completion`) hears
  `DungeonPlayerDeathEvent`, spends one life per death (actionbar +
  sound to the party), and at zero calls
  `DungeonCompletionManager.fail(dungeon)` **next tick** (so the death
  event settles first). `lives.enabled: false` restores infinite respawns.
- **New end reason `FAILED`.** `DungeonCompletionManager` refactored:
  `complete()` and `fail()` share `endRun(reason)` (freeze → despawn mobs
  → `fireEnd` → announce → grace → `beginReturn` → cleanup, all reused).
  `fail()` shows a Defeat title instead of the victory one.
  `DungeonWorldManager.deleteWorld` maps `isFailed -> FAILED`; the bus
  still dedups so only the first end fires.
- **Loss XP.** `ClassProgressionService.runExperience(diff, kills,
  completed)` is the shared XP math; `awardDungeonLoss` pays
  `mobXP · dungeon-xp-multiplier · dungeon-loss-xp-fraction` (0.35, in
  classes.yml) - no completion bonus, no shard rolls.
  `ClassDungeonListener.onDungeonEnd` now pays `COMPLETED` in full and
  `FAILED` at the reduced rate; everything else pays nothing.
- **Known gap:** a trap-floor death currently spends a life too (the trap
  was designed to "cost nothing but the walk back"). Exempt it later with
  a damage-cause check in `DungeonLivesListener` if wanted.

## Warrior Dash polish (2026-09-08)

- `AbilityService.warriorDash` rewritten. Targeting was
  `filterIsInstance<Monster>()` (whiffed on non-`Monster` dungeon/boss
  mobs); now `LivingEntity` filtered by `queries.isDungeonMob(it) || it is
  Monster`, in a config `abilities.warrior.dash-radius` (2.6) sweep.
- **Berserk loop.** `PassiveService` exposes `isBerserk(player)` and
  `feedRage(player, amount)` (thin public wrapper over `addRage`, keeps its
  rank/berserk guards and can tip the bar over the threshold). A Dash that
  connects with ≥1 enemy calls `feedRage(player, warrior.dash-rage-on-hit)`
  (25) — so sword → dash to top off → Berserk. While `isBerserk`, the Dash
  multiplies `dash-speed` / `bonus-damage` by
  `abilities.warrior.berserk-speed-multiplier` (1.35) /
  `berserk-damage-multiplier` (1.8) and does a real knock-up; feedRage
  no-ops during Berserk by design (can't build Rage while raging).
- **Feedback.** New `FeedbackService.warriorDashCast` (sweep whoosh +
  a 6-tick particle streak riding the player; red dust/flame when berserk,
  cloud/crit otherwise) and `warriorDashImpact` (SWEEP_ATTACK + crit burst
  + crunch, only on a connect). Actionbar reads "Berserk Dash!" when empowered.
- New `classes.yml` keys are all additive so they merge into an existing
  server file cleanly (unlike changed keys).
- **Testing without a skill tree:** `/skills passiverank <0-5|tree> [player]`
  (admin) forces `PlayerClassData.debugSignatureRank`, which
  `ClassProgressionService.signatureRank` returns ahead of the tree read.
  Runtime-only; cleared on restart, class switch, or character reset.

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
- **Rewards are dungeon XP** (`pool.<cat>.<id>.reward-xp`, `QuestDefinition.
  rewardXp`). Claiming calls `ClassProgressionService.grantSkillExperience`,
  which now applies the quest XP multiplier to *every* skill-XP source and
  returns the effective amount; on a `classes.enabled: false` server it
  falls back to vanilla `giveExp`.
- **XP multiplier** (`xp-multiplier.*` in quests.yml): completing every quest
  in `daily` and/or `weekly` (claimed or not - `QuestManager.categoryComplete`)
  turns on that track's factor (`daily` 1.10, `weekly` 1.20 by default).
  Daily+weekly stack - `stacking: multiplicative` (1.32) or `additive`
  (1.30). `QuestManager.xpMultiplier(uuid)` computes it live from completion
  state, so a refresh (which clears the quests) drops it automatically;
  `general` never contributes. The class layer reaches it through
  `DungeonPlugin.questXpMultiplier(uuid)` (1.0 before the quest layer is up).
  `QuestConfig.categoryMultiplier` falls back to the shipped value (not 1.0)
  and `reload()` backfills the `xp-multiplier` block, so a `quests.yml` from
  before this feature still gets a working, tunable bonus - an in-place edit
  there wins over both.
- **GUI** (`QuestMenu`): `/quests` opens the single-chest selector
  (paper/Daily, map/Weekly, filled-map/General). Each selector button's lore
  ends with the wait until that category's next refresh (`<time>` in
  `menu.selector.refresh-timer-format`; general shows `no-refresh-text`) -
  computed when the menu opens, not live-ticking. Clicking one opens the
  double-chest list of that category's 4 quests, **ordered lowest
  `required` first** (`roll` sorts the picked set ascending, so slot 0 =
  easiest and the row ramps up to the right). Each quest item shows title /
  objective / `progress`/`required` / `reward-xp`, with a per-state material
  (`menu.list.state.*`: LIME_DYE in progress, glowing GOLD_INGOT complete,
  GRAY_DYE claimed, BARRIER unresolved). Clicking a complete-unclaimed quest
  claims it. Back arrow (slot 49) returns to the selector. An open list
  redraws in place as progress lands.
- **Multiplier visuals**: a selector button for a fully-cleared category
  glows and gains a `✔ All complete` lore line (`menu.selector.complete-text`
  / `-general`). Slot 22 holds an `EXPERIENCE_BOTTLE` readout
  (`menu.selector.multiplier.*`) showing the current factor and a per-track
  breakdown. `addProgress` announces the transition in chat + a challenge
  sound. When the class layer is on, the sidebar gains an `XP Bonus: ×N.NN`
  line while the factor is above 1.0 (`FeedbackService`).
- **Quest board** (`QuestBoardManager` + `QuestBoardListener`, 2026-09-07):
  a free-standing in-world notice board built from display/interaction
  entities, same furniture model as the difficulty/skill panels (spawned
  non-persistent from `quest-boards.yml`, per-viewer overlays, proximity
  sweep on a 10-tick task, orphan sweep, `dungeon_quest_board` PDC keys,
  transient reach boost to `board.click-range` while within
  `board.activation-radius`). Placed with `/quests board place|remove|list`
  (admin); placement snaps to the block grid (`board.snap-to-grid`,
  block-centre X/Z, floor Y, nearest-90° yaw) so the axes stay square.
  Note hitboxes and the page arrow are **per-viewer** overlay entities
  rebuilt on page flip, so they track the cards on both the two-column and
  the centred (General) page. `board.layout-version` resets the whole
  `board:` block to bundled defaults while the layout is being tuned. **Page 0** = Daily (left column) + Weekly (right column), **page
  1** = General; right/left arrows flip. Shared per board: backing
  `BlockDisplay` + log frame, "Quest Board" title, 8 note hitboxes (L0-3 /
  R0-3) + 2 arrow hitboxes - the clicking player's own page decides what a
  hitbox does (`resolveNote`). Per viewer: a parchment `TextDisplay` per
  note (tan background, brighter when complete-unclaimed) carrying
  `<category> Quest: <title>` / refresh timer / description / progress bar +
  counter / `Completed!` state, an XP-multiplier strip under the title, and
  the visible arrow. Overlays rebuild on progress/claim/refresh
  (`refreshViewer` / `refreshAllViewers` from `QuestManager`) and every
  `board.overlay-refresh-seconds` so the timers move. All layout, palette
  and text in `quests.yml` `board:` - coordinates are first-guess and need
  live tuning. The chest `/quests` menu stays as the tested fallback.
- **Command**: `/quests` (perm `dungeonplugin.quests`, default true). Admin
  (`dungeonplugin.admin`): `/quests refresh <cat>`, `/quests progress
  <kill|damage|all> <n>` (`all` advances both objectives, so one command
  finishes a category), `/quests info` (per-slot state + multiplier).
- **Untested on a server** like the rest of the plugin. Watch especially: the
  timezone/boundary math and the offline catch-up on the first real midnight
  and Friday; progress wipes hitting the right players; the double-chest slot
  layout rendering as intended.

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
7. `quests.yml` + `quest-data.yml` appear; `/quests` opens the selector;
   `/quests progress all 99999` finishes every active quest (both objective
   types) so a category can be cleared in one command; claim gives dungeon
   XP scaled by the multiplier; clearing all of `daily` turns on the ×1.10
   bonus (selector glows, slot-22 readout, sidebar line). `/quests info` as
   a player shows per-slot state and the computed multiplier.
   `/quests refresh daily` rolls a new set, clears progress, drops the bonus.
8. `/quests board place` drops the in-world board; walk to ~5 blocks, read
   the Daily/Weekly notes, right-arrow to General and back, claim a
   completed note. Coordinates in `quests.yml` `board:` almost certainly
   need tuning on first look.

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
- The folder is committed to the Dungons repo — the revert path is git history, plus the untouched DungeonForge and classskills folders beside it.
