(ns adsb.css.stack
  "THE STACK (adsb-dgb.9) — the live flight-level ruler on the map's edge
  (design direction §9).

  The ruler's FILL is painted inline by adsb.ui.stack from the same
  adsb.map.style altitude stops the map uses — never duplicated here. Each
  tick/graduation carries its place on the ALTITUDE AXIS as the unitless
  --alt-pct custom property; this namespace maps that axis to `bottom`
  (desktop, vertical) or `left` (phone, horizontal) — same DOM, same
  semantics, rotated geometry (Q9c). Ink and paper come from adsb.css.tokens,
  so the Stack prints in both editions (adsb-dgb.7) without knowing the theme
  exists.

  This namespace is a BOUNDED SECTION: it owns the .adsb-stack-* rules and
  the map attribution's clearance from the ruler, and nothing else owns them.
  Its phone geometry lives at the bottom of this file rather than in
  adsb.css.phone, for the same reason."
  (:require
    [adsb.css.decl :refer [decl]]
    [garden.stylesheet :refer [at-media]]))

(def column
  [[:.adsb-stack
    (decl :position       "absolute"
          :top            0
          :right          0
          :bottom         0
          :z-index        1
          :display        "flex"
          :flex-direction "column"
          :width          "var(--stack-w)"
          :padding        "var(--s4) var(--s3) var(--s3)"
          :background     "var(--paper-veil)"
          :border-left    "1px solid var(--rule)"
          :color          "var(--ink)"
          :overflow       "visible"       ; labels spill onto the chart
          :box-sizing     "border-box")]

   ;; The ruler column — the gradient fill (inline style) with the airborne
   ;; ticks and flight-level graduations positioned along it.
   ;;
   ;; min-height is the ruler's FLOOR, and it is load-bearing (adsb-hsk).
   ;; `flex: 1` means flex-basis 0 — the ruler receives only LEFTOVER space —
   ;; while a shelf's basis is its content, and every tick in the ruler is
   ;; absolutely positioned, so the ruler's natural min-content size is zero.
   ;; A crowded shelf therefore squeezed the ruler to nothing rather than
   ;; being squeezed itself: a busy sky erased the very instrument that was
   ;; supposed to be showing it. The floor makes the scale's length a
   ;; property of the STACK, never of the shelves' population.
   ;;
   ;; touch-action: none — the ruler is scrubbed (adsb-4et), and a drag along
   ;; it must reach us, not become a browser pan.
   [:.adsb-stack-ruler
    (decl :position    "relative"
          :flex        1
          :width       "22px"
          :min-height  "55%"
          :margin-left "auto"             ; labels live to its left
          :border      "1px solid var(--rule-strong)"
          :touch-action "none")]

   ;; Flight-level graduations — dashed ink rules across the fill.
   [:.adsb-stack-grad
    (decl :position       "absolute"
          :left           0
          :right          0
          :bottom         "calc(var(--alt-pct, 0) * 1%)"
          :border-top     "1px dashed var(--rule-strong)"
          :pointer-events "none")]

   [:.adsb-stack-grad-label
    (decl :position             "absolute"
          :right                "calc(100% + 5px)"
          :transform            "translateY(-50%)"
          :font-size            "var(--t-2)"
          :letter-spacing       "0.04em"
          :font-variant-numeric "tabular-nums"
          :color                "var(--faded-ink)"
          :white-space          "nowrap")]])

