(ns adsb.ingest.wss-test
  (:require [adsb.fixtures :as fixtures]
            [adsb.ingest.beast-source :as beast-source]
            [adsb.ingest.sbs :as sbs]
            [adsb.ingest.source :as source]
            [adsb.ingest.wss :as wss]
            [adsb.test-feed :as feed]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as hk])
  (:import (clojure.lang ExceptionInfo)
           (java.net InetSocketAddress ServerSocket URI)
           (java.nio.charset StandardCharsets)))

(def ^:private sbs-capture-path "test/resources/sbs-capture-2026-07-14.txt")
(def ^:private beast-capture-path "test/resources/beast-capture-2026-07-14.bin")

(defn- sbs-capture-bytes []
  (.getBytes (slurp sbs-capture-path) StandardCharsets/US_ASCII))

(defn- beast-capture-bytes []
  (with-open [in (io/input-stream beast-capture-path)]
    (.readAllBytes in)))

(def ^:private sbs-aircraft-count 33)
(def ^:private beast-aircraft-count 29)

(defn- ws-app [next-chunks!]
  (fn [request]
    (hk/as-channel request
      {:on-open
       (fn [channel]
         (future
           (doseq [chunk (partition-all 512 (next-chunks!))]
             (hk/send! channel (byte-array chunk))
             (Thread/sleep 1))))})))

(defn- ws-frame-app [next-frames!]
  (fn [request]
    (hk/as-channel request
      {:on-open
       (fn [channel]
         (future
           (doseq [^bytes frame (next-frames!)]
             (hk/send! channel frame)
             (Thread/sleep 1))))})))

(defn- with-ws-server [handler f]
  (let [stop! (hk/run-server handler {:port 0 :ip "127.0.0.1"})
        port  (:local-port (meta stop!))]
    (try
      (f (URI. (str "ws://127.0.0.1:" port "/feed")))
      (finally (stop!)))))

(defn- with-ws-feed [next-chunks! f]
  (with-ws-server (ws-app next-chunks!) f))

(def ^:private wait-timeout-ms 4000)
(def ^:private wait-opts {:timeout-ms wait-timeout-ms :sleep-ms 25})

(defn- picture-of [src expected-count]
  (try
    (loop [previous ::none
           deadline (+ (System/currentTimeMillis) wait-timeout-ms)]
      (let [batch (feed/fetch-quietly src)]
        (if (and (= expected-count (count batch)) (= previous batch))
          (fixtures/by-icao batch)
          (if (< (System/currentTimeMillis) deadline)
            (do (Thread/sleep 50) (recur batch deadline))
            (fixtures/by-icao batch)))))
    (finally (source/close! src))))

(deftest sbs-capture-streams-identically-over-wss-and-tcp
  (testing "the SBS capture accumulates the same picture whether it arrives
            over the websocket transport or a plain socket"
    (let [capture     (sbs-capture-bytes)
          clock       {:clock (constantly 0)}
          wss-picture (with-ws-feed
                        (fn [] (seq capture))
                        (fn [uri]
                          (picture-of
                            (source/open!
                              (sbs/->source "127.0.0.1" 0
                                            (assoc clock :transport
                                                         (wss/transport uri nil))))
                            sbs-aircraft-count)))
          tcp-picture (feed/with-byte-feed
                        capture
                        (fn [host port]
                          (picture-of
                            (source/open! (sbs/->source host port clock))
                            sbs-aircraft-count)))]
      (is (= sbs-aircraft-count (count wss-picture)))
      (is (= tcp-picture wss-picture)))))

(deftest beast-capture-streams-identically-over-wss-and-tcp
  (testing "the Beast capture accumulates the same picture over the
            websocket transport as over a plain socket — :carry and CPR
            state reassemble across websocket message boundaries too"
    (let [capture     (beast-capture-bytes)
          clock       {:clock (constantly 1000000)}
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
          tcp-picture (feed/with-byte-feed
                        capture
                        (fn [host port]
                          (picture-of
                            (source/open!
                              (beast-source/->source host port clock))
                            beast-aircraft-count)))]
      (is (= beast-aircraft-count (count wss-picture)))
      (is (= tcp-picture wss-picture)))))

(defn- sbs-identification [icao callsign]
  (.getBytes (str "MSG,1,1,1," icao ",1,,,,," callsign ",,,,,,,,,,,\n")
             StandardCharsets/US_ASCII))

