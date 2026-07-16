(ns adsb.map.follow-test
  (:require [adsb.events]
            [adsb.fixtures :as fixtures]
            [adsb.map.follow :as follow]
            [adsb.map.maplibre :as maplibre]
            [adsb.stream]
            [adsb.subs]
            [clojure.test :refer-macros [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(rf/reg-event-db :test/set-picture
  (fn [db [_ picture]] (assoc db :aircraft/picture picture)))

(def ^:private ups fixtures/ups-2717)
(def ^:private ups-icao (:aircraft/icao ups))
(def ^:private ups-pos (:aircraft/position ups))

(defn- moved []
  (update-in ups [:aircraft/position :geo/lon] #(+ % 0.05)))

(defn- recording-map []
  (let [!rec  (atom {:ease-to [] :fly-to [] :dragstart nil})
        !drag (atom nil)
        !move (atom nil)]
    {:rec           !rec
     :fire-drag!    (fn [] (when-let [f @!drag] (f)))
     :fire-moveend! (fn [] (when-let [f @!move] (f)))
     :m             (reify maplibre/Map
                      (ease-to! [_ pos]
                        (swap! !rec update :ease-to conj pos))
                      (fly-to! [_ pos]
                        (swap! !rec update :fly-to conj pos))
                      (on-move! [_ f]
                        (reset! !move f))
                      (on-drag-start! [_ f]
                        (reset! !drag f)
                        (swap! !rec assoc :dragstart true)))}))

(deftest following?-only-follow
  (is (follow/following? :follow))
  (is (not (follow/following? :free)))
  (is (not (follow/following? nil)))
  (is (not (follow/following? :course-up))))

(deftest should-track?-needs-mode-and-position
  (is (follow/should-track? :follow ups))
  (is (not (follow/should-track? :free ups)))
  (is (not (follow/should-track? :follow nil)))
  (is (not (follow/should-track? :follow (dissoc ups :aircraft/position)))))

(deftest camera-defaults-to-free
  (rf-test/run-test-sync
    (rf/dispatch [:app/initialize-db])
    (is (= :free @(rf/subscribe [:map/camera-mode])))))

(deftest map-select-stays-free-roster-focus-enters-follow
  (rf-test/run-test-sync
    (rf/dispatch [:app/initialize-db])
    (rf/dispatch [:test/set-picture {ups-icao ups}])
    (rf/reg-fx :map/fly-to (fn [_]))

    (testing "map click (plain select) leaves the camera free"
      (rf/dispatch [:aircraft/select ups-icao])
      (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao])))
      (is (= :free @(rf/subscribe [:map/camera-mode]))))

    (testing "deselect drops camera mode"
      (rf/dispatch [:aircraft/select ups-icao])
      (is (nil? @(rf/subscribe [:aircraft/selected-icao])))
      (is (= :free @(rf/subscribe [:map/camera-mode]))))

    (testing "roster focus selects and enters follow"
      (rf/dispatch [:aircraft/focus ups-icao])
      (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao])))
      (is (= :follow @(rf/subscribe [:map/camera-mode]))))

    (testing "user pan releases to free while selection stays"
      (rf/dispatch [:map/user-pan])
      (is (= :free @(rf/subscribe [:map/camera-mode])))
      (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao]))))

    (testing "toggle-follow re-enters follow"
      (rf/dispatch [:map/toggle-follow])
      (is (= :follow @(rf/subscribe [:map/camera-mode]))))

    (testing "toggle-follow while following frees the camera"
      (rf/dispatch [:map/toggle-follow])
      (is (= :free @(rf/subscribe [:map/camera-mode]))))

    (testing "clear-selection drops follow"
      (rf/dispatch [:map/follow])
      (is (= :follow @(rf/subscribe [:map/camera-mode])))
      (rf/dispatch [:aircraft/clear-selection])
      (is (nil? @(rf/subscribe [:aircraft/selected-icao])))
      (is (= :free @(rf/subscribe [:map/camera-mode]))))))

(deftest age-out-clears-follow-with-selection
  (rf-test/run-test-sync
    (rf/dispatch [:app/initialize-db])
    (rf/dispatch [:test/set-picture {ups-icao ups}])
    (rf/reg-fx :map/fly-to (fn [_]))
    (rf/dispatch [:aircraft/focus ups-icao])
    (is (= :follow @(rf/subscribe [:map/camera-mode])))
    (rf/dispatch [:test/set-picture {}])
    (rf/dispatch [:ui/tick 1000000])
    (is (nil? @(rf/subscribe [:aircraft/selected-icao])))
    (is (= :free @(rf/subscribe [:map/camera-mode])))))

