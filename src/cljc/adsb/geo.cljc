(ns adsb.geo
  "Pure geodesy — bearing, distance, destination, circles, bounds, and the
   viewport edge annotation. No I/O, no clock, no aircraft. GeoJSON
   presentation lives in adsb.geojson."
  (:require [clojure.math :as math]))

(def ^:const earth-radius-m 6371000)
(def ^:const meters-per-km 1000)
(def ^:const meters-per-nm 1852)
(def ^:const seconds-per-hour 3600)

(defn- square [x] (* x x))

(defn normalize-lon-deg
  "Wrap a longitude in degrees into the half-open range [-180, 180).
   NOTE: adsb.ingest.cpr keeps its own copy to stay dependency-free spec math."
  [lon]
  (-> lon (+ 540.0) (mod 360.0) (- 180.0)))

(defn bearing [from to]
  (let [lat1 (math/to-radians (:geo/lat from))
        lat2 (math/to-radians (:geo/lat to))
        dlon (math/to-radians (- (:geo/lon to) (:geo/lon from)))
        y    (* (math/sin dlon) (math/cos lat2))
        x    (- (* (math/cos lat1) (math/sin lat2))
                (* (math/sin lat1) (math/cos lat2) (math/cos dlon)))]
    (mod (+ (math/to-degrees (math/atan2 y x)) 360) 360)))

(defn distance [from to]
  (let [lat1 (math/to-radians (:geo/lat from))
        lat2 (math/to-radians (:geo/lat to))
        dlat (math/to-radians (- (:geo/lat to) (:geo/lat from)))
        dlon (math/to-radians (- (:geo/lon to) (:geo/lon from)))
        a    (+ (square (math/sin (/ dlat 2)))
                (* (math/cos lat1) (math/cos lat2)
                   (square (math/sin (/ dlon 2)))))]
    (* earth-radius-m 2 (math/atan2 (math/sqrt a) (math/sqrt (- 1 a))))))

(defn meters->km [m] (/ m meters-per-km))
(defn meters->nm [m] (/ m meters-per-nm))

(defn knots->mps [knots]
  (/ (* knots meters-per-nm) seconds-per-hour))

(defn destination [from bearing-deg distance-m]
  (let [lat1    (math/to-radians (:geo/lat from))
        lon1    (math/to-radians (:geo/lon from))
        bearing (math/to-radians bearing-deg)
        angular (/ distance-m earth-radius-m)
        lat2    (math/asin (+ (* (math/sin lat1) (math/cos angular))
                              (* (math/cos lat1) (math/sin angular)
                                 (math/cos bearing))))
        lon2    (+ lon1
                   (math/atan2 (* (math/sin bearing) (math/sin angular)
                                  (math/cos lat1))
                               (- (math/cos angular)
                                  (* (math/sin lat1) (math/sin lat2)))))]
    {:geo/lat (math/to-degrees lat2)
     :geo/lon (normalize-lon-deg (math/to-degrees lon2))}))

(def ^:const default-circle-segments 128)

(defn circle
  ([center radius-m] (circle center radius-m default-circle-segments))
  ([center radius-m segments]
   (let [ring (mapv #(destination center (* % (/ 360 segments)) radius-m)
                    (range segments))]
     (conj ring (first ring)))))

(defn bounds [positions]
  (when (seq positions)
    (let [lats (map :geo/lat positions)
          lons (map :geo/lon positions)]
      {:geo/min-lat (reduce min lats)
       :geo/max-lat (reduce max lats)
       :geo/min-lon (reduce min lons)
       :geo/max-lon (reduce max lons)})))

(defn- lon-near [lon reference-lon]
  (let [offset (- lon reference-lon)]
    (cond
      (> offset 180) (recur (- lon 360) reference-lon)
      (< offset -180) (recur (+ lon 360) reference-lon)
      :else lon)))

(defn- clamp01 [x] (-> x (max 0.0) (min 1.0)))

(defn edge-annotation [{:geo/keys [min-lat max-lat min-lon max-lon]} {:geo/keys [lat lon]}]
  (let [lat-span (- max-lat min-lat)
        lon-span (- max-lon min-lon)]
    (when (and (pos? lat-span) (pos? lon-span))
      (let [centre {:geo/lat (+ min-lat (/ lat-span 2))
                    :geo/lon (+ min-lon (/ lon-span 2))}
            lon*   (lon-near lon (:geo/lon centre))
            x      (/ (- lon* min-lon) lon-span)
            y      (/ (- max-lat lat) lat-span)]
        (when-not (and (<= 0 x 1) (<= 0 y 1))
          (let [dx     (- x 0.5)
                dy     (- y 0.5)
                t      (min (if (zero? dx) ##Inf (/ 0.5 (abs dx)))
                            (if (zero? dy) ##Inf (/ 0.5 (abs dy))))
                target {:geo/lat lat :geo/lon lon*}]
            {:edge/x           (clamp01 (+ 0.5 (* t dx)))
             :edge/y           (clamp01 (+ 0.5 (* t dy)))
             :edge/bearing-deg (bearing centre target)
             :edge/distance-m  (distance centre target)}))))))
