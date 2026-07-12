(ns adsb.ingest.config
  "Boot-time validation of the feeder connection. ADSB_ULTRAFEEDER_URL
  decides what host the server issues HTTP requests to — an SSRF primitive —
  so it comes from the environment only and is checked before the first
  poll, not on it. Fail loudly at boot. See docs/validation-boundaries.md
  Boundary 3.

  The cloud backend reaches the HOME feeder over a Cloudflare Tunnel whose
  hostname is fronted by a Cloudflare Access service-token policy (bead
  adsb-kh4.3). The static CF-Access-Client-Id / CF-Access-Client-Secret
  credential the backend presents on every feeder request comes from the
  environment too (ADSB_FEEDER_AUTH_ID / ADSB_FEEDER_AUTH_SECRET) and is
  likewise validated here, at boot — the secret is NEVER logged and never
  rides an exception message.

  These validators are pure: they take the environment map. Reading the real
  environment stays at the caller edge (adsb.main)."
  (:require [clojure.string :as str])
  (:import (java.net URI URISyntaxException)))

(def ^:const feeder-url-env "ADSB_ULTRAFEEDER_URL")

(def ^:const source-env
  "Selects the ingest Source. Unset (or anything but \"replay\") is the
  live ultrafeeder, which requires ADSB_ULTRAFEEDER_URL."
  "ADSB_SOURCE")

(def ^:const replay-source
  "The ADSB_SOURCE value that swaps the live feeder for the recorded
  fixture (adsb.ingest.replay) — bb dev with no feeder reachable, and no
  feeder URL required."
  "replay")

(def ^:private allowed-schemes #{"http" "https"})

(defn replay-source?
  "True when ADSB_SOURCE selects the fixture-replay Source instead of the
  live ultrafeeder. Case- and whitespace-insensitive; nil (the default)
  is false, so the live feeder — and its required, validated URL —
  remains the default."
  [source]
  (= replay-source (some-> source str/trim str/lower-case)))

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

;; ---------------------------------------------------------------------
;; Feeder auth — the Cloudflare Access service token
;;
;; The home feeder is reachable only over a Cloudflare Tunnel whose
;; hostname is gated by an Access "Service Auth" policy. The cloud backend
;; is the only client, and it authenticates by presenting a static service
;; token as two headers on every request (aircraft.json AND receiver.json).
;; Missing the token, the tunnel answers 403 — which the poll loop already
;; treats as a feeder outage (backoff + honest UI status), so the failure
;; mode is safe. A feeder on a trusted LAN needs no token; the headers are
;; then simply absent.

(def ^:const feeder-auth-id-env "ADSB_FEEDER_AUTH_ID")

(def ^:const feeder-auth-secret-env "ADSB_FEEDER_AUTH_SECRET")

(def ^:const cf-access-client-id-header "CF-Access-Client-Id")

(def ^:const cf-access-client-secret-header "CF-Access-Client-Secret")

(defn feeder-auth-headers
  "The static auth headers the backend sends on EVERY feeder request so a
  Cloudflare Access service-token policy lets the poll through — a
  header-name -> value map, or nil when no service token is configured.

  Both ADSB_FEEDER_AUTH_ID and ADSB_FEEDER_AUTH_SECRET must be set
  together: half a credential is a boot-time misconfiguration and fails
  loudly here, before the first poll, exactly like a bad feeder URL. The
  secret is NEVER placed in the exception message or the ex-data — only the
  names of the env vars are, so a boot-failure log can never leak it
  (docs/validation-boundaries.md). Pure given the env map; reading the real
  environment stays at the caller's edge (adsb.main)."
  [env]
  (let [id     (some-> (get env feeder-auth-id-env) str/trim not-empty)
        secret (some-> (get env feeder-auth-secret-env) str/trim not-empty)]
    (cond
      (and id secret) {cf-access-client-id-header     id
                       cf-access-client-secret-header secret}
      (or id secret)  (throw (ex-info (str feeder-auth-id-env " and "
                                           feeder-auth-secret-env
                                           " must be set together"
                                           " (found only one)")
                                      {:type ::incomplete-feeder-auth
                                       :env  [feeder-auth-id-env
                                              feeder-auth-secret-env]}))
      :else           nil)))
