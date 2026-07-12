# Design Direction — The Sectional, Day & Night

**Status: settled.** Decided by the Overseer via `docs/design-questionnaire.md`
(answered 2026-07-12, commit `a7dc144`; bead `adsb-bvi.5`). Three candidate
mockups preceded this — *Nocturne*, *The Sectional*, *Terminal* (session
artifacts `nocturne.html` / `sectional.html` / `terminal.html`; palettes
archived in the notes on `adsb-bvi.5`). The verdict is none of them as drawn:
**The Sectional's body with Nocturne's pulse**, plus two commissioned
inventions. This document is self-sufficient; the mockups are flavor only.

Two decisions inside it were *scheduled, not made*: typography (§5 — now
SETTLED, picked in-app 2026-07-12, bead `adsb-dgb.10`) and the final proof
of the two inventions (§8, §9), each of which needed a prototype before it
was load-bearing.

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

**The night edition is PROVED** (bead `adsb-dgb.7`): both editions were
rendered in the running app — Liberty re-inked to this table over live replay
traffic, theme-switched by `prefers-color-scheme` — and the night print reads
as a printed night chart, not an invert. One value moved under proof, in the
table below: **night roads print at ~0.6 alpha** (`#6B5540` full-strength
glows like filament against the night paper at regional-zoom road density;
the alpha the day edition always used quiets the network to embers). Every
other night value stood as designed. Where the palette lives in code:
aircraft inks in `adsb.map.style/palettes`, basemap inks in
`adsb.map.basemap/editions`, chrome inks in `app.css` custom properties —
one table, three media, change them together.

| Role | Day edition | Night edition |
|---|---|---|
| Paper (map base) | `#F5EFDF` | `#151B26` |
| Paper, chrome/panels | `#FBF6E8` | `#1B2330` |
| Terrain tint 1 / 2 | `#E7DBB8` / `#E0CFA0` | `#1D2634` / `#232E40` |
| Contour lines | `#D9C99F` | `#2E3A49` |
| Water fill / line | `#C9DCD6` / `#3D5E8C` | `#101823` / `#7FA3D4` |
| Ink (text, rules, glyph outlines) | `#2C2A24` | `#E9E2CE` |
| Faded ink (captions, ticks) | `#6E6A58` | `#8D96A8` |
| Aviation magenta (accents, selection) | `#A83A63` | `#E77E9B` |
| Aero blue (links, water labels) | `#36547E` | `#8BA9D6` |
| Roads | `#A65A2E` at ~0.6 alpha | `#6B5540` at ~0.6 alpha |
| Emergency red | `#CE2029` | `#FF5A4D` |

*The magenta and aero rows were re-inked 2026-07-12 by the §5 pick — the
"wine pen" accent variation (bead `adsb-dgb.10`, chosen live in
`#/preview`). The relationships stand; only the pen pressed deeper. The
night-edition proof above predates the re-ink and is unaffected: the pen
rows are chrome accents, not the plate.*

*One value tuned in place by the visual pass (`adsb-dgb.5`), under this
section's own rule: day faded ink deepened `#8B8471` → `#6E6A58` — the §5
mono captions print at 10.5px and the old ink sat at 3.4:1 against the
chrome paper; the new one reads 5.0:1 and keeps the dusty-khaki
relationship. Applied across all three media. (The chip greens/ambers in
app.css — `--ok`/`--warn`, not table rows — were contrast-tuned the same
day.)*

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
  (**the whole chart writes in the plotter's mono hand** — the §5 open
  call, decided by eye in `adsb-dgb.5`; verdict below).
- **Richly rendered** (Q4c): coastline/water, major roads, place labels, and
  terrain (Q5 a–d) all speak. Terrain feel comes from tinting Liberty's
  landcover/hillshade layers to the paper-and-contour palette; true contour
  lines are not in the tiles — the *texture* of contours may be suggested in
  the paper itself, never fabricated as data.