(def ticks
  "A tick — one aircraft, at its true altitude. `bottom` (and `left` on phone)
  TRANSITIONS at just under the 1 Hz feeder cadence, so a climbing aircraft's
  tick drifts up the ruler instead of jumping."
  [[:.adsb-stack-tick
    (decl :cursor     "pointer"
          :transition "bottom 0.9s linear, left 0.9s linear")]

   [:.adsb-stack-tick:focus-visible
    (decl :outline        "2px solid var(--magenta)"
          :outline-offset "1px")]

   ;; Airborne: an ink bar across the ruler with a hairline paper halo so it
   ;; survives every band of the gradient. Centered on its altitude.
   [".adsb-stack-ruler .adsb-stack-tick"
    (decl :position   "absolute"
          :left       "-3px"
          :right      "-3px"
          :height     "3px"
          :bottom     "calc(var(--alt-pct, 0) * 1%)"
          :transform  "translateY(50%)"
          :background "var(--ink)"
          :box-shadow "0 0 0 1px var(--paper-halo)")]

   ;; HIT-SLOP (adsb-4et). The ink stays 3px; the TARGET does not. A ruler
   ;; tick is a hairline by design — that is what lets sixty of them share
   ;; one ruler — and a hairline is an impossible thing to hit with a finger
   ;; (the guideline is 44px). This transparent pad fattens the target along
   ;; the altitude axis and across the ruler in both stances, without
   ;; touching a single pixel of what is drawn. It is the poke; the scrub is
   ;; the aim.
   [".adsb-stack-ruler .adsb-stack-tick::before"
    (decl :content  "\"\""
          :position "absolute"
          :inset    "-9px")]

   [".adsb-stack-ruler .adsb-stack-tick:hover"
    (decl :height  "5px"
          :z-index 2)]

   ;; Selected: the magenta accent, prominent.
   [:.adsb-stack-tick-selected
    (decl :z-index 2)]

   [".adsb-stack-ruler .adsb-stack-tick-selected"
    (decl :height     "5px"
          :background "var(--magenta)"
          :box-shadow "0 0 0 1px var(--paper-veil)")]

   ;; Emergency: red, hatched, pinned prominent — ink that never blinks (§7).
   [:.adsb-stack-tick-emergency
    (decl :z-index 3)]

   [".adsb-stack-ruler .adsb-stack-tick-emergency"
    (decl :height     "7px"
          :background (str "repeating-linear-gradient("
                           "45deg, "
                           "var(--emergency) 0, "
                           "var(--emergency) 3px, "
                           "var(--paper-veil) 3px, "
                           "var(--paper-veil) 5px)")
          :box-shadow "0 0 0 1px var(--emergency)")]])

(def labels
  "The name beside a tick — shown on hover, selection, and always for an
  emergency. Identity lives on the Stack; the map stays glyphs-only."
  [[:.adsb-stack-tick-label
    (decl :position       "absolute"
          :right          "calc(100% + 8px)"
          :top            "50%"
          :transform      "translateY(-50%)"
          :padding        "1px var(--s2)"
          :background     "var(--paper-chrome)"
          :border         "1px solid var(--rule-strong)"
          :border-radius  "2px"
          :font-family    "var(--mono)"
          :font-size      "var(--t-1)"
          :line-height    1.5
          :color          "var(--ink)"
          :white-space    "nowrap"
          :pointer-events "none")]

   [".adsb-stack-tick-selected .adsb-stack-tick-label"
    (decl :border-color "var(--magenta)"
          :color        "var(--magenta)"
          :font-weight  700)]

   [".adsb-stack-tick-emergency .adsb-stack-tick-label"
    (decl :background   "var(--emergency)"
          :border-color "var(--emergency)"
          :color        "var(--on-emergency)"
          :font-weight  700)]])

