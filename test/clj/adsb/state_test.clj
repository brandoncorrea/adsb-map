(ns adsb.state-test
  (:require [adsb.fixtures :as fixtures :refer [captured-at-ms]]
            [adsb.state :as state]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private ups-icao (:aircraft/icao fixtures/ups-2717))
(def ^:private long-silent-icao (:aircraft/icao fixtures/long-silent))

(use-fixtures :each
  (fn [run]
    (state/clear!)
    (try (run) (finally (state/clear!)))))

(deftest apply-batch!
  (testing "an applied batch is visible in the snapshot, keyed by icao"
    (state/apply-batch! [fixtures/ups-2717] captured-at-ms)
    (is (= "UPS2717" (get-in (state/snapshot) [ups-icao :aircraft/callsign])))))

(deftest apply-batch!-flags-position-jumps
  (testing "an impossible position jump between polls surfaces in the
            store as :aircraft/position-suspect? — flagged, never
            dropped (adsb.ingest.plausibility)"
    (let [mid-atlantic {:geo/lat 28.0 :geo/lon -60.0}
          teleported   (assoc fixtures/ups-2717
                         :aircraft/position mid-atlantic)]
      (state/apply-batch! [fixtures/ups-2717] captured-at-ms)
      (state/apply-batch! [teleported] (+ captured-at-ms 1000))
      (let [aircraft (state/lookup ups-icao)]
        (is (true? (:aircraft/position-suspect? aircraft)))
        (is (= mid-atlantic (:aircraft/position aircraft)))))))

(deftest lookup
  (testing "returns the aircraft last heard under an icao"
    (state/apply-batch! [fixtures/ups-2717] captured-at-ms)
    (is (= "UPS2717" (:aircraft/callsign (state/lookup ups-icao)))))

  (testing "finds the lower-case domain identity from an upper-case query"
    (state/apply-batch! [fixtures/ups-2717] captured-at-ms)
    (is (some? (state/lookup (str/upper-case ups-icao)))))

  (testing "returns nil for an aircraft never heard"
    (is (nil? (state/lookup "ffffff")))))

(deftest age-out!
  (testing "drops a long-silent aircraft and keeps a fresh one"
    (state/apply-batch! [fixtures/ups-2717 fixtures/long-silent]
                        captured-at-ms)
    (state/age-out! (inc captured-at-ms))
    (is (nil? (state/lookup long-silent-icao)))
    (is (some? (state/lookup ups-icao)))))

(deftest snapshot
  (testing "is an empty map before any batch arrives"
    (is (= {} (state/snapshot)))))
