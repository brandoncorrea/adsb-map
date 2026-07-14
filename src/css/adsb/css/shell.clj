(ns adsb.css.shell
  "The shell, and the health signals that outlived the header.

  The shell fills the viewport and the map is its full-bleed background. THERE
  IS NO HEADER (adsb-sod): the title, the counts, the scalars and the clock each
  left for somewhere they belonged or for nowhere at all, and a 36px band
  holding two glyphs is not a band. The chart runs edge to edge.

  What is left of the apparatus is one dot, and it rides the roster rail
  (adsb.ui.health). Its rules stay here — they are chrome, not the roster's
  own section, and the roster merely gives them a seat."
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

   ;; The shell fills the viewport; the map is its full-bleed background
   ;; (including under the notch — chrome, not the chart, clears safe areas).
   ;; 100dvh prefers the dynamic viewport on mobile; 100vh is the fallback.
   [:.adsb-shell
    (decl :position "relative"
          :height   "100vh"
          :overflow "hidden")]

   [:.adsb-shell
    (decl :height "100dvh")]

   [:.adsb-map
    (decl :position   "absolute"
          :inset      0
          ;; the chart's own paper while tiles load
          :background "var(--paper)")]

   ;; Free / Follow reticle — map chrome stacked just above MapLibre's
   ;; compact attribution (i) in the bottom-right corner (adsb-jg4).
   ;; Desktop clearance matches .maplibregl-ctrl-bottom-right (roster.clj);
   ;; phone sheet snaps override bottom in adsb.css.roster/phone.
   ;; 10px is MapLibre's control margin; 34px clears the compact (i).
   [:.adsb-follow-control
    (decl :position        "absolute"
          :z-index         2
          :right           "calc(var(--roster-w) + var(--safe-right) + 10px)"
          :bottom          "calc(var(--safe-bottom) + 10px + 34px)"
          :display         "inline-flex"
          :align-items     "center"
          :justify-content "center"
          :width           "29px"
          :height          "29px"
          :margin          0
          :padding         0
          :border          "1px solid var(--rule)"
          :border-radius   "4px"
          :background      "var(--paper-chrome)"
          :color           "var(--faded-ink)"
          :box-shadow      "0 0 0 2px rgba(0, 0, 0, 0.1)"
          :cursor          "pointer"
          :line-height     0)]

   [:.adsb-follow-control:hover
    (decl :color "var(--ink)")]

   [".adsb-follow-control.is-active"
    (decl :color      "var(--magenta)"
          :border-color "var(--magenta)")]

   [:.adsb-follow-control:focus-visible
    (decl :outline        "2px solid var(--magenta)"
          :outline-offset "2px")]

   [:.adsb-follow-glyph
    (decl :display "block"
          :width   "16px"
          :height  "16px")]

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

   ;; The group, on the roster rail. It collapses to nothing when every
   ;; signal is quiet — which is the common case, and the point.
   [:.adsb-health
    (decl :display     "flex"
          :align-items "center"
          :gap         "var(--s3)"
          :margin-left "var(--s3)")]

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

   ;; SILENT — reachable, and hearing nothing. Amber, and it PRINTS ITS WORDS
   ;; (`No messages`): this is the state the green dot used to lie about, and a
   ;; lie corrected into a colour nobody can name would not be much of a fix.
   [:.adsb-feeder-silent
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
  [base health])
