(ns adsb.ingest.sbs-test
  (:require [adsb.ingest.sbs :as sbs]
            [adsb.ingest.source :as source]
            [adsb.ingest.streaming-source-contract :as contract]
            [adsb.ingest.tcp :as tcp]
            [adsb.stats :as stats]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (clojure.lang ExceptionInfo)
           (java.io OutputStreamWriter)
           (java.net InetSocketAddress ServerSocket)
           (java.nio.charset StandardCharsets)))

(def ^:private fixture-lines
  (str/split-lines (slurp "test/resources/sbs-sample.txt")))

(deftest parses-each-consumed-message-type
  (testing "MSG,1 identification yields a trimmed callsign"
    (is (= #:aircraft{:icao "a1b2c3" :callsign "UAL123"}
           (sbs/line->delta
             "MSG,1,1,1,A1B2C3,1,,,,,UAL123  ,,,,,,,,,,,"))))
  (testing "MSG,3 airborne position yields position and altitude"
    (is (= #:aircraft{:icao        "a1b2c3"
                      :position    #:geo{:lat 39.8721 :lon -104.6702}
                      :altitude-ft 37000}
           (sbs/line->delta
             "MSG,3,1,1,A1B2C3,1,,,,,,37000,,,39.8721,-104.6702,,,,,,"))))
  (testing "MSG,4 velocity yields ground speed, track, and vertical rate"
    (is (= #:aircraft{:icao            "a1b2c3"
                      :ground-speed-kt 451.2
                      :track-deg       268.5
                      :baro-rate-fpm   -1216}
           (sbs/line->delta
             "MSG,4,1,1,A1B2C3,1,,,,,,,451.2,268.5,,,-1216,,,,,"))))
  (testing "MSG,6 surveillance-id yields the squawk"
    (is (= #:aircraft{:icao "a1b2c3" :altitude-ft 37000 :squawk "7700"}
           (sbs/line->delta
             "MSG,6,1,1,A1B2C3,1,,,,,,37000,,,,,,7700,,,,"))))
  (testing "MSG,8 all-call yields the on-ground marker (SBS emits -1)"
    (is (= #:aircraft{:icao "abcdef" :on-ground? true}
           (sbs/line->delta
             "MSG,8,1,1,ABCDEF,1,,,,,,,,,,,,,,,,-1")))))

(deftest reports-airborne-out-loud
  (testing "an explicit airborne flag (\"0\") is a false in the delta, not an
            absent field — deltas merge, so only a false clears a stale
            on-ground once the aircraft takes off (adsb-b0w)"
    (is (= #:aircraft{:icao "abcdef" :on-ground? false}
           (sbs/line->delta "MSG,8,1,1,ABCDEF,1,,,,,,,,,,,,,,,,0"))))
  (testing "a line whose on-ground field is empty says nothing about the
            ground, and carries no marker either way"
    (is (not (contains? (sbs/line->delta
                          "MSG,3,1,1,ABCDEF,1,,,,,,37000,,,39.8721,-104.6702,,,,,,")
                        :aircraft/on-ground?))))
  (testing "an unreadable on-ground field is absent, never a false"
    (is (not (contains? (sbs/line->delta
                          "MSG,8,1,1,ABCDEF,1,,,,,,,,,,,,,,,,yes")
                        :aircraft/on-ground?)))))

(deftest normalizes-fields-at-the-boundary
  (testing "the ICAO identity is lower-cased, matching the domain vocabulary"
    (is (= "a1b2c3" (:aircraft/icao
                      (sbs/line->delta "MSG,8,1,1,A1B2C3,1,,,,,,,,,,,,,,,,-1")))))
  (testing "a leading-zero-stripped squawk is recovered to four digits"
    (is (= "0021" (:aircraft/squawk
                    (sbs/line->delta
                      "MSG,6,1,1,A1B2C3,1,,,,,,,,,,,,21,,,,"))))))

(deftest drops-malformed-and-hostile-lines
  (testing "a non-MSG class (STA/AIR/SEL/ID/CLK) carries no telemetry we keep"
    (is (nil? (sbs/line->delta "STA,,1,1,A1B2C3,1,,,,,,,,,,,,,,,,"))))
  (testing "a garbage line is not SBS-shaped and is dropped"
    (is (nil? (sbs/line->delta "GARBAGE not a real message"))))
  (testing "an empty line is dropped"
    (is (nil? (sbs/line->delta ""))))
  (testing "a MSG line with no ICAO is worthless — the accumulator keys on it"
    (is (nil? (sbs/line->delta
                "MSG,3,1,1,,1,,,,,,,,,39.9,-104.6,,,,,,"))))
  (testing "a MSG line with a garbage ICAO is dropped"
    (is (nil? (sbs/line->delta
                "MSG,3,1,1,ZZZZZZ,1,,,,,,37000,,,,,,,,,,")))))

