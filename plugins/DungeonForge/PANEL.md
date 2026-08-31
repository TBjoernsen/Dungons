# Building an in-world panel

How the difficulty panel is built, in enough detail to build a different one
without reading the whole class again. Assumes Java and Bukkit, assumes no
knowledge of this codebase. Font metrics and colours live in `STYLE.md`; this
document is about structure and mechanism.

The visual conventions in `STYLE.md` are not repeated here, but §7 below
explains *why* two of those numbers (4.5 and 40) exist at all.

---

## 1. The model

A panel is **furniture, not a menu**. An admin places it once, it stands there
forever, and anyone who walks up can use it. There is no open/close, no
inventory, no packet-level UI.

Three properties follow from that and shape everything else:

- **The world save never contains it.** Every entity is spawned with
  `setPersistent(false)`. `panels.yml` holds five numbers per panel and nothing
  else; the entities are rebuilt from those numbers whenever a chunk loads.
  Duplication is therefore impossible by construction, and a crash leaves
  nothing behind.
- **Most of it is shared.** One set of entities serves every player. Only the
  number row exists per viewer, and only for players who have actually clicked.
- **Nothing about it is animated by the server.** There is no render loop. The
  one repeating task is a proximity sweep at 2 Hz; the sliding carousel is
  entirely client-side interpolation. See §6.

---

## 2. Where the code is

| Path | What it does |
|---|---|
| [`panel/DifficultyPanelManager.java`](src/main/java/nl/riddernix/dungeonforge/panel/DifficultyPanelManager.java) | Everything: placement, rendering, per-player rows, clicks, persistence, cleanup. ~1000 lines, the only file that matters. |
| [`panel/DifficultyPanelListener.java`](src/main/java/nl/riddernix/dungeonforge/panel/DifficultyPanelListener.java) | Six event handlers, each one line: chunk load, right click, left click, quit, world change, respawn. Holds no state. |
| [`DungeonForgePlugin.java`](src/main/java/nl/riddernix/dungeonforge/DungeonForgePlugin.java) | Owns the two `NamespacedKey`s (`panelIdKey`, `panelRoleKey`), constructs the manager, calls `load()`, registers the listener, schedules `panelManager::tick` every 10 ticks, and calls `despawnAll()` on disable. |
| [`command/DungeonCommand.java`](src/main/java/nl/riddernix/dungeonforge/command/DungeonCommand.java) | `handlePanel` (place/move/remove/removeall/list), the shared `readLocation` coordinate parser, and `startFromPanel` — the callback the Enter button fires into. |
| [`util/Messages.java`](src/main/java/nl/riddernix/dungeonforge/util/Messages.java) | MiniMessage deserialisation and `Messages.ph(...)` placeholders. Used for both chat messages and the `<n>` substitution in number glyphs. |
| `plugins/DungeonForge/panels.yml` | Runtime storage. Written by `save()`, read by `load()`. |

### Config it reads

Everything under **`difficulty-panel`** in `config.yml` (lines ~963–1070).
Grouped by what it controls:

| Keys | Controls |
|---|---|
| `remove-radius`, `cleanup-radius`, `activation-radius` | How near you must be to remove a panel, how far the orphan sweep reaches, how near a player must be to keep their personal row |
| `brightness`, `view-range`, `flip-facing` | Fixed light level, render distance multiplier, turning the panel around in place |
| `slide-ticks` | Client interpolation duration for the carousel |
| `heading.*` | Title text, height, scale |
| `numbers.*` | Height, `spacing`, `centre-extra`, `visible-each-side`, `scales`, `opacities`, `format` |
| `arrows.*` | Glyphs, `offset` from centre, height, scale |
| `button.*` | Label, height, scale, `flash-ticks` |
| `button.plate.*` | The whole plate geometry — blocks, padding, border, corner step, the three z-offsets, the two label nudges |
| `hitboxes.*` | Interaction box sizes for the two arrows and the button |
| `sounds.*` | `click`, `deny`, `enter` |

It also reads `messages.panel-*`, `messages.menu-replaced*` and
`messages.location-usage` for command feedback.

### Shared with the skill tree panel — and what is not

Less than you would hope. The skill panel duplicates rather than reuses:

**Genuinely shared**

