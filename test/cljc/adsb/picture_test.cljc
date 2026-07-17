(ns adsb.picture-test
  (:require [adsb.aircraft :as aircraft]
            [adsb.fixtures :as fixtures]
            [adsb.picture :as picture]
            [clojure.test :refer [deftest is testing]]))

;; The poll path stamps against captured-at-ms, the streaming path against
;; heard-at-ms; both instants coincide here, but the names keep the two
;; vocabularies distinct at the call sites below.
(def ^:private captured-at-ms 1720713600000)
(def ^:private t0 captured-at-ms)
(def ^:private ups-icao (:aircraft/icao fixtures/ups-2717))
(def ^:private swa-icao "d4e5f6")
(def ^:private never-positioned-icao (:aircraft/icao fixtures/never-positioned))
(def ^:private long-silent-icao (:aircraft/icao fixtures/long-silent))

;; --- poll snapshots: merge-batch ---

(deftest merge-batch
  (testing "a newly heard aircraft is added to the picture, keyed by icao"
    (let [pic (picture/merge-batch {} [fixtures/ups-2717]
                                   captured-at-ms)]
      (is (= [ups-icao] (keys pic)))
      (is (= "UPS2717" (get-in pic [ups-icao :aircraft/callsign])))))

  (testing "the observation instant is capture time minus the feeder's seen"
    (let [pic (picture/merge-batch {} [fixtures/long-silent]
                                   captured-at-ms)]
      (is (= (- captured-at-ms 300000)
             (get-in pic [long-silent-icao :aircraft/seen-at-ms])))))

  (testing "an observation with no seen is pinned to the capture instant"
    (let [never-seen (dissoc fixtures/ups-2717 :aircraft/seen-s)
          pic        (picture/merge-batch {} [never-seen] captured-at-ms)]
      (is (= captured-at-ms
             (get-in pic [ups-icao :aircraft/seen-at-ms])))))

  (testing "an aircraft that arrives already stamped keeps its stamp — a
            streaming snapshot carries an absolute seen-at-ms and no
            seen-s, and re-deriving from capture time would re-hear a
            long-silent aircraft as if it had just spoken (adsb-0g0)"
    (let [heard-at (- captured-at-ms 100000)
          streamed (-> fixtures/ups-2717
                       (dissoc :aircraft/seen-s)
                       (assoc :aircraft/seen-at-ms heard-at))
          pic      (picture/merge-batch {} [streamed] captured-at-ms)]
      (is (= heard-at (get-in pic [ups-icao :aircraft/seen-at-ms])))))

  (testing "a streamed aircraft silent past the threshold ages out of the
            merged picture — its true stamp, not the capture instant, is
            what the sweep reads"
    (let [heard-at (- captured-at-ms
                      (inc aircraft/age-out-threshold-ms))
          streamed (-> fixtures/ups-2717
                       (dissoc :aircraft/seen-s)
                       (assoc :aircraft/seen-at-ms heard-at))]
      (is (empty? (-> (picture/merge-batch {} [streamed] captured-at-ms)
                      (picture/sweep captured-at-ms))))))

  (testing "the capture-relative seen-s is replaced by absolute seen-at-ms"
    (let [pic    (picture/merge-batch {} [fixtures/ups-2717]
                                      captured-at-ms)
          stored (get pic ups-icao)]
      (is (not (contains? stored :aircraft/seen-s)))
      (is (= (- captured-at-ms 400) (:aircraft/seen-at-ms stored)))))

  (testing "a field the feeder stopped reporting goes absent, not stale"
    (let [altitude-less (dissoc fixtures/ups-2717 :aircraft/altitude-ft)
          pic           (-> {}
                            (picture/merge-batch [fixtures/ups-2717]
                                                 captured-at-ms)
                            (picture/merge-batch [altitude-less]
                                                 (+ captured-at-ms 1000)))]
      (is (not (contains? (get pic ups-icao)
                          :aircraft/altitude-ft)))))

  (testing "last-known position is retained across a position-less update"
    (let [position-less (dissoc fixtures/ups-2717 :aircraft/position)
          pic           (-> {}
                            (picture/merge-batch [fixtures/ups-2717]
                                                 captured-at-ms)
                            (picture/merge-batch [position-less]
                                                 (+ captured-at-ms 1000)))]
      (is (= (:aircraft/position fixtures/ups-2717)
             (get-in pic [ups-icao :aircraft/position])))))

  (testing "a never-positioned aircraft is kept, without a position"
    (let [pic (picture/merge-batch {} [fixtures/never-positioned]
                                   captured-at-ms)]
      (is (contains? pic never-positioned-icao))
      (is (not (aircraft/positioned? (get pic never-positioned-icao))))))

  (testing "no position is fabricated for a never-positioned aircraft"
    (let [pic (-> {}
                  (picture/merge-batch [fixtures/never-positioned]
                                       captured-at-ms)
                  (picture/merge-batch [fixtures/never-positioned]
                                       (+ captured-at-ms 1000)))]
      (is (not (aircraft/positioned? (get pic never-positioned-icao))))))

  (testing "an aircraft missing from one poll is retained, not removed"
    (let [pic (-> {}
                  (picture/merge-batch [fixtures/ups-2717
                                        fixtures/never-positioned]
                                       captured-at-ms)
                  (picture/merge-batch [fixtures/never-positioned]
                                       (+ captured-at-ms 1000)))]
      (is (contains? pic ups-icao)))))

