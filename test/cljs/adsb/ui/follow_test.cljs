(ns adsb.ui.follow-test
  (:require ["@testing-library/react" :as rtl]
            [adsb.corejs :as cjs]
            [adsb.events]
            [adsb.fixtures :as fixtures]
            [adsb.stream]
            [adsb.subs]
            [adsb.test-dom :as test-dom]
            [adsb.test-rf :as test-rf]
            [adsb.ui.follow :as follow]
            [clojure.test :refer-macros [deftest is use-fixtures async]]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]))

(use-fixtures :each
  {:before (fn [] (rf/dispatch-sync [:app/initialize-db]))
   :after  rtl/cleanup})

(rf/reg-fx :enrich/fetch! (fn [_]))

(def ^:private ups fixtures/ups-2717)
(def ^:private ups-icao (:aircraft/icao ups))

(defn- render-follow! []
  (test-dom/render! [follow/follow-control]))

(deftest follow-control-hidden-without-selection
  (rf-test/run-test-sync
    (render-follow!)
    (is (nil? (.queryByTestId rtl/screen "camera-follow")))))

(deftest follow-control-hidden-without-position
  (rf-test/run-test-sync
    (let [anon fixtures/never-positioned
          icao (:aircraft/icao anon)]
      (rf/dispatch [:test/set-picture {icao anon}])
      (rf/dispatch [:aircraft/select icao])
      (render-follow!)
      (is (nil? (.queryByTestId rtl/screen "camera-follow"))))))

(deftest follow-control-toggles-camera-mode
  (async done
    (rf/dispatch-sync [:app/initialize-db])
    (rf/reg-fx :map/fly-to (fn [_]))
    (rf/dispatch-sync [:test/set-picture {ups-icao ups}])
    (rf/dispatch-sync [:aircraft/select ups-icao])
    (render-follow!)
    (let [button (.getByTestId rtl/screen "camera-follow")]
      (is (= "free" (cjs/get-attribute button "data-camera")))
      (is (= "false" (cjs/get-attribute button "aria-pressed")))
      (is (= "Follow aircraft" (cjs/get-attribute button "aria-label")))
      (.click rtl/fireEvent button)
      (test-rf/wait-for! done
        (fn []
          (assert (= "follow"
                     (cjs/get-attribute (.getByTestId rtl/screen "camera-follow")
                                        "data-camera"))))
        (fn [_]
          (is (= :follow @(rf/subscribe [:map/camera-mode])))
          (let [btn (.getByTestId rtl/screen "camera-follow")]
            (is (= "true" (cjs/get-attribute btn "aria-pressed")))
            (is (= "Stop following" (cjs/get-attribute btn "aria-label")))
            (.click rtl/fireEvent btn))
          (-> (rtl/waitFor
                (fn []
                  (assert (= "free"
                             (cjs/get-attribute
                               (.getByTestId rtl/screen "camera-follow")
                               "data-camera")))))
              (.then (fn [_]
                       (is (= :free @(rf/subscribe [:map/camera-mode])))
                       (done)))))
        "camera toggle did not land"))))
