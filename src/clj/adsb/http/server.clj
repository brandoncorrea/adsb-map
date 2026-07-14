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

(def ^:const server-header
  "http-kit announces itself in a `Server:` header. A reverse proxy of ours
  used to strip it; there is no longer a proxy of ours (App Platform,
  adsb-9n6/kh4.7), so the app declines to send it in the first place. nil
  means omit. Naming the exact HTTP library and version to an anonymous
  internet client only ever helps the client."
  nil)

(defn start-server!
  "Start a server and return its handle. ALWAYS starts a new one, and the
  caller owns what it gets back — nothing here is shared. This is the seam
  for anyone who needs a server of their own: the composition root, and any
  test that wants its own handler on its own port.

  :port defaults to 8280; every other option is a handler dependency passed
  through to adsb.http.routes/handler — :state-lookup (default: the
  adsb.state store), :feeder-status, and :stream-connect."
  ([] (start-server! {}))
  ([{:keys [port] :or {port default-port} :as options}]
   (let [dependencies (merge {:state-lookup state/lookup}
                             (dissoc options :port))
         srv          (http-kit/run-server
                        (routes/handler dependencies)
                        {:port                  port
                         :legacy-return-value?  false
                         :max-line              max-request-line-bytes
                         :max-body              max-request-body-bytes
                         :server-header         server-header})]
     (log/info "adsb http server listening on port"
               (http-kit/server-port srv))
     srv)))

(defn stop-server!
  "Stop a handle from start-server! and BLOCK until it has actually stopped.
  nil-safe and idempotent.

  The deref is the point. http-kit's server-stop! is asynchronous: it
  returns a promise and keeps draining in the background. Dropping that
  promise makes `stopped` and `gone` two different instants, so a caller
  that stopped a server and moved on could still be racing it (adsb-a07)."
  [srv]
  (when srv
    @(http-kit/server-stop! srv)
    (log/info "adsb http server stopped"))
  nil)

;; ---------------------------------------------------------------------
;; The REPL's one server
;;
;; A convenience for `bb dev` and a REPL: one server, held in a global, so
;; a restart is two keystrokes. Production and tests do NOT come through
;; here — they own their handle via start-server!. Note what start! must
;; do to stay idempotent: hand back the RUNNING server and discard the
;; options it was passed. That is exactly what you want from a REPL and
;; exactly what you do not want anywhere else, because a caller cannot
;; tell the server it asked for from the one it was given.

(defonce ^:private server (atom nil))

(defn start!
  "Start the REPL's server. Idempotent — a no-op returning the running
  server if one is already up, IGNORING the options passed. Callers that
  need a server of their own want start-server!."
  ([] (start! {}))
  ([options]
   (or @server (reset! server (start-server! options)))))

(defn stop!
  "Stop the REPL's server if running. Idempotent. Blocks until it is gone."
  []
  (when-let [srv @server]
    (stop-server! srv)
    (reset! server nil))
  nil)
