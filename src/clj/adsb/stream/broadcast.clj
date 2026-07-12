(ns adsb.stream.broadcast
  "SSE fan-out of the aircraft picture: a client registry, a ~1 Hz tick
  that sends every client the full current picture, and a heartbeat.

  On connect a client receives one `snapshot` event, then an `update`
  event per tick — both carry the full picture on the adsb.wire format,
  so a reconnect needs no replay. The tick obtains the picture through
  the injected `:picture` fn (a fn of now-ms); production injects
  adsb.state/age-out!, which prunes long-silent aircraft AND returns
  the surviving picture — age-out runs on the broadcast cadence, one
  scheduler for both jobs (decision recorded on adsb-kbm.2).

  ## Slow-consumer policy (adsb-kbm.2)

  http-kit's async channels do not backpressure: send! only enqueues,
  and its boolean says whether the channel was still open. The policy
  is DROP-AND-CLOSE: any client whose send! returns false is closed and
  unregistered on the spot. What bounds a stalled-but-still-open
  consumer is that every frame is the full picture, never a queued
  backlog of deltas — the server buffers at most one bounded frame per
  tick until the OS gives up on the socket and send! starts failing.
  A dropped client reconnects and gets a fresh snapshot; nothing is
  owed to it."
  (:require
    [adsb.stream.sse :as sse]
    [adsb.wire :as wire]
    [cheshire.core :as json]
    [clojure.tools.logging :as log]
    [org.httpkit.server :as http-kit])
  (:import
    (java.util.concurrent Executors ScheduledExecutorService
                          ThreadFactory TimeUnit)))

(def ^:const default-interval-ms 1000)

(def ^:const default-heartbeat-ms 15000)

(def ^:const snapshot-event "snapshot")

(def ^:const update-event "update")

;; ---------------------------------------------------------------------
;; Frames

(defn- picture-frame!
  "One full-picture SSE frame as of now-ms, carrying the session `stats`
  (adsb.stats, or nil) and the `feeder` health (adsb.ingest.poll/status, or
  nil). The ! is the frame-id counter: snapshot and update frames share it,
  so ids increase across the whole stream."
  [{:stream/keys [frame-id]} event-name picture stats feeder now-ms]
  (sse/event-frame event-name
                   (swap! frame-id inc)
                   (json/generate-string
                     (wire/picture->wire picture stats feeder now-ms))))

;; ---------------------------------------------------------------------
;; The registry and the slow-consumer policy

(defn- register! [{:stream/keys [clients]} channel]
  (swap! clients conj channel))

(defn- unregister! [{:stream/keys [clients]} channel]
  (swap! clients disj channel))

(defn- drop-client!
  "The slow-consumer policy's teeth: a channel whose send! reported
  closed is closed for real and forgotten (see the ns docstring)."
  [broadcaster channel]
  (unregister! broadcaster channel)
  (http-kit/close channel))

(defn- send-or-drop! [broadcaster channel frame]
  (when-not (http-kit/send! channel frame false)
    (drop-client! broadcaster channel)))

(defn- broadcast!
  [{:stream/keys [clients] :as broadcaster} frame]
  (doseq [channel @clients]
    (send-or-drop! broadcaster channel frame)))

;; ---------------------------------------------------------------------
;; The tick and the heartbeat

(defn- broadcast-picture!
  "One tick: obtain the picture as of now — the injected fn runs even
  with no audience, because in production it is also the age-out
  sweep — compute the session stats and cache them for the next connect's
  snapshot, then fan the update out to whoever is listening. Stats are
  computed ONLY here, on the single broadcast thread, so their
  accumulator (adsb.stats) has one writer; connect! reuses the cache
  rather than recomputing off-thread. The feeder status is a plain read of
  the poller's atom, safe from any thread, so it is read fresh per frame
  rather than cached."
  [{:stream/keys [picture stats feeder last-stats clients] :as broadcaster}]
  (let [now-ms          (System/currentTimeMillis)
        current-picture (picture now-ms)
        current-stats   (stats current-picture now-ms)]
    (reset! last-stats current-stats)
    (when (seq @clients)
      (broadcast! broadcaster
                  (picture-frame! broadcaster update-event
                                  current-picture current-stats (feeder)
                                  now-ms)))))

