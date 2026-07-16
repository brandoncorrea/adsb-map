(ns adsb.css.emergency
  (:require [adsb.css.decl :refer [decl]]))

(def mayday
  [[:.adsb-mayday
    {:width          "84px"
     :height         "84px"
     :pointer-events "none"}]

   [".adsb-mayday svg"
    {:display  "block"
     :width    "100%"
     :height   "100%"
     :overflow "visible"}]

   [".adsb-mayday ellipse"
    (decl :fill "none"
          :stroke "var(--emergency)"
          :stroke-width "2px"
          :stroke-linecap "round")]])

(def stamp
  [[:.adsb-mayday-stamp
    (decl :position "absolute"
          :transform "translate(-50%, -50%) rotate(-4deg)"
          :display "flex"
          :flex-direction "column"
          :gap "var(--s1)"
          :width "max-content"
          :max-width "168px"
          :padding "var(--s2) var(--s3)"
          :background "var(--paper-veil)"
          :border "1.5px solid var(--emergency)"
          :border-radius "1px"
          :box-shadow "2px 2px 0 var(--rule-faint)"
          :color "var(--emergency)"
          :font-size "var(--t-1)"
          :line-height 1.25
          :text-align "left")]

   [:.adsb-mayday-word
    (decl :font-weight 700
          :text-transform "uppercase"
          :letter-spacing "0.12em"
          :border-bottom "1px solid var(--emergency)"
          :padding-bottom "var(--s1)")]

   [:.adsb-mayday-callsign
    {:font-weight    700
     :letter-spacing "0.02em"
     :overflow-wrap  "anywhere"}]

   [:.adsb-mayday-facts
    {:display     "flex"
     :align-items "baseline"
     :gap         "var(--s2)"
     :white-space "nowrap"}]

   [:.adsb-mayday-label
    {:opacity 0.75}]

   [:.adsb-mayday-value
    {:font-variant-numeric "tabular-nums"
     :text-transform       "uppercase"}]])

(def edge-arrow
  [[:.adsb-edge-arrow
    (decl :display "flex"
          :align-items "center"
          :gap "var(--s2)"
          :padding "var(--s1) var(--s3) var(--s1) var(--s2)"
          :background "var(--paper-veil)"
          :border "1.5px solid var(--emergency)"
          :border-radius "1px"
          :box-shadow "2px 2px 0 var(--rule-faint)"
          :color "var(--emergency)"
          :font "inherit"
          :font-size "var(--t-1)"
          :font-weight 700
          :cursor "pointer")]

   [:.adsb-edge-arrow:hover
    {:filter "brightness(1.08)"}]

   [:.adsb-edge-arrow:focus-visible
    {:outline        "2px solid var(--emergency)"
     :outline-offset "2px"}]

   [:.adsb-edge-arrow-glyph
    {:display "block"
     :width   "14px"
     :height  "14px"
     :fill    "var(--emergency)"}]

   [:.adsb-edge-arrow-callsign
    {:letter-spacing "0.02em"
     :overflow-wrap  "anywhere"}]

   [:.adsb-edge-arrow-distance
    {:font-weight          400
     :font-variant-numeric "tabular-nums"
     :text-transform       "uppercase"}]])

(def styles [mayday stamp edge-arrow])
