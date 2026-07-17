(ns adsb.stream.broadcast-test
  (:require [adsb.fixtures :as fixtures :refer [captured-at-ms declared-crop]]
            [adsb.http.server :as server]
            [adsb.ingest.sbs :as sbs]
            [adsb.ingest.source :as source]
            [adsb.ingest.tcp :as tcp]
            [adsb.picture :as picture]
            [adsb.stream.admission :as admission]
            [adsb.stream.broadcast :as broadcast]
            [adsb.test-feed :as feed]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as http-kit])
  (:import (java.io BufferedReader OutputStreamWriter PipedInputStream PipedOutputStream)
           (java.net Socket URI)
           (java.net.http HttpClient HttpRequest HttpRequest$Builder HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)))

(def ^:private cast-picture (picture/merge-batch {} fixtures/all captured-at-ms))
(def ^:const ^:private frame-timeout-ms 2000)
(def ^:const ^:private delta-latency-samples 10)
(def ^:const ^:private max-delta-latency-ms 100)
(def ^:private await-opts {:sleep-ms 10})

(defn- start-streaming-server!
  [{:keys [picture stats feeder crop interval-ms stats-interval-ms heartbeat-ms
           max-clients max-per-ip trust-forwarded? trusted-proxy-hops]
    :or   {picture            (constantly cast-picture)
           stats              (constantly nil)
           feeder             (constantly nil)
           crop               nil
           interval-ms        50
           stats-interval-ms  60000
           heartbeat-ms       60000
           max-clients        100
           max-per-ip         100
           trust-forwarded?   false
           trusted-proxy-hops 1}}]
  (let [broadcaster (broadcast/start!
                      {:picture            picture
                       :stats              stats
                       :feeder             feeder
                       :crop               crop
                       :interval-ms        interval-ms
                       :stats-interval-ms  stats-interval-ms
                       :heartbeat-ms       heartbeat-ms
                       :max-clients        max-clients
                       :max-per-ip         max-per-ip
                       :trust-forwarded?   trust-forwarded?
                       :trusted-proxy-hops trusted-proxy-hops})
        srv         (server/start-server!
                      {:port           0
                       :stream-connect #(broadcast/connect! broadcaster %)})]
    {:broadcaster broadcaster
     :server      srv
     :port        (http-kit/server-port srv)}))

(defn- stop-streaming-server! [{:keys [broadcaster server]}]
  (server/stop-server! server)
  (broadcast/stop! broadcaster))

(defn- start-and-stop-broadcaster [opts]
  (let [broadcaster (broadcast/start! (merge {:picture (constantly cast-picture)} opts))]
    (broadcast/stop! broadcaster)
    broadcaster))

(defn- stream-response! ^HttpResponse [port headers]
  (let [request (reduce-kv (fn [builder header-name header-value]
                             (.header ^HttpRequest$Builder builder
                                      header-name header-value))
                           (-> (HttpRequest/newBuilder
                                 (URI. (str "http://localhost:" port
                                            "/api/stream")))
                               .GET)
                           headers)]
    (.send (HttpClient/newHttpClient) (.build ^HttpRequest$Builder request)
           (HttpResponse$BodyHandlers/ofInputStream))))

(defn- open-stream!
  (^BufferedReader [port] (open-stream! port {}))
  (^BufferedReader [port headers]
   (io/reader (.body (stream-response! port headers)))))

(defn- retry-after [^HttpResponse response]
  (.orElse (.firstValue (.headers response) "Retry-After") nil))

(defn- read-frame! [^BufferedReader reader]
  (deref (future
           (loop [lines []]
             (let [line (.readLine reader)]
               (cond
                 (nil? line) lines
                 (str/blank? line) (if (seq lines) lines (recur lines))
                 :else (recur (conj lines line))))))
         frame-timeout-ms
         ::timeout))

(defn- frame-event [frame]
  (some #(second (re-find #"^event: (.+)$" %)) frame))

(defn- read-config! [^BufferedReader reader]
  (read-frame! reader))

(defn- frame-id [frame]
  (some #(some-> (re-find #"^id: (\d+)$" %) second parse-long) frame))

(defn- frame-json [frame]
  (str/join (keep #(second (re-find #"^data: (.+)$" %)) frame)))

(defn- frame-data [frame]
  (json/parse-string (frame-json frame) true))

(deftest config-event-leads-the-connection
  (testing "the FIRST frame of a connection is `config`, carrying the crop's
            declared centre and radius — the map draws the edge of what this
            app publishes before any aircraft land inside it"
    (let [streaming (start-streaming-server! {:crop declared-crop})
          reader    (open-stream! (:port streaming))]
      (try
        (let [config (read-frame! reader)]
          (is (= "config" (frame-event config)))
          (is (= {:lat 27.9753 :lon -82.5331 :radius-km 100}
                 (:crop (frame-data config))))
          (is (= "snapshot" (frame-event (read-frame! reader)))))
        (finally
          (.close reader)
          (stop-streaming-server! streaming)))))

  (testing "it is sent ONCE and never again — nothing on it can change while
            the process lives, so it has no tick"
    (let [streaming (start-streaming-server! {:crop declared-crop})
          reader    (open-stream! (:port streaming))]
      (try
        (read-config! reader)
        (dotimes [_ 5]
          (is (not= "config" (frame-event (read-frame! reader)))))
        (finally
          (.close reader)
          (stop-streaming-server! streaming)))))

  (testing "a DISABLED crop still sends the config frame, but with no crop —
            the browser draws no boundary, and NOTHING falls back to the
            receiver position, which is the coordinate this whole feature
            exists to protect"
    (let [streaming (start-streaming-server! {:crop nil})
          reader    (open-stream! (:port streaming))]
      (try
        (let [config (read-frame! reader)]
          (is (= "config" (frame-event config)))
          (is (not (contains? (frame-data config) :crop))))
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))

(deftest snapshot-then-stats-then-updates
  (testing "a new client receives one full snapshot (aircraft only), one
            immediate stats frame, then ~per-tick updates, with ids
            increasing across every event kind"
    (let [streaming (start-streaming-server! {})
          reader    (open-stream! (:port streaming))]
      (try
        (read-config! reader)
        (let [snapshot     (read-frame! reader)
              stats-frame  (read-frame! reader)
              update-frame (read-frame! reader)]
          (is (= "snapshot" (frame-event snapshot)))
          (let [{:keys [at aircraft] :as envelope} (frame-data snapshot)]
            (is (number? at))
            (is (= (count cast-picture) (count aircraft)))
            (is (contains? (set (map :icao aircraft)) "abc0e4"))
            (is (every? :seen-at aircraft))
            (is (not (contains? envelope :stats))))
          (is (= "stats" (frame-event stats-frame)))
          (is (not (contains? (frame-data stats-frame) :aircraft)))
          (is (= "update" (frame-event update-frame)))
          (let [update-envelope (frame-data update-frame)]
            (is (= (count cast-picture) (count (:aircraft update-envelope))))
            (is (not (contains? update-envelope :stats))))
          (is (< (frame-id snapshot)
                 (frame-id stats-frame)
                 (frame-id update-frame))))
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))

(deftest stats-ride-their-own-event
  (testing "the session stats the broadcaster computes on the stats tick
            reach the client as a stats event's wire scalars — never on an
            aircraft frame"
    (let [streaming (start-streaming-server!
                      {:stats (constantly {:stats/max-range-km   312
                                           :stats/message-rate   148
                                           :stats/aircraft-count 5})})
          reader    (open-stream! (:port streaming))]
      (try
        (read-config! reader)
        (read-frame! reader)
        (let [{:keys [stats]} (frame-data (read-frame! reader))]
          (is (= {:max-range-km 312 :message-rate 148} stats)))
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))

(deftest feeder-status-rides-the-stats-event
  (testing "the feeder status the broadcaster reads reaches the client on
            the stats event as the wire status + timestamp, and the
            internal error detail never leaves"
    (let [streaming (start-streaming-server!
                      {:feeder (constantly
                                 {:feeder/status          :down
                                  :feeder/last-success-ms 1720713599000
                                  :feeder/last-error
                                  "connect timed out: dietpi.local:8100"})})
          reader    (open-stream! (:port streaming))]
      (try
        (read-config! reader)
        (read-frame! reader)
        (let [stats-frame (read-frame! reader)]
          (is (= "stats" (frame-event stats-frame)))
          (is (= {:status "down" :last-success 1720713599000}
                 (:feeder (frame-data stats-frame))))
          (is (not (str/includes? (frame-json stats-frame) "dietpi.local"))))
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
        (read-config! reader)
        (read-frame! reader)
        (read-frame! reader)
        (is (= [": hb"] (read-frame! reader)))
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))

(def ^:const ^:private sbs-position-line
  "MSG,3,1,1,A1B2C3,1,,,,,,37000,,,39.8721,-104.6702,,,,,,")

(defn- with-piped-sbs-source! [on-delta f]
  (let [out (PipedOutputStream.)
        in  (PipedInputStream. out)
        src (source/open!
              (sbs/->source "pipe" 0
                            {:transport (fn [_host _port _opts]
                                          {:in     in
                                           :close! #(tcp/close-quietly! in)})
                             :on-delta  on-delta}))
        w   (OutputStreamWriter. out StandardCharsets/US_ASCII)]
    (try
      (is (true? (feed/wait-until #(deref (:connected? src)) await-opts)))
      (f (fn write-line! [line]
           (.write w (str line "\n"))
           (.flush w)))
      (finally
        (source/close! src)
        (.close w)))))

(deftest a-delta-reaches-a-live-client-fast-with-no-tick-in-the-path
  (let [streaming (start-streaming-server! {:interval-ms nil})
        b         (:broadcaster streaming)
        reader    (doto (open-stream! (:port streaming))
                    (read-config!)
                    (read-frame!)
                    (read-frame!))]
    (try
      (with-piped-sbs-source!
        (fn [aircraft now-ms] (broadcast/offer-delta! b aircraft now-ms))
        (fn [write-line!]
          (let [samples (doall
                          (for [_ (range delta-latency-samples)]
                            (let [started-at (System/nanoTime)]
                              (write-line! sbs-position-line)
                              (let [frame (read-frame! reader)]
                                {:frame      frame
                                 :elapsed-ms (/ (- (System/nanoTime) started-at)
                                                1e6)}))))
                fastest (apply min (map :elapsed-ms samples))
                frame   (:frame (first samples))]
            (is (= "aircraft" (frame-event frame)))
            (is (= "a1b2c3" (get-in (frame-data frame) [:aircraft :icao])))
            (is (number? (get-in (frame-data frame) [:aircraft :seen-at])))
            (is (every? #(= "aircraft" (frame-event (:frame %))) samples))
            (is (< fastest max-delta-latency-ms)))))
      (finally
        (.close reader)
        (stop-streaming-server! streaming)))))

(deftest streaming-shape-has-no-update-tick
  (testing "with :interval-ms nil (the streaming deployment) a client gets
            the snapshot and its stats frame and then NO full-picture
            updates — the next thing on the wire is the heartbeat"
    (let [streaming (start-streaming-server! {:interval-ms  nil
                                              :heartbeat-ms 100})
          reader    (open-stream! (:port streaming))]
      (try
        (read-config! reader)
        (is (= "snapshot" (frame-event (read-frame! reader))))
        (is (= "stats" (frame-event (read-frame! reader))))
        (is (= [": hb"] (read-frame! reader)))
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))

(deftest offer-delta!-never-blocks-and-drops-newest-under-pressure
  (let [broadcaster (broadcast/start! {:picture           (constantly {})
                                       :interval-ms       nil
                                       :stats-interval-ms 60000
                                       :delta-queue-depth 4})]
    (broadcast/stop! broadcaster)
    (Thread/sleep 150)
    (let [started-at (System/nanoTime)
          results    (mapv (fn [i]
                             (broadcast/offer-delta!
                               broadcaster #:aircraft{:icao (str i)} i))
                           (range 10))
          elapsed-ms (/ (- (System/nanoTime) started-at) 1e6)]
      (testing "a full queue never blocks the reader: the four that fit are
                accepted, the rest are shed, and the whole burst returns well
                under the blocking-put timeout"
        (is (= [true true true true] (take 4 results)))
        (is (every? false? (drop 4 results)))
        (is (< elapsed-ms 200)))
      (testing "every shed delta is counted, not silently discarded, so a
                saturated fan-out is observable rather than invisible (adsb-u7z)"
        (is (= 6 (broadcast/deltas-dropped broadcaster)))))))

(deftest the-age-out-sweep-outlives-the-update-tick
  (testing "with no update tick (streaming) and zero clients, the injected
            picture fn — production's age-out sweep — still runs on the
            stats cadence"
    (let [sweeps      (atom 0)
          broadcaster (broadcast/start!
                        {:picture           (fn [_now-ms]
                                              (swap! sweeps inc)
                                              {})
                         :interval-ms       nil
                         :stats-interval-ms 20})]
      (try
        (is (true? (feed/wait-until #(< 2 @sweeps) await-opts)))
        (finally
          (broadcast/stop! broadcaster))))))

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
        (is (true? (feed/wait-until #(= 1 (connected)) await-opts)))
        (.close socket)
        (is (true? (feed/wait-until #(zero? (connected)) await-opts)))
        (finally
          (stop-streaming-server! streaming))))))

(defn- open-admitted!
  (^BufferedReader [port] (open-admitted! port {}))
  (^BufferedReader [port headers]
   (doto (open-stream! port headers)
     (read-config!)
     (read-frame!)
     (read-frame!))))

(deftest total-connection-cap
  (testing "at the concurrent-client cap a new connect is refused with
            503 + Retry-After, while admitted clients keep streaming"
    (let [streaming (start-streaming-server! {:max-clients 2})
          port      (:port streaming)
          admitted  [(open-admitted! port) (open-admitted! port)]]
      (try
        (let [refused (stream-response! port {})]
          (is (= 503 (.statusCode refused)))
          (is (some? (retry-after refused)))
          (is (str/includes? (slurp (.body refused)) "server-full")))
        (is (= "update" (frame-event (read-frame! (first admitted)))))
        (finally
          (doseq [^BufferedReader reader admitted] (.close reader))
          (stop-streaming-server! streaming))))))

(deftest over-cap-connect-rejects-before-the-sse-upgrade
  (let [broadcaster (start-and-stop-broadcaster {:max-clients 0})
        response    (broadcast/connect! broadcaster
                                        {:headers {} :remote-addr "1.2.3.4"})]
    (testing "connect! returns a plain 503 map, not an upgraded channel"
      (is (map? response))
      (is (= 503 (:status response)))
      (is (= (str admission/retry-after-s) (get-in response [:headers "Retry-After"])))
      (is (str/includes? (:body response) "server-full")))
    (testing "and nothing was registered — a synchronous refusal claims no slot"
      (is (zero? (broadcast/client-count broadcaster))))))

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

(deftest the-per-ip-cap-keys-on-cf-connecting-ip
  (testing "two connections Cloudflare says are the SAME client are counted
            together, even though X-Forwarded-For disagrees on every hop —
            this is the production failure, in a test"
    (let [streaming (start-streaming-server! {:max-clients      100
                                              :max-per-ip       1
                                              :trust-forwarded? true})
          port      (:port streaming)
          headers   (fn [xff] {"CF-Connecting-IP" "1.2.3.4"
                               "X-Forwarded-For"  xff})
          admitted  (open-admitted! port (headers "1.2.3.4, 172.71.10.1"))]
      (try
        (let [refused (stream-response! port (headers "1.2.3.4, 172.71.99.7"))]
          (is (= 503 (.statusCode refused)))
          (is (str/includes? (slurp (.body refused)) "ip-full")))
        (finally
          (.close admitted)
          (stop-streaming-server! streaming)))))

  (testing "two genuinely different clients are NOT counted together, so the
            cap does not lock the site — the other direction of the bug"
    (let [streaming (start-streaming-server! {:max-clients      100
                                              :max-per-ip       1
                                              :trust-forwarded? true})
          port      (:port streaming)
          admitted  (open-admitted! port {"CF-Connecting-IP" "1.2.3.4"})]
      (try
        (let [other (open-admitted! port {"CF-Connecting-IP" "5.6.7.8"})]
          (is (= 2 (broadcast/client-count (:broadcaster streaming))))
          (.close other))
        (finally
          (.close admitted)
          (stop-streaming-server! streaming)))))

  (testing "with trust off, a client cannot name itself — a forged
            CF-Connecting-IP is just bytes, and both connections count
            against the socket's own address"
    (let [streaming (start-streaming-server! {:max-clients 100
                                              :max-per-ip  1})
          port      (:port streaming)
          admitted  (open-admitted! port {"CF-Connecting-IP" "1.2.3.4"})]
      (try
        (let [refused (stream-response! port {"CF-Connecting-IP" "5.6.7.8"})]
          (is (= 503 (.statusCode refused))))
        (finally
          (.close admitted)
          (stop-streaming-server! streaming))))))

(deftest start!-requires-the-picture-at-boot
  (testing "the one REQUIRED option is enforced by :keys! — a broadcaster
            with no picture fn fails at boot with a named key, not later
            with an NPE on the first tick inside the executor thread"
    (is (thrown-with-msg? IllegalArgumentException
                          #"Missing required key: :picture"
                          (broadcast/start! {})))))

(deftest start!-seeds-the-client-ip-diagnostic-budget
  (testing "with the diagnostic off, start! creates no counter — it is
            opt-in and costs nothing when unused"
    (is (nil? (:stream/diagnose-remaining
                (start-and-stop-broadcaster {:diagnose-client-ip? false})))))

  (testing "with the diagnostic on, start! seeds the hard-capped budget so
            the connect-time logging cannot run forever"
    (is (= admission/diagnose-budget
           @(:stream/diagnose-remaining
              (start-and-stop-broadcaster {:diagnose-client-ip? true}))))))

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
        (is (true? (feed/wait-until #(= 1 (connected)) await-opts)))
        (is (= 503 (.statusCode (stream-response! port {}))))
        (.close socket)
        (is (true? (feed/wait-until #(zero? (connected)) await-opts)))
        (let [reader (open-admitted! port)]
          (is (= 1 (connected)))
          (.close reader))
        (finally
          (stop-streaming-server! streaming))))))

(deftest a-short-xff-header-falls-to-the-socket-peer
  (testing "with trust on and two hops configured but only ONE X-Forwarded-For
            entry, the header is too short to name the client — forwarded-ip
            returns nil and the per-IP key falls to the socket peer, so two
            such connections collide on that peer and the second is refused.
            The old clamp-to-leftmost would have trusted each spoofable single
            entry as a distinct client and admitted both (adsb-u7z)"
    (let [streaming (start-streaming-server! {:max-per-ip         1
                                              :trust-forwarded?   true
                                              :trusted-proxy-hops 2})
          port      (:port streaming)
          admitted  (stream-response! port {"X-Forwarded-For" "203.0.113.7"})]
      (try
        (is (= 200 (.statusCode admitted)))
        (let [refused (stream-response! port {"X-Forwarded-For" "203.0.113.8"})]
          (is (= 503 (.statusCode refused)))
          (is (str/includes? (slurp (.body refused)) "ip-full")))
        (finally
          (.close (.body admitted))
          (stop-streaming-server! streaming))))))

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
        (is (= 200 (.statusCode two)))
        (let [refused (stream-response! port {"X-Forwarded-For" "198.51.100.1, 203.0.113.8"})]
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
          one       (stream-response!
                      port {"X-Forwarded-For" "203.0.113.7, 10.0.0.7"})
          two       (stream-response!
                      port {"X-Forwarded-For" "203.0.113.8, 10.0.0.7"})]
      (try
        (is (= 200 (.statusCode one)))
        (is (= 200 (.statusCode two)))
        (let [refused (stream-response! port {"X-Forwarded-For" "198.51.100.1, 203.0.113.8, 10.0.0.7"})]
          (is (= 503 (.statusCode refused))))
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
        (let [refused (stream-response! port {"X-Forwarded-For" "203.0.113.9"})]
          (is (= 503 (.statusCode refused))))
        (finally
          (.close (.body admitted))
          (stop-streaming-server! streaming))))))

(def ^:private receiver-relative-poison
  {:aircraft/r-dst 987.654
   :aircraft/r-dir 271.5
   :r_dst          987.654
   :r_dir          271.5
   :receiver/lat   27.94
   :receiver/lon   -82.45})

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
        (read-config! reader)
        (let [snapshot    (read-frame! reader)
              stats-frame (read-frame! reader)
              update-f    (read-frame! reader)]
          (is (= "stats" (frame-event stats-frame)))
          (is (not (str/includes? (frame-json stats-frame) "987.654")))
          (doseq [frame [snapshot update-f]]
            (let [json-text (frame-json frame)]
              (is (str/includes? json-text "abc0e4"))
              (is (not (re-find receiver-relative-pattern json-text)))
              (is (not (str/includes? json-text "987.654"))))))
        (finally
          (.close reader)
          (stop-streaming-server! streaming)))))

  (testing "frames from the real fixture pipeline are clean too — no
            receiver position, no r_dst/r_dir, no rssi"
    (let [streaming (start-streaming-server! {})
          reader    (open-stream! (:port streaming))]
      (try
        (read-config! reader)
        (let [json-text (frame-json (read-frame! reader))]
          (is (str/includes? json-text "abc0e4"))
          (is (not (re-find receiver-relative-pattern json-text))))
        (finally
          (.close reader)
          (stop-streaming-server! streaming)))))

  (testing "the config frame carries the DECLARED crop and nothing
            receiver-relative"
    (let [streaming (start-streaming-server! {:crop declared-crop})
          reader    (open-stream! (:port streaming))]
      (try
        (let [json-text (frame-json (read-frame! reader))]
          (is (not (re-find receiver-relative-pattern json-text)))
          (is (str/includes? json-text "27.9753")))
        (finally
          (.close reader)
          (stop-streaming-server! streaming)))))

  (testing "a disabled crop emits NO coordinate at all — it must never fall
            back to the receiver position"
    (let [streaming (start-streaming-server! {:crop nil})
          reader    (open-stream! (:port streaming))]
      (try
        (let [json-text (frame-json (read-frame! reader))]
          (is (not (re-find #"lat|lon" json-text))))
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))
