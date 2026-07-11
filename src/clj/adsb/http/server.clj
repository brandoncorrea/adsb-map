(ns adsb.http.server
  "REPL-friendly lifecycle for the http-kit server. start! and stop! are
  idempotent and hold the running server in a private atom, so a REPL can
  restart freely. This is what `bb dev` boots.

  The aircraft store lands in adsb-nqf.2; until then start! defaults to an
  empty-state lookup and the aircraft endpoint honestly 404s."
  (:require
    [adsb.http.routes :as routes]
    [clojure.tools.logging :as log]
    [org.httpkit.server :as http-kit])
  (:gen-class))

(def ^:const default-port 8280)

(defn- empty-state-lookup
  "Default state-lookup: nothing is known, so every address 404s. The
  live store is injected in adsb-nqf.2."
  [_icao]
  nil)

(defonce ^:private server (atom nil))

(defn start!
  "Start the server. Idempotent — a no-op returning the running server if
  one is already up. Options: :port (default 8280) and :state-lookup (a
  fn from icao to aircraft-or-nil, default empty state)."
  ([] (start! {}))
  ([{:keys [port state-lookup]
     :or   {port default-port state-lookup empty-state-lookup}}]
   (or @server
       (let [srv (http-kit/run-server
                   (routes/handler state-lookup)
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

(defn -main [& _args]
  (start!)
  @(promise))

(comment
  (start!)
  (start! {:port 0})
  (stop!))
