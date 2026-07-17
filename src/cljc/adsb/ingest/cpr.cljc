(ns adsb.ingest.cpr
  (:require [clojure.math :as math]))

;; Compact Position Reporting, per DO-260B §A.1.7 / ICAO Annex 10 Vol IV.
(def ^:const ^:private cpr-scale 131072.0)  ; 2^17 — the fields are 17-bit fractions
(def ^:const ^:private even-zone-count 60)
(def ^:const ^:private odd-zone-count 59)

;; NZ, the number of latitude zones.
(def ^:private nz 15)

(defn nl [lat]
  ;; NL(lat), the longitude-zone count for a latitude — the spec's closed
  ;; form, with the table's edge cases pinned at the equator and the poles.
  (let [lat (abs (double lat))]
    (cond
      (zero? lat) 59
      (== lat 87) 2
      (> lat 87) 1
      :else
      (let [zone-height (- 1 (math/cos (/ math/PI (* 2 nz))))
            lat-squash  (let [c (math/cos (* (/ math/PI 180) lat))]
                          (* c c))]
        (-> (/ (* 2 math/PI)
               (math/acos (- 1 (/ zone-height lat-squash))))
            math/floor
            long)))))

(defn- zone-count [parity]
  (if (= :odd parity)
    odd-zone-count
    even-zone-count))

;; Duplicated with adsb.geo/normalize-lon-deg — kept local so this CPR spec
;; math stays dependency-free (cpr does not require geo).
(defn- normalize-lon [lon]
  (-> lon
      (+ 540.0)
      (mod 360.0)
      (- 180.0)))

(defn- lat-in-range? [lat] (<= -90.0 lat 90.0))

(defn- global-lat [parity j cpr-lat-fraction]
  (let [zones (zone-count parity)
        lat   (* (/ 360.0 zones) (+ (mod j zones) cpr-lat-fraction))]
    (cond-> lat
            ;; Southern-hemisphere unwrap: the spec's decode lands southern
            ;; latitudes in [270, 360).
            (>= lat 270.0)
            (- 360.0))))

(defn global-position [even odd]
  ;; The global (unambiguous) decode from an even/odd pair — the j and m
  ;; zone-index computations are straight from the spec's algorithm; don't
  ;; re-derive them from this code.
  (let [lat-fraction-even (/ (:cpr/lat even) cpr-scale)
        lat-fraction-odd  (/ (:cpr/lat odd) cpr-scale)
        j                 (math/floor
                            (+ 0.5
                               (- (* odd-zone-count lat-fraction-even)
                                  (* even-zone-count lat-fraction-odd))))
        lat-even          (global-lat :even j lat-fraction-even)
        lat-odd           (global-lat :odd j lat-fraction-odd)]
    (when (and (lat-in-range? lat-even)
               (lat-in-range? lat-odd)
               (= (nl lat-even) (nl lat-odd)))
      (let [odd-newest?       (> (:cpr/heard-at-ms odd)
                                 (:cpr/heard-at-ms even))
            lat               (if odd-newest? lat-odd lat-even)
            zones             (nl lat)
            lon-fraction-even (/ (:cpr/lon even) cpr-scale)
            lon-fraction-odd  (/ (:cpr/lon odd) cpr-scale)
            m                 (math/floor
                                (+ 0.5
                                   (- (* lon-fraction-even (dec zones))
                                      (* lon-fraction-odd zones))))
            n                 (max 1 (if odd-newest? (dec zones) zones))
            lon-fraction      (if odd-newest?
                                lon-fraction-odd
                                lon-fraction-even)
            lon               (* (/ 360.0 n) (+ (mod m n) lon-fraction))]
        {:geo/lat lat
         :geo/lon (normalize-lon lon)}))))

(defn local-position [{:cpr/keys [parity] :as half} reference]
  (let [zones        (zone-count parity)
        zone-height  (/ 360.0 zones)
        lat-fraction (/ (:cpr/lat half) cpr-scale)
        ref-lat      (:geo/lat reference)
        j            (+ (math/floor (/ ref-lat zone-height))
                        (math/floor
                          (+ 0.5
                             (- (/ (mod ref-lat zone-height) zone-height)
                                lat-fraction))))
        lat          (* zone-height (+ j lat-fraction))]
    (when (lat-in-range? lat)
      (let [n            (max 1 (- (nl lat) (if (= :odd parity) 1 0)))
            zone-width   (/ 360.0 n)
            lon-fraction (/ (:cpr/lon half) cpr-scale)
            ref-lon      (:geo/lon reference)
            m            (+ (math/floor (/ ref-lon zone-width))
                            (math/floor
                              (+ 0.5
                                 (- (/ (mod ref-lon zone-width) zone-width)
                                    lon-fraction))))]
        {:geo/lat lat
         :geo/lon (normalize-lon (* zone-width (+ m lon-fraction)))}))))
