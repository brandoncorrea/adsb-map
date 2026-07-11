(ns adsb.dev
  "Development entry point: http-kit on :8280 serving a stub page.
  The real routes (reitit, Malli coercion, static assets) are
  TODO(adsb-kbm.1) — do not grow them here."
  (:require
    [clojure.tools.logging :as log]
    [org.httpkit.server :as http-server])
  (:gen-class))

(def ^:const port 8280)

(def ^:private stub-page
  "<!DOCTYPE html>
<html lang=\"en\">
<head><meta charset=\"utf-8\"><title>adsb</title></head>
<body>
  <h1>adsb</h1>
  <p>Backend skeleton is up. Routes land in adsb-kbm.1;
     the app shell lands in adsb-2yu.1.</p>
</body>
</html>")

(defn handle-request
  "Answer every request with the stub page."
  [_request]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    stub-page})

(defn start-server!
  "Start http-kit on the dev port. Returns the stop function."
  []
  (http-server/run-server handle-request {:port port}))

(defn -main [& _args]
  (start-server!)
  (log/info "adsb dev server listening on port" port)
  @(promise))
