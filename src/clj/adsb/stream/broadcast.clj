(ns adsb.stream.broadcast
  (:require [adsb.env :as env]
            [adsb.stream.admission :as admission]
            [adsb.stream.sse :as sse]
            [adsb.wire :as wire]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as http-kit])
  (:import (java.util.concurrent ArrayBlockingQueue Executors
                                 ScheduledExecutorService ThreadFactory TimeUnit)))

(def ^:const default-interval-ms 1000)
(def ^:const default-stats-interval-ms 10000)
(def ^:const default-delta-queue-depth 1024)
(def ^:const default-heartbeat-ms 15000)
(def ^:const default-max-clients 100)
(def ^:const default-max-per-ip 4)
(def ^:const default-trusted-proxy-hops 1)
(def ^:const snapshot-event "snapshot")
(def ^:const update-event "update")
(def ^:const aircraft-event "aircraft")
(def ^:const stats-event "stats")
(def ^:const config-event "config")

;; The four operator-tunable admission knobs. Named here because start!'s env
;; fallback reads them and adsb.main resolves the same names from the
;; .env-merged env map (adsb-rgv).
(def ^:const sse-max-clients-env "ADSB_SSE_MAX_CLIENTS")
(def ^:const sse-max-per-ip-env "ADSB_SSE_MAX_PER_IP")
(def ^:const trust-forwarded-for-env "ADSB_TRUST_FORWARDED_FOR")
(def ^:const trusted-proxy-hops-env "ADSB_TRUSTED_PROXY_HOPS")

(defn- picture-frame! [{:stream/keys [frame-id]} event-name picture now-ms]
  (sse/event-frame event-name
                   (swap! frame-id inc)
                   (json/generate-string
                     (wire/picture->wire picture now-ms))))

(defn- upsert-frame! [{:stream/keys [frame-id]} aircraft now-ms]
  (sse/event-frame aircraft-event
                   (swap! frame-id inc)
                   (json/generate-string
                     (wire/upsert->wire aircraft now-ms))))

(defn- stats-frame! [{:stream/keys [frame-id]} stats feeder now-ms]
  (sse/event-frame stats-event
                   (swap! frame-id inc)
                   (json/generate-string
                     (wire/stats-event->wire stats feeder now-ms))))

(defn- config-frame! [{:stream/keys [frame-id crop]} now-ms]
  (sse/event-frame config-event
                   (swap! frame-id inc)
                   (json/generate-string
                     (wire/config-event->wire crop now-ms))))

(defn- drop-client! [broadcaster channel]
  (admission/unregister! broadcaster channel)
  (http-kit/close channel))

(defn- send-or-drop! [broadcaster channel frame]
  (when-not (http-kit/send! channel frame false)
    (drop-client! broadcaster channel)))

(defn- broadcast! [{:stream/keys [clients] :as broadcaster} frame]
  (doseq [[channel {:client/keys [ready?]}] @clients
          :when ready?]
    (send-or-drop! broadcaster channel frame)))

(defn- reject! [broadcaster channel reason ip]
  (admission/log-rejection! broadcaster reason ip)
  (http-kit/send! channel (admission/rejection-response reason) true))

(defn- broadcast-picture! [{:stream/keys [picture clients] :as broadcaster}]
  (let [now-ms          (System/currentTimeMillis)
        current-picture (picture now-ms)]
    (when (seq @clients)
      (broadcast! broadcaster
                  (picture-frame! broadcaster update-event
                                  current-picture now-ms)))))

(defn- broadcast-stats! [broadcaster]
  (let [{:stream/keys [picture stats feeder last-stats clients]} broadcaster
        now-ms          (System/currentTimeMillis)
        current-picture (picture now-ms)
        current-stats   (stats current-picture now-ms)]
    (reset! last-stats current-stats)
    (when (seq @clients)
      (broadcast! broadcaster
                  (stats-frame! broadcaster current-stats (feeder) now-ms)))))

(defn- broadcast-heartbeat! [broadcaster]
  (broadcast! broadcaster (sse/comment-frame "hb")))

(def ^:const ^:private delta-drop-log-interval-ms 10000)

(defn- log-delta-drop! [{:stream/keys [deltas-dropped last-delta-drop-log-ms]}]
  (let [dropped   (swap! deltas-dropped inc)
        now-ms    (System/currentTimeMillis)
        [old new] (swap-vals! last-delta-drop-log-ms
                              #(if (>= (- now-ms %) delta-drop-log-interval-ms)
                                 now-ms
                                 %))]
    (when (not= old new)
      (log/warn "SSE delta queue full —" dropped "upsert(s) dropped so far;"
                "the reader is outrunning the fan-out. Newest deltas are shed"
                "deliberately to bound memory; the periodic picture tick and"
                "the next client snapshot reconcile. Further drops muted for"
                (quot delta-drop-log-interval-ms 1000) "s."))))