- `NamespacedKey` *pattern*, but **not the keys**. The skill panel has its own
  `dungeon_skill_panel` / `dungeon_skill_panel_role`. This is deliberate and
  load-bearing: each manager's orphan sweep deletes anything carrying its own
  key whose panel it does not recognise, so a shared key would make each sweep
  eat the other's entities.
- `Messages`, MiniMessage, and the colours in `STYLE.md`.
- Two config keys: `SkillPanelManager` reads `difficulty-panel.arrows.left`
  and `.right` for its class carousel arrows (lines 750 and 753). The only
  cross-read in either direction.

**Duplicated, not shared**

- The carousel. `SkillPanelManager` has its own implementation of the same
  recipe — centre large, neighbours faded, slide by teleport interpolation.
  The behaviour matches; the code is a second copy. Extracting a shared
  component was deferred to avoid rebuilding the working difficulty panel
  mid-feature.
- The placement math (`facing`/`rightward`), the `Placement` record, the
  per-viewer overlay technique, the storage/chunk-load/orphan-sweep lifecycle.
  All present in both, written twice.
- Font metrics: the difficulty panel exposes 4.5 and 40 as config
  (`button.plate.label-y-pixels`, `.pixels-per-block`); the skill panel
  hardcodes them as `GLYPH_CENTRE_PIXELS` and `/ 40.0`.

If you build a third panel, this is the moment to extract the shared parts —
see §9.

---

## 3. From command to standing panel

`/dungeon panel place [x y z [yaw]]` →

1. **`DungeonCommand.handlePanel`** checks `dungeonforge.admin`, then
   `readLocation(player, tokens)` — no arguments means the player's own
   location including their yaw; three or four arguments are absolute or
   `~`-relative coordinates.
2. **`DifficultyPanelManager.place(where)`**
   ```java
   String id = UUID.randomUUID().toString().substring(0, 8);
   Location base = where.clone();
   base.setPitch(0.0F);          // panels are always upright
   panels.put(id, base);
   render(id, base);
   save();
   ```
   The eight-character id is what every entity is tagged with. The location's
   **yaw is the panel's facing** — that is the only orientation input.
3. **`render(id, base)`** does the work. It bails if the chunk is not loaded,
   then **always calls `clearEntities` first** — so calling it twice can never
   leave two panels on top of each other. This is what makes `/dungeon reload`
   and chunk reload safe.

`render` spawns, in order: heading → shared number row → left arrow → right
arrow → button plate → button label → three hitboxes. Every id it collects goes
into `spawned.put(id, ids)`, and the fill bars separately into `buttons` (the
press-flash needs them) and the number row into `sharedNumbers` (per-player
hiding needs them).

---

## 4. Anchor and orientation

**The anchor is the base location itself** — the exact spot the command was run,
pitch forced to 0. It is the panel's horizontal centre and its vertical zero.
Every element is placed relative to it, and nothing is ever placed absolutely.

The whole orientation model is four lines, repeated in `render`,
`ensurePersonalRow` and `animateRow`:

```java
float yaw = base.getYaw() + (config.getBoolean("difficulty-panel.flip-facing", false) ? 180.0F : 0.0F);
double radians = Math.toRadians(yaw);
Vector facing    = new Vector(-Math.sin(radians), 0.0, Math.cos(radians));
Vector rightward = new Vector(facing.getZ(), 0.0, -facing.getX());
```

- `facing` is the direction the panel looks — the unit vector for the yaw.
- `rightward` is `facing` rotated 90°. The viewer stands on the facing side
  looking back, so **their** right hand points along it. Numbers grow that way
  and `>` sits at `+offset`.
- `flip-facing` exists because a placement that reads mirrored is easy to
  create and annoying to fix by hand; it turns the panel around without editing
  `panels.yml`.

These are bundled into a record passed to every spawn helper:

```java
private record Placement(World world, Location base, float yaw, Vector facing, Vector rightward, int brightness) { }
```

**Every element's position is the same three-term expression:**

```java
Location at = placement.base().clone()
        .add(placement.rightward().clone().multiply(x))   // sideways
        .add(0.0, y, 0.0)                                 // up
        .add(placement.facing().clone().multiply(z));     // out of the panel
at.setYaw(placement.yaw());
at.setPitch(0.0F);
```

so a layout is nothing more than a table of `(x, y, z)` triples. Add a new
element by picking three numbers.

