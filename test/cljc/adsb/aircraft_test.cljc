(ns adsb.aircraft-test
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.fixtures :as fixtures]
    #?(:clj  [clojure.test :refer [deftest testing is]]
       :cljs [cljs.test :refer-macros [deftest testing is]])))

(def ^:private captured-at-ms 1720713600000)

;; The cast's domain identities, so assertions read as lookups rather
;; than repeated magic hex strings. ups-2717 arrives with seen 0.4,
;; long-silent with seen 300 (see adsb.fixtures).
(def ^:private ups-icao (:aircraft/icao fixtures/ups-2717))
(def ^:private never-positioned-icao
  (:aircraft/icao fixtures/never-positioned))
(def ^:private long-silent-icao (:aircraft/icao fixtures/long-silent))

(deftest stale?
  (testing "an aircraft heard within the threshold is still fresh"
    (is (not (aircraft/stale? {:aircraft/seen-at-ms 0}
                              aircraft/stale-threshold-ms))))

  (testing "an aircraft silent past the threshold is stale"
    (is (aircraft/stale? {:aircraft/seen-at-ms 0}
                         (inc aircraft/stale-threshold-ms)))))

(deftest positioned?
  (testing "true for an aircraft that has reported a position"
    (is (aircraft/positioned? fixtures/ups-2717)))

  (testing "false for an aircraft heard but never positioned"
    (is (not (aircraft/positioned? fixtures/never-positioned)))))

(deftest emergency?
  (testing "each of the three distress squawks reads as an emergency and
            names its distinct kind"
    (is (aircraft/emergency? {:aircraft/squawk "7500"}))
    (is (aircraft/emergency? {:aircraft/squawk "7600"}))
    (is (aircraft/emergency? {:aircraft/squawk "7700"}))
    (is (= :hijack        (aircraft/squawk->emergency-kind "7500")) "7500 is a hijack")
    (is (= :radio-failure (aircraft/squawk->emergency-kind "7600")) "7600 is a comms failure")
    (is (= :general       (aircraft/squawk->emergency-kind "7700")) "7700 is a general emergency"))

  (testing "the squawking-7700 cast member is a general emergency, through
            the real ingest boundary"
    (is (aircraft/emergency? fixtures/squawking-7700))
    (is (= :general (aircraft/emergency-kind fixtures/squawking-7700))))

  (testing "an ordinary squawk is not an emergency and has no kind"
    (is (not (aircraft/emergency? {:aircraft/squawk "0000"})))
    (is (nil? (aircraft/squawk->emergency-kind "0000")))
    (is (not (aircraft/emergency? fixtures/ups-2717))))

  (testing "an absent squawk is not an emergency — nil, never a false positive"
    (is (not (aircraft/emergency? {})))
    (is (nil? (aircraft/emergency-kind {})))))

