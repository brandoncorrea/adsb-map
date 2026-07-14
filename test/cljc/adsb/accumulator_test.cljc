(ns adsb.accumulator-test
  (:require
    [adsb.accumulator :as accumulator]
    [adsb.aircraft :as aircraft]
    #?(:clj  [clojure.test :refer [deftest testing is]]
       :cljs [cljs.test :refer-macros [deftest testing is]])))

;; A single wall-clock instant the deltas are heard at, so age-out
;; assertions read against a known now. Literal maps and literal now-ms
;; throughout — the namespace is pure, so there is nothing to mock.
(def ^:private t0 1720713600000)

(def ^:private ups-icao "a1b2c3")
(def ^:private swa-icao "d4e5f6")

(deftest accumulate
  (testing "a delta for an unheard aircraft adds it, stamped at now-ms"
    (let [picture (accumulator/accumulate
                    {} {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                    t0)]
      (is (= [ups-icao] (keys picture)))
      (is (= "UPS2717" (get-in picture [ups-icao :aircraft/callsign])))
      (is (= t0 (get-in picture [ups-icao :aircraft/seen-at-ms])))))

  (testing "a later delta's field wins over the earlier value"
    (let [picture (-> {}
                      (accumulator/accumulate
                        {:aircraft/icao ups-icao :aircraft/altitude-ft 30000}
                        t0)
                      (accumulator/accumulate
                        {:aircraft/icao ups-icao :aircraft/altitude-ft 31000}
                        (+ t0 1000)))]
      (is (= 31000 (get-in picture [ups-icao :aircraft/altitude-ft])))))

  (testing "a field absent from a delta persists from the earlier state"
    (let [picture (-> {}
                      (accumulator/accumulate
                        {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                        t0)
                      (accumulator/accumulate
                        {:aircraft/icao ups-icao
                         :aircraft/position {:geo/lat 39.0 :geo/lon -104.0}}
                        (+ t0 1000)))]
      (is (= "UPS2717" (get-in picture [ups-icao :aircraft/callsign]))
          "the callsign the position-only delta never mentioned survives")
      (is (= {:geo/lat 39.0 :geo/lon -104.0}
             (get-in picture [ups-icao :aircraft/position])))))

  (testing "every applied delta refreshes the seen stamp to now-ms"
    (let [picture (-> {}
                      (accumulator/accumulate
                        {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                        t0)
                      (accumulator/accumulate
                        {:aircraft/icao ups-icao :aircraft/track-deg 90}
                        (+ t0 5000)))]
      (is (= (+ t0 5000) (get-in picture [ups-icao :aircraft/seen-at-ms])))))

  (testing "interleaved deltas across two ICAOs accumulate independently"
    (let [picture (-> {}
                      (accumulator/accumulate
                        {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                        t0)
                      (accumulator/accumulate
                        {:aircraft/icao swa-icao :aircraft/callsign "SWA100"}
                        (+ t0 100))
                      (accumulator/accumulate
                        {:aircraft/icao ups-icao :aircraft/altitude-ft 30000}
                        (+ t0 200))
                      (accumulator/accumulate
                        {:aircraft/icao swa-icao :aircraft/altitude-ft 12000}
                        (+ t0 300)))]
      (is (= "UPS2717" (get-in picture [ups-icao :aircraft/callsign])))
      (is (= 30000 (get-in picture [ups-icao :aircraft/altitude-ft])))
      (is (= "SWA100" (get-in picture [swa-icao :aircraft/callsign])))
      (is (= 12000 (get-in picture [swa-icao :aircraft/altitude-ft])))))

  (testing "a delta revives an aircraft that had aged out"
    (let [stale-at   (- t0 (inc aircraft/age-out-threshold-ms))
          picture    (-> {}
                         (accumulator/accumulate
                           {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                           stale-at)
                         (accumulator/accumulate
                           {:aircraft/icao ups-icao :aircraft/altitude-ft 30000}
                           t0))]
      (is (= t0 (get-in picture [ups-icao :aircraft/seen-at-ms]))
          "the reviving delta stamps it fresh")
      (is (not (aircraft/aged-out? (get picture ups-icao) t0)))
      (is (= "UPS2717" (get-in picture [ups-icao :aircraft/callsign]))
          "the pre-silence callsign carries through the revival"))))

(deftest snapshot
  (testing "the empty picture yields an empty batch"
    (is (= [] (accumulator/snapshot {} t0))))

  (testing "live aircraft come back as a vector, silent ones dropped"
    (let [picture (-> {}
                      (accumulator/accumulate
                        {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                        (- t0 (inc aircraft/age-out-threshold-ms)))
                      (accumulator/accumulate
                        {:aircraft/icao swa-icao :aircraft/callsign "SWA100"}
                        t0))
          batch   (accumulator/snapshot picture t0)]
      (is (vector? batch))
      (is (= [swa-icao] (map :aircraft/icao batch)))))

  (testing "silence exactly at the threshold has not yet aged out"
    (let [picture (accumulator/accumulate
                    {} {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                    (- t0 aircraft/age-out-threshold-ms))
          batch   (accumulator/snapshot picture t0)]
      (is (= [ups-icao] (map :aircraft/icao batch)))))

  (testing "one millisecond past the threshold ages out"
    (let [picture (accumulator/accumulate
                    {} {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                    (- t0 (inc aircraft/age-out-threshold-ms)))
          batch   (accumulator/snapshot picture t0)]
      (is (= [] batch)))))
