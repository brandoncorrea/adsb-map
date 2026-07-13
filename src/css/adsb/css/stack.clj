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
          :top            "var(--header-h)"
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
   [:.adsb-stack-ruler
    (decl :position    "relative"
          :flex        1
          :width       "22px"
          :margin-left "auto"             ; labels live to its left
          :border      "1px solid var(--rule-strong)")]

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

   ;; voice: adsb.css.captions
   [:.adsb-stack-shelf-label
    (decl :color        "var(--faded-ink)"
          :margin-right "var(--s1)")]

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

(def phone
  "Phone: the ruler lies down along the bottom edge — identical semantics,
  rotated geometry. --stack-axis flips the gradient; --alt-pct maps to `left`
  instead of `bottom`. Neither stage degrades (Q9c)."
  (at-media {:max-width "640px"}
    [:.adsb-stack
     (decl :top            "auto"
           :left           0
           :right          0
           :bottom         0
           :width          "auto"
           :height         "var(--stack-w)"
           :flex-direction "row"
           :align-items    "stretch"
           :padding        "22px 10px var(--s3)"
           :border-left    "none"
           :border-top     "1px solid var(--rule)")]

    [:.adsb-stack-ruler
     (decl :--stack-axis "to right"
           :flex         1
           :width        "auto"
           :height       "22px"
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

    [:.adsb-stack-shelf
     (decl :margin-top    0
           :margin-left   "var(--s3)"
           :min-width     "34px"
           :align-content "center")]

    ;; The bottom edge is the Stack's now: the map attribution and the margin
    ;; column both step up out of its lane.
    [:.maplibregl-ctrl-bottom-right
     (decl :right  0
           :bottom "var(--stack-w)")]))

(def styles
  [column ticks labels shelves phone])
