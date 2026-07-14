(ns adsb.ingest.tcp-test
  "The shared reader's own state: the sweep that keeps the picture atom
  from growing for the life of the process (adsb-gq3).

  The transport, reconnect, and unreachable-vs-quiet behaviours are
  exercised through the Sources that use them (adsb.ingest.sbs-test,
  adsb.ingest.beast-source-test) over a local in-process socket. What is
  left to test here is the state tcp itself owns, and that needs no
  socket at all: reader-state builds the atoms, accumulate! folds into
  them, and the clock is a literal. The message counter behind
  last-metadata (adsb-3mw) is the same kind of state, counted in the same
  fold."
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.ingest.tcp :as tcp]
    [clojure.test :refer [deftest testing is]]))

(def ^:private t0 1720713600000)

(def ^:private ups-icao "a1b2c3")
(def ^:private swa-icao "d4e5f6")

(defn- reader-state
  "A reader state with no transport and no pump — accumulate! and the
  sweep touch neither, so nothing here dials anything."
  []
  (tcp/reader-state "127.0.0.1" 30005 {} (fn [_in _state]) "test-reader"))

(deftest sweep-due?
  (testing "a reader that has never swept is due at once"
    (is (tcp/sweep-due? nil t0)))

  (testing "inside the interval it is not due again"
    (is (not (tcp/sweep-due? t0 (+ t0 (dec tcp/default-sweep-interval-ms))))))

  (testing "at the interval it is due — scanning the picture once a
            minute, not once a message"
    (is (tcp/sweep-due? t0 (+ t0 tcp/default-sweep-interval-ms)))))

(deftest accumulate-sweeps-the-picture
  (testing "an aircraft that has aged out is evicted from the picture
            atom, not merely hidden from the snapshot: the reader runs
            for weeks and hears thousands of airframes a day"
    (let [{:keys [picture] :as state} (reader-state)
          returns-at (+ t0 (* 4 60 60 1000))]
      (tcp/accumulate! state {:aircraft/icao ups-icao} t0)
      (is (= [ups-icao] (keys @picture)))
      ;; Four hours later a different aircraft speaks: the sweep is due,
      ;; and the silent one goes.
      (tcp/accumulate! state {:aircraft/icao swa-icao} returns-at)
      (is (= [swa-icao] (keys @picture))
          "the aircraft silent for four hours is gone from the atom")))

  (testing "a live aircraft survives a sweep it is present for"
    (let [{:keys [picture] :as state} (reader-state)
          later (+ t0 tcp/default-sweep-interval-ms)]
      (tcp/accumulate! state {:aircraft/icao ups-icao} t0)
      (tcp/accumulate! state {:aircraft/icao swa-icao} later)
      (is (= #{ups-icao swa-icao} (set (keys @picture)))
          "a minute of silence is nowhere near the age-out line")))

  (testing "the sweep runs before the fold, so a returning aircraft's
            delta lands in an empty slot rather than on its own stale
            entry — belt and braces with accumulator/merge-delta"
    (let [{:keys [picture] :as state} (reader-state)
          returns-at (+ t0 (inc aircraft/age-out-threshold-ms)
                        tcp/default-sweep-interval-ms)]
      (tcp/accumulate! state
                       {:aircraft/icao     ups-icao
                        :aircraft/position {:geo/lat 39.0 :geo/lon -104.0}}
                       t0)
      (tcp/accumulate! state
                       {:aircraft/icao ups-icao :aircraft/callsign "UPS2717"}
                       returns-at)
      (is (not (contains? (get @picture ups-icao) :aircraft/position))
          "the hours-old position is not re-broadcast stamped heard-now")
      (is (= returns-at (get-in @picture [ups-icao :aircraft/seen-at-ms]))))))

(deftest last-metadata-counts-the-decoded-messages
  (testing "a reader that has heard nothing reports a count, not nil — the
            rate is zero-so-far, and adsb.stats needs a first sample to
            difference from"
    (is (= {:messages 0} (tcp/last-metadata (reader-state)))))

  (testing "every decoded message counts, including several from one
            aircraft — this is a message rate, not an aircraft count"
    (let [state (reader-state)]
      (tcp/accumulate! state {:aircraft/icao ups-icao} t0)
      (tcp/accumulate! state {:aircraft/icao ups-icao :aircraft/altitude-ft 37000} t0)
      (tcp/accumulate! state {:aircraft/icao swa-icao} t0)
      (is (= {:messages 3} (tcp/last-metadata state)))))

  (testing "the count is CUMULATIVE and monotonic, like the ultrafeeder
            payload's counter: it survives the sweep that empties the
            picture, since a counter that fell back would read to
            adsb.stats as a feeder restart"
    (let [{:keys [picture] :as state} (reader-state)
          returns-at (+ t0 (* 4 60 60 1000))]
      (tcp/accumulate! state {:aircraft/icao ups-icao} t0)
      (tcp/accumulate! state {:aircraft/icao swa-icao} returns-at)
      (is (= [swa-icao] (keys @picture)) "the sweep ran")
      (is (= {:messages 2} (tcp/last-metadata state))
          "the swept-away aircraft's message is still counted"))))

;; ---------------------------------------------------------------------
;; close! during an in-flight dial (adsb-12j)

(defn- wait-until
  "Busy-wait for pred, up to 2s. Returns pred's value or nil."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 2)
            (recur))))))

(defn- stuck-transport
  "A transport whose dial is IN FLIGHT until `release?` says otherwise, and
  which ignores an interrupt while it waits — the property that makes this
  worth testing at all, and the one Socket.connect actually has (it does
  not respond to the interrupt flag). Records its close! in `closed?`."
  [dialing? release? closed?]
  (fn [_host _port _opts]
    (reset! dialing? true)
    (while (not @release?)
      (Thread/onSpinWait))
    {:in     (java.io.ByteArrayInputStream. (byte-array 0))
     :close! #(reset! closed? true)}))

(deftest close!-during-a-dial-releases-the-fresh-connection
  (testing "a close! that lands mid-dial closes the connection the dial goes
            on to return, and never pumps it. close! can only close the
            connection it can see — mid-dial that is still the old one (nil)
            — and the interrupt it fires does not abort a dial, so the dial
            completes into a Source that has already been stopped. Without
            the running? re-check the reader would consume! that socket and
            hold it open until the next message or the 60s idle timeout,
            long after close! returned (adsb-12j)."
    (let [dialing? (atom false)
          release? (atom false)
          closed?  (atom false)
          consumed? (atom false)
          state    (tcp/reader-state
                     "127.0.0.1" 30005
                     {:transport (stuck-transport dialing? release? closed?)}
                     (fn [_in _state] (reset! consumed? true))
                     "test-reader")]
      (tcp/open! state)
      (is (wait-until #(deref dialing?)) "the reader is in the dial")
      (tcp/close! state)
      (reset! release? true)                 ; the dial completes anyway
      (is (wait-until #(deref closed?))
          "the connection the dial returned is closed")
      (is (not @consumed?)
          "and never pumped — the Source was already stopped")
      (is (not @(:connected? state))
          "a stopped Source never reports connected"))))
