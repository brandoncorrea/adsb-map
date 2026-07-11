(ns adsb.core
  "Frontend entry point. Boots re-frame's app-db and mounts the app shell
  onto the page. The map, panels, filters, and aircraft layer all hang off
  `adsb.views/app-root`."
  (:require
    [adsb.events]
    [adsb.views :as views]
    [re-frame.core :as rf]
    [reagent.dom.client :as rdom]))

(defonce ^:private root
  ;; A single React 18 root, created once and reused across hot reloads so
  ;; `:dev/after-load` re-renders in place instead of leaking roots.
  (atom nil))

(defn ^:dev/after-load mount!
  "Render the app shell into #app. Idempotent under hot reload."
  []
  (when-let [el (js/document.getElementById "app")]
    (when (nil? @root)
      (reset! root (rdom/create-root el)))
    (rdom/render @root [views/app-root])))

(defn init!
  "Called by the :app build on page load. Initialize the app-db, then mount."
  []
  (rf/dispatch-sync [:app/initialize-db])
  (mount!))
