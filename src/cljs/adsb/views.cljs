(ns adsb.views
  "The app shell — the root Reagent tree everything else lands on. A minimal
  header bar over a full-viewport map. Styling here is a NEUTRAL PLACEHOLDER:
  the design direction (adsb-bvi.5) is not chosen yet, so this commits to none
  of the proposed looks — just structure and a home for future chrome."
  (:require
    [adsb.map.view :as map-view]
    [adsb.ui.aircraft-panel :as aircraft-panel]
    [adsb.ui.header :as header]
    [adsb.ui.legend :as legend]
    [adsb.ui.sidebar :as sidebar]))

(defn app-root
  "The shell: a full-viewport map with the chrome floating over it — a thin
  header bar (title, live counts, connection health) up top and the altitude
  legend tucked in a corner. Every one is a SIBLING of the map, never a child:
  the chrome is Reagent's and the map owns no React inside it."
  []
  [:div.adsb-shell
   [header/header]
   [map-view/map-view]
   ;; Corner overlay explaining the colour ramp. Static chrome — reads the
   ;; style constants, derefs nothing (adsb.ui.legend).
   [legend/legend]
   ;; The aircraft roster — the same picture the map reads, as a sortable,
   ;; filterable, click-to-select list (adsb.ui.sidebar).
   [sidebar/sidebar]
   ;; Renders nothing until an aircraft is selected (adsb.ui.aircraft-panel).
   [aircraft-panel/aircraft-panel]])
