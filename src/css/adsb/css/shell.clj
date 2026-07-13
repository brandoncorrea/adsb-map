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
          :background "var(--paper)")]

   ;; Taken out of the visual channel, left in the accessible one. The feeder's
   ;; healthy state wears this: the eye gets a green dot, a screen reader still
   ;; hears "Feeder OK" (adsb-33i). `display: none` would have deleted the
   ;; words from both channels, which is the mistake this class exists to avoid.
   [:.adsb-vh
    (decl :position    "absolute"
          :width       "1px"
          :height      "1px"
          :margin      "-1px"
          :padding     0
          :overflow    "hidden"
          :clip-path   "inset(50%)"
          :white-space "nowrap"
          :border      0)]])

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
          :letter-spacing       "0.02em")]

   ;; The session scalars — max range, message rate. They were a bordered chip
   ;; in the margin column, covering the map; they are vitals, so they sit with
   ;; the vitals, and a box drawn around two numbers is a box around nothing
   ;; (adsb-33i). The label voice is adsb.css.captions'.
   [:.adsb-stats
    (decl :display     "flex"
          :align-items "baseline"
          :gap         "var(--s4)"
          :font-size   "var(--t-1)")]

   [:.adsb-stats-row
    (decl :display     "flex"
          :align-items "baseline"
          :gap         "var(--s2)")]

   ;; the VOICE is adsb.css.captions'; the colour travelled up with the rules.
   ;; It is an <abbr>, so the browser's dotted underline has to go — the long
   ;; form lives in the title, and a chart's marginalia are not hyperlinks.
   [:.adsb-stats-label
    (decl :color           "var(--faded-ink)"
          :text-decoration "none"
          :cursor          "help")]

   [:.adsb-stats-value
    (decl :font-variant-numeric "tabular-nums")]])

(def health
  "Health chips — stream and feeder, drawn as stamped chart notes: squared,
  hairline-ruled, mono. :down is a filled red alarm so a frozen map cannot
  read as a live one.

  Each PROBLEM state is a dot colour AND a text label, because colour alone is
  not accessible and a colour-blind reader must not be the only one who cannot
  see that the antenna is dead. The BENIGN states are allowed to be quiet: the
  stream chip is not rendered at all while live (adsb.ui.header), and the
  feeder at :ok sheds its box and its printed label and is simply a green dot
  (its words live on in the accessibility tree — see .adsb-vh). Colour alone
  may say fine; it may never say broken (adsb-33i)."
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

   ;; Whichever health signal comes FIRST takes the free space and pins the
   ;; pair to the right edge — the stream chip when it is present, and the
   ;; feeder when it is not, which is most of the time. Health is always in the
   ;; same corner, whether or not the stream has anything to say.
   [:.adsb-conn :.adsb-feeder
    (decl :margin-left "auto")]

   [".adsb-conn + .adsb-feeder"
    (decl :margin-left "var(--s3)")]

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

   ;; OK: the dot, and nothing else. No box, because there is no longer a word
   ;; inside it to hold — and a slightly larger dot, because it is now carrying
   ;; the signal alone.
   [:.adsb-feeder-ok
    (decl :color        "var(--ok)"
          :padding      0
          :border-color "transparent")]

   [".adsb-feeder-ok .adsb-feeder-dot"
    (decl :width  "9px"
          :height "9px")]

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
