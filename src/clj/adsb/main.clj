(ns adsb.main
  "Production entry point. Reads all configuration from the environment,
  starts the http server (adsb.http.server), and blocks forever. This is
  the class the uberjar's Main-Class points at — see build.clj.

  Config parsing is a pure function of an environment map so it can be
  tested with a literal and so the feeder URL (validated in adsb-nqf.1)
  can slot in beside the port without reshaping the reader."
  (:require
    [adsb.http.server :as server]
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:gen-class))

(defn- parse-port
  "Parse a PORT string to an int, defaulting when unset or blank. A
  non-numeric PORT is a boot-time misconfiguration and must fail loudly."
  [port]
  (if (str/blank? port)
    server/default-port
    (Long/parseLong port)))

(defn env->config
  "Pure: derive server config from an environment map (string->string).
  PORT defaults to adsb.http.server/default-port. ADSB_ULTRAFEEDER_URL is
  captured but not yet used — feeder ingest lands in adsb-nqf.1, which
  reads and validates it from here."
  [env]
  {:port            (parse-port (get env "PORT"))
   :ultrafeeder-url (get env "ADSB_ULTRAFEEDER_URL")})

(defn -main
  "Boot the server from the process environment and park the main thread.
  start! logs the bound port; we block on a never-delivered promise so the
  JVM stays up until the container stops it."
  [& _args]
  (let [{:keys [port]} (env->config (System/getenv))]
    (log/info "adsb starting")
    (server/start! {:port port})
    @(promise)))

(comment
  (env->config {})
  (env->config {"PORT" "9000" "ADSB_ULTRAFEEDER_URL" "http://feeder/data"}))