(deftest merge-batch
  (testing "a newly heard aircraft is added to the picture, keyed by icao"
    (let [picture (aircraft/merge-batch {} [fixtures/ups-2717]
                                        captured-at-ms)]
      (is (= [ups-icao] (keys picture)))
      (is (= "UPS2717"
             (get-in picture [ups-icao :aircraft/callsign])))))

  (testing "the observation instant is capture time minus the feeder's seen"
    (let [picture (aircraft/merge-batch {} [fixtures/long-silent]
                                        captured-at-ms)]
      (is (= (- captured-at-ms 300000)
             (get-in picture [long-silent-icao :aircraft/seen-at-ms])))))

  (testing "an observation with no seen is pinned to the capture instant"
    (let [never-seen (dissoc fixtures/ups-2717 :aircraft/seen-s)
          picture    (aircraft/merge-batch {} [never-seen] captured-at-ms)]
      (is (= captured-at-ms
             (get-in picture [ups-icao :aircraft/seen-at-ms])))))

  (testing "an aircraft that arrives already stamped keeps its stamp — the
            streaming accumulator's snapshots carry an absolute seen-at-ms
            and no seen-s, and re-deriving from capture time would re-hear
            a long-silent aircraft as if it had just spoken (adsb-0g0)"
    (let [heard-at (- captured-at-ms 100000)
          streamed (-> fixtures/ups-2717
                       (dissoc :aircraft/seen-s)
                       (assoc :aircraft/seen-at-ms heard-at))
          picture  (aircraft/merge-batch {} [streamed] captured-at-ms)]
      (is (= heard-at
             (get-in picture [ups-icao :aircraft/seen-at-ms])))))

  (testing "a streamed aircraft silent past the threshold ages out of the
            merged picture — its true stamp, not the capture instant, is
            what age-out reads"
    (let [heard-at (- captured-at-ms
                      (inc aircraft/age-out-threshold-ms))
          streamed (-> fixtures/ups-2717
                       (dissoc :aircraft/seen-s)
                       (assoc :aircraft/seen-at-ms heard-at))]
      (is (empty? (-> (aircraft/merge-batch {} [streamed] captured-at-ms)
                      (aircraft/age-out captured-at-ms))))))

  (testing "the capture-relative seen-s is replaced by absolute seen-at-ms"
    (let [picture (aircraft/merge-batch {} [fixtures/ups-2717]
                                        captured-at-ms)
          stored  (get picture ups-icao)]
      (is (not (contains? stored :aircraft/seen-s)))
      (is (= (- captured-at-ms 400) (:aircraft/seen-at-ms stored)))))

  (testing "a field the feeder stopped reporting goes absent, not stale"
    (let [altitude-less (dissoc fixtures/ups-2717 :aircraft/altitude-ft)
          picture (-> {}
                      (aircraft/merge-batch [fixtures/ups-2717]
                                            captured-at-ms)
                      (aircraft/merge-batch [altitude-less]
                                            (+ captured-at-ms 1000)))]
      (is (not (contains? (get picture ups-icao)
                          :aircraft/altitude-ft)))))

  (testing "last-known position is retained across a position-less update"
    (let [position-less (dissoc fixtures/ups-2717 :aircraft/position)
          picture (-> {}
                      (aircraft/merge-batch [fixtures/ups-2717]
                                            captured-at-ms)
                      (aircraft/merge-batch [position-less]
                                            (+ captured-at-ms 1000)))]
      (is (= (:aircraft/position fixtures/ups-2717)
             (get-in picture [ups-icao :aircraft/position])))))

  (testing "a never-positioned aircraft is kept, without a position"
    (let [picture (aircraft/merge-batch {} [fixtures/never-positioned]
                                        captured-at-ms)]
      (is (contains? picture never-positioned-icao))
      (is (not (aircraft/positioned?
                 (get picture never-positioned-icao))))))

  (testing "no position is fabricated for a never-positioned aircraft"
    (let [picture (-> {}
                      (aircraft/merge-batch [fixtures/never-positioned]
                                            captured-at-ms)
                      (aircraft/merge-batch [fixtures/never-positioned]
                                            (+ captured-at-ms 1000)))]
      (is (not (aircraft/positioned?
                 (get picture never-positioned-icao))))))

  (testing "an aircraft missing from one poll is retained, not removed"
    (let [picture (-> {}
                      (aircraft/merge-batch [fixtures/ups-2717
                                             fixtures/never-positioned]
                                            captured-at-ms)
                      (aircraft/merge-batch [fixtures/never-positioned]
                                            (+ captured-at-ms 1000)))]
      (is (contains? picture ups-icao)))))

(deftest age-out
  (let [picture (aircraft/merge-batch {} [fixtures/ups-2717
                                          fixtures/long-silent]
                                      captured-at-ms)
        ;; A plane stamped at exactly the age-out line (adsb-rg1: 2 min).
        at-line (aircraft/merge-batch
                  {}
                  [(assoc fixtures/ups-2717
                          :aircraft/icao "aaedge"
                          :aircraft/seen-s
                          (/ aircraft/age-out-threshold-ms 1000.0))]
                  captured-at-ms)]
    (testing "the long-silent aircraft (well past the line) ages out"
      (let [aged (aircraft/age-out picture (inc captured-at-ms))]
        (is (not (contains? aged long-silent-icao)))))

    (testing "a freshly heard aircraft survives an age-out pass"
      (let [aged (aircraft/age-out picture (inc captured-at-ms))]
        (is (contains? aged ups-icao))))

    (testing "silence exactly at the threshold does not yet age out"
      (let [aged (aircraft/age-out at-line captured-at-ms)]
        (is (contains? aged "aaedge"))))

    (testing "one millisecond past the threshold ages out"
      (let [aged (aircraft/age-out at-line (inc captured-at-ms))]
        (is (not (contains? aged "aaedge")))))))
