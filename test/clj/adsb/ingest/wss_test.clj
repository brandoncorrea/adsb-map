(ns adsb.ingest.wss-test
  "The websocket transport (adsb.ingest.wss) end to end: the streaming
  Sources dial an in-process http-kit websocket server on 127.0.0.1 that
  replays the real 2026-07-14 tunnel captures as binary messages, and the
  picture that comes out must match the plain-socket transport's byte for
  byte — the whole point of the seam is that SBS/Beast cannot tell which
  transport fed them (adsb-elf). A localhost server we run ourselves is NOT
  a live feeder (docs/CLAUDE.md); the sbs/beast wire is the frozen capture,
  never sbs.bwawan.com.

  Also here: the stall — a connected-but-silent websocket must drop and
  reconnect once :idle-timeout-ms of silence elapses — and the unreachable
  case, where an endpoint that never accepts leaves fetch! throwing."
  (:require
    [adsb.ingest.beast-source :as beast-source]
    [adsb.ingest.sbs :as sbs]
    [adsb.ingest.source :as source]
    [adsb.ingest.wss :as wss]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest testing is]]
    [org.httpkit.server :as hk])
  (:import
    (clojure.lang ExceptionInfo)
    (java.net InetSocketAddress ServerSocket URI)
    (java.nio.charset StandardCharsets)))

;; ---------------------------------------------------------------------
;; The captures — the same frozen bytes capture-replay-test drives through
;; the pure boundary, here driven through the websocket transport instead.

(def ^:private sbs-capture-path "test/resources/sbs-capture-2026-07-14.txt")
(def ^:private beast-capture-path "test/resources/beast-capture-2026-07-14.bin")

(defn- sbs-capture-bytes []
  (.getBytes (slurp sbs-capture-path) StandardCharsets/US_ASCII))

(defn- beast-capture-bytes []
  (with-open [in (io/input-stream beast-capture-path)]
    (.readAllBytes in)))

;; The accumulated pictures each capture yields — asserted alongside the
;; cross-transport equality so a change to either shows up as its own
;; failure (matches capture-replay-test's counts).
(def ^:private sbs-aircraft-count 33)
(def ^:private beast-aircraft-count 29)

;; ---------------------------------------------------------------------
;; A local in-process websocket feed, streamed as several binary messages

(defn- ws-app
  "An http-kit ring handler that, on each websocket connection, streams
  (next-chunks!) as binary messages in small flushed frames — so a message
  boundary falls mid-line / mid-frame exactly like a socket read — then
  holds the channel open. next-chunks! is a 0-arg fn returning the byte
  sequence for this connection, so a reconnect can be served fresh bytes."
  [next-chunks!]
  (fn [request]
    (hk/as-channel request
      {:on-open
       (fn [channel]
         (future
           (doseq [chunk (partition-all 512 (next-chunks!))]
             (hk/send! channel (byte-array chunk))
             (Thread/sleep 1))))})))

(defn- with-ws-feed
  "Run `next-chunks!` behind a websocket server on an ephemeral 127.0.0.1
  port and call (f uri). Never a live feeder — a localhost server we run
  ourselves (docs/CLAUDE.md)."
  [next-chunks! f]
  (let [stop! (hk/run-server (ws-app next-chunks!) {:port 0 :ip "127.0.0.1"})
        port  (:local-port (meta stop!))]
    (try
      (f (URI. (str "ws://127.0.0.1:" port "/feed")))
      (finally
        (stop!)))))

(def ^:private wait-timeout-ms 4000)

(defn- wait-until [thunk]
  (let [deadline (+ (System/currentTimeMillis) wait-timeout-ms)]
    (loop []
      (or (thunk)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 25)
            (recur))))))

(defn- fetch-quietly [src]
  (try (source/fetch! src) (catch ExceptionInfo _ nil)))

(defn- by-icao [batch]
  (into {} (map (juxt :aircraft/icao identity)) batch))

;; ---------------------------------------------------------------------
;; A local in-process plain-TCP feed of the same bytes, to prove the
;; websocket picture equals the socket picture.

(defn- with-tcp-feed
  "Serve `capture` over a plain TCP socket on an ephemeral 127.0.0.1 port,
  written in small flushed chunks, and call (f host port). Held open until
  the client hangs up."
  [^bytes capture f]
  (let [server (doto (ServerSocket.)
                 (.bind (InetSocketAddress. "127.0.0.1" 0)))
        port   (.getLocalPort server)
        conn   (future
                 (with-open [client (.accept server)]
                   (let [out (.getOutputStream client)]
                     (doseq [chunk (partition-all 512 capture)]
                       (.write out (byte-array chunk))
                       (.flush out)
                       (Thread/sleep 1))
                     (.read (.getInputStream client)))))]
    (try
      (f "127.0.0.1" port)
      (finally
        (future-cancel conn)
        (.close server)))))

(defn- picture-of
  "open!/wait/fetch!/close! a Source, returning the by-ICAO picture once the
  whole capture has drained — the picture has reached `expected-count`
  aircraft AND settled (two successive fetches agree, so the stream is done
  updating fields, not merely mid-replay). Comparing a mid-stream snapshot
  would be racy: two concurrent replays reach a given aircraft count at
  different byte offsets."
  [src expected-count]
  (try
    (loop [previous ::none
           deadline (+ (System/currentTimeMillis) wait-timeout-ms)]
      (let [batch (fetch-quietly src)]
        (if (and (= expected-count (count batch)) (= previous batch))
          (by-icao batch)
          (if (< (System/currentTimeMillis) deadline)
            (do (Thread/sleep 50) (recur batch deadline))
            (by-icao batch)))))
    (finally (source/close! src))))

