(ns adsb.http.routes
  "The reitit route table and the assembled Ring handler. Malli coerces
  every path parameter and validates every declared response body;
  muuntaja negotiates JSON. Static assets — the compiled frontend — are
  served from resources/public. Coercion failures become a 400 in
  middleware, before any handler runs (docs/validation-boundaries.md,
  Boundary 2). An unhandled handler exception becomes a generic 500 —
  without the middleware below, http-kit writes the exception MESSAGE
  into the response body, and an exception message is an internal
  detail (hostnames, config), not something an anonymous internet
  client gets to read (adsb-kh4.4)."
  (:require [adsb.http.handlers :as handlers]
            [adsb.http.security :as security]
            [adsb.schema :as schema]
            [clojure.tools.logging :as log]
            [muuntaja.core :as muuntaja]
            [reitit.coercion.malli :as coercion-malli]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.exception :as exception]
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

(def ^:private exception-middleware
  "Every unhandled exception: a full log line here, a generic 500 to
  the client. Coercion errors never reach this — the inner
  coerce-exceptions-middleware already turned them into a 400."
  (exception/create-exception-middleware
    {::exception/default
     (fn [exception _request]
       (log/error exception "unhandled exception in HTTP handler")
       {:status 500
        :body   {:error "internal error"}})}))

(defn router
  "The reitit router with Malli coercion and muuntaja content
  negotiation wired in as route data. Middleware order matters:
  format sits outside exception handling so the generic 500 is still
  JSON-encoded; exception handling sits outside coercion so a handler
  blow-up is caught even mid-coercion."
  [dependencies]
  (ring/router
    (routes dependencies)
    {:data {:coercion   coercion-malli/coercion
            :muuntaja   muuntaja/instance
            :middleware [muuntaja-mw/format-middleware
                         exception-middleware
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
    :stream-connect Ring handler opening the SSE  (default: 503)

  The security headers wrap the OUTSIDE of the whole thing
  (adsb.http.security) so they ride the static assets and the 404 too,
  and so they ship no matter what edge is in front — DigitalOcean's
  router in production, or nothing at all in front of a bare `bb dev`.

    :dev-csp?  serve the dev Content-Security-Policy, which the
               shadow-cljs watch build cannot run without
               (adsb.http.security/dev-content-security-policy).
               Default false — production is strict and stays strict.

    :origin-token  the shared secret Cloudflare stamps on requests to
               the origin (adsb.http.security/wrap-origin-lock). Wraps
               OUTSIDE the headers — a request we refuse should cost us
               a comparison, not a route match. nil disables the lock,
               which is right for a laptop and wrong for a deployment."
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
          (ring/create-resource-handler {:path "/"})
          (ring/create-default-handler)))
      dev-csp?)
    origin-token))
