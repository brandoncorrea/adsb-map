(ns adsb.main
  "The composition root — the one place the backend becomes an organism.
  Production (-main, the uberjar's Main-Class) and `bb dev` (the :dev
  alias) both boot through start!, so there is exactly one wiring path:

    ultrafeeder Source -> poll loop -> range gate -> privacy crop -> adsb.state
                                                                       |-> SSE broadcast
                                                                       '-> HTTP API

  Both gates run before anything enters adsb.state (see `admit`): the
  range gate rejects what this antenna cannot physically have heard, the
  crop withholds what falls outside the disc we publicly declared. The
  second is why the feed does not draw a picture of the antenna.

  Config comes from the environment; the feeder URL is validated before
  anything starts, so a misconfigured boot dies loudly on line one
  rather than limping (docs/validation-boundaries.md, Boundary 3).

  The receiver position (adsb.ingest.receiver) is resolved once here at
  boot and lives only in this composition path — closed over by the
  poll callback's range gate AND by the broadcast stats fn (which
  measures max range from it), never stored in state, never serialized
  to the wire, never logged (privacy: adsb-nqf.3 / adsb-kbm.2). The
  stats fn emits only the scalar max range, not the position it measured
  from (adsb.stats)."
  (:require [adsb.env :as env]
            [adsb.http.server :as server]
            [adsb.ingest.beast-source :as beast-source]
            [adsb.ingest.config :as config]
            [adsb.ingest.crop :as crop]
            [adsb.ingest.plausibility :as plausibility]
            [adsb.ingest.poll :as poll]
            [adsb.ingest.receiver :as receiver]
            [adsb.ingest.replay :as replay]
            [adsb.ingest.sbs :as sbs]
            [adsb.ingest.source :as source]
            [adsb.ingest.ultrafeeder :as ultrafeeder]
            [adsb.ingest.wss :as wss]
            [adsb.state :as state]
            [adsb.stats :as stats]
            [adsb.stream.broadcast :as broadcast]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn- parse-port
  "Parse a PORT string to an int, defaulting when unset or blank. A
  non-numeric PORT is a boot-time misconfiguration and must fail loudly."
  [port]
  (if (str/blank? port)
    server/default-port
    (or (parse-long port)
        (throw (ex-info (str "PORT must be a number, got: " port)
                        {:type ::invalid-port :port port})))))

(def ^:const dev-csp-env
  "Serves the DEV Content-Security-Policy, which permits the two things
  shadow-cljs's dev tooling needs and the shipped release build never does
  — eval (the CLJS REPL) and inline style attributes (the dev HUD). See
  adsb.http.security. `bb dev` sets this; no deployment does, and the boot
  warns loudly if one ever tries."
  "ADSB_DEV_CSP")

(defn dev-csp?
  "Pure: does the environment ask for the relaxed dev CSP? Only the exact
  string \"true\" counts — a policy this consequential does not get turned
  off by a typo or a stray \"0\"."
  [env]
  (= "true" (some-> (get env dev-csp-env) str/trim str/lower-case)))

(def ^:const origin-token-env
  "The shared secret Cloudflare stamps on every request it sends to the
  origin, and without which the app answers 403 (adsb.http.security/
  wrap-origin-lock). Unset means NO LOCK — correct on a laptop, wrong in
  a deployment, and start! says so out loud."
  "ADSB_ORIGIN_TOKEN")

(defn origin-token
  "Pure: the origin-lock secret from an environment map, or nil when it
  is absent or blank. Blank counts as absent — an empty string would
  otherwise be a token every request could match by omitting the header
  entirely."
  [env]
  (some-> (get env origin-token-env) str/trim not-empty))

(defn env->config
  "Pure: derive the boot config from an environment map (string ->
  string). PORT defaults to adsb.http.server/default-port.
  ADSB_ULTRAFEEDER_URL is validated by start! (unless ADSB_SOURCE selects
  the fixture-replay Source, which needs no feeder). The map itself rides
  along as :env for the receiver-position override
  (ADSB_RECEIVER_LAT/LON — adsb.ingest.receiver)."
  [env]
  {:port            (parse-port (get env "PORT"))
   :source          (get env config/source-env)
   :ultrafeeder-url (get env config/feeder-url-env)
   :feed-url        (get env config/feed-url-env)
   :dev-csp?        (dev-csp? env)
   :origin-token    (origin-token env)
   :env             env})

