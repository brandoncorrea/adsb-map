(ns adsb.http.routes
  "The reitit route table and the assembled Ring handler. Malli coerces
  every path parameter and validates every declared response body;
  muuntaja negotiates JSON. Static assets — the compiled frontend — are
  served from resources/public. Coercion failures become a 400 in
  middleware, before any handler runs (docs/validation-boundaries.md,
  Boundary 2)."
  (:require [adsb.http.handlers :as handlers]
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

(defn- routes [{:keys [state-lookup feeder-status stream-connect]}]
  [["/healthz"
    {:get {:responses {200 {:body health-response}}
           :handler   (handlers/health feeder-status)}}]
   ;; SSE — the handler switches the request to an http-kit async
   ;; channel and writes its own headers and frames (adsb.stream), so
   ;; no response schema or muuntaja encoding applies past the switch.
   ["/api/stream"
    {:get {:handler stream-connect}}]
   ["/api/aircraft/:icao"
    {:get {:parameters {:path [:map [:icao schema/icao-address]]}
           :responses  {200 {:body schema/aircraft}}
           :handler    (handlers/aircraft-detail state-lookup)}}]])

(defn router
  "The reitit router with Malli coercion and muuntaja content
  negotiation wired in as route data."
  [dependencies]
  (ring/router
    (routes dependencies)
    {:data {:coercion   coercion-malli/coercion
            :muuntaja   muuntaja/instance
            :middleware [muuntaja-mw/format-middleware
                         coercion/coerce-exceptions-middleware
                         coercion/coerce-request-middleware
                         coercion/coerce-response-middleware]}}))

(defn- stream-unavailable
  "The /api/stream handler when no broadcaster is injected (a bare
  handler in tests): an honest 503 instead of a hang."
  [_request]
  {:status 503
   :body   {:error "stream not wired"}})

(defn handler
  "The complete Ring handler: API routes first, then static assets from
  resources/public, then a 404 default. Dependencies are injected —
  each has an honest default so a bare (handler {}) still stands up:

    :state-lookup   icao -> aircraft or nil       (default: empty state)
    :feeder-status  () -> poller status map       (default: unknown)
    :stream-connect Ring handler opening the SSE  (default: 503)"
  [{:keys [state-lookup feeder-status stream-connect]
    :or   {state-lookup   (constantly nil)
           feeder-status  (constantly nil)
           stream-connect stream-unavailable}}]
  (ring/ring-handler
    (router {:state-lookup   state-lookup
             :feeder-status  feeder-status
             :stream-connect stream-connect})
    (ring/routes
      (ring/create-resource-handler {:path "/"})
      (ring/create-default-handler))))
