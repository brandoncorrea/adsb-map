(ns adsb.css.captions
  "The printed caption voice (§5 captions, bead adsb-fon).

  One rule, one voice: every small label on the chart prints in Space Grotesk
  — Space Mono's proportional sibling — upright, uppercase, tracked, at --t-2.
  The blocks that own these labels keep only their own colour and geometry;
  the VOICE lives here and nowhere else.

  ORDER-CRITICAL: this rule must be emitted AFTER every namespace whose
  selectors it names (equal specificity, later wins). adsb.css.app is where
  that order is kept."
  (:require [adsb.css.decl :refer [decl]]))

(def styles
  [[:.adsb-count-unit
    :.adsb-stats-label
    :.adsb-fact-label
    :.adsb-mayday-label
    :.adsb-stack-shelf-label
    (decl :font-family    "\"Space Grotesk\", system-ui, sans-serif"
          :font-style     "normal"
          :font-weight    500
          :text-transform "uppercase"
          :letter-spacing "0.12em"
          :font-size      "var(--t-2)")]])
