(ns adsb.css.margin
  "The margin column — the map key, and nothing else now.

  Tucked bottom-left, out of the header's way, on a paper veil. The session
  stats used to sit here in a bordered chip above it; they were vitals wearing
  marginalia's clothes, and they have gone up to the header where the counts
  already live (adsb-33i — their rules moved with them, to adsb.css.shell)."
  (:require
    [adsb.css.decl :refer [decl]]))

(def column
  [[:.adsb-margin
    (decl :position       "absolute"
          :left           "var(--s5)"
          :bottom         "var(--s5)"
          :z-index        1
          :display        "flex"
          :flex-direction "column"
          :align-items    "flex-start"
          :gap            "var(--s2)"
          :max-width      "calc(100vw - var(--stack-w) - 2 * var(--s5))")]])

(def legend
  "The legend — a chart margin table. Every swatch is painted from the
  adsb.map.style palette of the CURRENT edition (adsb.ui.legend derefs the
  theme ratom)."
  [[:.adsb-legend
    (decl :display       "flex"
          :gap           "var(--s5)"
          :padding       "var(--s3) var(--s4)"
          :background    "var(--paper-veil)"
          :color         "var(--ink)"
          :border        "1px solid var(--rule-strong)"
          :border-radius "2px"
          :font-size     "var(--t-1)"
          :box-sizing    "border-box")]

   ;; Block headings print in the pen: caps-tracked bold magenta (§5).
   [:.adsb-legend-heading
    (decl :margin         "0 0 var(--s2)"
          :font-size      "var(--t-2)"
          :font-weight    700
          :text-transform "uppercase"
          :letter-spacing "0.1em"
          :color          "var(--magenta)")]

   [:.adsb-legend-list
    (decl :margin     0
          :padding    0
          :list-style "none")]

   [:.adsb-legend-row
    (decl :display     "flex"
          :align-items "center"
          :gap         "var(--s3)"
          :line-height 1.7
          :font-size   "var(--t-1)")]

   [:.adsb-legend-swatch
    (decl :width         "13px"
          :height        "13px"
          :border-radius "2px"
          :flex          "none"
          :box-shadow    "inset 0 0 0 1px var(--rule)")]

   [:.adsb-legend-label
    (decl :white-space "nowrap")]])

(def styles
  [column legend])
