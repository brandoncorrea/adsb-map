(ns adsb.re-frame-isolation-test
  (:require [clojure.test :refer-macros [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]))

(rf/reg-event-db :test/set-callsign (fn [db [_ callsign]] (assoc db :callsign callsign)))
(rf/reg-sub :test/callsign (fn [db _] (:callsign db)))

(deftest first-test-writes-app-db
  (testing "a dispatch inside run-test-sync updates this test's app-db"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-callsign "UPS2717"])
      (is (= "UPS2717" @(rf/subscribe [:test/callsign]))))))

(deftest second-test-gets-a-fresh-app-db
  (testing "the callsign written by the previous test is gone"
    (rf-test/run-test-sync
      (is (nil? @(rf/subscribe [:test/callsign]))))))
