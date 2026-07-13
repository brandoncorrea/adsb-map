(ns adsb.views
  "The app shell — the root Reagent tree everything else lands on. A thin
  chart-title-block header over a full-viewport map, with the marginalia
  floating around it. Dressed in The Sectional (docs/design-direction.md)
  by the visual pass (adsb-dgb.5); the look itself lives in app.css."
  (:require [adsb.map.view :as map-view]
            [adsb.ui.aircraft-panel :as aircraft-panel]
            [adsb.ui.alert :as alert]
            [adsb.ui.header :as header]
            [adsb.ui.stack :as stack]))

(defn app-root
  "The shell: a full-viewport map with the chrome floating over it — a thin
  header bar (title, live counts, session scalars, connection health) up top
  and the altitude legend tucked in a corner. Every one is a SIBLING of the
  map, never a child: the chrome is Reagent's and the map owns no React
  inside it."
  []
  [:div.adsb-shell
   [header/header]
   ;; The emergency banner sits directly under the header — top of the shell,
   ;; impossible to miss while any aircraft is squawking distress. Renders
   ;; nothing when the sky is calm (adsb.ui.alert).
   [alert/alert-ribbon]
   [map-view/map-view]
   ;; There is no margin column. The session stats went up to the header, where
   ;; the other vitals live (adsb-33i); the map key went onto the Stack, which
   ;; was already most of it (adsb-sod). Nothing was left in the corner but the
   ;; box, so the corner is the map's again.
   ;; The Stack — the live altitude ruler on the map's edge, replacing the
   ;; sidebar outright (design direction §9: there is no list). Ticks at
   ;; true altitudes, hover-to-highlight, click-to-select (adsb.ui.stack).
   [stack/stack]
   ;; Renders nothing until an aircraft is selected (adsb.ui.aircraft-panel).
   [aircraft-panel/aircraft-panel]])
