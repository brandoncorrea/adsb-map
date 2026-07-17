(ns adsb.aircraft-test
  (:require [adsb.aircraft :as aircraft]
            [adsb.fixtures :as fixtures]
            [clojure.test :refer [deftest is testing]]))

(def ^:private never-positioned-icao (:aircraft/icao fixtures/never-positioned))

(deftest stale?
  (testing "an aircraft heard within the threshold is still fresh"
    (is (not (aircraft/stale? {:aircraft/seen-at-ms 0}
                              aircraft/stale-threshold-ms))))

  (testing "an aircraft silent past the threshold is stale"
    (is (aircraft/stale? {:aircraft/seen-at-ms 0}
                         (inc aircraft/stale-threshold-ms)))))

(deftest display-name
  (testing "prefers the callsign, falls back to the icao"
    (is (= "UPS2717" (aircraft/display-name fixtures/ups-2717)))
    (is (= never-positioned-icao
           (aircraft/display-name
             (dissoc fixtures/never-positioned :aircraft/callsign))))))

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
    (is (= :hijack (aircraft/squawk->emergency-kind "7500")))
    (is (= :radio-failure (aircraft/squawk->emergency-kind "7600")))
    (is (= :general (aircraft/squawk->emergency-kind "7700"))))

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
