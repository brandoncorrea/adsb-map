(ns adsb.css.panel
  (:require [adsb.css.card :as card]
            [adsb.css.decl :refer [decl]]))

(def card
  [[:.adsb-panel
    card/face
    {:position "absolute"
     :top      "calc(var(--s3) + var(--safe-top))"
     :left     "calc(var(--s3) + var(--safe-left))"
     :z-index  2
     :width    "288px"}]

   [".adsb-panel.is-collapsed"
    {:width      "auto"
     :min-width  "0"
     :max-width  "min(280px, calc(100% - 24px))"
     :box-shadow "1px 1px 0 var(--rule-faint), 0 4px 12px var(--rule-faint)"}]

   [:.adsb-panel-header
    card/head
    {:align-items    "center"
     :padding-top    "var(--s3)"
     :padding-bottom "var(--s3)"}]

   [".adsb-panel.is-collapsed .adsb-panel-header"
    {:border-bottom "none"
     :padding       "var(--s3) var(--s3)"}]

   [:.adsb-panel-toggle
    {:display     "flex"
     :align-items "center"
     :gap         "var(--s2)"
     :flex        1
     :min-width   0
     :background  "none"
     :border      "none"
     :padding     0
     :margin      0
     :font        "inherit"
     :color       "inherit"
     :cursor      "pointer"
     :text-align  "left"
     :line-height 1.2}]

   [".adsb-panel-toggle:hover .adsb-panel-title"
    {:color "var(--magenta)"}]

   [:.adsb-panel-chevron
    {:flex        "none"
     :width       "12px"
     :font-size   "var(--t-1)"
     :color       "var(--faded-ink)"
     :line-height 1}]

   [:.adsb-panel-title card/title]

   [".adsb-panel.is-collapsed .adsb-panel-title"
    {:font-size "var(--t0)"}]

   [:.adsb-panel-chip-meta
    {:flex                 "none"
     :font-size            "var(--t-1)"
     :font-weight          700
     :font-variant-numeric "tabular-nums"
     :color                "var(--faded-ink)"}]

   [:.adsb-panel-close
    card/close
    {:padding "0 var(--s1)" :flex "none"}]

   [:.adsb-panel-close:hover
    {:color "var(--ink)"}]

   [:.adsb-panel-close:focus-visible
    {:outline        "2px solid var(--magenta)"
     :outline-offset "1px"}]

   [".adsb-panel.is-emergency.is-collapsed"
    {:border-color "var(--emergency)"}]

   [".adsb-panel.is-emergency.is-collapsed .adsb-panel-title"
    {:color "var(--emergency)"}]

   [:.adsb-panel-badges
    {:display   "flex"
     :flex-wrap "wrap"
     :gap       "var(--s2)"
     :padding   "var(--s2) var(--s3) 0"}]

   [:.adsb-panel-facts
    {:padding "var(--s2) var(--s3) var(--s3)"}]

   [:.adsb-fact
    {:display         "flex"
     :align-items     "baseline"
     :justify-content "space-between"
     :gap             "var(--s3)"
     :padding         "var(--s1) 0"
     :border-bottom   "1px dotted var(--rule-faint)"}]

   [:.adsb-fact:last-child
    {:border-bottom "none"}]

   [:.adsb-fact-label
    {:color "var(--faded-ink)"}]

   [:.adsb-fact-value
    {:text-align           "right"
     :font-size            "var(--t-1)"
     :font-variant-numeric "tabular-nums"
     :overflow-wrap        "anywhere"}]])

(def badges
  [[:.adsb-badge
    {:display       "inline-block"
     :padding       "1px var(--s2)"
     :border-radius "2px"
     :font-size     "var(--t-1)"
     :font-weight   700
     :white-space   "nowrap"
     :background    "transparent"
     :border        "1px solid var(--rule)"
     :color         "var(--faded-ink)"}]

   [:.adsb-badge-emergency
    {:background     "var(--emergency)"
     :border-color   "var(--emergency)"
     :color          "var(--on-emergency)"
     :text-transform "uppercase"
     :letter-spacing "0.03em"}]])

(def selection-ring
  [[:.adsb-selection-ring
    {:position       "relative"
     :box-sizing     "border-box"
     :width          "44px"
     :height         "44px"
     :margin         0
     :padding        0
     :overflow       "visible"
     :pointer-events "none"}]

   [".adsb-selection-ring svg"
    {:display  "block"
     :width    "44px"
     :height   "44px"
     :margin   0
     :overflow "visible"}]

   [".adsb-selection-ring circle"
    (decl :fill "none"
          :stroke "var(--magenta)"
          :stroke-width "1.6px"
          :stroke-linecap "round"
          :stroke-dasharray "3.2 3.8"
          :animation "adsb-ring-draw 420ms ease-out")]

   [:.adsb-hover-pin
    {:position       "relative"
     :box-sizing     "border-box"
     :width          "0"
     :height         "0"
     :overflow       "visible"
     :pointer-events "none"}]

   [:.adsb-flight-label
    (decl :position "absolute"
          :left "50%"
          :top "100%"
          :margin-top "6px"
          :transform "translateX(-50%)"
          :white-space "nowrap"
          :background "var(--paper-chrome)"
          :border "1px solid var(--rule)"
          :padding "1px 6px"
          :font-size "var(--t-1)"
          :font-weight 700
          :letter-spacing "0.03em"
          :color "var(--ink)"
          :pointer-events "none"
          :box-shadow "1px 1px 0 var(--rule-faint)"
          :z-index 1)]

   [".adsb-hover-pin .adsb-flight-label"
    {:top        "10px"
     :margin-top 0}]])

(def styles [card badges selection-ring])
