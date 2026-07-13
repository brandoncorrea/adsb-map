(ns adsb.css.panel
  "The detail panel — the index card (§4) — with its badges, and the
  selection mark that answers it on the chart.

  Chart marginalia for one selected aircraft: a paper card under the header,
  clear of the Stack — ink rule under the title, caption-voice fact labels,
  mono data, em-dashes for facts the sky never reported. It settles in (§6);
  it never crowds the plot."
  (:require
    [adsb.css.decl :refer [decl]]))

(def card
  [[:.adsb-panel
    (decl :position      "absolute"
          :top           "calc(var(--header-h) + var(--s3))"
          :right         "calc(var(--stack-w) + var(--s3))"
          :z-index       2
          :width         "288px"
          :background    "var(--paper-veil)"
          :border        "1px solid var(--rule-strong)"
          :border-radius "2px"
          :box-shadow    "2px 2px 0 var(--rule-faint)"
          :color         "var(--ink)"
          :box-sizing    "border-box"
          :animation     "adsb-settle 180ms ease-out")]

   [:.adsb-panel-header
    (decl :display         "flex"
          :align-items     "baseline"
          :justify-content "space-between"
          :gap             "var(--s2)"
          :padding         "var(--s3) var(--s3) var(--s2)"
          :border-bottom   "1px solid var(--ink)")]

   [:.adsb-panel-title
    (decl :font-size      "var(--t1)"
          :font-weight    700
          :letter-spacing "0.04em"
          ;; a hostile callsign must not shear the card
          :overflow-wrap  "anywhere")]

   ;; ORDER: `font: inherit` resets font-size; the font-size below it is the
   ;; one that must survive. See adsb.css.decl.
   [:.adsb-panel-close
    (decl :background  "none"
          :border      "none"
          :padding     "0 var(--s1)"
          :color       "var(--faded-ink)"
          :font        "inherit"
          :font-size   "var(--t1)"
          :line-height 1
          :cursor      "pointer")]

   [:.adsb-panel-close:hover
    (decl :color "var(--ink)")]

   [:.adsb-panel-close:focus-visible
    (decl :outline        "2px solid var(--magenta)"
          :outline-offset "1px")]

   [:.adsb-panel-badges
    (decl :display   "flex"
          :flex-wrap "wrap"
          :gap       "var(--s2)"
          :padding   "var(--s2) var(--s3) 0")]

   [:.adsb-panel-facts
    (decl :padding "var(--s2) var(--s3) var(--s3)")]

   [:.adsb-fact
    (decl :display         "flex"
          :align-items     "baseline"
          :justify-content "space-between"
          :gap             "var(--s3)"
          :padding         "var(--s1) 0"
          :border-bottom   "1px dotted var(--rule-faint)")]

   [:.adsb-fact:last-child
    (decl :border-bottom "none")]

   ;; voice: adsb.css.captions
   [:.adsb-fact-label
    (decl :color "var(--faded-ink)")]

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
  "The selection mark — the compass-pencil ring (§4).

  A dashed magenta ring around the selected aircraft, drawn as a map marker
  (adsb.map.selection) so it rides the chart, not the screen. It draws itself
  in once per selection and then holds still."
  [[:.adsb-selection-ring
    (decl :width          "44px"
          :height         "44px"
          ;; the plane beneath stays clickable
          :pointer-events "none")]

   [".adsb-selection-ring svg"
    (decl :display  "block"
          :width    "100%"
          :height   "100%"
          :overflow "visible")]

   [".adsb-selection-ring circle"
    (decl :fill             "none"
          :stroke           "var(--magenta)"
          :stroke-width     "1.6px"
          :stroke-linecap   "round"
          ;; of pathLength 70 — ten pencil dashes
          :stroke-dasharray "3.2 3.8"
          :animation        "adsb-ring-draw 420ms ease-out")]])

(def styles
  [card badges selection-ring])