(defn- admit
  "The two gates every aircraft passes before it may enter the picture,
  in order. Both take the whole batch; either may empty it.

    1. RANGE GATE (adsb.ingest.plausibility) — receiver-centred,
       generous, ANTI-SPOOFING. Drops what cannot have come from this
       antenna's sky. Disabled by a nil receiver-position.

    2. PRIVACY CROP (adsb.ingest.crop, adsb-au5) — decoy-centred, tight,
       PRIVACY. Drops what falls outside the disc we declared, so that
       the boundary of the published set is one we CHOSE rather than the
       antenna's horizon, whose centroid is the antenna. Disabled by a
       nil crop, which start! warns about loudly.

  Two gates, two concerns; neither substitutes for the other. Applied
  here at INGEST rather than at the wire (adsb.wire) on purpose: an
  aircraft that never enters adsb.state cannot leak through a future
  endpoint, through the stats, or through the connect-time snapshot. The
  crop is a property of the system, not of one serializer."
  [batch receiver-position crop]
  (-> batch
      (plausibility/gate-range receiver-position
                               plausibility/default-max-range-m)
      (crop/gate-crop crop)))

(defn- ingest-batch!
  "The poll loop's on-batch! seam: put the batch through both gates
  (admit) — the receiver position and the crop are resolved once at
  boot, never per poll — then merge what survives into the state store,
  stamping capture time here at the edge (the domain takes time as an
  argument). Jump flagging needs no wiring; it is composed into
  adsb.state/apply-batch!."
  [receiver-position crop batch]
  (-> batch
      (admit receiver-position crop)
      (state/apply-batch! (System/currentTimeMillis))))

(defn- delta->stream!
  "The streaming Sources' :on-delta hook body (adsb-jpf), run on the
  ingest READER THREAD for every accumulated message: put the one merged
  aircraft through the same two gates the poll path applies (admit), so
  a spoofed impossible-range track is rejected — and an aircraft outside
  the declared crop is withheld — per delta exactly as per poll. What
  survives goes to the broadcaster's bounded queue
  (broadcast/offer-delta!), which never blocks and drops under pressure.

  Jump flagging needs no wiring here either, and for the same reason it
  needs none in ingest-batch!: it is composed into the fold each path
  already runs — adsb.ingest.tcp/accumulate! for a stream, as
  adsb.state/apply-batch! for a poll (adsb-b36). The aircraft reaching
  this hook is therefore already marked if it teleported, and it stays
  marked on every later upsert, which a client applies as a full-state
  replacement.

  The aircraft arrives MERGED, which is what makes the crop's
  drop-the-position-less rule safe here: an altitude-only or
  velocity-only message from an in-crop aircraft carries the position it
  inherited from the picture, so it is not position-less and is not
  dropped (adsb.ingest.crop/outside-crop?).

  `fan-out` is nil for the first instants of boot (the hook is
  constructed before the broadcaster exists — see start!); a delta
  landing in that window is dropped, and the connect-time snapshot
  covers it."
  [{:keys [receiver-position crop broadcaster]} aircraft now-ms]
  (when (and broadcaster
             (seq (admit [aircraft] receiver-position crop)))
    (broadcast/offer-delta! broadcaster aircraft now-ms)))

(defn- ->stream-source
  "Construct a streaming Source (SBS or Beast) from ADSB_FEED_URL. `->source`
  is sbs/->source or beast-source/->source. A tcp:// feed uses the default
  plain-socket transport (a dev/LAN feeder); a wss:// feed dials the tunnel
  via adsb.ingest.wss, presenting the CF-Access service token from
  ADSB_FEEDER_AUTH_ID/SECRET (both-or-neither — half a credential fails
  loudly). A missing or malformed ADSB_FEED_URL already failed in
  config/parse-feed-url. `on-delta` is the per-message hook
  (delta->stream!) every streaming Source carries."
  [->source {:keys [scheme uri host port]} env on-delta]
  (let [opts (case scheme
               :tcp {:on-delta on-delta}
               :wss {:on-delta  on-delta
                     :transport (wss/transport uri
                                               (config/feeder-auth-headers env))})]
    (->source host port opts)))

(defn- build-source!
  "Select the ingest Source from ADSB_SOURCE and return
  [source feeder-url feeder-auth-headers]; the trailing two carry the poll
  path's HTTP feeder URL and auth headers for the receiver-position resolve,
  and are nil for every source that has no aircraft.json to consult (replay,
  sbs, beast). A misconfiguration — an unknown ADSB_SOURCE, a missing or
  malformed URL, or half a service-token credential — fails loudly here,
  before anything starts (Boundary 3).

    :poll    live ultrafeeder over HTTP, ADSB_ULTRAFEEDER_URL validated
    :replay  the recorded fixture (adsb.ingest.replay), no feeder needed
    :sbs     the SBS stream (:30003) via ADSB_FEED_URL
    :beast   the Beast stream (:30005) via ADSB_FEED_URL

  Only the streaming Sources take `on-delta` (the per-message push hook,
  adsb-jpf); poll and replay have no message-arrival instant to hook."
  [{:keys [source ultrafeeder-url feed-url env]} on-delta]
  (case (config/source-kind source)
    :replay [(replay/->source) nil nil]
    :poll   (let [feeder-url (config/validate-feeder-url ultrafeeder-url)
                  headers    (config/feeder-auth-headers env)]
              [(ultrafeeder/->source feeder-url ultrafeeder/default-timeout-ms
                                     headers)
               feeder-url headers])
    :sbs    [(->stream-source sbs/->source
                              (config/parse-feed-url feed-url) env on-delta)
             nil nil]
    :beast  [(->stream-source beast-source/->source
                              (config/parse-feed-url feed-url) env on-delta)
             nil nil]))

