(ns adsb.css.splash)

(def styles
  [[:#adsb-splash
    {:position        "fixed"
     :inset           0
     :z-index         1
     :display         "flex"
     :align-items     "center"
     :justify-content "center"
     :background      "var(--paper)"
     :transition      "opacity 400ms ease-out"}]

   [:#adsb-splash.is-gone
    {:opacity        0
     :pointer-events "none"}]

   [:#adsb-splash.is-error
    {:cursor "pointer"}
    [:.adsb-splash-note
     {:animation "none"
      :color     "var(--ink)"}]]

   [:.adsb-splash-note
    {:font-family    "var(--mono)"
     :font-size      "var(--t0)"
     :letter-spacing "0.08em"
     :color          "var(--faded-ink)"
     :animation      "adsb-breathe 3.2s ease-in-out infinite"}]])
