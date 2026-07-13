(ns adsb.css.shell
  "The shell and the header — the chart's title block.

  The shell fills the viewport and the map is its full-bleed background. The
  header is 36px (--header-h) of instrument: the title stamped in bold under
  the magenta pen underline, mono vitals, the two health chips pinned right
  so the map's health is always in the same place. One ink rule below."
  (:require
    [adsb.css.decl :refer [decl]]))

(def base
  [[:html :body :#app
    (decl :margin 0
          :height "100%")]

   [:body
    (decl :font-family "var(--mono)"
          :font-size   "var(--t0)"
          :background  "var(--paper)"   ; the paper shows before the first tile
          :color       "var(--ink)")]

   ;; The shell fills the viewport; the map is its full-bleed background.
   [:.adsb-shell
    (decl :position "relative"
          :height   "100vh"
          :overflow "hidden")]

   [:.adsb-map
    (decl :position   "absolute"
          :inset      0
          ;; the chart's own paper while tiles load
          :background "var(--paper)")]])

(def header
  [[:.adsb-header
    (decl :position      "absolute"
          :top           0
          :left          0
          :right         0
          :z-index       1
          :display       "flex"
          :align-items   "center"
          :gap           "var(--s4)"
          :height        "var(--header-h)"
          :padding       "0 var(--s4)"
          :background    "var(--paper-chrome)"
          :border-bottom "1px solid var(--ink)"
          :color         "var(--ink)"
          :font-size     "var(--t0)"
          :box-sizing    "border-box")]

   [:.adsb-title
    (decl :font-size      "var(--t1)"
          :font-weight    700
          :letter-spacing "0.04em"
          :line-height    1
          :border-bottom  "2px solid var(--magenta)"  ; the plotter's pen (§5)
          :padding-bottom "1px")]

   ;; Live vital signs. The counts sit next to the title; the two health
   ;; chips are pushed to the far right.
   [:.adsb-counts
    (decl :color     "var(--faded-ink)"
          :font-size "var(--t-1)")]

   [:.adsb-count-total :.adsb-count-positioned
    (decl :font-weight 700
          :color       "var(--ink)")]

   ;; .adsb-count-unit takes the printed caption voice — adsb.css.captions
   ;; owns it.

   [:.adsb-count-sep
    (decl :opacity 0.6)]

   [:.adsb-clock
    (decl :color                "var(--faded-ink)"
          :font-size            "var(--t-1)"
          :font-variant-numeric "tabular-nums"
          :letter-spacing       "0.02em")]])

(def health
  "Health chips — stream and feeder, each distinct per state (a dot colour
  AND a text label; colour alone is not accessible). Drawn as stamped chart
  notes: squared, hairline-ruled, mono. :down is a filled red alarm so a
  frozen map cannot read as a live one."
  [[:.adsb-conn :.adsb-feeder
    (decl :display        "inline-flex"
          :align-items    "center"
          :gap            "var(--s2)"
          :padding        "var(--s1) var(--s3)"
          :border-radius  "2px"
          :font-weight    700
          :line-height    1.4
          :border         "1px solid transparent"
          :font-size      "var(--t-1)"
          :letter-spacing "0.04em")]

   [:.adsb-conn
    (decl :margin-left "auto")]

   [:.adsb-conn-dot :.adsb-feeder-dot
    (decl :width         "7px"
          :height        "7px"
          :border-radius "50%"
          :background    "currentColor"
          :border        "1px solid var(--rule-strong)"
          :box-sizing    "border-box"
          :flex          "none")]

   ;; The healthy dots breathe — ambient life, never a blink (§6).
   [".adsb-conn-live .adsb-conn-dot" ".adsb-feeder-ok .adsb-feeder-dot"
    (decl :animation "adsb-breathe 3.2s ease-in-out infinite")]

   [:.adsb-conn-live
    (decl :color        "var(--ok)"
          :border-color "var(--rule)")]

   [:.adsb-conn-reconnecting
    (decl :color        "var(--warn)"
          :border-color "var(--warn)")]

   [:.adsb-conn-down
    (decl :color        "var(--on-emergency)"
          :background   "var(--emergency)"
          :border-color "var(--emergency)")]

   [:.adsb-feeder-ok
    (decl :color        "var(--ok)"
          :border-color "var(--rule)")]

   [:.adsb-feeder-starting
    (decl :color        "var(--warn)"
          :border-color "var(--warn)")]

   [:.adsb-feeder-unknown
    (decl :color        "var(--faded-ink)"
          :border-color "var(--rule)")]

   [:.adsb-feeder-down
    (decl :color        "var(--on-emergency)"
          :background   "var(--emergency)"
          :border-color "var(--emergency)")]])

(def styles
  [base header health])
