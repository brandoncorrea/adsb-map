(ns adsb.views
  "The app shell — the root Reagent tree everything else lands on. A minimal
  header bar over a full-viewport map. Styling here is a NEUTRAL PLACEHOLDER:
  the design direction (adsb-bvi.5) is not chosen yet, so this commits to none
  of the proposed looks — just structure and a home for future chrome."
  (:require
    [adsb.map.view :as map-view]
    [adsb.ui.aircraft-panel :as aircraft-panel]))

(defn header
  "Placeholder header bar. Holds the app name today; sidebar toggles, filters,
  and a live-status indicator will land here once there is a design to hang
  them on."
  []
  [:header.adsb-header
   [:span.adsb-title "adsb"]])

(defn app-root
  "The shell: a full-viewport map with a thin header bar floating over it.
  Panels, filters, and the aircraft legend all attach to this later."
  []
  [:div.adsb-shell
   [header]
   [map-view/map-view]
   ;; A sibling of the map, not a child — the panel is Reagent chrome and the
   ;; map owns no React inside it. Renders nothing until an aircraft is
   ;; selected (adsb.ui.aircraft-panel).
   [aircraft-panel/aircraft-panel]])
