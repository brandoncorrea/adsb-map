(ns adsb.events-test
  (:require [adsb.events :as events]
            [adsb.worker :as worker]
            [clojure.test :refer-macros [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]))

;; Selecting an aircraft now fires :enrich/ensure, whose fetch reaches the
;; network. These tests exercise click intent, not enrichment — swallow it.
(defn- swallow-enrich! [] (rf/reg-fx :enrich/fetch! (fn [_])))

(def ^:private icao "abc0e4")

;; ---- click-fx: the pure click-intent decision -----------------------------
;; The old module-atom machine lived in aircraft_layer.cljs; its select/arm/
;; follow/dedupe semantics are now this one pure function, tested with literals.

(deftest click-fx-single-click-on-nothing-selects-now
  (let [{:keys [fx]} (events/click-fx {} 100000 {:icao icao :detail 1})]
    (testing "a fresh single click selects, cancelling any armed deselect"
      (is (some #{[:dispatch [:aircraft/select icao]]} fx))
      (is (some #{[:aircraft/cancel-deselect nil]} fx)))))

(deftest click-fx-single-click-on-selected-arms-a-delayed-deselect
  (testing "clicking the already-selected aircraft ARMS a deselect — it does
            not deselect immediately, so a double-click can still cancel it"
    (is (= {:fx [[:aircraft/arm-deselect icao]]}
           (events/click-fx {:aircraft/selected-icao icao} 100000
                            {:icao icao :detail 1})))))

(deftest click-fx-double-click-follows-and-cancels-any-armed-deselect
  (let [{:keys [db fx]} (events/click-fx {} 100000 {:icao icao :detail 2})]
    (testing "a double-click follows, stamps the follow clock, and cancels the
              armed deselect so the click never deselects behind the follow"
      (is (= 100000 (:aircraft/last-follow-ms db)))
      (is (some #{[:dispatch [:aircraft/dblclick-follow icao]]} fx))
      (is (some #{[:aircraft/cancel-deselect nil]} fx)))))

(deftest click-fx-dedupes-the-duplicate-double-click-within-the-window
  (testing "a second detail>=2 within follow-dedupe-ms fires NO follow — the
            dedupe that suppressed the click(detail>=2)/dblclick pair survives"
    (let [db     {:aircraft/last-follow-ms 100000}
          {:keys [db fx]} (events/click-fx db (+ 100000 events/follow-dedupe-ms)
                                           {:icao icao :detail 2})]
      (is (nil? db) "no new follow stamp on the deduped click")
      (is (not (some #{[:dispatch [:aircraft/dblclick-follow icao]]} fx)))
      (is (= [[:aircraft/cancel-deselect nil]] fx))))
  (testing "past the window, the double-click follows again"
    (let [db {:aircraft/last-follow-ms 100000}
          {:keys [fx]} (events/click-fx db (+ 100001 events/follow-dedupe-ms)
                                        {:icao icao :detail 2})]
      (is (some #{[:dispatch [:aircraft/dblclick-follow icao]]} fx)))))

;; ---- :aircraft/click through the event loop: the timer effects ------------

(deftest single-click-on-unselected-selects-through-the-event
  (rf-test/run-test-sync
    (swallow-enrich!)
    (rf/dispatch [:app/initialize-db])
    (rf/dispatch [:aircraft/click {:icao icao :detail 1}])
    (is (= icao @(rf/subscribe [:aircraft/selected-icao])))))

(deftest arming-a-deselect-fires-after-the-delay
  (rf-test/run-test-sync
    (swallow-enrich!)
    (let [!armed (atom nil)]
      (with-redefs [worker/timeout       (fn [f _ms] (reset! !armed f) :deselect-timer)
                    worker/clear-timeout (constantly nil)]
        (rf/dispatch [:app/initialize-db])
        (rf/dispatch [:aircraft/select icao])
        (is (= icao @(rf/subscribe [:aircraft/selected-icao])))
        (rf/dispatch [:aircraft/click {:icao icao :detail 1}])
        (testing "the deselect is only ARMED — selection still stands"
          (is (fn? @!armed))
          (is (= icao @(rf/subscribe [:aircraft/selected-icao]))))
        (testing "when the armed timer fires, it deselects"
          (@!armed)
          (is (nil? @(rf/subscribe [:aircraft/selected-icao]))))))))

(deftest a-double-click-cancels-the-armed-deselect-and-follows
  (rf-test/run-test-sync
    (swallow-enrich!)
    (let [!armed   (atom nil)
          !cleared (atom [])]
      (with-redefs [worker/timeout       (fn [f _ms] (reset! !armed f) :deselect-timer)
                    worker/clear-timeout (fn [id] (swap! !cleared conj id))]
        (rf/dispatch [:app/initialize-db])
        (rf/dispatch [:aircraft/select icao])
        (rf/dispatch [:aircraft/click {:icao icao :detail 1}])
        (is (fn? @!armed) "single click on the selected aircraft armed a deselect")
        (rf/dispatch [:aircraft/click {:icao icao :detail 2}])
        (testing "the double-click cancelled the armed deselect"
          (is (= [:deselect-timer] @!cleared)))
        (testing "and followed instead — the aircraft stays selected"
          (is (= icao @(rf/subscribe [:aircraft/selected-icao]))))))))