Every display uses `Display.Billboard.FIXED`. The entity's yaw does the
orienting; the display never turns to face the player. `CENTER` billboarding
would make a flat panel rotate into nonsense as you walk past.

---

## 5. The layers, and the depth budget

`z` is distance out of the panel face, in blocks. The values are small and
their *ordering* is the entire point — this is the part that silently breaks.

| Layer | `z` | Extent | Why |
|---|---|---|---|
| Border bars | `border-z` 0.010 | 0.000 – 0.020 | Behind the fill |
| Fill bars | `fill-z` 0.031 | 0.021 – 0.041 | 1 mm clear of the border |
| Heading, arrows, numbers | 0.03 | — | Text, no depth of its own |
| Hitboxes | 0.05 | — | In front of everything clickable |
| Button label | derived ≈ 0.056 | — | Must clear the fill's front face |

**Block displays are centred on their offset, so each bar spans half its depth
either side.** With `depth: 0.02`, a bar at `border-z: 0.010` occupies
0.000–0.020 and a bar at `fill-z: 0.031` occupies 0.021–0.041. Change `depth`
without changing the two offsets and the layers overlap, and the border ring
z-fights against the fill.

The label's z is **derived, never a constant**:

```java
double labelZ = config.getDouble(path + "fill-z", 0.031)
        + config.getDouble(path + "depth", 0.02) / 2.0
        + config.getDouble(path + "label-clearance", 0.015);
```

The fill bars are solid blocks that write depth. A label sitting level with
them was hidden from close up and only broke through at a distance, where depth
precision degrades — which reads as "the text appears when I walk away", one of
the more confusing bugs to diagnose. Deriving it from `fill-z + depth/2`
guarantees the label clears the front face whatever the plate is set to.

---

## 6. Per-player state

Two maps hold everything:

```java
/** Each player's chosen difficulty. Survives walking away; dropped on logout. */
private final Map<UUID, Integer> selections = new HashMap<>();
/** Per player, per panel: their personal number-row entities. */
private final Map<UUID, Map<String, List<UUID>>> personalRows = new HashMap<>();
```

| Part | Shared or per-viewer |
|---|---|
| Heading, arrows, button plate, button label, hitboxes | Shared |
| Number row | **Both** — a shared default row, plus a personal row per engaged player |

### How the swap works

The shared row is drawn once, at selection 1, for everybody. It is what a
player sees before their first click. The moment they click an arrow,
`ensurePersonalRow` spawns them nine text displays with:

```java
text.setVisibleByDefault(false);   // invisible to the world
...
player.showEntity(plugin, display); // shown to exactly one person
```

and then `setSharedRowHidden(player, panelId, true)` calls
`player.hideEntity(...)` on the shared row for that player only. **The personal
row hides the shared one rather than covering it** — two rows in the same place
would z-fight and read as doubled text.

Cost: nine text displays per engaged player standing near a panel.

### The one repeating task

`panelManager::tick` runs every 10 ticks and does **only** proximity
management — it renders nothing and animates nothing:

- engaged player within `activation-radius` → make sure their row exists
- otherwise, if they have a row → remove it
- rows whose owner is offline → remove, or they stand invisible forever

`selections` deliberately outlives the row. Walking away destroys nine
entities; it does not forget which difficulty you picked. Only
`handleQuit` clears both.

Note the asymmetry between the two spawn paths, which is easy to get wrong:

- **`renderNumberRow`** (shared) spawns only numbers within `visible-each-side`
  of the selection and skips anything outside 1–9. Five entities at most. It
  never animates, so it needs nothing else.
- **`ensurePersonalRow`** (per viewer) always spawns **all nine**, using
  `numberPose` to park off-carousel numbers at scale 0.05 and minimum opacity.
  Interpolation can only move an entity that already exists, so a number that
  has to slide back in must have been there all along.

---

## 7. A click, end to end

1. The three `Interaction` entities carry `panelIdKey` and `panelRoleKey` in
   their PDC. Interaction boxes are the only clickable things; text displays
   have no hitbox at all.
