(ns adsb.http.handlers
  "Request handlers for the HTTP API. Each public fn builds a plain Ring
  handler over its injected dependencies — no ambient state, so tests
  inject fakes and production injects the live system (adsb.main). Path
  params arrive already coerced by Malli at the router boundary, so
  handlers trust their input (docs/validation-boundaries.md,
  Boundary 2). They never re-parse and never keywordize user strings.")

(def ^:const feeder-status-unknown "unknown")

(defn- feeder-status-name
  "The wire name of a poller status map's :feeder/status — \"starting\",
  \"ok\", \"down\" — or unknown when no poller is wired."
  [status]
  (if-some [feeder-status (:feeder/status status)]
    (name feeder-status)
    feeder-status-unknown))

(defn health
  "Build the /healthz handler over feeder-status — a fn returning the
  live poller's status map (adsb.ingest.poll/status), or nil when no
  poller is wired. Always 200: liveness is about THIS process; the
  feeder's health is reported in the body, never conflated with it."
  [feeder-status]
  (fn [_request]
    {:status 200
     :body   {:status        "ok"
              :feeder-status (feeder-status-name (feeder-status))}}))

(defn aircraft-detail
  "Build the aircraft-detail handler over an injected state-lookup fn.
  The lookup takes a coerced icao string and returns a domain aircraft
  or nil. With the empty-state default the endpoint honestly 404s until
  the live store is injected."
  [state-lookup]
  (fn [request]
    (let [icao (get-in request [:parameters :path :icao])]
      (if-let [aircraft (state-lookup icao)]
        {:status 200
         :body   aircraft}
        {:status 404
         :body   {:error "aircraft not found"
                  :icao  icao}}))))
