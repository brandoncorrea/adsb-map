(ns adsb.ui.roster-test
  (:require ["@testing-library/react" :as rtl]
            [adsb.corejs :as cjs]
            [adsb.events]
            [adsb.fixtures :as fixtures]
            [adsb.stream]
            [adsb.subs]
            [adsb.test-dom :as test-dom]
            [adsb.ui.roster :as roster]
            [clojure.test :refer-macros [deftest is testing use-fixtures async]]
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
  {ups-icao                                ups
   dal-icao                                dal
   (:aircraft/icao fixtures/on-the-ground) fixtures/on-the-ground})

(defn- render-roster! []
  (test-dom/render! [roster/roster]))

(defn- fresh-db! []
  (rf/dispatch-sync [:app/initialize-db]))

(defn- open-roster! []
  (rf/dispatch-sync [:roster/set-sheet :half]))

(deftest matches-query-is-blank-friendly
  (is (roster/matches-query? ups ""))
  (is (roster/matches-query? ups "UPS"))
  (is (roster/matches-query? ups "ups"))
  (is (roster/matches-query? ups "abc0"))
  (is (not (roster/matches-query? ups "DAL"))))

(deftest roster-sort-puts-emergencies-first
  (let [rows (roster/roster-sort [ups dal fixtures/on-the-ground])]
    (is (= dal-icao (:aircraft/icao (first rows))))
    (is (= ups-icao (:aircraft/icao (second rows))))))

(deftest handle-label-matches-stance-actions
  (testing "desktop is binary: open says hide, closed says show"
    (with-redefs [cjs/phone-stance? (constantly false)]
      (is (= "3 aircraft · hide" (roster/handle-label :half 3 3 "")))
      (is (= "3 aircraft · hide" (roster/handle-label :full 3 3 "")))
      (is (= "3 aircraft · show" (roster/handle-label :closed 3 3 "")))
      (is (= "1 of 3 · hide" (roster/handle-label :half 1 3 "UPS")))))
  (testing "phone keeps the three-snap ladder"
    (with-redefs [cjs/phone-stance? (constantly true)]
      (is (= "3 aircraft · show" (roster/handle-label :closed 3 3 "")))
      (is (= "3 aircraft · expand" (roster/handle-label :half 3 3 "")))
      (is (= "3 aircraft · hide" (roster/handle-label :full 3 3 ""))))))

(deftest the-dock-opens-closed
  (rf-test/run-test-sync
    (rf/dispatch [:app/initialize-db])
    (rf/dispatch [:test/set-picture (picture)])
    (render-roster!)
    (let [dock (.getByTestId rtl/screen "roster")]
      (is (= "false" (cjs/get-attribute dock "data-open")))
      (is (= "closed" (cjs/get-attribute dock "data-sheet"))))
    (testing "the body is not rendered at all — no rows, no search field"
      (is (nil? (.queryByTestId rtl/screen "roster-search")))
      (is (nil? (.queryByTestId rtl/screen (str "roster-row:" ups-icao)))))
    (testing "but the rail is on screen and says how to open it — a closed
              drawer the reader cannot find is a deleted feature"
      (is (some? (.getByTestId rtl/screen "roster-toggle"))))))

(defn- click! [el]
  (rtl/act (fn [] (.click rtl/fireEvent el) js/undefined)))

(deftest the-whole-lip-toggles-not-just-the-button
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (render-roster!)
    (let [dock (.getByTestId rtl/screen "roster")]
      (click! dock)
      (-> (rtl/waitFor
            (fn [] (assert (= "true" (cjs/get-attribute dock "data-open")))))
          (.then (fn [_]
                   (is (= "true" (cjs/get-attribute dock "data-open")))
                   (click! (.getByTestId rtl/screen "roster-toggle"))
                   (rtl/waitFor
                     (fn [] (assert (= "false" (cjs/get-attribute dock "data-open")))))))
          (.then (fn [_]
                   (is (= "false" (cjs/get-attribute dock "data-open")))
                   (done)))
          (.catch (fn [err]
                    (is false (str "the lip did not toggle the drawer: " err))
                    (done)))))))

