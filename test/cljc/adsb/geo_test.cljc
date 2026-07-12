(ns adsb.geo-test
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.fixtures :as fixtures]
    [adsb.geo :as geo]
    ;; The whole-fixture test needs file I/O, so it is JVM-only — as are
    ;; the requires that exist solely to serve it.
    #?@(:clj [[adsb.ingest.coerce :as coerce]
              [cheshire.core :as json]])
    #?(:clj  [clojure.test :refer [deftest testing is]]
       :cljs [cljs.test :refer-macros [deftest testing is]])))

(defn- close?
  "Within `tol` of `expected` — floating-point geo math never lands on a
  literal, so assertions are tolerance-based."
  [expected actual tol]
  (< (abs (- expected actual)) tol))

;; A degree of longitude at the equator, in meters — the haversine of
;; two points one degree apart along the equator (R * pi / 180).
(def ^:private equator-degree-m 111194.9)

;; ---------------------------------------------------------------------
;; Great-circle math against published real-world values

(deftest distance-haversine
  (testing "Cambridge -> Paris matches the published great-circle 404 km
            (the canonical Movable-Type worked example)"
    (is (close? 404300
                (geo/distance {:geo/lat 52.205 :geo/lon 0.119}
                              {:geo/lat 48.857 :geo/lon 2.351})
                2000)))

  (testing "one degree of longitude along the equator is ~111.19 km"
    (is (close? equator-degree-m
                (geo/distance {:geo/lat 0 :geo/lon 0}
                              {:geo/lat 0 :geo/lon 1})
                1)))

  (testing "a point is zero meters from itself"
    (is (zero? (geo/distance {:geo/lat 40.64 :geo/lon -73.78}
                             {:geo/lat 40.64 :geo/lon -73.78}))))

  (testing "meters convert cleanly to km and nautical miles"
    (let [m (geo/distance {:geo/lat 0 :geo/lon 0}
                          {:geo/lat 0 :geo/lon 1})]
      (is (close? 111.19 (geo/meters->km m) 0.01))
      (is (close? 60.04 (geo/meters->nm m) 0.01)))))

(deftest bearing-initial
  (testing "Cambridge -> Paris matches the published initial bearing 156°"
    (is (close? 156.2
                (geo/bearing {:geo/lat 52.205 :geo/lon 0.119}
                             {:geo/lat 48.857 :geo/lon 2.351})
                0.5)))

  (testing "the four cardinal directions from the origin, normalized to
            [0, 360)"
    (let [origin {:geo/lat 0 :geo/lon 0}]
      (is (close? 0   (geo/bearing origin {:geo/lat 1 :geo/lon 0}) 0.01))
      (is (close? 90  (geo/bearing origin {:geo/lat 0 :geo/lon 1}) 0.01))
      (is (close? 180 (geo/bearing origin {:geo/lat -1 :geo/lon 0}) 0.01))
      (is (close? 270 (geo/bearing origin {:geo/lat 0 :geo/lon -1}) 0.01)))))

;; ---------------------------------------------------------------------
;; Spherical destination — the dead-reckoning primitive

(deftest destination-known-values
  (testing "the canonical Movable-Type worked example: 124.8 km on an
            initial bearing of 96°01′18″ from 53°19′14″N 1°43′47″W lands
            at 53°11′18″N 0°08′00″E"
    (let [reached (geo/destination {:geo/lat 53.320556 :geo/lon -1.729722}
                                   96.021667
                                   124800)]
      (is (close? 53.18833 (:geo/lat reached) 0.001))
      (is (close? 0.13333 (:geo/lon reached) 0.001))))

  (testing "one equator-degree of meters due north or east of the origin
            is one degree of latitude or longitude"
    (let [origin {:geo/lat 0 :geo/lon 0}
          north  (geo/destination origin 0 equator-degree-m)
          east   (geo/destination origin 90 equator-degree-m)]
      (is (close? 1 (:geo/lat north) 1e-6))
      (is (close? 0 (:geo/lon north) 1e-6))
      (is (close? 0 (:geo/lat east) 1e-6))
      (is (close? 1 (:geo/lon east) 1e-6))))

  (testing "zero distance goes nowhere"
    (let [from {:geo/lat 27.961166 :geo/lon -83.975953}]
      (is (close? 0 (geo/distance from (geo/destination from 97.14 0))
                  0.001))))

  (testing "destination inverts distance + bearing: going the measured
            distance on the measured bearing reaches the measured point"
    (let [from    {:geo/lat 52.205 :geo/lon 0.119}
          to      {:geo/lat 48.857 :geo/lon 2.351}
          reached (geo/destination from
                                   (geo/bearing from to)
                                   (geo/distance from to))]
      (is (close? (:geo/lat to) (:geo/lat reached) 1e-6))
      (is (close? (:geo/lon to) (:geo/lon reached) 1e-6))))

  (testing "crossing the antimeridian normalizes longitude to [-180, 180)"
    (let [reached (geo/destination {:geo/lat 0 :geo/lon 179.5}
                                   90
                                   equator-degree-m)]
      (is (close? -179.5 (:geo/lon reached) 1e-6)))))

