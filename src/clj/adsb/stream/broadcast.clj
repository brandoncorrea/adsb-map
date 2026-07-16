(ns adsb.stream.broadcast
  (:require [adsb.stream.sse :as sse]
            [adsb.wire :as wire]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as http-kit])
  (:import (java.lang.reflect Field)
           (java.net InetSocketAddress)
           (java.nio.channels SelectionKey SocketChannel)
           (java.util.concurrent ArrayBlockingQueue Executors
                                 ScheduledExecutorService ThreadFactory TimeUnit)
           (org.httpkit.server AsyncChannel)))

(def ^:const default-interval-ms 1000)
(def ^:const default-stats-interval-ms 10000)
(def ^:const default-delta-queue-depth 1024)
(def ^:const default-heartbeat-ms 15000)
(def ^:const default-max-clients 100)
(def ^:const default-max-per-ip 4)
(def ^:const default-trusted-proxy-hops 1)
(def ^:const retry-after-s 30)
(def ^:const snapshot-event "snapshot")
(def ^:const update-event "update")
(def ^:const aircraft-event "aircraft")
(def ^:const stats-event "stats")
(def ^:const config-event "config")

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

(def ^:private async-channel-key-field
  (delay
    (try
      (doto (.getDeclaredField AsyncChannel "key")
        (.setAccessible true))
      (catch Exception _))))

(defn- socket-peer-ip [request]
  (or (when-some [^Field field @async-channel-key-field]
        (try
          (when-some [channel (:async-channel request)]
            (let [^SelectionKey key (.get field channel)
                  socket-channel    (.channel key)]
              (when (instance? SocketChannel socket-channel)
                (let [address (.getRemoteAddress ^SocketChannel socket-channel)]
                  (when (instance? InetSocketAddress address)
                    (.getHostAddress
                      (.getAddress ^InetSocketAddress address)))))))
          (catch Exception _)))
      (:remote-addr request)))

