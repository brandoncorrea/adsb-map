(ns adsb.stream.broadcast-test
  "SSE over a real HTTP connection to an ephemeral-port server. The
  picture is the fixture cast — never a live feeder. The JDK HttpClient
  reads the chunked stream as it arrives; http-kit's own client buffers
  whole responses and cannot."
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.fixtures :as fixtures]
    [adsb.http.server :as server]
    [adsb.stream.broadcast :as broadcast]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
    [org.httpkit.server :as http-kit])
  (:import
    (java.io BufferedReader)
    (java.net Socket URI)
    (java.net.http HttpClient HttpRequest HttpRequest$Builder HttpResponse
                   HttpResponse$BodyHandlers)))

(def ^:private captured-at-ms 1720713600000)

(def ^:private cast-picture
  (aircraft/merge-batch {} fixtures/all captured-at-ms))

(def ^:const ^:private frame-timeout-ms 2000)

(def ^:const ^:private await-timeout-ms 2000)

;; ---------------------------------------------------------------------
;; Harness: a streaming server, a live client, frames off the wire

(defn- start-streaming-server!
  "A broadcaster plus the real http server on an ephemeral port.
  Updates every 50 ms by default so tests read frames fast; heartbeats
  effectively off unless a test turns them up. Limits are pinned
  explicitly so a stray ADSB_SSE_* in the environment cannot bend a
  test."
  [{:keys [picture stats feeder interval-ms heartbeat-ms
           max-clients max-per-ip trust-forwarded? trusted-proxy-hops]
    :or   {picture            (constantly cast-picture)
           stats              (constantly nil)
           feeder             (constantly nil)
           interval-ms        50
           heartbeat-ms       60000
           max-clients        100
           max-per-ip         100
           trust-forwarded?   false
           trusted-proxy-hops 1}}]
  (let [broadcaster (broadcast/start!
                      {:picture            picture
                       :stats              stats
                       :feeder             feeder
                       :interval-ms        interval-ms
                       :heartbeat-ms       heartbeat-ms
                       :max-clients        max-clients
                       :max-per-ip         max-per-ip
                       :trust-forwarded?   trust-forwarded?
                       :trusted-proxy-hops trusted-proxy-hops})
        srv         (server/start!
                      {:port           0
                       :stream-connect #(broadcast/connect! broadcaster %)})]
    {:broadcaster broadcaster
     :port        (http-kit/server-port srv)}))

(defn- stop-streaming-server! [{:keys [broadcaster]}]
  (server/stop!)
  (broadcast/stop! broadcaster))

(defn- stream-response!
  "GET /api/stream (optionally with extra headers) and return the raw
  HttpResponse — status and headers inspectable, body an InputStream."
  ^HttpResponse [port headers]
  (let [request (reduce-kv (fn [builder header-name header-value]
                             (.header ^HttpRequest$Builder builder
                                      header-name header-value))
                           (-> (HttpRequest/newBuilder
                                 (URI. (str "http://localhost:" port
                                            "/api/stream")))
                               (.GET))
                           headers)]
    (.send (HttpClient/newHttpClient) (.build ^HttpRequest$Builder request)
           (HttpResponse$BodyHandlers/ofInputStream))))

(defn- open-stream!
  "GET /api/stream and return a line reader over the live body."
  ^BufferedReader [port]
  (io/reader (.body (stream-response! port {}))))

(defn- retry-after [^HttpResponse response]
  (.orElse (.firstValue (.headers response) "Retry-After") nil))

(defn- read-frame!
  "One SSE frame — the lines up to a blank line — or ::timeout. The
  read runs on a future so a stalled stream fails the test instead of
  hanging it."
  [^BufferedReader reader]
  (deref (future
           (loop [lines []]
             (let [line (.readLine reader)]
               (cond
                 (nil? line)       lines
                 (str/blank? line) (if (seq lines) lines (recur lines))
                 :else             (recur (conj lines line))))))
         frame-timeout-ms
         ::timeout))

(defn- frame-event [frame]
  (some #(second (re-find #"^event: (.+)$" %)) frame))

(defn- frame-id [frame]
  (some #(some-> (re-find #"^id: (\d+)$" %) second parse-long) frame))

(defn- frame-json [frame]
  (str/join (keep #(second (re-find #"^data: (.+)$" %)) frame)))

(defn- frame-data [frame]
  (json/parse-string (frame-json frame) true))

(defn- eventually
  "Poll thunk until it returns truthy or the deadline passes; the last
  value either way."
  [thunk]
  (let [deadline (+ (System/currentTimeMillis) await-timeout-ms)]
    (loop []
      (let [value (thunk)]
        (if (or value (>= (System/currentTimeMillis) deadline))
          value
          (do (Thread/sleep 10)
              (recur)))))))

;; ---------------------------------------------------------------------
;; The stream's contract

(deftest snapshot-then-updates
  (testing "a new client receives one full snapshot, then ~per-tick
            updates, with ids increasing across the stream"
    (let [streaming (start-streaming-server! {})
          reader    (open-stream! (:port streaming))]
      (try
        (let [snapshot     (read-frame! reader)
              update-frame (read-frame! reader)]
          (is (= "snapshot" (frame-event snapshot)))
          (let [{:keys [at aircraft]} (frame-data snapshot)]
            (is (number? at))
            (is (= (count cast-picture) (count aircraft)))
            (is (contains? (set (map :icao aircraft)) "abc0e4"))
            (is (every? :seen-at aircraft)
                "the aging timestamp rides on every wire aircraft"))
          (is (= "update" (frame-event update-frame)))
          (is (= (count cast-picture)
                 (count (:aircraft (frame-data update-frame)))))
          (is (< (frame-id snapshot) (frame-id update-frame))))
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))

(deftest stats-ride-the-envelope
  (testing "the session stats the broadcaster computes on the tick reach
            both the snapshot and the update frame as the wire scalars"
    (let [streaming (start-streaming-server!
                      {:stats (constantly {:stats/max-range-km 312
                                           :stats/message-rate 148
                                           ;; counts must NOT reach the wire
                                           :stats/aircraft-count 5})})
          reader    (open-stream! (:port streaming))]
      (try
        ;; The snapshot serves the cached stats from the first tick, so
        ;; skip to an update frame to see a freshly computed one.
        (read-frame! reader)
        (let [{:keys [stats]} (frame-data (read-frame! reader))]
          (is (= {:max-range-km 312 :message-rate 148} stats)
              "only the two scalars, never the counts"))
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))

(deftest feeder-status-rides-the-envelope
  (testing "the feeder status the broadcaster reads reaches both the snapshot
            and the update frame as the wire status + timestamp, and the
            internal error detail never leaves"
    (let [streaming (start-streaming-server!
                      {:feeder (constantly
                                 {:feeder/status          :down
                                  :feeder/last-success-ms 1720713599000
                                  :feeder/last-error
                                  "connect timed out: dietpi.local:8100"})})
          reader    (open-stream! (:port streaming))]
      (try
        (let [snapshot (read-frame! reader)
              update-f (read-frame! reader)]
          (doseq [frame [snapshot update-f]]
            (is (= {:status "down" :last-success 1720713599000}
                   (:feeder (frame-data frame)))
                "status and timestamp, nothing more")
            (is (not (str/includes? (frame-json frame) "dietpi.local"))
                "the error string — a leak risk — never reaches the wire")))
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))

(deftest heartbeat
  (testing "a heartbeat comment appears on the stream at the configured
            interval, keeping buffering proxies awake"
    (let [streaming (start-streaming-server! {:interval-ms  60000
                                              :heartbeat-ms 100})
          reader    (open-stream! (:port streaming))]
      (try
        ;; The snapshot arrives on connect; with updates effectively
        ;; off, the next frame on the wire must be the heartbeat.
        (read-frame! reader)
        (is (= [": hb"] (read-frame! reader)))
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))

(deftest disconnect-cleanup
  (testing "closing the connection removes the client from the registry
            without leaking channels"
    (let [streaming (start-streaming-server! {})
          socket    (Socket. "localhost" (int (:port streaming)))
          connected #(broadcast/client-count (:broadcaster streaming))]
      (try
        (let [writer (io/writer (.getOutputStream socket))]
          (.write writer (str "GET /api/stream HTTP/1.1\r\n"
                              "Host: localhost\r\n"
                              "Accept: text/event-stream\r\n\r\n"))
          (.flush writer))
        (is (true? (eventually #(= 1 (connected))))
            "the client registers on connect")
        (.close socket)
        (is (true? (eventually #(zero? (connected))))
            "the client is dropped once the socket is gone")
        (finally
          (stop-streaming-server! streaming))))))

;; ---------------------------------------------------------------------
;; Connection limits (adsb-kh4.4) — the stream is anonymous and
;; internet-facing, so admission is bounded in the registry itself.

(defn- open-admitted!
  "Open a stream and consume its snapshot, so the client is admitted,
  ready, and receiving ticks. Returns the reader; the caller closes."
  ^BufferedReader [port]
  (doto (open-stream! port)
    (read-frame!)))

(deftest total-connection-cap
  (testing "at the concurrent-client cap a new connect is refused with
            503 + Retry-After, while admitted clients keep streaming"
    (let [streaming (start-streaming-server! {:max-clients 2})
          port      (:port streaming)
          admitted  [(open-admitted! port) (open-admitted! port)]]
      (try
        (let [refused (stream-response! port {})]
          (is (= 503 (.statusCode refused)))
          (is (some? (retry-after refused))
              "a polite client is told when to come back")
          (is (str/includes? (slurp (.body refused)) "server-full")))
        (is (= "update" (frame-event (read-frame! (first admitted))))
            "a rejection costs the admitted clients nothing")
        (finally
          (doseq [^BufferedReader reader admitted] (.close reader))
          (stop-streaming-server! streaming))))))

(deftest per-ip-connection-cap
  (testing "one IP's concurrent connections are capped even when the
            server as a whole has room"
    (let [streaming (start-streaming-server! {:max-clients 100
                                              :max-per-ip  1})
          port      (:port streaming)
          admitted  (open-admitted! port)]
      (try
        (let [refused (stream-response! port {})]
          (is (= 503 (.statusCode refused)))
          (is (str/includes? (slurp (.body refused)) "ip-full")))
        (finally
          (.close admitted)
          (stop-streaming-server! streaming))))))

(deftest disconnect-frees-the-slot
  (testing "a client's disconnect releases its slot; the next connect
            at what was a full house is admitted"
    (let [streaming (start-streaming-server! {:max-clients 1})
          port      (:port streaming)
          connected #(broadcast/client-count (:broadcaster streaming))
          socket    (Socket. "localhost" (int port))]
      (try
        (let [writer (io/writer (.getOutputStream socket))]
          (.write writer (str "GET /api/stream HTTP/1.1\r\n"
                              "Host: localhost\r\n"
                              "Accept: text/event-stream\r\n\r\n"))
          (.flush writer))
        (is (true? (eventually #(= 1 (connected)))))
        (is (= 503 (.statusCode (stream-response! port {})))
            "the house is full while the first client holds the slot")
        (.close socket)
        (is (true? (eventually #(zero? (connected))))
            "the disconnect frees the slot")
        (let [reader (open-admitted! port)]
          (is (= 1 (connected))
              "the next client takes the freed slot with a full snapshot")
          (.close reader))
        (finally
          (stop-streaming-server! streaming))))))

(deftest forwarded-ip-picks-the-entry-the-proxy-chain-vouches-for
  (testing "one trusted hop — the proxy appended the peer it saw, so the
            rightmost entry is the client and anything left of it is the
            client's own writing"
    (is (= "1.2.3.4" (broadcast/forwarded-ip 1 "1.2.3.4")))
    (is (= "1.2.3.4" (broadcast/forwarded-ip 1 "9.9.9.9, 1.2.3.4"))))

  (testing "two trusted hops — the rightmost entry is the inner proxy;
            the client is one further left"
    (is (= "1.2.3.4" (broadcast/forwarded-ip 2 "1.2.3.4, 10.0.0.7")))
    (is (= "1.2.3.4"
           (broadcast/forwarded-ip 2 "9.9.9.9, 1.2.3.4, 10.0.0.7"))))

  (testing "a header shorter than the configured chain means the hop
            count is misconfigured: clamp to the leftmost entry, which
            weakens the per-IP cap but keeps distinct clients distinct.
            Reaching past the left end instead would collapse every
            visitor into one bucket — an outage, which is worse"
    (is (= "1.2.3.4" (broadcast/forwarded-ip 5 "1.2.3.4, 10.0.0.7"))))

  (testing "no header, or nothing in it, is not an address"
    (is (nil? (broadcast/forwarded-ip 1 nil)))
    (is (nil? (broadcast/forwarded-ip 1 "")))
    (is (nil? (broadcast/forwarded-ip 1 " , , ")))
    (is (= "1.2.3.4" (broadcast/forwarded-ip 1 "  ,  , 1.2.3.4 ,"))
        "empty entries are noise, not hops"))

  (testing "an IPv6 client survives the split (no colon confusion)"
    (is (= "2001:db8::1" (broadcast/forwarded-ip 1 "2001:db8::1")))))

(deftest forwarded-for-trust-model
  (testing "behind the trusted proxy, the rightmost X-Forwarded-For
            entry — the one the proxy appended — is the client, so
            distinct clients get distinct per-IP budgets and earlier
            attacker-written entries are ignored"
    (let [streaming (start-streaming-server! {:max-per-ip       1
                                              :trust-forwarded? true})
          port      (:port streaming)
          one       (stream-response! port {"X-Forwarded-For" "203.0.113.7"})
          two       (stream-response! port {"X-Forwarded-For" "203.0.113.8"})]
      (try
        (is (= 200 (.statusCode one)))
        (is (= 200 (.statusCode two))
            "a different forwarded client has its own budget")
        (let [refused (stream-response!
                        port
                        ;; leftmost forged, rightmost proxy-appended:
                        ;; must be counted as .8, which is already full
                        {"X-Forwarded-For" "198.51.100.1, 203.0.113.8"})]
          (is (= 503 (.statusCode refused))))
        (finally
          (.close (.body one))
          (.close (.body two))
          (stop-streaming-server! streaming)))))

  (testing "two trusted hops: the rightmost entry is the INNER PROXY, not
            the client, so the client is one further left. Counting the
            rightmost here would bucket every visitor under the proxy's
            single address — the outage this option exists to prevent"
    (let [streaming (start-streaming-server! {:max-per-ip         1
                                              :trust-forwarded?   true
                                              :trusted-proxy-hops 2})
          port      (:port streaming)
          ;; what a two-hop edge produces: client, then the address the
          ;; inner proxy saw (the outer proxy).
          one       (stream-response!
                      port {"X-Forwarded-For" "203.0.113.7, 10.0.0.7"})
          two       (stream-response!
                      port {"X-Forwarded-For" "203.0.113.8, 10.0.0.7"})]
      (try
        (is (= 200 (.statusCode one)))
        (is (= 200 (.statusCode two))
            "a second client behind the SAME inner proxy still has its
             own budget — it is not counted as the proxy")
        (let [refused (stream-response!
                        port
                        {"X-Forwarded-For" "198.51.100.1, 203.0.113.8, 10.0.0.7"})]
          (is (= 503 (.statusCode refused))
              "and the forged leftmost entry is still ignored"))
        (finally
          (.close (.body one))
          (.close (.body two))
          (stop-streaming-server! streaming)))))

  (testing "on a direct connection (no trusted proxy) X-Forwarded-For is
            an anonymous client's bytes and is ignored — the TCP peer is
            what gets counted"
    (let [streaming (start-streaming-server! {:max-per-ip       1
                                              :trust-forwarded? false})
          port      (:port streaming)
          admitted  (stream-response! port {})]
      (try
        (is (= 200 (.statusCode admitted)))
        (let [refused (stream-response! port
                                        {"X-Forwarded-For" "203.0.113.9"})]
          (is (= 503 (.statusCode refused))
              "a forged header does not buy a second budget"))
        (finally
          (.close (.body admitted))
          (stop-streaming-server! streaming))))))

;; ---------------------------------------------------------------------
;; Privacy (the adsb-kbm.2 mandate) — over serialized frames

(def ^:private receiver-relative-poison
  "Fields the wire must never carry, hypothetically smuggled onto every
  cast member. Values are distinctive so a leak is unmistakable."
  {:aircraft/r-dst 987.654
   :aircraft/r-dir 271.5
   :r_dst 987.654
   :r_dir 271.5
   :receiver/lat 27.94
   :receiver/lon -82.45})

(def ^:private poisoned-picture
  (update-vals cast-picture #(merge % receiver-relative-poison)))

(def ^:private receiver-relative-pattern
  #"(?i)r[-_]dst|r[-_]dir|receiver|rssi")

(deftest wire-privacy
  (testing "serialized frames stay clean even when the state
            hypothetically carries receiver-relative fields"
    (let [streaming (start-streaming-server!
                      {:picture (constantly poisoned-picture)})
          reader    (open-stream! (:port streaming))]
      (try
        (doseq [frame [(read-frame! reader) (read-frame! reader)]]
          (let [json-text (frame-json frame)]
            (is (str/includes? json-text "abc0e4")
                "the frame still carries the aircraft")
            (is (not (re-find receiver-relative-pattern json-text)))
            (is (not (str/includes? json-text "987.654")))))
        (finally
          (.close reader)
          (stop-streaming-server! streaming)))))

  (testing "frames from the real fixture pipeline are clean too — no
            receiver position, no r_dst/r_dir, no rssi"
    (let [streaming (start-streaming-server! {})
          reader    (open-stream! (:port streaming))]
      (try
        (let [json-text (frame-json (read-frame! reader))]
          (is (str/includes? json-text "abc0e4"))
          (is (not (re-find receiver-relative-pattern json-text))))
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))
