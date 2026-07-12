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
    (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

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
  effectively off unless a test turns them up."
  [{:keys [picture stats interval-ms heartbeat-ms]
    :or   {picture      (constantly cast-picture)
           stats        (constantly nil)
           interval-ms  50
           heartbeat-ms 60000}}]
  (let [broadcaster (broadcast/start! {:picture      picture
                                       :stats        stats
                                       :interval-ms  interval-ms
                                       :heartbeat-ms heartbeat-ms})
        srv         (server/start!
                      {:port           0
                       :stream-connect #(broadcast/connect! broadcaster %)})]
    {:broadcaster broadcaster
     :port        (http-kit/server-port srv)}))

(defn- stop-streaming-server! [{:keys [broadcaster]}]
  (server/stop!)
  (broadcast/stop! broadcaster))

(defn- open-stream!
  "GET /api/stream and return a line reader over the live body."
  ^BufferedReader [port]
  (let [request  (-> (HttpRequest/newBuilder
                       (URI. (str "http://localhost:" port "/api/stream")))
                     (.GET)
                     (.build))
        response (.send (HttpClient/newHttpClient) request
                        (HttpResponse$BodyHandlers/ofInputStream))]
    (io/reader (.body response))))

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
