(ns adsb.geojson-test
  (:require [adsb.aircraft :as aircraft]
            [adsb.fixtures :as fixtures]
            [adsb.geojson :as geojson]
            [clojure.test :refer [deftest testing is]]
            #?@(:clj [[adsb.ingest.coerce :as coerce]
                      [cheshire.core :as json]])))

(defn- pos [lat lon] {:geo/lat lat :geo/lon lon})

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
    (let [feature (geojson/aircraft->feature cruising 0)]
      (is (= "Feature" (:type feature)))
      (is (= {:type "Point" :coordinates [-83.975953 27.961166]}
             (:geometry feature)))))

  (testing "a never-positioned aircraft produces NO feature"
    (is (nil? (geojson/aircraft->feature bare-mode-s 0)))))

(deftest aircraft->feature-properties
  (testing "properties carry what the map styles on — icao, callsign,
            track, altitude, emergency"
    (let [props (:properties (geojson/aircraft->feature cruising 0))]
      (is (= "abc0e4" (:icao props)))
      (is (= "UPS2717" (:callsign props)))
      (is (= 97.14 (:track props)))
      (is (= 34775 (:altitude props)))
      (is (false? (:emergency props)))))

  (testing "\"ground\" altitude and absent altitude are DISTINCT
            representations — and absent never serializes as 0"
    (let [ground   (:properties
                     (geojson/aircraft->feature
                       (-> cruising
                           (dissoc :aircraft/altitude-ft)
                           (assoc :aircraft/on-ground? true))
                       0))
          airborne (:properties (geojson/aircraft->feature cruising 0))
          unknown  (:properties
                     (geojson/aircraft->feature
                       (dissoc cruising :aircraft/altitude-ft) 0))]
      (is (= "ground" (:altitude ground)))
      (is (= 34775 (:altitude airborne)))
      (is (not (contains? unknown :altitude)))))

  (testing "an absent callsign or track is omitted, never defaulted"
    (let [props (:properties
                  (geojson/aircraft->feature
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
                    (geojson/aircraft->feature fixtures/ups-2717 0)))))
    (is (= "A7" (:category
                  (:properties
                    (geojson/aircraft->feature
                      (assoc fixtures/ups-2717 :aircraft/category "A7")
                      0))))))

  (testing "an aircraft that never said what it is still gets a feature —
            the category is simply omitted, and the style layer reads that
            absence as the generic plane. No aircraft goes undrawn for
            want of a classification."
    (let [feature (geojson/aircraft->feature (dissoc fixtures/ups-2717 :aircraft/category) 0)]
      (is (some? feature))
      (is (= "abc0e4" (:icao (:properties feature))))
      (is (not (contains? (:properties feature) :category))))))

(deftest emergency-property
  (testing "the three distress squawks each mark the aircraft as an
            emergency"
    (doseq [code ["7500" "7600" "7700"]]
      (is (-> (assoc cruising :aircraft/squawk code)
              (geojson/aircraft->feature 0)
              :properties
              :emergency))))

  (testing "an ordinary squawk, and an absent one, are not emergencies"
    (is (not (:emergency (:properties (geojson/aircraft->feature cruising 0)))))
    (is (not (-> (dissoc cruising :aircraft/squawk)
                 (geojson/aircraft->feature 0)
                 :properties
                 :emergency)))))

(deftest stale-property
  (let [heard (assoc cruising :aircraft/seen-at-ms 0)]
    (testing "stale is derived from the now-ms argument, not a clock"
      (is (not (-> (geojson/aircraft->feature heard aircraft/stale-threshold-ms)
                   :properties
                   :stale)))
      (is (-> (geojson/aircraft->feature heard (inc aircraft/stale-threshold-ms))
              :properties
              :stale)))

    (testing "an aircraft with no receive time carries no stale property —
              there is nothing to judge"
      (is (not (contains? (:properties (geojson/aircraft->feature cruising 0)) :stale))))))

(deftest age-property
  (let [heard (assoc cruising :aircraft/seen-at-ms 0)]
    (testing "age-s is the continuous silence in seconds, judged against
              now-ms — the property the opacity fade interpolates over"
      (is (= 0 (:age-s (:properties (geojson/aircraft->feature heard 0)))))
      (is (= 45 (:age-s (:properties (geojson/aircraft->feature heard 45000)))))
      (is (= 250 (:age-s (:properties (geojson/aircraft->feature heard 250000))))))

    (testing "age-s grows with the clock — a later now-ms reads as older,
              so the fade only ever deepens for a silent aircraft"
      (let [young (:age-s (:properties (geojson/aircraft->feature heard 30000)))
            old   (:age-s (:properties (geojson/aircraft->feature heard 90000)))]
        (is (> old young))))

    (testing "an aircraft with no receive time carries no age — nothing to
              measure from"
      (is (not (contains?
                 (:properties (geojson/aircraft->feature cruising 0))
                 :age-s))))))

