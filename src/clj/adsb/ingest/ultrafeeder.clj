(ns adsb.ingest.ultrafeeder
  "The ultrafeeder Source: poll <base-url>/data/aircraft.json over HTTP and
  coerce it into domain aircraft. This is the trust boundary for the feeder
  wire format — fetch! hands the raw `aircraft` array to
  adsb.ingest.coerce/->aircraft-batch, so what leaves here is already
  validated (see docs/validation-boundaries.md).

  Stateless per poll: open!/close! are no-ops. A future SBS/Beast Source
  holds a socket instead; this one just issues a GET.

  Payload-level metadata (the top-level cumulative `messages` counter)
  rides a side-channel, not the batch: fetch! stores it in a metadata atom
  the Source exposes through adsb.ingest.source/Metadata, so the per-poll
  message count reaches adsb.stats without churning the transport-agnostic
  poll loop or the domain (adsb-6wd.4)."
  (:require [adsb.ingest.coerce :as coerce]
            [adsb.ingest.source :as source]
            [cheshire.core :as json]
            [org.httpkit.client :as http]))

(def ^:private aircraft-json-path "/data/aircraft.json")
(def ^:const default-timeout-ms 5000)

(defn- parse-payload
  "An aircraft.json body split into the coerced aircraft batch and the
  payload-level metadata we keep (the cumulative `messages` counter).
  cheshire parses to keyword keys, which is the vocabulary
  ->aircraft-batch expects. `messages` is absent on feeders that do not
  report it — then it is nil and the rate is simply unknown."
  [body]
  (let [{:keys [aircraft messages]} (json/parse-string body true)]
    {:batch    (coerce/->aircraft-batch aircraft)
     :metadata {:messages messages}}))

(defn- get-text! [url opts]
  (-> {:url    url
       :method :get
       :as     :text}
      (merge opts)
      http/request
      deref))

(defn- fetch-batch!
  "GET aircraft.json once and return the coerced batch, recording the
  payload's metadata (the cumulative `messages` counter) in `metadata` as
  a side effect, or throw ex-info the poll loop can turn into feeder
  status. A request error (the feeder is down) and a non-200 status are
  both failures — the metadata is left untouched on failure, so a stale
  count is never mistaken for a fresh one.

  `headers` are the static feeder-auth headers (the Cloudflare Access
  service token — adsb.ingest.config/feeder-auth-headers), sent on every
  request so the tunnel's Access policy lets the poll through; nil on a
  trusted LAN feeder. They ride the request opts only — never the ex-info,
  so a failed poll's exception can never leak the secret."
  [base-url timeout-ms headers metadata]
  (let [url (str base-url aircraft-json-path)
        {:keys [status body error]} (get-text! url {:timeout timeout-ms
                                                    :headers headers})]
    (cond
      error
      (throw (ex-info "Feeder request failed"
                      {:type ::request-failed :url url} error))

      (not= 200 status)
      (throw (ex-info "Feeder returned a non-200 status"
                      {:type ::unexpected-status :status status :url url}))

      :else
      (let [{:keys [batch] parsed-metadata :metadata} (parse-payload body)]
        (reset! metadata parsed-metadata)
        batch))))

(defrecord UltrafeederSource [base-url timeout-ms headers metadata]
  source/Source
  (open! [this] this)
  (fetch! [_] (fetch-batch! base-url timeout-ms headers metadata))
  (close! [this] this)
  source/Metadata
  (last-metadata [_] @metadata))

(defn ->source
  "A Source polling `base-url`/data/aircraft.json. `base-url` should be the
  validated feeder URL (adsb.ingest.config/validate-feeder-url). `headers`
  are the optional static feeder-auth headers presented on every request
  (the Cloudflare Access service token —
  adsb.ingest.config/feeder-auth-headers); nil for a trusted LAN feeder.
  The metadata atom starts empty and is filled by the first successful
  fetch!."
  ([base-url] (->source base-url default-timeout-ms nil))
  ([base-url timeout-ms] (->source base-url timeout-ms nil))
  ([base-url timeout-ms headers]
   (->UltrafeederSource base-url timeout-ms headers (atom nil))))

(comment
  (source/fetch! (->source "http://dietpi.local:8100")))
