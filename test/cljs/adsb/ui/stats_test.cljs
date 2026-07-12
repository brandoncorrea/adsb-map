(ns adsb.ui.stats-test
  "The corner stats readout, rendered in a real browser. Two things must
  hold: a present scalar renders as its number with a unit, and an absent
  scalar renders as a DASH — never a fabricated zero. The raw value rides
  on `data-value` so the assertion is exact, unclouded by the unit suffix."
  (:require
    ["@testing-library/react" :as rtl]
    [adsb.stream]                                 ; registers :stats/session
    [adsb.ui.stats :as stats]
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

(use-fixtures :each {:after rtl/cleanup})

;; Seed :stats/session directly — the owning event lives in adsb.stream and
;; speaks wire JSON, noise for a render test.
(rf/reg-event-db :test/set-stats
  (fn [db [_ session]] (assoc db :stats/session session)))

(defn- row [testid] (.getByTestId rtl/screen testid))
(defn- data-value [testid] (.getAttribute (row testid) "data-value"))
(defn- text [testid] (.-textContent (row testid)))

(deftest renders-present-scalars
  (testing "a known max range and message rate render as numbers with units"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-stats {:stats/max-range-km 312
                                     :stats/message-rate 148}])
      (rtl/render (r/as-element [stats/stats-readout]))
      (is (= "312" (data-value "stats-max-range")))
      (is (= "148" (data-value "stats-message-rate")))
      (is (re-find #"312" (text "stats-max-range")))
      (is (re-find #"km" (text "stats-max-range")))
      (is (re-find #"148" (text "stats-message-rate"))))))

(deftest dashes-absent-scalars
  (testing "an absent scalar renders a dash, not a zero"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-stats {}])
      (rtl/render (r/as-element [stats/stats-readout]))
      (is (= "" (data-value "stats-max-range"))
          "no raw value carried when the scalar is absent")
      (is (= "" (data-value "stats-message-rate")))
      (is (re-find (re-pattern stats/em-dash) (text "stats-max-range")))
      (is (re-find (re-pattern stats/em-dash) (text "stats-message-rate")))
      (is (not (re-find #"0" (text "stats-max-range")))
          "absent is a dash, never a fabricated zero"))))

(deftest dashes-a-partially-known-readout
  (testing "each scalar is judged on its own: a known rate shows while an
            unknown range dashes"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-stats {:stats/message-rate 90}])
      (rtl/render (r/as-element [stats/stats-readout]))
      (is (= "" (data-value "stats-max-range")))
      (is (= "90" (data-value "stats-message-rate"))))))