(deftest mlat-property
  (testing "a multilaterated aircraft carries :mlat true so the style can
            demote its lower-confidence position"
    (is (:mlat (:properties (geojson/aircraft->feature fixtures/mlat-derived 0)))))

  (testing "a self-reporting ADS-B aircraft omits :mlat entirely — absent,
            never false"
    (is (not (contains? (:properties (geojson/aircraft->feature fixtures/ups-2717 0)) :mlat)))))

(deftest aged-out-aircraft-produce-no-feature
  (let [captured    1720713600000
        at-line     (assoc fixtures/ups-2717
                      :aircraft/seen-s
                      (/ aircraft/age-out-threshold-ms 1000))
        picture     (aircraft/merge-batch {} [at-line] captured)
        planes      (vals picture)
        threshold-s (quot aircraft/age-out-threshold-ms 1000)]
    (testing "at the age-out line the aircraft still renders — faded, not gone"
      (let [coll     (geojson/aircraft-picture->feature-collection planes captured)
            features (:features coll)]
        (is (= 1 (count features)))
        (is (= threshold-s (:age-s (:properties (first features)))))))

    (testing "one millisecond past the age-out line it produces no feature —
              the client drops it even if the server has not yet"
      (is (empty? (:features
                    (geojson/aircraft-picture->feature-collection
                      planes (inc captured))))))

    (testing "long-silent (well past the line) produces no feature at capture"
      (let [silent (vals (aircraft/merge-batch {} [fixtures/long-silent] captured))]
        (is (empty? (:features
                      (geojson/aircraft-picture->feature-collection silent captured))))))

    (testing "an un-timed aircraft is never spuriously aged out — with no
              receive time there is nothing to judge silent"
      (is (= 1 (count (:features
                        (geojson/aircraft-picture->feature-collection
                          [cruising] (+ captured 1000000)))))))))

(deftest feature-collection
  (testing "only positioned aircraft become features; the count is the
            positioned count, never the total"
    (let [collection (geojson/aircraft-picture->feature-collection
                       [cruising bare-mode-s cruising] 0)]
      (is (= "FeatureCollection" (:type collection)))
      (is (= 2 (count (:features collection))))))

  (testing "an empty picture yields an empty, well-formed collection"
    (is (= {:type "FeatureCollection" :features []}
           (geojson/aircraft-picture->feature-collection [] 0)))))

#?(:clj
   (deftest real-fixture-round-trip
     (let [payload    (json/parse-string
                        (slurp "test/resources/aircraft-sample.json") true)
           batch      (coerce/->aircraft-batch (:aircraft payload))
           collection (geojson/aircraft-picture->feature-collection batch 0)
           positioned (count (filter :aircraft/position batch))]

       (testing "the capture's positioned count survives to the
                 FeatureCollection intact"
         (is (= 39 positioned))
         (is (= positioned (count (:features collection)))))

       (testing "every feature is a well-formed GeoJSON Point"
         (is (every? #(= "Feature" (:type %)) (:features collection)))
         (is (every? #(= "Point" (get-in % [:geometry :type]))
                     (:features collection)))))))

(deftest trail-collection-emits-a-linestring-per-live-multi-point-ring
  (let [icao (:aircraft/icao fixtures/ups-2717)
        ring [(pos 27.0 -83.0) (pos 27.1 -83.1) (pos 27.2 -83.2)]
        coll (geojson/history->trail-feature-collection {icao ring} #{icao})
        feat (first (:features coll))]
    (testing "a well-formed FeatureCollection with one LineString"
      (is (= "FeatureCollection" (:type coll)))
      (is (= 1 (count (:features coll))))
      (is (= "LineString" (get-in feat [:geometry :type]))))

    (testing "coordinates are [lon lat], oldest first — tail (progress 0) to
              head (progress 1)"
      (is (= [[-83.0 27.0] [-83.1 27.1] [-83.2 27.2]]
             (get-in feat [:geometry :coordinates]))))

    (testing "the feature carries its icao"
      (is (= icao (get-in feat [:properties :icao]))))))

(deftest trail-collection-omits-lone-points-and-non-live-aircraft
  (let [icao (:aircraft/icao fixtures/ups-2717)]
    (testing "a single-point ring is not a line — no feature"
      (is (empty? (:features
                    (geojson/history->trail-feature-collection
                      {icao [(pos 27.0 -83.0)]} #{icao})))))

    (testing "an aircraft not in live-icaos (aged out / departed) leaves no
              trail, even with a multi-point ring"
      (is (empty? (:features
                    (geojson/history->trail-feature-collection
                      {icao [(pos 27.0 -83.0) (pos 27.1 -83.1)]} #{})))))

    (testing "an empty history yields an empty, well-formed collection"
      (is (= {:type "FeatureCollection" :features []}
             (geojson/history->trail-feature-collection {} #{icao}))))))
