(ns adsb.css.roster
  (:require [adsb.css.decl :refer [decl]]
            [adsb.css.shell :as shell]))

(def dock
  [[:.adsb-roster
    (decl :position "absolute"
          :top "var(--safe-top)"
          :right "var(--safe-right)"
          :bottom "var(--safe-bottom)"
          :z-index 2
          :display "flex"
          :flex-direction "column"
          :width "var(--roster-w)"
          :background "var(--paper-veil)"
          :border-left "1px solid var(--rule)"
          :color "var(--ink)"
          :box-sizing "border-box"
          :transition "width 180ms ease-out"
          :overflow "hidden")]

   [".adsb-roster:not(.is-open)"
    {:width "var(--roster-rail-w)"}]

   [:.adsb-roster-rail
    (decl :position "relative"
          :display "flex"
          :flex-direction "column"
          :align-items "stretch"
          :flex "none"
          :border-bottom "1px solid var(--rule-faint)"
          :padding "var(--s3) 36px var(--s3) var(--s2)"
          :box-sizing "border-box"
          :touch-action "none"
          :user-select "none")]

   [".adsb-roster:not(.is-open)"
    {:touch-action "none"}]

   [:.adsb-roster-handle
    (decl :display "flex"
          :flex-direction "row"
          :align-items "center"
          :justify-content "flex-start"
          :gap "var(--s3)"
          :width "100%"
          :padding "var(--s2) var(--s2)"
          :background "transparent"
          :border "none"
          :font "inherit"
          :font-size "var(--t-1)"
          :font-weight 700
          :letter-spacing "0.06em"
          :color "var(--faded-ink)"
          :cursor "pointer"
          :text-align "left"
          :box-sizing "border-box"
          :touch-action "none"
          :user-select "none")]

   [:.adsb-roster-handle-bar
    {:width         "3px"
     :height        "28px"
     :border-radius "2px"
     :flex          "none"
     :background    "var(--rule)"}]

   [:.adsb-roster-handle-label
    {:white-space   "nowrap"
     :overflow      "hidden"
     :text-overflow "ellipsis"}]

   [".adsb-roster:not(.is-open) .adsb-roster-rail"
    {:flex          1
     :border-bottom "none"
     :padding       "var(--s3) var(--s1)"
     :align-items   "center"
     :gap           "var(--s2)"}]

   [".adsb-roster:not(.is-open) .adsb-roster-handle"
    (decl :flex-direction "column"
          :align-items "center"
          :gap "var(--s3)"
          :padding "var(--s2) 0"
          :height "auto"
          :flex 1
          :min-height 0
          :width "100%")]

   [".adsb-roster:not(.is-open) .adsb-roster-handle-bar"
    {:display "none"}]

   [".adsb-roster:not(.is-open) .adsb-roster-handle-label"
    {:writing-mode   "vertical-rl"
     :transform      "rotate(180deg)"
     :letter-spacing "0.1em"}]

   [".adsb-roster .adsb-health"
    {:position        "absolute"
     :top             "var(--s3)"
     :right           "var(--s3)"
     :margin          0
     :padding         0
     :justify-content "center"
     :align-items     "center"
     :gap             "var(--s2)"
     :z-index         1}]

   [".adsb-roster .adsb-conn-label,
     .adsb-roster .adsb-feeder-label"
    shell/visually-hidden]

   [".adsb-roster .adsb-conn,
     .adsb-roster .adsb-feeder"
    {:padding      0
     :border-color "transparent"
     :background   "transparent"}]

   [".adsb-roster .adsb-conn-dot,
     .adsb-roster .adsb-feeder-dot"
    {:width  "9px"
     :height "9px"}]

   [".adsb-roster .adsb-conn-down,
     .adsb-roster .adsb-feeder-down"
    {:background    "var(--emergency)"
     :border-radius "50%"
     :padding       "2px"}]

   [".adsb-roster:not(.is-open) .adsb-health"
    {:position        "static"
     :top             "auto"
     :right           "auto"
     :transform       "none"
     :order           -1
     :flex            "none"
     :justify-content "center"
     :margin          0
     :padding         0
     :z-index         "auto"}]

   [:.adsb-roster-body
    {:flex           1
     :min-height     0
     :min-width      0
     :overflow       "auto"
     :display        "flex"
     :flex-direction "column"}]

   [:.adsb-roster-toolbar
    {:padding        "var(--s3) var(--s4) var(--s3)"
     :display        "flex"
     :flex-direction "column"
     :gap            "var(--s3)"
     :flex           "none"
     :border-bottom  "1px solid var(--rule-faint)"
     :background     "var(--paper-chrome)"}]

   [:.adsb-roster-search
    {:display        "flex"
     :flex-direction "column"
     :gap            "var(--s1)"}]

   [:.adsb-roster-search-field
    {:position    "relative"
     :display     "flex"
     :align-items "center"}]

   [".adsb-roster-search-field .adsb-icon"
    {:position       "absolute"
     :left           "var(--s3)"
     :font-size      "var(--t0)"
     :color          "var(--faded-ink)"
     :pointer-events "none"}]

   [:.adsb-roster-search-input
    {:width         "100%"
     :box-sizing    "border-box"
     :padding       "var(--s3) var(--s3) var(--s3) calc(var(--s3) + 1em + var(--s2))"
     :font          "inherit"
     :font-size     "var(--t0)"
     :font-weight   700
     :background    "var(--paper-chrome)"
     :color         "var(--ink)"
     :border        "1px solid var(--rule)"
     :border-radius "2px"
     :outline       "none"}]

   [:.adsb-roster-search-input:focus
    {:border-color "var(--magenta)"
     :box-shadow   "0 0 0 2px rgba(168, 58, 99, 0.15)"}]

   [:.adsb-roster-key
    {:padding       "var(--s2) var(--s2)"
     :background    "var(--paper-veil)"
     :border        "1px solid var(--rule-faint)"
     :border-radius "2px"}]

   [:.adsb-roster-key-ramp
    {:height        "8px"
     :border-radius "2px"
     :border        "1px solid var(--rule-faint)"
     :margin-bottom "var(--s2)"}]

   [:.adsb-roster-key-labels
    {:display         "flex"
     :justify-content "space-between"
     :font-size       "var(--t-2)"
     :font-weight     700
     :letter-spacing  "0.04em"
     :color           "var(--faded-ink)"}]

   [:.adsb-roster-cols
    (decl :display "grid"
          :grid-template-columns "1.5fr 1fr 0.65fr 0.65fr"
          :gap "var(--s2)"
          :padding "0 var(--s1)"
          :font-size "var(--t-2)"
          :font-weight 700
          :letter-spacing "0.1em"
          :text-transform "uppercase"
          :color "var(--faded-ink)")]

   [:.adsb-roster-list
    {:list-style "none"
     :margin     0
     :padding    "0 var(--s2) var(--s3)"}]

   [:.adsb-roster-row
    (decl :display "grid"
          :grid-template-columns "1.5fr 1fr 0.65fr 0.65fr"
          :gap "var(--s2)"
          :align-items "baseline"
          :width "100%"
          :padding "var(--s3) var(--s2)"
          :background "transparent"
          :border "none"
          :border-bottom "1px solid var(--rule-faint)"
          :font "inherit"
          :font-size "var(--t-1)"
          :color "var(--ink)"
          :cursor "pointer"
          :text-align "left"
          :box-sizing "border-box")]

   [:.adsb-roster-row:hover
    {:background "rgba(168, 58, 99, 0.06)"}]

   [".adsb-roster-row.is-selected"
    {:background "rgba(168, 58, 99, 0.1)"
     :box-shadow "inset 3px 0 0 var(--magenta)"}]

   [".adsb-roster-row.is-hovered"
    {:background "rgba(168, 58, 99, 0.08)"}]

   [".adsb-roster-row.is-emergency"
    {:color "var(--emergency)"}]

   [:.adsb-roster-name
    {:font-weight   700
     :overflow      "hidden"
     :text-overflow "ellipsis"
     :white-space   "nowrap"}]

   [:.adsb-roster-alt
    {:font-variant-numeric "tabular-nums"}]

   [:.adsb-roster-spd
    {:font-variant-numeric "tabular-nums"
     :color                "var(--faded-ink)"}]

   [:.adsb-roster-sq
    {:font-variant-numeric "tabular-nums"
     :color                "var(--faded-ink)"}]

   [:.adsb-roster-empty
    {:padding    "var(--s5) var(--s4)"
     :font-size  "var(--t-1)"
     :color      "var(--faded-ink)"
     :text-align "center"}]

   [:.maplibregl-ctrl-bottom-right
    {:right  "calc(var(--roster-w) + var(--safe-right))"
     :bottom "var(--safe-bottom)"}]

   [".adsb-shell:has(.adsb-roster:not(.is-open)) .maplibregl-ctrl-bottom-right"
    {:right "calc(var(--roster-rail-w) + var(--safe-right))"}]

   [".adsb-shell:has(.adsb-roster:not(.is-open)) .adsb-follow-control"
    {:right "calc(var(--roster-rail-w) + var(--safe-right) + var(--maplibre-ctrl-margin))"}]])

;; The phone bottom-sheet rules live in adsb.css.phone alongside the rest of
;; the ≤640px chrome. This namespace keeps only the desktop dock.
(def styles [dock])
