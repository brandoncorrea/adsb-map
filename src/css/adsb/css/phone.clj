(ns adsb.css.phone
  "Phone (Q9c: a designed stance, not a degradation).

  The NOTAM strip spans the full width (the roster is a bottom drawer now);
  the index card sits under the strip. The roster's own phone geometry lives
  in adsb.css.roster — this namespace is the rest of the chrome's phone stance.

  ORDER-CRITICAL: these are overrides, and they must be emitted last."
  (:require
    [adsb.css.decl :refer [decl]]
    [garden.stylesheet :refer [at-media]]))

(def styles
  (at-media {:max-width "640px"}
    ;; Collapsed drawer height — NOTAM and edge arrows clear the bottom rail
    ;; when the sheet is closed. Open snaps lift attribution via
    ;; adsb.css.roster's .is-sheet-* rules (adsb-xgg).
    [":root"
     ;; Matches --roster-rail-h on the phone drawer (adsb.css.roster).
     (decl :--roster-w "48px")]

    [:.adsb-alerts
     (decl :right 0)]

    [:.adsb-panel
     (decl :left  "var(--s3)"
           :right "var(--s3)"
           :width "auto"
           ;; Under the roster drawer (adsb-4ca): a full sheet must cover
           ;; the card so find/list stay reachable; the chip is still on
           ;; the map when the drawer is half/closed.
           :z-index 3)]

    [".adsb-panel.is-collapsed"
     (decl :right "auto" :width "auto")]))
