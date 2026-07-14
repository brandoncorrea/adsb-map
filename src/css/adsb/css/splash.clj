(ns adsb.css.splash
  "The cold-load splash (#adsb-splash).

  A first-time reader waits on the JS bundle, then the basemap style, then the
  first tile before any of the chart appears. The paper backdrop already shows
  through while tiles load (adsb.css.shell), but a blank sheet says nothing
  about whether the page is working. So a single breathing note sits over the
  paper until the map's first frame paints, when adsb.ui.splash fades the whole
  element and pulls it from the DOM.

  The markup is static, in index.html — it must paint before the bundle runs,
  which a Reagent component cannot. These rules dress it; app.css is
  render-blocking in <head>, so #adsb-splash is dressed before it is seen, and
  there is no flash of an unstyled splash.

  The note breathes, and readers who ask for stillness get a static one — but
  that OFF switch is NOT here. Every reduced-motion rule lives in the one block
  adsb.css.motion emits LAST, where it can win the tie on source order; a
  second block minted early would lose it (adsb.css-test pins this). So
  `.adsb-splash-note` is disabled there, beside the panel, the dots and the
  ring — not in this namespace."
  (:require [adsb.css.decl :refer [decl]]))

(def styles
  [;; The full-viewport sheet — over the MAP, but UNDER the chrome. z-index 1
   ;; clears the map (a full-bleed background at stacking level 0) while
   ;; sitting beneath the roster (4), the NOTAM ribbon (3) and the Free/Follow
   ;; reticle (2), so the always-on roster rail is never veiled by a loading
   ;; map. Before React mounts there is no chrome to paint over it anyway —
   ;; the blank page is all map — so 1 is enough to cover that too. It only
   ;; exists during a cold load; the map underneath is what is not ready, and
   ;; the map is all this hides.
   [:#adsb-splash
    (decl :position         "fixed"
          :inset            0
          :z-index          1
          :display          "flex"
          :align-items      "center"
          :justify-content  "center"
          :background       "var(--paper)"     ; the same paper the chart prints on
          ;; The dissolve adsb.ui.splash trips (it adds .is-gone, then removes
          ;; the node a beat later) — a quiet fade to the chart underneath,
          ;; never a cut. Keep this duration in step with splash/fade-ms.
          :transition       "opacity 400ms ease-out")]

   ;; Fading out: id + class outranks the bare id, so opacity 0 wins. And
   ;; pointer-events none for the fade's duration, so a lingering sheet cannot
   ;; swallow the reader's first pan before the node is finally removed.
   [:#adsb-splash.is-gone
    (decl :opacity        0
          :pointer-events "none")]

   ;; Terminal state: the style fetch is out of retries (adsb.ui.splash), so
   ;; the sheet stays — but now it is a button. The whole sheet is the tap
   ;; target (adsb.ui.splash sets the onclick), so it reads as one, cursor and
   ;; all. It NEVER fades: unlike .is-gone this class carries no opacity, so the
   ;; error stays put until the reader taps to reload.
   ;;
   ;; The nested vector is a DESCENDANT selector, not a group: the note stops
   ;; breathing when the load has failed — a pulse says 'still working', and
   ;; nothing is. `#adsb-splash.is-error .adsb-splash-note` outranks the bare
   ;; note rule below, so it wins wherever both apply; the reduced-motion
   ;; off-switch in adsb.css.motion is a separate concern, untouched here.
   [:#adsb-splash.is-error
    (decl :cursor "pointer")
    [:.adsb-splash-note
     (decl :animation "none"
           :color     "var(--ink)")]]

   [:.adsb-splash-note
    (decl :font-family    "var(--mono)"
          :font-size      "var(--t0)"
          :letter-spacing "0.08em"
          :color          "var(--faded-ink)"
          ;; Breathing, never blinking (§6) — the shell's own ambient pulse.
          ;; Stilled under prefers-reduced-motion by adsb.css.motion's final
          ;; block (see this namespace's docstring), not here.
          :animation      "adsb-breathe 3.2s ease-in-out infinite")]])
