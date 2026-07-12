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
