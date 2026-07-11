(ns adsb.http.routes
  "The reitit route table and the assembled Ring handler. Malli coerces
  every path parameter and validates every declared response body;
  muuntaja negotiates JSON. Static assets — the compiled frontend — are
  served from resources/public. Coercion failures become a 400 in
  middleware, before any handler runs (docs/validation-boundaries.md,
  Boundary 2)."
  (:require
    [adsb.http.handlers :as handlers]
    [muuntaja.core :as muuntaja]
    [reitit.coercion.malli :as coercion-malli]
    [reitit.ring :as ring]
    [reitit.ring.coercion :as coercion]
    [reitit.ring.middleware.muuntaja :as muuntaja-mw]))

;; TODO(adsb-bvi.3): consolidate with src/cljc/adsb/schema.cljc once its
;; icao-address schema lands. Defined here for now so this bead does not
;; block on that one; the two must not drift.
(def icao-address
  "An ICAO 24-bit address as it appears in a URL: six hex digits,
  optionally prefixed with ~ for non-ICAO (TIS-B / ADS-R) targets.
  Stays a string — never keywordized."
  [:re {:error/message "icao must be 6 hex digits, optionally ~-prefixed"}
   #"(?i)~?[0-9a-f]{6}"])

;; TODO(adsb-bvi.3): replace with the real aircraft schema from
;; src/cljc/adsb/schema.cljc. Minimal placeholder so responses are
;; schema-checked today; the live store (adsb-nqf.2) will return maps
;; that satisfy the full schema.
(def aircraft-response
  "The 200 body for aircraft-detail: an open domain-aircraft map."
  [:map [:aircraft/icao :string]])

(def health-response
  [:map
   [:status :string]
   [:feeder-status :string]])

(defn- routes [state-lookup]
  [["/healthz"
    {:get {:responses {200 {:body health-response}}
           :handler   handlers/health}}]
   ["/api/aircraft/:icao"
    {:get {:parameters {:path [:map [:icao icao-address]]}
           :responses  {200 {:body aircraft-response}}
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
