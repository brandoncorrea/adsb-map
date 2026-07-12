(ns adsb.stream-test
  "The SSE client, driven without a network. The seam
  adsb.stream.source/connect! is faked to capture the callbacks the client
  wires, so a test can play the part of the server — deliver a frame, fire an
  error with a chosen ready-state — and watch app-db and the subscriptions
  respond. No EventSource, no timer, no clock: schedule-reconnect! is faked
  too, so the reconnect state machine runs synchronously. See
  docs/testing-setup.md, \"The Map Seam\" — this is the same idea for the
  stream."
  (:require
    [adsb.fixtures :as fixtures]
    [adsb.stream :as stream]
    [adsb.stream.source :as source]
    [adsb.wire :as wire]
    [cljs.test :refer-macros [deftest is testing]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]))

(defn- frame
  "One SSE frame's `data` string: the full picture built from a list of
  domain aircraft, serialized exactly as the server would (adsb.wire ->
  JSON)."
  ([aircraft] (frame aircraft nil nil))
  ([aircraft stats] (frame aircraft stats nil))
  ([aircraft stats feeder]
   (let [picture (into {} (map (juxt :aircraft/icao identity)) aircraft)]
     (js/JSON.stringify
       (clj->js (wire/picture->wire picture stats feeder 1720713600000))))))

(defn- fake-connection []
  (reify source/Connection
    (close! [_] nil)))

(deftest snapshot-populates-picture
  (testing "a snapshot frame lands as the decoded, namespaced domain picture"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)]
        (with-redefs [source/connect! (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (fn [_ms] nil)]
          (rf/dispatch [:stream/start])
          ((:on-open @!cbs))
          ((:on-frame @!cbs) (frame [fixtures/ups-2717 fixtures/squawking-7700]))

          (let [pic  @(rf/subscribe [:aircraft/picture])
                icao (:aircraft/icao fixtures/ups-2717)]
            (is (= #{(:aircraft/icao fixtures/ups-2717)
                     (:aircraft/icao fixtures/squawking-7700)}
                   (set (keys pic)))
                "both aircraft land, keyed by icao")
            (is (= icao (get-in pic [icao :aircraft/icao]))
                "decoded to namespaced domain keys, not raw wire keys")
            (is (contains? (get pic icao) :aircraft/position)
                "position round-trips through the shared wire codec")))))))

(deftest stats-land-in-app-db
  (testing "the envelope's stats scalars decode onto :stats/session, and
            an absent scalar stays absent"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)]
        (with-redefs [source/connect! (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (fn [_ms] nil)]
          (rf/dispatch [:stream/start])
          ((:on-frame @!cbs)
           (frame [fixtures/ups-2717] {:stats/max-range-km 312
                                       :stats/message-rate 148}))
          (is (= {:stats/max-range-km 312 :stats/message-rate 148}
                 @(rf/subscribe [:stats/session]))
              "both scalars land, namespaced")

          ;; A later frame with no max range (receiver position gone):
          ;; the scalar drops out, never zeroes.
          ((:on-frame @!cbs)
           (frame [fixtures/ups-2717] {:stats/message-rate 90}))
          (is (= {:stats/message-rate 90} @(rf/subscribe [:stats/session]))
              "absent max range is omitted, not defaulted"))))))

(deftest feeder-status-lands-in-app-db
  (testing "the envelope's feeder status decodes onto :feeder/status, and an
            absent status stays nil (unknown)"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)]
        (with-redefs [source/connect! (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (fn [_ms] nil)]
          (rf/dispatch [:stream/start])
          ((:on-frame @!cbs)
           (frame [fixtures/ups-2717] nil {:feeder/status          :ok
                                           :feeder/last-success-ms 1720713599000}))
          (is (= :ok @(rf/subscribe [:feeder/status]))
              "the reported status lands, as a keyword")
          (is (= :ok @(rf/subscribe [:feeder/health]))
              "and the derived health agrees while the stream is live")

          ;; A later frame the server sends with no named status: the raw
          ;; status drops to nil and the derived health goes :unknown.
          ((:on-frame @!cbs) (frame [fixtures/ups-2717]))
          (is (nil? @(rf/subscribe [:feeder/status]))
              "an absent status is nil, never a stale keyword")
          (is (= :unknown @(rf/subscribe [:feeder/health]))))))))

