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
    [adsb.map.emergency :as emergency]
    [adsb.map.maplibre :as maplibre]
    [adsb.map.selection :as selection]
    [adsb.map.theme :as theme]
    [re-frame.core :as rf]
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
   ;; Attribution is required and NEVER hidden — the basemap must credit
   ;; OpenFreeMap / OpenMapTiles / OpenStreetMap. The style JSON's sources
   ;; carry the text; enabling the control is what makes MapLibre render it.
   ;;
   ;; `compact` gives the credit an (i) button to fold INTO — MapLibre's own
   ;; first-class mode for exactly this. It still opens as a banner and is still
   ;; read; `attribution-fold-ms` is what folds it, five seconds later, and that
   ;; number is a licence term. Nothing here hides it.
   :attributionControl {:compact true}})

(def ^:private attribution-selector ".maplibregl-ctrl-attrib")
(def ^:private attribution-open-class "maplibregl-compact-show")

(def ^:const attribution-fold-ms
  "Five seconds, and this number is not a taste — it is the licence.

  The OSMF attribution guidelines permit the credit to be folded behind an (i)
  button, and they name the ONLY three things that may fold it: a dismiss
  interaction, a map interaction (pan / click / zoom), or a timeout of five
  seconds. Every one of them presupposes the credit was SHOWN first. A map that
  opens with the credit already folded is not on that list — the reader must be
  given the chance to read it.

  So the banner shows, and five seconds later it folds. Do not shorten this, and
  do not fold on load: OpenFreeMap, OpenMapTiles and OpenStreetMap are being
  credited here, and the five seconds are theirs."
  5000)

(defn collapse-attribution!
  "Fold the compact attribution shut inside `container`.

  Call this on the fold TIMEOUT, never at map creation — see
  `attribution-fold-ms`, which is a licence term wearing a number's clothes.
  MapLibre's own logic also folds it on the reader's first pan or zoom, which is
  the other permitted trigger; whichever comes first, the result is the same.

  Dropping the open-class STICKS, and is not a race: MapLibre re-runs its
  compact logic on resize and on styledata, but it adds the open-class back only
  when `maplibregl-compact` is ABSENT — and we leave that one exactly where it
  is. So it never re-opens itself behind us, and the (i) button still toggles it,
  because the button's own handler owns the very class we drop.

  Nothing is hidden: the credit was read, the control is still there, the (i) is
  one tap away. And if MapLibre ever renames those classes this quietly does
  nothing and the attribution simply stays open — the safe way to fail."
  [container]
  (when-let [control (some-> container (.querySelector attribution-selector))]
    (.remove (.-classList control) attribution-open-class)
    control))

;; ---------------------------------------------------------------------
;; The camera, as an effect.
;;
;; The map is a stateful JS object and lives in the component below, so the
;; effect reads it from here — the ONE place that knows whether a map currently
;; exists. Registered at namespace level (an effect handler must exist before the
;; first dispatch that names it), pointed at whatever map is mounted.
;;
;; A dead map is not an error: an edition flip tears the map down and builds
;; another, and a fly-to that lands in that gap simply does not happen. There is
;; no chart to move.

(defonce ^:private !live-map (atom nil))

(rf/reg-fx
  :map/fly-to
  (fn [position]
    (when-let [m @!live-map]
      (maplibre/fly-to! m position))))

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
        !ring      (atom nil)
        !emergency (atom nil)
        !raw-style (atom nil)
        !unwatch   (atom nil)
        !fold-timer (atom nil)
        !disposed  (atom false)]
    (letfn [(mount-map! [th]
              (let [style (basemap/edition-style @!raw-style th)
                    m     (maplibre/create! @!container (default-map-opts style))]
                (reset! !map m)
                (reset! !live-map m)          ; the camera effect's handle
                ;; The credit shows, and folds five seconds later — the timeout
                ;; the OSMF guidelines name (see attribution-fold-ms). MapLibre
                ;; folds it on the first pan or zoom too, which is the other
                ;; permitted trigger; whichever comes first wins, and both leave
                ;; the (i) button reachable forever after.
                ;;
                ;; Armed on every mount: a theme flip destroys and re-creates the
                ;; map, and the new map arrives with a freshly opened credit.
                (reset! !fold-timer
                        (js/setTimeout #(when-not @!disposed
                                          (collapse-attribution! @!container))
                                       attribution-fold-ms))
                (reset! !aircraft (aircraft-layer/attach! m th))
                ;; The selection ring rides the same lifecycle: ring and
                ;; map are created and torn down together (adsb.map.selection).
                (reset! !ring (selection/attach! m))
                ;; So do the §7 emergency annotations — the red-pen
                ;; ellipse, MAYDAY stamp, and edge arrow (adsb.map.emergency).
                (reset! !emergency (emergency/attach! m))))
            (unmount-map! []
              ;; A pending fold belongs to the map being torn down; left armed,
              ;; it would fire into the next one's DOM (or none at all).
              (when-let [timer @!fold-timer]
                (js/clearTimeout timer)
                (reset! !fold-timer nil))
              (when-let [annotations @!emergency]
                (emergency/detach! @!map annotations)
                (reset! !emergency nil))
              (when-let [ring @!ring]
                (selection/detach! @!map ring)
                (reset! !ring nil))
              (when-let [layer @!aircraft]
                (aircraft-layer/detach! layer)
                (reset! !aircraft nil))
              (when-let [m @!map]
                (maplibre/destroy! m)
                (reset! !map nil)
                (reset! !live-map nil)))
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
