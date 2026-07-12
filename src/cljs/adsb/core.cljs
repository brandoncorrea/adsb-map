(ns adsb.core
  "Frontend entry point. Boots re-frame's app-db, picks the page the
  location hash names, and mounts it onto #app.

  ## The router is two pure functions and a reload

  There are exactly two pages — the app shell at #/ (and everywhere
  else: the default page IS the app) and the design-options preview at
  #/preview (bead adsb-dgb.11). `route-for` names the route, `page-for`
  names its root component; both are data-in data-out so a test asserts
  the whole table without mounting anything. No routing library for a
  two-entry table.

  Crossing the boundary between the two reloads the page, deliberately:
  each route boots a different WORLD — the app opens the SSE stream,
  the preview seeds a fixture sky and must never have a live stream
  overwriting it — and a reload is the honest way to swap worlds. Hash
  edits WITHIN the preview (the mix parameters) keep the #/preview
  prefix and never trigger it; they are also written via replaceState,
  which fires no hashchange at all."
  (:require
    [adsb.events]
    [adsb.preview :as preview]
    [adsb.stream :as stream]
    [adsb.subs]
    [adsb.ui.aircraft-panel :as aircraft-panel]
    [adsb.views :as views]
    [clojure.string :as str]
    [re-frame.core :as rf]
    [reagent.dom.client :as rdom]))

(defn route-for
  "Which page a location `hash` names: :preview for #/preview (and its
  query-carrying forms), :app for everything else — an absent or
  unrecognized hash must never hide the chart."
  [hash]
  (if (str/starts-with? (or hash "") preview/hash-prefix) :preview :app))

(defn page-for
  "The root component for `route`, as hiccup data — so a test can
  assert the routing table without mounting a real map."
  [route]
  (if (= :preview route) [preview/page] [views/app-root]))

(defonce ^:private !route
  ;; The route this page load booted with. Set once by init!; a hash
  ;; change that names a different route reloads rather than re-routes.
  (atom :app))

(defonce ^:private root
  ;; A single React 18 root, created once and reused across hot reloads so
  ;; `:dev/after-load` re-renders in place instead of leaking roots.
  (atom nil))

(defn ^:dev/after-load mount!
  "Render the booted route's page into #app. Idempotent under hot reload."
  []
  (when-let [el (js/document.getElementById "app")]
    (when (nil? @root)
      (reset! root (rdom/create-root el)))
    (rdom/render @root (page-for @!route))))

(defn- watch-route!
  "Reload when the hash crosses the app/preview boundary — each route
  boots its own world (see the ns docstring). A hash change WITHIN the
  preview is a pasted mix link (the page's own switches use
  replaceState, which never fires hashchange), so the preview adopts it
  live instead."
  []
  (.addEventListener js/window "hashchange"
                     (fn [_]
                       (let [route (route-for (.-hash js/location))]
                         (cond
                           (not= route @!route) (.reload js/location)
                           (= :preview route)   (preview/adopt-hash!))))))

(defn init!
  "Called by the :app build on page load. Initialize the app-db, mount
  the hash-named page, then boot that page's world: the app path opens
  the SSE stream; the preview path seeds its fixture sky instead and
  never streams."
  []
  (rf/dispatch-sync [:app/initialize-db])
  (reset! !route (route-for (.-hash js/location)))
  (watch-route!)
  (when (= :preview @!route)
    (preview/start!))
  (mount!)
  ;; The coarse UI clock that drives the panel's seen-age and prunes a
  ;; selection whose aircraft has aged out. Idempotent under hot reload.
  ;; Both routes want it: the preview renders the same clock-fed chrome.
  (aircraft-panel/start-clock!)
  (when (= :app @!route)
    (stream/start!)))
