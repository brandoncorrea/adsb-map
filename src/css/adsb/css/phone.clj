(ns adsb.css.phone
  (:require [garden.stylesheet :refer [at-media]]))

;; Shared bits of the phone roster bottom sheet, repeated across its open and
;; collapsed states.
(def ^:private handle-bar-dims {:width "36px" :height "3px"})

(def ^:private rail-padding "var(--s2) 36px var(--s3) var(--s3)")

(def ^:private handle-stack
  {:flex-direction  "column"
   :align-items     "center"
   :justify-content "center"
   :gap             "var(--s1)"
   :width           "auto"
   :flex            1
   :min-height      0
   :padding         "var(--s2) var(--s2) var(--s1)"})

(def styles
  (at-media {:max-width "640px"}
    [":root" {:--roster-w "48px"}]

    [:.adsb-alerts {:right "var(--safe-right)"}]

    [:.adsb-panel
     {:left    "calc(var(--s3) + var(--safe-left))"
      :right   "calc(var(--s3) + var(--safe-right))"
      :width   "auto"
      :z-index 3}]

    [".adsb-panel.is-collapsed"
     {:right "auto"
      :width "auto"}]

    ;; ---- Roster bottom sheet ----------------------------------------------
    ;; The snap heights must match adsb.ui.roster-sheet/sheet-heights and
    ;; closed-rail-px — the CLJS drag math settles onto these values.
    [":root"
     {:--roster-sheet-h "52dvh"
      :--roster-full-h  "min(calc(92dvh + var(--safe-bottom)), calc(100dvh - var(--safe-top)))"
      :--roster-rail-h  "48px"
      :--sheet-ease     "400ms cubic-bezier(0.22, 1, 0.36, 1)"}]

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
      :transition     "height var(--sheet-ease), max-height var(--sheet-ease)"
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
      :padding         rail-padding
      :flex            "none"
      :min-height      "var(--roster-rail-h)"
      :box-sizing      "border-box"
      :cursor          "grab"}]

    [:.adsb-roster-handle (assoc handle-stack :cursor "grab")]

    [".adsb-roster.is-dragging .adsb-roster-rail,
      .adsb-roster.is-dragging .adsb-roster-handle"
     {:cursor "grabbing"}]

    [:.adsb-roster-handle-bar handle-bar-dims]

    [".adsb-roster:not(.is-open) .adsb-roster-handle-bar"
     (assoc handle-bar-dims :display "block")]

    [:.adsb-roster-handle-label
     {:padding-bottom "1px"}]

    ;; 16px minimum, or iOS Safari zooms the page when the input focuses.
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
      :padding         rail-padding}]

    [".adsb-roster:not(.is-open) .adsb-roster-handle"
     (assoc handle-stack :height "auto")]

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
      :transition "bottom var(--sheet-ease)"}]

    [:.adsb-follow-control
     {:right      "calc(var(--safe-right) + var(--maplibre-ctrl-margin))"
      :bottom     "calc(var(--roster-sheet-h) + var(--safe-bottom) + var(--maplibre-ctrl-margin) + var(--maplibre-attribution))"
      :transition "bottom var(--sheet-ease)"}]

    [".adsb-shell:has(.adsb-roster) .maplibregl-ctrl-bottom-right"
     {:right "var(--safe-right)"}]

    [".adsb-shell:has(.adsb-roster) .adsb-follow-control"
     {:right "calc(var(--safe-right) + var(--maplibre-ctrl-margin))"}]

    [".adsb-shell:has(.adsb-roster.is-sheet-closed) .maplibregl-ctrl-bottom-right,
      .adsb-shell:has(.adsb-roster:not(.is-open)) .maplibregl-ctrl-bottom-right"
     {:right  "var(--safe-right)"
      :bottom "calc(var(--roster-rail-h) + var(--safe-bottom))"}]

    [".adsb-shell:has(.adsb-roster.is-sheet-closed) .adsb-follow-control,
      .adsb-shell:has(.adsb-roster:not(.is-open)) .adsb-follow-control"
     {:right  "calc(var(--safe-right) + var(--maplibre-ctrl-margin))"
      :bottom "calc(var(--roster-rail-h) + var(--safe-bottom) + var(--maplibre-ctrl-margin) + var(--maplibre-attribution))"}]

    [".adsb-shell:has(.adsb-roster.is-sheet-half) .maplibregl-ctrl-bottom-right"
     {:bottom "calc(52dvh + var(--safe-bottom))"}]

    [".adsb-shell:has(.adsb-roster.is-sheet-half) .adsb-follow-control"
     {:bottom "calc(52dvh + var(--safe-bottom) + var(--maplibre-ctrl-margin) + var(--maplibre-attribution))"}]

    [".adsb-shell:has(.adsb-roster.is-sheet-full) .maplibregl-ctrl-bottom-right"
     {:bottom "var(--roster-full-h)"}]

    [".adsb-shell:has(.adsb-roster.is-sheet-full) .adsb-follow-control"
     {:bottom "calc(var(--roster-full-h) + var(--maplibre-ctrl-margin) + var(--maplibre-attribution))"}]))
