(ns adsb.ui.follow-test
  "Map Free / Follow reticle (adsb-jg4) — chart chrome above attribution,
  not the detail card. Renders only for a selected aircraft with a
  position; click toggles camera mode."
  (:require
    ["@testing-library/react" :as rtl]
    [adsb.events]
    [adsb.fixtures :as fixtures]
    [adsb.stream]
    [adsb.subs]
    [adsb.ui.follow :as follow]
    [cljs.test :refer-macros [deftest is use-fixtures async]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

(use-fixtures :each
  {:before (fn [] (rf/dispatch-sync [:app/initialize-db]))
   :after  rtl/cleanup})

(rf/reg-event-db :test/set-picture
  (fn [db [_ picture]] (assoc db :aircraft/picture picture)))

(rf/reg-fx :enrich/fetch! (fn [_] nil))

(def ^:private ups fixtures/ups-2717)
(def ^:private ups-icao (:aircraft/icao ups))

(defn- render-follow!
  []
  (rtl/cleanup)
  (rtl/render (r/as-element [follow/follow-control])))

(deftest follow-control-hidden-without-selection
  (rf-test/run-test-sync
    (render-follow!)
    (is (nil? (.queryByTestId rtl/screen "camera-follow"))
        "no selected plane → no reticle on the chart")))

(deftest follow-control-hidden-without-position
  (rf-test/run-test-sync
    (let [anon fixtures/never-positioned
          icao (:aircraft/icao anon)]
      (rf/dispatch [:test/set-picture {icao anon}])
      (rf/dispatch [:aircraft/select icao])
      (render-follow!)
      (is (nil? (.queryByTestId rtl/screen "camera-follow"))
          "heard but never located → nowhere to follow"))))

(deftest follow-control-toggles-camera-mode
  (async done
    (rf/dispatch-sync [:app/initialize-db])
    (rf/reg-fx :map/fly-to (fn [_]))
    (rf/dispatch-sync [:test/set-picture {ups-icao ups}])
    (rf/dispatch-sync [:aircraft/select ups-icao])
    (render-follow!)
    (let [button (.getByTestId rtl/screen "camera-follow")]
      (is (= "free" (.getAttribute button "data-camera")))
      (is (= "false" (.getAttribute button "aria-pressed")))
      (is (= "Follow aircraft" (.getAttribute button "aria-label")))
      (.click rtl/fireEvent button)
      (-> (rtl/waitFor
            (fn []
              (assert (= "follow"
                         (.getAttribute (.getByTestId rtl/screen "camera-follow")
                                        "data-camera")))))
          (.then (fn [_]
                   (is (= :follow @(rf/subscribe [:map/camera-mode])))
                   (let [btn (.getByTestId rtl/screen "camera-follow")]
                     (is (= "true" (.getAttribute btn "aria-pressed")))
                     (is (= "Stop following" (.getAttribute btn "aria-label")))
                     (.click rtl/fireEvent btn))
                   (rtl/waitFor
                     (fn []
                       (assert (= "free"
                                  (.getAttribute
                                    (.getByTestId rtl/screen "camera-follow")
                                    "data-camera")))))))
          (.then (fn [_]
                   (is (= :free @(rf/subscribe [:map/camera-mode])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "camera toggle did not land: " err))
                    (done)))))))
