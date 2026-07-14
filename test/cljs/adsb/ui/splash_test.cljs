(ns adsb.ui.splash-test
  "The cold-load splash is dismissed when — and only when — the map paints its
  first frame. The map view dispatches [:map/ready] from the MapLibre `load`
  event (adsb.map.view); here we prove the event records readiness and the
  effect fades the static #adsb-splash node out. The map itself is not built —
  this is the dismissal wiring, not the map's."
  (:require
    [adsb.ui.splash]
    [cljs.test :refer-macros [deftest is testing use-fixtures]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]))

;; A stand-in for index.html's static splash: a #adsb-splash node in the DOM
;; for the effect to find, torn down after each test so no id leaks across.
(def ^:private !node (atom nil))

(use-fixtures :each
  {:before (fn []
             (let [el (.createElement js/document "div")]
               (set! (.-id el) "adsb-splash")
               (.appendChild (.-body js/document) el)
               (reset! !node el)))
   :after  (fn []
             (when-let [el @!node]
               (.remove el)
               (reset! !node nil)))})

(deftest map-ready-records-readiness
  (testing "[:map/ready] flips the app-db readiness flag"
    (rf-test/run-test-sync
      (rf/reg-sub :test/ready? (fn [db _] (:map/ready? db)))
      (is (not @(rf/subscribe [:test/ready?])) "not ready before the first frame")
      (rf/dispatch [:map/ready])
      (is (true? @(rf/subscribe [:test/ready?])) "ready once the map has painted"))))

(deftest map-ready-fades-the-splash
  (testing "[:map/ready] adds is-gone, which the CSS transitions to opacity 0"
    (rf-test/run-test-sync
      (is (not (.contains (.-classList @!node) "is-gone"))
          "the splash starts opaque, over the paper")
      (rf/dispatch [:map/ready])
      (is (.contains (.-classList @!node) "is-gone")
          "and fades the moment the chart's first frame lands"))))

(deftest dismiss-is-idempotent-and-safe-with-no-splash
  (testing "a theme re-print fires load again after the node is already gone —
            dismissing a missing splash must be a quiet no-op, never a throw"
    (.remove @!node)
    (reset! !node nil)
    (rf-test/run-test-sync
      (is (nil? (rf/dispatch [:map/ready]))
          "no #adsb-splash in the DOM: nothing to fade, and nothing thrown"))))
