(ns adsb.geo-test
  (:require [adsb.aircraft :as aircraft]
            [adsb.fixtures :as fixtures]
            [adsb.geo :as geo]
            [clojure.test :refer [deftest testing is]]
            #?@(:clj [[adsb.ingest.coerce :as coerce]
                      [cheshire.core :as json]])))

(defn- close? [expected actual tol]
  (< (abs (- expected actual)) tol))

(def ^:private equator-degree-m 111194.9)

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

  (testing "the four cardinal directions from the origin, normalized to [0, 360)"
    (let [origin {:geo/lat 0 :geo/lon 0}]
      (is (close? 0 (geo/bearing origin {:geo/lat 1 :geo/lon 0}) 0.01))
      (is (close? 90 (geo/bearing origin {:geo/lat 0 :geo/lon 1}) 0.01))
      (is (close? 180 (geo/bearing origin {:geo/lat -1 :geo/lon 0}) 0.01))
      (is (close? 270 (geo/bearing origin {:geo/lat 0 :geo/lon -1}) 0.01)))))

(deftest destination-known-values
  (testing "the canonical Movable-Type worked example: 124.8 km on an
            initial bearing of 96°01′18″ from 53°19′14″N 1°43′47″W lands
            at 53°11′18″N 0°08′00″E"
    (let [reached (geo/destination {:geo/lat 53.320556 :geo/lon -1.729722} 96.021667 124800)]
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
      (is (close? 0 (geo/distance from (geo/destination from 97.14 0)) 0.001))))

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

(def ^:private viewport
  {:geo/min-lat 27.0
   :geo/max-lat 29.0
   :geo/min-lon -83.0
   :geo/max-lon -81.0})

(deftest edge-annotation-known-geometry
  (testing "a target INSIDE the viewport needs no arrow"
    (is (nil? (geo/edge-annotation viewport {:geo/lat 28.0 :geo/lon -82.0})))
    (is (nil? (geo/edge-annotation viewport {:geo/lat 28.9 :geo/lon -81.1}))))

  (testing "a target due NORTH exits through the top edge's midpoint,
            bearing 0 along the meridian"
    (let [edge (geo/edge-annotation viewport {:geo/lat 31.0 :geo/lon -82.0})]
      (is (close? 0.5 (:edge/x edge) 1e-9))
      (is (close? 0.0 (:edge/y edge) 1e-9))
      (is (close? 0.0 (:edge/bearing-deg edge) 0.01))))

  (testing "a target due SOUTH exits through the bottom edge's midpoint,
            bearing 180"
    (let [edge (geo/edge-annotation viewport {:geo/lat 25.0 :geo/lon -82.0})]
      (is (close? 0.5 (:edge/x edge) 1e-9))
      (is (close? 1.0 (:edge/y edge) 1e-9))
      (is (close? 180.0 (:edge/bearing-deg edge) 0.01))))

  (testing "a target on the exact 45° plate diagonal exits through the
            top-right corner"
    (let [edge (geo/edge-annotation viewport {:geo/lat 30.0 :geo/lon -80.0})]
      (is (close? 1.0 (:edge/x edge) 1e-9))
      (is (close? 0.0 (:edge/y edge) 1e-9))))

  (testing "the tighter half-span wins: a target far east and a little
            north pins to the RIGHT edge, part-way up"
    (let [edge (geo/edge-annotation viewport {:geo/lat 28.5 :geo/lon -78.0})]
      (is (close? 1.0 (:edge/x edge) 1e-9))
      (is (close? 0.4375 (:edge/y edge) 1e-9))
      (is (< 80 (:edge/bearing-deg edge) 90))))

  (testing "the distance is the great-circle distance from the viewport
            centre, so the arrow's label agrees with geo/distance"
    (let [target {:geo/lat 31.0 :geo/lon -82.0}
          edge   (geo/edge-annotation viewport target)]
      (is (close? (geo/distance {:geo/lat 28.0 :geo/lon -82.0} target)
                  (:edge/distance-m edge)
                  1))))

  (testing "an unwrapped-bounds pan meets a wrapped longitude: the target
            reads in the viewport's frame, not 360° away"
    (let [across {:geo/min-lat 27.0 :geo/max-lat 29.0
                  :geo/min-lon 178.0 :geo/max-lon 182.0}]
      (is (nil? (geo/edge-annotation across {:geo/lat 28.0 :geo/lon -179.0})))
      (let [edge (geo/edge-annotation across {:geo/lat 28.0 :geo/lon -175.0})]
        (is (close? 1.0 (:edge/x edge) 1e-9)))))

  (testing "a degenerate viewport has no edge worth annotating"
    (is (nil? (geo/edge-annotation
                {:geo/min-lat 28.0 :geo/max-lat 28.0
                 :geo/min-lon -82.0 :geo/max-lon -82.0}
                {:geo/lat 31.0 :geo/lon -82.0})))))

(def cruising
  {:aircraft/icao        "abc0e4"
   :aircraft/callsign    "UPS2717"
   :aircraft/position    {:geo/lat 27.961166 :geo/lon -83.975953}
   :aircraft/altitude-ft 34775
   :aircraft/track-deg   97.14
   :aircraft/squawk      "6040"})

(def bare-mode-s {:aircraft/icao "a10202"})

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
      (is (not (contains? unknown :altitude)))))

  (testing "an absent callsign or track is omitted, never defaulted"
    (let [props (:properties
                  (geo/aircraft->feature
                    (dissoc cruising :aircraft/callsign :aircraft/track-deg)
                    0))]
      (is (not (contains? props :callsign)))
      (is (not (contains? props :track)))))

  (testing "the emitter category reaches the style layer as a feature
            property — the channel the silhouette is keyed on (adsb-rnp).
            Through the CAST, so this is the real ingest boundary's output,
            not a hand-written map that could drift from it."
    (is (= "A5" (:category
                  (:properties
                    (geo/aircraft->feature fixtures/ups-2717 0)))))
    (is (= "A7" (:category
                  (:properties
                    (geo/aircraft->feature
                      (assoc fixtures/ups-2717 :aircraft/category "A7")
                      0))))))

  (testing "an aircraft that never said what it is still gets a feature —
            the category is simply omitted, and the style layer reads that
            absence as the generic plane. No aircraft goes undrawn for
            want of a classification."
    (let [feature (geo/aircraft->feature (dissoc fixtures/ups-2717 :aircraft/category) 0)]
      (is (some? feature))
      (is (= "abc0e4" (:icao (:properties feature))))
      (is (not (contains? (:properties feature) :category))))))

