(ns adsb.ingest.config
  (:require [clojure.string :as str])
  (:import (java.net URI URISyntaxException)))

(def ^:const feeder-url-env "ADSB_ULTRAFEEDER_URL")
(def ^:const source-env "ADSB_SOURCE")
(def ^:const feed-url-env "ADSB_FEED_URL")
(def ^:private allowed-schemes #{"http" "https"})

(defn- normalize-source [source]
  (some-> source str/trim str/lower-case not-empty))

(defn source-kind [source]
  (case (normalize-source source)
    (nil "poll") :poll
    "replay" :replay
    "sbs" :sbs
    "beast" :beast
    (throw (ex-info (str source-env " must be one of poll, sbs, beast,"
                         " replay — got: " (pr-str source))
                    {:type ::invalid-source :env source-env}))))

(defn- fail! [env detail]
  (throw (ex-info (str env " " detail)
                  {:type ::invalid-uri
                   :env  env})))

(defn- parse-uri [env url]
  (try
    (URI. url)
    (catch URISyntaxException _
      (fail! env (str "must be a valid URL, got: " url)))))

(defn validate-feeder-url [url]
  (when (str/blank? url)
    (fail! feeder-url-env "must be set"))
  (let [uri    (parse-uri feeder-url-env url)
        scheme (.getScheme uri)]
    (when-not (allowed-schemes scheme)
      (fail! feeder-url-env (str "must be http or https, got scheme: " (pr-str scheme))))
    (when (str/blank? (.getHost uri))
      (fail! feeder-url-env (str "must include a host, got: " url)))
    (str/replace url #"/+$" "")))

(def ^:const feeder-auth-id-env "ADSB_FEEDER_AUTH_ID")
(def ^:const feeder-auth-secret-env "ADSB_FEEDER_AUTH_SECRET")
(def ^:const cf-access-client-id-header "CF-Access-Client-Id")
(def ^:const cf-access-client-secret-header "CF-Access-Client-Secret")

(defn feeder-auth-headers [env]
  (let [id     (some-> (get env feeder-auth-id-env) str/trim not-empty)
        secret (some-> (get env feeder-auth-secret-env) str/trim not-empty)]
    (cond
      (and id secret) {cf-access-client-id-header     id
                       cf-access-client-secret-header secret}
      (or id secret) (throw (ex-info (str feeder-auth-id-env " and "
                                          feeder-auth-secret-env
                                          " must be set together"
                                          " (found only one)")
                                     {:type ::incomplete-feeder-auth
                                      :env  [feeder-auth-id-env
                                             feeder-auth-secret-env]})))))

(def ^:private feed-schemes #{"tcp" "wss"})
(def ^:private default-wss-port 443)

(defn parse-feed-url [url]
  (when (str/blank? url)
    (fail! feed-url-env "must be set for ADSB_SOURCE=sbs or beast"))
  (let [uri    (parse-uri feed-url-env url)
        scheme (some-> (.getScheme uri) str/lower-case)
        host   (.getHost uri)
        port   (.getPort uri)]
    (when-not (feed-schemes scheme)
      (fail! feed-url-env (str "must be tcp:// or wss://, got scheme: "
                               (pr-str scheme))))
    (when (str/blank? host)
      (fail! feed-url-env (str "must include a host, got: " url)))
    (if (= "tcp" scheme)
      (do (when (neg? port)
            (fail! feed-url-env (str "tcp:// must include a port, got: " url)))
          {:scheme :tcp :host host :port port})
      {:scheme :wss
       :uri    uri
       :host   host
       :port   (if (neg? port) default-wss-port port)})))
