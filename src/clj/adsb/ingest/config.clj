(ns adsb.ingest.config
  "Boot-time validation of the feeder URL. ADSB_ULTRAFEEDER_URL decides
  what host the server issues HTTP requests to — an SSRF primitive — so it
  comes from the environment only and is checked before the first poll, not
  on it. Fail loudly at boot. See docs/validation-boundaries.md Boundary 3.

  The validator is pure: it takes the string. Reading the environment stays
  at the caller edge (adsb.main)."
  (:require
    [clojure.string :as str])
  (:import
    (java.net URI URISyntaxException)))

(def ^:const feeder-url-env "ADSB_ULTRAFEEDER_URL")

(def ^:private allowed-schemes #{"http" "https"})

(defn- fail! [detail]
  (throw (ex-info (str feeder-url-env " " detail)
                  {:type ::invalid-feeder-url
                   :env  feeder-url-env})))

(defn- parse-uri [url]
  (try
    (URI. url)
    (catch URISyntaxException _
      (fail! (str "must be a valid URL, got: " url)))))

(defn validate-feeder-url
  "Validate a feeder base URL string: present, parseable as a URL, and
  http/https with a host. Returns the base URL with any trailing slash
  stripped (so the aircraft.json path joins cleanly). Throws ex-info naming
  the env var otherwise."
  [url]
  (when (str/blank? url)
    (fail! "must be set"))
  (let [uri    (parse-uri url)
        scheme (.getScheme uri)]
    (when-not (allowed-schemes scheme)
      (fail! (str "must be http or https, got scheme: " (pr-str scheme))))
    (when (str/blank? (.getHost uri))
      (fail! (str "must include a host, got: " url)))
    (str/replace url #"/+$" "")))

(comment
  (validate-feeder-url "http://dietpi.local:8100")
  (validate-feeder-url "http://dietpi.local:8100/")
  (validate-feeder-url nil)
  (validate-feeder-url "ftp://dietpi.local")
  (validate-feeder-url "not a url"))
