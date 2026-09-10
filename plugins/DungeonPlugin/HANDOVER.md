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
- **classes.yml** merges per-key, BUT also carries its own top-level
  `config-version` (`ClassesConfig.reload`): when the bundled version is
  higher than the server file's, the whole file is deleted and rewritten
  from the bundled copy. Bump `config-version` in the bundled
  `src/main/resources/classes.yml` whenever a default value there changes,
  or the server keeps its stale value. (Currently `1`.)
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

## Archer Focus: feel & fairness pass A (2026-09-08)

- **Softer break rules** (all in `classes.yml` `archer:`). `handleIncomingDamage`:
  a hit of `focus-break-damage-threshold` (4.0) final damage or more shatters
  the whole bar; anything less drops `focus-chip-penalty` (1) stacks.
  `handleProjectileMiss`: a whiff now costs `focus-miss-penalty` (2) stacks,
  not the whole bar. All these paths call `refreshClassPlayer` so the HUD
  keeps up.
- **Focus Shot is a real payoff.** `focusShotDamageMultiplier` takes rank:
  `focus-shot-base-multiplier` (2.0) + `focus-shot-multiplier-per-rank`
  (0.25) → ×2 at Rank I, ×3 at V. `castFocusShot` also sets
  `arrow.pierceLevel` from `focus-shot-pierce-from-rank` (4; rank − that + 1,
  cap 4), `isGlowing`, and speed `focus-shot-speed` (3.4). The old flat
  `focusShotDamageMultiplier(): 2.0` is gone.
