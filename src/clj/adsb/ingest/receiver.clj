(ns adsb.ingest.receiver
  "Where the antenna is: the receiver position the range gate measures
  from (adsb.ingest.plausibility/gate-range). Resolved ONCE at poller
  setup, never per poll: the ADSB_RECEIVER_LAT/LON environment
  override wins; otherwise the feeder's <base-url>/data/receiver.json
  (readsb serves top-level `lat`/`lon` beside refresh/history/version);
  otherwise nil — the range gate is disabled, and that is logged once
  here.

  PRIVACY (adsb-nqf.3): the receiver position locates a home antenna.
  It lives only inside ingest configuration — it is never attached to
  a domain aircraft, never stored in the state picture, never
  serialized to the wire, and its coordinates are never logged. The
  feeder's per-aircraft r_dst/r_dir (receiver-relative range and
  bearing) are likewise never copied by adsb.ingest.coerce — one
  aircraft's position plus its r_dst/r_dir locates the antenna
  exactly. Tests assert both absences."
  (:require
    [adsb.schema :as schema]
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [malli.core :as m]
    [org.httpkit.client :as http]))

(declare env-position fetch-position!)

(def ^:const receiver-lat-env "ADSB_RECEIVER_LAT")

(def ^:const receiver-lon-env "ADSB_RECEIVER_LON")

(def ^:private receiver-json-path "/data/receiver.json")

(def ^:const default-timeout-ms 5000)

(defn resolve-position!
  "The receiver position for the range gate, or nil when none can be
  had — in which case the gate is disabled and that is logged ONCE
  here, at setup, never per poll. `env` is an environment map
  (string->string, pass (System/getenv) at the edge); `base-url` is
  the validated feeder URL, or nil when there is nothing to ask;
  `headers` are the optional static feeder-auth headers (the Cloudflare
  Access service token — adsb.ingest.config/feeder-auth-headers) sent on
  the receiver.json request, matching the aircraft.json poll. The env
  override wins over the feeder. Coordinates are deliberately never
  logged — the position is private."
  [{:keys [env base-url timeout-ms headers]
    :or   {timeout-ms default-timeout-ms}}]
  (if-let [position (or (env-position env)
                        (when base-url
                          (fetch-position! base-url timeout-ms headers)))]
    (do (log/info "Receiver position resolved; range gate enabled")
        position)
    (do (log/warn (str "No receiver position (" receiver-lat-env "/"
                       receiver-lon-env
                       " unset, receiver.json unavailable);"
                       " range gate disabled"))
        nil)))

;; ---------------------------------------------------------------------
;; Candidate coordinates -> position, or nil

(def ^:private valid-position? (m/validator schema/position))

(defn- ->position
  "A {:geo/lat _ :geo/lon _} from candidate coordinate values, or nil
  when either is missing, non-numeric, or out of range. Out-of-range
  is rejected, never clamped."
  [lat lon]
  (let [position {:geo/lat lat :geo/lon lon}]
    (when (valid-position? position)
      position)))

;; ---------------------------------------------------------------------
;; The environment override

(defn- parse-coordinate [s]
  (when-not (str/blank? s)
    (try
      (Double/parseDouble s)
      (catch NumberFormatException _ nil))))

(defn env-position
  "The receiver position from the ADSB_RECEIVER_LAT/LON entries of an
  environment map, or nil unless BOTH are set, numeric, and in range.
  Pure given the map; reading the real environment stays at the
  caller's edge."
  [env]
  (->position (parse-coordinate (get env receiver-lat-env))
              (parse-coordinate (get env receiver-lon-env))))

;; ---------------------------------------------------------------------
;; The feeder's receiver.json

(defn- parse-receiver
  "receiver.json's body as keyword-keyed data, or nil when it isn't
  JSON — an unavailable position must disable the gate, never throw."
  [body]
  (try
    (json/parse-string body true)
    (catch Exception _ nil)))

(defn fetch-position!
  "GET the feeder's receiver.json once and return its position, or nil
  when the feeder is unreachable, answers non-200, or the body carries
  no usable lat/lon. Never throws — no position means a disabled range
  gate (resolve-position!), not a failed boot. `headers` are the optional
  static feeder-auth headers (the Cloudflare Access service token) sent so
  a token-gated tunnel lets the request through; nil on a trusted LAN."
  ([base-url] (fetch-position! base-url default-timeout-ms nil))
  ([base-url timeout-ms] (fetch-position! base-url timeout-ms nil))
  ([base-url timeout-ms headers]
   (let [url (str base-url receiver-json-path)
         {:keys [status body error]} @(http/request {:url     url
                                                     :method  :get
                                                     :timeout timeout-ms
                                                     :headers headers
                                                     :as      :text})]
     (when (and (nil? error) (= 200 status))
       (let [{:keys [lat lon]} (parse-receiver body)]
         (->position lat lon))))))
