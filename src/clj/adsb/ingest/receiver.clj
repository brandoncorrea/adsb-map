(ns adsb.ingest.receiver
  (:require [adsb.schema :as schema]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [org.httpkit.client :as http]))

(def ^:const receiver-lat-env "ADSB_RECEIVER_LAT")
(def ^:const receiver-lon-env "ADSB_RECEIVER_LON")
(def ^:private receiver-json-path "/data/receiver.json")
(def ^:const default-timeout-ms 5000)
(def ^:private valid-position? (m/validator schema/position))

(defn- ->position [lat lon]
  (let [position {:geo/lat lat :geo/lon lon}]
    (when (valid-position? position)
      position)))

(defn- parse-coordinate [s]
  (when-not (str/blank? s)
    (parse-double s)))

(defn env-position [env]
  (->position (parse-coordinate (get env receiver-lat-env))
              (parse-coordinate (get env receiver-lon-env))))

(defn- parse-receiver [body]
  (try
    (json/parse-string body true)
    (catch Exception _)))

(defn fetch-position!
  ([base-url] (fetch-position! base-url default-timeout-ms nil))
  ([base-url timeout-ms] (fetch-position! base-url timeout-ms nil))
  ([base-url timeout-ms headers]
   (let [url (str base-url receiver-json-path)
         {:keys [status body error]} @(http/request {:url     url
                                                     :method  :get
                                                     :timeout timeout-ms
                                                     :headers headers
                                                     :as      :text})]
     (when (and (nil? error) (= 200 status))
       (let [{:keys [lat lon]} (parse-receiver body)]
         (->position lat lon))))))

(defn resolve-position!
  [{:keys [env base-url timeout-ms headers]
    :or   {timeout-ms default-timeout-ms}}]
  (if-let [position (or (env-position env)
                        (when base-url
                          (fetch-position! base-url timeout-ms headers)))]
    (do (log/info "Receiver position resolved; range gate enabled")
        position)
    (do (log/warn (str "No receiver position (" receiver-lat-env "/"
                       receiver-lon-env
                       " unset, receiver.json unavailable);"
                       " range gate disabled"))
        nil)))
