# Design Questionnaire

Fifteen questions from AUTEUR to the Overseer. Your answers pick — or at least
corner — the design direction for the app (bead `adsb-bvi.5`).

Three candidate directions already exist as mockups (see the notes on
`adsb-bvi.5` for palettes and file paths):

- **Nocturne** — glass cockpit at night. Near-black map, glowing aircraft,
  frosted-glass chrome, continuous altitude gradient, pulsing red emergency halo.
- **The Sectional** — a living aeronautical paper chart. Warm paper, ink
  aircraft silhouettes with annotation leader lines, serif editorial chrome,
  red-pen-and-stamp emergency.
- **Terminal** — airport wayfinding signage. Black and signage yellow, heavy
  grotesque type, departures-board sidebar, discrete altitude bands, flashing
  red emergency band.

**How to answer:** mark one checkbox per question (checkboxes for Q5, which is
multi-select). A bare letter per question number works too — `1b, 2a, ...`.
Where an option echoes a candidate, it's tagged **(N)**, **(S)**, or **(T)**.
No essays required, none accepted.

---

## A. Mood & Light

**1. You open the app at a random moment. What should the first half-second feel like?**

- [ ] a. A cockpit instrument at night — dark, calm, the data glows **(N)**
- [x] b. A beautiful printed artifact that happens to be alive **(S)**
- [ ] c. Public signage — loud, instant, zero ambiguity **(T)**
- [ ] d. No strong pull — decide it for me from the rest of my answers

**2. Dark or light?**

- [ ] a. Dark, always **(N, T)**
- [ ] b. Light, always **(S)**
- [x] c. Follow the system theme — design must carry both
- [ ] d. Dark first; a light variant can come later

**3. How much personality may the app have?**

- [ ] a. Subdued and professional — a tool, not a statement
- [ ] b. Distinctive but restrained — opinionated details, quiet overall
- [x] c. Unapologetically themed — commit to the bit **(S, T lean here)**

## B. The Map's Voice

**4. How much should the basemap itself speak?**

- [ ] a. Near-silent — hairline coast and roads on black, planes are everything **(T)**
- [ ] b. Muted canvas — faint geography for orientation, never competing **(N)**
- [x] c. Richly rendered — terrain, water, and labels are part of the pleasure **(S)**

**5. Which geographic context do you actually want visible? *(check all that apply)***

- [x] a. Coastline / water
- [x] b. Major roads
- [x] c. Place-name labels
- [x] d. Terrain / contours
- [ ] e. None — just the aircraft

**6. How should altitude be encoded in the aircraft color?**

- [x] a. Continuous gradient ramp — smooth warm-to-cool **(N)**
- [ ] b. Discrete named bands — GND / <10k / 10–25k / >25k chips **(T)**
- [ ] c. A small set of ink colors, chart-legend style **(S)**

> **Amended — the question was too small.** Color is a *precise* channel and a
> weak *instant* one. The Overseer asked what else could carry height, and the
> answer is now two channels, not one:
>
> 1. **Continuous gradient ramp** for precision (as above).
> 2. **A cast shadow on the paper** for instinct. Every aircraft throws a shadow
>    onto the chart, its offset and blur scaling with altitude. A plane on the
>    deck has its shadow tucked underneath it; one at FL380 floats far off the
>    page. Vertical rate rides the same channel for free — a closing shadow
>    reads as a descent before any number does.
>
> Rejected alternates: contour halo rings (chart-native, too noisy at density),
> leader-line length (fights Q7a — no always-on annotations).

## C. Density & Chrome

**7. Default information density on the map itself?**

- [x] a. Minimal — glyphs only; callsign appears on hover/tap
- [ ] b. Moderate — callsign labels always, details on selection **(N, T)**
- [ ] c. Dense — every plane carries an annotation data block **(S)**

**8. The aircraft list sidebar, by default:**

- [ ] a. Always visible on desktop — the map shares the stage
- [ ] b. Collapsed by default, one keystroke/tap away — the map stands alone
- [ ] c. Bottom sheet everywhere, phone-style, even on desktop
- [x] **d. None of these — there is no sidebar. See below.**

