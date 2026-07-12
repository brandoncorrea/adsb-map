(ns adsb.ingest.ultrafeeder
  "The ultrafeeder Source: poll <base-url>/data/aircraft.json over HTTP and
  coerce it into domain aircraft. This is the trust boundary for the feeder
  wire format — fetch! hands the raw `aircraft` array to
  adsb.ingest.coerce/->aircraft-batch, so what leaves here is already
  validated (see docs/validation-boundaries.md).

  Stateless per poll: open!/close! are no-ops. A future SBS/Beast Source
  holds a socket instead; this one just issues a GET."
  (:require
    [adsb.ingest.coerce :as coerce]
    [adsb.ingest.source :as source]
    [cheshire.core :as json]
    [org.httpkit.client :as http]))

(def ^:private aircraft-json-path "/data/aircraft.json")

(def ^:const default-timeout-ms 5000)

(defn- parse-batch
  "The `aircraft` array of an aircraft.json body, coerced to domain
  aircraft. cheshire parses to keyword keys, which is the vocabulary
  ->aircraft-batch expects."
  [body]
  (-> body
      (json/parse-string true)
      :aircraft
      coerce/->aircraft-batch))

(defn- fetch-batch!
  "GET aircraft.json once and return the coerced batch, or throw ex-info
  the poll loop can turn into feeder status. A request error (the feeder is
  down) and a non-200 status are both failures."
  [base-url timeout-ms]
  (let [url (str base-url aircraft-json-path)
        {:keys [status body error]} @(http/request {:url     url
                                                     :method  :get
                                                     :timeout timeout-ms
                                                     :as      :text})]
    (cond
      error
      (throw (ex-info "Feeder request failed"
                      {:type ::request-failed :url url} error))

      (not= 200 status)
      (throw (ex-info "Feeder returned a non-200 status"
                      {:type ::unexpected-status :status status :url url}))

      :else
      (parse-batch body))))

(defrecord UltrafeederSource [base-url timeout-ms]
  source/Source
  (open! [this] this)
  (fetch! [_] (fetch-batch! base-url timeout-ms))
  (close! [this] this))

(defn ->source
  "A Source polling `base-url`/data/aircraft.json. `base-url` should be the
  validated feeder URL (adsb.ingest.config/validate-feeder-url)."
  ([base-url] (->source base-url default-timeout-ms))
  ([base-url timeout-ms]
   (->UltrafeederSource base-url timeout-ms)))

(comment
  (source/fetch! (->source "http://dietpi.local:8100")))
