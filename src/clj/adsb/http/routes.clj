(ns adsb.http.routes
  (:require [adsb.http.assets :as assets]
            [adsb.http.handlers :as handlers]
            [adsb.http.security :as security]
            [adsb.schema :as schema]
            [clojure.tools.logging :as log]
            [muuntaja.core :as muuntaja]
            [reitit.coercion.malli :as coercion-malli]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja-mw]
            [ring.middleware.not-modified :as not-modified]))

(def health-response
  [:map
   [:status :string]
   [:feeder-status :string]])

(defn- routes [{:keys [state-lookup feeder-status stream-connect]}]
  [["/healthz"
    {:get {:responses {200 {:body health-response}}
           :handler   (handlers/health feeder-status)}}]
   ["/api/stream"
    {:get {:handler stream-connect}}]
   ["/api/aircraft/:icao"
    {:get {:parameters {:path [:map [:icao schema/icao-address]]}
           :responses  {200 {:body schema/aircraft}}
           :handler    (handlers/aircraft-detail state-lookup)}}]])

(def ^:private exception-middleware
  (exception/create-exception-middleware
    {::exception/default
     (fn [exception _request]
       (log/error exception "unhandled exception in HTTP handler")
       {:status 500
        :body   {:error "internal error"}})}))

(defn router [dependencies]
  (ring/router
    (routes dependencies)
    {:data {:coercion   coercion-malli/coercion
            :muuntaja   muuntaja/instance
            :middleware [muuntaja-mw/format-middleware
                         exception-middleware
                         coercion/coerce-exceptions-middleware
                         coercion/coerce-request-middleware
                         coercion/coerce-response-middleware]}}))

(defn- stream-unavailable [_request]
  {:status 503
   :body   {:error "stream not wired"}})

(defn- static-handler []
  (-> {:path "/"}
      ring/create-resource-handler
      not-modified/wrap-not-modified
      assets/handler))

(defn handler
  [{:keys [state-lookup feeder-status stream-connect dev-csp? origin-token]
    :or   {state-lookup   (constantly nil)
           feeder-status  (constantly nil)
           stream-connect stream-unavailable
           dev-csp?       false}}]
  (security/wrap-origin-lock
    (security/wrap-security-headers
      (ring/ring-handler
        (router {:state-lookup   state-lookup
                 :feeder-status  feeder-status
                 :stream-connect stream-connect})
        (ring/routes
          (static-handler)
          (ring/create-default-handler)))
      dev-csp?)
    origin-token))
