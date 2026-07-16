(ns adsb.ingest.beast-source-test
  (:require [adsb.ingest.beast-source :as beast-source]
            [adsb.ingest.mode-s :as mode-s]
            [adsb.ingest.source :as source]
            [adsb.ingest.tcp :as tcp]
            [clojure.test :refer [deftest is testing]])
  (:import (clojure.lang ExceptionInfo)
           (java.net InetSocketAddress ServerSocket)))

(def ^:private escape-byte 0x1a)
(def ^:private long-type-byte 0x33)
(def ^:private mode-ac-type-byte 0x31)
(def ^:private short-type-byte 0x32)
(def ^:private sample-mlat [0x00 0x00 0x1a 0x00 0x00 0x01])
(def ^:private sample-signal 0xc8)

(defn- escape-data [bytes]
  (mapcat #(if (= % escape-byte) [escape-byte escape-byte] [%]) bytes))

(defn- beast-frame [type-byte payload]
  (concat [escape-byte type-byte]
          (escape-data (concat sample-mlat [sample-signal] payload))))

(defn- hex->payload [hex]
  (mapv (fn [pair] (Integer/parseInt (apply str pair) 16))
        (partition 2 hex)))

(defn- with-parity [data-bytes]
  (let [parity (mode-s/crc data-bytes)]
    (into data-bytes [(bit-shift-right parity 16)
                      (bit-and (bit-shift-right parity 8) 0xff)
                      (bit-and parity 0xff)])))

(def ^:private identification-payload
  (hex->payload "8D4840D6202CC371C32CE0576098"))

(def ^:private even-position-payload
  (hex->payload "8D40621D58C382D690C8AC2863A7"))

(def ^:private odd-position-payload
  (hex->payload "8D40621D58C386435CC412692AD6"))

(def ^:private corrupt-payload
  (update (with-parity (into [0x8d 0xab 0xcd 0xef] (repeat 7 0)))
          13 bit-xor 0x01))

(def ^:private book-position
  {:geo/lat 52.2572021484375
   :geo/lon 3.91937255859375})

(def ^:private wire-bytes
  (byte-array
    (map unchecked-byte
         (concat (beast-frame long-type-byte identification-payload)
                 [0x00 0xff 0x77]                           ; pure junk
                 (beast-frame long-type-byte even-position-payload)
                 (beast-frame mode-ac-type-byte [0xaa 0xbb]) ; ignored
                 (beast-frame long-type-byte odd-position-payload)
                 (beast-frame short-type-byte (range 0x40 0x47)) ; ignored
                 (beast-frame long-type-byte corrupt-payload) ; no trace
                 [escape-byte 0x00 0x88]))))                ; bad type, resync

(def ^:private chunk-size 5)

(defn- with-beast-feed [^bytes wire f]
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
                     (.read (.getInputStream client)))))]
    (try
      (f "127.0.0.1" port)
      (finally
        (future-cancel conn)
        (.close server)))))

(def ^:private wait-timeout-ms 2000)

(defn- wait-until [thunk]
  (let [deadline (+ (System/currentTimeMillis) wait-timeout-ms)]
    (loop []
      (or (thunk)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 20)
            (recur))))))

(defn- fetch-quietly [src]
  (try (source/fetch! src)
       (catch ExceptionInfo _)))

(defn- by-icao [batch]
  (into {} (map (juxt :aircraft/icao identity)) batch))

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
              (is (some? batch))
              (testing "the KLM1023 identification frame yields its callsign"
                (is (= "KLM1023" (get-in planes ["4840d6" :aircraft/callsign])))
                (is (not (contains? (planes "4840d6") :aircraft/position))))
              (testing "the even/odd pair globally decodes the book position and 38000 ft altitude"
                (is (= book-position (get-in planes ["40621d" :aircraft/position])))
                (is (= 38000 (get-in planes ["40621d" :aircraft/altitude-ft]))))
              (testing "only trustworthy long frames reach the picture"
                (is (= #{"4840d6" "40621d"} (set (keys planes))))
                (is (not (contains? planes "abcdef")))))
            (finally (source/close! src))))))))

(def ^:private decoded-message-count 3)

(deftest metadata-reports-the-decoded-message-count
  (testing "the Beast Source counts every frame it decodes and exposes the
            running total through Metadata (adsb-3mw), so adsb.stats can
            difference it into a rate on a streaming deployment"
    (with-beast-feed wire-bytes
      (fn [host port]
        (let [src (source/open!
                    (beast-source/->source host port
                                           {:clock (constantly 1000000)}))]
          (try
            (is (= {:messages decoded-message-count}
                   (wait-until
                     (fn []
                       (let [m (source/metadata src)]
                         (when (= decoded-message-count (:messages m)) m))))))
            (finally (source/close! src))))))))

(deftest read-loop-sweeps-the-cpr-state
  (testing "the read loop's sweep step drops the aircraft that have gone
            quiet and stamps the sweep, so cpr-state does not grow for
            the life of the connection (adsb-gq3)"
    (let [sweep-if-due #'beast-source/sweep-cpr-state-if-due
          {:keys [cpr-state]} (mode-s/decode odd-position-payload 1000 nil)
          {:keys [cpr-state]} (mode-s/decode even-position-payload 1500 cpr-state)
          a-day        (+ 1500 (* 24 60 60 1000))]
      (testing "never swept: the sweep runs, and its instant is stamped"
        (is (= [{} a-day] (sweep-if-due cpr-state nil a-day))))

      (testing "inside the interval nothing is scanned — the sweep does
                not ride every socket read"
        (let [just-swept (- a-day 1)]
          (is (= [cpr-state just-swept] (sweep-if-due cpr-state just-swept a-day)))))

      (testing "an aircraft still inside the age-out line keeps its
                reference through a sweep it is due for"
        (let [soon (+ 1500 tcp/default-sweep-interval-ms)
              [swept swept-at-ms] (sweep-if-due cpr-state nil soon)]
          (is (= soon swept-at-ms))
          (is (= book-position (get-in swept ["40621d" :cpr/reference :cpr/position]))))))))

(deftest quiet-feed-returns-a-snapshot-not-a-throw
  (testing "a connected feed that has said nothing yet is not unreachable —
            fetch! returns the (empty) snapshot rather than throwing"
    (with-beast-feed (byte-array 0)
      (fn [host port]
        (let [src (source/open! (beast-source/->source host port))]
          (try
            (let [batch (wait-until #(fetch-quietly src))]
              (is (vector? batch))
              (is (empty? batch)))
            (finally (source/close! src))))))))

(deftest fetch-throws-while-unreachable
  (testing "a stream that never connects is unreachable, so fetch! throws
            and the poll loop's backoff engages — like the SBS Source"
    (let [server (doto (ServerSocket.)
                   (.bind (InetSocketAddress. "127.0.0.1" 0)))
          port   (.getLocalPort server)]
      (.close server)
      (let [src (source/open!
                  (beast-source/->source "127.0.0.1" port {:reconnect-ms 50}))]
        (try
          (is (thrown? ExceptionInfo (source/fetch! src)))
          (finally (source/close! src)))))))