(deftest an-empty-frame-between-two-messages-is-invisible-to-the-reader
  (testing "a zero-length websocket message carries no bytes and means
            nothing: the reader must skip it, not surface a 0-byte read.
            An InputStream returning 0 for a nonzero request is the Beast
            pump's end-of-stream signal and makes the SBS pump's decoder
            throw — either way the connection drops and CPR pairing and the
            Beast carry drop with it, so an edge emitting empty frames would
            churn the feed once per frame (adsb-kpd)"
    (let [connections  (atom 0)
          next-frames! (fn []
                         (swap! connections inc)
                         [(sbs-identification "aaaaaa" "ALPHA123")
                          (byte-array 0)                    ;; the empty frame, mid-stream
                          (sbs-identification "bbbbbb" "BRAVO123")])]
      (with-ws-server (ws-frame-app next-frames!)
        (fn [uri]
          (let [src (source/open!
                      (sbs/->source "127.0.0.1" 0
                                    {:clock           (constantly 0)
                                     :transport       (wss/transport uri nil)
                                     :idle-timeout-ms 2000
                                     :reconnect-ms    50}))]
            (try
              (let [picture (feed/wait-until
                              (fn []
                                (let [planes (fixtures/by-icao (feed/fetch-quietly src))]
                                  (when (contains? planes "bbbbbb") planes)))
                              wait-opts)]
                (is (= "ALPHA123" (get-in picture ["aaaaaa" :aircraft/callsign])))
                (is (= "BRAVO123" (get-in picture ["bbbbbb" :aircraft/callsign])))
                (is (= 1 @connections)))
              (finally (source/close! src)))))))))

(deftest silence-is-a-stall-that-drops-and-reconnects
  (testing "a websocket that names one aircraft then falls silent past
            :idle-timeout-ms is treated as dead: the reader drops it and
            reconnects, and the fresh connection's aircraft reaches the
            picture — the stalled-tunnel case the shared reader exists to
            surface"
    (let [connections  (atom 0)
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
              (let [picture (feed/wait-until
                              (fn []
                                (let [planes (fixtures/by-icao (feed/fetch-quietly src))]
                                  (when (contains? planes "bbbbbb") planes)))
                              wait-opts)]
                (is (some? picture))
                (is (< 1 @connections))
                (is (= "ALPHA123" (get-in picture ["aaaaaa" :aircraft/callsign]))))
              (finally (source/close! src)))))))))

(deftest an-endpoint-that-never-accepts-throws-unreachable
  (testing "a wss endpoint with nothing listening never connects, so the
            websocket upgrade fails on every attempt and fetch! keeps
            throwing — the poll loop's backoff engages, exactly as for a
            refused socket"
    (let [server (doto (ServerSocket.)
                   (.bind (InetSocketAddress. "127.0.0.1" 0)))
          port   (.getLocalPort server)]
      (.close server)
      (let [uri (URI. (str "ws://127.0.0.1:" port "/feed"))
            src (source/open!
                  (sbs/->source "127.0.0.1" 0
                                {:transport    (wss/transport uri nil)
                                 :reconnect-ms 50}))]
        (try
          (is (thrown? ExceptionInfo (source/fetch! src)))
          (finally (source/close! src)))))))

(defn- selector-thread-count []
  (->> (.keySet (Thread/getAllStackTraces))
       (filter #(re-matches #"HttpClient-\d+-SelectorManager" (.getName ^Thread %)))
       count))

(deftest reconnects-reuse-a-single-http-client
  (testing "a Source that drops and re-dials many times must not hatch an
            HttpClient per dial — each brings its own selector thread and
            connection pool, and against a flapping edge redialing every
            :reconnect-ms they pile up for hours (adsb-ad8). One client for
            the Source's lifetime, so the selector threads do not grow with
            the reconnects"
    (let [connections  (atom 0)
          next-chunks! (fn []
                         (swap! connections inc)
                         (seq (sbs-identification "aaaaaa" "ALPHA123")))
          before       (selector-thread-count)]
      (with-ws-feed next-chunks!
        (fn [uri]
          (let [src (source/open!
                      (sbs/->source "127.0.0.1" 0
                                    {:clock           (constantly 0)
                                     :transport       (wss/transport uri nil)
                                     :idle-timeout-ms 100
                                     :reconnect-ms    25}))]
            (try
              (is (feed/wait-until #(<= 4 @connections) wait-opts))
              (is (>= 1 (- (selector-thread-count) before)))
              (finally (source/close! src)))))))))

(def ^:private claim-dial! #'wss/claim-dial!)
(def ^:private abandon-dial! #'wss/abandon-dial!)

(deftest a-dial-that-lands-before-we-give-up-is-handed-to-the-dialer
  (testing "the ordinary connect: the websocket lands, the waiting dialer
            claims it, and abandon-dial! is never called — nobody aborts it"
    (let [websocket (Object.)
          state     (atom nil)]
      (is (true? (claim-dial! state websocket)))
      (is (identical? websocket @state)))))

(deftest a-dial-still-in-flight-when-we-give-up-is-aborted-when-it-lands
  (testing "we time out first: there is nothing to abort yet, so the abort
            falls to the JDK end — claim-dial! must refuse the websocket
            that lands afterwards, marking it the caller's to close (adsb-ad8)"
    (let [state (atom nil)]
      (is (nil? (abandon-dial! state)))
      (is (false? (claim-dial! state (Object.)))))))

(deftest a-dial-that-lands-in-the-instant-we-give-up-is-aborted-by-us
  (testing "the other ordering: the websocket lands and is claimed just as
            the dialer stops waiting. The JDK end has already handed it over
            and will not abort it, so abandon-dial! must return it — else a
            live websocket no reader holds stays open (adsb-ad8)"
    (let [websocket (Object.)
          state     (atom nil)]
      (is (true? (claim-dial! state websocket)))
      (is (identical? websocket (abandon-dial! state))))))