> **Rejected and replaced.** The Overseer's objection: a sidebar table is the
> un-creative answer, and none of a/b/c commit to the bit. The replacement is
> **"the Stack"** — a live altitude ruler running down one edge of the map.
>
> - The ruler is a flight-level axis. Every aircraft is a tick mark sitting at
>   its *true* altitude, drifting up and down the ruler as it climbs or
>   descends. It is a profile view of the sky, next to the plan view of it.
> - It is simultaneously the altitude legend (Q6's gradient is the ruler's own
>   fill), the altitude scale, and the aircraft list. One object, three jobs.
> - Hover a tick to light its aircraft on the map; click to select. Traffic
>   stacking on approach, a departure climbing out — visible as motion on the
>   ruler, not as rows re-sorting in a table.
> - On phone the ruler lies down: a horizontal strip along an edge, same
>   semantics. (Q9c — neither stage may degrade.)
>
> Rejected alternates: an ATC flight-progress strip rack (handsome, on-theme,
> but still a list; may yet return as the *selection* detail surface), and a
> "chart marginalia" panel (a table in a costume).

**9. Phone vs desktop — which is the primary stage?**

- [ ] a. Phone-first — glance from the couch is the canonical use
- [ ] b. Desktop-first — the wall/desk monitor is canonical
- [x] c. Genuinely equal — neither may degrade

## D. Motion

**10. Beyond aircraft gliding (non-negotiable), how much motion?**

- [x] a. Let it breathe — glows pulse, trails shimmer, panels ease **(N)**
- [ ] b. Restrained — planes move; the chrome holds perfectly still **(S)**
- [ ] c. Mechanical — discrete ticks and flips, like a split-flap board **(T)**

**11. Position trails behind aircraft:**

- [x] a. Always on — fading ribbons for everyone
- [ ] b. Only for the selected/hovered aircraft
- [ ] c. Off — current position and heading are enough

## E. Emergency Loudness

**12. A plane squawks 7700. How loud is the app allowed to get?**

- [ ] a. Full takeover — a flashing red band seizes the top of the screen **(T)**
- [ ] b. Prominent but contained — pulsing halo, red ribbon, pinned row **(N)**
- [x] c. Marked and annotated — circled in red pen with a MAYDAY stamp; grave, not flashing **(S)**

**13. If the emergency aircraft is off-screen when it squawks:**

- [ ] a. Banner only — I'll pan myself
- [ ] b. Banner + the map auto-pans/zooms to include it
- [x] c. Banner + an edge arrow pointing toward it; no camera hijack

## F. Typography & Glanceability

**14. Typographic leaning?**

- [ ] a. Engineering — quiet sans UI, tabular monospace for every number **(N)**
- [ ] b. Editorial — serif headers, italic captions, mono data blocks **(S)**
- [ ] c. Signage — heavy grotesque caps, oversized numerals **(T)**
- [ ] d. Invisible — system defaults, typography stays out of the way

> **Deferred, deliberately.** The Overseer will not settle type in the abstract:
> this decision sets the chrome for the whole site and wants seeing, not
> guessing. It gets its own bake-off once the layout exists — competing
> typefaces, modular scales, spacing scales, and color palettes shown side by
> side against the real Stack and the real map. Everything else in this document
> is decided; this one is scheduled.

**15. "Readable from across the room" is:**

- [ ] a. A primary requirement — size and contrast outrank elegance **(T)**
- [ ] b. Nice to have — optimize for arm's length, degrade gracefully
- [x] c. Not a goal — this is an up-close, lean-in app **(S)**

---

## The verdict

Answered by the Overseer, 2026-07-12. It is **not** one of the three as drawn.

**The base is The Sectional** (Q1b, Q3c, Q4c, Q5·all, Q12c, Q15c). A living
aeronautical chart, richly rendered, committed to the bit, read up close, grave
rather than shrill when something goes wrong.

**Three amendments, and they are the whole job:**

1. **It must survive the dark** (Q2c). Sectional was drawn as warm paper and
   nothing else. Following the system theme means the artifact needs a night
   edition — not a filter over the day one. This is the single biggest risk in
   the direction and the first thing to prototype.
2. **It breathes** (Q10a, Q11a) — Sectional as drawn held the chrome perfectly
   still. It doesn't anymore: glows pulse, trails are always-on fading ribbons
   for everyone, panels ease. Nocturne's pulse inside Sectional's body.
3. **It is quiet on the surface and deep on the axis** (Q7a, Q8d). No always-on
   annotation blocks — Sectional's dense data blocks are out. Glyphs only,
   callsign on hover. The information that would have lived in a sidebar lives
   in **the Stack** instead.

**Two inventions were commissioned that no candidate had** — the cast shadow
(Q6) and the Stack (Q8). Both are load-bearing and both need to be proven in a
prototype, not a mockup.

**One decision is scheduled, not made:** typography, scales, and palette (Q14),
via bake-off against the real layout.

Everything above is now input to `docs/design-direction.md`.
