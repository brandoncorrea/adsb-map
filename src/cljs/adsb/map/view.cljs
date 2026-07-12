(ns adsb.map.view
  "The map, mounted and alive — a full-viewport MapLibre canvas. This
  namespace owns the default map options and the Reagent component that
  drives the imperative map through the `adsb.map.maplibre` seam, and it
  wires the aircraft layer's lifecycle (adsb.map.aircraft-layer) to the
  component's own: attached on mount, detached on unmount. The aircraft
  DATA never passes through here — the layer reads the picture outside
  React entirely."
  (:require
    [adsb.map.aircraft-layer :as aircraft-layer]
    [adsb.map.maplibre :as maplibre]
    [reagent.core :as r]))

;; Basemap: OpenFreeMap's "liberty" style — the production basemap (adsb-kh4.5).
;; The PROVIDER is settled here; adsb-dgb.5's visual pass may swap the VARIANT
;; (liberty -> bright / positron / dark) by editing the URL below.
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
;;     rendered — the richly-rendered direction the design questionnaire leans.
;;   * MapLibre renders the style JSON's own attribution automatically via the
;;     attribution control (enabled below): "OpenFreeMap © OpenMapTiles Data
;;     from OpenStreetMap" — the required credit, carried by the style itself.
;; See README "Basemap" and https://openfreemap.org for the fair-use terms.
(def ^:const style-url "https://tiles.openfreemap.org/styles/liberty")

;; PRIVACY — non-negotiable (adsb-2yu.1, per the Overseer). The default center
;; is a FIXED, whole-degree-rounded point over the Tampa Bay / Florida Gulf
;; coast coverage area. It MUST NEVER be set to the receiver's position: a
;; rounded regional center reveals a region, not a rooftop.
;; MapLibre wants [lon lat]; aviation reads lat,lon. Here: lat 28.0, lon -82.0.
(def ^:const default-center [-82.0 28.0])
(def ^:const default-zoom 7)

(defn default-map-opts
  "The MapLibre map options the shell boots with. Pure — the test asserts
  against this directly, and the fake `create!` receives exactly this."
  []
  {:style              style-url
   :center             default-center
   :zoom               default-zoom
   ;; Attribution is required and never hidden — the basemap must credit
   ;; OpenFreeMap / OpenMapTiles / OpenStreetMap. The style JSON carries the
   ;; text; enabling the control is what makes MapLibre render it.
   :attributionControl true})

(defn map-container
  "The map's DOM anchor. Named (not an inline anonymous fn in the render
  path) so the zero-re-render proof in adsb.map.aircraft-layer-test can
  count the map component's renders with a redef."
  [!container]
  [:div.adsb-map {:ref #(reset! !container %)}])

(defn map-view
  "Full-viewport MapLibre canvas. A form-3 component: it owns a plain DOM node
  (the container is not reactive — no ratom) and drives the imperative map
  through the seam. The map is created on mount and destroyed on unmount; the
  aircraft layer is attached and detached alongside it. This component derefs
  no subscription and NEVER re-renders on aircraft traffic — the layer pushes
  picture changes straight into MapLibre, outside React."
  []
  (let [!container (atom nil)
        !map       (atom nil)
        !aircraft  (atom nil)]
    (r/create-class
      {:display-name "adsb-map"

       :component-did-mount
       (fn [_this]
         (let [m (maplibre/create! @!container (default-map-opts))]
           (reset! !map m)
           (reset! !aircraft (aircraft-layer/attach! m))))

       :component-will-unmount
       (fn [_this]
         (when-let [layer @!aircraft]
           (aircraft-layer/detach! layer)
           (reset! !aircraft nil))
         (when-let [m @!map]
           (maplibre/destroy! m)
           (reset! !map nil)))

       :reagent-render
       (fn []
         (map-container !container))})))
