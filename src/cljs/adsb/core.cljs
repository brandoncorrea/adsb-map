(ns adsb.core
  (:require [adsb.events]
            [adsb.stream :as stream]
            [adsb.subs]
            [adsb.ui.aircraft-panel :as aircraft-panel]
            [adsb.ui.splash]
            [adsb.views :as views]
            [re-frame.core :as rf]
            [reagent.dom.client :as rdom]))

(defonce ^:private root (atom nil))

(defn ^:dev/after-load mount! []
  (when-let [el (js-invoke js/document "getElementById" "app")]
    (swap! root (fn [r] (or r (rdom/create-root el))))
    (rdom/render @root [views/app-root])))

(defn init! []
  (rf/dispatch-sync [:app/initialize-db])
  (mount!)
  (aircraft-panel/start-clock!)
  (aircraft-panel/start-keyboard!)
  (stream/start!))
