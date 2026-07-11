(ns adsb.http.handlers
  "Pure request handlers for the HTTP API. Each is a plain function of a
  Ring request returning a Ring response map — no I/O beyond reading the
  request. Path params arrive already coerced by Malli at the router
  boundary, so handlers trust their input (docs/validation-boundaries.md,
  Boundary 2). They never re-parse and never keywordize user strings.")

(def ^:const feeder-status-unknown "unknown")

(defn health
  "Liveness/readiness for the container healthcheck. 200 with a small
  JSON body. The feeder-status field is stubbed as unknown until real
  feeder reachability lands in adsb-nqf.1."
  [_request]
  {:status 200
   :body   {:status        "ok"
            :feeder-status  feeder-status-unknown}})

(defn aircraft-detail
  "Build the aircraft-detail handler over an injected state-lookup fn.
  The lookup takes a coerced icao string and returns a domain aircraft
  or nil. With the empty-state default the endpoint honestly 404s until
  the live store is injected in adsb-nqf.2."
  [state-lookup]
  (fn [request]
    (let [icao (get-in request [:parameters :path :icao])]
      (if-let [aircraft (state-lookup icao)]
        {:status 200 :body aircraft}
        {:status 404 :body {:error "aircraft not found" :icao icao}}))))
