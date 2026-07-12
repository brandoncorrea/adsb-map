(ns adsb.map.view
  "The map, mounted and alive — a full-viewport MapLibre canvas. This
  namespace owns the map options and the Reagent component that drives
  the imperative map through the `adsb.map.maplibre` seam, and it wires
  the aircraft layer's lifecycle (adsb.map.aircraft-layer) to the
  component's own. The aircraft DATA never passes through here — the
  layer reads the picture outside React entirely.

  ## The two printed editions

  The basemap is not handed to MapLibre as a URL: the raw Liberty style
  JSON is fetched ONCE (through the `load-style!` seam), and each mount
  prints it in the current edition via adsb.map.basemap/edition-style —
  day or night per the system theme (adsb.map.theme). When the system
  scheme flips, the component tears the map and layer down and re-prints
  them in the other edition: a theme flip is a rare, human-scale event,
  and a clean re-print is honest about what it is — a different physical
  chart — where MapLibre's setStyle would silently drop our sources and
  layers and demand a re-wire anyway. The fetched JSON is cached in the
  component, so a flip costs no network."
  (:require
    [adsb.map.aircraft-layer :as aircraft-layer]
    [adsb.map.basemap :as basemap]
    [adsb.map.maplibre :as maplibre]
    [adsb.map.theme :as theme]
    [reagent.core :as r]))

;; Basemap: OpenFreeMap's "liberty" style — the production basemap (adsb-kh4.5),
;; re-inked per edition by adsb.map.basemap (adsb-dgb.7). The PROVIDER is
;; settled; the direction customizes the style JSON, not the provider.
;;
;; Why OpenFreeMap, and why the same URL in dev and prod:
;;   * No token, no API key, no registration — nothing secret ever reaches the
;;     browser bundle. This is the whole reason security-checklist.md §3 still
;;     promises there are no browser-visible secrets; keep it that way.
;;   * Its public instance permits unlimited production traffic for a public
;;     hobby site (no per-view caps, commercial use allowed). So there is no
;;     reason to branch dev vs prod — one URL everywhere is the simplest thing
;;     that respects the fair-use terms.
;;   * "liberty" is a neutral-rich variant: terrain, water, and labels all
;;     rendered — the richly-rendered basemap the direction requires (Q4c).
;;   * MapLibre renders the style's own attribution automatically via the
;;     attribution control (enabled below): "OpenFreeMap © OpenMapTiles Data
;;     from OpenStreetMap". The credit rides the style JSON's SOURCES, which
;;     the edition transform never touches, so it survives the re-inking.
;; See README "Basemap" and https://openfreemap.org for the fair-use terms.
(def ^:const style-url "https://tiles.openfreemap.org/styles/liberty")

;; PRIVACY — non-negotiable (adsb-2yu.1, per the Overseer). The default center
;; is a FIXED, whole-degree-rounded point over the Tampa Bay / Florida Gulf
;; coast coverage area. It MUST NEVER be set to the receiver's position: a
;; rounded regional center reveals a region, not a rooftop.
;; MapLibre wants [lon lat]; aviation reads lat,lon. Here: lat 28.0, lon -82.0.
(def ^:const default-center [-82.0 28.0])
(def ^:const default-zoom 7)

(defn load-style!
  "Fetch the basemap style JSON at `url` and call `cb` with it as Clojure
  data (keywordized). A seam: tests redef this to hand back a fixture
  style synchronously — no network, no flake. Errors are left to the
  browser console; a map that cannot fetch its style has no fallback
  worth pretending to."
  [url cb]
  (-> (js/fetch url)
      (.then (fn [res] (.json res)))
      (.then (fn [json] (cb (js->clj json :keywordize-keys true))))))

(defn default-map-opts
  "The MapLibre map options the shell boots with, printing `style` (an
  edition's style JSON as Clojure data). Pure — the test asserts against
  this directly, and the fake `create!` receives exactly this."
  [style]
  {:style              style
   :center             default-center
   :zoom               default-zoom
   ;; Attribution is required and never hidden — the basemap must credit
   ;; OpenFreeMap / OpenMapTiles / OpenStreetMap. The style JSON's sources
   ;; carry the text; enabling the control is what makes MapLibre render it.
   :attributionControl true})

(defn map-container
  "The map's DOM anchor. Named (not an inline anonymous fn in the render
  path) so the zero-re-render proof in adsb.map.aircraft-layer-test can
  count the map component's renders with a redef."
  [!container]
  [:div.adsb-map {:ref #(reset! !container %)}])

(defn map-view
  "Full-viewport MapLibre canvas. A form-3 component: it owns a plain DOM
  node (the container is not reactive — no ratom deref in render) and
  drives the imperative map through the seam. On mount it fetches the raw
  basemap style once, prints the current edition, and creates map +
  aircraft layer; on a system theme flip it destroys and re-prints both;
  on unmount everything is torn down and the flip listener removed. This
  component derefs no subscription and NEVER re-renders on aircraft
  traffic — the layer pushes picture changes straight into MapLibre,
  outside React."
  []
  (let [!container (atom nil)
        !map       (atom nil)
        !aircraft  (atom nil)
        !raw-style (atom nil)
        !unwatch   (atom nil)
        !disposed  (atom false)]
    (letfn [(mount-map! [th]
              (let [style (basemap/edition-style @!raw-style th)
                    m     (maplibre/create! @!container (default-map-opts style))]
                (reset! !map m)
                (reset! !aircraft (aircraft-layer/attach! m th))))
            (unmount-map! []
              (when-let [layer @!aircraft]
                (aircraft-layer/detach! layer)
                (reset! !aircraft nil))
              (when-let [m @!map]
                (maplibre/destroy! m)
                (reset! !map nil)))
            (reprint! [th]
              ;; The system slid between day and night: put the other
              ;; edition on the table. Only once the plate is fetched and
              ;; we are still alive — a flip before the style arrives is
              ;; covered by mount-map! reading the fresh !theme.
              (theme/set-theme! th)
              (when (and @!raw-style (not @!disposed))
                (unmount-map!)
                (mount-map! th)))]
      (r/create-class
        {:display-name "adsb-map"

         :component-did-mount
         (fn [_this]
           (reset! !unwatch (theme/watch-system! reprint!))
           (load-style! style-url
                        (fn [raw]
                          (when-not @!disposed
                            (reset! !raw-style raw)
                            (mount-map! (theme/sync!))))))

         :component-will-unmount
         (fn [_this]
           (reset! !disposed true)
           (when-let [unwatch @!unwatch]
             (unwatch)
             (reset! !unwatch nil))
           (unmount-map!))

         :reagent-render
         (fn []
           (map-container !container))}))))
