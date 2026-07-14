(ns adsb.stream.broadcast
  "SSE fan-out of the aircraft picture: a client registry, an immediate
  per-aircraft upsert path fed by the streaming Sources (adsb-jpf), a
  full-picture tick for poll deployments, a low-rate stats tick, and a
  heartbeat.

  AIRCRAFT DATA AND STATS NEVER SHARE A FRAME (owner decision,
  adsb-jpf): coupled in one envelope, a change to either forces itself
  on the other; separated, each evolves alone. So a client sees five
  kinds of traffic:

    * one `config` on connect, BEFORE the snapshot — the static boot
      config, which today is the privacy crop's declared boundary
      (adsb.ingest.crop). It has no tick and is never resent: nothing on
      it can change while the process lives, so a client learns it once
      and relearns it on reconnect, which is exactly when the process may
      have been replaced under it. It leads because it is the frame of
      reference the map draws — the reader sees the edge of what this app
      publishes before the aircraft land inside it.

    * one `snapshot` on connect — the full picture, aircraft only —
      followed immediately by one `stats` frame, so the chrome
      populates without waiting out a stats interval.

    * `aircraft` events — ONE full merged aircraft per event, pushed the
      instant a streaming Source applies a message. NO tick and NO
      coalesce window sit in this path (owner requirement, adsb-jpf):
      the ingest reader thread enqueues into a small bounded queue
      (offer-delta! — offer, never put) and a dedicated fan-out thread
      drains it to every ready client, one SSE frame per delta. Poll
      deployments never enqueue and get their aircraft from the tick
      below. On a streaming deployment this path IS the aircraft
      stream: there is no recurring full-picture frame behind it.

    * `update` events — the full picture, aircraft only, every
      :interval-ms. The POLL deployment's update path (~1 Hz — its
      cadence is inherent to polling). A streaming deployment passes
      :interval-ms nil and sends NO update frames at all: aircraft data
      flows exclusively as snapshot + upserts.

    * `stats` events — session stats and feeder health, every
      :stats-interval-ms (~10 s), both deployments alike. Stats are
      computed on this cadence ONLY, never per delta and never per
      update frame.

  CONVERGENCE, without a reconcile frame. A dropped or lost upsert
  heals on that aircraft's next message (~0.5 s on a live feed) —
  upserts are idempotent full states, not diffs. An aircraft that goes
  SILENT is removed by client-side age-out: the browser judges every
  aircraft against the shared adsb.aircraft threshold on its own clock,
  so a track the server would prune disappears from the map without a
  server frame saying so. The server's own sweep still runs here on the
  scheduler — the ticks obtain the picture through the injected
  `:picture` fn (a fn of now-ms), and production injects
  adsb.state/age-out!, which prunes long-silent aircraft AND returns
  the surviving picture. The stats tick runs EVEN WITH ZERO CLIENTS and
  even when :interval-ms is nil, so the sweep never stops (decision
  recorded on adsb-kbm.2; the upsert path changes nothing here, since
  it never removes anything).

  Frame ids increment across ALL event kinds — snapshot, update, stats,
  aircraft — off one shared counter, so ids increase over the whole
  stream; the tick thread and the fan-out thread can interleave sends,
  so ids on a channel are increasing but not always consecutive.

  ## Slow-consumer policy (adsb-kbm.2, re-argued for adsb-jpf)

  http-kit's async channels do not backpressure: send! only enqueues,
  and its boolean says whether the channel was still open. The policy
  is DROP-AND-CLOSE: any client whose send! returns false is closed and
  unregistered on the spot. The OLD bound — every frame is the full
  picture, so at most one bounded frame per tick queues — is GONE:
  `aircraft` events are per-delta frames. What bounds a
  stalled-but-still-open consumer now is different but still real:

    * a per-delta frame is SMALL (one wire aircraft, a few hundred
      bytes), and frames are produced at the feed's message rate — the
      server cannot manufacture them faster than the radio does, and
      the bounded handoff queue caps any burst the fan-out replays;
    * http-kit buffers per channel only until the OS pushes back and
      send! reports the channel closed, at which point the client is
      dropped — so a stalled channel holds a bounded burst of small
      frames, never an unbounded backlog of full pictures;
    * healing is owed to no one: a dropped client reconnects into a
      fresh snapshot, a client that missed upserts (queue pressure, a
      lossy interlude) converges on each aircraft's next message, and a
      silent aircraft leaves via client-side age-out (CONVERGENCE,
      above).

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
    (java.util.concurrent ArrayBlockingQueue Executors
                          ScheduledExecutorService ThreadFactory TimeUnit)
    (org.httpkit.server AsyncChannel)))

(def ^:const default-interval-ms 1000)

