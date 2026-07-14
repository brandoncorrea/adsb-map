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
  "Selects the ingest Source: \"poll\" (default, also the unset value) polls
  ultrafeeder's aircraft.json and requires ADSB_ULTRAFEEDER_URL; \"sbs\" and
  \"beast\" stream the tunnel-exposed TCP feeds and require ADSB_FEED_URL;
  \"replay\" serves the recorded fixture and requires nothing."
  "ADSB_SOURCE")

(def ^:const feed-url-env
  "The streaming feed endpoint for ADSB_SOURCE=sbs / beast, as a URL:
  tcp://host:port for the plain-socket transport on a dev/LAN feeder, or
  wss://host for the websocket transport through the Cloudflare Tunnel
  (wss then presents the CF-Access service token from
  ADSB_FEEDER_AUTH_ID/SECRET). Required for sbs/beast, unused otherwise."
  "ADSB_FEED_URL")

(def ^:const replay-source
  "The ADSB_SOURCE value that swaps the live feeder for the recorded
  fixture (adsb.ingest.replay) — bb dev with no feeder reachable, and no
  feeder URL required."
  "replay")

(def ^:private allowed-schemes #{"http" "https"})

(defn- normalize-source [source]
  (some-> source str/trim str/lower-case not-empty))

(defn replay-source?
  "True when ADSB_SOURCE selects the fixture-replay Source instead of the
  live ultrafeeder. Case- and whitespace-insensitive; nil (the default)
  is false, so the live feeder — and its required, validated URL —
  remains the default."
  [source]
  (= replay-source (normalize-source source)))

(defn source-kind
  "Classify ADSB_SOURCE into the Source the boot path builds: :poll (the
  default — unset or \"poll\"), :replay, :sbs, or :beast. Case- and
  whitespace-insensitive. An unrecognized value is a boot-time
  misconfiguration and fails loudly, exactly like a bad feeder URL."
  [source]
  (case (normalize-source source)
    (nil "poll") :poll
    "replay"     :replay
    "sbs"        :sbs
    "beast"      :beast
    (throw (ex-info (str source-env " must be one of poll, sbs, beast,"
                         " replay — got: " (pr-str source))
                    {:type ::invalid-source :env source-env}))))

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

;; ---------------------------------------------------------------------
;; The streaming feed endpoint — ADSB_FEED_URL for ADSB_SOURCE=sbs / beast.
;;
;; Two transports, chosen by scheme (adsb-elf): tcp://host:port dials a
;; plain socket (adsb.ingest.tcp/socket-transport) for a dev/LAN feeder;
;; wss://host dials the websocket transport (adsb.ingest.wss) through the
;; Cloudflare Tunnel, presenting the same CF-Access service token the HTTP
;; poller already holds. Parsed defensively and validated at boot — a
;; missing, malformed, or wrong-scheme URL fails loudly before the first
;; dial, exactly like a bad feeder URL (Boundary 3).

(def ^:private feed-schemes #{"tcp" "wss"})

(def ^:private default-wss-port 443)

(defn- fail-feed! [detail]
  (throw (ex-info (str feed-url-env " " detail)
                  {:type ::invalid-feed-url
                   :env  feed-url-env})))

(defn- parse-feed-uri [url]
  (try
    (URI. url)
    (catch URISyntaxException _
      (fail-feed! (str "must be a valid URL, got: " url)))))

(defn parse-feed-url
  "Parse ADSB_FEED_URL into a transport descriptor for a streaming Source.
  tcp://host:port yields {:scheme :tcp :host h :port p} (plain socket);
  wss://host[:port] yields {:scheme :wss :uri <URI> :host h :port p}
  (websocket — the caller adds the CF-Access headers). A blank, unparseable,
  wrong-scheme, hostless, or portless-tcp URL fails loudly, naming the env
  var."
  [url]
  (when (str/blank? url)
    (fail-feed! "must be set for ADSB_SOURCE=sbs or beast"))
  (let [uri    (parse-feed-uri url)
        scheme (some-> (.getScheme uri) str/lower-case)
        host   (.getHost uri)
        port   (.getPort uri)]
    (when-not (feed-schemes scheme)
      (fail-feed! (str "must be tcp:// or wss://, got scheme: "
                       (pr-str scheme))))
    (when (str/blank? host)
      (fail-feed! (str "must include a host, got: " url)))
    (if (= "tcp" scheme)
      (do (when (neg? port)
            (fail-feed! (str "tcp:// must include a port, got: " url)))
          {:scheme :tcp :host host :port port})
      {:scheme :wss :uri uri :host host
       :port  (if (neg? port) default-wss-port port)})))
