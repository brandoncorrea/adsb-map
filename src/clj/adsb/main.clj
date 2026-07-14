(ns adsb.main
  "The composition root — the one place the backend becomes an organism.
  Production (-main, the uberjar's Main-Class) and `bb dev` (the :dev
  alias) both boot through start!, so there is exactly one wiring path:

    ultrafeeder Source -> poll loop -> range gate -> adsb.state
                                                       |-> SSE broadcast
                                                       '-> HTTP API

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

(defn- ingest-batch!
  "The poll loop's on-batch! seam: range-gate the batch against the
  receiver position — resolved once at boot, never per poll — then
  merge it into the state store, stamping capture time here at the
  edge (the domain takes time as an argument). Jump flagging needs no
  wiring; it is composed into adsb.state/apply-batch!."
  [receiver-position batch]
  (-> batch
      (plausibility/gate-range receiver-position
                               plausibility/default-max-range-m)
      (state/apply-batch! (System/currentTimeMillis))))

(defn- delta->stream!
  "The streaming Sources' :on-delta hook body (adsb-jpf), run on the
  ingest READER THREAD for every accumulated message: range-gate the one
  merged aircraft against the receiver position — the same gate
  ingest-batch! applies, so a spoofed impossible-range track is rejected
  per delta exactly as it is per poll — then hand it to the
  broadcaster's bounded queue (broadcast/offer-delta!), which never
  blocks and drops under pressure. `fan-out` is nil for the first
  instants of boot (the hook is constructed before the broadcaster
  exists — see start!); a delta landing in that window is dropped, and
  the connect-time snapshot covers it."
  [{:keys [receiver-position broadcaster]} aircraft now-ms]
  (when (and broadcaster
             (seq (plausibility/gate-range [aircraft] receiver-position
                                           plausibility/default-max-range-m)))
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
       feeder's receiver.json, else nil — range gate disabled)
    3. poll the source at ~1 Hz through the range gate into the store
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
        accumulator       (stats/create)
        poller            (poll/start!
                            {:source    source
                             :on-batch! #(ingest-batch! receiver-position %)})
        broadcaster       (broadcast/start!
                            {:picture state/age-out!
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
        http-server       (server/start!
                            {:port           port
                             :dev-csp?       dev-csp?
                             :origin-token   origin-token
                             :feeder-status  #(poll/status poller)
                             :stream-connect #(broadcast/connect!
                                                broadcaster %)})]
    ;; Arm the delta hook (see !fan-out above). From here every message a
    ;; streaming Source accumulates is range-gated and pushed to the SSE
    ;; clients the instant it lands.
    (reset! !fan-out {:receiver-position receiver-position
                      :broadcaster       broadcaster})
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
  "Stop a running system, edge first. For tests and the REPL —
  production stops by dying."
  [{:system/keys [poller broadcaster]}]
  (server/stop!)
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