(def ^:const default-stats-interval-ms
  "The `stats` event cadence — and, riding along, the floor on the
  server's age-out sweep when no update tick runs (ns docstring). Low
  on purpose: stats are a readout, not the sky."
  10000)

(def ^:const default-delta-queue-depth
  "Capacity of the bounded reader -> fan-out handoff queue. A busy local
  sky is ~35 aircraft at ~60 messages/s, against a fan-out that drains
  in microseconds — the queue lives near empty. 1024 slots is ~17 s of
  headroom under a wholly stalled fan-out before offers start failing,
  and a failed offer is safe by design (offer-delta!)."
  1024)

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

(def ^:const aircraft-event
  "The single-aircraft upsert event (adsb-jpf): one full merged aircraft
  on the adsb.wire upsert envelope, pushed per ingest message."
  "aircraft")

(def ^:const stats-event
  "The stats event: session stats and feeder health on the adsb.wire
  stats envelope — the ONLY frame either may ride (ns docstring)."
  "stats")

(def ^:const config-event
  "The config event: the static boot config, sent ONCE per connection
  ahead of the snapshot and never again. Nothing on it can change while
  the process lives, so it has no tick — a client learns it on connect and
  relearns it on reconnect, which is exactly when the process might have
  been replaced under it.

  Today it carries only the privacy crop's declared boundary (adsb.wire/
  crop->wire) so the map can draw the edge of what this app publishes."
  "config")

;; ---------------------------------------------------------------------
;; Frames — every builder shares the frame-id counter (the ! is that
;; counter), so ids increase across the whole stream regardless of kind.

(defn- picture-frame!
  "One full-picture SSE frame as of now-ms — aircraft data only."
  [{:stream/keys [frame-id]} event-name picture now-ms]
  (sse/event-frame event-name
                   (swap! frame-id inc)
                   (json/generate-string
                     (wire/picture->wire picture now-ms))))

(defn- upsert-frame!
  "One single-aircraft upsert SSE frame: the full merged state one
  streaming-Source message produced, as of its arrival instant."
  [{:stream/keys [frame-id]} aircraft now-ms]
  (sse/event-frame aircraft-event
                   (swap! frame-id inc)
                   (json/generate-string
                     (wire/upsert->wire aircraft now-ms))))

(defn- stats-frame!
  "One stats SSE frame as of now-ms: the session `stats` (adsb.stats, or
  nil) and the `feeder` health (adsb.ingest.poll/status, or nil)."
  [{:stream/keys [frame-id]} stats feeder now-ms]
  (sse/event-frame stats-event
                   (swap! frame-id inc)
                   (json/generate-string
                     (wire/stats-event->wire stats feeder now-ms))))

(defn- config-frame!
  "One config SSE frame as of now-ms: the static boot config, which today
  is the privacy crop's declared boundary (or nothing, when the crop is
  disabled)."
  [{:stream/keys [frame-id crop]} now-ms]
  (sse/event-frame config-event
                   (swap! frame-id inc)
                   (json/generate-string
                     (wire/config-event->wire crop now-ms))))

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

(defn- rejection-response
  "The 503 an over-limit connect gets: a JSON body naming the reason and a
  Retry-After. A plain Ring response map, so the same rejection can be
  RETURNED before the SSE upgrade (connect!, the common case) or SENT over
  the async channel after it (reject!, the race case)."
  [reason]
  {:status  503
   :headers {"Content-Type" "application/json"
             "Retry-After"  (str retry-after-s)}
   :body    (json/generate-string
              {:error  "stream at capacity"
               :reason (name reason)})})

(defn- reject!
  "Answer an over-limit connect that slipped past the synchronous check in
  connect! and lost the slot in the :on-open race. Sent over the async
  channel because the request is already upgraded by then — which is
  exactly why Cloudflare turns this into a 504 for the client (adsb-1se),
  and exactly why connect! rejects the COMMON case before upgrading. This
  path is the rare tail, not the norm."
  [broadcaster channel reason ip]
  (log-rejection! broadcaster reason ip)
  (http-kit/send! channel (rejection-response reason) true))

;; ---------------------------------------------------------------------
;; The tick and the heartbeat

(defn- broadcast-picture!
  "One update tick — the POLL deployment's aircraft path (~1 Hz; a
  streaming deployment does not schedule this at all, ns docstring):
  obtain the picture as of now — the injected fn runs even with no
  audience, because in production it is also the age-out sweep — and fan
  the full picture out to whoever is listening. Aircraft only; stats
  live on their own tick."
  [{:stream/keys [picture clients] :as broadcaster}]
  (let [now-ms          (System/currentTimeMillis)
        current-picture (picture now-ms)]
    (when (seq @clients)
      (broadcast! broadcaster
                  (picture-frame! broadcaster update-event
                                  current-picture now-ms)))))

