(ns adsb.test-console
  "The browser suite's console voice: everything the suite provokes ON PURPOSE
  is dropped, and everything else still prints.

  A passing test should say nothing. Ours said plenty — malformed aircraft being
  rejected, a 404 shard, re-frame handlers being re-registered — and every line
  of it was the suite doing exactly its job. That is noise with the shape of a
  problem, and it buried the one line that was a problem.

  FILTERED BY MESSAGE, NOT BY MUTING. The list below is an allowlist of the
  specific things this suite is known to provoke, each with the test that
  provokes it. A console.warn nobody predicted still reaches the terminal, which
  is the whole point: muting `warn` wholesale would have hidden the React 18
  legacy-root warning that turned out to be a real defect.

  TWO INTERCEPTION POINTS, because there are two routes to the console:

    adsb.ingest.coerce and adsb.enrich reach `js/console` directly and resolve
    the property at call time, so patching js/console catches them.

    re-frame does not. re-frame.loggers captures console.warn when it loads —
    long before this namespace runs — so patching js/console cannot reach it,
    and re-frame's own `set-loggers!` is the only door. Same predicate, both
    doors."
  (:require
    [clojure.string :as str]
    [re-frame.core :as rf]))

(def ^:private expected-noise
  "Substrings of console output this suite provokes deliberately. Anything not
  matched here still prints."
  [;; adsb.ingest.coerce rejects malformed feeder entries at the boundary and
   ;; says so. coerce-test feeds it deliberate garbage — the rejection IS the
   ;; assertion, and the tests that check the log capture it by redefining the
   ;; log fn, not by reading the console.
   "Rejected aircraft"

   ;; adsb.enrich fetches airframe shards over HTTP. Under test there is no
   ;; db/ directory to serve, so the fetch 404s and enrich says (once) that
   ;; details will be absent and the map is unaffected. That degradation is
   ;; itself under test — enrich-test asserts the map survives a missing shard.
   "aircraft enrichment unavailable"

   ;; re-frame scolds anyone who derefs a subscription outside a component,
   ;; because in APP code that leaks an uncached reaction. In a test it is the
   ;; only way to ask what a subscription currently says, and run-test-sync
   ;; makes it safe.
   "outside of a reactive context"

   ;; day8.re-frame.test/run-test-sync registers its own handlers per test, and
   ;; several tests register a handler of the same name. Re-registration is how
   ;; the harness works, not a collision anyone needs to hear about.
   "overwriting :event handler"
   "overwriting :fx handler"
   "overwriting :sub handler"])

(defn- expected?
  "Is this console call one the suite provokes on purpose?"
  [args]
  (boolean
    (some (fn [arg]
            (and (string? arg)
                 (some #(str/includes? arg %) expected-noise)))
          args)))

(defonce ^:private installed? (atom false))

(defn install!
  "Filter the suite's console. Idempotent: a hot reload must not wrap the
  wrappers, or the originals are lost behind a chain of them."
  []
  (when (compare-and-set! installed? false true)
    (let [console js/console
          warn    (.-warn console)
          info    (.-info console)
          quietly (fn [emit]
                    (fn [& args]
                      (when-not (expected? args)
                        (.apply emit console (to-array args)))))]
      (set! (.-warn console) (quietly warn))
      (set! (.-info console) (quietly info))
      ;; re-frame holds its own captured reference; route it through the same
      ;; predicate, to the ORIGINAL warn (not the patched one — that would
      ;; double-filter, harmlessly but confusingly).
      (rf/set-loggers! {:warn (quietly warn)}))))
