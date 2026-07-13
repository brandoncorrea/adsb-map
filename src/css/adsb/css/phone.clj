(ns adsb.css.phone
  "Phone (Q9c: a designed stance, not a degradation).

  The header holds its 36px: the clock and the counts' unit words step aside
  (the numbers stay), the chips keep their labels. The NOTAM strip spans the
  full width (the Stack is on the bottom edge now); the index card spans the
  width under the header; the margin column steps above the recumbent Stack
  and the lifted attribution.

  The Stack's own phone geometry is NOT here — it stays inside its bounded
  section, adsb.css.stack. This namespace is the chrome's phone stance.

  ORDER-CRITICAL: these are overrides, and they must be emitted last."
  (:require
    [adsb.css.decl :refer [decl]]
    [garden.stylesheet :refer [at-media]]))

(def styles
  (at-media {:max-width "640px"}
    [:.adsb-header
     (decl :gap     "var(--s3)"
           :padding "0 var(--s3)")]

    [:.adsb-clock :.adsb-count-unit
     (decl :display "none")]

    [:.adsb-alerts
     (decl :right 0)]

    [:.adsb-panel
     (decl :left  "var(--s3)"
           :right "var(--s3)"
           :width "auto")]

    [:.adsb-margin
     (decl :left      "var(--s3)"
           ;; Above the recumbent Stack, and that is all it must clear now. The
           ;; attribution used to be a banner of running text wrapping to two
           ;; lines across this whole edge, so the column was lifted 56px over
           ;; it; folded to its (i) button it is 24px wide in the far corner,
           ;; and the column simply sits beside it. That lift was 56px of map.
           :bottom    "calc(var(--stack-w) + var(--s2))"
           :max-width "calc(100vw - 2 * var(--s3))")]

    [:.adsb-legend
     (decl :gap     "var(--s4)"
           :padding "var(--s2) var(--s3)")]))