;; ---------------------------------------------------------------------
;; The picture is transport-agnostic: wss == plain socket, byte for byte

(deftest sbs-capture-streams-identically-over-wss-and-tcp
  (testing "the SBS capture accumulates the same picture whether it arrives
            over the websocket transport or a plain socket"
    (let [capture (sbs-capture-bytes)
          clock   {:clock (constantly 0)}
          wss-picture (with-ws-feed
                        (fn [] (seq capture))
                        (fn [uri]
                          (picture-of
                            (source/open!
                              (sbs/->source "127.0.0.1" 0
                                            (assoc clock :transport
                                                   (wss/transport uri nil))))
                            sbs-aircraft-count)))
          tcp-picture (with-tcp-feed
                        capture
                        (fn [host port]
                          (picture-of
                            (source/open! (sbs/->source host port clock))
                            sbs-aircraft-count)))]
      (is (= sbs-aircraft-count (count wss-picture))
          "the websocket picture is the whole capture")
      (is (= tcp-picture wss-picture)
          "the transport does not change what the boundary produces"))))

(deftest beast-capture-streams-identically-over-wss-and-tcp
  (testing "the Beast capture accumulates the same picture over the
            websocket transport as over a plain socket — :carry and CPR
            state reassemble across websocket message boundaries too"
    (let [capture (beast-capture-bytes)
          clock   {:clock (constantly 1000000)}
          wss-picture (with-ws-feed
                        (fn [] (seq capture))
                        (fn [uri]
                          (picture-of
                            (source/open!
                              (beast-source/->source
                                "127.0.0.1" 0
                                (assoc clock :transport
                                       (wss/transport uri nil))))
                            beast-aircraft-count)))
          tcp-picture (with-tcp-feed
                        capture
                        (fn [host port]
                          (picture-of
                            (source/open!
                              (beast-source/->source host port clock))
                            beast-aircraft-count)))]
      (is (= beast-aircraft-count (count wss-picture))
          "the websocket picture is the whole capture")
      (is (= tcp-picture wss-picture)
          "the transport does not change what the boundary produces"))))

;; ---------------------------------------------------------------------
;; Stall detection: a connected-but-silent websocket drops and reconnects

(defn- sbs-identification
  "One valid SBS identification line for `icao`, so a single message names a
  whole aircraft the picture can be asserted on."
  [icao callsign]
  (.getBytes (str "MSG,1,1,1," icao ",1,,,,," callsign ",,,,,,,,,,,\n")
             StandardCharsets/US_ASCII))

(deftest silence-is-a-stall-that-drops-and-reconnects
  (testing "a websocket that names one aircraft then falls silent past
            :idle-timeout-ms is treated as dead: the reader drops it and
            reconnects, and the fresh connection's aircraft reaches the
            picture — the stalled-tunnel case the shared reader exists to
            surface"
    (let [connections (atom 0)
          ;; connection 1 names ALPHA then goes silent; a reconnect names
          ;; BRAVO, whose arrival is the proof the reader re-dialed.
          next-chunks! (fn []
                         (if (= 1 (swap! connections inc))
                           (seq (sbs-identification "aaaaaa" "ALPHA123"))
                           (seq (sbs-identification "bbbbbb" "BRAVO123"))))]
      (with-ws-feed next-chunks!
        (fn [uri]
          (let [src (source/open!
                      (sbs/->source "127.0.0.1" 0
                                    {:clock           (constantly 0)
                                     :transport       (wss/transport uri nil)
                                     :idle-timeout-ms 200
                                     :reconnect-ms    50}))]
            (try
              (let [picture (wait-until
                              (fn []
                                (let [planes (by-icao (fetch-quietly src))]
                                  (when (contains? planes "bbbbbb") planes))))]
                (is (some? picture)
                    "the reconnect's aircraft appears, so the reader re-dialed")
                (is (< 1 @connections)
                    "the server saw more than one connection")
                (is (= "ALPHA123" (get-in picture ["aaaaaa" :aircraft/callsign]))
                    "the pre-stall aircraft survives the reconnect in the picture"))
              (finally (source/close! src)))))))))

;; ---------------------------------------------------------------------
;; Unreachable: an endpoint that never accepts leaves fetch! throwing

(deftest an-endpoint-that-never-accepts-throws-unreachable
  (testing "a wss endpoint with nothing listening never connects, so the
            websocket upgrade fails on every attempt and fetch! keeps
            throwing — the poll loop's backoff engages, exactly as for a
            refused socket"
    (let [server (doto (ServerSocket.)
                   (.bind (InetSocketAddress. "127.0.0.1" 0)))
          port   (.getLocalPort server)]
      (.close server) ;; free the port so nothing accepts the upgrade
      (let [uri (URI. (str "ws://127.0.0.1:" port "/feed"))
            src (source/open!
                  (sbs/->source "127.0.0.1" 0
                                {:transport    (wss/transport uri nil)
                                 :reconnect-ms 50}))]
        (try
          (is (thrown? ExceptionInfo (source/fetch! src)))
          (finally (source/close! src)))))))