2. **`DifficultyPanelListener`** catches both click types:
   - `PlayerInteractEntityEvent` — right click. **Fires once per hand**, so
     `event.getHand() != EquipmentSlot.HAND` returns early or every click
     counts twice.
   - `PrePlayerAttackEntityEvent` — left click. Needed separately because a
     left click arrives as an attack, and it arrives **even against
     invulnerable entities**, so `setInvulnerable(true)` does not suppress it.

   Both cancel the event and call `handleClick`.
3. **`handleClick`** reads the role tag and switches:
   `hit-arrow-left` → `shift(-1)`, `hit-arrow-right` → `shift(+1)`,
   `hit-button` → `enter`. All state is keyed by player UUID, so two people
   clicking in the same tick cannot interfere.
4. **`shift`**:
   ```java
   int current = selections.getOrDefault(player.getUniqueId(), 1);
   int next = Math.clamp(current + direction, 1, 9);
   if (next == current) { playSound(player, "deny", 0.6F); return; }
   selections.put(player.getUniqueId(), next);
   playSound(player, "click", 1.0F + next * 0.05F);
   if (ensurePersonalRow(player, panelId, base, current)) {
       animateRow(player, panelId, base, next);
   }
   ```
   Two details worth copying. The row **stops** at 1 and 9 rather than
   wrapping — wrapping makes 9→1 a one-click accident, and the carousel fade
   already reads as a real edge. And `ensurePersonalRow` is seeded with
   `current`, the *old* selection, before `animateRow` moves it to `next`: a
   freshly spawned row therefore starts where the player was, so the very first
   click animates instead of snapping.
   The click pitch rises with the number, which gives the carousel an audible
   direction for free.
5. **`enter`** plays a sound, calls `flashButton(panelId)`, and hands off to
   `plugin.command().startFromPanel(player, selection)`. The flash is on the
   **shared** button so everyone at the panel sees it pressed — it is a
   physical button on a physical installation. Hover cannot be detected
   server-side, so "being used" is the only feedback available.

---

## 8. The animation — there is no animation code

The single most reusable idea here: **the server never draws a frame.** It sets
a destination and lets the client interpolate.

At spawn: `text.setTeleportDuration(slideTicks)`. On every shift, `animateRow`
does this per number and stops:

```java
NumberPose pose = numberPose(index + 1, selection);
display.teleport(numberLocation(placement, pose));
display.setInterpolationDelay(0);
display.setInterpolationDuration(slideTicks);
display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
        new Vector3f(pose.scale(), pose.scale(), pose.scale()), new Quaternionf()));
display.setTextOpacity(pose.opacity());
```

- `setTeleportDuration` makes the **position** lerp instead of snapping.
- `setInterpolationDelay(0)` + `setInterpolationDuration(n)` makes the
  **transformation** (scale) lerp.
- Opacity is **not** interpolated by the client — it steps. In practice the
  scale change carries the motion and nobody notices.

Nine `teleport` calls per click, no scheduled task, no per-tick cost.

`numberPose` is the whole layout function:

```java
int step = number - selection;
int magnitude = Math.abs(step);
double x = step * spacing + Math.signum(step) * centreExtra;
if (magnitude > visible) {
    return new NumberPose(number, x, 0.05F, MINIMUM_OPACITY);
}
float scale  = scales.get(Math.min(magnitude, scales.size() - 1)).floatValue();
byte opacity = (byte) Math.clamp(opacities.get(Math.min(magnitude, opacities.size() - 1)),
        MINIMUM_OPACITY, 255);
```

`centre-extra` is added with the *sign* of the step, which pushes both
neighbours outward from the enlarged centre number without moving the centre —
the enlarged glyph needs breathing room the plain spacing does not give it.

Numbers past the carousel edge are **shrunk to 0.05, not removed**, so they can
grow back in with the same interpolated slide. Removing and respawning would
snap.

---

## 9. Persistence, restart, reload

**`panels.yml` stores five values per panel and nothing else:**

```yaml
panels:
  a1b2c3d4:
    world: world
    x: 128.5
    y: 65.0
    z: -44.5
    yaw: 90.0
```

No entity UUIDs, no layout, no styling. Everything else is derived from config
at render time, which is why a config change plus `/dungeon reload` restyles
every existing panel.