(defn- broadcast-stats!
  "One stats tick: obtain the picture as of now — on a streaming
  deployment (no update tick) THIS is the age-out sweep, so it runs even
  with no audience — compute the session stats and cache them for the
  next connect's post-snapshot stats frame, then fan a stats frame out
  to whoever is listening. Stats are computed ONLY here, on the single
  scheduler thread and never per delta, so their accumulator
  (adsb.stats) has one writer; connect! reuses the cache rather than
  recomputing off-thread. The feeder status is a plain read of the
  poller's atom, safe from any thread, so it is read fresh per frame
  rather than cached."
  [{:stream/keys [picture stats feeder last-stats clients] :as broadcaster}]
  (let [now-ms          (System/currentTimeMillis)
        current-picture (picture now-ms)
        current-stats   (stats current-picture now-ms)]
    (reset! last-stats current-stats)
    (when (seq @clients)
      (broadcast! broadcaster
                  (stats-frame! broadcaster current-stats (feeder) now-ms)))))

(defn- broadcast-heartbeat! [broadcaster]
  (broadcast! broadcaster (sse/comment-frame "hb")))

;; ---------------------------------------------------------------------
;; The per-aircraft upsert path (adsb-jpf): reader thread -> bounded
;; queue -> dedicated fan-out thread -> every ready client. No tick, no
;; coalescing, and the enqueue side can never block.

(defn offer-delta!
  "Hand one aircraft's full merged post-accumulate state (plus its
  arrival instant) to the fan-out thread. Called from an INGEST READER
  THREAD, which must never block on fan-out — and it never does: a
  bounded queue and `offer`, never `put`. Returns true when enqueued,
  false when the queue was full and the delta was DROPPED.

  Drop policy: the NEWEST delta is dropped — the failed offer is simply
  discarded — not the oldest. Drop-oldest sounds fresher, but buying it
  takes a poll-then-offer dance that races the draining thread and can
  STILL fail, where a lone failed offer is one atomic, provably
  non-blocking call. And the freshness it would buy is negligible: the
  queue is only full when the fan-out has stalled for many seconds, at
  which point everything in it is about stale to the same degree — and
  every drop heals regardless, because upserts are idempotent full
  states: the aircraft's next message (~0.5 s on a live feed) or the
  reconcile frame re-asserts it. Simplicity wins the tie."
  [{:stream/keys [^ArrayBlockingQueue deltas]} aircraft now-ms]
  (.offer deltas [aircraft now-ms]))

(def ^:const ^:private delta-poll-ms
  "How long the fan-out thread parks waiting for a delta before
  re-checking whether it was stopped. Latency is unaffected — a parked
  poll wakes the instant an offer lands."
  100)

(defn- take-delta!
  "The next queued [aircraft now-ms], or nil after delta-poll-ms of
  quiet (or an interrupt) so the loop can notice stop!."
  [^ArrayBlockingQueue deltas]
  (try
    (.poll deltas delta-poll-ms TimeUnit/MILLISECONDS)
    (catch InterruptedException _ nil)))

(defn- broadcast-delta!
  "Fan one dequeued delta out as an `aircraft` frame — skipping even the
  serialization when nobody is connected, which is the poll deployment
  and the empty-audience streaming one."
  [{:stream/keys [clients] :as broadcaster} [aircraft now-ms]]
  (when (seq @clients)
    (broadcast! broadcaster (upsert-frame! broadcaster aircraft now-ms))))

(defn- run-delta-fan-out!
  "The fan-out thread's body: drain the handoff queue, one SSE frame per
  delta. The catch is load-bearing for the same reason as schedule!'s: a
  fan-out loop that dies is a stream that silently degrades to reconcile
  cadence."
  [{:stream/keys [delta-running? deltas] :as broadcaster}]
  (loop []
    (when @delta-running?
      (when-some [delta (take-delta! deltas)]
        (try
          (broadcast-delta! broadcaster delta)
          (catch Throwable e
            (log/error e "SSE delta fan-out failed"))))
      (recur))))

