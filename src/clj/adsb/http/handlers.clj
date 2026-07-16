(ns adsb.http.handlers)

(def ^:const feeder-status-unknown "unknown")

(defn- feeder-status-name [status]
  (if-some [feeder-status (:feeder/status status)]
    (name feeder-status)
    feeder-status-unknown))

(defn health [feeder-status]
  (fn [_request]
    {:status 200
     :body   {:status        "ok"
              :feeder-status (feeder-status-name (feeder-status))}}))

(defn aircraft-detail [state-lookup]
  (fn [request]
    (let [icao (get-in request [:parameters :path :icao])]
      (if-let [aircraft (state-lookup icao)]
        {:status 200
         :body   aircraft}
        {:status 404
         :body   {:error "aircraft not found"
                  :icao  icao}}))))
