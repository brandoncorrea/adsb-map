(ns adsb.trails
  "Per-aircraft position history — the ring-buffer domain that accumulates a
   trail across frames. The GeoJSON shaping of these rings into LineString
   features lives in adsb.geojson.")

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