(deftest knots-to-meters-per-second
  (testing "one knot is one nautical mile per hour"
    (is (close? 0.514444 (geo/knots->mps 1) 1e-4))
    (is (close? 231.757 (geo/knots->mps 450.5) 0.01))
    (is (zero? (geo/knots->mps 0)))))

;; ---------------------------------------------------------------------
;; Dead reckoning — pure projection between real frames

(def dead-reckoner
  "A positioned aircraft with everything projection needs: observed at
  t=0, due east from the origin at 360 kt — a tidy 1852 m every 10 s."
  {:aircraft/icao "abc0e4"
   :aircraft/position {:geo/lat 0 :geo/lon 0}
   :aircraft/ground-speed-kt 360
   :aircraft/track-deg 90
   :aircraft/seen-at-ms 0})

(deftest project-aircraft-moves-along-track-at-ground-speed
  (testing "10 s at 360 kt due east covers one nautical mile, on heading"
    (let [projected (geo/project-aircraft dead-reckoner 10000)
          from      (:aircraft/position dead-reckoner)
          to        (:aircraft/position projected)]
      (is (close? geo/meters-per-nm (geo/distance from to) 0.01))
      (is (close? 90 (geo/bearing from to) 0.01))
      (is (close? 0 (:geo/lat to) 1e-6))))

  (testing "the cruising cast member one second on: ~231.8 m along its
            97.14° track — the real per-frame glide"
    (let [heard     (assoc fixtures/ups-2717 :aircraft/seen-at-ms 0)
          projected (geo/project-aircraft heard 1000)
          from      (:aircraft/position heard)
          to        (:aircraft/position projected)]
      (is (close? 231.757 (geo/distance from to) 0.5))
      (is (close? 97.14 (geo/bearing from to) 0.1))))

  (testing "only the position changes — every reported fact rides along
            untouched"
    (is (= (dissoc (geo/project-aircraft dead-reckoner 10000)
                   :aircraft/position)
           (dissoc dead-reckoner :aircraft/position)))))

(deftest project-aircraft-holds-what-it-cannot-honestly-move
  (testing "no ground speed, no track, no observation instant, or no
            position: unchanged — absent is not zero"
    (doseq [missing [:aircraft/ground-speed-kt :aircraft/track-deg
                     :aircraft/seen-at-ms :aircraft/position]]
      (let [grounded (dissoc dead-reckoner missing)]
        (is (= grounded (geo/project-aircraft grounded 10000))
            (str missing " absent must project nowhere")))))

  (testing "a stale aircraft holds its last real position — a silent
            plane gliding forever is a lie"
    (let [barely-fresh aircraft/stale-threshold-ms
          barely-stale (inc aircraft/stale-threshold-ms)]
      (is (not= dead-reckoner (geo/project-aircraft dead-reckoner
                                                    barely-fresh))
          "at the stale line it still projects")
      (is (= dead-reckoner (geo/project-aircraft dead-reckoner
                                                 barely-stale))
          "one ms past the stale line it holds")))

  (testing "a now-ms before the observation projects nowhere, never
            backward"
    (let [heard-later (assoc dead-reckoner :aircraft/seen-at-ms 5000)
          projected   (geo/project-aircraft heard-later 1000)]
      (is (close? 0 (geo/distance (:aircraft/position heard-later)
                                  (:aircraft/position projected))
                  0.001)))))

(deftest projectable-honesty
  (let [at-10s 10000]
    (testing "the full-vector, fresh aircraft is projectable"
      (is (true? (geo/projectable? dead-reckoner at-10s))))

    (testing "a zero ground speed is a lawful speed, not an absence"
      (is (true? (geo/projectable?
                   (assoc dead-reckoner :aircraft/ground-speed-kt 0)
                   at-10s))))

    (testing "missing vector components, a missing observation instant,
              a missing position, or staleness ground the projection"
      (doseq [missing [:aircraft/ground-speed-kt :aircraft/track-deg
                       :aircraft/seen-at-ms :aircraft/position]]
        (is (false? (geo/projectable? (dissoc dead-reckoner missing)
                                      at-10s))))
      (is (false? (geo/projectable? dead-reckoner
                                    (inc aircraft/stale-threshold-ms)))))))

