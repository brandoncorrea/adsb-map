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
            [reitit.ring.middleware.muuntaja :as muuntaja-mw]
            [ring.middleware.not-modified :as not-modified]
            [ring.util.response :as response]))

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

;; ---------------------------------------------------------------------
;; Static asset caching (adsb-8cb).

(def ^:const static-cache-control
  "Cache the compiled frontend — but never SERVE a cached copy without
  asking us first.

  This exists because the app previously set no Cache-Control at all,
  and a response that declines to say is not a response nobody caches:
  DigitalOcean's router stamps `Cache-Control: private` on anything that
  arrives without one (measured — even a bare 403 with an empty body
  comes back `private`), and Cloudflare, reading that, refused to cache
  the bundle at all. Every first load pulled the whole ~1.2 MB from this
  container. Saying nothing was a decision; it was just someone else's.

  `no-cache` is NOT `no-store`. Caches may keep the bytes; they must
  revalidate before serving them. Ring already puts a Last-Modified on
  every resource response, so that revalidation is a conditional GET
  that comes back 304 with no body (see wrap-static-caching) — the
  bundle crosses the wire once per change instead of once per load.

  It is `no-cache` and not a long `max-age` because the bundle is NOT
  fingerprinted: it is /js/main.js at every deploy, so a cached copy
  whose max-age has not expired is a cache serving the OLD app with no
  way to be told otherwise. Revalidation buys the caching without the
  staleness. Fingerprint the bundle (adsb-452) and this becomes
  `max-age=31536000, immutable` and the revalidation disappears too.

  Compression is deliberately NOT our business: the edge already
  brotli-compresses this (measured — 1,233,883 bytes become 348 KB), so
  a compression middleware here would buy nothing and would put a
  body-buffering middleware one route away from text/event-stream."
  "public, no-cache")

(defn- cached
  "Add the caching header to a static response. nil passes through
  untouched — a nil response means the resource handler found no such
  file, and the 404 default handler downstream, not this, decides what
  that becomes."
  [resp]
  (when resp
    (response/header resp "Cache-Control" static-cache-control)))

(defn- wrap-static-caching
  "Ring middleware for the STATIC ASSET branch, and nothing else.

  Two things, from the inside out:

    wrap-not-modified  turns a conditional GET whose If-Modified-Since
                       matches the resource's Last-Modified into a 304
                       with no body. Without it the origin re-ships the
                       entire bundle on every revalidation — measured
                       against the deployment, it did exactly that, so
                       even a browser that had the file already paid
                       1.2 MB to be told it was current.
    Cache-Control      makes the response cacheable in the first place.
                       It sits OUTSIDE so the 304 carries it too: a 304
                       that omits Cache-Control leaves a cache to fall
                       back on its own heuristics, which is how we got
                       here.

  That it wraps ONLY the resource handler is the load-bearing part. The
  SSE stream must never meet a middleware that inspects, buffers, or
  conditionally empties a response body (adsb.stream) — an event-stream
  is not a document: it has no Last-Modified, it is never complete, and
  a 304 is a category error against it. Static assets are the only
  responses here that are safe to cache and the only ones that need to
  be, so they are the only ones this touches."
  [handler]
  (let [handler (not-modified/wrap-not-modified handler)]
    (fn
      ([request] (cached (handler request)))
      ([request respond raise]
       (handler request (fn [resp] (respond (cached resp))) raise)))))

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
          (wrap-static-caching (ring/create-resource-handler {:path "/"}))
          (ring/create-default-handler)))
      dev-csp?)
    origin-token))
