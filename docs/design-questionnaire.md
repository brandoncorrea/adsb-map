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
- [ ] b. A beautiful printed artifact that happens to be alive **(S)**
- [ ] c. Public signage — loud, instant, zero ambiguity **(T)**
- [ ] d. No strong pull — decide it for me from the rest of my answers

**2. Dark or light?**

- [ ] a. Dark, always **(N, T)**
- [ ] b. Light, always **(S)**
- [ ] c. Follow the system theme — design must carry both
- [ ] d. Dark first; a light variant can come later

**3. How much personality may the app have?**

- [ ] a. Subdued and professional — a tool, not a statement
- [ ] b. Distinctive but restrained — opinionated details, quiet overall
- [ ] c. Unapologetically themed — commit to the bit **(S, T lean here)**

## B. The Map's Voice

**4. How much should the basemap itself speak?**

- [ ] a. Near-silent — hairline coast and roads on black, planes are everything **(T)**
- [ ] b. Muted canvas — faint geography for orientation, never competing **(N)**
- [ ] c. Richly rendered — terrain, water, and labels are part of the pleasure **(S)**

**5. Which geographic context do you actually want visible? *(check all that apply)***

- [ ] a. Coastline / water
- [ ] b. Major roads
- [ ] c. Place-name labels
- [ ] d. Terrain / contours
- [ ] e. None — just the aircraft

**6. How should altitude be encoded in the aircraft color?**

- [ ] a. Continuous gradient ramp — smooth warm-to-cool **(N)**
- [ ] b. Discrete named bands — GND / <10k / 10–25k / >25k chips **(T)**
- [ ] c. A small set of ink colors, chart-legend style **(S)**

## C. Density & Chrome

**7. Default information density on the map itself?**

- [ ] a. Minimal — glyphs only; callsign appears on hover/tap
- [ ] b. Moderate — callsign labels always, details on selection **(N, T)**
- [ ] c. Dense — every plane carries an annotation data block **(S)**

**8. The aircraft list sidebar, by default:**

- [ ] a. Always visible on desktop — the map shares the stage
- [ ] b. Collapsed by default, one keystroke/tap away — the map stands alone
- [ ] c. Bottom sheet everywhere, phone-style, even on desktop

**9. Phone vs desktop — which is the primary stage?**

- [ ] a. Phone-first — glance from the couch is the canonical use
- [ ] b. Desktop-first — the wall/desk monitor is canonical
- [ ] c. Genuinely equal — neither may degrade

## D. Motion

**10. Beyond aircraft gliding (non-negotiable), how much motion?**

- [ ] a. Let it breathe — glows pulse, trails shimmer, panels ease **(N)**
- [ ] b. Restrained — planes move; the chrome holds perfectly still **(S)**
- [ ] c. Mechanical — discrete ticks and flips, like a split-flap board **(T)**

**11. Position trails behind aircraft:**

- [ ] a. Always on — fading ribbons for everyone
- [ ] b. Only for the selected/hovered aircraft
- [ ] c. Off — current position and heading are enough

## E. Emergency Loudness

**12. A plane squawks 7700. How loud is the app allowed to get?**

- [ ] a. Full takeover — a flashing red band seizes the top of the screen **(T)**
- [ ] b. Prominent but contained — pulsing halo, red ribbon, pinned row **(N)**
- [ ] c. Marked and annotated — circled in red pen with a MAYDAY stamp; grave, not flashing **(S)**

**13. If the emergency aircraft is off-screen when it squawks:**

- [ ] a. Banner only — I'll pan myself
- [ ] b. Banner + the map auto-pans/zooms to include it
- [ ] c. Banner + an edge arrow pointing toward it; no camera hijack

## F. Typography & Glanceability

**14. Typographic leaning?**

- [ ] a. Engineering — quiet sans UI, tabular monospace for every number **(N)**
- [ ] b. Editorial — serif headers, italic captions, mono data blocks **(S)**
- [ ] c. Signage — heavy grotesque caps, oversized numerals **(T)**
- [ ] d. Invisible — system defaults, typography stays out of the way

**15. "Readable from across the room" is:**

- [ ] a. A primary requirement — size and contrast outrank elegance **(T)**
- [ ] b. Nice to have — optimize for arm's length, degrade gracefully
- [ ] c. Not a goal — this is an up-close, lean-in app **(S)**

---

## How your answers map to the three candidates

Count your **(N)** / **(S)** / **(T)** tags:

- **Mostly (N)** → **Nocturne** as drawn. Q10a and Q12b confirm it.
- **Mostly (S)** → **The Sectional**. Q2b and Q4c are its load-bearing walls —
  if either goes the other way, Sectional needs rethinking, not tweaking.
- **Mostly (T)** → **Terminal**. Q15a and Q6b are its heart.
- **A split** is not a failure — it's a commission. The likeliest hybrids:
  Nocturne's canvas with Terminal's discrete bands and emergency takeover
  (N-base, T at Q6/Q12), or Nocturne's darkness with Sectional's annotation
  density (N-base, S at Q7). Q1–Q3 set the base; everything else tunes it.

Untagged options (density, sidebar, phone primacy, trails, off-screen
emergencies) apply to *any* winner and will be folded into
`docs/design-direction.md` when the direction is decided.
