(ns adsb.ingest.poll
  "The poll loop: drive a Source at ~1 Hz, hand each successful batch to a
  callback, and survive a feeder outage forever.

  A feeder outage is a status, not a crash. On failure the loop logs once —
  on the transition into :down, never once per tick, or a down feeder would
  fill the disk — backs off exponentially to a cap, and recovers on its own
  when the feeder returns. The loop never dies.

  Feeder status lives in the atom returned inside the poller (read it with
  `status`). The /healthz handler (adsb-kbm.2) and the SSE chrome
  (adsb-nqf.2) will consume it once they're wired; nothing reads it yet."
  (:require [adsb.ingest.source :as source]
            [clojure.tools.logging :as log]))

(def ^:const default-interval-ms 1000)
(def ^:const default-initial-backoff-ms 1000)
(def ^:const default-max-backoff-ms 30000)

(def ^:private initial-status
  {:feeder/status          :starting
   :feeder/last-success-ms nil
   :feeder/last-error      nil})

;; ---------------------------------------------------------------------
;; Status transitions — logged once per state change, not per tick.

(defn- mark-ok!
  "Record a successful poll. Logs recovery once, on the transition up from
  :down. Single writer (the poll thread), so read-then-swap is safe."
  [status]
  (let [recovered? (= :down (:feeder/status @status))]
    (swap! status assoc
           :feeder/status :ok
           :feeder/last-success-ms (System/currentTimeMillis)
           :feeder/last-error nil)
    (when recovered?
      (log/info "Feeder recovered"))))

(defn- mark-down!
  "Record a failed poll. Logs once, on the transition down into :down, so a
  persistently unreachable feeder costs one log line, not one per tick."
  [status ^Throwable e]
  (let [already-down? (= :down (:feeder/status @status))]
    (swap! status assoc
           :feeder/status :down
           :feeder/last-error (ex-message e))
    (when-not already-down?
      (log/warn e "Feeder unreachable; backing off until it returns"))))

;; ---------------------------------------------------------------------
;; The loop

(defn- next-backoff [backoff-ms max-ms]
  (min max-ms (* 2 backoff-ms)))

(defn- deliver-batch!
  "Hand the batch to the callback, isolating its failures — a broken
  callback must not kill the loop or masquerade as a feeder outage."
  [on-batch! batch]
  (try
    (on-batch! batch)
    (catch Throwable e
      (log/error e "Feeder batch callback threw"))))

(defn- poll-once!
  "One poll. Returns true on a successful fetch (feeder :ok), false on a
  feeder failure (feeder :down). Never throws."
  [source on-batch! status]
  (let [batch (try
                (source/fetch! source)
                (catch Throwable e
                  (mark-down! status e)
                  ::failed))]
    (when-not (= ::failed batch)
      (deliver-batch! on-batch! batch)
      (mark-ok! status)
      true)))

(defn- sleep!
  "Sleep, returning false if interrupted (stop! interrupts the thread) so
  the loop can exit promptly mid-wait."
  [ms]
  (try
    (Thread/sleep (long ms))
    true
    (catch InterruptedException _)))

(defn- run-loop!
  [{:keys [source on-batch! status running?
           interval-ms initial-backoff-ms max-backoff-ms]}]
  (loop [backoff initial-backoff-ms]
    (when @running?
      (let [ok?  (poll-once! source on-batch! status)
            wait (if ok? interval-ms backoff)]
        (when (sleep! wait)
          (recur (if ok?
                   initial-backoff-ms
                   (next-backoff backoff max-backoff-ms))))))))

;; ---------------------------------------------------------------------
;; Lifecycle

(defn start!
  "Start polling `source`, handing each successful batch to `on-batch!`.
  Returns a poller to pass to stop! and status. Options (all optional but
  :source and :on-batch! are required):

    :source            a Source (adsb.ingest.source/Source)
    :on-batch!         fn of one arg, the coerced aircraft batch
    :interval-ms       poll period on success (default ~1 Hz)
    :initial-backoff-ms / :max-backoff-ms   exponential backoff bounds

  The loop runs on a daemon thread and never throws out of it."
  [{:keys [source on-batch! interval-ms initial-backoff-ms max-backoff-ms]
    :or   {interval-ms        default-interval-ms
           initial-backoff-ms default-initial-backoff-ms
           max-backoff-ms     default-max-backoff-ms}}]
  (let [status   (atom initial-status)
        running? (atom true)
        loop-arg {:source             source
                  :on-batch!          on-batch!
                  :status             status
                  :running?           running?
                  :interval-ms        interval-ms
                  :initial-backoff-ms initial-backoff-ms
                  :max-backoff-ms     max-backoff-ms}
        thread   (Thread.
                   (fn []
                     (try
                       (source/open! source)
                       (run-loop! loop-arg)
                       (catch Throwable e
                         (log/error e "Feeder poll loop terminated"))
                       (finally
                         (try
                           (source/close! source)
                           (catch Throwable _ nil)))))
                   "adsb-feeder-poll")]
    (.setDaemon thread true)
    (.start thread)
    {:poll/thread thread :poll/running? running? :poll/status status}))

(defn stop!
  "Stop a poller returned by start!. Idempotent — interrupts the loop
  thread so an in-progress backoff sleep aborts at once."
  [{:poll/keys [^Thread thread running?]}]
  (when running?
    (reset! running? false)
    (.interrupt thread))
  nil)

(defn status
  "The current feeder status map: :feeder/status (:starting/:ok/:down),
  :feeder/last-success-ms, :feeder/last-error. Readable by /healthz
  (adsb-kbm.2) and the SSE chrome (adsb-nqf.2) once wired."
  [{:poll/keys [status]}]
  @status)
