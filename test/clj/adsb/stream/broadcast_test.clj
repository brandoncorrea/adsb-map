(ns adsb.stream.broadcast-test
  "SSE over a real HTTP connection to an ephemeral-port server. The
  picture is the fixture cast — never a live feeder. The JDK HttpClient
  reads the chunked stream as it arrives; http-kit's own client buffers
  whole responses and cannot."
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.fixtures :as fixtures]
    [adsb.http.server :as server]
    [adsb.ingest.sbs :as sbs]
    [adsb.ingest.source :as source]
    [adsb.ingest.tcp :as tcp]
    [adsb.stream.broadcast :as broadcast]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
    [org.httpkit.server :as http-kit])
  (:import
    (java.io BufferedReader OutputStreamWriter PipedInputStream
             PipedOutputStream)
    (java.net Socket URI)
    (java.net.http HttpClient HttpRequest HttpRequest$Builder HttpResponse
                   HttpResponse$BodyHandlers)
    (java.nio.charset StandardCharsets)))

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
  and periodic stats effectively off unless a test turns them up (the
  connect-time stats frame still arrives — it is part of the connect
  contract, not of the cadence). :interval-ms nil is honored: it
  disables the update tick, the streaming deployment's shape. Limits
  are pinned explicitly so a stray ADSB_SSE_* in the environment cannot
  bend a test."
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
           trusted-proxy-hops 1}
    :as   opts}]
  (let [broadcaster (broadcast/start!
                      {:picture            picture
                       :stats              stats
                       :feeder             feeder
                       :crop               crop
                       ;; honor an explicit nil (no update tick)
                       :interval-ms        (if (contains? opts :interval-ms)
                                             (:interval-ms opts)
                                             interval-ms)
                       :stats-interval-ms  stats-interval-ms
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

(defn- start-and-stop-broadcaster
  "Start a broadcaster with `opts`, immediately stop its ticks, and return
  the (now-inert) broadcaster map so its atoms can be inspected. For
  wiring assertions that need no live server or clients."
  [opts]
  (let [broadcaster (broadcast/start! (merge {:picture (constantly cast-picture)}
                                             opts))]
    (broadcast/stop! broadcaster)
    broadcaster))

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
  (^BufferedReader [port] (open-stream! port {}))
  (^BufferedReader [port headers]
   (io/reader (.body (stream-response! port headers)))))

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

(defn- read-config!
  "Consume the connect-time `config` frame — the FIRST frame of every
  connection, ahead of the snapshot (adsb-au5). Every test that reads the
  handshake positionally calls this first, so the `config` event is
  asserted in exactly one place (config-event-leads-the-connection) and
  merely stepped over everywhere else."
  [^BufferedReader reader]
  (read-frame! reader))

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
;; The config event — the declared boundary, once, ahead of everything

(def ^:private declared-crop
  "A crop centred on TPA. NOT the receiver — that is the whole contract
  (adsb.ingest.crop): the centre is a public, arbitrary point, and the
  fixture cast's own synthetic receiver sits elsewhere."
  {:crop/center {:geo/lat 27.9753 :geo/lon -82.5331}
   :crop/radius-m 100000})

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
          (is (= "snapshot" (frame-event (read-frame! reader)))
              "and the snapshot follows it"))
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
          (is (not= "config" (frame-event (read-frame! reader)))
              "no second config frame ever comes"))
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

;; ---------------------------------------------------------------------
;; The stream's contract

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
            (is (every? :seen-at aircraft)
                "the aging timestamp rides on every wire aircraft")
            (is (not (contains? envelope :stats))
                "aircraft data and stats never share a payload (adsb-jpf)"))
          (is (= "stats" (frame-event stats-frame))
              "one stats frame right behind the snapshot, so the chrome
               populates without waiting out a stats interval")
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
                      {:stats (constantly {:stats/max-range-km 312
                                           :stats/message-rate 148
                                           ;; counts must NOT reach the wire
                                           :stats/aircraft-count 5})})
          reader    (open-stream! (:port streaming))]
      (try
        (read-config! reader)
        (read-frame! reader)                       ; the snapshot
        (let [{:keys [stats]} (frame-data (read-frame! reader))]
          (is (= {:max-range-km 312 :message-rate 148} stats)
              "only the two scalars, never the counts"))
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
        (read-frame! reader)                       ; the snapshot
        (let [stats-frame (read-frame! reader)]
          (is (= "stats" (frame-event stats-frame)))
          (is (= {:status "down" :last-success 1720713599000}
                 (:feeder (frame-data stats-frame)))
              "status and timestamp, nothing more")
          (is (not (str/includes? (frame-json stats-frame) "dietpi.local"))
              "the error string — a leak risk — never reaches the wire"))
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
        ;; The snapshot and its stats frame arrive on connect; with
        ;; updates and periodic stats effectively off, the next frame on
        ;; the wire must be the heartbeat.
        (read-config! reader)
        (read-frame! reader)
        (read-frame! reader)
        (is (= [": hb"] (read-frame! reader)))
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))

