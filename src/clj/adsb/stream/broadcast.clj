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
  owed to it.

  ## Connection limits (adsb-kh4.4)

  The app is internet-facing and the stream is anonymous, so admission
  is bounded HERE, in the registry — the reverse proxy cannot count
  event-stream clients reliably. Two caps, both configurable:

    * a total cap on concurrent SSE clients (ADSB_SSE_MAX_CLIENTS,
      default 100) — at cap, a new connect gets 503 + Retry-After
    * a per-IP cap (ADSB_SSE_MAX_PER_IP, default 4) — one browser
      opening a handful of tabs is fine; one client opening hundreds
      of connections is a denial of service and gets 503

  ## The X-Forwarded-For trust model

  Per-IP counting needs the client's address. `X-Forwarded-For` is an
  ordinary request header — any client can send one — so it is honored
  ONLY when :trust-forwarded? is set (ADSB_TRUST_FORWARDED_FOR=true),
  which is correct exactly when the app port is NOT directly reachable
  and every connection arrives through a trusted reverse proxy (App
  Platform: DigitalOcean's router is the only way in). Direct
  deployments leave the flag off and the TCP peer address is used; a
  spoofed XFF is then just bytes. (The peer is read from the socket
  itself, NOT from ring's :remote-addr — see socket-peer-ip for the
  http-kit trap there.)

  Trusting the header is only half the question. The other half is WHICH
  of its entries is the client, and that depends on how many proxies
  stand in front — :trusted-proxy-hops / ADSB_TRUSTED_PROXY_HOPS, which
  is a fact about the DEPLOYMENT and cannot be inferred from the code.
  One trusted hop makes the rightmost entry the client. A managed
  platform may front the app with more, and then the rightmost entry is
  the platform's own internal address — which would count every visitor
  on earth as ONE IP and lock the site the moment :max-per-ip strangers
  are watching. We run on a managed platform whose chain we have not
  measured, so the default is a GUESS: verify it against the deployed
  environment before trusting the per-IP cap. forwarded-ip spells out
  both failure directions."
  (:require
    [adsb.stream.sse :as sse]
    [adsb.wire :as wire]
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [org.httpkit.server :as http-kit])
  (:import
    (java.lang.reflect Field)
    (java.net InetSocketAddress)
    (java.nio.channels SelectionKey SocketChannel)
    (java.util.concurrent Executors ScheduledExecutorService
                          ThreadFactory TimeUnit)
    (org.httpkit.server AsyncChannel)))

(def ^:const default-interval-ms 1000)

(def ^:const default-heartbeat-ms 15000)

(def ^:const default-max-clients 100)

(def ^:const default-max-per-ip 4)

(def ^:const default-trusted-proxy-hops
  "Trusted proxies between the internet and this app. See forwarded-ip:
  this number decides WHICH X-Forwarded-For entry is the client, and the
  correct value is a property of the deployment, not of the code — so
  this default is the SIMPLEST GUESS (a single proxy in front), not a
  measured fact. DigitalOcean's real chain must be counted from a live
  request and set explicitly; until then the per-IP cap is unproven."
  1)

(def ^:const retry-after-s
  "Advisory Retry-After on a 503 rejection. One broadcast tick is 1 s,
  so slots churn fast; 30 s keeps a polite client from hammering."
  30)

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
;; The registry, admission, and the slow-consumer policy
;;
;; The registry is a map channel -> {:client/ip ip :client/ready? bool}.
;; A client is admitted (and holds a slot) the moment its channel opens,
;; but becomes :client/ready? — visible to the broadcast tick — only
;; after the SSE headers and snapshot went out, so the tick can never
;; write an update frame onto a channel whose response head hasn't been
;; sent yet.

(def ^:private async-channel-key-field
  "Reflective access to AsyncChannel's private SelectionKey; nil when
  http-kit's internals no longer match. See socket-peer-ip for why the
  reach past the ring map is necessary at all."
  (delay
    (try
      (doto (.getDeclaredField AsyncChannel "key")
        (.setAccessible true))
      (catch Exception _ nil))))

