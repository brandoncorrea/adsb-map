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
  poll callback's range gate, never stored in state, never serialized
  to the wire, never logged (privacy: adsb-nqf.3 / adsb-kbm.2)."
  (:require [adsb.http.server :as server]
            [adsb.ingest.config :as config]
            [adsb.ingest.plausibility :as plausibility]
            [adsb.ingest.poll :as poll]
            [adsb.ingest.receiver :as receiver]
            [adsb.ingest.ultrafeeder :as ultrafeeder]
            [adsb.state :as state]
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

(defn env->config
  "Pure: derive the boot config from an environment map (string ->
  string). PORT defaults to adsb.http.server/default-port.
  ADSB_ULTRAFEEDER_URL is validated by start!. The map itself rides
  along as :env for the receiver-position override
  (ADSB_RECEIVER_LAT/LON — adsb.ingest.receiver)."
  [env]
  {:port            (parse-port (get env "PORT"))
   :ultrafeeder-url (get env "ADSB_ULTRAFEEDER_URL")
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

(defn start!
  "Boot the backend from config and return the running system:

    1. validate ADSB_ULTRAFEEDER_URL — throws before anything starts
    2. resolve the receiver position once (env override, else the
       feeder's receiver.json, else nil — range gate disabled)
    3. poll the feeder at ~1 Hz through the range gate into the store
    4. broadcast the picture over SSE; the broadcast tick doubles as
       the age-out cadence (its picture fn is adsb.state/age-out!)
    5. serve HTTP with real feeder status on /healthz and the stream
       on /api/stream"
  [{:keys [port ultrafeeder-url env]}]
  (let [feeder-url        (config/validate-feeder-url ultrafeeder-url)
        receiver-position (receiver/resolve-position! {:env      env
                                                       :base-url feeder-url})
        poller            (poll/start!
                            {:source    (ultrafeeder/->source feeder-url)
                             :on-batch! #(ingest-batch! receiver-position %)})
        broadcaster       (broadcast/start! {:picture state/age-out!})
        http-server       (server/start!
                            {:port           port
                             :feeder-status  #(poll/status poller)
                             :stream-connect #(broadcast/connect!
                                                broadcaster %)})]
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
  "Boot from the process environment and park the main thread. We block
  on a never-delivered promise so the JVM stays up until the container
  stops it."
  [& _args]
  (log/info "adsb starting")
  (start! (env->config (System/getenv)))
  @(promise))

(comment
  (env->config {})
  (def system (start! (env->config (System/getenv))))
  (stop! system))