;; ---------------------------------------------------------------------
;; The per-aircraft upsert path (adsb-jpf)

(def ^:const ^:private sbs-position-line
  "One SBS MSG,3 line — a1b2c3 with a position and altitude."
  "MSG,3,1,1,A1B2C3,1,,,,,,37000,,,39.8721,-104.6702,,,,,,")

(defn- with-piped-sbs-source!
  "An SbsSource whose transport is an in-memory pipe — a real reader
  thread and a real accumulate path, no socket — wired to `on-delta`.
  Calls (f write-line!) once the reader is connected, closing everything
  after. write-line! feeds the reader one SBS line."
  [on-delta f]
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
      (is (true? (eventually #(deref (:connected? src))))
          "the reader dialed the pipe")
      (f (fn write-line! [line]
           (.write w (str line "\n"))
           (.flush w)))
      (finally
        (source/close! src)
        (.close w)))))

(deftest a-delta-reaches-a-live-client-fast-with-no-tick-in-the-path
  ;; THE LATENCY PROOF (adsb-jpf): a message arriving at a Source-shaped
  ;; path — a real SbsSource reader thread, its accumulate, the :on-delta
  ;; hook, the bounded queue, the fan-out thread, http-kit, a real TCP
  ;; read — lands on the client well under 100 ms. Every tick is pinned
  ;; effectively off (update tick nil — the streaming deployment — and
  ;; stats/heartbeat at 60 s), so no fixed cadence can be in the path:
  ;; if the push were tick-driven, this test could not pass.
  (let [streaming (start-streaming-server! {:interval-ms nil})
        b         (:broadcaster streaming)
        reader    (doto (open-stream! (:port streaming))
                    (read-config!)    ; the connect-time config frame
                    (read-frame!)     ; the snapshot
                    (read-frame!))]   ; the connect-time stats frame
    (try
      (with-piped-sbs-source!
        (fn [aircraft now-ms] (broadcast/offer-delta! b aircraft now-ms))
        (fn [write-line!]
          (let [started-at (System/nanoTime)]
            (write-line! sbs-position-line)
            (let [frame      (read-frame! reader)
                  elapsed-ms (/ (- (System/nanoTime) started-at) 1e6)]
              (is (= "aircraft" (frame-event frame)))
              (is (= "a1b2c3" (get-in (frame-data frame) [:aircraft :icao]))
                  "the upsert carries the one merged aircraft")
              (is (number? (get-in (frame-data frame) [:aircraft :seen-at]))
                  "stamped at its arrival instant")
              (is (< elapsed-ms 100)
                  (str "delta-to-client took " elapsed-ms " ms"))))))
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
        (is (= [": hb"] (read-frame! reader))
            "no update frame ever comes")
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))

(deftest offer-delta!-never-blocks-and-drops-newest-under-pressure
  ;; The reader thread must NEVER block on fan-out. A stopped broadcaster
  ;; is the worst case — a fan-out that never drains — so a tiny queue
  ;; fills and every further offer must return false immediately rather
  ;; than wait (offer, never put; drop-newest — see the docstring).
  (let [broadcaster (broadcast/start! {:picture           (constantly {})
                                       :interval-ms       nil
                                       :stats-interval-ms 60000
                                       :delta-queue-depth 4})]
    (broadcast/stop! broadcaster)
    (Thread/sleep 150)                  ; let the fan-out thread exit
    (let [started-at (System/nanoTime)
          results    (mapv (fn [i]
                             (broadcast/offer-delta!
                               broadcaster #:aircraft{:icao (str i)} i))
                           (range 10))
          elapsed-ms (/ (- (System/nanoTime) started-at) 1e6)]
      (is (= [true true true true] (take 4 results))
          "the queue admits up to its depth")
      (is (every? false? (drop 4 results))
          "at capacity the NEWEST delta is dropped — the offer just fails")
      (is (< elapsed-ms 200)
          (str "ten offers against a full queue took " elapsed-ms
               " ms — an offer never waits")))))

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
        (is (true? (eventually #(< 2 @sweeps)))
            "the sweep keeps running with no audience at all")
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
  "Open a stream and consume its config, snapshot, and connect-time stats
  frames, so the client is admitted, ready, and receiving ticks. Returns
  the reader; the caller closes."
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
          (is (some? (retry-after refused))
              "a polite client is told when to come back")
          (is (str/includes? (slurp (.body refused)) "server-full")))
        (is (= "update" (frame-event (read-frame! (first admitted))))
            "a rejection costs the admitted clients nothing")
        (finally
          (doseq [^BufferedReader reader admitted] (.close reader))
          (stop-streaming-server! streaming))))))

