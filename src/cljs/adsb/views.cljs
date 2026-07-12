(ns adsb.views
  "The app shell — the root Reagent tree everything else lands on. A minimal
  header bar over a full-viewport map. Styling here is a NEUTRAL PLACEHOLDER:
  the design direction (adsb-bvi.5) is not chosen yet, so this commits to none
  of the proposed looks — just structure and a home for future chrome."
  (:require
    [adsb.map.view :as map-view]
    [adsb.ui.aircraft-panel :as aircraft-panel]
    [adsb.ui.alert :as alert]
    [adsb.ui.header :as header]
    [adsb.ui.legend :as legend]
    [adsb.ui.stack :as stack]
    [adsb.ui.stats :as stats]))

(defn app-root
  "The shell: a full-viewport map with the chrome floating over it — a thin
  header bar (title, live counts, connection health) up top and the altitude
  legend tucked in a corner. Every one is a SIBLING of the map, never a child:
  the chrome is Reagent's and the map owns no React inside it."
  []
  [:div.adsb-shell
   [header/header]
   ;; The emergency banner sits directly under the header — top of the shell,
   ;; impossible to miss while any aircraft is squawking distress. Renders
   ;; nothing when the sky is calm (adsb.ui.alert).
   [alert/alert-ribbon]
   [map-view/map-view]
   ;; Corner overlay explaining the colour ramp. Static chrome — reads the
   ;; style constants, derefs nothing (adsb.ui.legend).
   [legend/legend]
   ;; Numbers-only session readout in the same corner — max range and
   ;; message rate, both scalars, never a position (adsb.ui.stats).
   [stats/stats-readout]
   ;; The Stack — the live altitude ruler on the map's edge, replacing the
   ;; sidebar outright (design direction §9: there is no list). Ticks at
   ;; true altitudes, hover-to-highlight, click-to-select (adsb.ui.stack).
   [stack/stack]
   ;; Renders nothing until an aircraft is selected (adsb.ui.aircraft-panel).
   [aircraft-panel/aircraft-panel]])
