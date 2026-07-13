(ns adsb.css.phone
  "Phone (Q9c: a designed stance, not a degradation).

  The header holds its 36px: the clock and the counts' unit words step aside
  (the numbers stay), the chips keep their labels. The NOTAM strip spans the
  full width (the Stack is on the bottom edge now); the index card spans the
  width under the header.

  There is no margin column any more (adsb-sod): the stats went to the header
  and the map key went onto the Stack, and the corner went back to the map.

  The Stack's own phone geometry is NOT here — it stays inside its bounded
  section, adsb.css.stack. This namespace is the chrome's phone stance.

  ORDER-CRITICAL: these are overrides, and they must be emitted last."
  (:require
    [adsb.css.decl :refer [decl]]
    [garden.stylesheet :refer [at-media]]))

(def styles
  (at-media {:max-width "640px"}
    [:.adsb-alerts
     (decl :right 0)]

    [:.adsb-panel
     (decl :left  "var(--s3)"
           :right "var(--s3)"
           :width "auto")]))
