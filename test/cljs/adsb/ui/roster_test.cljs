(ns adsb.ui.roster-test
  "The Search + Sheet roster (adsb-66h), in a real browser."
  (:require
    ["@testing-library/react" :as rtl]
    [adsb.events]
    [adsb.fixtures :as fixtures]
    [adsb.stream]
    [adsb.subs]
    [adsb.ui.roster :as roster]
    [cljs.test :refer-macros [deftest is testing use-fixtures async]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

(use-fixtures :each {:after rtl/cleanup})

(rf/reg-event-db :test/set-picture (fn [db [_ picture]] (assoc db :aircraft/picture picture)))

(def ^:private ups fixtures/ups-2717)
(def ^:private ups-icao (:aircraft/icao fixtures/ups-2717))
(def ^:private dal fixtures/squawking-7700)
(def ^:private dal-icao (:aircraft/icao fixtures/squawking-7700))

(defn- picture
  []
  {ups-icao ups
   dal-icao dal
   (:aircraft/icao fixtures/on-the-ground) fixtures/on-the-ground})

(defn- render-roster! []
  (rtl/cleanup)
  (rtl/render (r/as-element [roster/roster])))

(defn- fresh-db! []
  (rf/dispatch-sync [:app/initialize-db]))

(deftest matches-query-is-blank-friendly
  (is (true? (roster/matches-query? ups "")))
  (is (true? (roster/matches-query? ups "UPS")))
  (is (true? (roster/matches-query? ups "ups")))
  (is (true? (roster/matches-query? ups "abc0")))
  (is (false? (roster/matches-query? ups "DAL"))))

(deftest roster-sort-puts-emergencies-first
  (let [rows (roster/roster-sort [ups dal fixtures/on-the-ground])]
    (is (= dal-icao (:aircraft/icao (first rows))) "emergency leads")
    (is (= ups-icao (:aircraft/icao (second rows))) "then higher altitude")))

(deftest sheet-snap-math
  (testing "nearest snap by fraction"
    (is (= :closed (roster/height-fraction->sheet 0.0 0)))
    (is (= :half   (roster/height-fraction->sheet 0.5 0)))
    (is (= :full   (roster/height-fraction->sheet 0.9 0))))
  (testing "velocity commits past the nearest rung"
    ;; 0.55 is nearest :half; a strong upward swipe climbs to :full.
    (is (= :full (roster/height-fraction->sheet 0.55 (+ roster/drag-velocity-threshold 0.01))))
    ;; 0.40 is nearest :half; a strong downward swipe drops to :closed.
    (is (= :closed (roster/height-fraction->sheet 0.40 (- (+ roster/drag-velocity-threshold 0.01))))))
  (testing "tap cycles closed → half → full → closed"
    (is (= :half   (roster/next-sheet :closed)))
    (is (= :full   (roster/next-sheet :half)))
    (is (= :closed (roster/next-sheet :full))))
  (testing "open? is half or full"
    (is (false? (roster/sheet-open? :closed)))
    (is (true?  (roster/sheet-open? :half)))
    (is (true?  (roster/sheet-open? :full)))))

(deftest handle-label-matches-stance-actions
  (testing "desktop is binary: open says hide, closed says show"
    (with-redefs [roster/phone-stance? (constantly false)]
      (is (= "3 aircraft · hide" (roster/handle-label :half 3 3 "")))
      (is (= "3 aircraft · hide" (roster/handle-label :full 3 3 "")))
      (is (= "3 aircraft · show" (roster/handle-label :closed 3 3 "")))
      (is (= "1 of 3 · hide" (roster/handle-label :half 1 3 "UPS")))))
  (testing "phone keeps the three-snap ladder"
    (with-redefs [roster/phone-stance? (constantly true)]
      (is (= "3 aircraft · show" (roster/handle-label :closed 3 3 "")))
      (is (= "3 aircraft · expand" (roster/handle-label :half 3 3 "")))
      (is (= "3 aircraft · hide" (roster/handle-label :full 3 3 ""))))))

(deftest the-roster-renders-the-picture
  (rf-test/run-test-sync
    (rf/dispatch [:test/set-picture (picture)])
    (render-roster!)
    (is (some? (.getByTestId rtl/screen "roster")))
    (is (some? (.getByTestId rtl/screen "roster-search")))
    (is (some? (.getByTestId rtl/screen (str "roster-row:" ups-icao))))
    (is (some? (.getByTestId rtl/screen (str "roster-row:" dal-icao))))
    (is (= "true" (.getAttribute (.getByTestId rtl/screen "roster") "data-open")))))

(deftest find-filters-the-roster-in-place
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (render-roster!)
    (rf/dispatch-sync [:roster/set-query "UPS"])
    (r/flush)
    (-> (rtl/waitFor
          (fn []
            (assert (some? (.getByTestId rtl/screen (str "roster-row:" ups-icao))))
            (assert (nil? (.queryByTestId rtl/screen (str "roster-row:" dal-icao))))))
        (.then (fn [_]
                 (is (some? (.getByTestId rtl/screen (str "roster-row:" ups-icao))))
                 (is (nil? (.queryByTestId rtl/screen (str "roster-row:" dal-icao))))
                 (done)))
        (.catch (fn [err]
                  (is false (str "filter did not land: " err))
                  (done))))))

(deftest collapsing-the-dock-sets-data-open-false
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (render-roster!)
    ;; Default is :half (open). Binary toggle closes in one step.
    (rf/dispatch-sync [:roster/toggle])
    (r/flush)
    (-> (rtl/waitFor
          (fn []
            (assert (= "false"
                       (.getAttribute (.getByTestId rtl/screen "roster")
                                      "data-open")))
            (assert (= "closed"
                       (.getAttribute (.getByTestId rtl/screen "roster")
                                      "data-sheet")))))
        (.then (fn [_]
                 (is (= "false"
                        (.getAttribute (.getByTestId rtl/screen "roster")
                                       "data-open")))
                 (is (= "closed"
                        (.getAttribute (.getByTestId rtl/screen "roster")
                                       "data-sheet")))
                 (done)))
        (.catch (fn [err]
                  (is false (str "collapse did not land: " err))
                  (done))))))

(deftest phone-cycle-walks-the-three-snaps
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (render-roster!)
    (rf/dispatch-sync [:roster/cycle]) ; half → full
    (r/flush)
    (-> (rtl/waitFor
          (fn []
            (assert (= "full"
                       (.getAttribute (.getByTestId rtl/screen "roster")
                                      "data-sheet")))))
        (.then (fn [_]
                 (rf/dispatch-sync [:roster/cycle]) ; full → closed
                 (r/flush)
                 (rtl/waitFor
                   (fn []
                     (assert (= "closed"
                                (.getAttribute (.getByTestId rtl/screen "roster")
                                               "data-sheet")))))))
        (.then (fn [_]
                 (is (= "closed"
                        (.getAttribute (.getByTestId rtl/screen "roster")
                                       "data-sheet")))
                 (done)))
        (.catch (fn [err]
                  (is false (str "cycle did not land: " err))
                  (done))))))