| Event | What happens |
|---|---|
| **Server start** | `load()` reads `panels.yml`, drops panels whose world is not loaded (with a warning), calls `render` on each, then `sweepOrphans()` |
| **Chunk load** | `handleChunkLoad` matches the chunk against every panel's base and re-renders, **one tick later** via `runTask` to stay clear of the load itself |
| **Chunk unload** | Nothing. The entities are non-persistent, so they simply cease to exist |
| **`/dungeon reload`** | `reload()` → `renderLoadedPanels()` → `render()`, which clears first. Fresh styling from config, same locations |
| **Server stop** | `despawnAll()` removes every personal row and every panel's entities |

Because `render` re-creates the shared row, it also has to invalidate personal
rows that pointed at the old entities:

```java
for (Map<String, List<UUID>> rows : personalRows.values()) {
    rows.remove(id);
}
```

The proximity tick rebuilds them for engaged players still nearby, within half
a second.

---

## 10. Cleanup and orphan guards

Five layers, each covering a failure the others miss:

1. **`setPersistent(false)` on every entity.** Nothing is ever written to the
   world save. This is the one that makes duplication structurally impossible
   rather than merely unlikely.
2. **PDC tags on everything.** `panelIdKey` (which panel) and `panelRoleKey`
   (which part). Every sweep works off these; an untagged entity is not ours.
3. **`clearEntities(id, base)` before every render.** Removes the tracked ids,
   then sweeps `getNearbyEntities` within `cleanup-radius` for anything tagged
   with **this panel's id, or with an id no longer in `panels.yml`**.
4. **The chunk force-load inside that sweep**, which is subtle enough to be
   worth copying verbatim:
   ```java
   for (int chunkX = (int) (base.getX() - reach) >> 4; chunkX <= (int) (base.getX() + reach) >> 4; chunkX++) {
       for (int chunkZ = (int) (base.getZ() - reach) >> 4; chunkZ <= (int) (base.getZ() + reach) >> 4; chunkZ++) {
           world.getChunkAt(chunkX, chunkZ);
       }
   }
   ```
   An entity in an unloaded neighbour chunk is invisible to `getNearbyEntities`
   and would survive the rebuild to stand alongside its replacement. A panel
   near a chunk border hits this immediately.
5. **`sweepOrphans()` on load**, which walks every entity in every world and
   removes tagged ones whose panel id is not in `panels.yml` — the net for
   entities left by a crash, or by a panel removed while its chunk was
   unloaded.

Plus `tick()` removing rows whose owner logged out, and `removeAll()` for the
administrative escape hatch.

---

## 11. The parts that were hard to get right

These are client constraints, not design choices. They will bite anyone
rebuilding this.

**Text is drawn upward from its own position.** A `TextDisplay` anchors at the
bottom of its line, not its centre. Centring a label inside a box means moving
it **down by half a line — 4.5 font pixels**:

```java
double labelUnit = buttonScale / config.getDouble("...pixels-per-block", 40.0);
double labelY = buttonHeight + config.getDouble("...y-offset", 0.0)
        - config.getDouble("...label-y-pixels", 4.5) * labelUnit;
```

Note the correction scales with the label — it is in font pixels converted to
blocks, never a fixed block offset.

**A centred line includes the trailing gap after its last glyph.** Every glyph
is followed by 1 px of spacing, including the final one, so a centred string
sits half a pixel left of true centre. Corrected by shifting right 0.5 px:
`label-x-pixels: 0.5`. Small, but visible on a plate with a tight border.

**Opacity has a floor.** `MINIMUM_OPACITY = 26`, and every opacity is clamped
to it. Below that the client stops drawing the text rather than fading it
further, so 26 is the dimmest a glyph can be while still existing. Off-carousel
numbers use exactly this value plus a 0.05 scale — present, invisible, ready to
interpolate back.

**Solid block displays write depth.** Anything meant to be seen in front of one
must clear its front face, and "in front" has to account for bars being centred
on their offset. See §5.

**`Interaction` entities anchor at their feet.** To centre a hitbox on the
visual it covers you subtract half its height:

```java
.add(0.0, y + 0.15 - height / 2.0, 0.0)
```

The `+0.15` is an additional fudge: the visible glyph centre sits slightly
above the element's nominal base height.

**Hitboxes are deliberately larger than the text.** Aiming a crosshair is far
less precise than pointing a mouse; arrows use 1.3 × 1.3 blocks for glyphs
much smaller than that.