(deftest feeder-health-is-unknowable-off-a-live-stream
  (testing "a feeder claim is only trustworthy while the stream is live: once
            the stream drops, the derived health goes :unknown rather than
            asserting a stale :ok"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)]
        (with-redefs [source/connect! (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (fn [_ms] nil)]
          (rf/dispatch [:stream/start])
          ((:on-open @!cbs))
          ((:on-frame @!cbs) (frame [fixtures/ups-2717] nil {:feeder/status :ok}))
          (is (= :ok @(rf/subscribe [:feeder/health])) "live: the claim stands")

          ;; The stream drops repeatedly and goes :down; the last feeder claim
          ;; is now stale, so the derived health must not keep asserting it.
          (dotimes [_ 4] ((:on-error @!cbs) :closed))
          (is (= :down @(rf/subscribe [:stream/connection])))
          (is (= :unknown @(rf/subscribe [:feeder/health]))
              "a stale :ok over a dead stream is suppressed"))))))

(deftest update-replaces-wholesale
  (testing "an update is a full picture, never a merge: a dropped aircraft is gone"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)]
        (with-redefs [source/connect! (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (fn [_ms] nil)]
          (rf/dispatch [:stream/start])
          ((:on-frame @!cbs) (frame [fixtures/ups-2717 fixtures/squawking-7700]))
          (is (= 2 (count @(rf/subscribe [:aircraft/picture]))))

          ((:on-frame @!cbs) (frame [fixtures/ups-2717]))
          (is (= #{(:aircraft/icao fixtures/ups-2717)}
                 (set (keys @(rf/subscribe [:aircraft/picture]))))
              "the aircraft absent from the newest frame is gone"))))))

(deftest connection-state-tracks-reality
  (testing ":live on open, :reconnecting while the browser retries, :down after repeated failure, healing on recovery"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)]
        (with-redefs [source/connect! (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (fn [_ms] nil)]
          (rf/dispatch [:stream/start])
          ((:on-open @!cbs))
          (is (= :live @(rf/subscribe [:stream/connection])))

          ;; A transient drop: the EventSource is CONNECTING — its own
          ;; auto-reconnect owns this. We only reflect it.
          ((:on-error @!cbs) :connecting)
          (is (= :reconnecting @(rf/subscribe [:stream/connection])))

          ;; The source is CLOSED and will not revive itself — ours now.
          ;; Hopeful at first, honest as failures pile up.
          ((:on-error @!cbs) :closed)
          (is (= :reconnecting @(rf/subscribe [:stream/connection])))
          ((:on-error @!cbs) :closed)
          ((:on-error @!cbs) :closed)
          (is (= :reconnecting @(rf/subscribe [:stream/connection]))
              "still hopeful up to the threshold")
          ((:on-error @!cbs) :closed)
          (is (= :down @(rf/subscribe [:stream/connection]))
              "past the threshold, surface :down — but keep retrying")

          ;; The server came back: a fresh open heals it, no page reload.
          ((:on-open @!cbs))
          (is (= :live @(rf/subscribe [:stream/connection])))

          ;; And the failure count reset — one fresh failure is not :down.
          ((:on-error @!cbs) :closed)
          (is (= :reconnecting @(rf/subscribe [:stream/connection]))
              "attempts reset on recovery"))))))

(deftest backoff-grows
  (testing "each consecutive CLOSED error schedules a longer reconnect"
    (rf-test/run-test-sync
      (let [!cbs    (atom nil)
            !delays (atom [])]
        (with-redefs [source/connect! (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (fn [ms] (swap! !delays conj ms))]
          (rf/dispatch [:stream/start])
          (dotimes [_ 4] ((:on-error @!cbs) :closed))
          (is (= [1000 2000 4000 8000] @!delays)
              "exponential from 1s, doubling each attempt")
          (is (apply < @!delays) "strictly growing")))))

  (testing "backoff-ms doubles from the base and is capped at the ceiling"
    (is (= 1000 (stream/backoff-ms 1)))
    (is (= 2000 (stream/backoff-ms 2)))
    (is (= 4000 (stream/backoff-ms 3)))
    (is (= stream/max-backoff-ms (stream/backoff-ms 100))
        "a long outage never schedules a retry further off than the cap")))