(defn start!
  "Boot the backend from config and return the running system:

    1. select the Source — live ultrafeeder (validating
       ADSB_ULTRAFEEDER_URL, which throws before anything starts) or the
       fixture-replay Source when ADSB_SOURCE=replay
    2. resolve the receiver position once (env override, else the
       feeder's receiver.json, else nil — range gate disabled) and the
       privacy crop once (ADSB_CROP_LAT/LON/RADIUS_KM, else nil — crop
       disabled and loudly warned; a PARTIAL crop throws)
    3. poll the source at ~1 Hz through both gates (admit) into the store
    4. broadcast over SSE. Aircraft data: a streaming Source (sbs/beast)
       pushes every message the instant it lands — its :on-delta hook
       (delta->stream!, adsb-jpf) feeds the broadcaster's per-aircraft
       upsert path, and NO update tick runs; a poll source keeps the
       ~1 Hz full-picture update tick, its cadence being inherent to
       polling. Stats and feeder health ride a separate low-rate `stats`
       event (adsb.stats computed there from the receiver position and
       the source's message-count side-channel; poll/status alongside,
       so a live stream over a dead feeder cannot masquerade as healthy
       in the browser). The stats tick doubles as the age-out sweep's
       floor (its picture fn is adsb.state/age-out!)
    5. serve HTTP with real feeder status on /healthz and the stream
       on /api/stream

  In replay mode the fixture always 'reaches', so the poller reports
  :ok and /healthz shows feeder-status \"ok\" — honestly meaning ingest
  is producing a picture, since there is no feeder whose reachability to
  report."
  [{:keys [port env dev-csp? origin-token] :as config}]
  (when-not origin-token
    ;; Loud, and for the same reason as the CSP warning below: the failure
    ;; is SILENT. Without the lock the container answers anyone who finds
    ;; its platform hostname, and every claim a request makes about who it
    ;; is (X-Forwarded-For, CF-Connecting-IP) becomes forgeable — so the
    ;; per-IP cap it feeds becomes decorative (adsb-wrx). We warn instead
    ;; of refusing to boot: an app that will not start is an outage, and
    ;; the total SSE cap still binds. On a laptop this line is expected.
    (log/warn (str "ORIGIN LOCK DISABLED (" origin-token-env " unset): this "
                   "process will answer ANY client that can reach it, not "
                   "only requests arriving through our edge — so "
                   "CF-Connecting-IP and X-Forwarded-For are forgeable and "
                   "the per-IP SSE cap cannot be trusted. Expected under "
                   "`bb dev`; in a deployment this is the incident.")))
  (when dev-csp?
    ;; Loud on purpose. This is the one switch in the app that weakens a
    ;; security boundary, and the failure mode it guards against is a
    ;; SILENT one: a production box that serves 'unsafe-eval' forever
    ;; because a variable leaked into its environment. If this line is in
    ;; a production log, that is the bug.
    (log/warn (str "CSP RELAXED FOR DEVELOPMENT (" dev-csp-env "=true): "
                   "script-src allows 'unsafe-eval' (the watch build and the "
                   "CLJS REPL run on eval), connect-src allows loopback "
                   "WebSockets (hot reload), style-src-attr allows "
                   "'unsafe-inline' (the dev HUD). This is for `bb dev` only — "
                   "it must NEVER be set in a deployed environment.")))
  (let [;; The delta hook is handed to the Source CONSTRUCTOR, but it
        ;; targets the broadcaster and the receiver position, which do
        ;; not exist yet — so it reads them through this late-bound atom
        ;; (delta->stream! no-ops until it is filled in below). The
        ;; reader thread must never block, so a promise is out.
        !fan-out          (atom nil)
        on-delta          (fn [aircraft now-ms]
                            (delta->stream! @!fan-out aircraft now-ms))
        [source feeder-url feeder-auth] (build-source! config on-delta)
        streaming?        (contains? #{:sbs :beast}
                                     (config/source-kind (:source config)))
        receiver-position (receiver/resolve-position! {:env      env
                                                       :base-url feeder-url
                                                       :headers  feeder-auth})
        ;; Throws on a partial or unparseable crop — a privacy control
        ;; that looks on and is off is worse than one nobody configured
        ;; (adsb.ingest.crop/env-crop).
        crop              (crop/env-crop env)
        accumulator       (stats/create)
        poller            (poll/start!
                            {:source    source
                             :on-batch! #(ingest-batch! receiver-position
                                                        crop %)})
        broadcaster       (broadcast/start!
                            {:picture state/age-out!
                             ;; The declared boundary, for the connect-time
                             ;; `config` event: the map draws the edge of
                             ;; what we publish. Safe on the wire precisely
                             ;; BECAUSE it is the decoy centre and not the
                             ;; antenna (adsb.wire/crop->wire). nil crop =
                             ;; no boundary drawn, never a fallback to the
                             ;; receiver position.
                             :crop crop
                             ;; nil DISABLES the update tick: a streaming
                             ;; deployment's aircraft flow per delta
                             ;; (adsb.stream.broadcast, adsb-jpf).
                             :interval-ms (when-not streaming?
                                            broadcast/default-interval-ms)
                             :stats   (fn [picture now-ms]
                                        (stats/compute!
                                          accumulator
                                          {:picture           picture
                                           :receiver-position receiver-position
                                           :now-ms            now-ms
                                           :messages          (:messages
                                                                (source/metadata
                                                                  source))}))
                             :feeder  #(poll/status poller)})
        http-server       (server/start-server!
                            {:port           port
                             :dev-csp?       dev-csp?
                             :origin-token   origin-token
                             :feeder-status  #(poll/status poller)
                             :stream-connect #(broadcast/connect!
                                                broadcaster %)})]
    ;; Arm the delta hook (see !fan-out above). From here every message a
    ;; streaming Source accumulates goes through both gates (admit) and is
    ;; pushed to the SSE clients the instant it lands.
    (reset! !fan-out {:receiver-position receiver-position
                      :crop              crop
                      :broadcaster       broadcaster})
    (when-not crop
      ;; The third of the silent-failure warnings, and the one with the
      ;; longest fuse. Nothing errors, nothing degrades, the map looks
      ;; perfect — we simply publish every aircraft the antenna can hear,
      ;; and the union of those positions is a disc centred on the roof.
      ;; Whoever wants the antenna does not need to attack anything; they
      ;; need to collect the feed for an afternoon and take a centroid
      ;; (adsb.ingest.crop, adsb-au5). Expected under `bb dev` against a
      ;; fixture; in a public deployment this line is the incident.
      (log/warn (str "PRIVACY CROP DISABLED (" crop/crop-lat-env "/"
                     crop/crop-lon-env "/" crop/crop-radius-km-env
                     " unset): this process publishes EVERY aircraft it "
                     "receives, so the boundary of the published set is the "
                     "antenna's own horizon and its centroid is the antenna. "
                     "Fine on a laptop against a fixture; if this is a "
                     "public deployment, the receiver's location is "
                     "recoverable from the feed.")))
    (when-not (broadcast/trusts-forwarded-for? broadcaster)
      ;; Same silent-failure family as the two warnings above. A per-IP cap
      ;; keyed on the socket peer does not error or degrade visibly — it just
      ;; counts the wrong address forever. This exact gap (ADSB_TRUST_FORWARDED_FOR
      ;; unset on the deployed app) hid for days behind Cloudflare because
      ;; nothing said so; adsb-nnk. Correct for a direct `bb dev`; behind a
      ;; proxy it means the cap is off.
      (log/warn (str "PER-IP SSE CAP KEYS ON THE SOCKET PEER "
                     "(ADSB_TRUST_FORWARDED_FOR not \"true\"): behind a proxy "
                     "the socket peer IS the proxy, so every visitor buckets "
                     "under one address and the per-IP cap does not bind. "
                     "Correct for a direct `bb dev`; in a deployment behind "
                     "Cloudflare / App Platform this is the incident — set it.")))
    {:system/poller      poller
     :system/broadcaster broadcaster
     :system/server      http-server}))

(defn stop!
  "Stop a running system, edge first, and block until each layer is down.
  For tests and the REPL — production stops by dying.

  Stops the server THIS system started, not whatever a global happens to
  hold: the system owns its handle (adsb-a07). By the time this returns,
  the socket is closed and the poll thread has ended, so nothing the
  system started can still be writing to adsb.state."
  [{:system/keys [poller broadcaster server]}]
  (server/stop-server! server)
  (broadcast/stop! broadcaster)
  (poll/stop! poller)
  nil)

(defn -main
  "Boot from the environment and park the main thread. We block on a
  never-delivered promise so the JVM stays up until the container stops
  it.

  adsb.env/read!, not System/getenv directly: a local .env backfills what
  the process environment does not define, so every entry point — uberjar,
  `bb dev`, a bare `clojure -M:dev`, the REPL — boots the same way. A real
  exported variable always wins, and production ships no .env."
  [& _args]
  (log/info "adsb starting")
  (start! (env->config (env/read!)))
  @(promise))
