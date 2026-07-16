(ns adsb.css.phone
  (:require [garden.stylesheet :refer [at-media]]))

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
      :width "auto"}]))