(deftest a-row-click-is-not-a-tap-on-the-drawer
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (open-roster!)
    (render-roster!)
    (let [dock (.getByTestId rtl/screen "roster")]
      (click! (.getByTestId rtl/screen (str "roster-row:" ups-icao)))
      (-> (rtl/waitFor
            (fn [] (assert (= ups-icao @(rf/subscribe [:aircraft/selected-icao])))))
          (.then (fn [_]
                   (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao])))
                   (is (= "half" (cjs/get-attribute dock "data-sheet")))
                   (done)))
          (.catch (fn [err]
                    (is false (str "row click did not land: " err))
                    (done)))))))

(defn- pointer! [el kind opts]
  (rtl/act (fn []
             (.dispatchEvent el (js/PointerEvent. kind
                                                  (clj->js (merge {:bubbles    true
                                                                   :cancelable true
                                                                   :pointerId  1
                                                                   :button     0}
                                                                  opts))))
             js/undefined)))

(deftest the-drawer-is-not-empty-while-it-opens
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (render-roster!)
    (with-redefs [cjs/phone-stance? (constantly true)]
      (let [dock (.getByTestId rtl/screen "roster")]
        (is (nil? (.queryByTestId rtl/screen "roster-list")))
        (pointer! dock "pointerdown" {:clientY 700})
        (pointer! dock "pointermove" {:clientY 500})
        (-> (rtl/waitFor
              (fn [] (assert (some? (.queryByTestId rtl/screen "roster-list")))))
            (.then (fn [_]
                     (is (some? (.getByTestId rtl/screen "roster-list")))
                     (is (some? (.getByTestId rtl/screen (str "roster-row:" ups-icao))))
                     (is (= "false" (cjs/get-attribute dock "data-open")))
                     (done)))
            (.catch (fn [err]
                      (is false (str "the drawer stayed blank mid-drag: " err))
                      (done))))))))

(deftest a-press-is-not-a-gesture
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (render-roster!)
    (with-redefs [cjs/phone-stance? (constantly true)]
      (let [dock  (.getByTestId rtl/screen "roster")
            klass (cjs/get-attribute dock "class")]
        (pointer! dock "pointerdown" {:clientY 700})
        (pointer! dock "pointermove" {:clientY 697})
        ;; A negative assertion — the press must NOT start a gesture — has no
        ;; positive signal to wait on. `(rtl/waitFor (some? dock))` was vacuous:
        ;; dock is non-nil before the press, so waitFor resolved on its first
        ;; check and asserted against pre-settle DOM. If a 3px move ever started
        ;; a drag, is-dragging / inline height would land a frame or two later,
        ;; unseen. settle! drains those frames so the assertions are evidence.
        (-> (test-dom/settle!)
            (.then (fn [_]
                     (is (= klass (cjs/get-attribute dock "class")))
                     (is (nil? (.queryByTestId rtl/screen "roster-list")))
                     (is (= "" (-> dock .-style .-height)))
                     (done)))
            (.catch (fn [err]
                      (is false (str "a press disturbed the drawer: " err))
                      (done))))))))

(deftest a-release-ends-the-gesture-so-the-mouse-cannot-hang-on
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (render-roster!)
    (with-redefs [cjs/phone-stance? (constantly true)]
      (let [dock (.getByTestId rtl/screen "roster")]
        (pointer! dock "pointerdown" {:clientY 700})
        (pointer! dock "pointermove" {:clientY 300})
        (-> (rtl/waitFor (fn [] (assert (cjs/has-class? dock "is-dragging"))))
            (.then (fn [_]
                     (pointer! dock "pointerup" {:clientY 300})
                     (rtl/waitFor
                       (fn []
                         (assert (not (cjs/has-class? dock "is-dragging")))
                         (assert (not (cjs/has-class? dock "is-settling")))))))
            (.then (fn [_]
                     (let [committed (cjs/get-attribute dock "data-sheet")
                           settled-h (-> dock .-style .-height)]
                       (pointer! dock "pointermove" {:clientY 600 :buttons 0})
                       (r/flush)
                       (is (= committed (cjs/get-attribute dock "data-sheet")))
                       (is (not (cjs/has-class? dock "is-dragging")))
                       (is (= settled-h (.. dock -style -height)))
                       (done))))
            (.catch (fn [err]
                      (is false (str "the release did not end the gesture: " err))
                      (done))))))))

