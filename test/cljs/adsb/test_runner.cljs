(ns adsb.test-runner
  {:dev/always true}
  (:require [clojure.test :as t]
            [shadow.test :as st]
            [shadow.test.env :as env]))

(defmethod t/report [::t/default :end-run-tests] [m]
  (set! (.-adsbTestResult js/window)
        (js-obj "fail" (:fail m)
                "error" (:error m)
                "pass" (:pass m)))
  (set! (.-adsbTestsDone js/window) true))

(defn ^:export init []
  (-> (env/get-test-data)
      (env/reset-test-data!))
  (st/run-all-tests))
