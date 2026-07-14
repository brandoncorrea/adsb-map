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

(defn- ws-frame-app
  "An http-kit ring handler that sends each byte array (next-frames!) yields
  as ONE websocket message — empty ones included — then holds the channel
  open. Where ws-app picks the frame boundaries for you, this one lets the
  test dictate them."
  [next-frames!]
  (fn [request]
    (hk/as-channel request
      {:on-open
       (fn [channel]
         (future
           (doseq [^bytes frame (next-frames!)]
             (hk/send! channel frame)
             (Thread/sleep 1))))})))

(defn- with-ws-server
  "Run `handler` on an ephemeral 127.0.0.1 port and call (f uri). Never a
  live feeder — a localhost server we run ourselves (docs/CLAUDE.md)."
  [handler f]
  (let [stop! (hk/run-server handler {:port 0 :ip "127.0.0.1"})
        port  (:local-port (meta stop!))]
    (try
      (f (URI. (str "ws://127.0.0.1:" port "/feed")))
      (finally
        (stop!)))))

(defn- with-ws-feed
  "Stream `next-chunks!`' bytes as a websocket feed and call (f uri)."
  [next-chunks! f]
  (with-ws-server (ws-app next-chunks!) f))

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
;; An empty websocket frame is legal traffic, not end of stream

(defn- sbs-identification
  "One valid SBS identification line for `icao`, so a single message names a
  whole aircraft the picture can be asserted on."
  [icao callsign]
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
    (let [connections (atom 0)
          next-frames! (fn []
                         (swap! connections inc)
                         [(sbs-identification "aaaaaa" "ALPHA123")
                          (byte-array 0) ;; the empty frame, mid-stream
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
              (let [picture (wait-until
                              (fn []
                                (let [planes (by-icao (fetch-quietly src))]
                                  (when (contains? planes "bbbbbb") planes))))]
                (is (= "ALPHA123" (get-in picture ["aaaaaa" :aircraft/callsign]))
                    "the message before the empty frame decoded")
                (is (= "BRAVO123" (get-in picture ["bbbbbb" :aircraft/callsign]))
                    "so did the one after it — the empty frame was skipped, not
                     read as end of stream")
                (is (= 1 @connections)
                    "and the feed never dropped: one connection served it all"))
              (finally (source/close! src)))))))))

;; ---------------------------------------------------------------------
;; Stall detection: a connected-but-silent websocket drops and reconnects

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

;; ---------------------------------------------------------------------
;; Dial lifecycle: one client across reconnects, and no dial left behind

(defn- selector-thread-count
  "How many java.net.http.HttpClient selector threads are alive. Each
  HttpClient starts exactly one, named HttpClient-N-SelectorManager, and it
  outlives any single request — so this counts live clients."
  []
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
    (let [connections (atom 0)
          ;; every connection names one aircraft then goes silent, so the
          ;; idle timeout drops it and the reader re-dials, over and over.
          next-chunks! (fn []
                         (swap! connections inc)
                         (seq (sbs-identification "aaaaaa" "ALPHA123")))
          before (selector-thread-count)]
      (with-ws-feed next-chunks!
        (fn [uri]
          (let [src (source/open!
                      (sbs/->source "127.0.0.1" 0
                                    {:clock           (constantly 0)
                                     :transport       (wss/transport uri nil)
                                     :idle-timeout-ms 100
                                     :reconnect-ms    25}))]
            (try
              (is (wait-until #(<= 4 @connections))
                  "the reader re-dialed several times")
              (is (>= 1 (- (selector-thread-count) before))
                  "but at most one selector thread appeared — one client, reused")
              (finally (source/close! src)))))))))

;; A dial we stop waiting for must never leave a live websocket behind: no
;; reader holds it, so its TCP connection would sit open for the life of the
;; (now shared, long-lived) client. Whichever end of the dial arrives second
;; owns the abort — claim-dial! / abandon-dial! are the referee, and this is
;; the one place both orderings can be driven deterministically. Driving it
;; through a slow server proves nothing: the JDK's own builder connectTimeout
;; tears the handshake down first, and the narrow race left over — the future
;; completing just as .get gives up — is not one a server can be made to hit
;; on demand.

(def ^:private claim-dial! #'wss/claim-dial!)
(def ^:private abandon-dial! #'wss/abandon-dial!)

(deftest a-dial-that-lands-before-we-give-up-is-handed-to-the-dialer
  (testing "the ordinary connect: the websocket lands, the waiting dialer
            claims it, and abandon-dial! is never called — nobody aborts it"
    (let [websocket (Object.)
          state     (atom nil)]
      (is (true? (claim-dial! state websocket))
          "the dial is claimed, so the JDK end does not abort it")
      (is (identical? websocket @state)))))

(deftest a-dial-still-in-flight-when-we-give-up-is-aborted-when-it-lands
  (testing "we time out first: there is nothing to abort yet, so the abort
            falls to the JDK end — claim-dial! must refuse the websocket
            that lands afterwards, marking it the caller's to close (adsb-ad8)"
    (let [state (atom nil)]
      (is (nil? (abandon-dial! state))
          "nothing had landed, so the waiter has nothing to abort")
      (is (false? (claim-dial! state (Object.)))
          "the late websocket is refused: whoever established it aborts it"))))

(deftest a-dial-that-lands-in-the-instant-we-give-up-is-aborted-by-us
  (testing "the other ordering: the websocket lands and is claimed just as
            the dialer stops waiting. The JDK end has already handed it over
            and will not abort it, so abandon-dial! must return it — else a
            live websocket no reader holds stays open (adsb-ad8)"
    (let [websocket (Object.)
          state     (atom nil)]
      (is (true? (claim-dial! state websocket)))
      (is (identical? websocket (abandon-dial! state))
          "the dial we walked away from comes back to us to abort"))))
