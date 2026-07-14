(ns adsb.views
  "The app shell — the root Reagent tree everything else lands on. A thin
  chart-title-block header over a full-viewport map, with the marginalia
  floating around it. Dressed in The Sectional (docs/design-direction.md)
  by the visual pass (adsb-dgb.5); the look itself lives in app.css.

  Product chrome (adsb-66h): Search + Sheet roster on the edge, collapsible
  detail card, NOTAM when the sky is loud. The Stack is retired."
  (:require [adsb.map.view :as map-view]
            [adsb.ui.aircraft-panel :as aircraft-panel]
            [adsb.ui.alert :as alert]
            [adsb.ui.roster :as roster]))

(defn app-root
  "The shell: a full-viewport map, and the chrome floating over it. Every
  surface left over it earns its place: the roster dock (find + ranked list),
  the NOTAM ribbon when an aircraft is squawking, the index card when one is
  selected.

  Every one is a SIBLING of the map, never a child: the chrome is Reagent's and
  the map owns no React inside it."
  []
  [:div.adsb-shell
   [alert/alert-ribbon]
   [map-view/map-view]
   [roster/roster]
   [aircraft-panel/aircraft-panel]])
