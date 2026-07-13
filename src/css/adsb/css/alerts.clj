(ns adsb.css.alerts
  "The NOTAM strip (§7) — the loudest chrome the app owns.

  Sits directly under the header, spanning the shell, while ANY aircraft
  squawks distress. Every emergency STACKS as its own row rather than cycling
  behind a timer. Grave, drawn once: red field, plain mono language, a stamped
  NOTAM tab — and ZERO motion. Ink does not blink."
  (:require [adsb.css.decl :refer [decl]]))

(def styles
  [[:.adsb-alerts
    (decl :position      "absolute"
          :top           "var(--header-h)"
          :left          0
          :right         "var(--stack-w)"  ; clear of the Stack on the right edge
          :z-index       3                 ; above the map, the panel, the Stack
          :display       "flex"
          :align-items   "stretch"
          :background    "var(--emergency)"
          :color         "var(--on-emergency)"
          :border-bottom "1px solid var(--rule-strong)"
          :max-width     "100%")]

   [:.adsb-alert-stamp
    (decl :align-self     "center"
          :flex           "none"
          :margin         "0 0 0 var(--s4)"
          :padding        "var(--s1) var(--s2)"
          :border         "1px solid var(--on-emergency)"
          :border-radius  "1px"
          :font-size      "var(--t-2)"
          :font-weight    700
          :letter-spacing "0.14em")]

   [:.adsb-alert-rows
    (decl :display        "flex"
          :flex-direction "column"
          :flex           1
          :min-width      0)]

   ;; ORDER: `border: none` erases the row rule, then `border-bottom` draws
   ;; the one we want; `font: inherit` resets font-size, then font-size sets
   ;; it. Written order is the meaning here — see adsb.css.decl.
   [:.adsb-alert
    (decl :display       "flex"
          :align-items   "baseline"
          :gap           "var(--s4)"
          :width         "100%"
          :padding       "var(--s3) var(--s4)"
          :border        "none"
          :border-bottom "1px solid color-mix(in srgb, var(--on-emergency) 30%, transparent)"
          :background    "transparent"
          :color         "var(--on-emergency)"
          :font          "inherit"
          :font-size     "var(--t0)"
          :text-align    "left"
          :cursor        "pointer"
          :box-sizing    "border-box")]

   [:.adsb-alert:last-child
    {:border-bottom "none"}]

   [:.adsb-alert:hover
    {:filter "brightness(1.08)"}]

   [:.adsb-alert:focus-visible
    (decl :outline        "2px solid var(--on-emergency)"
          :outline-offset "-3px")]

   [:.adsb-alert-name
    {:font-weight    700
     :letter-spacing "0.02em"}]

   [:.adsb-alert-squawk
    (decl :font-variant-numeric "tabular-nums"
          :font-weight          700)]

   [:.adsb-alert-meaning
    (decl :text-transform "uppercase"
          :letter-spacing "0.04em"
          :font-size      "var(--t-1)"
          :opacity        0.9)]])