(deftest keeps-the-aircraft-when-only-a-field-is-bad
  (testing "a truncated line still yields the aircraft when its ICAO survives"
    (is (= #:aircraft{:icao "d00d1e"} (sbs/line->delta "MSG,3,,,D00D1E"))))
  (testing "an out-of-range latitude drops the position, never the aircraft"
    (let [delta (sbs/line->delta
                  "MSG,3,1,1,E5E5E5,1,,,,,,12000,,,199.0,-104.6,,,,,,")]
      (is (= 12000 (:aircraft/altitude-ft delta)))
      (is (not (contains? delta :aircraft/position)))))
  (testing "an implausible altitude costs the field, not the aircraft"
    (let [delta (sbs/line->delta
                  "MSG,3,1,1,A1B2C3,1,,,,,,999999,,,39.8,-104.6,,,,,,")]
      (is (not (contains? delta :aircraft/altitude-ft)))
      (is (contains? delta :aircraft/position))))
  (testing "a non-finite numeric (NaN injection) is rejected"
    (let [delta (sbs/line->delta
                  "MSG,4,1,1,A1B2C3,1,,,,,,,NaN,268,,,,,,,,")]
      (is (not (contains? delta :aircraft/ground-speed-kt)))
      (is (= 268.0 (:aircraft/track-deg delta))))))

(deftest never-throws-on-hostile-input
  (testing "no line, however malformed, escapes the boundary as an exception"
    (doseq [line ["MSG" "MSG," "MSG,3" ",,,,,,," "MSG,3,1,1"
                  "\u0000\u0001 binary junk" "MSG,,,,,,,,,,,,,,,,,,,,,"]]
      (is (nil? (try (sbs/line->delta line) nil (catch Throwable _ :threw)))
          (str "line->delta must not throw on: " (pr-str line))))))

(defn- with-sbs-feed [lines f]
  (let [server (doto (ServerSocket.)
                 (.bind (InetSocketAddress. "127.0.0.1" 0)))
        port   (.getLocalPort server)
        conn   (future
                 (with-open [client (.accept server)]
                   (let [w (OutputStreamWriter. (.getOutputStream client)
                                                StandardCharsets/US_ASCII)]
                     (doseq [line lines] (.write w (str line "\n")))
                     (.flush w)
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
  (try (source/fetch! src) (catch ExceptionInfo _)))

(deftest streams-and-accumulates-the-fixture
  (testing "open!/fetch!/close! stream the fixture through the accumulator,
            folding fields from separate messages into per-ICAO tracks and
            dropping every hostile line at the boundary"
    (with-sbs-feed fixture-lines
      (fn [host port]
        (let [src (source/open! (sbs/->source host port))]
          (try
            (let [batch   (wait-until
                            (fn []
                              (let [b (fetch-quietly src)]
                                (when (= 4 (count b)) b))))
                  by-icao (into {} (map (juxt :aircraft/icao identity)) batch)]
              (is (some? batch)
                  "the reader accumulates the fixture within the timeout")
              (testing "a1b2c3's four messages fold into one track"
                (is (= "UAL123" (get-in by-icao ["a1b2c3" :aircraft/callsign])))
                (is (= {:geo/lat 39.8721 :geo/lon -104.6702}
                       (get-in by-icao ["a1b2c3" :aircraft/position])))
                (is (= 451.2 (get-in by-icao ["a1b2c3" :aircraft/ground-speed-kt])))
                (is (= "7700" (get-in by-icao ["a1b2c3" :aircraft/squawk]))))
              (testing "only real aircraft reach the picture"
                (is (= #{"a1b2c3" "abcdef" "d00d1e" "e5e5e5"} (set (keys by-icao)))))
              (testing "a bad field costs the field, never the aircraft"
                (is (not (contains? (by-icao "e5e5e5") :aircraft/position)))
                (is (= 12000 (get-in by-icao ["e5e5e5" :aircraft/altitude-ft])))))
            (finally (source/close! src))))))))

(def ^:private fixture-message-count 8)

(deftest metadata-reports-the-decoded-message-count
  (contract/assert-metadata-reports-message-count!
    sbs/->source
    (fn [body] (with-sbs-feed fixture-lines body))
    {}
    fixture-message-count))

(deftest the-counter-differences-into-a-message-rate
  (testing "adsb.stats turns two samples of the Source's counter into a
            per-second rate exactly as it does the poll Source's — the
            record IS the reader state, so messages can be folded straight
            into it without a socket"
    (let [src    (sbs/->source "127.0.0.1" 30003)
          acc    (stats/create)
          sample (fn [now-ms]
                   (:stats/message-rate
                     (stats/compute! acc {:picture  {}
                                          :now-ms   now-ms
                                          :messages (:messages
                                                      (source/metadata src))})))
          t0     1720713600000]
      (is (nil? (sample t0)))
      (dotimes [_ 12]
        (tcp/accumulate! src {:aircraft/icao "a1b2c3"} t0))
      (is (= 12 (sample (+ t0 1000))))
      (is (= 0 (sample (+ t0 2000)))))))

(deftest quiet-feed-returns-a-snapshot-not-a-throw
  (contract/assert-quiet-feed-returns-a-snapshot!
    sbs/->source
    (fn [body] (with-sbs-feed [] body))))

(deftest fetch-throws-while-unreachable
  (contract/assert-fetch-throws-while-unreachable! sbs/->source))

(def ^:private identification-line
  "MSG,1,1,1,A1B2C3,1,,,,,UAL123  ,,,,,,,,,,,")

(def ^:private position-line
  "MSG,3,1,1,A1B2C3,1,,,,,,37000,,,39.8721,-104.6702,,,,,,")

(deftest on-delta-receives-the-full-merged-aircraft
  (testing "the hook fires per accumulated message with the aircraft's
            POST-MERGE state — the second call carries the first
            message's fields too, proving the event unit is the merged
            aircraft, never the bare field delta"
    (let [heard (atom [])]
      (with-sbs-feed [identification-line position-line]
        (fn [host port]
          (let [src (source/open!
                      (sbs/->source host port
                                    {:clock    (constantly 42)
                                     :on-delta (fn [aircraft now-ms]
                                                 (swap! heard conj
                                                        [aircraft now-ms]))}))]
            (try
              (is (some? (wait-until #(= 2 (count @heard)))))
              (let [[[first-aircraft _] [second-aircraft now-ms]] @heard]
                (is (= "UAL123" (:aircraft/callsign first-aircraft)))
                (is (= {:aircraft/icao           "a1b2c3"
                        :aircraft/callsign       "UAL123"
                        :aircraft/position       #:geo{:lat 39.8721
                                                       :lon -104.6702}
                        :aircraft/altitude-ft    37000
                        :aircraft/seen-at-ms     42
                        :aircraft/position-at-ms 42}
                       second-aircraft))
                (is (= 42 now-ms)))
              (finally (source/close! src)))))))))

(def ^:private teleported-position-line
  "MSG,3,1,1,A1B2C3,1,,,,,,37000,,,39.8721,-99.2000,,,,,,")

(def ^:private velocity-line
  "MSG,4,1,1,A1B2C3,1,,,,,,,451.2,268.5,,,-1216,,,,,")

(deftest a-teleporting-track-is-flagged-on-the-delta-path
  (testing "an impossible jump is flagged as the message folds in, so the
            aircraft the hook hands the broadcaster carries
            position-suspect — the streaming fast lane never consults the
            state store, so an unflagged delta would be a spoof broadcast
            as a clean track"
    (let [heard (atom [])
          ticks (atom 0)]
      (with-sbs-feed [position-line teleported-position-line velocity-line]
        (fn [host port]
          (let [src (source/open!
                      (sbs/->source host port
                                    {:clock    #(* 1000 (swap! ticks inc))
                                     :on-delta (fn [aircraft _now-ms]
                                                 (swap! heard conj aircraft))}))]
            (try
              (is (some? (wait-until #(= 3 (count @heard)))))
              (let [[origin jumped after] @heard]
                (is (not (contains? origin :aircraft/position-suspect?)))
                (is (true? (:aircraft/position-suspect? jumped)))
                (is (= {:geo/lat 39.8721 :geo/lon -99.2}
                       (:aircraft/position jumped)))
                (testing "and the flag SURVIVES the next upsert — a client
                          upsert is a full-state replacement, so an
                          unflagged velocity message would erase the
                          indicator the flag exists to raise"
                  (is (true? (:aircraft/position-suspect? after)))
                  (is (= 451.2 (:aircraft/ground-speed-kt after)))))
              (finally (source/close! src)))))))))

(def ^:private moved-on-position-line
  "MSG,3,1,1,A1B2C3,1,,,,,,37000,,,40.0721,-104.6702,,,,,,")

(deftest a-legitimate-reception-gap-does-not-flag
  (testing "~12 nm covered across 100 s of silence — a hill, a banked
            turn, an antenna null — is ordinary flight and must not flag:
            the previous entry's stamp is when it was HEARD, not when the
            gap ended (adsb-0g0). The gap stays inside the age-out
            threshold on purpose, so the previous entry is still there to
            be measured against rather than swept away unmeasured."
    (let [heard  (atom [])
          gap-ms 100000
          clocks (atom [1000 (+ 1000 gap-ms)])]
      (with-sbs-feed [position-line moved-on-position-line]
        (fn [host port]
          (let [src (source/open!
                      (sbs/->source host port
                                    {:clock    #(let [[t] @clocks]
                                                  (swap! clocks rest)
                                                  t)
                                     :on-delta (fn [aircraft _now-ms]
                                                 (swap! heard conj aircraft))}))]
            (try
              (is (some? (wait-until #(= 2 (count @heard)))))
              (let [aircraft (second @heard)]
                (is (= (+ 1000 gap-ms) (:aircraft/seen-at-ms aircraft)))
                (is (not (contains? aircraft :aircraft/position-suspect?))))
              (finally (source/close! src)))))))))

(deftest a-throwing-on-delta-hook-never-kills-the-reader
  (testing "a hook that throws costs its own notification, not the
            connection: the reader keeps consuming and the picture keeps
            accumulating"
    (with-sbs-feed [identification-line position-line]
      (fn [host port]
        (let [src (source/open!
                    (sbs/->source host port
                                  {:on-delta (fn [_aircraft _now-ms]
                                               (throw (ex-info "broken hook" {})))}))]
          (try
            (let [batch (wait-until
                          (fn []
                            (let [b (fetch-quietly src)]
                              (when (some :aircraft/position b) b))))]
              (is (some? batch))
              (is (= "UAL123" (:aircraft/callsign (first batch)))))
            (finally (source/close! src))))))))

(defn- with-restarting-sbs-feed [next-lines! f]
  (let [server  (doto (ServerSocket.)
                  (.bind (InetSocketAddress. "127.0.0.1" 0)))
        port    (.getLocalPort server)
        serving (atom true)
        accept  (future
                  (while @serving
                    (let [client (.accept server)
                          w      (OutputStreamWriter. (.getOutputStream client)
                                                      StandardCharsets/US_ASCII)]
                      (doseq [line (next-lines!)] (.write w (str line "\n")))
                      (.flush w))))]
    (try
      (f "127.0.0.1" port)
      (finally
        (reset! serving false)
        (future-cancel accept)
        (.close server)))))

(deftest silence-drops-the-socket-and-reconnects
  (testing "a connected-but-silent socket past :idle-timeout-ms is treated as
            dead via SO_TIMEOUT: the reader drops it and reconnects, and the
            fresh connection's aircraft reaches the picture — the stalled feed
            case, uniform with the websocket transport"
    (let [connections (atom 0)
          next-lines! (fn []
                        (if (= 1 (swap! connections inc))
                          ["MSG,1,1,1,AAAAAA,1,,,,,ALPHA123 ,,,,,,,,,,,"]
                          ["MSG,1,1,1,BBBBBB,1,,,,,BRAVO123 ,,,,,,,,,,,"]))]
      (with-restarting-sbs-feed next-lines!
        (fn [host port]
          (let [src (source/open!
                      (sbs/->source host port
                                    {:clock           (constantly 0)
                                     :idle-timeout-ms 200
                                     :reconnect-ms    50}))]
            (try
              (let [picture (wait-until
                              (fn []
                                (let [planes (->> (fetch-quietly src)
                                                  (map (juxt :aircraft/icao identity))
                                                  (into {}))]
                                  (when (contains? planes "bbbbbb") planes))))]
                (is (some? picture))
                (is (< 1 @connections)))
              (finally (source/close! src)))))))))
