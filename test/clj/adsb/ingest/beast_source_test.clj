(ns adsb.ingest.beast-source-test
  "The Beast Source end to end: bytes off a socket, through Beast framing
  (adsb.ingest.beast) and DF17 decode (adsb.ingest.mode-s), into the shared
  accumulator, out as coerced domain aircraft — open!/fetch!/close! over a
  local in-process TCP feed, never the live feeder (docs/CLAUDE.md: the sky
  is not a fixture; a localhost socket we serve ourselves is the sanctioned
  stand-in, bead adsb-c75 tracks a real capture).

  The wire bytes are SYNTHETIC, built here by wrapping mode-s.org's
  published known-answer Mode-S vectors (the same the mode-s decode tests
  pin — KLM1023 identification, and the even/odd pair that decodes to
  52.2572/3.91937 at 38000 ft) in Beast framing, then interleaving pure
  junk, a Mode-A/C and a short Mode-S frame the Source must ignore, a
  corrupted-CRC frame that must leave no trace, and an escape+bad-type
  resync run. The feed is served in small flushed chunks so frames split
  across socket reads, exercising the Source's :carry reassembly."
  (:require
    [adsb.ingest.beast-source :as beast-source]
    [adsb.ingest.mode-s :as mode-s]
    [adsb.ingest.source :as source]
    [clojure.test :refer [deftest testing is]])
  (:import
    (clojure.lang ExceptionInfo)
    (java.net InetSocketAddress ServerSocket)))

;; ---------------------------------------------------------------------
;; Beast framing of the published Mode-S vectors

(def ^:private escape-byte 0x1a)
(def ^:private long-type-byte 0x33)
(def ^:private mode-ac-type-byte 0x31)
(def ^:private short-type-byte 0x32)

(def ^:private sample-mlat
  "A 6-byte MLAT timestamp carrying an 0x1a byte, so the doubling escape
  is exercised end to end — the Source must de-escape it back to one byte."
  [0x00 0x00 0x1a 0x00 0x00 0x01])

(def ^:private sample-signal 0xc8)

(defn- escape-data
  "Double every 0x1a in the frame's data, the Beast self-escape."
  [bytes]
  (mapcat #(if (= % escape-byte) [escape-byte escape-byte] [%]) bytes))

(defn- beast-frame
  "One Beast frame: the 0x1a marker, a type byte, then the escaped MLAT,
  signal, and payload — the ultrafeeder's port-30005 wire format."
  [type-byte payload]
  (concat [escape-byte type-byte]
          (escape-data (concat sample-mlat [sample-signal] payload))))

(defn- hex->payload
  [hex]
  (mapv (fn [pair] (Integer/parseInt (apply str pair) 16))
        (partition 2 hex)))

(defn- with-parity
  "The data bytes with their CRC-24 appended — how a transponder builds a
  frame, so a crafted frame arrives parity-clean."
  [data-bytes]
  (let [parity (mode-s/crc data-bytes)]
    (into data-bytes [(bit-shift-right parity 16)
                      (bit-and (bit-shift-right parity 8) 0xff)
                      (bit-and parity 0xff)])))

(def ^:private identification-payload
  "TC4 identification, callsign KLM1023 (mode-s.org worked example)."
  (hex->payload "8D4840D6202CC371C32CE0576098"))

(def ^:private even-position-payload
  "TC11 airborne position, the even half of the book's pair."
  (hex->payload "8D40621D58C382D690C8AC2863A7"))

(def ^:private odd-position-payload
  "TC11 airborne position, the odd half of the book's pair."
  (hex->payload "8D40621D58C386435CC412692AD6"))

(def ^:private corrupt-payload
  "A CRC-clean proof-of-life frame for a distinct ICAO, then one bit
  flipped so parity fails. Had it decoded it would key 'abcdef'; its
  absence from the picture proves a corrupt frame leaves no trace."
  (update (with-parity (into [0x8d 0xab 0xcd 0xef] (repeat 7 0)))
          13 bit-xor 0x01))

(def ^:private book-position
  {:geo/lat 52.2572021484375 :geo/lon 3.91937255859375})

(def ^:private wire-bytes
  (byte-array
    (map unchecked-byte
         (concat (beast-frame long-type-byte identification-payload)
                 [0x00 0xff 0x77]                            ; pure junk
                 (beast-frame long-type-byte even-position-payload)
                 (beast-frame mode-ac-type-byte [0xaa 0xbb]) ; ignored
                 (beast-frame long-type-byte odd-position-payload)
                 (beast-frame short-type-byte (range 0x40 0x47)) ; ignored
                 (beast-frame long-type-byte corrupt-payload)    ; no trace
                 [escape-byte 0x00 0x88]))))                 ; bad type, resync

;; ---------------------------------------------------------------------
;; A local in-process Beast feed, served in split chunks

(def ^:private chunk-size
  "Bytes written per flush. Small enough to split frames across socket
  reads, so the Source's :carry must reassemble them."
  5)

(defn- with-beast-feed
  "Serve `wire` over a local TCP socket on an ephemeral 127.0.0.1 port,
  written in small flushed chunks so frames split across reads, and call
  (f host port). A localhost socket we serve ourselves is NOT a live feeder
  (docs/CLAUDE.md). The connection is held open until the client hangs up,
  so a connected-but-silent feed can be exercised too."
  [^bytes wire f]
  (let [server (doto (ServerSocket.)
                 (.bind (InetSocketAddress. "127.0.0.1" 0)))
        port   (.getLocalPort server)
        conn   (future
                 (with-open [client (.accept server)]
                   (let [out (.getOutputStream client)]
                     (doseq [chunk (partition-all chunk-size wire)]
                       (.write out (byte-array chunk))
                       (.flush out)
                       (Thread/sleep 1))
                     ;; Block until the client closes, holding the socket up.
                     (.read (.getInputStream client)))))]
    (try
      (f "127.0.0.1" port)
      (finally
        (future-cancel conn)
        (.close server)))))

(def ^:private wait-timeout-ms 2000)

(defn- wait-until
  "Poll `thunk` until it returns a truthy value or the timeout elapses,
  giving the async reader thread time to connect and consume. Returns the
  truthy value, or nil on timeout."
  [thunk]
  (let [deadline (+ (System/currentTimeMillis) wait-timeout-ms)]
    (loop []
      (or (thunk)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 20)
            (recur))))))

