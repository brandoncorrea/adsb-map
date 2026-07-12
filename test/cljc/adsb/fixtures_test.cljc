(ns adsb.fixtures-test
  "Tests for the cast itself. The cast is only useful if it keeps telling
  the truth: every member must still survive the real ingest boundary and
  still satisfy the domain schema, and each must exhibit exactly the one
  quirk it is named for."
  (:require
    [adsb.fixtures :as fixtures]
    [adsb.ingest.coerce :as coerce]
    [adsb.schema :as schema]
    [malli.core :as m]
    #?(:clj  [clojure.test :refer [deftest testing is]]
       :cljs [cljs.test :refer-macros [deftest testing is]])))

(deftest cast-integrity
  (testing "every raw cast member coerces through the real ingest
            boundary into a domain aircraft"
    (is (every? some? (map coerce/->aircraft fixtures/all-raw))))

  (testing "every domain cast member satisfies the aircraft schema, so a
            schema change breaks the cast loudly"
    (is (every? #(m/validate schema/aircraft %) fixtures/all)))

  (testing "the cast has six members, in both forms"
    (is (= 6 (count fixtures/all-raw)))
    (is (= 6 (count fixtures/all)))))

(deftest ups-2717-fixture
  (testing "ups-2717 is the happy path — a positioned, well-formed,
            cruising aircraft with complete data"
    (is (= "abc0e4" (:aircraft/icao fixtures/ups-2717)))
    (is (= "UPS2717" (:aircraft/callsign fixtures/ups-2717)))
    (is (contains? fixtures/ups-2717 :aircraft/position))
    (is (= 34775 (:aircraft/altitude-ft fixtures/ups-2717)))))

(deftest on-the-ground-fixture
  (testing "on-the-ground's raw alt_baro is the string \"ground\", not a
            number"
    (is (= "ground" (:alt_baro fixtures/on-the-ground-raw))))

  (testing "on-the-ground coerces \"ground\" to an on-ground flag and
            never an altitude"
    (is (true? (:aircraft/on-ground? fixtures/on-the-ground)))
    (is (not (contains? fixtures/on-the-ground :aircraft/altitude-ft)))))

(deftest never-positioned-fixture
  (testing "never-positioned's raw form reports no lat/lon"
    (is (not (contains? fixtures/never-positioned-raw :lat)))
    (is (not (contains? fixtures/never-positioned-raw :lon))))

  (testing "never-positioned is kept as a domain aircraft, without an
            :aircraft/position"
    (is (some? fixtures/never-positioned))
    (is (= "a10202" (:aircraft/icao fixtures/never-positioned)))
    (is (not (contains? fixtures/never-positioned :aircraft/position)))))

(deftest squawking-7700-fixture
  (testing "squawking-7700 carries the 7700 general-emergency squawk"
    (is (= "7700" (:aircraft/squawk fixtures/squawking-7700)))))

(deftest long-silent-fixture
  (testing "long-silent was last heard 300 seconds ago"
    (is (= 300 (:aircraft/seen-s fixtures/long-silent)))))

(deftest mlat-derived-fixture
  (testing "mlat-derived's raw form is flagged type \"mlat\" with a
            non-empty mlat field naming the multilaterated fields"
    (is (= "mlat" (:type fixtures/mlat-derived-raw)))
    (is (seq (:mlat fixtures/mlat-derived-raw))))

  (testing "mlat-derived still coerces to an ordinary positioned domain
            aircraft"
    (is (contains? fixtures/mlat-derived :aircraft/position))))
