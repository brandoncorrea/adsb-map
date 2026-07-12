# Design Direction — The Sectional, Day & Night

**Status: settled.** Decided by the Overseer via `docs/design-questionnaire.md`
(answered 2026-07-12, commit `a7dc144`; bead `adsb-bvi.5`). Three candidate
mockups preceded this — *Nocturne*, *The Sectional*, *Terminal* (session
artifacts `nocturne.html` / `sectional.html` / `terminal.html`; palettes
archived in the notes on `adsb-bvi.5`). The verdict is none of them as drawn:
**The Sectional's body with Nocturne's pulse**, plus two commissioned
inventions. This document is self-sufficient; the mockups are flavor only.

Two decisions inside it are *scheduled, not made*: typography (§5) and the
final proof of the two inventions (§8, §9), each of which needs a prototype
before it is load-bearing.

---

## 1. Concept

The app is **a living aeronautical chart** — a beautiful printed artifact that
happens to be alive. Warm paper, terrain tints, contour texture, ink-drawn
aircraft. Unapologetically themed: the chart is the bit, and we commit to it.

But amended, in three ways that are the whole job:

1. **It survives the dark.** The app follows the system theme, so the chart
   exists in two *printed editions* — a day chart on warm paper and a genuine
   **night edition**, its own artifact in the tradition of dark-printed charts
   for red-lit cockpits. Not a CSS invert. Not a filter. Two prints of the
   same plate.
2. **It breathes.** The original Sectional held perfectly still; this one has
   Nocturne's pulse. Aircraft glide (non-negotiable, always), every aircraft
   trails a fading ribbon, panels ease, selection marks draw themselves in.
   The paper is calm; the sky on it is alive.
3. **It is quiet on the surface and deep on the axis.** No always-on
   annotation blocks — glyphs only, callsign on hover/tap. There is **no
   sidebar**; the list's job moves to **the Stack** (§9), a live altitude
   ruler on the map's edge.

It is an **up-close, lean-in app** (Q15c). Across-the-room glanceability is
explicitly not a goal; optimize for arm's length and for pleasure on close
inspection.

## 2. The two editions — palette

The hue *relationships* are the identity; each edition renders them on its own
paper. All values are the settled starting point for the visual pass — tune in
place, keep the relationships.

| Role | Day edition | Night edition |
|---|---|---|
| Paper (map base) | `#F5EFDF` | `#151B26` |
| Paper, chrome/panels | `#FBF6E8` | `#1B2330` |
| Terrain tint 1 / 2 | `#E7DBB8` / `#E0CFA0` | `#1D2634` / `#232E40` |
| Contour lines | `#D9C99F` | `#2E3A49` |
| Water fill / line | `#C9DCD6` / `#3D5E8C` | `#101823` / `#7FA3D4` |
| Ink (text, rules, glyph outlines) | `#2C2A24` | `#E9E2CE` |
| Faded ink (captions, ticks) | `#8B8471` | `#8D96A8` |
| Aviation magenta (accents, selection) | `#C0447C` | `#E06A9F` |
| Aero blue (links, water labels) | `#3D5E8C` | `#7FA3D4` |
| Roads | `#A65A2E` at ~0.6 alpha | `#6B5540` |
| Emergency red | `#CE2029` | `#FF5A4D` |

**Aircraft altitude ramp** — *continuous* (Q6a), interpolated through
chart-native inks, warm (low) → cool (high):

| Feet | Day | Night |
|---|---|---|
| 0 | `#A0622D` sienna | `#C98A54` |
| 10,000 | `#C2447C` aviation magenta | `#E06A9F` |
| 20,000 | `#7A4F86` plum | `#A98BC4` |
| 30,000 | `#3D5E8C` aero blue | `#7FA3D4` |
| 40,000 | `#2A3F66` deep ink blue | `#5F7FB8` |

Supporting aircraft states (same semantics as today's
`src/cljs/adsb/map/style.cljs`, recolored): **ground** `#8A8374` / `#6E7686`;
**altitude unknown** `#9A937F` / `#7C8494`; **stale** keeps the existing
continuous opacity fade; **mlat** keeps the existing size demotion. Trails are
a quiet ink echo, alpha-graded tail→head as today: day rgb `44,42,36`, night
rgb `233,226,206`, head opacity ≤ 0.5 on paper.

## 3. Basemap treatment

- **Provider is settled and out of scope:** OpenFreeMap, `liberty` style
  (bead `adsb-kh4.5`). No token, attribution flows automatically.
- The direction **customizes the style JSON, not the provider**: derive
  day-chart and night-chart variants of Liberty by recoloring its layers to
  the §2 palette — water, landcover/landuse, roads, boundaries, labels
  (serif where the label is a *place*, per the eventual §5 bake-off).
- **Richly rendered** (Q4c): coastline/water, major roads, place labels, and
  terrain (Q5 a–d) all speak. Terrain feel comes from tinting Liberty's
  landcover/hillshade layers to the paper-and-contour palette; true contour
  lines are not in the tiles — the *texture* of contours may be suggested in
  the paper itself, never fabricated as data.
- Theme switches with `prefers-color-scheme`; both editions ship at once.

## 4. Aircraft styling

All constants live in `src/cljs/adsb/map/style.cljs` — the re-skin edits data
there, not logic.

- **Glyph:** an ink plan-view aircraft silhouette (SDF, tintable), rotated to
  track; the existing non-directional dot stays for unknown-track targets.
  Hairline paper-colored halo so ink survives busy chart areas.
- **Color = altitude**, continuous ramp per §2. Emergency overrides all.
- **Surface density is minimal** (Q7a): glyphs only. Callsign appears on
  hover (desktop) / tap (phone); full detail on selection, in an index-card
  panel drawn like chart marginalia (paper panel, ink rule, mono data).
