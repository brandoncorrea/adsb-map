(ns adsb.ingest.poll
  (:require [adsb.ingest.source :as source]
            [clojure.tools.logging :as log]))

(def ^:const default-interval-ms 1000)
(def ^:const default-initial-backoff-ms 1000)
(def ^:const default-max-backoff-ms 30000)

(def ^:private initial-status
  {:feeder/status          :starting
   :feeder/last-success-ms nil
   :feeder/last-error      nil})

(defn- mark-ok! [status]
  (let [recovered? (= :down (:feeder/status @status))]
    (swap! status assoc
           :feeder/status :ok
           :feeder/last-success-ms (System/currentTimeMillis)
           :feeder/last-error nil)
    (when recovered?
      (log/info "Feeder recovered"))))

(defn- mark-down! [status ^Throwable e]
  (let [already-down? (= :down (:feeder/status @status))]
    (swap! status assoc
           :feeder/status :down
           :feeder/last-error (ex-message e))
    (when-not already-down?
      (log/warn e "Feeder unreachable; backing off until it returns"))))

(defn- next-backoff [backoff-ms max-ms]
  (min max-ms (* 2 backoff-ms)))

(defn- deliver-batch! [on-batch! batch]
  (try
    (on-batch! batch)
    (catch Throwable e
      (log/error e "Feeder batch callback threw"))))

(defn- poll-once! [source on-batch! status running?]
  (let [batch (try
                (source/fetch! source)
                (catch Throwable e
                  (when @running?
                    (mark-down! status e))
                  ::failed))]
    (when-not (= ::failed batch)
      (deliver-batch! on-batch! batch)
      (mark-ok! status)
      true)))

(defn- sleep [ms]
  (try
    (Thread/sleep (long ms))
    true
    (catch InterruptedException _)))

(defn- run-loop!
  [{:keys [source on-batch! status running?
           interval-ms initial-backoff-ms max-backoff-ms]}]
  (loop [backoff initial-backoff-ms]
    (when @running?
      (let [ok?  (poll-once! source on-batch! status running?)
            wait (if ok? interval-ms backoff)]
        (when (and @running? (sleep wait))
          (recur (if ok?
                   initial-backoff-ms
                   (next-backoff backoff max-backoff-ms))))))))

(defn start!
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
                           (catch Throwable _)))))
                   "adsb-feeder-poll")]
    (.setDaemon thread true)
    (.start thread)
    {:poll/thread thread :poll/running? running? :poll/status status}))

(def ^:const stop-timeout-ms 5000)

(defn stop! [{:poll/keys [^Thread thread running?]}]
  (when running?
    (reset! running? false)
    (.interrupt thread)
    (.join thread ^long stop-timeout-ms)
    (when (.isAlive thread)
      (log/warn "feeder poll thread still alive" stop-timeout-ms
                "ms after stop!; abandoning it")))
  nil)

(defn status [{:poll/keys [status]}] @status)
