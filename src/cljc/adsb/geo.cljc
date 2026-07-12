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
(def ^:const seconds-per-hour 3600)
(def ^:const millis-per-second 1000)

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

(defn knots->mps
  "Knots (nautical miles per hour) as meters per second."
  [knots]
  (/ (* knots meters-per-nm) seconds-per-hour))

(defn destination
  "The position `distance-m` meters from `from` along the great circle
  leaving on initial bearing `bearing-deg` (degrees clockwise from true
  north) — the standard spherical destination formula, the inverse of
  `distance` + `bearing`. Longitude is normalized to [-180, 180)."
  [from bearing-deg distance-m]
  (let [lat1    (deg->rad (:geo/lat from))
        lon1    (deg->rad (:geo/lon from))
        bearing (deg->rad bearing-deg)
        angular (/ distance-m earth-radius-m)
        lat2    (Math/asin (+ (* (Math/sin lat1) (Math/cos angular))
                              (* (Math/cos lat1) (Math/sin angular)
                                 (Math/cos bearing))))
        lon2    (+ lon1
                   (Math/atan2 (* (Math/sin bearing) (Math/sin angular)
                                  (Math/cos lat1))
                               (- (Math/cos angular)
                                  (* (Math/sin lat1) (Math/sin lat2)))))]
    {:geo/lat (rad->deg lat2)
     :geo/lon (-> (rad->deg lon2) (+ 540) (mod 360) (- 180))}))

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
;; Dead reckoning — projecting an aircraft between real frames
;;
;; Positions arrive at ~1 Hz; the map draws at ~60. Between real frames
;; the imperative aircraft layer projects each aircraft forward along
;; its reported track at its reported ground speed, measured from its
;; LAST REAL observation instant — pure math over reported facts, never
;; a substitute for them: the next real frame resets the base. The
;; projection is honest only while the aircraft is fresh: past the
;; shared stale threshold it holds its last real position, because a
;; silent plane gliding forever is a lie.

(defn projectable?
  "True when dead reckoning may honestly move this aircraft at `now-ms`:
  it has a position to project from, an observation instant to measure
  elapsed time from, BOTH ground speed and track (absent is not zero —
  a missing vector grounds the projection), and it is not yet stale
  (adsb.aircraft/stale-threshold-ms, the shared domain line)."
  [{:aircraft/keys [position seen-at-ms ground-speed-kt track-deg]
    :as aircraft}
   now-ms]
  (boolean
    (and position seen-at-ms ground-speed-kt track-deg
         (not (aircraft/stale? aircraft now-ms)))))

(defn project-aircraft
  "The aircraft with its position dead-reckoned forward to `now-ms` —
  along `:aircraft/track-deg` at `:aircraft/ground-speed-kt` from its
  last real observation (`:aircraft/seen-at-ms`). Returned unchanged
  when projection would not be honest (`projectable?`), and a `now-ms`
  before the observation projects nowhere rather than backward."
  [aircraft now-ms]
  (if (projectable? aircraft now-ms)
    (let [{:aircraft/keys [position seen-at-ms ground-speed-kt track-deg]}
          aircraft
          elapsed-s  (/ (max 0 (- now-ms seen-at-ms)) millis-per-second)
          distance-m (* (knots->mps ground-speed-kt) elapsed-s)]
      (assoc aircraft
             :aircraft/position (destination position track-deg distance-m)))
    aircraft))

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
  omitted) when the aircraft carries no receive time to judge against.
  The boolean marks the crossing of the stale line; the continuous fade
  rides `age-property`."
  [aircraft now-ms]
  (when (:aircraft/seen-at-ms aircraft)
    (aircraft/stale? aircraft now-ms)))

(defn- age-property
  "Seconds of silence since the aircraft was last heard, judged against
  wall-clock `now-ms` — the continuous age the opacity ramp interpolates
  smoothly from fresh through the stale line to the age-out line. nil
  (and so omitted) when there is no receive time to measure from. The
  thresholds that bound the fade live in adsb.aircraft; this is the raw
  elapsed time, judged, never a threshold."
  [aircraft now-ms]
  (when-let [seen-at-ms (:aircraft/seen-at-ms aircraft)]
    (/ (- now-ms seen-at-ms) millis-per-second)))

(defn- feature-properties [aircraft now-ms]
  (let [{:aircraft/keys [icao callsign track-deg altitude-ft on-ground?
                         mlat?]}
        aircraft
        stale (stale-property aircraft now-ms)
        age   (age-property aircraft now-ms)]
    (cond-> {:icao      icao
             ;; The emergency predicate now lives in the domain
             ;; (adsb.aircraft); the boolean feature property it feeds the
             ;; style layer is unchanged.
             :emergency (aircraft/emergency? aircraft)}
      callsign      (assoc :callsign callsign)
      track-deg     (assoc :track track-deg)
      on-ground?    (assoc :altitude ground-altitude)
      altitude-ft   (assoc :altitude altitude-ft)
      (some? stale) (assoc :stale stale)
      (some? age)   (assoc :age-s age)
      ;; Multilateration is lower-confidence than self-reported ADS-B; the
      ;; style layer demotes it visually. Omitted when absent — a plain
      ;; ADS-B target carries no :mlat, not `false`.
      mlat?         (assoc :mlat true))))

(defn aircraft->feature
  "A domain aircraft as a GeoJSON Point Feature, or nil when it has no
  position — there is nothing to place on the map. `now-ms` is the wall
  clock the `stale`/`age-s` properties are judged against. GeoJSON
  coordinates are [lon lat], not [lat lon]."
  [aircraft now-ms]
  (when-let [{:geo/keys [lat lon]} (:aircraft/position aircraft)]
    {:type       "Feature"
     :geometry   {:type "Point" :coordinates [lon lat]}
     :properties (feature-properties aircraft now-ms)}))

(defn- present?
  "True when the aircraft still belongs on the map — not yet aged out.
  An aircraft with no receive time cannot be judged silent, so it is
  kept: the same guard `stale-property` makes, so an un-timed aircraft
  is never spuriously removed. This is the client's between-frame safety
  net — the server ages aircraft out of its picture, but between frames
  (or during a stream stall) the client keeps aging and drops anything
  past the shared age-out line, judged with adsb.aircraft/aged-out?."
  [aircraft now-ms]
  (not (and (:aircraft/seen-at-ms aircraft)
            (aircraft/aged-out? aircraft now-ms))))

(defn aircraft-picture->feature-collection
  "A seq of domain aircraft as a GeoJSON FeatureCollection. Aircraft with
  no position contribute no feature, and aircraft silent past the
  age-out line contribute none either — they have left the picture even
  if a stalled stream has not yet said so. So the feature count is the
  positioned, still-present count — never the total. Plain data, ready
  for clj->js."
  [aircraft-picture now-ms]
  {:type     "FeatureCollection"
   :features (into []
                   (comp (filter #(present? % now-ms))
                         (keep #(aircraft->feature % now-ms)))
                   aircraft-picture)})