(defn- fetch-quietly [src]
  (try (source/fetch! src) (catch ExceptionInfo _ nil)))

(defn- by-icao [batch]
  (into {} (map (juxt :aircraft/icao identity)) batch))

;; ---------------------------------------------------------------------
;; The Source — open!/fetch!/close! over the local Beast feed

(deftest streams-decodes-and-accumulates-the-beast-feed
  (testing "the full stack — socket, framing, DF17 decode, accumulator —
            yields the published aircraft, folding CPR halves from separate
            frames into a position and dropping every hostile frame"
    (with-beast-feed wire-bytes
      (fn [host port]
        (let [src (source/open!
                    (beast-source/->source host port
                                           {:clock (constantly 1000000)}))]
          (try
            (let [batch  (wait-until
                           (fn []
                             (let [b (fetch-quietly src)]
                               (when (get-in (by-icao b)
                                             ["40621d" :aircraft/position])
                                 b))))
                  planes (by-icao batch)]
              (is (some? batch)
                  "the reader decodes the even/odd pair within the timeout")
              (testing "the KLM1023 identification frame yields its callsign"
                (is (= "KLM1023"
                       (get-in planes ["4840d6" :aircraft/callsign])))
                (is (not (contains? (planes "4840d6") :aircraft/position))
                    "an identification frame carries no position"))
              (testing "the even/odd pair globally decodes the book position
                        and 38000 ft altitude"
                (is (= book-position
                       (get-in planes ["40621d" :aircraft/position])))
                (is (= 38000
                       (get-in planes ["40621d" :aircraft/altitude-ft]))))
              (testing "only trustworthy long frames reach the picture"
                (is (= #{"4840d6" "40621d"} (set (keys planes)))
                    "junk, Mode-A/C, short Mode-S, the corrupt frame, and
                     the resync run all leave no trace")
                (is (not (contains? planes "abcdef"))
                    "the corrupted-CRC frame decoded to nothing")))
            (finally (source/close! src))))))))

(deftest quiet-feed-returns-a-snapshot-not-a-throw
  (testing "a connected feed that has said nothing yet is not unreachable —
            fetch! returns the (empty) snapshot rather than throwing"
    (with-beast-feed (byte-array 0)
      (fn [host port]
        (let [src (source/open! (beast-source/->source host port))]
          (try
            (let [batch (wait-until #(fetch-quietly src))]
              (is (vector? batch) "a live but silent feed yields a snapshot")
              (is (empty? batch) "with nothing heard, the snapshot is empty"))
            (finally (source/close! src))))))))

(deftest fetch-throws-while-unreachable
  (testing "a stream that never connects is unreachable, so fetch! throws
            and the poll loop's backoff engages — like the SBS Source"
    (let [server (doto (ServerSocket.)
                   (.bind (InetSocketAddress. "127.0.0.1" 0)))
          port   (.getLocalPort server)]
      (.close server) ;; free the port so nothing is listening
      (let [src (source/open!
                  (beast-source/->source "127.0.0.1" port {:reconnect-ms 50}))]
        (try
          (is (thrown? ExceptionInfo (source/fetch! src)))
          (finally (source/close! src)))))))
