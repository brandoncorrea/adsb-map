(ns adsb.ui.splash-test
  (:require [adsb.corejs :as cjs]
            [adsb.ui.splash]
            [clojure.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]))

(def ^:private !node (atom nil))

(use-fixtures :each
  {:before (fn []
             (let [el   (cjs/create-element "div")
                   note (cjs/create-element "div")]
               (set! (.-id el) "adsb-splash")
               (set! (.-className note) "adsb-splash-note")
               (set! (.-textContent note) "Tuning in…")
               (cjs/append-child! el note)
               (cjs/append-child! (.-body js/document) el)
               (reset! !node el)))
   :after  (fn []
             (when-let [el @!node]
               (cjs/remove! el)
               (reset! !node nil)))})

(deftest map-ready-records-readiness
  (testing "[:map/ready] flips the app-db readiness flag"
    (rf-test/run-test-sync
      (rf/reg-sub :test/ready? (fn [db _] (:map/ready? db)))
      (is (not @(rf/subscribe [:test/ready?])))
      (rf/dispatch [:map/ready])
      (is @(rf/subscribe [:test/ready?])))))

(deftest map-ready-fades-the-splash
  (testing "[:map/ready] adds is-gone, which the CSS transitions to opacity 0"
    (rf-test/run-test-sync
      (is (not (cjs/has-class? @!node "is-gone")))
      (rf/dispatch [:map/ready])
      (is (cjs/has-class? @!node "is-gone")))))

(deftest map-load-failed-turns-the-splash-into-a-retry
  (testing "[:map/load-failed] records the failure, wears the error state, tells
            the reader what happened, and makes the whole sheet a tap-to-reload"
    (rf-test/run-test-sync
      (rf/reg-sub :test/failed? (fn [db _] (:map/load-failed? db)))
      (is (not @(rf/subscribe [:test/failed?])))
      (is (not (cjs/has-class? @!node "is-error")))
      (rf/dispatch [:map/load-failed])
      (is @(rf/subscribe [:test/failed?]))
      (is (cjs/has-class? @!node "is-error"))
      (is (= "Couldn't reach the map. Tap to retry." (.-textContent (cjs/select @!node ".adsb-splash-note"))))
      ;; TODO: Test with addEventListener / dispatchEvent
      (is (fn? (.-onclick @!node)) "and a tap now reloads the page — the refresh, one tap away"))))

(deftest load-failed-is-safe-with-no-splash
  (testing "if the splash is already gone, marking a failure is a quiet no-op —
            the effect finds no node and throws nothing"
    (cjs/remove! @!node)
    (reset! !node nil)
    (rf-test/run-test-sync
      (is (nil? (rf/dispatch [:map/load-failed]))))))

(deftest dismiss-is-idempotent-and-safe-with-no-splash
  (testing "a theme re-print fires load again after the node is already gone —
            dismissing a missing splash must be a quiet no-op, never a throw"
    (cjs/remove! @!node)
    (reset! !node nil)
    (rf-test/run-test-sync
      (is (nil? (rf/dispatch [:map/ready]))))))
