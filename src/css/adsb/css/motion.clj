(ns adsb.css.motion
  (:require [garden.stylesheet :refer [at-keyframes at-media]]))

(def settle
  (at-keyframes "adsb-settle"
    ["from" {:opacity   0
             :transform "translateY(-4px)"}]))

(def settle-up
  (at-keyframes "adsb-settle-up"
    ["from" {:opacity   0
             :transform "translateY(4px)"}]))

(def breathe
  (at-keyframes "adsb-breathe"
    ["0%" {:opacity 1}]
    ["50%" {:opacity 0.45}]
    ["100%" {:opacity 1}]))

(def ring-draw
  (at-keyframes "adsb-ring-draw"
    ["from" {:stroke-dasharray  "0 7"
             :stroke-dashoffset 10
             :opacity           0.2}]
    ["to" {:stroke-dasharray  "3.2 3.8"
           :stroke-dashoffset 0
           :opacity           1}]))

(def mayday-draw
  (at-keyframes "adsb-mayday-draw"
    ["from" {:stroke-dashoffset 100}]))

(def reduced-motion
  (at-media {:prefers-reduced-motion :reduce}
    [:.adsb-panel
     :.adsb-roster
     ".adsb-selection-ring circle"
     ".adsb-conn-live .adsb-conn-dot"
     ".adsb-feeder-ok .adsb-feeder-dot"
     :.adsb-splash-note
     {:animation "none"}]

    [".adsb-mayday ellipse"
     {:animation-name [["none" "!important"]]}]

    [:.adsb-roster
     {:transition "none"}]))

(def styles [settle settle-up breathe ring-draw mayday-draw])
