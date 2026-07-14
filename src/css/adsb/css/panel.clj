(ns adsb.css.panel
  "The detail panel — the index card (§4) — with its badges, and the
  selection mark that answers it on the chart.

  Chart marginalia for one selected aircraft: a paper card under the header,
  clear of the roster dock — ink rule under the title, caption-voice fact
  labels, mono data, em-dashes for facts the sky never reported. It settles
  in (§6); it never crowds the plot.

  The card's FACE lives in adsb.css.card, shared with the drawer (adsb-l4m):
  two surfaces floating over one chart must read as two of the same thing.
  Only the geometry here is the panel's own."
  (:require [adsb.css.card :as card]
            [adsb.css.decl :refer [decl]]))

(def card
  [[:.adsb-panel
    card/face
    (decl :position "absolute"
          ;; Clear the device safe area, then the usual chrome air.
          :top      "calc(var(--s3) + var(--safe-top))"
          :left     "calc(var(--s3) + var(--safe-left))"
          :z-index  2
          :width    "288px")]

   [".adsb-panel.is-collapsed"
    (decl :width     "auto"
          :min-width "0"
          :max-width "min(280px, calc(100% - 24px))"
          :box-shadow "1px 1px 0 var(--rule-faint), 0 4px 12px var(--rule-faint)")]

   ;; The shared card header and title stamp (adsb.css.card), plus collapse.
   ;; Vertical center for chevron / title / chip / close (adsb-rsm) — the
   ;; shared head defaults to baseline for multi-line drawer titles; the
   ;; panel's single line wants the optical middle.
   [:.adsb-panel-header
    card/head
    (decl :align-items     "center"
          :padding-top     "var(--s3)"
          :padding-bottom  "var(--s3)")]

   [".adsb-panel.is-collapsed .adsb-panel-header"
    (decl :border-bottom "none"
          :padding       "var(--s3) var(--s3)")]

   [:.adsb-panel-toggle
    (decl :display     "flex"
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
          :line-height 1.2)]

   [".adsb-panel-toggle:hover .adsb-panel-title"
    (decl :color "var(--magenta)")]

   [:.adsb-panel-chevron
    (decl :flex        "none"
          :width       "12px"
          :font-size   "var(--t-1)"
          :color       "var(--faded-ink)"
          :line-height 1)]

   [:.adsb-panel-title card/title]

   [".adsb-panel.is-collapsed .adsb-panel-title"
    (decl :font-size "var(--t0)")]

   [:.adsb-panel-chip-meta
    (decl :flex                 "none"
          :font-size            "var(--t-1)"
          :font-weight          700
          :font-variant-numeric "tabular-nums"
          :color                "var(--faded-ink)")]

   ;; The shared close voice (adsb.css.card), plus this card's own geometry.
   [:.adsb-panel-close
    card/close
    (decl :padding "0 var(--s1)" :flex "none")]

   [:.adsb-panel-close:hover
    {:color "var(--ink)"}]

   [:.adsb-panel-close:focus-visible
    (decl :outline        "2px solid var(--magenta)"
          :outline-offset "1px")]

   [".adsb-panel.is-emergency.is-collapsed"
    (decl :border-color "var(--emergency)")]

   [".adsb-panel.is-emergency.is-collapsed .adsb-panel-title"
    (decl :color "var(--emergency)")]

   [:.adsb-panel-badges
    (decl :display   "flex"
          :flex-wrap "wrap"
          :gap       "var(--s2)"
          :padding   "var(--s2) var(--s3) 0")]

   [:.adsb-panel-facts
    {:padding "var(--s2) var(--s3) var(--s3)"}]

   [:.adsb-fact
    (decl :display         "flex"
          :align-items     "baseline"
          :justify-content "space-between"
          :gap             "var(--s3)"
          :padding         "var(--s1) 0"
          :border-bottom   "1px dotted var(--rule-faint)")]

   [:.adsb-fact:last-child
    {:border-bottom "none"}]

   ;; voice: adsb.css.captions
   [:.adsb-fact-label
    {:color "var(--faded-ink)"}]

   [:.adsb-fact-value
    (decl :text-align           "right"
          :font-size            "var(--t-1)"
          :font-variant-numeric "tabular-nums"
          :overflow-wrap        "anywhere")]])

(def badges
  "Status badges — small flags on rows and in the detail panel. The emergency
  badge is the only one with a committed colour, because it is the only one
  that means someone is in danger."
  [[:.adsb-badge
    (decl :display       "inline-block"
          :padding       "1px var(--s2)"
          :border-radius "2px"
          :font-size     "var(--t-1)"
          :font-weight   700
          :white-space   "nowrap"
          :background    "transparent"
          :border        "1px solid var(--rule)"
          :color         "var(--faded-ink)")]

   [:.adsb-badge-emergency
    (decl :background     "var(--emergency)"
          :border-color   "var(--emergency)"
          :color          "var(--on-emergency)"
          :text-transform "uppercase"
          :letter-spacing "0.03em")]])

(def selection-ring
  "The selection mark — the compass-pencil ring (§4) and the callsign
  label beneath selected / hovered aircraft (adsb-xgg).

  A dashed magenta ring around the selected aircraft, drawn as a map marker
  (adsb.map.selection) so it rides the chart, not the screen. It draws itself
  in once per selection and then holds still. The label is the same marker
  family: a paper chip under the plane, pointer-events none so the glyph
  stays clickable."
  [[:.adsb-selection-ring
    (decl :position        "relative"
          :box-sizing      "border-box"
          :width           "44px"
          :height          "44px"
          :margin          0
          :padding         0
          :overflow        "visible"
          ;; the plane beneath stays clickable
          :pointer-events  "none")]

   [".adsb-selection-ring svg"
    (decl :display  "block"
          :width    "44px"
          :height   "44px"
          :margin   0
          :overflow "visible")]

   [".adsb-selection-ring circle"
    (decl :fill             "none"
          :stroke           "var(--magenta)"
          :stroke-width     "1.6px"
          :stroke-linecap   "round"
          ;; of pathLength 70 — ten pencil dashes
          :stroke-dasharray "3.2 3.8"
          :animation        "adsb-ring-draw 420ms ease-out")]

   ;; Hover-only pin: no ring, just the label centred on the aircraft.
   ;; 0×0 box so MapLibre's centre anchor is exactly the lat/lon.
   [:.adsb-hover-pin
    (decl :position       "relative"
          :box-sizing     "border-box"
          :width          "0"
          :height         "0"
          :overflow       "visible"
          :pointer-events "none")]

   ;; Callsign chip under selected / hovered planes. Absolutely positioned
   ;; so it never grows the marker box (a taller box would lift the ring
   ;; off the plane under MapLibre's centre-anchor transform — adsb-rg1).
   [:.adsb-flight-label
    (decl :position       "absolute"
          :left           "50%"
          :top            "100%"
          :margin-top     "6px"
          :transform      "translateX(-50%)"
          :white-space    "nowrap"
          :background     "var(--paper-chrome)"
          :border         "1px solid var(--rule)"
          :padding        "1px 6px"
          :font-size      "var(--t-1)"
          :font-weight    700
          :letter-spacing "0.03em"
          :color          "var(--ink)"
          :pointer-events "none"
          :box-shadow     "1px 1px 0 var(--rule-faint)"
          :z-index        1)]

   [".adsb-hover-pin .adsb-flight-label"
    (decl :top        "10px"
          :margin-top 0)]])

(def styles
  [card badges selection-ring])