;; ---------------------------------------------------------------------
;; Bounds

(deftest bounds-box
  (testing "the box spans the extremes of a seq of positions"
    (is (= {:geo/min-lat 10 :geo/max-lat 40
            :geo/min-lon -20 :geo/max-lon 5}
           (geo/bounds [{:geo/lat 40 :geo/lon 5}
                        {:geo/lat 10 :geo/lon -20}
                        {:geo/lat 25 :geo/lon 0}]))))

  (testing "a single position yields a degenerate, zero-area box"
    (is (= {:geo/min-lat 30 :geo/max-lat 30
            :geo/min-lon 12 :geo/max-lon 12}
           (geo/bounds [{:geo/lat 30 :geo/lon 12}]))))

  (testing "there is no box around nothing"
    (is (nil? (geo/bounds [])))
    (is (nil? (geo/bounds nil)))))

;; ---------------------------------------------------------------------
;; Domain aircraft -> GeoJSON

(def cruising
  "A positioned, airborne aircraft — the common case."
  {:aircraft/icao "abc0e4"
   :aircraft/callsign "UPS2717"
   :aircraft/position {:geo/lat 27.961166 :geo/lon -83.975953}
   :aircraft/altitude-ft 34775
   :aircraft/track-deg 97.14
   :aircraft/squawk "6040"})

(def bare-mode-s
  "A heard-but-never-positioned target — belongs in the sidebar, gets no
  feature."
  {:aircraft/icao "a10202"})

(deftest aircraft->feature-geometry
  (testing "a positioned aircraft becomes a Point Feature with [lon lat]
            coordinates (GeoJSON order, not [lat lon])"
    (let [feature (geo/aircraft->feature cruising 0)]
      (is (= "Feature" (:type feature)))
      (is (= {:type "Point" :coordinates [-83.975953 27.961166]}
             (:geometry feature)))))

  (testing "a never-positioned aircraft produces NO feature"
    (is (nil? (geo/aircraft->feature bare-mode-s 0)))))

(deftest aircraft->feature-properties
  (testing "properties carry what the map styles on — icao, callsign,
            track, altitude, emergency"
    (let [props (:properties (geo/aircraft->feature cruising 0))]
      (is (= "abc0e4" (:icao props)))
      (is (= "UPS2717" (:callsign props)))
      (is (= 97.14 (:track props)))
      (is (= 34775 (:altitude props)))
      (is (false? (:emergency props)))))

  (testing "\"ground\" altitude and absent altitude are DISTINCT
            representations — and absent never serializes as 0"
    (let [ground   (:properties
                     (geo/aircraft->feature
                       (-> cruising
                           (dissoc :aircraft/altitude-ft)
                           (assoc :aircraft/on-ground? true))
                       0))
          airborne (:properties (geo/aircraft->feature cruising 0))
          unknown  (:properties
                     (geo/aircraft->feature
                       (dissoc cruising :aircraft/altitude-ft) 0))]
      (is (= "ground" (:altitude ground)))
      (is (= 34775 (:altitude airborne)))
      ;; Absent must be absent — not 0, not "ground", not nil-in-the-map.
      (is (not (contains? unknown :altitude)))))

  (testing "an absent callsign or track is omitted, never defaulted"
    (let [props (:properties
                  (geo/aircraft->feature
                    (dissoc cruising :aircraft/callsign :aircraft/track-deg)
                    0))]
      (is (not (contains? props :callsign)))
      (is (not (contains? props :track))))))

(deftest emergency-property
  (testing "the three distress squawks each mark the aircraft as an
            emergency"
    (doseq [code ["7500" "7600" "7700"]]
      (is (true? (:emergency
                   (:properties
                     (geo/aircraft->feature
                       (assoc cruising :aircraft/squawk code) 0))))
          (str code " must read as an emergency"))))

  (testing "an ordinary squawk, and an absent one, are not emergencies"
    (is (false? (:emergency (:properties
                              (geo/aircraft->feature cruising 0)))))
    (is (false? (:emergency
                  (:properties
                    (geo/aircraft->feature
                      (dissoc cruising :aircraft/squawk) 0)))))))

