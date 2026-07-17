(ns adsb.css.shell
  (:require [adsb.css.decl :refer [decl]]))

;; Screen-reader-only: present in the accessibility tree, gone from layout.
;; Shared by .adsb-vh here and the roster's conn/feeder labels (adsb.css.roster).
(def visually-hidden
  {:position    "absolute"
   :width       "1px"
   :height      "1px"
   :margin      "-1px"
   :padding     0
   :overflow    "hidden"
   :clip-path   "inset(50%)"
   :white-space "nowrap"
   :border      0})

(def base
  [[:html :body :#app
    {:margin 0
     :height "100%"}]

   [:body
    {:font-family "var(--mono)"
     :font-size   "var(--t0)"
     :background  "var(--paper)"
     :color       "var(--ink)"}]

   ;; Declared twice on purpose: 100vh is the fallback for browsers without
   ;; dvh; the second rule wins where dvh is understood.
   [:.adsb-shell
    {:position "relative"
     :height   "100vh"
     :overflow "hidden"}]

   [:.adsb-shell
    {:height "100dvh"}]

   [:.adsb-map
    {:position   "absolute"
     :inset      0
     :background "var(--paper)"}]

   [:.adsb-follow-control
    (decl :position "absolute"
          :z-index 2
          :right "calc(var(--roster-w) + var(--safe-right) + var(--maplibre-ctrl-margin))"
          :bottom "calc(var(--safe-bottom) + var(--maplibre-ctrl-margin) + var(--maplibre-attribution))"
          :display "inline-flex"
          :align-items "center"
          :justify-content "center"
          :width "29px"
          :height "29px"
          :margin 0
          :padding 0
          :border "1px solid var(--rule)"
          :border-radius "4px"
          :background "var(--paper-chrome)"
          :color "var(--faded-ink)"
          :box-shadow "0 0 0 2px rgba(0, 0, 0, 0.1)"
          :cursor "pointer"
          :font-size "16px"
          :line-height 0)]

   [:.adsb-follow-control:hover
    {:color "var(--ink)"}]

   [".adsb-follow-control.is-active"
    {:color        "var(--magenta)"
     :border-color "var(--magenta)"}]

   [:.adsb-follow-control:focus-visible
    {:outline        "2px solid var(--magenta)"
     :outline-offset "2px"}]

   [:.adsb-vh visually-hidden]])

(def health
  [[:.adsb-conn :.adsb-feeder
    (decl :display "inline-flex"
          :align-items "center"
          :gap "var(--s2)"
          :padding "var(--s1) var(--s3)"
          :border-radius "2px"
          :font-weight 700
          :line-height 1.4
          :border "1px solid transparent"
          :font-size "var(--t-1)"
          :letter-spacing "0.04em")]

   [:.adsb-health
    {:display     "flex"
     :align-items "center"
     :gap         "var(--s3)"
     :margin-left "var(--s3)"}]

   [:.adsb-conn-dot :.adsb-feeder-dot
    {:width         "7px"
     :height        "7px"
     :border-radius "50%"
     :background    "currentColor"
     :border        "1px solid var(--rule-strong)"
     :box-sizing    "border-box"
     :flex          "none"}]

   [".adsb-conn-live .adsb-conn-dot" ".adsb-feeder-ok .adsb-feeder-dot"
    {:animation "adsb-breathe 3.2s ease-in-out infinite"}]

   [:.adsb-conn-live
    {:color        "var(--ok)"
     :border-color "var(--rule)"}]

   ;; Warn trio and down duo: the conn and feeder health states share a look.
   [:.adsb-conn-reconnecting :.adsb-feeder-starting :.adsb-feeder-silent
    {:color        "var(--warn)"
     :border-color "var(--warn)"}]

   [:.adsb-conn-down :.adsb-feeder-down
    {:color        "var(--on-emergency)"
     :background   "var(--emergency)"
     :border-color "var(--emergency)"}]

   [:.adsb-feeder-ok
    {:color        "var(--ok)"
     :padding      0
     :border-color "transparent"}]

   [".adsb-feeder-ok .adsb-feeder-dot"
    {:width  "9px"
     :height "9px"}]

   [:.adsb-feeder-unknown
    {:color        "var(--faded-ink)"
     :border-color "var(--rule)"}]])

(def styles [base health])
