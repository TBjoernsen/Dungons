# DungeonForge visual style reference

DungeonForge is a Minecraft (Paper) plugin that builds in-world interfaces out
of **display entities** — floating text and coloured blocks placed in the world
itself. There is no resource pack and no client mod: everything below is what a
vanilla client renders on its own.

Two interfaces exist today and share one style: a **difficulty panel** (heading,
a carousel of numbers 1–9, two arrows, an "Enter Dungeon" button) and a **skill
tree panel** (nodes as circles, connecting lines, a detail panel). New work
should match what follows.

---

## 1. The font

**Minecraft's default font. It is never set explicitly** — no `font:` tag
appears anywhere in the code or config, so text displays fall back to the
vanilla font the chat window uses. Do not introduce a custom font: it would
require a resource pack, which this project deliberately avoids.

Text shadows are switched off everywhere (`setShadowed(false)`). Keep them off;
the palette relies on flat colour against dark backgrounds.

### Metrics that affect layout

These were derived to size backgrounds and centre labels. They are properties
of the vanilla font, so they hold for any new interface.

| Constant | Value | Meaning |
|---|---|---|
| Pixels per block | **40** | One font pixel = 1/40 block at transformation scale 1.0 |
| Line height | **11 px** | One line of text *plus* the 1 px background padding on each side (9 px line + 2) |
| Glyph vertical anchor | **4.5 px** | A text display draws its line *upwards* from its own position, so text sits 4.5 px above centre |
| Trailing gap | **1 px** | Every glyph is followed by 1 px of spacing, including the last one |

**Two corrections follow from that, and both are needed for anything centred:**

1. **Vertical.** A text display is *not* vertically centred on its entity
   position. To centre a label inside a box, move the label **down by 4.5 font
   pixels** (`4.5 * scale / 40` blocks). Without this the text floats high in
   its plate.
2. **Horizontal.** A line is centred *including* the trailing gap after its last
   glyph, so the visible text sits half a pixel left of centre. Shift it
   **right by 0.5 font pixels** to correct it.

### Character widths

Advance width in pixels per character. **Bold adds +1 per character**, and
every character is followed by +1 px of spacing. So a string's total width is
`sum(glyphWidth(c) + (bold ? 1 : 0) + 1)`.

| Width | Characters |
|---|---|
| 1 | `i` `!` `,` `.` `:` `;` `|` `'` |
| 2 | `l` `` ` `` |
| 3 | space `t` `I` `[` `]` `{` `}` `(` `)` `"` `*` |
| 4 | `f` `k` `<` `>` |
| 6 | `@` `~` |
| 5 | everything else |

Example: `Enter Dungeon` in bold = 87 px wide.

Use this whenever a background must track a label's width. Sizing a plate as a
*ratio* of the text does not work: a ratio adds a share of the width sideways
and a share of the line height vertically, which for a wide one-line label is
far more sideways than up, producing a border thicker on the left and right
than on the top and bottom. Compute in pixels instead.

---

## 2. Colours

Everything derives from one gold gradient, taken from the plugin's chat prefix.

### The gradient

| Name | Hex | Role |
|---|---|---|
| **Gold dark** | `#c9a227` | Gradient start. The workhorse: arrows, labels, mid-brightness states |
| **Gold light** | `#f2e39b` | Gradient end. Highlights: selected item, unlocked state |

Written as `<gradient:#c9a227:#f2e39b>` in MiniMessage. Headings and button
labels use the full gradient; single-colour elements pick whichever end matches
their emphasis — **light for "this one is active", dark for everything else
that is still fully lit**.

### Every colour in use

