(ns adsb.http.routes
  "The reitit route table and the assembled Ring handler. Malli coerces
  every path parameter and validates every declared response body;
  muuntaja negotiates JSON. Static assets — the compiled frontend — are
  served from resources/public. Coercion failures become a 400 in
  middleware, before any handler runs (docs/validation-boundaries.md,
  Boundary 2)."
  (:require
    [adsb.http.handlers :as handlers]
    [adsb.schema :as schema]
    [muuntaja.core :as muuntaja]
    [reitit.coercion.malli :as coercion-malli]
    [reitit.ring :as ring]
    [reitit.ring.coercion :as coercion]
    [reitit.ring.middleware.muuntaja :as muuntaja-mw]))

(def health-response
  [:map
   [:status :string]
   [:feeder-status :string]])

(defn- routes [state-lookup]
  [["/healthz"
    {:get {:responses {200 {:body health-response}}
           :handler   handlers/health}}]
   ["/api/aircraft/:icao"
    {:get {:parameters {:path [:map [:icao schema/icao-address]]}
           :responses  {200 {:body schema/aircraft}}
           :handler    (handlers/aircraft-detail state-lookup)}}]])

(defn router
  "The reitit router with Malli coercion and muuntaja content
  negotiation wired in as route data."
  [state-lookup]
  (ring/router
    (routes state-lookup)
    {:data {:coercion   coercion-malli/coercion
            :muuntaja   muuntaja/instance
            :middleware [muuntaja-mw/format-middleware
                         coercion/coerce-exceptions-middleware
                         coercion/coerce-request-middleware
                         coercion/coerce-response-middleware]}}))

(defn handler
  "The complete Ring handler: API routes first, then static assets from
  resources/public, then a 404 default. state-lookup is injected into
  aircraft-detail — pass (constantly nil) for empty state."
  [state-lookup]
  (ring/ring-handler
    (router state-lookup)
    (ring/routes
      (ring/create-resource-handler {:path "/"})
      (ring/create-default-handler))))
