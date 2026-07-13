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
    [clojure.string :as str]
    [re-frame.core :as rf]
    [shadow.test :as st]
    [shadow.test.env :as env]))

(defmethod t/report [::t/default :end-run-tests] [m]
  (set! (.-adsbTestResult js/window)
        #js {:fail (:fail m) :error (:error m) :pass (:pass m)})
  (set! (.-adsbTestsDone js/window) true))

(def ^:private reactive-context-warning
  "re-frame scolds anyone who derefs a subscription outside a component, because
  in APP code that leaks an uncached reaction on every call. In a test it is the
  only way to ask what a subscription currently says, and `run-test-sync` makes
  it safe — so the warning is right about the code it was written for and wrong
  about this code, once per assertion, dozens of times a run."
  "outside of a reactive context")

(defn- warn-unless-expected!
  "js/console.warn, minus the one warning the suite provokes on purpose. Every
  other re-frame warning — an unregistered handler, a missing coeffect — still
  prints, which is the point of filtering by message rather than muting :warn."
  [& args]
  (when-not (some #(and (string? %) (str/includes? % reactive-context-warning)) args)
    (.apply (.-warn js/console) js/console (to-array args))))

(defn ^:export init []
  (rf/set-loggers! {:warn warn-unless-expected!})
  ;; Populate the test registry from the compile-time snapshot before running;
  ;; without this the registry is empty and zero tests run.
  (-> (env/get-test-data)
      (env/reset-test-data!))
  (st/run-all-tests))
