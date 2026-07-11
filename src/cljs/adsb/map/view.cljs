(ns adsb.map.view
  "The map, mounted and alive — a full-viewport MapLibre canvas and nothing
  more. No aircraft here: the imperative GeoJSON layer is bead adsb-2yu.4.
  This namespace owns the default map options and the Reagent component that
  drives the imperative map through the `adsb.map.maplibre` seam."
  (:require
    [adsb.map.maplibre :as maplibre]
    [reagent.core :as r]))

;; Basemap: MapLibre's public demo tiles — a no-token style, for DEV ONLY.
;; No API key, no vendor secret ever reaches the browser bundle, and the
;; style JSON carries its own OpenStreetMap attribution. Production tiles
;; (a real, styled basemap) are bead adsb-kh4.5 — do not harden this here.
(def ^:const dev-style-url "https://demotiles.maplibre.org/style.json")

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
  {:style              dev-style-url
   :center             default-center
   :zoom               default-zoom
   ;; Attribution is required and never hidden — the basemap must credit OSM.
   :attributionControl true})

(defn map-view
  "Full-viewport MapLibre canvas. A form-3 component: it owns a plain DOM node
  (the container is not reactive — no ratom) and drives the imperative map
  through the seam. The map is created on mount and destroyed on unmount."
  []
  (let [!container (atom nil)
        !map       (atom nil)]
    (r/create-class
      {:display-name "adsb-map"

       :component-did-mount
       (fn [_this]
         (reset! !map (maplibre/create! @!container (default-map-opts))))

       :component-will-unmount
       (fn [_this]
         (when-let [m @!map]
           (maplibre/destroy! m)
           (reset! !map nil)))

       :reagent-render
       (fn []
         [:div.adsb-map {:ref #(reset! !container %)}])})))
