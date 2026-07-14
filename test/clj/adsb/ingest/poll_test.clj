(ns adsb.ingest.poll-test
  (:require
    [adsb.ingest.coerce :as coerce]
    [adsb.ingest.poll :as poll]
    [adsb.ingest.source :as source]
    [clojure.test :refer [deftest testing is]]))

;; Fast bounds so the suite never waits real seconds. Never a live feeder.
(def ^:private fast
  {:interval-ms 5 :initial-backoff-ms 5 :max-backoff-ms 40})

(def ^:private raw-entries
  [{:hex "abc0e4" :alt_baro 34775 :gs 450.5 :lat 27.9 :lon -83.9}
   {:hex "ac5697" :flight "SWA349  " :squawk "6040"}])

(defn- wait-until
  "Busy-wait for pred, up to timeout-ms. Returns pred's value or nil."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 2)
            (recur))))))

(deftest success-path-invokes-callback-with-coerced-batch
  (testing "each poll hands the callback a coerced domain-aircraft batch"
    (let [seen   (atom nil)
          src    (source/fn-source #(coerce/->aircraft-batch raw-entries))
          poller (poll/start! (merge fast {:source    src
                                           :on-batch! #(reset! seen %)}))]
      (try
        (let [batch (wait-until #(deref seen))]
          (is (some? batch) "callback should fire")
          (is (= 2 (count batch)))
          (is (= #{"abc0e4" "ac5697"}
                 (set (map :aircraft/icao batch)))
              "batch carries coerced, namespaced domain aircraft")
          (is (= :ok (:feeder/status (poll/status poller))))
          (is (some? (:feeder/last-success-ms (poll/status poller)))))
        (finally (poll/stop! poller))))))

(deftest survives-outage-then-recovers
  (testing "a Source that throws N times then recovers keeps the loop alive"
    (let [fail-count 4
          calls      (atom 0)
          seen       (atom nil)
          src        (source/fn-source
                       (fn []
                         (if (<= (swap! calls inc) fail-count)
                           (throw (ex-info "feeder down" {}))
                           (coerce/->aircraft-batch raw-entries))))
          poller     (poll/start! (merge fast {:source    src
                                               :on-batch! #(reset! seen %)}))]
      (try
        ;; The loop marks :down during the outage...
        (is (wait-until #(= :down (:feeder/status (poll/status poller))))
            "feeder goes :down while unreachable")
        ;; ...and recovers on its own once the Source returns a batch.
        (let [batch (wait-until #(deref seen))]
          (is (some? batch) "callback fires after recovery")
          (is (> @calls fail-count) "loop survived every failure")
          (is (= :ok (:feeder/status (poll/status poller)))))
        (finally (poll/stop! poller))))))

(deftest backoff-grows-exponentially-to-a-cap
  (testing "next-backoff doubles and clamps at the cap"
    (let [next-backoff #'poll/next-backoff
          cap          40]
      (is (= 10 (next-backoff 5 cap)))
      (is (= 20 (next-backoff 10 cap)))
      (is (= 40 (next-backoff 20 cap)))
      (is (= cap (next-backoff 40 cap)) "clamped, never past the cap")
      (is (= cap (next-backoff 1000 cap))))))

(deftest a-broken-callback-does-not-kill-the-loop
  (testing "a throwing callback neither crashes the loop nor flips :down"
    (let [calls  (atom 0)
          src    (source/fn-source
                   (fn [] (swap! calls inc) (coerce/->aircraft-batch raw-entries)))
          poller (poll/start! (merge fast {:source    src
                                           :on-batch! (fn [_]
                                                        (throw (ex-info "boom" {})))}))]
      (try
        (is (wait-until #(> @calls 3)) "loop keeps polling despite callback throws")
        (is (= :ok (:feeder/status (poll/status poller)))
            "a callback failure is not a feeder failure")
        (finally (poll/stop! poller))))))

(deftest stop-waits-for-an-in-flight-batch
  (testing "stop! does not return while on-batch! is still running, so a
            batch cannot land after the caller believes polling has ended.
            The assembled system's on-batch! writes to adsb.state, so a
            stop! that returned early let a dying poller repopulate the
            global picture a test had just cleared (adsb-a07).

            on-batch! is slow on purpose: without the join, stop! returns
            straight through the sleep and the thread is still alive."
    (let [in-batch? (atom false)
          landed    (atom 0)
          src       (source/fn-source #(coerce/->aircraft-batch raw-entries))
          poller    (poll/start!
                      (merge fast
                             {:source    src
                              :on-batch! (fn [_]
                                           (reset! in-batch? true)
                                           (Thread/sleep 300)
                                           (swap! landed inc))}))]
      (wait-until #(deref in-batch?))
      (poll/stop! poller)
      (is (not (.isAlive ^Thread (:poll/thread poller)))
          "the loop thread has ended by the time stop! returns")
      (let [at-stop @landed]
        (Thread/sleep 200)
        (is (= at-stop @landed)
            "no batch lands after stop! returned")))))

(deftest stop-is-idempotent-and-halts-polling
  (testing "stop! ends the loop and is safe to call twice"
    (let [calls  (atom 0)
          src    (source/fn-source #(do (swap! calls inc) []))
          poller (poll/start! (merge fast {:source src :on-batch! identity}))]
      (wait-until #(> @calls 1))
      (is (nil? (poll/stop! poller)))
      (let [after (do (Thread/sleep 30) @calls)]
        (Thread/sleep 30)
        (is (<= @calls (inc after)) "polling stops after stop!"))
      (is (nil? (poll/stop! poller)) "second stop! is a no-op"))))

(deftest stop-during-a-fetch-is-not-a-feeder-failure
  (testing "stop! interrupts the loop thread, and an interrupt landing inside
            a fetch comes out as an exception like any other. Charging that
            to the feeder logged 'Feeder unreachable' at every clean shutdown
            and left the status :down, libelling a feeder that was fine
            (adsb-12j). The fetch here blocks until it is interrupted, so the
            stop always lands mid-fetch."
    (let [in-fetch? (atom false)
          src       (source/fn-source
                      (fn []
                        (reset! in-fetch? true)
                        (Thread/sleep 10000)   ; interrupted by stop!
                        []))
          poller    (poll/start! (merge fast {:source    src
                                              :on-batch! identity}))]
      (is (wait-until #(deref in-fetch?)) "the loop is inside a fetch")
      (poll/stop! poller)
      (is (not= :down (:feeder/status (poll/status poller)))
          "the shutdown is not charged to the feeder")
      (is (not (.isAlive ^Thread (:poll/thread poller)))
          "and the loop thread is gone — no backoff sleep after the stop"))))
