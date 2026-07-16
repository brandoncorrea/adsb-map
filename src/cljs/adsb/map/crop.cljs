(ns adsb.map.crop
  (:require [adsb.geo :as geo]
            [adsb.map.maplibre :as maplibre]
            [adsb.map.style :as style]
            [adsb.map.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def ^:const source-id "coverage-boundary")
(def ^:const layer-id "coverage-boundary")

(def empty-feature-collection {:type "FeatureCollection" :features []})
(def source-spec {:type "geojson" :data empty-feature-collection})
(defn layer-spec [theme] (style/crop-layer-spec theme layer-id source-id))
(def ^:const frame-padding-px 40)

(defn crop-bounds [{:crop/keys [center radius-m]}]
  (when (and center radius-m)
    (geo/bounds (geo/circle center radius-m))))

(defn crop->feature-collection [{:crop/keys [center radius-m]}]
  (if (and center radius-m)
    {:type     "FeatureCollection"
     :features [{:type       "Feature"
                 :properties {}
                 :geometry   {:type        "Polygon"
                              :coordinates [(->> (geo/circle center radius-m)
                                                 (mapv (juxt :geo/lon :geo/lat)))]}}]}
    empty-feature-collection))

(defn- frame-once! [m !state crop]
  (when-let [box (and (not (:framed? @!state)) (crop-bounds crop))]
    (swap! !state assoc :framed? true)
    (maplibre/fit-bounds! m box frame-padding-px)
    true))

(defn attach!
  ([m] (attach! m @theme/!theme))
  ([m theme] (attach! m theme nil))
  ([m theme {:keys [framed?]}]
   (let [!state (atom {:framed? framed?})]
     (maplibre/on-load!
       m
       (fn []
         (when-not (:disposed? @!state)
           (maplibre/add-source! m source-id source-spec)
           (maplibre/add-layer! m (layer-spec theme))
           (swap! !state assoc :track
                  (r/track!
                    (fn []
                      (let [crop @(rf/subscribe [:crop/declared])]
                        (maplibre/set-source-data!
                          m source-id
                          (crop->feature-collection crop))
                        (frame-once! m !state crop))))))))
     !state)))

(defn detach! [!state]
  (swap! !state assoc :disposed? true)
  (some-> (:track @!state) r/dispose!)
  (swap! !state dissoc :track))