(defn- start-delta-fan-out!
  "Start the dedicated fan-out consumer. A daemon, like every broadcast
  thread, so a live broadcaster never blocks JVM exit."
  ^Thread [broadcaster]
  (doto (Thread. ^Runnable #(run-delta-fan-out! broadcaster)
                 "adsb-sse-delta-fan-out")
    (.setDaemon true)
    (.start)))

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
  switch the request to an http-kit async channel, send the SSE headers,
  one full snapshot, and one stats frame, and join the traffic that
  follows. The registry
  slot is claimed atomically on open and freed by on-close (the browser
  went away), by a failed send (drop-and-close), or by rejection.

  Over-limit connects are rejected SYNCHRONOUSLY here, before the SSE
  upgrade, returning a plain 503. Rejecting after as-channel — sending a
  503 over a just-upgraded stream — is what Cloudflare reports to the
  client as a 504 (adsb-1se). This pre-check is a fast path, not the
  gate: try-register! in :on-open is still the authoritative atomic claim,
  and it covers the race where the last slot is taken between this read
  and that claim. That race still 504s, but it is a rare tail, not the
  every-over-cap-connect norm this fixes."
  [{:stream/keys [picture last-stats feeder trust-forwarded?
                  trusted-proxy-hops clients limits]
    :as          broadcaster}
   request]
  ;; config -> snapshot -> stats, in that order and once each. Config
  ;; leads because it is what the map draws its own frame of reference
  ;; with (the crop boundary); the aircraft then land inside a chart the
  ;; reader can already see the edges of.
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
                       ;; Config, snapshot, then one immediate stats frame
                       ;; from the tick's cache, so the chrome populates
                       ;; without waiting out a stats interval.
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

;; ---------------------------------------------------------------------
;; Lifecycle

(def ^:private broadcast-threads
  "Daemon threads, so a live broadcaster never blocks JVM exit."
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. ^Runnable runnable "adsb-sse-broadcast")
        (.setDaemon true)))))

(defn- schedule!
  "Run task! every period-ms, forever, starting after initial-delay-ms
  (0 runs it at once — the stats tick warms its cache that way). The
  catch is load-bearing: a ScheduledExecutorService silently cancels a
  task that throws, and a broadcast that dies is a map that freezes."
  [^ScheduledExecutorService executor initial-delay-ms period-ms task!]
  (.scheduleAtFixedRate executor
                        (fn []
                          (try
                            (task!)
                            (catch Throwable e
                              (log/error e "SSE broadcast task failed"))))
                        (long initial-delay-ms)
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
  [{:keys [picture stats feeder stats-interval-ms heartbeat-ms crop
           delta-queue-depth max-clients max-per-ip trust-forwarded?
           trusted-proxy-hops diagnose-client-ip?]
    :or   {stats             (constantly nil)
           feeder            (constantly nil)
           stats-interval-ms default-stats-interval-ms
           heartbeat-ms      default-heartbeat-ms
           delta-queue-depth default-delta-queue-depth}
    :as   options}]
  (let [;; get, not :or destructuring: an EXPLICIT nil must survive to
        ;; mean "no update tick" (docstring), and :or would default it.
        interval-ms (get options :interval-ms default-interval-ms)
        executor    (Executors/newSingleThreadScheduledExecutor
                      broadcast-threads)
        broadcaster {:stream/picture    picture
                     :stream/stats      stats
                     :stream/feeder     feeder
                     :stream/crop       crop
                     :stream/last-stats (atom nil)
                     :stream/clients    (atom {})
                     :stream/deltas     (ArrayBlockingQueue.
                                          (int delta-queue-depth))
                     :stream/delta-running? (atom true)
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
    (when interval-ms
      (schedule! executor interval-ms interval-ms
                 #(broadcast-picture! broadcaster)))
    (schedule! executor 0 stats-interval-ms #(broadcast-stats! broadcaster))
    (schedule! executor heartbeat-ms heartbeat-ms
               #(broadcast-heartbeat! broadcaster))
    (assoc broadcaster
           :stream/delta-thread (start-delta-fan-out! broadcaster))))

(defn client-count
  "How many SSE clients are connected right now."
  [{:stream/keys [clients]}]
  (count @clients))

(defn trusts-forwarded-for?
  "Whether this broadcaster believes the edge-supplied client address
  (ADSB_TRUST_FORWARDED_FOR — CF-Connecting-IP / X-Forwarded-For). False
  means the per-IP cap keys on the TCP peer, which behind a proxy is the
  proxy — so the composition root warns when a boot resolves it false.
  Reads the RESOLVED value, not the environment, so the warning cannot
  drift from what the running broadcaster actually does."
  [{:stream/keys [trust-forwarded?]}]
  (boolean trust-forwarded?))

(defn stop!
  "Stop the ticks and the delta fan-out and close every client.
  Idempotent."
  [{:stream/keys [^ScheduledExecutorService executor clients
                  delta-running? ^Thread delta-thread]}]
  (.shutdownNow executor)
  (some-> delta-running? (reset! false))
  (some-> delta-thread .interrupt)
  (doseq [channel (keys @clients)]
    (http-kit/close channel))
  (reset! clients {})
  nil)
