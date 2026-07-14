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

;; The dock now OPENS CLOSED (roster/default-sheet) — the map is the
;; product and gets the viewport until the reader asks for the roster. The
;; body, and so every row and the search field, renders only when open. So
;; a test that is about ROWS says so out loud and opens the sheet first;
;; the default itself is pinned once, in the-dock-opens-closed.
(defn- open-roster! []
  (rf/dispatch-sync [:roster/set-sheet :half]))

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
    (is (true?  (roster/sheet-open? :full))))
  (testing "snap heights are ordered closed < half < full"
    (let [c (roster/sheet-height-px :closed)
          h (roster/sheet-height-px :half)
          f (roster/sheet-height-px :full)]
      (is (< c h f))
      (is (pos? c))))
  (testing "ease-out-cubic is identity at ends and soft in the middle"
    (is (= 0.0 (roster/ease-out-cubic 0)))
    (is (= 1.0 (roster/ease-out-cubic 1)))
    (is (< 0.5 (roster/ease-out-cubic 0.5) 1.0)
        "front-loaded progress so the settle decelerates into the snap")))

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

(deftest the-dock-opens-closed
  (rf-test/run-test-sync
    (rf/dispatch [:app/initialize-db])
    (rf/dispatch [:test/set-picture (picture)])
    (render-roster!)
    (let [dock (.getByTestId rtl/screen "roster")]
      (is (= "false" (.getAttribute dock "data-open")))
      (is (= "closed" (.getAttribute dock "data-sheet"))))
    (testing "the body is not rendered at all — no rows, no search field"
      (is (nil? (.queryByTestId rtl/screen "roster-search")))
      (is (nil? (.queryByTestId rtl/screen (str "roster-row:" ups-icao)))))
    (testing "but the rail is on screen and says how to open it — a closed
              drawer the reader cannot find is a deleted feature"
      (is (some? (.getByTestId rtl/screen "roster-toggle"))))))

;; The drag and the tap live on the SHELL, not on the handle button (the
;; button is smaller than the lip a finger aims at — that was the finnicky
;; drag). These pin the consequences: every part of the lip toggles, the
;; button still toggles by bubbling, and the body does not toggle at all.
(defn- click! [el]
  (rtl/act (fn [] (.click rtl/fireEvent el) js/undefined)))

(deftest the-whole-lip-toggles-not-just-the-button
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (render-roster!)
    (let [dock (.getByTestId rtl/screen "roster")]
      ;; The rail's padding and the safe-area band belong to the shell, and
      ;; the shell is the grab target — a tap anywhere on the lip opens it.
      (click! dock)
      (-> (rtl/waitFor
            (fn [] (assert (= "true" (.getAttribute dock "data-open")))))
          (.then (fn [_]
                   (is (= "true" (.getAttribute dock "data-open")))
                   ;; The handle button keeps its role, label and focus ring;
                   ;; its click bubbles to the shell, so a pointer tap on it —
                   ;; and Enter on it, which fires the same click — still toggle.
                   (click! (.getByTestId rtl/screen "roster-toggle"))
                   (rtl/waitFor
                     (fn [] (assert (= "false" (.getAttribute dock "data-open")))))))
          (.then (fn [_]
                   (is (= "false" (.getAttribute dock "data-open")))
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
      ;; A row's click bubbles through the shell's handler too. It comes from
      ;; the body, so the drawer must ignore it — selecting an aircraft may
      ;; not cycle the sheet out from under the reader.
      (click! (.getByTestId rtl/screen (str "roster-row:" ups-icao)))
      (-> (rtl/waitFor
            (fn [] (assert (= ups-icao @(rf/subscribe [:aircraft/selected-icao])))))
          (.then (fn [_]
                   (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao]))
                       "the row still selects")
                   (is (= "half" (.getAttribute dock "data-sheet"))
                       "and the sheet stays exactly where it was")
                   (done)))
          (.catch (fn [err]
                    (is false (str "row click did not land: " err))
                    (done)))))))

(defn- pointer! [el kind opts]
  (rtl/act (fn []
             (.dispatchEvent el (js/PointerEvent. kind
                                                  (clj->js (merge {:bubbles true
                                                                   :cancelable true
                                                                   :pointerId 1
                                                                   :button 0}
                                                                  opts))))
             js/undefined)))

(deftest the-drawer-is-not-empty-while-it-opens
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (render-roster!)
    ;; The drag is the phone drawer's gesture, and the browser suite runs at
    ;; desktop width — say so out loud rather than resizing the window.
    (with-redefs [roster/phone-stance? (constantly true)]
      (let [dock (.getByTestId rtl/screen "roster")]
        (is (nil? (.queryByTestId rtl/screen "roster-list"))
            "closed and untouched: no body")
        ;; A finger on the lip, pulling up. NOT released.
        (pointer! dock "pointerdown" {:clientY 700})
        (pointer! dock "pointermove" {:clientY 500})
        (-> (rtl/waitFor
              (fn [] (assert (some? (.queryByTestId rtl/screen "roster-list")))))
            (.then (fn [_]
                     (is (some? (.getByTestId rtl/screen "roster-list"))
                         "the roster is on screen mid-drag — the reader can see
                          what they are pulling up, before they commit to it")
                     (is (some? (.getByTestId rtl/screen (str "roster-row:" ups-icao)))
                         "and it is the real picture, not a placeholder")
                     (is (= "false" (.getAttribute dock "data-open"))
                         "while the COMMITTED state is still closed — the sheet
                          does not snap until the finger lifts")
                     (done)))
            (.catch (fn [err]
                      (is false (str "the drawer stayed blank mid-drag: " err))
                      (done))))))))

(deftest a-press-is-not-a-gesture
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture (picture)])
    (render-roster!)
    (with-redefs [roster/phone-stance? (constantly true)]
      (let [dock  (.getByTestId rtl/screen "roster")
            klass (.getAttribute dock "class")]
        ;; Finger down, and a tremble well inside the tap slop. Being TOUCHED
        ;; is not a state of the drawer: the handle the reader is pressing has
        ;; to look exactly like the handle they were about to press, so not one
        ;; class may flip and no body may mount.
        (pointer! dock "pointerdown" {:clientY 700})
        (pointer! dock "pointermove" {:clientY 697})
        (-> (rtl/waitFor (fn [] (assert (some? dock))))
            (.then (fn [_]
                     (is (= klass (.getAttribute dock "class"))
                         "the class list is untouched — no is-open, no is-dragging,
                          so every :not(.is-open) rule still matches and the rail,
                          the grip and the label do not move")
                     (is (nil? (.queryByTestId rtl/screen "roster-list"))
                         "and nothing mounted behind them")
                     (is (= "" (.. dock -style -height))
                         "and no inline geometry was written")
                     (done)))
            (.catch (fn [err]
                      (is false (str "a press disturbed the drawer: " err))
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
    (is (= "true" (.getAttribute (.getByTestId rtl/screen "roster") "data-open")))))

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
    ;; Opened above; the binary toggle closes it in one step.
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
    ;; From the CLOSED default, the cycle now walks the full ring in one
    ;; test: closed -> half -> full -> closed.
    (rf/dispatch-sync [:roster/cycle]) ; closed → half
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
    ;; The sheet must be OPEN: scrolling a row into view inside a shut
    ;; drawer would be a no-op with nothing to scroll (roster/default-sheet
    ;; is :closed, and the scroll track guards on sheet-open?).
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
