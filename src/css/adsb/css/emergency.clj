(ns adsb.css.emergency
  "The §7 map annotations (adsb.map.emergency).

  The chart's own emergency marks, beside the NOTAM strip's chrome: the
  red-pen double ellipse + MAYDAY stamp on a squawking aircraft in view, the
  edge arrow when it is beyond the frame. Both print in the edition's
  emergency red via var(--emergency) — §2's two reds, one variable. The
  ellipse's ONE-TIME draw-in is inline (see adsb.css.motion); everything in
  this namespace is still ink."
  (:require
    [adsb.css.decl :refer [decl]]))

(def mayday
  [[:.adsb-mayday
    (decl :width          "84px"   ; mirror adsb.map.emergency/ellipse-box-px
          :height         "84px"
          ;; the plane beneath stays clickable
          :pointer-events "none")]

   [".adsb-mayday svg"
    (decl :display  "block"
          :width    "100%"
          :height   "100%"
          :overflow "visible")]

   [".adsb-mayday ellipse"
    (decl :fill           "none"
          :stroke         "var(--emergency)"
          :stroke-width   "2px"
          :stroke-linecap "round")]])

(def stamp
  "The MAYDAY stamp: a small rotated annotation on the paper beside the
  ellipse, in the plotter's hand — red ink, pressed slightly askew. Placement
  (left/top) is chosen once by adsb.map.emergency from the track at placement;
  only its DATA ever changes after."
  [[:.adsb-mayday-stamp
    (decl :position       "absolute"
          :transform      "translate(-50%, -50%) rotate(-4deg)"
          :display        "flex"
          :flex-direction "column"
          :gap            "var(--s1)"
          :width          "max-content"
          :max-width      "168px"
          :padding        "var(--s2) var(--s3)"
          :background     "var(--paper-veil)"
          :border         "1.5px solid var(--emergency)"
          :border-radius  "1px"
          :box-shadow     "2px 2px 0 var(--rule-faint)"
          :color          "var(--emergency)"
          :font-size      "var(--t-1)"
          :line-height    1.25
          :text-align     "left")]

   [:.adsb-mayday-word
    (decl :font-weight    700
          :text-transform "uppercase"
          :letter-spacing "0.12em"
          :border-bottom  "1px solid var(--emergency)"
          :padding-bottom "var(--s1)")]

   [:.adsb-mayday-callsign
    (decl :font-weight    700
          :letter-spacing "0.02em"
          ;; a hostile callsign must not shear the stamp
          :overflow-wrap  "anywhere")]

   [:.adsb-mayday-facts
    (decl :display     "flex"
          :align-items "baseline"
          :gap         "var(--s2)"
          :white-space "nowrap")]

   ;; voice: adsb.css.captions
   [:.adsb-mayday-label
    (decl :opacity 0.75)]

   [:.adsb-mayday-value
    (decl :font-variant-numeric "tabular-nums"
          :text-transform       "uppercase")]])

(def edge-arrow
  "The edge arrow (Q13c): pinned just inside the frame on the bearing toward
  an off-screen emergency. A button — it OFFERS selection; the camera is never
  hijacked. Zero animation of any kind."
  [;; ORDER: `font: inherit` resets font-size AND font-weight; both longhands
   ;; below it are the ones that must survive. See adsb.css.decl.
   [:.adsb-edge-arrow
    (decl :display       "flex"
          :align-items   "center"
          :gap           "var(--s2)"
          :padding       "var(--s1) var(--s3) var(--s1) var(--s2)"
          :background    "var(--paper-veil)"
          :border        "1.5px solid var(--emergency)"
          :border-radius "1px"
          :box-shadow    "2px 2px 0 var(--rule-faint)"
          :color         "var(--emergency)"
          :font          "inherit"
          :font-size     "var(--t-1)"
          :font-weight   700
          :cursor        "pointer")]

   [:.adsb-edge-arrow:hover
    (decl :filter "brightness(1.08)")]

   [:.adsb-edge-arrow:focus-visible
    (decl :outline        "2px solid var(--emergency)"
          :outline-offset "2px")]

   [:.adsb-edge-arrow-glyph
    (decl :display "block"
          :width   "14px"
          :height  "14px"
          :fill    "var(--emergency)")]

   [:.adsb-edge-arrow-callsign
    (decl :letter-spacing "0.02em"
          :overflow-wrap  "anywhere")]

   [:.adsb-edge-arrow-distance
    (decl :font-weight          400
          :font-variant-numeric "tabular-nums"
          :text-transform       "uppercase")]])

(def styles
  [mayday stamp edge-arrow])
