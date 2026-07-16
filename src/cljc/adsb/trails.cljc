(ns adsb.trails)

(def ^:const max-positions 60)

(defn append-position [ring position]
  (let [ring (or ring [])]
    (if (= position (peek ring))
      ring
      (let [grown (conj ring position)
            n     (count grown)]
        (if (> n max-positions)
          (into [] (subvec grown (- n max-positions)))
          grown)))))

(defn accumulate [history picture]
  (reduce
    (fn [acc ac]
      (let [icao (:aircraft/icao ac)
            ring (get history icao)]
        (if-let [position (:aircraft/position ac)]
          (assoc acc icao (append-position ring position))
          (cond-> acc ring (assoc icao ring)))))
    {}
    picture))

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