| Element | Hex / value | Notes |
|---|---|---|
| Heading text (both panels) | `#c9a227` → `#f2e39b` | Full gradient, bold |
| Carousel selected number | `#f2e39b` | Gold light |
| Carousel neighbours | `#f2e39b` | Same colour — dimmed by **opacity**, not by hue |
| Arrows `<` `>` | `#c9a227` | Gold dark |
| Button label | `#c9a227` → `#f2e39b` | Full gradient, bold |
| Button fill | `BLACK_CONCRETE` | A block, not a hex colour — see below |
| Button border | `YELLOW_CONCRETE` | Block |
| Button pressed flash | `BROWN_CONCRETE` | Block, shown ~3 ticks |
| Skill node — unlocked | `#f2e39b`, opacity 255 | Gold light, full |
| Skill node — available | `#c9a227`, opacity 230 | Gold dark |
| Skill node — locked | `#6b6353`, opacity 140 | Desaturated gold-grey |
| Skill line — unlocked | `GOLD_BLOCK`, brightness 15 | Block |
| Skill line — available | `SMOOTH_QUARTZ`, brightness 11 | Block |
| Skill line — locked | `GRAY_CONCRETE`, brightness 5 | Block |
| Detail panel body text | `#b3a577` | Muted gold for descriptions |
| Detail panel background | `A0140F05` | ARGB, alpha first: ~63% opaque near-black brown |

**Why some elements are blocks and not hex values.** A text display's
background is a flat rectangle whose colour you set directly; a block display
shows a block texture. Blocks are used where a *shape* is needed (button plate
with stepped corners, connection lines at arbitrary angles), because a text
background can only ever be an axis-aligned rectangle. Concrete blocks are the
closest thing to a flat colour. Their apparent brightness is controlled by the
display's emissive `brightness` (0–15), which is why the three line states are
distinguished by both block and brightness.

---

## 3. Consistency rules

Follow these; they are what makes new elements look like existing ones.

**Dimming is opacity, never a different colour.** The difficulty carousel keeps
every number the same gold and varies text opacity by distance from centre:
**255 → 165 → 85** (selected, ±1, ±2), with sizes **2.8 → 1.6 → 1.0**. Numbers
beyond the visible range shrink to scale 0.05 rather than being deleted, so
they can grow back in with the same interpolated slide. Opacity is clamped to a
floor of **26** — a text display at 0 opacity behaves unreliably.

**Three states, three levels, in a fixed order.** Anything with unlocked /
available / locked semantics uses the same ladder: unlocked is brightest
(gold light, full opacity, brightness 15), available is mid (gold dark, opacity
230, brightness 11), locked is clearly dimmest (grey-gold `#6b6353`, opacity
140, brightness 5). The gap between locked and available should always read at
a glance from several blocks away; that is the whole point of the ladder.

**Shared furniture is drawn locked; brightness is a per-viewer overlay.** The
skill tree's shared entities render the entire tree in the locked style. What a
particular player has unlocked is drawn *on top* as extra entities that are
invisible by default and shown only to that player. Two players at the same
tree see different brightness patterns on one structure. Overlay lines are
drawn 20% thicker than the base line they cover so their faces sit outside it
instead of z-fighting.

**Layering.** Elements stack toward the viewer in a fixed order, spaced far
enough apart to avoid depth fighting: base lines → overlay lines → node glyphs
→ overlay node glyphs → selection ring / labels. A solid block display writes
to the depth buffer, so any text meant to be read must sit **in front of** the
block's front face, not level with it — text level with a block is hidden close
up and only breaks through at a distance, where depth precision degrades.

**Emphasis is size, not colour.** The selected carousel number is 2.8× scale
against 1.6× for its neighbours; the heading is 1.5×. Reach for scale before
reaching for a new colour.

**Sounds are private.** Click feedback plays only to the player who clicked
(`player.playSound`), never to the area.

---

## 4. Copy-paste examples

