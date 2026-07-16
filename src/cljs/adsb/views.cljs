(ns adsb.views
  (:require [adsb.map.view :as map-view]
            [adsb.ui.aircraft-panel :as aircraft-panel]
            [adsb.ui.alert :as alert]
            [adsb.ui.follow :as follow]
            [adsb.ui.roster :as roster]))

(defn app-root []
  [:div.adsb-shell
   [alert/alert-ribbon]
   [map-view/map-view]
   [follow/follow-control]
   [roster/roster]
   [aircraft-panel/aircraft-panel]])
