(ns adsb.css.roster
  (:require [adsb.css.decl :refer [decl]]
            [garden.stylesheet :refer [at-media]]))

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
    {:width "40px"}]

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
    {:position    "absolute"
     :width       "1px"
     :height      "1px"
     :margin      "-1px"
     :padding     0
     :overflow    "hidden"
     :clip-path   "inset(50%)"
     :white-space "nowrap"
     :border      0}]

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

   [".adsb-roster:not(.is-open) ~ .adsb-map .maplibregl-ctrl-bottom-right,
     .adsb-shell:has(.adsb-roster:not(.is-open)) .maplibregl-ctrl-bottom-right"
    {:right "calc(40px + var(--safe-right))"}]

   [".adsb-roster:not(.is-open) ~ .adsb-follow-control,
     .adsb-shell:has(.adsb-roster:not(.is-open)) .adsb-follow-control"
    {:right "calc(40px + var(--safe-right) + 10px)"}]])

(def phone
  (at-media {:max-width "640px"}
    [[":root"
      {:--roster-sheet-h "52dvh"
       :--roster-full-h  "min(calc(92dvh + var(--safe-bottom)), calc(100dvh - var(--safe-top)))"
       :--roster-rail-h  "48px"}]

     [:.adsb-roster
      {:top            "auto"
       :left           "var(--safe-left)"
       :right          "var(--safe-right)"
       :bottom         0
       :width          "auto"
       :height         "calc(var(--roster-sheet-h) + var(--safe-bottom))"
       :max-height     "calc(var(--roster-sheet-h) + var(--safe-bottom))"
       :border-left    "none"
       :border-top     "1px solid var(--rule)"
       :border-radius  "10px 10px 0 0"
       :box-shadow     "0 -6px 20px var(--rule-faint)"
       :transition     "height 400ms cubic-bezier(0.22, 1, 0.36, 1), max-height 400ms cubic-bezier(0.22, 1, 0.36, 1)"
       :z-index        4
       :padding-bottom "var(--safe-bottom)"
       :box-sizing     "border-box"}]

     [".adsb-roster.is-sheet-closed"
      {:height     "calc(var(--roster-rail-h) + var(--safe-bottom))"
       :max-height "calc(var(--roster-rail-h) + var(--safe-bottom))"
       :width      "auto"}]

     [".adsb-roster.is-sheet-half"
      {:height     "calc(52dvh + var(--safe-bottom))"
       :max-height "calc(52dvh + var(--safe-bottom))"}]

     [".adsb-roster.is-sheet-full"
      {:height     "var(--roster-full-h)"
       :max-height "var(--roster-full-h)"}]

     [".adsb-roster.is-dragging,
       .adsb-roster.is-settling"
      {:transition "none"}]

     [".adsb-roster.is-open"
      {:padding-bottom 0}]

     [".adsb-roster.is-open .adsb-roster-body"
      {:padding-bottom "var(--safe-bottom)"}]

     [".adsb-roster:not(.is-open)"
      {:width      "auto"
       :height     "calc(var(--roster-rail-h) + var(--safe-bottom))"
       :max-height "calc(var(--roster-rail-h) + var(--safe-bottom))"}]

     [:.adsb-roster-rail
      {:position        "relative"
       :flex-direction  "row"
       :align-items     "center"
       :justify-content "center"
       :border-bottom   "none"
       :padding         "var(--s2) 36px var(--s3) var(--s3)"
       :flex            "none"
       :min-height      "var(--roster-rail-h)"
       :box-sizing      "border-box"
       :cursor          "grab"}]

     [:.adsb-roster-handle
      {:flex-direction  "column"
       :align-items     "center"
       :justify-content "center"
       :gap             "var(--s1)"
       :width           "auto"
       :flex            1
       :padding         "var(--s2) var(--s2) var(--s1)"
       :min-height      0
       :cursor          "grab"}]

     [".adsb-roster.is-dragging .adsb-roster-rail,
       .adsb-roster.is-dragging .adsb-roster-handle"
      {:cursor "grabbing"}]

     [:.adsb-roster-handle-bar
      {:width  "36px"
       :height "3px"}]

     [".adsb-roster:not(.is-open) .adsb-roster-handle-bar"
      {:display "block"
       :width   "36px"
       :height  "3px"}]

     [:.adsb-roster-handle-label
      {:padding-bottom "1px"}]

     [:.adsb-roster-search-input
      {:font-size "16px"}]

     [".adsb-roster-search-field .adsb-icon"
      {:font-size "16px"}]

     [".adsb-roster:not(.is-open) .adsb-roster-rail"
      {:flex            "none"
       :align-items     "center"
       :justify-content "center"
       :gap             0
       :border-bottom   "none"
       :padding         "var(--s2) 36px var(--s3) var(--s3)"}]

     [".adsb-roster:not(.is-open) .adsb-roster-handle"
      {:flex-direction  "column"
       :align-items     "center"
       :justify-content "center"
       :gap             "var(--s1)"
       :width           "auto"
       :flex            1
       :min-height      0
       :height          "auto"
       :padding         "var(--s2) var(--s2) var(--s1)"}]

     [".adsb-roster:not(.is-open) .adsb-roster-handle-label"
      {:writing-mode   "horizontal-tb"
       :transform      "none"
       :letter-spacing "inherit"}]

     [".adsb-roster .adsb-health,
       .adsb-roster:not(.is-open) .adsb-health"
      {:position        "absolute"
       :top             "var(--s3)"
       :right           "var(--s3)"
       :transform       "none"
       :order           "initial"
       :justify-content "center"}]

     [".adsb-roster .adsb-conn-reconnecting,
       .adsb-roster .adsb-feeder-silent,
       .adsb-roster .adsb-feeder-starting"
      {:background    "transparent"
       :border-radius "50%"
       :padding       "2px"}]

     [:.maplibregl-ctrl-bottom-right
      {:right      "var(--safe-right)"
       :bottom     "calc(var(--roster-sheet-h) + var(--safe-bottom))"
       :z-index    1
       :transition "bottom 400ms cubic-bezier(0.22, 1, 0.36, 1)"}]

     [:.adsb-follow-control
      {:right      "calc(var(--safe-right) + 10px)"
       :bottom     "calc(var(--roster-sheet-h) + var(--safe-bottom) + 10px + 34px)"
       :transition "bottom 400ms cubic-bezier(0.22, 1, 0.36, 1)"}]

     [".adsb-shell:has(.adsb-roster) .maplibregl-ctrl-bottom-right"
      {:right "var(--safe-right)"}]

     [".adsb-shell:has(.adsb-roster) .adsb-follow-control"
      {:right "calc(var(--safe-right) + 10px)"}]

     [".adsb-shell:has(.adsb-roster.is-sheet-closed) .maplibregl-ctrl-bottom-right,
       .adsb-shell:has(.adsb-roster:not(.is-open)) .maplibregl-ctrl-bottom-right"
      {:right  "var(--safe-right)"
       :bottom "calc(var(--roster-rail-h) + var(--safe-bottom))"}]

     [".adsb-shell:has(.adsb-roster.is-sheet-closed) .adsb-follow-control,
       .adsb-shell:has(.adsb-roster:not(.is-open)) .adsb-follow-control"
      {:right  "calc(var(--safe-right) + 10px)"
       :bottom "calc(var(--roster-rail-h) + var(--safe-bottom) + 10px + 34px)"}]

     [".adsb-shell:has(.adsb-roster.is-sheet-half) .maplibregl-ctrl-bottom-right"
      {:bottom "calc(52dvh + var(--safe-bottom))"}]

     [".adsb-shell:has(.adsb-roster.is-sheet-half) .adsb-follow-control"
      {:bottom "calc(52dvh + var(--safe-bottom) + 10px + 34px)"}]

     [".adsb-shell:has(.adsb-roster.is-sheet-full) .maplibregl-ctrl-bottom-right"
      {:bottom "var(--roster-full-h)"}]

     [".adsb-shell:has(.adsb-roster.is-sheet-full) .adsb-follow-control"
      {:bottom "calc(var(--roster-full-h) + 10px + 34px)"}]]))

(def styles [dock phone])
