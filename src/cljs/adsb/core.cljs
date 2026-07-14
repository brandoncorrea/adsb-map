(ns adsb.core
  "Frontend entry point. Boots re-frame's app-db, mounts the app shell
  onto #app, and opens the SSE stream.

  One page, one world: the live chart. There is no hash router — the
  design gallery that once lived at #/preview is gone."
  (:require
    [adsb.events]
    [adsb.stream :as stream]
    [adsb.subs]
    [adsb.ui.aircraft-panel :as aircraft-panel]
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
  (when-let [el (.getElementById js/document "app")]
    (swap! root (fn [r] (or r (rdom/create-root el))))
    (rdom/render @root [views/app-root])))

(defn init!
  "Called by the :app build on page load. Initialize the app-db, mount
  the shell, start the panel clock and keyboard, then open the SSE stream."
  []
  (rf/dispatch-sync [:app/initialize-db])
  (mount!)
  ;; The coarse UI clock that drives the panel's seen-age and prunes a
  ;; selection whose aircraft has aged out. Idempotent under hot reload.
  (aircraft-panel/start-clock!)
  (aircraft-panel/start-keyboard!)
  (stream/start!))