(deftest the-roster-renders-the-picture
  (rf-test/run-test-sync
    (rf/dispatch [:test/set-picture (picture)])
    (open-roster!)
    (render-roster!)
    (is (some? (.getByTestId rtl/screen "roster")))
    (is (some? (.getByTestId rtl/screen "roster-search")))
    (is (some? (.getByTestId rtl/screen (str "roster-row:" ups-icao))))
    (is (some? (.getByTestId rtl/screen (str "roster-row:" dal-icao))))
    (is (= "true" (cjs/get-attribute (.getByTestId rtl/screen "roster") "data-open")))))

(deftest find-filters-the-roster-in-place
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (open-roster!)
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
    (open-roster!)
    (render-roster!)
    (rf/dispatch-sync [:roster/toggle])
    (r/flush)
    (-> (rtl/waitFor
          (fn []
            (assert (= "false"
                       (cjs/get-attribute (.getByTestId rtl/screen "roster")
                                          "data-open")))
            (assert (= "closed"
                       (cjs/get-attribute (.getByTestId rtl/screen "roster")
                                          "data-sheet")))))
        (.then (fn [_]
                 (is (= "false"
                        (cjs/get-attribute (.getByTestId rtl/screen "roster")
                                           "data-open")))
                 (is (= "closed"
                        (cjs/get-attribute (.getByTestId rtl/screen "roster")
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
    (rf/dispatch-sync [:roster/cycle])
    (rf/dispatch-sync [:roster/cycle])
    (r/flush)
    (-> (rtl/waitFor
          (fn []
            (assert (= "full"
                       (cjs/get-attribute (.getByTestId rtl/screen "roster")
                                          "data-sheet")))))
        (.then (fn [_]
                 (rf/dispatch-sync [:roster/cycle])
                 (r/flush)
                 (rtl/waitFor
                   (fn []
                     (assert (= "closed"
                                (cjs/get-attribute (.getByTestId rtl/screen "roster")
                                                   "data-sheet")))))))
        (.then (fn [_]
                 (is (= "closed"
                        (cjs/get-attribute (.getByTestId rtl/screen "roster")
                                           "data-sheet")))
                 (done)))
        (.catch (fn [err]
                  (is false (str "cycle did not land: " err))
                  (done))))))

(deftest selecting-scrolls-the-correlated-row-into-view
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (open-roster!)
    (let [!scrolled (atom nil)]
      (with-redefs [roster/scroll-row-into-view! #(reset! !scrolled %)]
        (render-roster!)
        (rf/dispatch-sync [:aircraft/select ups-icao])
        (r/flush)
        (-> (rtl/waitFor
              (fn []
                (assert (= ups-icao @!scrolled))))
            (.then (fn [_]
                     (is (= ups-icao @!scrolled))
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
                       (cjs/get-attribute (.getByTestId rtl/screen "roster")
                                          "data-sheet")))))
        (.then (fn [_]
                 (is (= "true"
                        (cjs/get-attribute (.getByTestId rtl/screen "roster")
                                           "data-open")))
                 (is (= "full"
                        (cjs/get-attribute (.getByTestId rtl/screen "roster")
                                           "data-sheet")))
                 (done)))
        (.catch (fn [err]
                  (is false (str "full sheet did not land: " err))
                  (done))))))