(def shelves
  "The shelves at the ruler's foot: the surface band (ground cluster) and the
  small holding area for aircraft that never reported an altitude — absent is
  not zero, so they are placed NOWHERE on the scale."
  [[:.adsb-stack-shelf
    (decl :position      "relative"
          :display       "flex"
          :flex-wrap     "wrap"
          :align-items   "center"
          :gap           "3px"
          :min-height    "16px"
          :margin-top    "var(--s3)"
          :padding       "var(--s2) var(--s2) var(--s2) var(--s2)"
          :border        "1px solid var(--rule)"
          :border-radius "2px")]

   ;; The chip — the shelf's swatch, its label and its count, and the button
   ;; that opens its sheet of names. A button, because it is one: it is the only
   ;; thing on the phone's Stack that a finger is asked to press deliberately.
   ;; The caption is its non-interactive twin (the emergency count, which opens
   ;; nothing — the NOTAM ribbon is where you go to act on a distress squawk).
   [:.adsb-stack-shelf-chip :.adsb-stack-shelf-caption
    (decl :position    "relative"        ; the phone stance hangs hit-slop on it
          :display     "flex"
          :align-items "center"
          :gap         "var(--s1)"
          :margin      0
          :padding     0
          :background  "none"
          :border      "none"
          :font        "inherit"          ; the label's voice: adsb.css.captions
          :color       "inherit"
          :text-align  "left")]

   [:.adsb-stack-shelf-chip
    (decl :cursor "pointer")]

   ;; THE MAP KEY (adsb-sod). `● GND 3` is a legend row and a count in one
   ;; breath. The colour is painted INLINE by adsb.ui.stack from the current
   ;; edition's adsb.map.style palette — never a token mirroring it — which is
   ;; the guarantee the deleted corner legend existed to hold: re-skin the ramp
   ;; and the key moves with the planes, or the test fails.
   ;;
   ;; The faded ink here is the FALLBACK, and it is what a swatch wears when its
   ;; state has no aircraft in it: nothing of that colour is on the chart, so
   ;; there is nothing of that colour to key.
   [:.adsb-stack-shelf-swatch
    (decl :flex          "none"
          :width         "7px"
          :height        "7px"
          :border-radius "50%"
          :background    "var(--faded-ink)"
          :box-shadow    "0 0 0 1px var(--paper-halo)")]

   ;; EMG is PERMANENT — it is a census of the sky, like GND and NO ALT, and a
   ;; stated zero beats an implied one on a distress readout. But the RED is
   ;; not: §7 makes red the ink that never blinks, and it holds that power only
   ;; by being absent from a calm chart. So the caption is quiet until an
   ;; aircraft squawks, and only then does it take the red — at which point it
   ;; is the key for the red now on the chart (adsb.ui.stack/emergency-shelf).
   [:.adsb-stack-emergency-active
    (decl :border-color "var(--emergency)")]

   [".adsb-stack-emergency-active .adsb-stack-shelf-count"
    (decl :color       "var(--emergency)"
          :font-weight 700)]

   [:.adsb-stack-shelf-chip:focus-visible
    (decl :outline        "2px solid var(--magenta)"
          :outline-offset "2px")]

   ;; voice: adsb.css.captions
   [:.adsb-stack-shelf-label
    (decl :color        "var(--faded-ink)"
          :margin-right "var(--s1)")]

   ;; The count. On the desktop the dots ARE the count, drawn one per
   ;; aircraft, so the numeral would only say it twice; the phone drops the
   ;; dots and this is what remains (adsb-hsk).
   [:.adsb-stack-shelf-count
    (decl :display              "none"
          :font-family          "var(--mono)"
          :font-size            "var(--t-1)"
          :font-variant-numeric "tabular-nums"
          :color                "var(--ink)")]

   ;; MapLibre pins its attribution to the map's bottom-right — under the
   ;; Stack. Attribution must stay readable (OpenFreeMap's terms flow through
   ;; it), so the control corner shifts clear of the ruler instead of hiding
   ;; beneath it; the phone rule below lifts it above the recumbent Stack.
   [:.maplibregl-ctrl-bottom-right
    (decl :right "var(--stack-w)")]

   ;; Shelf ticks cluster as dots — grouped, never ranked.
   [".adsb-stack-shelf .adsb-stack-tick"
    (decl :position      "relative"
          :width         "7px"
          :height        "7px"
          :border-radius "50%"
          :background    "var(--alt-ground)"
          :box-shadow    "0 0 0 1px var(--paper-halo)")]

   [".adsb-stack-unknown .adsb-stack-tick"
    (decl :background "var(--alt-unknown)")]

   [".adsb-stack-shelf .adsb-stack-tick-selected"
    (decl :background "var(--magenta)")]

   [".adsb-stack-shelf .adsb-stack-tick-emergency"
    (decl :background "var(--emergency)"
          :box-shadow "0 0 0 1px var(--emergency), 0 0 0 2px var(--paper-veil)")]

   [".adsb-stack-shelf .adsb-stack-tick-label"
    (decl :right     "auto"
          :top       "auto"
          :left      "50%"
          :bottom    "calc(100% + 6px)"
          :transform "translateX(-50%)")]])