- **FOCUSED indicator.** A quiet `END_ROD` aura in `PassiveService.tick()`
  while the bar is full and the player is in a dungeon, plus the existing
  sidebar readout line and the per-hit / full actionbar cues. (An earlier
  boss-bar version was removed at the user's request.)
- **Feedback methods:** `FeedbackService.focusShotFired(player, arrow)`
  (heavy release + a cyan/crit trail task riding the arrow) and
  `focusShotImpact(location)` (crit + firework + sweep burst, sharp hit sound).

## Archer Skyfall (2026-09-09)

- **Combo:** loose a *drawn bow shot* (not the left-click Focus Shot) while
  airborne from Wind Jump with a **full Focus bar** → that arrow detonates
  on impact (ground or enemy) as an entity-only AoE and the bar is spent.
- `AbilityService`: `windJumpUntil` map set in `archerDoubleJump` for
  `abilities.archer.wind-jump-window-seconds` (4.0); `isWindJumping(player)`
  = window live **and** `!isOnGround`. Wind Jump actionbar reads
  "Skyfall armed" when `PassiveService.focusFull(player)`.
- `PassiveService.handleBowShoot`: inside the existing full-Focus branch, if
  `classAbilities.isWindJumping` → `ItemService.markSkyfallArrow(projectile)`,
  `data.focus = 0`, feedback. `ItemService` gains `skyfallArrowKey` /
  `markSkyfallArrow` / `isSkyfallArrow` (mirrors the focus-shot marker).
- `CoreListener.onProjectileHit`: `isSkyfallArrow` →
  `PassiveService.detonateSkyfall(projectile.location, shooter)` then
  `projectile.remove()`, ahead of the focus-shot / miss handling.
- `detonateSkyfall`: mobs within `archer.skyfall-radius` (4.0) take
  `archerAttackBonus * archer.skyfall-damage-multiplier` (1.5) and a
  `skyfall-knockup` (0.35) pop away from centre. No terrain damage.
- **Visuals (revised 2026-09-09):** `skyfallDetonate` is a loose lime DUST +
  POOF + END_ROD poof (no explosion textures) with WIND_CHARGE_WIND_BURST +
  AMETHYST_BLOCK_CHIME instead of an explosion bang. Arrow trails come from
  the shared `FeedbackService.arrowTrail(projectile, color)` - one dust per
  tick, lime for Skyfall, cyan for the Focus Shot.

## Paladin "Holy Bulwark" overhaul (2026-09-09)

Turtle Master is gone. Taunt is now a fightable stance built around a
build-and-spend loop, plus rank-scaled Shield and a Smite passive.

- **Fightable stance.** `activateTaunt` = **Slowness
  `paladin.taunt-slowness-amplifier` (0 = I)** + **Resistance
  `taunt-resistance-amplifier` (1 = II)** + a transient
  `KNOCKBACK_RESISTANCE` modifier (`tauntKnockbackKey`,
  `taunt-knockback-resistance` 1.0) + a flat `taunt-damage-reduction`
  (0.30) in `handleIncomingDamage` (stacks with Resistance - a Paladin
  being swarmed has to survive).
- **Zeal -> Holy Nova + one armed Smite.** Damage soaked during Taunt
  banks `PlayerClassData.zeal` (`zeal-per-damage`, cap `zeal-threshold`
  90) - no mid-fight auto-fire. When Taunt ends, `power = zeal/threshold`
  drives `releaseHolyNova(player, power)` (mob damage + ally heal/regen in
  `nova-radius`, per-rank scaled) **and** `startRetribution(player, power)`
  which sets `retributionUntil` (= now + `smite-armed-timeout-seconds`
  safety cap) / `retributionPower`.
- **Smite = the one strike after Taunt.** No Smite during Taunt. The
  Paladin's **next axe hit** while not taunting (and `retributionUntil >
  now`) adds `smiteFlat(rank) + retribution-bonus * retributionPower`,
  then clears both fields (single use). Big END_ROD/TOTEM burst +
  `ITEM_TRIDENT_THUNDER` + `§6§lSMITE`. `retributionUntil` also cleared on
  a fresh `activateTaunt`. Sidebar shows `SMITE ARMED - next strike`;
  boss-bar title shows `next Smite +N`.
- **Consecrated Ground = a buff zone.** `startConsecration` stores a
  `Consecration(centre, radiusSq, expiresAt, task)` in `consecrations`
  (cancelled on re-cast / Taunt end); the task only refreshes mob Slowness
  (`consecration-slow-amplifier`, -1 = off) and draws the ring. The real
  effect is reactive via `inConsecration(player)`: **anyone** standing in
  it takes `consecration-damage-reduction` (0.20) less damage
  (`handleIncomingDamage`, after the class `when`) and heals 1 HP per
  `consecration-heal-per-damage` (5) damage dealt (`handleDamage`). The
  tick also burns mobs for `consecration-dot` (1.0, sourceless) + slows
  them. `consecration-radius` 8. `FeedbackService.paladinConsecrationTick`
  draws the ring.
- **Radius taunt.** `targetAllMobs` -> `targetMobsInRadius`
  (`taunt-radius` 32), re-pulled each `maintainTaunt`.
- **Presence + decay.** `updateTauntPresence` in `tick()` - gold aura,
  one-shot "Taunt fading..." under 1.6s (`tauntFadeWarned`). Judgment
  bleeds out of combat via `decayJudgmentOutOfCombat`
  (`judgment-decay-*`, `lastJudgmentCombatAt` set in `buildTaunt`).
- **Zeal boss bar.** `PassiveService.tauntStatus` -> `TauntStatus`;
  `FeedbackService.zealBars` + `updateZealBar` (from `refresh`) shows a
  bar while Taunt is up whose title carries Zeal, the live Smite bonus and
  the seconds left. `FeedbackService.shutdown()` re-added + wired in
  `onDisable`. Sidebar readout also shows `ACTIVE Ns | Zeal x/threshold`.
- **Shield rank identity.** `AbilityService.paladinShield`:
  `shield-hearts + shield-hearts-per-rank*(rank-1)`, wired
  `absorption-amplifier`, and from `shield-bless-min-rank` (3) also strips
  Slowness/Weakness + grants Resistance I for `shield-bless-seconds`.
  `FeedbackService.paladinShieldCast` gold flash.
- `ActiveTaunt` gains `rank`. `PlayerClassData` gains `zeal` +
  `lastJudgmentCombatAt` (cleared in `clearCombatResources`).

## Mage: Arcane Charge + Blink pass (2026-09-10)

Gives the Mage the build-and-spend loop + rank identity the other three
have. `PlayerClassData.arcaneCharge` (cleared in `clearCombatResources`).

- **Arcane Charge -> Surge.** `castArcaneBolt`: a normal bolt adds
  `mage.charge-per-cast` (1) on cast and `mage.charge-per-hit` (3) in its
  onImpact lambda when it hit a mob. At `mage.charge-threshold` (8) the
  *next* cast is a **Surge**: `arcaneCharge = 0`, free (no mana),
  `arcaneBoltDamage * surge-damage-multiplier` (2.2), pierces from
  `surge-pierce-from-rank` (3), splash x`surge-splash-multiplier` (1.6),
  fatter/brighter trail + bigger orb + FLASH (via new
  `ArcaneBoltFlight.launch(..., pierce, surge, onImpact)` - it now tracks
  `hitIds` and nudges past a hit to pierce). `addArcaneCharge` /
  `arcaneSurgeArmed` / `chargeThreshold` / `onArcaneSurgeHit` in
  `PassiveService`. Rank IV: `onArcaneSurgeHit` refunds
  `surge-mana-refund` (40); rank V: also an Arcane Nova
  (`surge-nova-radius`, `FeedbackService.arcaneSurgeNova`) at the impact,
  fired once even through pierce (closure flag). Sidebar `Charge x/8` /
  `SURGE armed`, purple aura in `tick()` when armed.
- **Blink.** `AbilityService.mageBlink`: rank-scaled distance
  (`blink-distance-per-rank`), full-look-vector travel unless
  `blink-vertical: false`, `blink-invuln-seconds` i-frames via
  `noDamageTicks`, and **no mana charged when a blink is blocked from the
  start**. From `blink-blast-min-rank` (2) the departure point detonates
  (`blink-blast-damage` + per-rank, a shove) and a connect feeds
  `blink-blast-charge` into Arcane Charge (`addArcaneChargeFromBlink`).
  `FeedbackService.mageBlink` / `mageBlinkBlast`. `safeBlinkDestination`
  now takes `(player, maxDistance, vertical)`.
- **Wand presets (2026-09-10):** the Mage staff's look is now a named
  preset under `mage.wand-presets`, chosen by `mage.wand-preset` (default
  `arcane-rod`). Each bundles `staff-item` (held Material), `orb-block`,
  `cast-sound`, `impact-sound`, `impact-particle`, `impact-lava` (bool -
  also flips Blink / nova / Surge cues to a fiery set), `impact-particles`,
  `trail-color/-size/-spacing/-accent/-accent-every`, `surge-trail-color`,
  `name`. Two shipped: **arcane-rod** ("The Arcane Rod", BREEZE_ROD +
  amethyst/purple, the original look) and **magma-wand** ("The Magma
  Wand", BLAZE_ROD + magma/fire). Resolved via new
  `ClassesConfig.mageWandString/Int/Double/Boolean/Material(leaf, default)`
  (`mage.wand-presets.<active>.<leaf>` -> default). `ArcaneBoltFlight`,
  `PassiveService.castBoltSound` / surge+armed sounds / aura,
  `ItemService.mageStaff`, `FeedbackService.mageFiery()` all read it. The
  Arcane Bolt cooldown is keyed to the held staff's Material now (varies
  by preset), not a hardcoded BLAZE_ROD.
- The trail rides up to three particle types: `DUST` always, plus
  `trail-accent` every `trail-accent-every` points and a sparser
  `trail-accent-2` (arcane-rod: END_ROD + ENCHANT; magma-wand: FLAME +
  SMALL_FLAME). Non-fiery impact adds ENCHANT alongside WITCH + DUST.
- Bigger face gap: `mage.bolt.muzzle-offset` 1.4 -> 2.0,
  `mage.bolt.trail.start-gap` 1.0 -> 1.5.
- `classes.yml` `config-version` -> 5.

## Mage Heal targeting: commit the highlight (2026-09-09)

Symptom: the heal-target glow reached far but the heal only landed
point-blank. Cause: both the glow and the cast re-ran `raycastHealTarget`
(a 12deg angular cone), so at range the click had to be pixel-perfect,
and the hover task only ran at 1 Hz so the glow was up to a second stale;
a missed cone silently self-heals.

- `AbilityService.tick()` -> `tickHealHover()`, moved off the 20-tick
  class loop onto its own `runTaskTimer(..., 4L, 4L)` so the glow tracks
  the crosshair.
- `castMageHeal` now targets `currentHealTarget(caster)`: the player under
  the live highlight if still online / alive / same world / within
  `heal-range`, else a fresh `raycastHealTarget` for that instant, else
  null (self-heal). The click confirms the glow instead of re-aiming.
- Future idea noted with the user: combine this with a homing heal orb
  (Arcane-Bolt-orb tech) that flies to the glowing ally.

## Warrior Berserk overhaul (2026-09-09)

Rage no longer auto-erupts. A full bar is a **banked Berserk** (and no
longer decays - `decayRageOutOfCombat` bails at threshold). `addRage` just
fires a "BERSERK READY - press Sneak" cue on the fill.

- **Manual unleash + Seismic Slam.** `CoreListener.onWarriorBerserkSneak`
  (`PlayerToggleSneakEvent`) -> `PassiveService.activateBerserk`
  (`BerserkActivationResult`). `startBerserk` sets `data.berserkStartedAt`
  (new `PlayerClassData` field, cleared in `clearCombatResources`),
  `rageActiveUntil`, STR/SPD via `refreshBerserkPotions`, then
  `seismicSlam` - AoE `warrior.slam-*` (damage 8 / radius 4 /
  knockback 0.6 + knockup 0.28 / Slowness III for slam-stagger-ticks).
  `FeedbackService.warriorSlam` (explosion + netherrack debris + embers +
  explode/roar/anvil).
- **Berserk = survival tool.** `applyBerserkOnHit` (from `handleDamage`
  while `isBerserk`): lifesteal `berserk-lifesteal-fraction` of melee
  damage dealt from rank `berserk-lifesteal-min-rank` (2);
  `handleIncomingDamage` cuts damage by `berserk-damage-reduction` from
  rank `berserk-resistance-min-rank` (3).
- **Cooldown.** After Berserk ends there is a `berserk-cooldown-seconds`
  (7.5) rest - `activateBerserk` returns `ON_COOLDOWN`
  (`berserkCooldownSeconds(player)` = `rageActiveUntil + cd - now`).
  `addRage` bails whenever `berserkCooldownSeconds > 0`, so **no Rage
  builds during Berserk or its cooldown**; the bar only starts refilling
  once the rest clears. Readout shows `cooldown Ns`.
- **Bloodlust (kills only).** From `bloodlust-min-rank` (4) each *kill*
  during Berserk (`CoreListener.onWarriorBloodlustKill` ->
  `PassiveService.bloodlustOnKill`) stretches `rageActiveUntil` by
  `bloodlust-ticks-per-kill` (20), hard-capped at `berserkStartedAt +
  berserk-max-seconds` (10); potions re-applied. "BLOODLUST +Ns" cue.
  Melee *hits* no longer extend - `applyBerserkOnHit` split into
  `berserkLifesteal` (still per-hit) + `bloodlustOnKill`.
- **Rank identity.** STR/SPD II at `berserk-strength-2-min-rank` (4) /
  `berserk-speed-2-min-rank` (5); lifesteal @2, resistance @3, Bloodlust
  @4, wider slam (`slam-shockwave-radius-multiplier`) at
  `berserk-shockwave-min-rank` (5).
- **Presence.** `updateBerserkPresence` in `tick()` (1 Hz): FLAME/SMALL_FLAME
  aura, one-shot "Rage fading..." under 1.6s left, "Your Rage subsides." on
  end (via `berserkActive` / `berserkFadeWarned` sets). Sidebar readout adds
  a `§6READY §7[Sneak]` state and a two-line ready prompt in `readoutLines`.

## Archer Wind Dash + Scope (2026-09-09)

- **Wind Dash** min rank lowered to `abilities.archer.wind-jump-double-charge-min-rank`
  **4** (was 5) - config default and the `archerDoubleJump` fallback.
- **Scope** (new, Focus rank `archer.scope-min-rank` = 5): crouch mid-air →
  `PassiveService.tryScope` grants `SLOW_FALLING` for
  `archer.scope-duration-ticks` (24) with a `archer.scope-cooldown-seconds`
  (3.0) rest. Fired from a new `CoreListener.onArcherScopeSneak`
  (`PlayerToggleSneakEvent`, alongside `onPaladinSneak`); gated on archer +
  rank + airborne + not gliding + off cooldown. Spyglass sound + END_ROD +
  "Scope" actionbar. `scopeReadyAt` map in `PassiveService`.

## Archer Wind Dash: max-rank second charge (2026-09-09)

- At Focus rank >= `abilities.archer.wind-jump-double-charge-min-rank` (now 4) a
  Wind Jump grants a second charge: `windDashChargeUntil` is set for
  `wind-jump-second-charge-seconds` (3.0). Pressing F again inside that
  window runs `archerDoubleJump(player, forward = true)` - a horizontal
  launch (`horizontalDirection * jump-velocity * wind-dash-forward-multiplier`
  (1.7), Y 0.3) with the same air-only gate, particles and wind-burst sound.
- `AbilityService.onSwapHands` checks `windDashChargeUntil` **before** the
  cooldown gate so the second charge ignores the 2.5s cooldown; spending it
  (or the vertical jump) still (re)applies the cooldown afterwards.
- `archerDoubleJump` gained a `forward` param; the vertical call site passes
  `false`. Actionbar appends " + Wind Dash" when the charge is armed, and
  "Wind Dash!" on the forward leg. The forward dash also refreshes
  `windJumpUntil`, so a Skyfall shot can still follow it.

## Class kit items are unbreakable (2026-09-09)

- `DungeonKitService.equipKit` wraps the Warrior sword / Archer bow /
  Paladin axe in a local `unbreakable()` (`isUnbreakable` + `HIDE_UNBREAKABLE`).
  `ItemService.taggedItem` does the same for every tagged item, so the Mage
  staff (and shards) are covered too. Loaner gear no longer wears out.

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
