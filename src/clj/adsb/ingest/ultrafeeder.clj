(ns adsb.ingest.ultrafeeder
  (:require [adsb.ingest.coerce :as coerce]
            [adsb.ingest.source :as source]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as http]))

(def ^:private aircraft-json-path "/data/aircraft.json")
(def ^:const default-timeout-ms 5000)

(defn- coerce-batch! [raw-entries]
  (let [{:keys [aircraft rejections]} (coerce/->aircraft-batch raw-entries)]
    (doseq [rejection rejections]
      (log/warn "Rejected aircraft" rejection))
    aircraft))

(defn- parse-payload! [body]
  (let [{:keys [aircraft messages]} (json/parse-string body true)]
    {:batch    (coerce-batch! aircraft)
     :metadata {:messages messages}}))

(defn- get-text! [url opts]
  (-> {:url    url
       :method :get
       :as     :text}
      (merge opts)
      http/request
      deref))

(defn- fetch-batch! [base-url timeout-ms headers metadata]
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
      (let [{:keys [batch] parsed-metadata :metadata} (parse-payload! body)]
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
  ([base-url] (->source base-url default-timeout-ms nil))
  ([base-url timeout-ms] (->source base-url timeout-ms nil))
  ([base-url timeout-ms headers]
   (->UltrafeederSource base-url timeout-ms headers (atom nil))))