(def sheet
  "The open shelf's sheet — its residents, NAMED (adsb-hsk). A dot cluster
  can only ever tell you how many; this tells you who, which is the one
  question the shelves could not answer in either stance. It floats above its
  chip, so it costs the Stack no permanent room in either.

  ORDER-CRITICAL: these rules restate a tick as a row, and they tie on
  specificity with the dot rules in `shelves` above (.adsb-stack-sheet
  .adsb-stack-tick vs .adsb-stack-shelf .adsb-stack-tick — both 0,2,0). They
  win on SOURCE ORDER alone, so this block must stay after that one."
  [[:.adsb-stack-sheet
    (decl :position      "absolute"
          :right         0
          :bottom        "calc(100% + var(--s2))"
          :z-index       4
          :display       "flex"
          :flex-direction "column"
          :gap           "1px"
          :min-width     "100%"
          :width         "max-content"
          :max-width     "60vw"
          :max-height    "40vh"
          :overflow-y    "auto"
          :padding       "var(--s2)"
          :background    "var(--paper-chrome)"
          :border        "1px solid var(--rule-strong)"
          :border-radius "2px")]

   ;; A resident, as a row: the dot it was, and the name it never had. The
   ;; dot moves to a ::before so the row itself is free to be a row — the
   ;; variant rules below tint that pseudo-element, never the row's own
   ;; background, which would paint the whole strip magenta.
   [".adsb-stack-sheet .adsb-stack-tick"
    (decl :display       "flex"
          :align-items   "center"
          :gap           "var(--s2)"
          :width         "auto"
          :height        "auto"
          :min-height    "32px"
          :padding       "0 var(--s2)"
          :border-radius 0
          :background    "none"
          :box-shadow    "none")]

   [".adsb-stack-sheet .adsb-stack-tick::before"
    (decl :content       "\"\""
          :flex          "none"
          :width         "7px"
          :height        "7px"
          :border-radius "50%"
          :background    "var(--alt-ground)"
          :box-shadow    "0 0 0 1px var(--paper-halo)")]

   [".adsb-stack-unknown .adsb-stack-sheet .adsb-stack-tick::before"
    (decl :background "var(--alt-unknown)")]

   [".adsb-stack-sheet .adsb-stack-tick-selected::before"
    (decl :background "var(--magenta)")]

   [".adsb-stack-sheet .adsb-stack-tick-emergency::before"
    (decl :background "var(--emergency)"
          :box-shadow "0 0 0 1px var(--emergency)")]

   ;; In a sheet the name is the row, not a tooltip pinned above a dot.
   [".adsb-stack-sheet .adsb-stack-tick-label"
    (decl :position   "static"
          :transform  "none"
          :padding    0
          :background "none"
          :border     "none")]])