;; --- per-message deltas: accumulate ---

(deftest accumulate
  (testing "a delta for an unheard aircraft adds it, stamped at heard-at-ms"
    (let [pic (picture/accumulate
                {} {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                t0)]
      (is (= [ups-icao] (keys pic)))
      (is (= "UPS2717" (get-in pic [ups-icao :aircraft/callsign])))
      (is (= t0 (get-in pic [ups-icao :aircraft/seen-at-ms])))))

  (testing "a later delta's field wins over the earlier value"
    (let [pic (-> {}
                  (picture/accumulate
                    {:aircraft/icao ups-icao :aircraft/altitude-ft 30000}
                    t0)
                  (picture/accumulate
                    {:aircraft/icao ups-icao :aircraft/altitude-ft 31000}
                    (+ t0 1000)))]
      (is (= 31000 (get-in pic [ups-icao :aircraft/altitude-ft])))))

  (testing "a field absent from a delta persists from the earlier state"
    (let [pic (-> {}
                  (picture/accumulate
                    {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                    t0)
                  (picture/accumulate
                    {:aircraft/icao     ups-icao
                     :aircraft/position {:geo/lat 39.0 :geo/lon -104.0}}
                    (+ t0 1000)))]
      (is (= "UPS2717" (get-in pic [ups-icao :aircraft/callsign])))
      (is (= {:geo/lat 39.0 :geo/lon -104.0}
             (get-in pic [ups-icao :aircraft/position])))))

  (testing "an explicit airborne false clears a landed aircraft's on-ground
            marker — a departure must not read GND at altitude (adsb-b0w)"
    (let [pic (-> {}
                  (picture/accumulate
                    {:aircraft/icao ups-icao :aircraft/on-ground? true}
                    t0)
                  (picture/accumulate
                    {:aircraft/icao        ups-icao
                     :aircraft/on-ground?  false
                     :aircraft/altitude-ft 3800}
                    (+ t0 1000)))]
      (is (not (get-in pic [ups-icao :aircraft/on-ground?])))
      (is (= 3800 (get-in pic [ups-icao :aircraft/altitude-ft])))))

  (testing "every applied delta refreshes the seen stamp to heard-at-ms"
    (let [pic (-> {}
                  (picture/accumulate
                    {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                    t0)
                  (picture/accumulate
                    {:aircraft/icao ups-icao :aircraft/track-deg 90}
                    (+ t0 5000)))]
      (is (= (+ t0 5000) (get-in pic [ups-icao :aircraft/seen-at-ms])))))

  (testing "interleaved deltas across two ICAOs accumulate independently"
    (let [pic (-> {}
                  (picture/accumulate
                    {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                    t0)
                  (picture/accumulate
                    {:aircraft/icao swa-icao :aircraft/callsign "SWA100"}
                    (+ t0 100))
                  (picture/accumulate
                    {:aircraft/icao ups-icao :aircraft/altitude-ft 30000}
                    (+ t0 200))
                  (picture/accumulate
                    {:aircraft/icao swa-icao :aircraft/altitude-ft 12000}
                    (+ t0 300)))]
      (is (= "UPS2717" (get-in pic [ups-icao :aircraft/callsign])))
      (is (= 30000 (get-in pic [ups-icao :aircraft/altitude-ft])))
      (is (= "SWA100" (get-in pic [swa-icao :aircraft/callsign])))
      (is (= 12000 (get-in pic [swa-icao :aircraft/altitude-ft])))))

  (testing "a delta revives an aircraft that had aged out — as a new
            arrival, inheriting nothing from before the silence (adsb-gq3)"
    (let [stale-at (- t0 (inc aircraft/age-out-threshold-ms))
          pic      (-> {}
                       (picture/accumulate
                         {:aircraft/icao     ups-icao
                          :aircraft/callsign "UPS2717"
                          :aircraft/position {:geo/lat 39.0 :geo/lon -104.0}}
                         stale-at)
                       (picture/accumulate
                         {:aircraft/icao ups-icao :aircraft/altitude-ft 30000}
                         t0))]
      (is (= t0 (get-in pic [ups-icao :aircraft/seen-at-ms])))
      (is (not (aircraft/aged-out? (get pic ups-icao) t0)))
      (is (not (contains? (get pic ups-icao) :aircraft/position)))
      (is (not (contains? (get pic ups-icao) :aircraft/callsign)))))

  (testing "silence short of the threshold is not a revival: the fields
            the delta does not mention still persist"
    (let [quiet-at (- t0 aircraft/age-out-threshold-ms)
          pic      (-> {}
                       (picture/accumulate
                         {:aircraft/icao     ups-icao
                          :aircraft/position {:geo/lat 39.0 :geo/lon -104.0}}
                         quiet-at)
                       (picture/accumulate
                         {:aircraft/icao ups-icao :aircraft/altitude-ft 30000}
                         t0))]
      (is (= {:geo/lat 39.0 :geo/lon -104.0}
             (get-in pic [ups-icao :aircraft/position]))))))

;; --- the shared sweep: drops aged-out entries, whichever path built them ---

(deftest sweep
  (testing "the aged-out aircraft are evicted from the picture itself, so
            the atom a Source folds into does not grow without bound"
    (let [pic   (-> {}
                    (picture/accumulate
                      {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                      (- t0 (inc aircraft/age-out-threshold-ms)))
                    (picture/accumulate
                      {:aircraft/icao swa-icao :aircraft/callsign "SWA100"}
                      t0))
          swept (picture/sweep pic t0)]
      (is (= [swa-icao] (keys swept)))
      (is (map? swept))))

  (testing "sweeping keeps exactly what snapshot would show"
    (let [pic (-> {}
                  (picture/accumulate
                    {:aircraft/icao ups-icao} (- t0 aircraft/age-out-threshold-ms))
                  (picture/accumulate
                    {:aircraft/icao swa-icao} t0))]
      (is (= (set (map :aircraft/icao (picture/snapshot pic t0)))
             (set (keys (picture/sweep pic t0)))))))

  (testing "the empty picture sweeps to the empty picture"
    (is (= {} (picture/sweep {} t0))))

  (let [pic     (picture/merge-batch {} [fixtures/ups-2717
                                         fixtures/long-silent]
                                     captured-at-ms)
        at-line (picture/merge-batch
                  {}
                  [(assoc fixtures/ups-2717
                     :aircraft/icao "aaedge"
                     :aircraft/seen-s
                     (/ aircraft/age-out-threshold-ms 1000.0))]
                  captured-at-ms)]
    (testing "a poll-built long-silent aircraft (well past the line) ages out"
      (let [aged (picture/sweep pic (inc captured-at-ms))]
        (is (not (contains? aged long-silent-icao)))))

    (testing "a freshly heard aircraft survives a sweep"
      (let [aged (picture/sweep pic (inc captured-at-ms))]
        (is (contains? aged ups-icao))))

    (testing "silence exactly at the threshold does not yet age out"
      (let [aged (picture/sweep at-line captured-at-ms)]
        (is (contains? aged "aaedge"))))

    (testing "one millisecond past the threshold ages out"
      (let [aged (picture/sweep at-line (inc captured-at-ms))]
        (is (not (contains? aged "aaedge")))))))

;; --- reading the picture out: snapshot ---

(deftest snapshot
  (testing "the empty picture yields an empty batch"
    (is (= [] (picture/snapshot {} t0))))

  (testing "live aircraft come back as a vector, silent ones dropped"
    (let [pic   (-> {}
                    (picture/accumulate
                      {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                      (- t0 (inc aircraft/age-out-threshold-ms)))
                    (picture/accumulate
                      {:aircraft/icao swa-icao :aircraft/callsign "SWA100"}
                      t0))
          batch (picture/snapshot pic t0)]
      (is (vector? batch))
      (is (= [swa-icao] (map :aircraft/icao batch)))))

  (testing "silence exactly at the threshold has not yet aged out"
    (let [pic   (picture/accumulate
                  {} {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                  (- t0 aircraft/age-out-threshold-ms))
          batch (picture/snapshot pic t0)]
      (is (= [ups-icao] (map :aircraft/icao batch)))))

  (testing "one millisecond past the threshold ages out"
    (let [pic   (picture/accumulate
                  {} {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                  (- t0 (inc aircraft/age-out-threshold-ms)))
          batch (picture/snapshot pic t0)]
      (is (= [] batch)))))
