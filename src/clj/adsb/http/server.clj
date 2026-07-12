(ns adsb.http.server
  "REPL-friendly lifecycle for the http-kit server. start! and stop! are
  idempotent and hold the running server in a private atom, so a REPL can
  restart freely. Composition happens in adsb.main — this namespace only
  binds the assembled handler to a port."
  (:require [adsb.http.routes :as routes]
            [adsb.state :as state]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as http-kit]))

(def ^:const default-port 8280)

(def ^:const max-request-line-bytes
  "Ceiling on the HTTP request line and each header line. The whole API
  is short GET paths; anything longer is a scanner or an attack, and
  http-kit answers it 414 before a handler runs (internet exposure,
  adsb-kh4.4)."
  8192)

(def ^:const max-request-body-bytes
  "Ceiling on a request body. This API accepts no bodies at all — every
  route is a GET — so the only job of this number is to stop an
  anonymous client streaming megabytes at the default 8 MB buffer.
  http-kit answers an over-limit body 413 before a handler runs."
  16384)

(defonce ^:private server (atom nil))

(defn start!
  "Start the server. Idempotent — a no-op returning the running server
  if one is already up. :port defaults to 8280; every other option is a
  handler dependency passed through to adsb.http.routes/handler —
  :state-lookup (default: the adsb.state store), :feeder-status, and
  :stream-connect."
  ([] (start! {}))
  ([{:keys [port] :or {port default-port} :as options}]
   (or @server
       (let [dependencies (merge {:state-lookup state/lookup}
                                 (dissoc options :port))
             srv          (http-kit/run-server
                            (routes/handler dependencies)
                            {:port                  port
                             :legacy-return-value?  false
                             :max-line              max-request-line-bytes
                             :max-body              max-request-body-bytes})]
         (log/info "adsb http server listening on port"
                   (http-kit/server-port srv))
         (reset! server srv)))))

(defn stop!
  "Stop the server if running. Idempotent."
  []
  (when-let [srv @server]
    (http-kit/server-stop! srv)
    (reset! server nil)
    (log/info "adsb http server stopped"))
  nil)

(comment
  (start! {:port 0})
  (stop!))