(defn forwarded-ip [hops header]
  (when-some [entries (some->> (some-> header (str/split #","))
                               (map str/trim)
                               (remove str/blank?)
                               seq
                               vec)]
    (nth entries (max 0 (- (count entries) hops)))))

(def ^:const cf-connecting-ip-header "cf-connecting-ip")

(defn- client-ip [trust-forwarded? hops request]
  (or (when trust-forwarded?
        (or (some-> (get-in request [:headers cf-connecting-ip-header])
                    str/trim
                    not-empty)
            (forwarded-ip hops (get-in request [:headers "x-forwarded-for"]))))
      (socket-peer-ip request)))

(defn- deny-reason [clients ip {:keys [max-clients max-per-ip]}]
  (cond
    (>= (count clients) max-clients)
    :server-full

    (>= (count (filter #(= ip (:client/ip %)) (vals clients))) max-per-ip)
    :ip-full))

(defn- try-register! [{:stream/keys [clients limits]} channel ip]
  (let [[old new] (swap-vals! clients
                              (fn [registry]
                                (if (deny-reason registry ip limits)
                                  registry
                                  (assoc registry channel
                                                  {:client/ip     ip
                                                   :client/ready? false}))))]
    (when-not (contains? new channel)
      (deny-reason old ip limits))))

(defn- mark-ready! [{:stream/keys [clients]} channel]
  (swap! clients
         (fn [registry]
           (cond-> registry
                   (contains? registry channel)
                   (assoc-in [channel :client/ready?] true)))))

(defn- unregister! [{:stream/keys [clients]} channel]
  (swap! clients dissoc channel))

(defn- drop-client! [broadcaster channel]
  (unregister! broadcaster channel)
  (http-kit/close channel))

(defn- send-or-drop! [broadcaster channel frame]
  (when-not (http-kit/send! channel frame false)
    (drop-client! broadcaster channel)))

(defn- broadcast! [{:stream/keys [clients] :as broadcaster} frame]
  (doseq [[channel {:client/keys [ready?]}] @clients
          :when ready?]
    (send-or-drop! broadcaster channel frame)))

(def ^:const ^:private reject-log-interval-ms 10000)

(defn- log-rejection! [{:stream/keys [last-reject-log-ms]} reason ip]
  (let [now-ms (System/currentTimeMillis)
        [old new] (swap-vals! last-reject-log-ms
                              #(if (>= (- now-ms %) reject-log-interval-ms)
                                 now-ms
                                 %))]
    (when (not= old new)
      (log/warn "SSE connect rejected" reason "for" ip
                "(further rejections muted for"
                (/ reject-log-interval-ms 1000) "s)"))))

(defn- rejection-response [reason]
  {:status  503
   :headers {"Content-Type" "application/json"
             "Retry-After"  (str retry-after-s)}
   :body    (json/generate-string
              {:error  "stream at capacity"
               :reason (name reason)})})

(defn- reject! [broadcaster channel reason ip]
  (log-rejection! broadcaster reason ip)
  (http-kit/send! channel (rejection-response reason) true))

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

(defn offer-delta! [{:stream/keys [^ArrayBlockingQueue deltas]} aircraft now-ms]
  (.offer deltas [aircraft now-ms]))

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

(def ^:const diagnose-client-ip-env "ADSB_DIAGNOSE_CLIENT_IP")
(def ^:const diagnose-budget 20)

(defn- diagnose-client-ip! [{:stream/keys [diagnose-remaining]} request resolved-ip]
  (when diagnose-remaining
    (let [[old _] (swap-vals! diagnose-remaining #(cond-> % (pos? %) dec))]
      (when (pos? old)
        (log/info "SSE-CLIENT-IP-DIAG"
                  {:cf-connecting-ip (get-in request [:headers
                                                      cf-connecting-ip-header])
                   :x-forwarded-for  (get-in request [:headers "x-forwarded-for"])
                   :socket-peer      (socket-peer-ip request)
                   :resolved-key     resolved-ip
                   :remaining        (dec old)})))))

(defn connect!
  [{:stream/keys [picture last-stats feeder trust-forwarded?
                  trusted-proxy-hops clients limits]
    :as          broadcaster}
   request]
  (let [ip (client-ip trust-forwarded? trusted-proxy-hops request)]
    (diagnose-client-ip! broadcaster request ip)
    (if-some [reason (deny-reason @clients ip limits)]
      (do (log-rejection! broadcaster reason ip)
          (rejection-response reason))
      (http-kit/as-channel
        request
        {:on-open  (fn [channel]
                     (if-some [reason (try-register! broadcaster channel ip)]
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
                           (mark-ready! broadcaster channel)
                           (drop-client! broadcaster channel)))))
         :on-close (fn [channel _status]
                     (unregister! broadcaster channel))}))))

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

(defn- env-limit [var-name]
  (when-some [value (System/getenv var-name)]
    (when-some [n (parse-long (str/trim value))]
      (when (pos? n) n))))

(defn- env-flag? [var-name]
  (= "true" (some-> (System/getenv var-name) str/trim str/lower-case)))

(defn start!
  "Start the ticks, the heartbeat, and the per-aircraft upsert fan-out
  (offer-delta! feeds it; a deployment with no streaming Source simply
  never calls it and pays one parked daemon thread). Options:

    :picture       REQUIRED — fn of now-ms returning the picture
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
  environment variable, else the compiled default. The env fallback
  lives here, at the lifecycle seam, so every entry point that starts a
  broadcaster gets the same operator-tunable limits:

    :max-clients      total concurrent SSE cap
    :max-per-ip       concurrent cap per client IP
    :trust-forwarded? honor the proxy-appended X-Forwarded-For for the
                      per-IP count. Set ONLY when the app port is
                      reachable exclusively through the trusted proxy.

    :trusted-proxy-hops  how many trusted proxies stand between the
                         internet and this app

    :diagnose-client-ip?  Log the raw address inputs at SSE connect

  Returns a broadcaster to hand to connect!, client-count, and stop!."
  [{:keys [picture stats feeder stats-interval-ms heartbeat-ms crop
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
                     :stream/delta-running?     (atom true)
                     :stream/limits
                     {:max-clients (or max-clients
                                       (env-limit "ADSB_SSE_MAX_CLIENTS")
                                       default-max-clients)
                      :max-per-ip  (or max-per-ip
                                       (env-limit "ADSB_SSE_MAX_PER_IP")
                                       default-max-per-ip)}
                     :stream/trust-forwarded?
                     (if (some? trust-forwarded?)
                       trust-forwarded?
                       (env-flag? "ADSB_TRUST_FORWARDED_FOR"))
                     :stream/trusted-proxy-hops
                     (or trusted-proxy-hops
                         (env-limit "ADSB_TRUSTED_PROXY_HOPS")
                         default-trusted-proxy-hops)
                     :stream/last-reject-log-ms (atom 0)
                     :stream/diagnose-remaining
                     (when (if (some? diagnose-client-ip?)
                             diagnose-client-ip?
                             (env-flag? diagnose-client-ip-env))
                       (atom diagnose-budget))
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

(defn stop! [{:stream/keys [^ScheduledExecutorService executor clients
                            delta-running? ^Thread delta-thread]}]
  (.shutdownNow executor)
  (some-> delta-running? (reset! false))
  (some-> delta-thread .interrupt)
  (doseq [channel (keys @clients)]
    (http-kit/close channel))
  (reset! clients {})
  nil)
