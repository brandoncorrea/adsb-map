(ns adsb.geojson
  "GeoJSON presentation for the MapLibre layers — aircraft point features and
   trail line features. This is the map's view of the domain: staleness,
   emergency, age, and mlat styling live here, not in the pure geodesy of
   adsb.geo. The aircraft layer feeds these FeatureCollections straight to a
   GeoJSON source via setData; see adsb.map.aircraft-layer."
  (:require [adsb.aircraft :as aircraft]))

(def ^:const millis-per-second 1000)
(def ^:const ground-altitude "ground")

(defn- stale-property [aircraft now-ms]
  (when (:aircraft/seen-at-ms aircraft)
    (aircraft/stale? aircraft now-ms)))

(defn- age-property [aircraft now-ms]
  (when-let [seen-at-ms (:aircraft/seen-at-ms aircraft)]
    (/ (- now-ms seen-at-ms) millis-per-second)))

(defn- feature-properties [aircraft now-ms]
  (let [{:aircraft/keys [icao callsign track-deg altitude-ft on-ground? category mlat?]} aircraft
        stale (stale-property aircraft now-ms)
        age   (age-property aircraft now-ms)]
    (cond-> {:icao      icao
             :emergency (aircraft/emergency? aircraft)}
            callsign (assoc :callsign callsign)
            track-deg (assoc :track track-deg)
            category (assoc :category category)
            on-ground? (assoc :altitude ground-altitude)
            altitude-ft (assoc :altitude altitude-ft)
            (some? stale) (assoc :stale stale)
            (some? age) (assoc :age-s age)
            mlat? (assoc :mlat true))))

(defn aircraft->feature [aircraft now-ms]
  (when-let [{:geo/keys [lat lon]} (:aircraft/position aircraft)]
    {:type       "Feature"
     :geometry   {:type "Point" :coordinates [lon lat]}
     :properties (feature-properties aircraft now-ms)}))

(defn- present? [aircraft now-ms]
  (not (and (:aircraft/seen-at-ms aircraft)
            (aircraft/aged-out? aircraft now-ms))))

(defn aircraft-picture->feature-collection [aircraft-picture now-ms]
  {:type     "FeatureCollection"
   :features (->> aircraft-picture
                  (filter #(present? % now-ms))
                  (keep #(aircraft->feature % now-ms))
                  (into []))})

(defn- trail-feature [icao ring]
  {:type       "Feature"
   :properties {:icao icao}
   :geometry   {:type        "LineString"
                :coordinates (mapv (juxt :geo/lon :geo/lat) ring)}})

(defn history->trail-feature-collection [history live-icaos]
  {:type     "FeatureCollection"
   :features (->> history
                  (keep (fn [[icao ring]]
                          (when (and (contains? live-icaos icao)
                                     (>= (count ring) 2))
                            (trail-feature icao ring))))
                  (into []))})