(defn offer-delta!
  "Hand one per-aircraft upsert to the fan-out thread, returning the queue's
  accept boolean. A full queue sheds the NEWEST delta deliberately (bounded
  memory under a burst); the drop is counted (see deltas-dropped) and warned,
  rate-limited. The periodic update tick and the connect-time snapshot
  reconcile any client that missed a shed delta."
  [{:stream/keys [^ArrayBlockingQueue deltas] :as broadcaster} aircraft now-ms]
  (let [accepted? (.offer deltas [aircraft now-ms])]
    (when-not accepted?
      (log-delta-drop! broadcaster))
    accepted?))

(def ^:const ^:private delta-poll-ms 100)

(defn- take-delta! [^ArrayBlockingQueue deltas]
  (try
    (.poll deltas delta-poll-ms TimeUnit/MILLISECONDS)
    (catch InterruptedException _)))

(defn- broadcast-delta! [broadcaster [aircraft now-ms]]
  (when (seq @(:stream/clients broadcaster))
    (broadcast! broadcaster (upsert-frame! broadcaster aircraft now-ms))))

(defn- run-delta-fan-out! [{:stream/keys [delta-running? deltas] :as broadcaster}]
  (loop []
    (when @delta-running?
      (when-some [delta (take-delta! deltas)]
        (try
          (broadcast-delta! broadcaster delta)
          (catch Throwable e
            (log/error e "SSE delta fan-out failed"))))
      (recur))))

(defn- start-delta-fan-out! ^Thread [broadcaster]
  (doto (Thread. ^Runnable #(run-delta-fan-out! broadcaster)
                 "adsb-sse-delta-fan-out")
    (.setDaemon true)
    (.start)))

(defn connect!
  [{:stream/keys [picture last-stats feeder trust-forwarded?
                  trusted-proxy-hops clients limits]
    :as          broadcaster}
   request]
  (let [ip (admission/client-ip trust-forwarded? trusted-proxy-hops request)]
    (admission/diagnose-client-ip! broadcaster request ip)
    (if-some [reason (admission/deny-reason @clients ip limits)]
      (do (admission/log-rejection! broadcaster reason ip)
          (admission/rejection-response reason))
      (http-kit/as-channel
        request
        {:on-open  (fn [channel]
                     (if-some [reason (admission/try-register! broadcaster
                                                               channel ip)]
                       (reject! broadcaster channel reason ip)
                       (let [now-ms   (System/currentTimeMillis)
                             config-f (config-frame! broadcaster now-ms)
                             snapshot (picture-frame! broadcaster
                                                      snapshot-event
                                                      (picture now-ms)
                                                      now-ms)
                             stats-f  (stats-frame! broadcaster @last-stats
                                                    (feeder) now-ms)]
                         (if (and (http-kit/send! channel
                                                  {:status  200
                                                   :headers sse/headers}
                                                  false)
                                  (http-kit/send! channel config-f false)
                                  (http-kit/send! channel snapshot false)
                                  (http-kit/send! channel stats-f false))
                           (admission/mark-ready! broadcaster channel)
                           (drop-client! broadcaster channel)))))
         :on-close (fn [channel _status]
                     (admission/unregister! broadcaster channel))}))))

(def ^:private broadcast-threads
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. ^Runnable runnable "adsb-sse-broadcast")
        (.setDaemon true)))))

(defn- schedule! [^ScheduledExecutorService executor initial-delay-ms period-ms task!]
  (.scheduleAtFixedRate executor
                        (fn []
                          (try
                            (task!)
                            (catch Throwable e
                              (log/error e "SSE broadcast task failed"))))
                        (long initial-delay-ms)
                        (long period-ms)
                        TimeUnit/MILLISECONDS))

