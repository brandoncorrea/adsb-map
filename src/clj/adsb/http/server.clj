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
                            {:port port :legacy-return-value? false})]
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
