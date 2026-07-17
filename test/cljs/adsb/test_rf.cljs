(ns adsb.test-rf
  "Shared re-frame test surface: the throwaway :test/set-picture event
  registered once, a fresh app-db, and the async waitFor scaffold — so the UI
  and map suites stop re-registering the event and re-typing the promise
  plumbing around every positive-signal wait."
  (:require ["@testing-library/react" :as rtl]
            [adsb.fixtures :as fixtures]
            [clojure.test :refer-macros [is]]
            [re-frame.core :as rf]))

;; Registered once at load. Every requiring test namespace shares this handler
;; instead of declaring its own top-level copy. Not private because clj-kondo
;; objects to a private var nobody reads.
(defonce set-picture-registered?
  (do (rf/reg-event-db :test/set-picture
        (fn [db [_ picture]] (assoc db :aircraft/picture picture)))
      true))

(defn set-picture!
  "Index `aircraft` (a coll) by ICAO and dispatch it as the picture. For
  run-test-sync contexts, where rf/dispatch runs synchronously."
  [aircraft]
  (rf/dispatch [:test/set-picture (fixtures/by-icao aircraft)]))

(defn fresh-db!
  "Reset app-db to its initialized shape, synchronously."
  []
  (rf/dispatch-sync [:app/initialize-db]))

(defn wait-for!
  "Positive-signal async wait. Polls `assert-fn` (which throws until the DOM
  settles) via rtl/waitFor, runs `on-ok` on resolution, and on timeout reports
  `fail-msg` with the error and calls `done`. `on-ok` runs its own `is` forms
  and either calls `done` or returns a further promise chain — multi-step
  tests return the next waitFor, and this one `.catch` still reports its
  rejection. The condition is asserted twice on purpose: once inside waitFor to
  drive the poll, once inside on-ok for the readable failure message."
  [done assert-fn on-ok fail-msg]
  (-> (rtl/waitFor assert-fn)
      (.then on-ok)
      (.catch (fn [err]
                (is false (str fail-msg ": " err))
                (done)))))
