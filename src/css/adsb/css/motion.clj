(ns adsb.css.motion
  "Motion (§6).

  The chrome breathes, quietly: panels settle in under 200ms ease-out, the
  live dots breathe (never blink), the selection ring draws itself in. The
  EMERGENCY treatment spends zero AMBIENT motion (§7) — nothing under
  .adsb-alerts or any -emergency class animates, ever. The one sanctioned
  exception is §7's own commission: the red-pen ellipse (.adsb-mayday) DRAWS
  ITSELF IN once — iteration-count 1, set inline by adsb.map.emergency so the
  suite can prove it — and then holds. Ink never blinks."
  (:require [adsb.css.decl :refer [decl]]
            [garden.stylesheet :refer [at-keyframes at-media]]))

(def settle
  (at-keyframes "adsb-settle"
    ["from" (decl :opacity 0
                  :transform "translateY(-4px)")]))

(def settle-up
  "The same settle, rising — for surfaces anchored to the BOTTOM edge (the
  phone's drawer stands up out of the Stack bar). A bottom sheet drifting
  DOWN into place reads as falling out of the sky it is supposed to grow
  from."
  (at-keyframes "adsb-settle-up"
    ["from" (decl :opacity 0
                  :transform "translateY(4px)")]))

(def breathe
  ;; 0% and 100% are separate frames because Garden cannot render a GROUPED
  ;; keyframe selector (`0%, 100%` comes out as `0, % { 1 0 0 % }`). Two
  ;; frames with the same declarations render identically — keyframes are
  ;; keyed by percentage, not by source grouping.
  (at-keyframes "adsb-breathe"
    ["0%" (decl :opacity 1)]
    ["50%" (decl :opacity 0.45)]
    ["100%" (decl :opacity 1)]))

(def ring-draw
  "The compass-pencil ring drawing itself in (§4): the dashes grow from
  nothing (both dasharrays sum to 7 of the circle's pathLength 70, so ten
  dashes press into place) while the offset sweeps them a step around — a
  pencil dragged once along a compass. Ink stays put after."
  (at-keyframes "adsb-ring-draw"
    ["from" (decl :stroke-dasharray "0 7"
                  :stroke-dashoffset 10
                  :opacity 0.2)]
    ["to" (decl :stroke-dasharray "3.2 3.8"
                :stroke-dashoffset 0
                :opacity 1)]))

(def mayday-draw
  "The red pen circling an emergency (§7): the dash sweeps from the full
  pathLength 100 to the ellipse's settled dashoffset 0 — one pass of the
  grease pencil, pressed in and left. Duration, delay, and the PROVABLE
  iteration-count 1 are inline longhands set by adsb.map.emergency."
  (at-keyframes "adsb-mayday-draw"
    ["from" (decl :stroke-dashoffset 100)]))

(def reduced-motion
  "ORDER-CRITICAL: this block must be emitted LAST — after every namespace
  that sets an animation or transition it disables. Media queries add no
  specificity, so these ties are broken by source order alone; emitted early
  (as it once was, riding along in `styles`) every rule here except the
  !important one was silently dead, and the panel settled, the dots breathed
  and the ticks drifted for exactly the readers who asked them not to
  (adsb-b1j). adsb.css.app holds the order; adsb.css-test pins it."
  (at-media {:prefers-reduced-motion :reduce}
    [:.adsb-panel
     :.adsb-roster
     ".adsb-selection-ring circle"
     ".adsb-conn-live .adsb-conn-dot"
     ".adsb-feeder-ok .adsb-feeder-dot"
     (decl :animation "none")]

    ;; !important because the draw-in rides inline longhands (see above);
    ;; reduced motion must still win, and the ellipse is simply already
    ;; drawn — the held state IS the design.
    [".adsb-mayday ellipse"
     (decl :animation-name [["none" "!important"]])]

    [:.adsb-roster
     (decl :transition "none")]))

(def styles
  "The keyframes only. `reduced-motion` is NOT here — it is emitted at the
  END of the cascade by adsb.css.app, where it can actually win."
  [settle settle-up breathe ring-draw mayday-draw])