- **Selection mark:** a dashed compass-pencil ring that draws itself in.
- **The cast shadow (§8)** carries altitude-as-instinct once proven.

## 5. Typography — scheduled, not settled

Q14 was deliberately deferred. Typefaces, modular scale, spacing scale, and
final palette tuning get a **bake-off against the real layout** (real Stack,
real map) as its own bead. Until then, build with the mockup's system stacks —
serif display (Iowan Old Style/Georgia), humanist sans labels, monospace
data — as *placeholder*, and hold the line: editorial voice, mono for numbers.

## 6. Motion principles

- Aircraft **glide** — interpolated between updates, sixty frames a second.
- **Trails always on, for everyone** (Q11a): fading ink ribbons.
- Chrome **eases**: panels slide/settle in 150–200ms ease-out; the selection
  ring draws in; the Stack's ticks drift smoothly to new altitudes.
- Glows may **pulse softly** where meaning warrants (fresh contact, hover) —
  breath, not blink.
- The emergency treatment does **not** flash (§7).
- Frame rate is a design constraint: no allocation in render loops, aircraft
  never pass through React, a dropped frame is a P0.

## 7. Emergency: grave, not shrill

A 7500/7600/7700 aircraft is a human being having the worst day of their
life. The chart responds the way a chartroom would — **marked and annotated**
(Q12c), unmissable without ever strobing:

- The aircraft is **circled in red pen** — a hand-drawn double ellipse that
  draws itself once and stays; ink does not blink.
- A rotated **MAYDAY + squawk stamp** sits beside it with callsign, altitude,
  and vertical rate.
- A **red NOTAM strip** appears beneath the header, full width, plain
  language.
- Its tick on the Stack renders red with hatching, pinned prominent.
- **Off-screen** (Q13c): NOTAM strip plus a red **edge arrow** at the map
  boundary pointing toward the aircraft. The camera is never hijacked.
- The glyph draws larger (existing `emergency-icon-size`) and emergency
  color overrides every other channel, as `style.cljs` already guarantees.

## 8. Invention I — the cast shadow *(prototype before load-bearing)*

Color is a precise altitude channel but a weak instant one. So every aircraft
**throws a shadow onto the paper**: offset and softness scale with altitude —
a plane on the deck has its shadow tucked beneath it; one at FL380 floats far
off the page. Vertical rate rides free: a closing shadow reads as descent
before any number does.

Candidate implementation: a second symbol layer under the aircraft layer —
same silhouette as a pre-softened sprite, ink-colored at low alpha (day
`rgba(44,42,36,·)`, night deep black-blue), data-driven `icon-offset` by
altitude, alpha falling as altitude rises. Must hold 60fps at hundreds of
aircraft and stay legible at density; **prove it in a prototype bead, and if
it fails, it dies without dragging the rest of the direction down.**

## 9. Invention II — the Stack *(prototype before load-bearing)*

There is no sidebar. The aircraft list, the altitude legend, and the altitude
scale are **one object**: a live flight-level ruler on the map's edge —
a profile view of the sky beside the plan view of it.

- The ruler's fill *is* the §2 altitude gradient — it is its own legend.
- Every aircraft is a **tick at its true altitude**, drifting as it climbs or
  descends. Approach stacking and departures read as motion, not re-sorting.
- Hover a tick → its aircraft lights on the map; click → select. And the
  reverse: hover a plane, its tick lights.
- Ground/unknown targets collect in a shelf at the ruler's foot.
- **Desktop:** vertical, right edge. **Phone:** the ruler lies down along the
  bottom edge, same semantics (Q9c — genuinely equal, neither stage may
  degrade).
- Emergency tick: red, hatched, prominent (§7).
- An ATC flight-progress-strip rack was considered and rejected as the list,
  but may return as the *selection detail* surface.

## 10. Stances & standing constraints

- **Phone and desktop are equals** (Q9c). Every surface has a designed stance
  in both; the Stack rotates rather than degrades.
- **The map is the product; chrome defers to it.** Header is a thin chart
  title block; panels are marginalia that never crowd the plot.
- **The receiver location is never shown** — no antenna marker, no range
  rings, nothing centered on home. (Also why contour *rings* around anything
  are banned outright.)
- **Emergencies must be unmissable** — §7 is the floor, not the ceiling.
- Lean-in, up-close reading (Q15c); elegance outranks distance legibility.

## 11. What this means for the visual pass (`adsb-dgb.5`)

In order, because risk fronts the queue:

1. **Night edition first.** The dark chart is the direction's biggest risk
   (Q2c). Recolor Liberty into the night palette (§2/§3) and *look at it*
   before anything else is styled. If night paper can't be beautiful, the
   palette gets renegotiated — early, not late.
2. **Day edition** recolor of Liberty; wire `prefers-color-scheme` switching.
3. **Re-skin `src/cljs/adsb/map/style.cljs`:** altitude stops, ground /
   unknown / emergency colors, halo to paper, trail rgb + head opacity per
   edition. Semantics (stale fade, mlat demotion, emergency override) do not
   change.
4. **Ink silhouette** SDF icon to replace the functional glyph.
5. **Chrome:** chart title-block header, index-card selection panel, NOTAM
   strip, edge-arrow, selection ring, easing per §6.
6. **File separate prototype beads** for the cast shadow (§8) and the Stack
   (§9) — the visual pass must not block on the inventions, and the sidebar
   stays absent (not replaced by a stopgap table) while the Stack is proven.
7. **File the typography bake-off bead** (§5) once the layout stands.
