(ns adsb.stream.admission
  "Client identity and SSE admission: who is connecting, whether the client
  registry has room for them, and the registry mutations that track it. The
  identity question is a genuine trust boundary — the client address is
  untrusted input (validation-boundaries.md, Boundary 2) — so it lives apart
  from the frame, tick, and fan-out machinery in adsb.stream.broadcast. The
  broadcaster map (built by broadcast/start!) carries the shared registry
  state under :stream/* keys; these functions read and mutate it."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.lang.reflect Field)
           (java.net InetSocketAddress)
           (java.nio.channels SelectionKey SocketChannel)
           (org.httpkit.server AsyncChannel)))

(def ^:const retry-after-s 30)
(def ^:const cf-connecting-ip-header "cf-connecting-ip")
(def ^:const diagnose-client-ip-env "ADSB_DIAGNOSE_CLIENT_IP")
(def ^:const diagnose-budget 20)

;; Reflection into http-kit's private channel field is deliberate: http-kit
;; fills :remote-addr from the leftmost (attacker-written) X-Forwarded-For
;; entry, so the real socket peer is the only trustworthy client identity.
;; See validation-boundaries.md, Boundary 2.
(def ^:private async-channel-key-field
  (delay
    (try
      (doto (.getDeclaredField AsyncChannel "key")
        (.setAccessible true))
      (catch Exception _))))

(defn- reflect-socket-peer-ip [request]
  (when-some [^Field field @async-channel-key-field]
    (try
      (when-some [channel (:async-channel request)]
        (let [^SelectionKey key (.get field channel)
              socket-channel    (.channel key)]
          (when (instance? SocketChannel socket-channel)
            (let [address (.getRemoteAddress ^SocketChannel socket-channel)]
              (when (instance? InetSocketAddress address)
                (.getHostAddress
                  (.getAddress ^InetSocketAddress address)))))))
      (catch Exception _))))

(def ^:const ^:private socket-peer-log-interval-ms 60000)
(defonce ^:private last-socket-peer-log-ms (atom 0))

(defn- log-socket-peer-failure! []
  (let [now-ms    (System/currentTimeMillis)
        [old new] (swap-vals! last-socket-peer-log-ms
                              #(if (>= (- now-ms %) socket-peer-log-interval-ms)
                                 now-ms
                                 %))]
    (when (not= old new)
      (log/error "SSE client identity unavailable: the socket peer could not"
                 "be read from http-kit's channel (its internals may have"
                 "changed). Affected connections get NO per-IP identity —"
                 ":remote-addr is attacker-written and is never used as a"
                 "fallback (validation-boundaries.md, Boundary 2). Further"
                 "reports muted for" (quot socket-peer-log-interval-ms 1000)
                 "s."))))

(defn socket-peer-ip
  "The trustworthy client identity — the real TCP peer, read off http-kit's
  socket. Returns nil (and logs an ERROR, rate-limited) when the peer cannot
  be read; it NEVER falls back to ring's :remote-addr, which http-kit fills
  from the attacker-written X-Forwarded-For and is forbidden for limits
  (validation-boundaries.md, Boundary 2)."
  [request]
  (or (reflect-socket-peer-ip request)
      (do (log-socket-peer-failure!)
          nil)))

(defn forwarded-ip
  "The client's entry in a TRUSTED X-Forwarded-For. Each proxy appends the
  peer it saw, so the header reads left-to-right from the attacker-written
  claim to the trustworthy last hop, and the client sits `hops` from the
  right. A header with fewer entries than `hops` is a misconfiguration whose
  index would reach past the left end into attacker-written territory — so we
  refuse to guess and return nil, and client-ip falls to the socket peer
  rather than to a spoofable claim (validation-boundaries.md, Boundary 2)."
  [hops header]
  (when-some [entries (some->> (some-> header (str/split #","))
                               (map str/trim)
                               (remove str/blank?)
                               seq
                               vec)]
    (let [index (- (count entries) hops)]
      (when (>= index 0)
        (nth entries index)))))

(defn client-ip
  "Resolve the identity the per-IP cap keys on. With trust enabled, prefer
  Cloudflare's CF-Connecting-IP, else the trusted X-Forwarded-For hop; in
  every other case — and whenever those yield nothing — the socket peer."
  [trust-forwarded? hops request]
  (or (when trust-forwarded?
        (or (some-> (get-in request [:headers cf-connecting-ip-header])
                    str/trim
                    not-empty)
            (forwarded-ip hops (get-in request [:headers "x-forwarded-for"]))))
      (socket-peer-ip request)))

(defn deny-reason
  "Why this ip may not be admitted right now — :server-full at the total cap,
  :ip-full at the per-IP cap — or nil when there is room."
  [clients ip {:keys [max-clients max-per-ip]}]
  (cond
    (>= (count clients) max-clients)
    :server-full

    (>= (count (filter #(= ip (:client/ip %)) (vals clients))) max-per-ip)
    :ip-full))

(defn try-register!
  "Atomically claim a registry slot for ip, unless a limit forbids it.
  Returns nil on success, else the deny-reason that blocked the slot."
  [{:stream/keys [clients limits]} channel ip]
  (let [[old new] (swap-vals! clients
                              (fn [registry]
                                (if (deny-reason registry ip limits)
                                  registry
                                  (assoc registry channel
                                                  {:client/ip     ip
                                                   :client/ready? false}))))]
    (when-not (contains? new channel)
      (deny-reason old ip limits))))

(defn mark-ready! [{:stream/keys [clients]} channel]
  (swap! clients
         (fn [registry]
           (cond-> registry
                   (contains? registry channel)
                   (assoc-in [channel :client/ready?] true)))))

(defn unregister! [{:stream/keys [clients]} channel]
  (swap! clients dissoc channel))

(def ^:const ^:private reject-log-interval-ms 10000)

(defn log-rejection! [{:stream/keys [last-reject-log-ms]} reason ip]
  (let [now-ms    (System/currentTimeMillis)
        [old new] (swap-vals! last-reject-log-ms
                              #(if (>= (- now-ms %) reject-log-interval-ms)
                                 now-ms
                                 %))]
    (when (not= old new)
      (log/warn "SSE connect rejected" reason "for" ip
                "(further rejections muted for"
                (quot reject-log-interval-ms 1000) "s)"))))

(defn rejection-response [reason]
  {:status  503
   :headers {"Content-Type" "application/json"
             "Retry-After"  (str retry-after-s)}
   :body    (json/generate-string
              {:error  "stream at capacity"
               :reason (name reason)})})

(defn diagnose-client-ip! [{:stream/keys [diagnose-remaining]} request resolved-ip]
  (when diagnose-remaining
    (let [[old _] (swap-vals! diagnose-remaining #(cond-> % (pos? %) dec))]
      (when (pos? old)
        (log/info "SSE-CLIENT-IP-DIAG"
                  {:cf-connecting-ip (get-in request [:headers
                                                      cf-connecting-ip-header])
                   :x-forwarded-for  (get-in request [:headers "x-forwarded-for"])
                   :socket-peer      (socket-peer-ip request)
                   :resolved-key     resolved-ip
                   :remaining        (dec old)})))))
