(ns adsb.test-runner
  "Browser test runner. Parks the run's outcome on `js/window` so the
  Playwright driver (adsb-4ga.8) can read it and map it to an exit
  code. See docs/testing-setup.md."
  (:require
    [cljs.test :as t]
    [shadow.test :as st]))

(defmethod t/report [::t/default :end-run-tests] [m]
  (set! (.-adsbTestResult js/window)
        #js {:fail (:fail m) :error (:error m) :pass (:pass m)})
  (set! (.-adsbTestsDone js/window) true))

(defn ^:export init []
  (st/run-all-tests))