(deftest selecting-scrolls-the-correlated-row-into-view
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (let [!scrolled (atom nil)]
      (with-redefs [roster/scroll-row-into-view! #(reset! !scrolled %)]
        (render-roster!)
        (rf/dispatch-sync [:aircraft/select ups-icao])
        (r/flush)
        (-> (rtl/waitFor
              (fn []
                (assert (= ups-icao @!scrolled))))
            (.then (fn [_]
                     (is (= ups-icao @!scrolled)
                         "map/list selection brings the roster row into view")
                     (done)))
            (.catch (fn [err]
                      (is false (str "scroll-into-view did not fire: " err))
                      (done))))))))

(deftest set-sheet-lands-data-sheet
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (render-roster!)
    (rf/dispatch-sync [:roster/set-sheet :full])
    (r/flush)
    (-> (rtl/waitFor
          (fn []
            (assert (= "full"
                       (.getAttribute (.getByTestId rtl/screen "roster")
                                      "data-sheet")))))
        (.then (fn [_]
                 (is (= "true"
                        (.getAttribute (.getByTestId rtl/screen "roster")
                                       "data-open")))
                 (is (= "full"
                        (.getAttribute (.getByTestId rtl/screen "roster")
                                       "data-sheet")))
                 (done)))
        (.catch (fn [err]
                  (is false (str "full sheet did not land: " err))
                  (done))))))
