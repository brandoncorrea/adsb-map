(ns adsb.main
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

(defn- parse-port [port]
  (if (str/blank? port)
    server/default-port
    (or (parse-long port)
        (throw (ex-info (str "PORT must be a number, got: " port)
                        {:type ::invalid-port :port port})))))

(def ^:const dev-csp-env "ADSB_DEV_CSP")

(defn dev-csp? [env]
  (= "true" (some-> (get env dev-csp-env) str/trim str/lower-case)))

(def ^:const origin-token-env "ADSB_ORIGIN_TOKEN")

(defn origin-token [env]
  (some-> (get env origin-token-env) str/trim not-empty))

(defn env->config [env]
  {:port            (parse-port (get env "PORT"))
   :source          (get env config/source-env)
   :ultrafeeder-url (get env config/feeder-url-env)
   :feed-url        (get env config/feed-url-env)
   :dev-csp?        (dev-csp? env)
   :origin-token    (origin-token env)
   :env             env})

(defn- admit [batch receiver-position crop]
  (-> batch
      (plausibility/gate-range receiver-position
                               plausibility/default-max-range-m)
      (crop/gate-crop crop)))

(defn- ingest-batch! [receiver-position crop batch]
  (-> batch
      (admit receiver-position crop)
      (state/apply-batch! (System/currentTimeMillis))))

(defn- delta->stream! [fan-out aircraft now-ms]
  (let [{:keys [receiver-position crop broadcaster]} fan-out]
    (when (and broadcaster
               (seq (admit [aircraft] receiver-position crop)))
      (broadcast/offer-delta! broadcaster aircraft now-ms))))

(defn- ->stream-source [->source {:keys [scheme uri host port]} env on-delta]
  (let [opts (case scheme
               :tcp {:on-delta on-delta}
               :wss {:on-delta  on-delta
                     :transport (wss/transport uri (config/feeder-auth-headers env))})]
    (->source host port opts)))

(defn- build-source! [{:keys [source ultrafeeder-url feed-url env]} on-delta]
  (case (config/source-kind source)
    :replay [(replay/->source) nil nil]
    :poll (let [feeder-url (config/validate-feeder-url ultrafeeder-url)
                headers    (config/feeder-auth-headers env)]
            [(ultrafeeder/->source feeder-url ultrafeeder/default-timeout-ms
                                   headers)
             feeder-url headers])
    :sbs [(->stream-source sbs/->source
                           (config/parse-feed-url feed-url) env on-delta)
          nil nil]
    :beast [(->stream-source beast-source/->source
                             (config/parse-feed-url feed-url) env on-delta)
            nil nil]))

(declare stop!)

(defn start! [{:keys [port env dev-csp? origin-token] :as config}]
  (when-not origin-token
    (log/warn (str "ORIGIN LOCK DISABLED (" origin-token-env " unset): this "
                   "process will answer ANY client that can reach it, not "
                   "only requests arriving through our edge — so "
                   "CF-Connecting-IP and X-Forwarded-For are forgeable and "
                   "the per-IP SSE cap cannot be trusted. Expected under "
                   "`bb dev`; in a deployment this is the incident.")))
  (when dev-csp?
    (log/warn (str "CSP RELAXED FOR DEVELOPMENT (" dev-csp-env "=true): "
                   "script-src allows 'unsafe-eval' (the watch build and the "
                   "CLJS REPL run on eval), connect-src allows loopback "
                   "WebSockets (hot reload), style-src-attr allows "
                   "'unsafe-inline' (the dev HUD). This is for `bb dev` only — "
                   "it must NEVER be set in a deployed environment.")))
  (let [!started (atom {})]
    (try
      (let [!fan-out          (atom nil)
            on-delta          (fn [aircraft now-ms]
                                (delta->stream! @!fan-out aircraft now-ms))
            [source feeder-url feeder-auth] (build-source! config on-delta)
            streaming?        (contains? #{:sbs :beast}
                                         (config/source-kind (:source config)))
            receiver-position (receiver/resolve-position!
                                {:env      env
                                 :base-url feeder-url
                                 :headers  feeder-auth})
            crop              (crop/env-crop env)
            accumulator       (stats/create)
            poller            (poll/start!
                                {:source    source
                                 :on-batch! #(ingest-batch! receiver-position
                                                            crop %)})
            _                 (swap! !started assoc :system/poller poller)
            broadcaster       (broadcast/start!
                                {:picture     state/age-out!
                                 :crop        crop
                                 :interval-ms (when-not streaming?
                                                broadcast/default-interval-ms)
                                 :stats       (fn [picture now-ms]
                                                (stats/compute!
                                                  accumulator
                                                  {:picture           picture
                                                   :receiver-position receiver-position
                                                   :now-ms            now-ms
                                                   :messages          (:messages
                                                                        (source/metadata
                                                                          source))}))
                                 :feeder      #(poll/status poller)})
            _                 (swap! !started assoc :system/broadcaster
                                     broadcaster)
            http-server       (server/start-server!
                                {:port           port
                                 :dev-csp?       dev-csp?
                                 :origin-token   origin-token
                                 :feeder-status  #(poll/status poller)
                                 :stream-connect #(broadcast/connect!
                                                    broadcaster %)})]
        (reset! !fan-out {:receiver-position receiver-position
                          :crop              crop
                          :broadcaster       broadcaster})
        (when-not crop
          (log/warn (str "PRIVACY CROP DISABLED (" crop/crop-lat-env "/"
                         crop/crop-lon-env "/" crop/crop-radius-km-env
                         " unset): this process publishes EVERY aircraft it "
                         "receives, so the boundary of the published set is the "
                         "antenna's own horizon and its centroid is the antenna. "
                         "Fine on a laptop against a fixture; if this is a "
                         "public deployment, the receiver's location is "
                         "recoverable from the feed.")))
        (when-not (:stream/trust-forwarded? broadcaster)
          (log/warn (str "PER-IP SSE CAP KEYS ON THE SOCKET PEER "
                         "(ADSB_TRUST_FORWARDED_FOR not \"true\"): behind a proxy "
                         "the socket peer IS the proxy, so every visitor buckets "
                         "under one address and the per-IP cap does not bind. "
                         "Correct for a direct `bb dev`; in a deployment behind "
                         "Cloudflare / App Platform this is the incident — set it.")))
        {:system/poller      poller
         :system/broadcaster broadcaster
         :system/server      http-server})
      (catch Throwable e
        (when (seq @!started)
          (log/error e "adsb boot failed; stopping what had already started")
          (stop! @!started))
        (throw e)))))

(defn stop! [{:system/keys [poller broadcaster server]}]
  (server/stop-server! server)
  (when broadcaster
    (broadcast/stop! broadcaster))
  (when poller
    (poll/stop! poller))
  nil)

(defn -main [& _args]
  (log/info "adsb starting")
  (start! (env->config (env/read!)))
  @(promise))
