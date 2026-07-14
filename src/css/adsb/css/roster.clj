(ns adsb.css.roster
  "THE ROSTER (adsb-66h) — Search + Sheet, the product chrome that replaced
  the Stack.

  Desktop: full-height side dock on the right edge. Phone: bottom pull-up
  drawer with three snap points (closed / half / full) and a drag handle
  (adsb-xgg). Same DOM; stance rearranges the furniture. The find field
  filters the ranked list in place.

  This namespace is a BOUNDED SECTION: it owns the .adsb-roster-* rules and
  the map attribution's clearance from the dock. Phone geometry lives at
  the bottom of this file rather than in adsb.css.phone, for the same
  reason the Stack's did."
  (:require
    [adsb.css.decl :refer [decl]]
    [garden.stylesheet :refer [at-media]]))

(def dock
  "Desktop default: side dock."
  [[:.adsb-roster
    (decl :position       "absolute"
          :top            "var(--safe-top)"
          :right          "var(--safe-right)"
          :bottom         "var(--safe-bottom)"
          :z-index        2
          :display        "flex"
          :flex-direction "column"
          :width          "var(--roster-w)"
          :background     "var(--paper-veil)"
          :border-left    "1px solid var(--rule)"
          :color          "var(--ink)"
          :box-sizing     "border-box"
          :transition     "width 180ms ease-out"
          :overflow       "hidden")]

   [".adsb-roster:not(.is-open)"
    (decl :width "40px")]

   [:.adsb-roster-rail
    (decl :position        "relative"
          :display         "flex"
          :flex-direction  "column"
          :align-items     "stretch"
          :flex            "none"
          :border-bottom   "1px solid var(--rule-faint)"
          ;; Right padding leaves room for the health pin (top-right).
          :padding         "var(--s3) 36px var(--s3) var(--s2)"
          :box-sizing      "border-box")]

   [:.adsb-roster-handle
    (decl :display         "flex"
          :flex-direction  "row"
          :align-items     "center"
          :justify-content "flex-start"
          :gap             "var(--s3)"
          :width           "100%"
          :padding         "var(--s2) var(--s2)"
          :background      "transparent"
          :border          "none"
          :font            "inherit"
          :font-size       "var(--t-1)"
          :font-weight     700
          :letter-spacing  "0.06em"
          :color           "var(--faded-ink)"
          :cursor          "pointer"
          :text-align      "left"
          :box-sizing      "border-box"
          :touch-action    "none"
          :user-select     "none")]

   [:.adsb-roster-handle-bar
    (decl :width         "3px"
          :height        "28px"
          :border-radius "2px"
          :flex          "none"
          :background    "var(--rule)")]

   [:.adsb-roster-handle-label
    (decl :white-space   "nowrap"
          :overflow      "hidden"
          :text-overflow "ellipsis")]

   ;; Collapsed rail: vertical label. Health sits ABOVE the label in
   ;; normal flow (not absolute) so the two never share a pixel —
   ;; absolute top-center used to paint the pin on the vertical text.
   [".adsb-roster:not(.is-open) .adsb-roster-rail"
    (decl :flex            1
          :border-bottom   "none"
          :padding         "var(--s3) var(--s1)"
          :align-items     "center"
          :gap             "var(--s2)")]

   [".adsb-roster:not(.is-open) .adsb-roster-handle"
    (decl :flex-direction "column"
          :align-items    "center"
          :gap            "var(--s3)"
          :padding        "var(--s2) 0"
          :height         "auto"
          :flex           1
          :min-height     0
          :width          "100%")]

   ;; Grip bar is a phone drag affordance. On desktop collapsed it only
   ;; steals height from the vertical label — hide it; the label reopens.
   [".adsb-roster:not(.is-open) .adsb-roster-handle-bar"
    (decl :display "none")]

   [".adsb-roster:not(.is-open) .adsb-roster-handle-label"
    (decl :writing-mode   "vertical-rl"
          :transform      "rotate(180deg)"
          :letter-spacing "0.1em")]

   ;; Health: pin to the rail's top-right when the dock is OPEN. Collapsed
   ;; desktop re-flows it into the column above the label (below).
   [".adsb-roster .adsb-health"
    (decl :position        "absolute"
          :top             "var(--s3)"
          :right           "var(--s3)"
          :margin          0
          :padding         0
          :justify-content "center"
          :align-items     "center"
          :gap             "var(--s2)"
          :z-index         1)]

   ;; Printed labels stay in the a11y tree; the pin is dots only.
   [".adsb-roster .adsb-conn-label,
     .adsb-roster .adsb-feeder-label"
    (decl :position    "absolute"
          :width       "1px"
          :height      "1px"
          :margin      "-1px"
          :padding     0
          :overflow    "hidden"
          :clip-path   "inset(50%)"
          :white-space "nowrap"
          :border      0)]

   [".adsb-roster .adsb-conn,
     .adsb-roster .adsb-feeder"
    (decl :padding      0
          :border-color "transparent"
          :background   "transparent")]

   [".adsb-roster .adsb-conn-dot,
     .adsb-roster .adsb-feeder-dot"
    (decl :width  "9px"
          :height "9px")]

   [".adsb-roster .adsb-conn-down,
     .adsb-roster .adsb-feeder-down"
    (decl :background    "var(--emergency)"
          :border-radius "50%"
          :padding       "2px")]

   [".adsb-roster:not(.is-open) .adsb-health"
    ;; In-flow, first in the column (DOM is handle then health — order
    ;; pulls the pin above the vertical label).
    (decl :position        "static"
          :top             "auto"
          :right           "auto"
          :transform       "none"
          :order           -1
          :flex            "none"
          :justify-content "center"
          :margin          0
          :padding         0
          :z-index         "auto")]

   [:.adsb-roster-body
    (decl :flex            1
          :min-height      0
          :min-width       0
          :overflow        "auto"
          :display         "flex"
          :flex-direction  "column")]

   [:.adsb-roster-toolbar
    (decl :padding         "var(--s3) var(--s4) var(--s3)"
          :display         "flex"
          :flex-direction  "column"
          :gap             "var(--s3)"
          :flex            "none"
          :border-bottom   "1px solid var(--rule-faint)"
          :background      "var(--paper-chrome)")]

   [:.adsb-roster-search
    (decl :display "flex" :flex-direction "column" :gap "var(--s1)")]

   [:.adsb-roster-search-label
    (decl :font-size      "var(--t-2)"
          :font-weight    700
          :letter-spacing "0.12em"
          :text-transform "uppercase"
          :color          "var(--faded-ink)")]

   [:.adsb-roster-search-input
    (decl :width           "100%"
          :box-sizing      "border-box"
          :padding         "var(--s3) var(--s3)"
          :font            "inherit"
          :font-size       "var(--t0)"
          :font-weight     700
          :background      "var(--paper-chrome)"
          :color           "var(--ink)"
          :border          "1px solid var(--rule)"
          :border-radius   "2px"
          :outline         "none")]

   [:.adsb-roster-search-input:focus
    (decl :border-color "var(--magenta)"
          :box-shadow   "0 0 0 2px rgba(168, 58, 99, 0.15)")]

   [:.adsb-roster-key
    (decl :padding       "var(--s2) var(--s2)"
          :background    "var(--paper-veil)"
          :border        "1px solid var(--rule-faint)"
          :border-radius "2px")]

   [:.adsb-roster-key-ramp
    (decl :height        "8px"
          :border-radius "2px"
          :border        "1px solid var(--rule-faint)"
          :margin-bottom "var(--s2)")]

   [:.adsb-roster-key-labels
    (decl :display         "flex"
          :justify-content "space-between"
          :font-size       "var(--t-2)"
          :font-weight     700
          :letter-spacing  "0.04em"
          :color           "var(--faded-ink)")]

   [:.adsb-roster-cols
    (decl :display               "grid"
          :grid-template-columns "1.5fr 1fr 0.65fr 0.65fr"
          :gap                   "var(--s2)"
          :padding               "0 var(--s1)"
          :font-size             "var(--t-2)"
          :font-weight           700
          :letter-spacing        "0.1em"
          :text-transform        "uppercase"
          :color                 "var(--faded-ink)")]

   [:.adsb-roster-list
    (decl :list-style "none" :margin 0 :padding "0 var(--s2) var(--s3)")]

   [:.adsb-roster-row
    (decl :display               "grid"
          :grid-template-columns "1.5fr 1fr 0.65fr 0.65fr"
          :gap                   "var(--s2)"
          :align-items           "baseline"
          :width                 "100%"
          :padding               "var(--s3) var(--s2)"
          :background            "transparent"
          :border                "none"
          :border-bottom         "1px solid var(--rule-faint)"
          :font                  "inherit"
          :font-size             "var(--t-1)"
          :color                 "var(--ink)"
          :cursor                "pointer"
          :text-align            "left"
          :box-sizing            "border-box")]

   [:.adsb-roster-row:hover
    (decl :background "rgba(168, 58, 99, 0.06)")]

   [".adsb-roster-row.is-selected"
    (decl :background "rgba(168, 58, 99, 0.1)"
          :box-shadow "inset 3px 0 0 var(--magenta)")]

   [".adsb-roster-row.is-hovered"
    (decl :background "rgba(168, 58, 99, 0.08)")]

   [".adsb-roster-row.is-emergency"
    (decl :color "var(--emergency)")]

   [:.adsb-roster-name
    (decl :font-weight   700
          :overflow      "hidden"
          :text-overflow "ellipsis"
          :white-space   "nowrap")]

   [:.adsb-roster-alt
    (decl :font-variant-numeric "tabular-nums")]

   [:.adsb-roster-spd
    (decl :font-variant-numeric "tabular-nums"
          :color                "var(--faded-ink)")]

   [:.adsb-roster-sq
    (decl :font-variant-numeric "tabular-nums"
          :color                "var(--faded-ink)")]

   [:.adsb-roster-empty
    (decl :padding    "var(--s5) var(--s4)"
          :font-size  "var(--t-1)"
          :color      "var(--faded-ink)"
          :text-align "center")]

   ;; Attribution clears the dock. z-index of the roster is above the map
   ;; chrome so the (i) never paints on top of the sheet; the control still
   ;; shifts clear of the dock so it stays reachable (OSMF terms).
   [:.maplibregl-ctrl-bottom-right
    (decl :right  "calc(var(--roster-w) + var(--safe-right))"
          :bottom "var(--safe-bottom)")]

   [".adsb-roster:not(.is-open) ~ .adsb-map .maplibregl-ctrl-bottom-right,
     .adsb-shell:has(.adsb-roster:not(.is-open)) .maplibregl-ctrl-bottom-right"
    (decl :right "calc(40px + var(--safe-right))")]

   ;; Follow reticle rides the same corner as the (i) — open dock width,
   ;; collapsed rail width (adsb.ui.follow, adsb-jg4).
   [".adsb-roster:not(.is-open) ~ .adsb-follow-control,
     .adsb-shell:has(.adsb-roster:not(.is-open)) .adsb-follow-control"
    (decl :right "calc(40px + var(--safe-right) + 10px)")]])