(deftest selecting-another-plane-resets-to-free
  (rf-test/run-test-sync
    (let [dal      fixtures/squawking-7700
          dal-icao (:aircraft/icao dal)]
      (rf/dispatch [:app/initialize-db])
      (rf/dispatch [:test/set-picture {ups-icao ups dal-icao dal}])
      (rf/reg-fx :map/fly-to (fn [_]))
      (rf/dispatch [:aircraft/focus ups-icao])
      (is (= :follow @(rf/subscribe [:map/camera-mode])))
      (rf/dispatch [:aircraft/select dal-icao])
      (is (= dal-icao @(rf/subscribe [:aircraft/selected-icao])))
      (is (= :free @(rf/subscribe [:map/camera-mode]))))))

(deftest follow-without-position-is-a-no-op
  (rf-test/run-test-sync
    (let [anon fixtures/never-positioned
          icao (:aircraft/icao anon)]
      (rf/dispatch [:app/initialize-db])
      (rf/dispatch [:test/set-picture {icao anon}])
      (rf/dispatch [:aircraft/select icao])
      (rf/dispatch [:map/follow])
      (is (= :free @(rf/subscribe [:map/camera-mode])))
      (rf/dispatch [:map/toggle-follow])
      (is (= :free @(rf/subscribe [:map/camera-mode]))))))

(deftest focus-zoom-is-inspect-distance
  (is (= 13 maplibre/focus-zoom)))

(deftest dblclick-follow-toggles-focus-follow
  (rf-test/run-test-sync
    (rf/dispatch [:app/initialize-db])
    (rf/dispatch [:test/set-picture {ups-icao ups}])
    (rf/reg-fx :map/fly-to (fn [_]))

    (testing "double-click a free plane selects and enters follow"
      (rf/dispatch [:aircraft/dblclick-follow ups-icao])
      (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao])))
      (is (= :follow @(rf/subscribe [:map/camera-mode]))))

    (testing "double-click the followed plane releases to free"
      (rf/dispatch [:aircraft/dblclick-follow ups-icao])
      (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao])))
      (is (= :free @(rf/subscribe [:map/camera-mode]))))

    (testing "double-click again re-enters follow"
      (rf/dispatch [:aircraft/dblclick-follow ups-icao])
      (is (= :follow @(rf/subscribe [:map/camera-mode]))))))

(deftest follow-eases-when-the-selected-plane-moves
  (rf-test/run-test-sync
    (let [{:keys [m rec fire-drag! fire-moveend!]} (recording-map)
          handle (follow/attach! m)]
      (rf/dispatch [:app/initialize-db])
      (rf/dispatch [:test/set-picture {ups-icao ups}])
      (rf/reg-fx :map/fly-to (fn [_]))

      (testing "free mode never moves the camera"
        (rf/dispatch [:aircraft/select ups-icao])
        (r/flush)
        (is (= [] (:fly-to @rec)))
        (is (= [] (:ease-to @rec))))

      (testing "enter follow flies to the current position (inspect zoom)"
        (rf/dispatch [:map/follow])
        (r/flush)
        (is (= [ups-pos] (:fly-to @rec)))
        (is (= [] (:ease-to @rec))))

      (testing "track steps during entry fly are buffered — no ease mid-zoom"
        (let [mid (moved)]
          (rf/dispatch [:test/set-picture {ups-icao mid}])
          (r/flush)
          (is (= [ups-pos] (:fly-to @rec)))
          (is (= [] (:ease-to @rec)))))

      (testing "moveend flushes the buffered position once"
        (let [mid-pos (:aircraft/position (moved))]
          (fire-moveend!)
          (is (= [mid-pos] (:ease-to @rec)))))

      (testing "after settle, a new position eases (keeps zoom)"
        (let [next (update-in (moved) [:aircraft/position :geo/lon] #(+ % 0.1))]
          (rf/dispatch [:test/set-picture {ups-icao next}])
          (r/flush)
          (is (= [ups-pos] (:fly-to @rec)))
          (is (= 2 (count (:ease-to @rec))))
          (is (= (:aircraft/position next) (last (:ease-to @rec))))))

      (testing "dragstart dispatches user-pan → free, and stops easing"
        (is (true? (:dragstart @rec)))
        (fire-drag!)
        (is (= :free @(rf/subscribe [:map/camera-mode])))
        (let [before (count (:ease-to @rec))
              next   (update-in (moved) [:aircraft/position :geo/lon] #(+ % 0.2))]
          (rf/dispatch [:test/set-picture {ups-icao next}])
          (r/flush)
          (is (= before (count (:ease-to @rec))))))

      (follow/detach! handle)
      (rf/dispatch [:map/follow])
      (r/flush)
      (fire-drag!)
      (is (= :follow @(rf/subscribe [:map/camera-mode]))))))
