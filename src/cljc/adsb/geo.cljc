(ns adsb.geo
  "Pure shared geo math and GeoJSON conversion. No MapLibre, no I/O, no
  clock — time is an argument. Runs identically on the JVM and in the
  browser, so the same code can validate a fixture in a `clojure.test`
  run and feed the live map.

  Positions are the domain's `{:geo/lat _ :geo/lon _}` maps. Distances
  are METERS; `meters->km` / `meters->nm` convert. Bearings are degrees
  clockwise from true north, in [0, 360)."
  (:require [adsb.aircraft :as aircraft]))

;; ---------------------------------------------------------------------
;; Constants

(def ^:const earth-radius-m 6371000)   ; mean radius, the haversine R
(def ^:const meters-per-km 1000)
(def ^:const meters-per-nm 1852)       ; the nautical mile is exactly this

;; ---------------------------------------------------------------------
;; Great-circle math
;;
;; Math/* resolves to java.lang.Math on the JVM and js/Math in the
;; browser, so these need no reader conditional. Math/toRadians is
;; JVM-only, though, so degrees<->radians are done by hand.

(defn- deg->rad [deg] (* deg (/ Math/PI 180)))

(defn- rad->deg [rad] (* rad (/ 180 Math/PI)))

(defn- square [x] (* x x))

(defn bearing
  "Initial great-circle bearing from `from` to `to`, in degrees clockwise
  from true north, normalized to [0, 360). The bearing of a great-circle
  path changes along its length; this is the heading at the start."
  [from to]
  (let [lat1 (deg->rad (:geo/lat from))
        lat2 (deg->rad (:geo/lat to))
        dlon (deg->rad (- (:geo/lon to) (:geo/lon from)))
        y    (* (Math/sin dlon) (Math/cos lat2))
        x    (- (* (Math/cos lat1) (Math/sin lat2))
                (* (Math/sin lat1) (Math/cos lat2) (Math/cos dlon)))]
    (mod (+ (rad->deg (Math/atan2 y x)) 360) 360)))

(defn distance
  "Great-circle (haversine) distance between `from` and `to`, in METERS.
  Use `meters->km` / `meters->nm` at the call site for other units."
  [from to]
  (let [lat1 (deg->rad (:geo/lat from))
        lat2 (deg->rad (:geo/lat to))
        dlat (deg->rad (- (:geo/lat to) (:geo/lat from)))
        dlon (deg->rad (- (:geo/lon to) (:geo/lon from)))
        a    (+ (square (Math/sin (/ dlat 2)))
                (* (Math/cos lat1) (Math/cos lat2)
                   (square (Math/sin (/ dlon 2)))))]
    (* earth-radius-m 2 (Math/atan2 (Math/sqrt a) (Math/sqrt (- 1 a))))))

(defn meters->km [m] (/ m meters-per-km))

(defn meters->nm [m] (/ m meters-per-nm))

(defn bounds
  "The bounding box enclosing a seq of `{:geo/lat _ :geo/lon _}`
  positions, as `{:geo/min-lat _ :geo/max-lat _ :geo/min-lon _
  :geo/max-lon _}`. nil for an empty seq — there is no box around
  nothing. A single position yields a degenerate (zero-area) box."
  [positions]
  (when (seq positions)
    (let [lats (map :geo/lat positions)
          lons (map :geo/lon positions)]
      {:geo/min-lat (reduce min lats)
       :geo/max-lat (reduce max lats)
       :geo/min-lon (reduce min lons)
       :geo/max-lon (reduce max lons)})))

;; ---------------------------------------------------------------------
;; Emergency squawks
;;
;; NOTE: 7500 hijack, 7600 radio failure, 7700 general emergency. This is
;; the minimal squawk logic geo needs to set an `emergency` property;
;; adsb-dgb.4 owns promoting a shared `emergency?` predicate into
;; adsb.aircraft, at which point this should delegate to it.

(def ^:const emergency-squawks #{"7500" "7600" "7700"})

(defn emergency?
  "True when the aircraft is squawking a distress code."
  [{:aircraft/keys [squawk]}]
  (boolean (emergency-squawks squawk)))

;; ---------------------------------------------------------------------
;; Domain aircraft -> GeoJSON
;;
;; Feature `:properties` keys are simple, UNqualified keywords. clj->js
;; drops keyword namespaces, so a namespaced key would land on the JS
;; object under its bare name anyway (and two namespaces could collide);
;; naming them plainly here makes the MapLibre style contract explicit —
;; `["get" "icao"]`, `["get" "altitude"]`, and so on. Absent facts are
;; OMITTED, never defaulted: a missing altitude must not read as 0, and a
;; missing track must not read as due north.

(def ^:const ground-altitude
  "The `:altitude` property for an aircraft on the tarmac — distinct from
  a numeric altitude and distinct from an absent one."
  "ground")

(defn- stale-property
  "Staleness as a boolean, judged against wall-clock `now-ms`. nil (and so
  omitted) when the aircraft carries no receive time to judge against."
  [aircraft now-ms]
  (when (:aircraft/seen-at-ms aircraft)
    (aircraft/stale? aircraft now-ms)))

(defn- feature-properties [aircraft now-ms]
  (let [{:aircraft/keys [icao callsign track-deg altitude-ft on-ground?]}
        aircraft
        stale (stale-property aircraft now-ms)]
    (cond-> {:icao      icao
             :emergency (emergency? aircraft)}
      callsign      (assoc :callsign callsign)
      track-deg     (assoc :track track-deg)
      on-ground?    (assoc :altitude ground-altitude)
      altitude-ft   (assoc :altitude altitude-ft)
      (some? stale) (assoc :stale stale))))

(defn aircraft->feature
  "A domain aircraft as a GeoJSON Point Feature, or nil when it has no
  position — there is nothing to place on the map. `now-ms` is the wall
  clock the `stale` property is judged against. GeoJSON coordinates are
  [lon lat], not [lat lon]."
  [aircraft now-ms]
  (when-let [{:geo/keys [lat lon]} (:aircraft/position aircraft)]
    {:type       "Feature"
     :geometry   {:type "Point" :coordinates [lon lat]}
     :properties (feature-properties aircraft now-ms)}))

(defn aircraft-picture->feature-collection
  "A seq of domain aircraft as a GeoJSON FeatureCollection. Aircraft with
  no position contribute no feature, so the feature count is the
  positioned count — never the total. Plain data, ready for clj->js."
  [aircraft-picture now-ms]
  {:type     "FeatureCollection"
   :features (into [] (keep #(aircraft->feature % now-ms)) aircraft-picture)})
