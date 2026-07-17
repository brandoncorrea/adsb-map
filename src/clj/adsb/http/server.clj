(ns adsb.http.server
  (:require [adsb.http.routes :as routes]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as http-kit]))

(def ^:const default-port 8280)
(def ^:const max-request-line-bytes 8192)
(def ^:const max-request-body-bytes 16384)
(def ^:const server-header nil)

(defn start-server!
  ([] (start-server! {}))
  ([{:keys [port] :or {port default-port} :as options}]
   (let [dependencies (dissoc options :port)
         srv          (http-kit/run-server
                        (routes/handler dependencies)
                        {:port                 port
                         :legacy-return-value? false
                         :max-line             max-request-line-bytes
                         :max-body             max-request-body-bytes
                         :server-header        server-header})]
     (log/info "adsb http server listening on port"
               (http-kit/server-port srv))
     srv)))

(defn stop-server! [srv]
  (when srv
    @(http-kit/server-stop! srv)
    (log/info "adsb http server stopped"))
  nil)

(defonce ^:private server (atom nil))

(defn start!
  "The REPL entry point: an idempotent singleton around start-server! so a
  REPL session can (start!)/(stop!) one server without juggling handles.
  Production boots through adsb.main, which calls start-server! directly."
  ([] (start! {}))
  ([options]
   (if-let [{:keys [srv] :as running} @server]
     (do
       (when (not= options (:options running))
         (log/warn (str "adsb http server is ALREADY RUNNING and start! is "
                        "idempotent: the options you passed were IGNORED and "
                        "you have been handed the server that is already up, "
                        "wired to the options below. Call stop! first. "
                        "running: " (pr-str (:options running))
                        " — requested: " (pr-str options))))
       srv)
     (:srv (reset! server {:srv     (start-server! options)
                           :options options})))))

(defn stop! []
  (when-let [{:keys [srv]} @server]
    (stop-server! srv)
    (reset! server nil))
  nil)