- Theme switches with `prefers-color-scheme`; both editions ship at once.
- **Mechanism (built, adsb-dgb.7):** the raw Liberty JSON is fetched once and
  re-inked at runtime by `adsb.map.basemap/edition-style` — a pure,
  palette-driven transform keyed on `source-layer` + layer type (not
  Liberty's layer ids), so upstream drift degrades to Liberty's own paint
  instead of breaking the plate. `adsb.map.theme` owns the media query; a
  flip re-prints map + aircraft layer together. Sprite decor that cannot be
  re-inked (highway shields, pattern fills) is hidden in the night edition;
  the low-zoom natural-earth raster is hidden in both — a photograph is not
  a print. OpenFreeMap's hosted darks (`dark`, `fiord`) were examined and
  rejected: generic dashboard darks, not our artifact. *Sustainable path:*
  keep the runtime transform while Liberty is stable; if upstream churns,
  vendor a build-time-generated pair of style JSONs produced from the same
  palette maps — the palettes stay the single source of truth either way.
- **Label voice — DECIDED by eye (`adsb-dgb.5`, 2026-07-12):** the whole
  chart adopts the plotter's mono hand. Both candidates were rendered
  live over the re-inked plate at replay density, both editions — serif
  places (Source Serif 4 against the mono chrome) and all-mono — and the
  serif whisper lost: it is a beautiful atlas voice, but it reads as a
  second author lettering someone else's plate, and it is the voice that
  already lost the §5 bake-off. One hand everywhere is The Annotation's
  own thesis, and it makes the chart unmistakably ours. Liberty's
  weight/italic hierarchy survives verbatim (Bold stays the capitals'
  stamp, Italic stays water's whisper). **Mechanism:** OpenFreeMap's
  glyph server hosts only Noto Sans, so the chart carries its own glyphs
  — SDF PBF ranges generated from the same OFL Space Mono files the
  chrome ships (`resources/public/glyphs/`, provenance in its README);
  `edition-style` re-points the style's `glyphs` endpoint and re-letters
  every symbol layer, shields included (a stack the endpoint does not
  host 404s, and a tile whose symbol bucket cannot resolve glyphs never
  finishes — fills and all).

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
- **Perspective size (§8)** carries altitude-as-instinct — the shadow it
  replaced is recorded there.

## 5. Typography — SETTLED

Q14 was deliberately deferred until it could be judged against the real
layout. The bake-off staged three complete systems on the running app
(bead `adsb-dgb.10`), the in-app preview let the Overseer browse and MIX
dimensions independently (`#/preview`, bead `adsb-dgb.11`), and he picked
from live mixes on 2026-07-12. **The winning mix**
(`typography=annotation labels=printed-grotesk scale=major-13
spacing=compact-4 palette=wine-pen`), the editorial-serif hypothesis
losing to its most radical rival:

- **Type system — THE ANNOTATION.** Everything written on the chart is
  written by one hand, the plotter's: **Space Mono** (OFL; self-hosted
  woff2s in `resources/public/fonts/`, licensing in `fonts/LICENSE.md`)
  in regular, bold, and italic. Hierarchy is weight and case, never a
  change of voice — bold stamps for titles (the header title carries a
  2px magenta pen underline), caps-tracked bold for block headings (the
  legend's print in the pen). Mono numbers are inherently tabular.
- **Caption voice — PRINTED, in Space Grotesk.** Refined by the Overseer
  after the crowning (bead `adsb-fon`, picked 2026-07-12): the system's
  original *italic Title Case* small labels strain at panel sizes, so
  the smallest labels — fact labels, stats labels, the header's count
  units, shelf labels — take the plotter's PRINTING hand instead:
  **Space Grotesk** (OFL; Space Mono's own proportional derivative, so
  still one hand at two widths) Medium, upright, uppercase, tracked
  0.12em, at `--t-2`. Grotesk stays in exactly this lane — small caps
  labels only, never titles, body, or data — where the siblings
  harmonize instead of competing. Space Mono's italic remains in the
  family for larger captions should one ever need a voice.
- **Modular scale — major third, 1.25 @ 13px.** Tokens 8.5 / 10.5 / 13 /
  16 / 20px (`--t-2 … --t2`). Deliberately shallow, true to the system's
  own thesis — and it spends its size on the DATA rather than the title
  stamp: mono data prints at 10.5px. The printed caption voice sits at
  8.5px and holds, which is the dgb.11 lesson made law: `--t-2` captions
  are legible only caps-tracked and upright — exactly what the printed
  voice is, and what the retired italic was not.
- **Spacing — compact, 4px base.** Tokens 2 / 4 / 8 / 12 / 16px
  (`--s1 … --s5`); header 36px. The chrome defers to the map (§10): a
  working chart's margin notes are dense, the mono's own letter-air
  keeps the tight rhythm readable, and the sky is the whitespace.
- **Palette — the wine pen.** The §2 hue relationships stand; only the
  PEN — the chrome's annotation accents — presses deeper. Aviation
  magenta prints `#A83A63` day / `#E77E9B` night; chrome aero blue
  steadies to `#36547E` day / `#8BA9D6` night to keep its distance from
  it (§2 table updated in place, per §2's own tune-in-place rule).
  Paper, ink, the aircraft altitude ramp, and emergency red move not at
  all.

**Mechanism:** the winner ships exactly as previewed — the mix's CSS
custom-property set promoted into `app.css` `:root` in both editions,
`font-src 'self'` added to the CSP (`adsb-kh4.7`), applied across the
chrome in the visual pass (`adsb-dgb.5`). The `#/preview` fitting room
(bead `adsb-dgb.11`) served every pick above and was then cleared out
at the Overseer's direction (bead `adsb-gdz`) — the app carries no
preview route. If a future re-skin wants the mechanism back, resurrect
it from git history rather than reinventing it.

**The one consequence left open — now closed:** whether basemap places
kept a serif voice against the mono chrome, or the whole chart adopted
the plotter's hand, was decided by eye in the visual pass — **the whole
chart writes in the plotter's hand.** Verdict, reasoning, and mechanism
in §3's label-voice bullet.

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

## 8. Invention I — the instinct-altitude channel *(verdict rendered)*

Color is a precise altitude channel but a weak instant one. The original
commission here was a **cast shadow** — offset and softness scaling with
altitude. It was prototyped honestly (adsb-dgb.8) and **rejected by the
Overseer**: too busy at real traffic density, and near-invisible on the
night stock, where there is little room left to darken. A drop-line tether
was auditioned in the same exploration (adsb-dgb.12) — exquisite at night,
but its verticals slice through dense clusters, and a chart has no verticals.

The crowned channel is **perspective size** (adsb-b23): low is near is
large, high is far is small — `icon-size` ramps from 1.25 on the deck to
0.55 at FL400, front-loaded so the low sky, where the drama lives, keeps its
resolution. Geometry carries no ink, so the night edition reads identically
to day. Emergency size (1.6) overrides absolutely — a plane in distress is
never allowed to look far away — and the MLAT demotion composes with the
altitude size as a ×0.78 factor rather than colliding with it.

Altitude now sings in three-part harmony: **color for precision, size for
instinct, the Stack (§9) for the profile.**

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

1. ~~**Night edition first.**~~ **Done (adsb-dgb.7)** — proved in the
   running app before anything else was styled.
2. ~~**Day edition**~~ **Done (adsb-dgb.7)** — both editions ship,
   switched by `prefers-color-scheme`.
3. ~~**Re-skin `src/cljs/adsb/map/style.cljs`**~~ **Done (adsb-dgb.7)** —
   semantics unchanged, palettes per edition.
4. ~~**Ink silhouette**~~ **Done** — the SDF plan-view silhouette ships
   (drawn at load, tinted by the ramp; the non-directional dot stays for
   track-less targets).
5. **Chrome — done in the pass itself (adsb-dgb.5):** chart title-block
   header (36px, mono vitals, the pen-underlined title stamp), index-card
   selection panel, NOTAM strip (stamped tab, zero motion), compass-pencil
   selection ring (a map marker that draws itself in), legend + stats as
   the margin column, the Stack re-tokened, easing per §6, and the label
   voice decided (§3).
6. ~~**Still owed: the §7 map annotations.**~~ **Done (adsb-bb1)** — the
   red-pen double ellipse, the MAYDAY stamp, and the off-screen edge arrow
   ship in `adsb.map.emergency`, filed as their own bead rather than
   half-shipped inside the chrome pass. Reviewed on the chart by the
   Overseer, 2026-07-12. They follow the selection ring's pattern —
   MapLibre markers driven by a `track!` outside React, so the chart's
   annotations never cost a render — and they *compose with* the GeoJSON
   layer's large red glyph rather than replacing it. Two constraints the
   code holds and this document should not lose: the stamp's copy comes
   from `adsb.ui.alert/emergency-words`, the same source the NOTAM strip
   prints, **so chart and chrome can never disagree about what is
   happening**; and the edge arrow, when clicked, fires the ordinary
   `[:aircraft/select icao]` contract — it opens the index card and moves
   the camera **not one inch** (Q13c).
7. ~~File separate prototype beads~~ **Done — and both verdicts are in**:
   the instinct channel is perspective size (§8, shadow rejected); the Stack
   (§9) is built and load-bearing.
8. ~~File the typography bake-off bead~~ **Done — picked and shipped**:
   the §5 mix is live in `app.css`; the `#/preview` fitting room was
   cleared out after the picks (in git history if ever needed again).

**Nothing in this section is outstanding.** The direction as written is the
direction as built; where the two disagreed, the disagreement is recorded
above rather than quietly reconciled.
