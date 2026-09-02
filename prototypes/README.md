# prototypes/

Archived prototype plugin jars, committed as a **backup reference only**. They
are not built, not depended on by anything in this repo, and not meant to run
alongside DungeonPlugin. Kept in case their UI or mechanics are useful later.

Both are **compiled jars with no source** (Paper API 26.1.2, author "RidderNix",
v0.1.0). They predate the DungeonForge + ClassSkills merge and are superseded by
it. Recover behaviour with `javap` / a decompiler if needed.

## ClassSelect-0.1.0.jar

Standalone proof-of-concept class system, package `nl.riddernix.classselect`, no
dependencies. (Original download name was `ClassSelect-0.1.0 (1).jar`.)

- Three hardcoded classes: Forgotten Knight, Ashen Ranger, Hollow Acolyte.
- `PlayerClass` — stat map (`Attribute` -> Double), `WieldTier`
  (LIGHT/MEDIUM/HEAVY), looting level, `Passive` enum
  (LAST_STAND / QUICK_STEP / SIPHON), starting kit.
- `ClassManager` — stores the chosen class in a player PDC key; applies /
  strips attribute modifiers on join.
- `SkillTree` — built on **vanilla Minecraft advancements**
  (`AdvancementProgress`, generated advancement JSON). `SkillNode` =
  path / parentPath / icon / title / description / frame.
- `ClassDialog` / `SkillDialog` — Paper **Dialog API** screens with a custom
  bitmap-font portrait system.
- Registers a `ClassSelectAPI` in the Bukkit **ServicesManager**.
- Commands: `/class`, `/skills [list | unlock <skill> | reset]`.

## ClassSkillsScreen-0.1.0.jar

`depend: [ClassSkills]` — hard-depends on the old standalone **ClassSkills**
plugin and references `dev.thorb.classskills.ClassSkillsPlugin` /
`model.ClassType` / `PlayerSkillData` / `StatType` directly. Will not load
without that old jar present.

- One command `/classmenu` (alias `/classscreen`), one class
  `ClassScreenDialog`: a Paper Dialog-API screen that reads live ClassSkills
  data (stats, tree maxima, weapon / passive blocks, footer) and calls
  `select(player, ClassType)`.
- Self-description: "ClassSelect-style class screen backed by ClassSkills
  data." Essentially the `menu/ClassScreenDialog` UI that the merge's handover
  lists under "Deliberately NOT ported".

## Why they are not in the project proper

- Separate package root / separate plugin; different class identities than
  DungeonPlugin's `ClassType` (Warrior / Archer / Paladin / Mage).
- ClassSelect uses a ServicesManager API and an advancement-based tree; the
  merge deliberately removed the public API and renders the tree with in-world
  display entities (`SkillPanelManager`).
- ClassSkillsScreen needs the removed `dev.thorb.classskills` plugin to load.
- Everything they do is already ported (`ClassProgressionService` +
  `HolographicClassSelection` + `/class`; `SkillPanelManager`) or intentionally
  excluded.

The real ClassSkills source (including `menu/ClassScreenDialog.java` and
`menu/TextLayout.java`) already lives in `plugins/classskills/`. If a flat
Dialog-API class screen is ever wanted as an alternative to the in-world
panels, port from that source — not from these jars.