(deftest emergency-property
  (testing "the three distress squawks each mark the aircraft as an
            emergency"
    (doseq [code ["7500" "7600" "7700"]]
      (is (-> (assoc cruising :aircraft/squawk code)
              (geo/aircraft->feature 0)
              :properties
              :emergency))))

  (testing "an ordinary squawk, and an absent one, are not emergencies"
    (is (not (:emergency (:properties (geo/aircraft->feature cruising 0)))))
    (is (not (-> (dissoc cruising :aircraft/squawk)
                 (geo/aircraft->feature 0)
                 :properties
                 :emergency)))))

(deftest stale-property
  (let [heard (assoc cruising :aircraft/seen-at-ms 0)]
    (testing "stale is derived from the now-ms argument, not a clock"
      (is (not (-> (geo/aircraft->feature heard aircraft/stale-threshold-ms)
                   :properties
                   :stale)))
      (is (-> (geo/aircraft->feature heard (inc aircraft/stale-threshold-ms))
              :properties
              :stale)))

    (testing "an aircraft with no receive time carries no stale property —
              there is nothing to judge"
      (is (not (contains? (:properties (geo/aircraft->feature cruising 0)) :stale))))))

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
    (is (:mlat (:properties (geo/aircraft->feature fixtures/mlat-derived 0)))))

  (testing "a self-reporting ADS-B aircraft omits :mlat entirely — absent,
            never false"
    (is (not (contains? (:properties (geo/aircraft->feature fixtures/ups-2717 0)) :mlat)))))

(deftest aged-out-aircraft-produce-no-feature
  (let [captured    1720713600000
        at-line     (assoc fixtures/ups-2717
                      :aircraft/seen-s
                      (/ aircraft/age-out-threshold-ms 1000))
        picture     (aircraft/merge-batch {} [at-line] captured)
        planes      (vals picture)
        threshold-s (quot aircraft/age-out-threshold-ms 1000)]
    (testing "at the age-out line the aircraft still renders — faded, not gone"
      (let [coll     (geo/aircraft-picture->feature-collection planes captured)
            features (:features coll)]
        (is (= 1 (count features)))
        (is (= threshold-s (:age-s (:properties (first features)))))))

    (testing "one millisecond past the age-out line it produces no feature —
              the client drops it even if the server has not yet"
      (is (empty? (:features
                    (geo/aircraft-picture->feature-collection
                      planes (inc captured))))))

    (testing "long-silent (well past the line) produces no feature at capture"
      (let [silent (vals (aircraft/merge-batch {} [fixtures/long-silent] captured))]
        (is (empty? (:features
                      (geo/aircraft-picture->feature-collection silent captured))))))

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

#?(:clj
   (deftest real-fixture-round-trip
     (let [payload    (json/parse-string
                        (slurp "test/resources/aircraft-sample.json") true)
           batch      (coerce/->aircraft-batch (:aircraft payload))
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

(deftest circle-ring
  (let [center {:geo/lat 27.9753 :geo/lon -82.5331}
        radius 100000]

    (testing "every vertex sits on the rim — at the radius from the centre,
              which is what makes it a GEODESIC circle and not a planar one
              that sags with latitude"
      (doseq [p (geo/circle center radius)]
        (is (< (abs (- (geo/distance center p) radius)) 1.0))))

    (testing "the ring is CLOSED — GeoJSON requires a LinearRing's first and
              last positions to be identical, and MapLibre drops a ring that
              is not"
      (let [ring (geo/circle center radius)]
        (is (= (first ring) (last ring)))
        (is (= (inc geo/default-circle-segments) (count ring)))))

    (testing "the first vertex leaves on bearing 0 — due north of the centre"
      (let [north (first (geo/circle center radius))]
        (is (> (:geo/lat north) (:geo/lat center)))
        (is (< (abs (- (:geo/lon north) (:geo/lon center))) 1e-9))))

    (testing "segment count is honoured"
      (is (= 5 (count (geo/circle center radius 4)))))))