**The plate is block displays, not text displays with backgrounds.** An earlier
version used text displays rendering the label dyed to their own background
colour as a width reference. That was only invisible by coincidence: at range
the enlarged gold border plate's copy of the label showed through and doubled
the text. Block displays carry no text and cannot do this. The cost is that a
block display shows a block *texture* rather than a flat colour — concrete is
the closest thing to a clean fill.

**A text-display background cannot hold a gradient.** It is one flat colour.
The gold gradient lives in the label itself, exactly like the chat prefix.

**Sizes are computed from the measured label, not authored.** `labelPixelWidth`
sums `glyphWidth(c) + (bold ? 1 : 0) + 1` per character; padding, border and
corner steps are all expressed in font pixels and multiplied by
`scale / pixels-per-block`. The border is `fill + 2 × thickness` in both
dimensions, so it is an exact even ring **by construction** rather than by
tuning. Change the button text and the plate re-fits itself.

**Stepped corners are three overlapping bars**, not a mask:

```java
double[][] bars = {
        {width,               height - 4.0 * step},
        {width - 2.0 * step,  height - 2.0 * step},
        {width - 4.0 * step,  height},
};
```

Full width but shortest, one step in and one step taller, narrowest and full
height. `corner-step-pixels: 0` gives square corners.

**A block display fills the unit cube from its own corner**, so the
transformation translation pulls it back by half its size to centre it:

```java
new Vector3f((float) (-width / 2.0), (float) (-height / 2.0), (float) (-depth / 2.0))
```

**One dead end to ignore:** `spawnText` takes a `Color background` parameter
that every call site passes `null` for, and the private `argb(String)` helper
is never called at all. Both are vestigial from the text-display plate. Do not
copy them.

---

## 12. Building a different panel with this machinery

### Reusable as-is

Copy these unchanged; nothing in them knows about difficulties.

- **The orientation model** — `Placement`, the `facing`/`rightward`
  derivation, and the `base + rightward·x + up·y + facing·z` placement
  expression. This is the core of the whole thing.
- **`spawnText`, `spawnBar`, `spawnHitbox`** — three generic spawn helpers
  parameterised by `(x, y, z)`, size and role tag.
- **`spawnButtonPlate`, `steppedPlate`, `labelPixelWidth`, `glyphWidth`** —
  a self-fitting button of any label. Only the config path prefix is specific.
- **The lifecycle**: `load` / `render` / `clearEntities` / `sweepOrphans` /
  `handleChunkLoad` / `despawnAll` / `save`, and the five cleanup layers.
  This is the part that took the longest to get right and has nothing to do
  with what the panel shows.
- **The per-viewer overlay technique**: `setVisibleByDefault(false)` +
  `showEntity` for the owner, `hideEntity` on the shared equivalent.
- **The interpolation approach** in `animateRow`.
- **The listener** — six handlers, all generic once the key check is swapped.

### Specific to the difficulty menu

- `numberPose` / `renderNumberRow` — the 1–9 carousel. The *recipe* (scales and
  opacities by distance from centre, clamp at the ends) generalises; the
  hardcoded 1–9 does not.
- `handleClick`'s role switch and the `shift` / `enter` / `flashButton`
  behaviour.
- `startFromPanel` as the action.
- Everything under the `difficulty-panel` config path.

### What to change

1. **New `NamespacedKey`s.** Non-negotiable — a shared key means each panel's
   orphan sweep deletes the other's entities. Add them in `DungeonForgePlugin`
   next to `panelIdKey` / `panelRoleKey`.
2. **New storage file** alongside `panels.yml`, same five-values-per-panel
   shape.
3. **New config section**, same key layout under a new root.
4. **Rewrite `render`** — the element table is the panel. Everything else
   stays.
5. **New role tags and a new switch** in `handleClick`.
6. **Register the manager**: construct it, `load()` it, register its listener,
   schedule its `tick` at 10 ticks, and call `despawnAll()` in `onDisable`.
   Six lines in `DungeonForgePlugin`, all next to the existing panel lines.
7. **Add the command branch** in `DungeonCommand` — `handlePanel` is a
   copyable template, and `readLocation` is already shared.

If this is the third panel, extract §12's "reusable as-is" list into a shared
base class first. Two implementations of the placement math and the orphan
sweep is already one too many; three is a maintenance problem.