(def phone
  "Phone: bottom pull-up with three snaps (closed / half / full).
  Attribution lifts above the sheet via CSS variables set per snap so the
  (i) never sits under the drawer (OSMF: credit must stay reachable)."
  (at-media {:max-width "640px"}
    [[":root"
      ;; Default open height matches half snap; JS data-sheet overrides via
      ;; the .is-sheet-* classes below for closed / full. Rail is a hair
      ;; taller than 44 so the handle label keeps air above the screen edge
      ;; (adsb-rsm).
      (decl :--roster-sheet-h "52vh"
            :--roster-rail-h  "48px")]

     [:.adsb-roster
      (decl :top            "auto"
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
            ;; Tap-to-cycle still uses CSS height transition. Drag settle
            ;; is rAF-driven (adsb.ui.roster) and opts out via .is-settling
            ;; — CSS height expand is unreliable in WebKit.
            :transition     "height 400ms cubic-bezier(0.22, 1, 0.36, 1), max-height 400ms cubic-bezier(0.22, 1, 0.36, 1)"
            :z-index        4
            ;; A sliver of chart shows past a full sheet — it is still a
            ;; drawer, not a modal page. Content clears the home indicator;
            ;; the paper itself runs under it so the edge is continuous.
            :padding-bottom "var(--safe-bottom)"
            :box-sizing     "border-box")]

     [".adsb-roster.is-sheet-closed"
      (decl :height     "calc(var(--roster-rail-h) + var(--safe-bottom))"
            :max-height "calc(var(--roster-rail-h) + var(--safe-bottom))"
            :width      "auto")]

     [".adsb-roster.is-sheet-half"
      (decl :height     "calc(52vh + var(--safe-bottom))"
            :max-height "calc(52vh + var(--safe-bottom))")]

     [".adsb-roster.is-sheet-full"
      ;; Mostly full: a band of chart remains above so it reads as a drawer.
      (decl :height     "calc(92vh + var(--safe-bottom))"
            :max-height "calc(92vh + var(--safe-bottom))")]

     ;; Finger tracking and rAF settle must not fight a CSS transition.
     [".adsb-roster.is-dragging,
       .adsb-roster.is-settling"
      (decl :transition "none")]

     [".adsb-roster:not(.is-open)"
      (decl :width "auto"
            :height "calc(var(--roster-rail-h) + var(--safe-bottom))"
            :max-height "calc(var(--roster-rail-h) + var(--safe-bottom))")]

     [:.adsb-roster-rail
      (decl :position        "relative"
            :flex-direction  "row"
            :align-items     "center"
            :justify-content "center"
            :border-bottom   "none"
            ;; Extra bottom air so the handle label is not hard against the
            ;; screen edge (adsb-rsm). Right padding leaves room for the
            ;; health pin in the top-right corner.
            :padding         "var(--s2) 36px var(--s3) var(--s3)"
            :flex            "none"
            :min-height      "var(--roster-rail-h)"
            :box-sizing      "border-box")]

     [:.adsb-roster-handle
      (decl :flex-direction  "column"
            :align-items     "center"
            :justify-content "center"
            :gap             "var(--s1)"
            :width           "auto"
            :flex            1
            :padding         "var(--s2) var(--s2) var(--s1)"
            :min-height      0
            :cursor          "grab")]

     [".adsb-roster.is-dragging .adsb-roster-handle"
      (decl :cursor "grabbing")]

     [:.adsb-roster-handle-bar
      (decl :width "36px" :height "3px")]

     ;; Phone always wants the grip — override the desktop collapsed hide.
     [".adsb-roster:not(.is-open) .adsb-roster-handle-bar"
      (decl :display "block" :width "36px" :height "3px")]

     [:.adsb-roster-handle-label
      (decl :padding-bottom "1px")]

     [".adsb-roster:not(.is-open) .adsb-roster-rail"
      (decl :flex "none")]

     [".adsb-roster:not(.is-open) .adsb-roster-handle"
      (decl :flex-direction "column" :height "auto")]

     [".adsb-roster:not(.is-open) .adsb-roster-handle-label"
      (decl :writing-mode "horizontal-tb" :transform "none")]

     ;; Phone always pins health top-right (absolute), including when
     ;; the sheet is closed. Desktop collapsed uses in-flow order:-1;
     ;; reassert the pin so phone does not inherit that stack.
     [".adsb-roster .adsb-health,
       .adsb-roster:not(.is-open) .adsb-health"
      (decl :position        "absolute"
            :top             "var(--s3)"
            :right           "var(--s3)"
            :transform       "none"
            :order           "initial"
            :justify-content "center")]

     [".adsb-roster .adsb-conn-reconnecting,
       .adsb-roster .adsb-feeder-silent,
       .adsb-roster .adsb-feeder-starting"
      (decl :background    "transparent"
            :border-radius "50%"
            :padding       "2px")]

     ;; Attribution clears the live sheet height. The (i) stays above the
     ;; drawer at every snap — closed rail, half, full — so the OSMF credit
     ;; remains reachable without painting on top of the sheet.
     [:.maplibregl-ctrl-bottom-right
      (decl :right  "var(--safe-right)"
            :bottom "calc(var(--roster-sheet-h) + var(--safe-bottom))"
            :z-index 1
            :transition "bottom 400ms cubic-bezier(0.22, 1, 0.36, 1)")]

     ;; Follow reticle: same right edge as the (i), bottom is attribution
     ;; bottom + control stack (margin 10 + (i) 29 + gap ~5).
     [:.adsb-follow-control
      (decl :right  "calc(var(--safe-right) + 10px)"
            :bottom "calc(var(--roster-sheet-h) + var(--safe-bottom) + 10px + 34px)"
            :transition "bottom 400ms cubic-bezier(0.22, 1, 0.36, 1)")]

     ;; Desktop dock clearance sets right:40px when the roster is not
     ;; .is-open. On phone "closed" is also not .is-open, so that rule
     ;; inset the (i) from the right edge — reassert safe-right for every
     ;; phone snap, closed included (adsb-4ca).
     [".adsb-shell:has(.adsb-roster) .maplibregl-ctrl-bottom-right"
      (decl :right "var(--safe-right)")]

     [".adsb-shell:has(.adsb-roster) .adsb-follow-control"
      (decl :right "calc(var(--safe-right) + 10px)")]

     [".adsb-shell:has(.adsb-roster.is-sheet-closed) .maplibregl-ctrl-bottom-right,
       .adsb-shell:has(.adsb-roster:not(.is-open)) .maplibregl-ctrl-bottom-right"
      (decl :right  "var(--safe-right)"
            :bottom "calc(var(--roster-rail-h) + var(--safe-bottom))")]

     [".adsb-shell:has(.adsb-roster.is-sheet-closed) .adsb-follow-control,
       .adsb-shell:has(.adsb-roster:not(.is-open)) .adsb-follow-control"
      (decl :right  "calc(var(--safe-right) + 10px)"
            :bottom "calc(var(--roster-rail-h) + var(--safe-bottom) + 10px + 34px)")]

     [".adsb-shell:has(.adsb-roster.is-sheet-half) .maplibregl-ctrl-bottom-right"
      (decl :bottom "calc(52vh + var(--safe-bottom))")]

     [".adsb-shell:has(.adsb-roster.is-sheet-half) .adsb-follow-control"
      (decl :bottom "calc(52vh + var(--safe-bottom) + 10px + 34px)")]

     [".adsb-shell:has(.adsb-roster.is-sheet-full) .maplibregl-ctrl-bottom-right"
      (decl :bottom "calc(92vh + var(--safe-bottom))")]

     [".adsb-shell:has(.adsb-roster.is-sheet-full) .adsb-follow-control"
      (decl :bottom "calc(92vh + var(--safe-bottom) + 10px + 34px)")]]))

(def styles
  [dock phone])