(deftest stale-property
  (let [heard (assoc cruising :aircraft/seen-at-ms 0)]
    (testing "stale is derived from the now-ms argument, not a clock"
      (is (false? (:stale
                    (:properties
                      (geo/aircraft->feature
                        heard aircraft/stale-threshold-ms)))))
      (is (true? (:stale
                   (:properties
                     (geo/aircraft->feature
                       heard (inc aircraft/stale-threshold-ms)))))))

    (testing "an aircraft with no receive time carries no stale property —
              there is nothing to judge"
      (is (not (contains?
                 (:properties (geo/aircraft->feature cruising 0))
                 :stale))))))

(deftest age-property
  (let [heard (assoc cruising :aircraft/seen-at-ms 0)]
    (testing "age-s is the continuous silence in seconds, judged against
              now-ms — the property the opacity fade interpolates over"
      (is (= 0 (:age-s (:properties (geo/aircraft->feature heard 0)))))
      (is (= 45 (:age-s (:properties (geo/aircraft->feature heard 45000)))))
      (is (= 250 (:age-s (:properties (geo/aircraft->feature heard 250000))))))

    (testing "age-s grows with the clock — a later now-ms reads as older,
              so the fade only ever deepens for a silent aircraft"
      (let [young (:age-s (:properties (geo/aircraft->feature heard 30000)))
            old   (:age-s (:properties (geo/aircraft->feature heard 90000)))]
        (is (> old young))))

    (testing "an aircraft with no receive time carries no age — nothing to
              measure from"
      (is (not (contains?
                 (:properties (geo/aircraft->feature cruising 0))
                 :age-s))))))

(deftest mlat-property
  (testing "a multilaterated aircraft carries :mlat true so the style can
            demote its lower-confidence position"
    (is (true? (:mlat (:properties
                        (geo/aircraft->feature fixtures/mlat-derived 0))))))

  (testing "a self-reporting ADS-B aircraft omits :mlat entirely — absent,
            never false"
    (is (not (contains?
               (:properties (geo/aircraft->feature fixtures/ups-2717 0))
               :mlat)))))

;; ---------------------------------------------------------------------
;; Client-side age-out: the long-silent cast member is the acceptance
;; test. seen-at-ms is stamped by merge-batch, so we age the fixture
;; through the real boundary rather than hand-write the receive time.

(deftest aged-out-aircraft-produce-no-feature
  (let [captured 1720713600000
        picture  (aircraft/merge-batch {} [fixtures/long-silent] captured)
        planes   (vals picture)]
    (testing "at the age-out line the long-silent aircraft still renders —
              faded, not gone: 300 s of silence, exactly the threshold"
      (let [coll     (geo/aircraft-picture->feature-collection planes captured)
            features (:features coll)]
        (is (= 1 (count features)))
        (is (= 300 (:age-s (:properties (first features))))
            "aged to the age-out line, where the fade bottoms out")))

    (testing "one millisecond past the age-out line it produces no feature —
              the client drops it even if the server has not yet"
      (is (empty? (:features
                    (geo/aircraft-picture->feature-collection
                      planes (inc captured))))))

    (testing "an un-timed aircraft is never spuriously aged out — with no
              receive time there is nothing to judge silent"
      (is (= 1 (count (:features
                        (geo/aircraft-picture->feature-collection
                          [cruising] (+ captured 1000000)))))))))

(deftest feature-collection
  (testing "only positioned aircraft become features; the count is the
            positioned count, never the total"
    (let [collection (geo/aircraft-picture->feature-collection
                       [cruising bare-mode-s cruising] 0)]
      (is (= "FeatureCollection" (:type collection)))
      (is (= 2 (count (:features collection))))))

  (testing "an empty picture yields an empty, well-formed collection"
    (is (= {:type "FeatureCollection" :features []}
           (geo/aircraft-picture->feature-collection [] 0)))))

;; ---------------------------------------------------------------------
;; Real fixture through coerce — positioned count in == features out

#?(:clj
   (deftest real-fixture-round-trip
     (let [payload (json/parse-string
                     (slurp "test/resources/aircraft-sample.json") true)
           batch   (coerce/->aircraft-batch (:aircraft payload))
           collection (geo/aircraft-picture->feature-collection batch 0)
           positioned (count (filter :aircraft/position batch))]

       (testing "the capture's positioned count survives to the
                 FeatureCollection intact"
         (is (= 39 positioned))
         (is (= positioned (count (:features collection)))))

       (testing "every feature is a well-formed GeoJSON Point"
         (is (every? #(= "Feature" (:type %)) (:features collection)))
         (is (every? #(= "Point" (get-in % [:geometry :type]))
                     (:features collection)))))))
