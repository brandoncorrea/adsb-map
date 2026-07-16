(ns adsb.css.captions
  (:require [adsb.css.decl :refer [decl]]))

(def styles
  [[:.adsb-fact-label
    :.adsb-mayday-label
    :.adsb-roster-cols
    :.adsb-flight-label
    (decl :font-family "\"Space Grotesk\", system-ui, sans-serif"
          :font-style "normal"
          :font-weight 500
          :text-transform "uppercase"
          :letter-spacing "0.12em"
          :font-size "var(--t-2)")]])
