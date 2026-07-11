(ns adsb.test-runner
  "Browser test runner. Parks the run's outcome on `js/window` so the
  Playwright driver (script/run-browser-tests.mjs) can read it and map it
  to an exit code. See docs/testing-setup.md.

  `:dev/always` forces a recompile every build so `env/get-test-data` — a
  macro that snapshots the registered test namespaces at compile time —
  always sees the current set of tests."
  {:dev/always true}
  (:require
    [cljs.test :as t]
    [shadow.test :as st]
    [shadow.test.env :as env]))

(defmethod t/report [::t/default :end-run-tests] [m]
  (set! (.-adsbTestResult js/window)
        #js {:fail (:fail m) :error (:error m) :pass (:pass m)})
  (set! (.-adsbTestsDone js/window) true))

(defn ^:export init []
  ;; Populate the test registry from the compile-time snapshot before running;
  ;; without this the registry is empty and zero tests run.
  (-> (env/get-test-data)
      (env/reset-test-data!))
  (st/run-all-tests))