(def phone
  "Phone: the ruler lies down along the bottom edge — identical semantics,
  rotated geometry. --stack-axis flips the gradient; --alt-pct maps to `left`
  instead of `bottom`. Neither stage degrades (Q9c).

  THE SHELVES ARE CAPTIONS HERE (adsb-hsk), not shelves at all. The dots go —
  a cluster of thirty identical dots is a picture of a crowd on the long axis
  and a wall on the short one — and once the dots are gone the box has nothing
  left to hold but a word. So the box goes too, and what remains is what the
  shelf always meant: GND 3, NO ALT 31, printed in the caption voice.

  With them out of the ruler's row, they cannot compete with it for width at
  all: they take a line of their own above it, and the ruler takes the whole
  bar. The row-gap is the flight levels' room — the graduations hang upward
  off the ruler's top edge into it (bottom: 100% + 3px), so the gap must clear
  a caption's height or the FLs will print through the counts.

  The bar does not grow to pay for this: a caption line, the graduations' gap,
  and the ruler come to ~51px inside the 63px the Stack already had."
  (at-media {:max-width "640px"}
    [:.adsb-stack
     (decl :top            "auto"
           :left           0
           :right          0
           :bottom         0
           :width          "auto"
           ;; The bar keeps its --stack-w of CONTENT and grows by whatever the
           ;; phone reserves at its bottom edge (see the padding below).
           :height         "calc(var(--stack-w) + env(safe-area-inset-bottom, 0px))"
           :flex-direction "row"
           :flex-wrap      "wrap"          ; the captions' line, then the ruler's
           :align-items    "center"
           :align-content  "flex-end"      ; both lines settle onto the bottom edge
           :row-gap        "16px"          ; the graduations live in here
           ;; THE RULER MUST CLEAR THE HOME INDICATOR. The Stack is pinned to
           ;; the bottom edge, and the bottom edge of a modern phone belongs to
           ;; the operating system: a swipe up from it goes home. The ruler is
           ;; SCRUBBED — a press and a drag along it — and a scrub that starts
           ;; inside that zone is a scrub the OS may take for itself. The
           ;; safe-area inset lifts it clear on the phones that have one, and is
           ;; zero on the phones that do not.
           :padding        "var(--s2) 10px calc(var(--s3) + env(safe-area-inset-bottom, 0px))"
           :border-left    "none"
           :border-top     "1px solid var(--rule)")]

    ;; The whole bar, on its own line: `flex-basis: 100%` is what makes the
    ;; ruler's length unstarvable, and it is a stronger guarantee than any
    ;; floor — there is nothing left in the row to lose the width TO. (The
    ;; desktop's min-height still earns its keep: there, the shelves and the
    ;; ruler do share a column.) min-height goes back to 0: the recumbent
    ;; ruler's height is its thickness, and 55% of the bar would swell it.
    [:.adsb-stack-ruler
     (decl :--stack-axis "to right"
           :order        2
           :flex         "1 0 100%"
           :width        "auto"
           :height       "22px"
           :min-height   0
           :margin-left  0
           :align-self   "flex-end")]

    [:.adsb-stack-grad
     (decl :left        "calc(var(--alt-pct, 0) * 1%)"
           :right       "auto"
           :top         0
           :bottom      0
           :border-top  "none"
           :border-left "1px dashed var(--rule-strong)")]

    [:.adsb-stack-grad-label
     (decl :right     "auto"
           :left      0
           :top       "auto"
           :bottom    "calc(100% + 3px)"
           :transform "none")]

    [".adsb-stack-ruler .adsb-stack-tick"
     (decl :left      "calc(var(--alt-pct, 0) * 1%)"
           :right     "auto"
           :top       "-3px"
           :bottom    "-3px"
           :width     "3px"
           :height    "auto"
           :transform "translateX(-50%)")]

    [".adsb-stack-ruler .adsb-stack-tick:hover"
     (decl :height "auto"
           :width  "5px")]

    [".adsb-stack-ruler .adsb-stack-tick-selected"
     (decl :height "auto"
           :width  "5px")]

    [".adsb-stack-ruler .adsb-stack-tick-emergency"
     (decl :height "auto"
           :width  "7px")]

    [:.adsb-stack-tick-label
     (decl :right     "auto"
           :top       "auto"
           :left      "50%"
           :bottom    "calc(100% + 6px)"
           :transform "translateX(-50%)")]

    ;; A caption, not a shelf: no border, no padding, no box. It holds a word
    ;; and a number, and a word and a number do not need a container.
    [:.adsb-stack-shelf
     (decl :order         1
           :flex          "none"
           :margin        0
           :margin-left   "var(--s4)"
           :padding       0
           :min-height    0
           :border        "none"
           :border-radius 0
           :align-items   "center")]

    ;; THE CENSUS SITS LEFT; THE SKY'S TALLY SITS RIGHT. Two reasons, and the
    ;; second is the load-bearing one:
    ;;
    ;;   * the three counts read left-to-right with the ruler beneath them,
    ;;     starting where the ruler starts;
    ;;   * their SHEETS open upward, and the map attribution's (i) button is
    ;;     pinned to the bottom-right corner directly above this bar. A sheet
    ;;     opening from the right end opened straight underneath it. Nothing
    ;;     that opens may live in that corner — so what lives there is the one
    ;;     caption that opens nothing.
    [:.adsb-stack-traffic
     (decl :margin-left "auto")]

    ;; The health dot rides the caption line, at its far end, after the traffic
    ;; fraction. `order` and not DOM order: the ruler takes order 2 to claim a
    ;; line of its own, so anything without an explicit order sorts BEFORE the
    ;; captions and lands at the head of the row — which is exactly where the dot
    ;; first appeared.
    [:.adsb-health
     (decl :order 1)]


    ;; The dots stand down. Their aircraft are not gone — they are in the
    ;; caption's count, and they are named in its sheet.
    [".adsb-stack-shelf > .adsb-stack-tick"
     (decl :display "none")]

    [:.adsb-stack-shelf-count
     (decl :display "block")]

    ;; HIT-SLOP, the ticks' trick again (adsb-4et): the caption prints at
    ;; --t-2, which is nowhere near a finger's 44px, and it now has no box to
    ;; fatten. So the target grows and the ink does not. The horizontal reach
    ;; is held under the captions' --s4 gap, so the two never overlap and each
    ;; count opens its own sheet.
    [".adsb-stack-shelf-chip::before"
     (decl :content  "\"\""
           :position "absolute"
           :inset    "-14px -6px")]

    ;; The sheets grow RIGHTWARD now, away from the (i) in the far corner, from
    ;; chips that sit at the left.
    [:.adsb-stack-sheet
     (decl :left      0
           :right     "auto"
           :max-width "calc(100vw - 2 * var(--s3))")]

    [".adsb-stack-sheet .adsb-stack-tick"
     (decl :min-height "44px")]

    ;; The bottom edge is the Stack's now: the map attribution and the margin
    ;; column both step up out of its lane.
    [:.maplibregl-ctrl-bottom-right
     (decl :right  0
           :bottom "calc(var(--stack-w) + env(safe-area-inset-bottom, 0px))")]))

(def styles
  ;; `sheet` after `shelves`, and `phone` last: both win their ties on source
  ;; order, and both say so in their own docstrings.
  [column ticks labels shelves sheet phone])