Everything user-facing is [MiniMessage](https://docs.advntr.dev/minimessage/).

**Heading** (difficulty panel and skill tree, at scale 1.5):

```
<gradient:#c9a227:#f2e39b><bold>Select Difficulty
```

**Button label** (scale 1.4, on a dark plate):

```
<gradient:#c9a227:#f2e39b><bold>Enter Dungeon
```

**Chat prefix** — the source of the whole scheme:

```
<dark_gray>[<gradient:#c9a227:#f2e39b>DungeonForge</gradient><dark_gray>] <gray>
```

**Carousel number** (`<n>` is substituted):

```
<color:#f2e39b><bold><n>
```

**Arrows:**

```
<color:#c9a227><bold><
<color:#c9a227><bold>>
```

**Skill node glyphs** — circles from the default font, `●` filled and `○` for
the selection ring:

```
<color:#f2e39b>●     unlocked
<color:#c9a227>●     available
<color:#6b6353>●     locked
<color:#f2e39b>○     selection ring
```

**Detail panel** (multi-line, `<name>` `<cost>` `<description>` `<level>` are
substituted):

```
<gradient:#c9a227:#f2e39b><bold><name></bold></gradient><newline><color:#c9a227>Cost: <cost> point(s)<newline><color:#b3a577><description><newline><color:#c9a227>Level <level>/1
```

In Java these are deserialized with
`MiniMessage.miniMessage().deserialize(raw, placeholders...)` into an Adventure
`Component`, then set on the display with `TextDisplay#text(Component)`.

---

## 5. Config keys

All under `plugins/DungeonForge/config.yml`. `/dungeon reload` re-renders every
panel with new values — no restart.

### Difficulty panel — `difficulty-panel`

| Key | Controls |
|---|---|
| `heading.text` / `.scale` / `.height` | Heading string, size, height above the base |
| `numbers.format` | Carousel number colour, `<n>` placeholder |
| `numbers.scales` | `[2.8, 1.6, 1.0]` — selected, ±1, ±2 |
| `numbers.opacities` | `[255, 165, 85]` — the fade |
| `numbers.spacing` / `.centre-extra` | Horizontal gaps; `centre-extra` is extra air around the selected number |
| `arrows.left` / `.right` / `.scale` / `.offset` | Arrow glyphs, size, distance from centre |
| `button.text` / `.scale` | Button label and size |
| `button.plate.fill-block` / `.border-block` / `.flash-block` | The three plate blocks |
| `button.plate.padding-pixels` / `.border-pixels` | Padding and border thickness, in font pixels, even on all four sides |
| `button.plate.corner-step-pixels` | How far each corner steps in; `0` gives square corners |
| `button.plate.label-y-pixels` / `.label-x-pixels` | The 4.5 px and 0.5 px centring corrections |
| `brightness` | Emissive level 0–15 for the whole panel |

### Skill tree panel — `skill-panel`

| Key | Controls |
|---|---|
| `heading.scale` / `.height-extra` | Class name above the tree |
| `nodes.scale` | Node circle size |
| `nodes.unlocked-format` / `available-format` / `locked-format` | The three node colours |
| `nodes.unlocked-opacity` / `available-opacity` / `locked-opacity` | `255 / 230 / 140` |
| `nodes.ring-format` / `.ring-scale` | Selection ring |
| `lines.unlocked` / `available` / `locked` | Each takes `{ block: …, brightness: 0-15 }` |
| `lines.thickness` / `.depth` | Bar dimensions in blocks |
| `detail.format` / `.background` / `.scale` / `.x` / `.height` / `.line-width` | The detail panel beside the tree |
| `radius-step` / `root-height` | Tree scale and height off the ground |
| `branch-labels.scale` | Branch name size |

Tree contents — node names, costs, descriptions, positions — live in a separate
`skills.yml`, not in the style config.

---

## 6. Hardcoded values that diverge from config

Anything below is **fixed in code and cannot be changed by editing config**.
Match these values rather than assuming a config key exists.

- **Skill panel glyph centring.** The 4.5 px vertical correction and the 40
  pixels-per-block constant are hardcoded in the skill panel
  (`GLYPH_CENTRE_PIXELS = 4.5`, `/ 40.0`). The difficulty panel exposes the
  same two numbers as `button.plate.label-y-pixels` and `.pixels-per-block`.
  The values agree; only the difficulty panel's are adjustable.
- **Skill panel depth offsets.** Base node glyphs sit at 0.05 blocks from the
  panel plane, per-player node overlays at 0.065, the selection ring at 0.058,
  overlay lines at 0.032, hitboxes at 0.06. Only the *base* line depth is
  configurable (`lines.z`, default 0.015).
- **Overlay line thickness.** Per-player lines are drawn at 1.2× the
  configured `lines.thickness`. The multiplier is not configurable.
- **Opacity floor.** Text opacity is clamped to a minimum of 26 in both panels.
- **Carousel off-screen scale.** Numbers outside the visible range are drawn at
  scale 0.05, hardcoded.
- **Detail panel default in code** matches the config default exactly; if the
  config key is missing the same string is used.