(defn- socket-peer-ip
  "The TCP peer's IP, read from the socket itself. Ring's :remote-addr
  is NOT that under http-kit: HttpRequest.getRemoteAddr silently
  substitutes the LEFTMOST X-Forwarded-For entry whenever the header is
  present — i.e. an anonymous direct client gets to name its own
  address by sending one header. A per-IP cap keyed on :remote-addr
  would be forgeable, so the cap counts the socket. Falls back to
  :remote-addr if the reflective read ever stops working (a future
  http-kit rearranging its internals): a degraded count beats a dead
  stream, and the total cap still binds."
  [request]
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
          (catch Exception _ nil)))
      (:remote-addr request)))

(defn forwarded-ip
  "Pure: the client address named by an `X-Forwarded-For` header, given
  the number of TRUSTED PROXY HOPS between the internet and this app.
  nil when the header is absent or empty.

  Every proxy in a chain APPENDS the peer it saw, so the header reads
  left-to-right from (attacker-writable) client claim to (trustworthy)
  last hop. With `hops` trusted proxies in front, the entry the outermost
  trusted proxy observed — the real client — sits at index (count - hops):

    hops=1, \"1.2.3.4\"                -> \"1.2.3.4\"   the client, no spoof
    hops=1, \"9.9.9.9, 1.2.3.4\"       -> \"1.2.3.4\"   client spoofed 9.9.9.9; ignored
    hops=2, \"1.2.3.4, 10.0.0.7\"      -> \"1.2.3.4\"   10.0.0.7 is the inner proxy
    hops=2, \"9.9.9.9, 1.2.3.4, 10.0.0.7\" -> \"1.2.3.4\"

  Getting `hops` RIGHT IS LOAD-BEARING, and being wrong fails in opposite
  directions. Too LOW and this returns a proxy's own address, so every
  visitor on earth is counted as one IP and the per-IP cap locks the site
  after :max-per-ip strangers. Too HIGH and it reaches left past the
  trusted chain into bytes the client chose, so the per-IP cap becomes
  spoofable (the total cap still binds, so this is a weakened limit, not
  an open door). Hence the clamp below is a floor at index 0, not a
  wraparound: a header shorter than the configured chain means `hops` is
  misconfigured, and we degrade to a weakened cap rather than to an
  outage."
  [hops header]
  (when-some [entries (some->> (some-> header (str/split #","))
                               (map str/trim)
                               (remove str/blank?)
                               seq
                               vec)]
    (nth entries (max 0 (- (count entries) hops)))))

(def ^:const cf-connecting-ip-header
  "Cloudflare's own name for the client. It is a SINGLE address, not a
  list, and Cloudflare overwrites any copy the client sent — so there is
  no chain to count and no hop number to get wrong."
  "cf-connecting-ip")

(defn- client-ip
  "The address a client is counted under, in order of preference:

    1. CF-Connecting-IP, when :trust-forwarded? is set. Our edge IS
       Cloudflare, and this is the address it says the client came from.
    2. The X-Forwarded-For entry the trusted proxy chain vouches for
       (forwarded-ip), when :trust-forwarded? is set but Cloudflare did
       not name the client. Kept as a fallback so a future edge that is
       not Cloudflare still gets a per-IP cap.
    3. The TCP peer address. What a direct deployment counts, and a
       spoofed header is then just bytes.

  WHY CF-Connecting-IP RATHER THAN COUNTING HOPS (adsb-nnk). Counting was
  measured against the live deployment and it does not work: five
  concurrent streams from ONE address, against a cap of four, were all
  admitted. The chain is browser -> Cloudflare -> DigitalOcean's edge,
  which is ITSELF Cloudflare, and the address the last hop appends is
  drawn from a pool that varies per connection — so every connection keyed
  under a different address and the cap never bound. A hop COUNT cannot
  fix that: there is no fixed index at which the client reliably sits.
  CF-Connecting-IP sidesteps the arithmetic entirely.

  It is trustworthy only because of the origin lock
  (adsb.http.security/wrap-origin-lock): a request that reaches this
  function provably came through our Cloudflare edge, so the header is
  Cloudflare's word and not a stranger's. Without the lock this header is
  exactly as forgeable as the one it replaces — which is why the lock,
  not this line, is the fix."
  [trust-forwarded? hops request]
  (or (when trust-forwarded?
        (or (some-> (get-in request [:headers cf-connecting-ip-header])
                    str/trim
                    not-empty)
            (forwarded-ip hops (get-in request [:headers "x-forwarded-for"]))))
      (socket-peer-ip request)))

(defn- deny-reason
  "Pure: why a client at ip may NOT join the registry — :server-full or
  :ip-full — or nil when there is room."
  [clients ip {:keys [max-clients max-per-ip]}]
  (cond
    (>= (count clients) max-clients)
    :server-full

    (>= (count (filter #(= ip (:client/ip %)) (vals clients))) max-per-ip)
    :ip-full

    :else nil))

(defn- try-register!
  "Atomically admit channel under the limits. Returns nil on admission,
  else the :server-full / :ip-full reason. Check and insert happen in
  one swap so two racing connects cannot both squeeze past the cap."
  [{:stream/keys [clients limits]} channel ip]
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
  (doseq [[channel {:client/keys [ready?]}] @clients
          :when ready?]
    (send-or-drop! broadcaster channel frame)))

(def ^:const ^:private reject-log-interval-ms
  "Rejections are anonymous-input noise; log at most one line per
  interval so a hammering client cannot fill the disk."
  10000)

(defn- log-rejection!
  [{:stream/keys [last-reject-log-ms]} reason ip]
  (let [now-ms    (System/currentTimeMillis)
        [old new] (swap-vals! last-reject-log-ms
                              #(if (>= (- now-ms %) reject-log-interval-ms)
                                 now-ms
                                 %))]
    (when (not= old new)
      (log/warn "SSE connect rejected" reason "for" ip
                "(further rejections muted for"
                (/ reject-log-interval-ms 1000) "s)"))))

(defn- reject!
  "Answer an over-limit connect with an honest 503 + Retry-After and
  close the channel. Sent through the async channel because admission
  is decided in :on-open — the first send! of a response map writes the
  real status line."
  [broadcaster channel reason ip]
  (log-rejection! broadcaster reason ip)
  (http-kit/send! channel
                  {:status  503
                   :headers {"Content-Type" "application/json"
                             "Retry-After"  (str retry-after-s)}
                   :body    (json/generate-string
                              {:error  "stream at capacity"
                               :reason (name reason)})}
                  true))

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
;; Client-address diagnostic (adsb-nnk) — TEMPORARY, and off by default.

(def ^:const diagnose-client-ip-env
  "Flag-gated, throwaway: log what the container ACTUALLY receives for a
  client's address at SSE connect. It exists because one fact cannot be
  inferred from source (adsb-nnk) — behind Cloudflare-in-front-of-
  Cloudflare, nobody knows whether the CF-Connecting-IP the container
  reads is the real visitor or an inner-edge address that rotates per
  connection. So: set this flag, deploy, open the stream from an address
  you know, read `doctl apps logs`, compare, then UNSET it and redeploy.
  No committed spec sets it."
  "ADSB_DIAGNOSE_CLIENT_IP")

(def ^:const diagnose-budget
  "Log at most this many connects, EVER, per process — a hard ceiling so
  the diagnostic self-limits even if the flag is left on. These are
  visitor IP addresses: a bounded burst is a measurement, an unbounded
  stream is a surveillance log, and this app does not keep one."
  20)

(defn- diagnose-client-ip!
  "No-op unless the diagnostic flag is on AND budget remains. Logs the
  raw address inputs the container saw for this connect — every candidate
  side by side — so the per-IP key can be settled against the real
  deployment. The socket peer, both headers, and the resolved key are all
  here precisely because which one equals the true visitor is the open
  question. Greppable prefix; the count runs down so `doctl apps logs`
  shows when it stopped."
  [{:stream/keys [diagnose-remaining]} request resolved-ip]
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

;; ---------------------------------------------------------------------
;; Connect

(defn connect!
  "The GET /api/stream handler body: admit the client under the
  connection limits (503 + Retry-After when over — ns docstring),
  switch the request to an http-kit async channel, send the SSE headers
  and one full snapshot, and join the ticks that follow. The registry
  slot is claimed atomically on open and freed by on-close (the browser
  went away), by a failed send (drop-and-close), or by rejection."
  [{:stream/keys [picture last-stats feeder trust-forwarded?
                  trusted-proxy-hops]
    :as          broadcaster}
   request]
  (let [ip (client-ip trust-forwarded? trusted-proxy-hops request)]
    (diagnose-client-ip! broadcaster request ip)
    (http-kit/as-channel
      request
      {:on-open  (fn [channel]
                   (if-some [reason (try-register! broadcaster channel ip)]
                     (reject! broadcaster channel reason ip)
                     (let [now-ms (System/currentTimeMillis)
                           frame  (picture-frame! broadcaster snapshot-event
                                                  (picture now-ms) @last-stats
                                                  (feeder) now-ms)]
                       (if (and (http-kit/send! channel
                                                {:status  200
                                                 :headers sse/headers}
                                                false)
                                (http-kit/send! channel frame false))
                         (mark-ready! broadcaster channel)
                         (drop-client! broadcaster channel)))))
       :on-close (fn [channel _status]
                   (unregister! broadcaster channel))})))

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

(defn- env-limit
  "An SSE limit from the process environment: a positive integer, or
  nil (unset, blank, or nonsense — a garbled limit falls back to the
  compiled default rather than to zero, which would be an outage)."
  [var-name]
  (when-some [value (System/getenv var-name)]
    (when-some [n (parse-long (str/trim value))]
      (when (pos? n) n))))

(defn- env-flag? [var-name]
  (= "true" (some-> (System/getenv var-name) str/trim str/lower-case)))

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

  Connection limits (ns docstring) — an explicit option wins, else the
  environment variable, else the compiled default. The env fallback
  lives here, at the lifecycle seam, so every entry point that starts a
  broadcaster gets the same operator-tunable limits:

    :max-clients      total concurrent SSE cap
                      (ADSB_SSE_MAX_CLIENTS, default 100)
    :max-per-ip       concurrent cap per client IP
                      (ADSB_SSE_MAX_PER_IP, default 4)
    :trust-forwarded? honor the proxy-appended X-Forwarded-For for the
                      per-IP count (ADSB_TRUST_FORWARDED_FOR=true,
                      default false). Set ONLY when the app port is
                      reachable exclusively through the trusted proxy.

    :trusted-proxy-hops  how many trusted proxies stand between the
                      internet and this app, which is what says WHICH
                      X-Forwarded-For entry is the client
                      (ADSB_TRUSTED_PROXY_HOPS, default 1 — a guess, not
                      a measurement). A managed platform may front the
                      app with more than one, and we run on one: read
                      forwarded-ip, then verify the real chain against
                      the deployment rather than assuming it.

    :diagnose-client-ip?  TEMPORARY. Log the raw address inputs at SSE
                      connect (ADSB_DIAGNOSE_CLIENT_IP, default off,
                      capped at diagnose-budget lines). The only way to
                      learn what CF-Connecting-IP the container actually
                      receives behind two Cloudflare layers — see
                      diagnose-client-ip-env and adsb-nnk.

  Returns a broadcaster to hand to connect!, client-count, and stop!."
  [{:keys [picture stats feeder interval-ms heartbeat-ms
           max-clients max-per-ip trust-forwarded? trusted-proxy-hops
           diagnose-client-ip?]
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
                     :stream/clients    (atom {})
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
  (doseq [channel (keys @clients)]
    (http-kit/close channel))
  (reset! clients {})
  nil)
