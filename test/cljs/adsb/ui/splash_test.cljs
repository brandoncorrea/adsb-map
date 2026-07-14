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
             ;; The static markup index.html ships: the sheet AND its breathing
             ;; note, so the failure effect (which rewrites the note) has the
             ;; child it reaches for in production.
             (let [el   (.createElement js/document "div")
                   note (.createElement js/document "div")]
               (set! (.-id el) "adsb-splash")
               (set! (.-className note) "adsb-splash-note")
               (set! (.-textContent note) "Tuning in…")
               (.appendChild el note)
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

(deftest map-load-failed-turns-the-splash-into-a-retry
  (testing "[:map/load-failed] records the failure, wears the error state, tells
            the reader what happened, and makes the whole sheet a tap-to-reload"
    (rf-test/run-test-sync
      (rf/reg-sub :test/failed? (fn [db _] (:map/load-failed? db)))
      (is (not @(rf/subscribe [:test/failed?])) "no failure before the last retry")
      (is (not (.contains (.-classList @!node) "is-error")) "and no error state")
      (rf/dispatch [:map/load-failed])
      (is (true? @(rf/subscribe [:test/failed?])) "the db records the dead load")
      (is (.contains (.-classList @!node) "is-error")
          "the sheet wears its error state, so CSS stills it and shows a pointer")
      (is (= "Couldn't reach the map. Tap to retry."
             (.-textContent (.querySelector @!node ".adsb-splash-note")))
          "the note says what happened and what to do — no lying spinner")
      (is (fn? (.-onclick @!node))
          "and a tap now reloads the page — the refresh, one tap away"))))

(deftest load-failed-is-safe-with-no-splash
  (testing "if the splash is already gone, marking a failure is a quiet no-op —
            the effect finds no node and throws nothing"
    (.remove @!node)
    (reset! !node nil)
    (rf-test/run-test-sync
      (is (nil? (rf/dispatch [:map/load-failed]))
          "no #adsb-splash in the DOM: nothing to repaint, and nothing thrown"))))

(deftest dismiss-is-idempotent-and-safe-with-no-splash
  (testing "a theme re-print fires load again after the node is already gone —
            dismissing a missing splash must be a quiet no-op, never a throw"
    (.remove @!node)
    (reset! !node nil)
    (rf-test/run-test-sync
      (is (nil? (rf/dispatch [:map/ready]))
          "no #adsb-splash in the DOM: nothing to fade, and nothing thrown"))))