(deftest over-cap-connect-rejects-before-the-sse-upgrade
  ;; adsb-1se: rejecting after http-kit upgrades the request to an SSE
  ;; channel makes Cloudflare report a 504 to the client. So the common
  ;; over-cap case must be a plain Ring response, decided synchronously —
  ;; never an as-channel upgrade. A full server (max-clients 0) denies
  ;; every connect, so connect! returns the response map directly.
  (let [broadcaster (start-and-stop-broadcaster {:max-clients 0})
        response    (broadcast/connect! broadcaster
                                        {:headers {} :remote-addr "1.2.3.4"})]
    (testing "connect! returns a plain 503 map, not an upgraded channel"
      (is (map? response))
      (is (= 503 (:status response)))
      (is (= (str broadcast/retry-after-s) (get-in response [:headers "Retry-After"])))
      (is (str/includes? (:body response) "server-full")))
    (testing "and nothing was registered — a synchronous refusal claims no slot"
      (is (zero? (broadcast/client-count broadcaster))))))

(deftest trusts-forwarded-for?-reflects-the-resolved-flag
  ;; The boot warning (adsb.main) reads this, not the environment, so it
  ;; cannot drift from what the broadcaster actually does.
  (is (true? (broadcast/trusts-forwarded-for?
               (start-and-stop-broadcaster {:trust-forwarded? true}))))
  (is (false? (broadcast/trusts-forwarded-for?
                (start-and-stop-broadcaster {:trust-forwarded? false})))))

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
  ;; The regression this exists for is adsb-nnk, and it was found in
  ;; PRODUCTION, not here: five concurrent streams from one address, against
  ;; a cap of four, were all admitted. The chain is browser -> Cloudflare ->
  ;; DigitalOcean's edge (itself Cloudflare), and the address the last hop
  ;; appends varies per connection, so every connection keyed under a
  ;; different address and the cap never bound. No hop COUNT can fix that —
  ;; there is no fixed index where the client reliably sits — so the cap now
  ;; keys on CF-Connecting-IP, which is one address and not a chain.
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
        ;; A different last hop every time — exactly what DigitalOcean's edge
        ;; does, and exactly what defeated the hop count.
        (let [refused (stream-response! port (headers "1.2.3.4, 172.71.99.7"))]
          (is (= 503 (.statusCode refused))
              "same client per Cloudflare, so the second connection is refused")
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
          (is (= 2 (broadcast/client-count (:broadcaster streaming)))
              "a second, different client gets its own slot")
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
          (is (= 503 (.statusCode refused))
              "spoofing a different CF-Connecting-IP buys no second slot"))
        (finally
          (.close admitted)
          (stop-streaming-server! streaming))))))