(defn start!
  "Start the ticks, the heartbeat, and the per-aircraft upsert fan-out
  (offer-delta! feeds it; a deployment with no streaming Source simply
  never calls it and pays one parked daemon thread). Options:

    :picture       REQUIRED (:keys! — boot throws if absent) — fn of
                   now-ms returning the picture
                   (icao -> aircraft) to put on the wire. Production
                   injects adsb.state/age-out! (see the ns docstring).
    :stats         fn of [picture now-ms] returning the session stats map
                   (adsb.stats) for the stats frame, or nil. Called only
                   on the stats tick — never per delta; defaults to
                   (constantly nil).
    :feeder        thunk returning the feeder status map
                   (adsb.ingest.poll/status) for the stats frame, or nil.
                   Read fresh per frame (a plain atom read, thread-safe);
                   defaults to (constantly nil) — no feeder health.
    :interval-ms   full-picture `update` tick period (default 1000,
                   ~1 Hz — the poll deployment's aircraft path). NIL
                   DISABLES the update tick entirely: the streaming
                   deployment, where aircraft data flows exclusively as
                   snapshot + per-delta upserts (adsb.main; ns
                   docstring).
    :stats-interval-ms  `stats` event period (default 10000, ~10 s).
                   Fires once immediately at start to warm the cache the
                   connect-time stats frame reads, and runs regardless
                   of :interval-ms and of audience — it doubles as the
                   age-out sweep's floor (ns docstring).
    :heartbeat-ms  heartbeat comment period (default 15000)
    :delta-queue-depth  capacity of the reader -> fan-out handoff queue
                   (default default-delta-queue-depth; see offer-delta!)
    :crop          the privacy crop (adsb.ingest.crop), or nil when the
                   gate is disabled. STATIC — held as a value, not a fn,
                   because it is resolved once at boot and cannot change
                   while the process lives. Rides the connect-time `config`
                   event so the map can draw the declared boundary of what
                   this app publishes; nil means no boundary is drawn.

  Connection limits (ns docstring) — an explicit option wins, else the
  environment variable, else the compiled default. The env fallback reads
  System/getenv only, so it misses values set solely in .env; the composition
  root (adsb.main) resolves the four knobs from the .env-merged env map and
  passes them as explicit options (adsb-rgv). The fallback stays here so
  entry points that skip env.clj still get operator-tunable limits:

    :max-clients      total concurrent SSE cap
    :max-per-ip       concurrent cap per client IP
    :trust-forwarded? honor the proxy-appended X-Forwarded-For for the
                      per-IP count. Set ONLY when the app port is
                      reachable exclusively through the trusted proxy.

    :trusted-proxy-hops  how many trusted proxies stand between the
                         internet and this app

    :diagnose-client-ip?  Log the raw address inputs at SSE connect

  Returns a broadcaster to hand to connect!, client-count, and stop!."
  [{:keys! [picture]
    :keys  [stats feeder stats-interval-ms heartbeat-ms crop
            delta-queue-depth max-clients max-per-ip trust-forwarded?
            trusted-proxy-hops diagnose-client-ip?]
    :or   {stats             (constantly nil)
           feeder            (constantly nil)
           stats-interval-ms default-stats-interval-ms
           heartbeat-ms      default-heartbeat-ms
           delta-queue-depth default-delta-queue-depth}
    :as   options}]
  (let [interval-ms (get options :interval-ms default-interval-ms)
        executor    (Executors/newSingleThreadScheduledExecutor
                      broadcast-threads)
        broadcaster {:stream/picture            picture
                     :stream/stats              stats
                     :stream/feeder             feeder
                     :stream/crop               crop
                     :stream/last-stats         (atom nil)
                     :stream/clients            (atom {})
                     :stream/deltas             (ArrayBlockingQueue.
                                                  (int delta-queue-depth))
                     :stream/deltas-dropped     (atom 0)
                     :stream/last-delta-drop-log-ms (atom 0)
                     :stream/delta-running?     (atom true)
                     :stream/limits
                     {:max-clients (or max-clients
                                       (env/positive-long (System/getenv)
                                                          sse-max-clients-env)
                                       default-max-clients)
                      :max-per-ip  (or max-per-ip
                                       (env/positive-long (System/getenv)
                                                          sse-max-per-ip-env)
                                       default-max-per-ip)}
                     :stream/trust-forwarded?
                     (if (some? trust-forwarded?)
                       trust-forwarded?
                       (env/flag? (System/getenv) trust-forwarded-for-env))
                     :stream/trusted-proxy-hops
                     (or trusted-proxy-hops
                         (env/positive-long (System/getenv)
                                            trusted-proxy-hops-env)
                         default-trusted-proxy-hops)
                     :stream/last-reject-log-ms (atom 0)
                     :stream/diagnose-remaining
                     (when (if (some? diagnose-client-ip?)
                             diagnose-client-ip?
                             (env/flag? (System/getenv)
                                        admission/diagnose-client-ip-env))
                       (atom admission/diagnose-budget))
                     :stream/frame-id           (atom 0)
                     :stream/executor           executor}]
    (when interval-ms
      (schedule! executor interval-ms interval-ms
                 #(broadcast-picture! broadcaster)))
    (schedule! executor 0 stats-interval-ms #(broadcast-stats! broadcaster))
    (schedule! executor heartbeat-ms heartbeat-ms
               #(broadcast-heartbeat! broadcaster))
    (assoc broadcaster
      :stream/delta-thread (start-delta-fan-out! broadcaster))))

(defn client-count [{:stream/keys [clients]}] (count @clients))

(defn deltas-dropped
  "How many per-aircraft upserts offer-delta! has shed because the fan-out
  queue was full — 0 on a healthy broadcaster."
  [broadcaster]
  @(:stream/deltas-dropped broadcaster))

(defn stop! [{:stream/keys [^ScheduledExecutorService executor clients
                            delta-running? ^Thread delta-thread]}]
  (.shutdownNow executor)
  (some-> delta-running? (reset! false))
  (some-> delta-thread .interrupt)
  (doseq [channel (keys @clients)]
    (http-kit/close channel))
  (reset! clients {})
  nil)