(defn- broadcast-heartbeat! [broadcaster]
  (broadcast! broadcaster (sse/comment-frame "hb")))

;; ---------------------------------------------------------------------
;; Connect

(defn connect!
  "The GET /api/stream handler body: switch the request to an http-kit
  async channel, send the SSE headers and one full snapshot, and join
  the registry for the ticks that follow. The registry entry is removed
  by on-close (the browser went away) or by a failed send
  (drop-and-close)."
  [{:stream/keys [picture last-stats feeder] :as broadcaster} request]
  (http-kit/as-channel
    request
    {:on-open  (fn [channel]
                 (let [now-ms (System/currentTimeMillis)
                       frame  (picture-frame! broadcaster snapshot-event
                                              (picture now-ms) @last-stats
                                              (feeder) now-ms)]
                   (when (and (http-kit/send! channel {:status  200
                                                       :headers sse/headers}
                                              false)
                              (http-kit/send! channel frame false))
                     (register! broadcaster channel))))
     :on-close (fn [channel _status]
                 (unregister! broadcaster channel))}))

;; ---------------------------------------------------------------------
;; Lifecycle

(def ^:private broadcast-threads
  "Daemon threads, so a live broadcaster never blocks JVM exit."
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. ^Runnable runnable "adsb-sse-broadcast")
        (.setDaemon true)))))

(defn- schedule!
  "Run task! every period-ms, forever. The catch is load-bearing: a
  ScheduledExecutorService silently cancels a task that throws, and a
  broadcast that dies is a map that freezes."
  [^ScheduledExecutorService executor period-ms task!]
  (.scheduleAtFixedRate executor
                        (fn []
                          (try
                            (task!)
                            (catch Throwable e
                              (log/error e "SSE broadcast task failed"))))
                        (long period-ms)
                        (long period-ms)
                        TimeUnit/MILLISECONDS))

(defn start!
  "Start the broadcast tick and the heartbeat. Options:

    :picture       REQUIRED — fn of now-ms returning the picture
                   (icao -> aircraft) to put on the wire. Production
                   injects adsb.state/age-out! (see the ns docstring).
    :stats         fn of [picture now-ms] returning the session stats map
                   (adsb.stats) for the frame, or nil. Called only on the
                   tick; defaults to (constantly nil) — no stats.
    :feeder        thunk returning the feeder status map
                   (adsb.ingest.poll/status) for the frame, or nil. Read
                   fresh per frame (a plain atom read, thread-safe);
                   defaults to (constantly nil) — no feeder health.
    :interval-ms   update tick period (default 1000, ~1 Hz)
    :heartbeat-ms  heartbeat comment period (default 15000)

  Returns a broadcaster to hand to connect!, client-count, and stop!."
  [{:keys [picture stats feeder interval-ms heartbeat-ms]
    :or   {stats        (constantly nil)
           feeder       (constantly nil)
           interval-ms  default-interval-ms
           heartbeat-ms default-heartbeat-ms}}]
  (let [executor    (Executors/newSingleThreadScheduledExecutor
                      broadcast-threads)
        broadcaster {:stream/picture    picture
                     :stream/stats      stats
                     :stream/feeder     feeder
                     :stream/last-stats (atom nil)
                     :stream/clients    (atom #{})
                     :stream/frame-id   (atom 0)
                     :stream/executor   executor}]
    (schedule! executor interval-ms #(broadcast-picture! broadcaster))
    (schedule! executor heartbeat-ms #(broadcast-heartbeat! broadcaster))
    broadcaster))

(defn client-count
  "How many SSE clients are connected right now."
  [{:stream/keys [clients]}]
  (count @clients))

(defn stop!
  "Stop the ticks and close every client. Idempotent."
  [{:stream/keys [^ScheduledExecutorService executor clients]}]
  (.shutdownNow executor)
  (doseq [channel @clients]
    (http-kit/close channel))
  (reset! clients #{})
  nil)