(deftest the-client-ip-diagnostic-is-bounded-and-opt-in
  ;; adsb-nnk: this is the throwaway that will tell us what CF-Connecting-IP
  ;; the container really sees behind two Cloudflare layers. Its whole safety
  ;; story is "off by default, and self-limiting when on" — so that is what
  ;; the test pins.
  (let [request {:headers     {"cf-connecting-ip" "1.2.3.4"
                               "x-forwarded-for"  "1.2.3.4, 172.71.9.9"}
                 :remote-addr "10.0.0.9"}
        diagnose #'broadcast/diagnose-client-ip!]
    (testing "with the flag off there is no counter, and a connect logs
              nothing and does not throw"
      (is (nil? (:stream/diagnose-remaining
                  (start-and-stop-broadcaster {:diagnose-client-ip? false}))))
      (is (nil? (diagnose {:stream/diagnose-remaining nil} request "1.2.3.4"))))

    (testing "with the flag on the budget counts down, once per connect,
              and stops dead at zero rather than logging forever"
      (let [remaining {:stream/diagnose-remaining (atom 2)}]
        (dotimes [_ 5] (diagnose remaining request "1.2.3.4"))
        (is (zero? @(:stream/diagnose-remaining remaining))
            "two logged, the next three were silent no-ops")))

    (testing "the env flag / option seeds the hard-capped budget"
      (is (= broadcast/diagnose-budget
             @(:stream/diagnose-remaining
                (start-and-stop-broadcaster {:diagnose-client-ip? true})))))))

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
        ;; snapshot, the connect-time stats frame, then an update — the
        ;; aircraft-bearing frames must carry the cast and nothing
        ;; receiver-relative; the stats frame carries no aircraft at all.
        (read-config! reader)
        (let [snapshot    (read-frame! reader)
              stats-frame (read-frame! reader)
              update-f    (read-frame! reader)]
          (is (= "stats" (frame-event stats-frame)))
          (is (not (str/includes? (frame-json stats-frame) "987.654")))
          (doseq [frame [snapshot update-f]]
            (let [json-text (frame-json frame)]
              (is (str/includes? json-text "abc0e4")
                  "the frame still carries the aircraft")
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

  ;; The config frame is the ONLY frame that carries a coordinate, so it is
  ;; the one place a receiver position could plausibly slip onto the wire —
  ;; either by someone "helpfully" defaulting a disabled crop to the antenna,
  ;; or by the crop being derived from it. Both would pass every other test
  ;; in this file. Neither may pass this one.
  (testing "the config frame carries the DECLARED crop and nothing
            receiver-relative"
    (let [streaming (start-streaming-server! {:crop declared-crop})
          reader    (open-stream! (:port streaming))]
      (try
        (let [json-text (frame-json (read-frame! reader))]
          (is (not (re-find receiver-relative-pattern json-text)))
          (is (str/includes? json-text "27.9753")
              "the declared decoy centre, which is public by construction"))
        (finally
          (.close reader)
          (stop-streaming-server! streaming)))))

  (testing "a disabled crop emits NO coordinate at all — it must never fall
            back to the receiver position"
    (let [streaming (start-streaming-server! {:crop nil})
          reader    (open-stream! (:port streaming))]
      (try
        (let [json-text (frame-json (read-frame! reader))]
          (is (not (re-find #"lat|lon" json-text))
              "no coordinate of any kind on a crop-disabled config frame"))
        (finally
          (.close reader)
          (stop-streaming-server! streaming))))))
