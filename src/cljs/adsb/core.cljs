(ns adsb.core
  "Frontend entry point. Boots re-frame's app-db and mounts the app shell
  onto the page. The map, panels, filters, and aircraft layer all hang off
  `adsb.views/app-root`.

  (A hash-routed #/preview fitting room lived here for the §5 bake-off —
  beads adsb-dgb.11 / adsb-fon. The picks were made and applied, and the
  Overseer cleared it out; resurrect it from git history if a future
  re-skin wants the mechanism back.)"
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
  (when-let [el (js/document.getElementById "app")]
    (when (nil? @root)
      (reset! root (rdom/create-root el)))
    (rdom/render @root [views/app-root])))

(defn init!
  "Called by the :app build on page load. Initialize the app-db, mount the
  shell, then open the SSE stream so the map starts receiving aircraft."
  []
  (rf/dispatch-sync [:app/initialize-db])
  (mount!)
  ;; The coarse UI clock that drives the panel's seen-age and prunes a
  ;; selection whose aircraft has aged out. Idempotent under hot reload.
  (aircraft-panel/start-clock!)
  (stream/start!))
