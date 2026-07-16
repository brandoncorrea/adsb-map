(ns adsb.stream-test
  (:require [adsb.aircraft :as aircraft]
            [adsb.corejs :as cjs]
            [adsb.fixtures :as fixtures]
            [adsb.stream :as stream]
            [adsb.stream.source :as source]
            [adsb.wire :as wire]
            [clojure.test :refer-macros [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]))

(def ^:const at-ms 1720713600000)

(defn- frame [aircraft]
  (let [picture (into {} (map (juxt :aircraft/icao identity)) aircraft)]
    (js-invoke js/JSON "stringify" (clj->js (wire/picture->wire picture at-ms)))))

(defn- stats-frame
  ([stats] (stats-frame stats nil))
  ([stats feeder]
   (js-invoke js/JSON "stringify" (clj->js (wire/stats-event->wire stats feeder at-ms)))))

(defn- upsert-frame [one-aircraft]
  (js-invoke js/JSON "stringify" (clj->js (wire/upsert->wire one-aircraft at-ms))))

(defn- fake-connection []
  (reify source/Connection
    (close! [_])))

(deftest snapshot-populates-picture
  (testing "a snapshot frame lands as the decoded, namespaced domain picture"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)]
        (with-redefs [source/connect!            (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (constantly nil)]
          (rf/dispatch [:stream/start])
          ((:on-open @!cbs))
          ((:on-frame @!cbs) (frame [fixtures/ups-2717 fixtures/squawking-7700]))

          (let [pic  @(rf/subscribe [:aircraft/picture])
                icao (:aircraft/icao fixtures/ups-2717)]
            (is (= #{(:aircraft/icao fixtures/ups-2717)
                     (:aircraft/icao fixtures/squawking-7700)}
                   (set (keys pic))))
            (is (= icao (get-in pic [icao :aircraft/icao])))
            (is (contains? (get pic icao) :aircraft/position))))))))

(deftest stats-land-in-app-db
  (testing "the stats event's scalars decode onto :stats/session, and
            an absent scalar stays absent"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)]
        (with-redefs [source/connect!            (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (constantly nil)]
          (rf/dispatch [:stream/start])
          ((:on-stats @!cbs)
           (stats-frame {:stats/max-range-km 312
                         :stats/message-rate 148}))
          (is (= {:stats/max-range-km 312 :stats/message-rate 148}
                 @(rf/subscribe [:stats/session])))

          ((:on-stats @!cbs) (stats-frame {:stats/message-rate 90}))
          (is (= {:stats/message-rate 90} @(rf/subscribe [:stats/session]))))))))

(deftest feeder-status-lands-in-app-db
  (testing "the stats event's feeder status decodes onto :feeder/status, and
            an absent status stays nil (unknown)"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)]
        (with-redefs [source/connect!            (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (constantly nil)]
          (rf/dispatch [:stream/start])
          ((:on-stats @!cbs)
           (stats-frame nil {:feeder/status          :ok
                             :feeder/last-success-ms 1720713599000}))
          (is (= :ok @(rf/subscribe [:feeder/status])))
          (is (= :ok @(rf/subscribe [:feeder/health])))

          ((:on-stats @!cbs) (stats-frame nil))
          (is (nil? @(rf/subscribe [:feeder/status])))
          (is (= :unknown @(rf/subscribe [:feeder/health]))))))))

(deftest feeder-health-is-unknowable-off-a-live-stream
  (testing "a feeder claim is only trustworthy while the stream is live: once
            the stream drops, the derived health goes :unknown rather than
            asserting a stale :ok"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)]
        (with-redefs [source/connect!            (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (constantly nil)]
          (rf/dispatch [:stream/start])
          ((:on-open @!cbs))
          ((:on-stats @!cbs) (stats-frame nil {:feeder/status :ok}))
          (is (= :ok @(rf/subscribe [:feeder/health])))

          (dotimes [_ 4] ((:on-error @!cbs) :closed))
          (is (= :down @(rf/subscribe [:stream/connection])))
          (is (= :unknown @(rf/subscribe [:feeder/health]))))))))

(deftest update-replaces-wholesale
  (testing "an update is a full picture, never a merge: a dropped aircraft is gone"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)]
        (with-redefs [source/connect!            (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (constantly nil)]
          (rf/dispatch [:stream/start])
          ((:on-frame @!cbs) (frame [fixtures/ups-2717 fixtures/squawking-7700]))
          (is (= 2 (count @(rf/subscribe [:aircraft/picture]))))

          ((:on-frame @!cbs) (frame [fixtures/ups-2717]))
          (is (= #{(:aircraft/icao fixtures/ups-2717)}
                 (set (keys @(rf/subscribe [:aircraft/picture]))))))))))

(deftest upsert-merges-into-the-picture
  (testing "an `aircraft` event merges its one full merged aircraft into the
            picture by icao — updating the aircraft it names, touching no
            other, and adding one not yet seen (adsb-jpf)"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)]
        (with-redefs [source/connect!            (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (constantly nil)]
          (rf/dispatch [:stream/start])
          ((:on-frame @!cbs) (frame [fixtures/ups-2717
                                     fixtures/squawking-7700]))
          (let [icao  (:aircraft/icao fixtures/ups-2717)
                moved (assoc fixtures/ups-2717 :aircraft/altitude-ft 33775)]
            ((:on-aircraft @!cbs) (upsert-frame moved))
            (let [pic @(rf/subscribe [:aircraft/picture])]
              (is (= 33775 (get-in pic [icao :aircraft/altitude-ft])))
              (is (contains? pic (:aircraft/icao fixtures/squawking-7700)))))

          ((:on-aircraft @!cbs) (upsert-frame fixtures/on-the-ground))
          (is (contains? @(rf/subscribe [:aircraft/picture])
                         (:aircraft/icao fixtures/on-the-ground)))

          (is (= :live @(rf/subscribe [:stream/connection]))))))))

(deftest stats-frames-prune-aged-out-aircraft
  (testing "each stats frame ages the picture out on the client's own clock
            (the shared adsb.aircraft threshold) — the removal mechanism on
            a streaming deployment, where no recurring full-picture frame
            exists to drop a silent aircraft (adsb-jpf)"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)
            t0   1720713600000]
        (with-redefs [source/connect!            (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (constantly nil)
                      cjs/now-ms                 (constantly (+ t0 aircraft/age-out-threshold-ms 1))]
          (rf/dispatch [:stream/start])
          (let [silent (assoc fixtures/ups-2717 :aircraft/seen-at-ms t0)
                fresh  (assoc fixtures/squawking-7700
                         :aircraft/seen-at-ms
                         (+ t0 aircraft/age-out-threshold-ms))]
            ((:on-frame @!cbs) (frame [silent fresh]))
            (is (= 2 (count @(rf/subscribe [:aircraft/picture]))))

            ((:on-stats @!cbs) (stats-frame {:stats/message-rate 90}))
            (is (= #{(:aircraft/icao fresh)}
                   (set (keys @(rf/subscribe [:aircraft/picture])))))))))))

(deftest connection-state-tracks-reality
  (testing ":live on open, :reconnecting while the browser retries, :down after repeated failure, healing on recovery"
    (rf-test/run-test-sync
      (let [!cbs (atom nil)]
        (with-redefs [source/connect!            (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (constantly nil)]
          (rf/dispatch [:stream/start])
          ((:on-open @!cbs))
          (is (= :live @(rf/subscribe [:stream/connection])))

          ((:on-error @!cbs) :closed)
          (is (= :reconnecting @(rf/subscribe [:stream/connection])))
          ((:on-error @!cbs) :closed)
          ((:on-error @!cbs) :closed)
          (is (= :reconnecting @(rf/subscribe [:stream/connection])))
          ((:on-error @!cbs) :closed)
          (is (= :down @(rf/subscribe [:stream/connection])))

          ((:on-open @!cbs))
          (is (= :live @(rf/subscribe [:stream/connection])))

          ((:on-error @!cbs) :closed)
          (is (= :reconnecting @(rf/subscribe [:stream/connection]))))))))

(deftest a-backend-that-is-simply-down-says-so
  (testing "connection refused never leaves CONNECTING — the browser retries that
            source itself, firing one error per failed attempt. Those errors are
            failures and they count: the status must escalate to :down on the same
            threshold as a CLOSED source, or the most common outage of all reads
            :reconnecting forever (adsb-xgc)"
    (rf-test/run-test-sync
      (let [!cbs    (atom nil)
            !opens  (atom 0)
            !delays (atom [])]
        (with-redefs [source/connect!            (fn [_url cbs]
                                                   (reset! !cbs cbs)
                                                   (swap! !opens inc)
                                                   (fake-connection))
                      stream/schedule-reconnect! (fn [ms] (swap! !delays conj ms))]
          (rf/dispatch [:stream/start])
          (is (= 1 @!opens))

          ((:on-error @!cbs) :connecting)
          (is (= :reconnecting @(rf/subscribe [:stream/connection])))
          (dotimes [_ 3] ((:on-error @!cbs) :connecting))
          (is (= :down @(rf/subscribe [:stream/connection])))

          (is (= [] @!delays))
          (is (= 1 @!opens))

          ((:on-open @!cbs))
          (is (= :live @(rf/subscribe [:stream/connection])))
          ((:on-error @!cbs) :connecting)
          (is (= :reconnecting @(rf/subscribe [:stream/connection]))))))))

(deftest silence-is-counted-in-frames-not-clocked
  (testing "a positive rate is life: the count resets"
    (is (= 0 (stream/silent-frames 9 148)))
    (is (= 0 (stream/silent-frames 0 1))))

  (testing "a rate of ZERO is a fact, and it accumulates — this is the evidence
            that the radio has gone deaf behind a container that still answers
            its poll"
    (is (= 1 (stream/silent-frames nil 0)))
    (is (= 1 (stream/silent-frames 0 0)))
    (is (= 10 (stream/silent-frames 9 0))))

  (testing "a rate of NIL is not a fact — the feeder reports no counter, or this
            is the first sample and there is nothing to difference. An unknown is
            never evidence of silence: absent is not zero, the rule the whole
            domain keeps"
    (is (= 0 (stream/silent-frames 9 nil))))

  (testing "the threshold is small, because the fact is urgent — but it exists,
            because the light must not blink. Stats frames arrive every ~10 s,
            so each unit here is ~10 s of wall clock"
    (is (pos? stream/silent-after-frames))
    (is (<= stream/silent-after-frames 6))))

(deftest backoff-grows
  (testing "each consecutive CLOSED error schedules a longer reconnect"
    (rf-test/run-test-sync
      (let [!cbs    (atom nil)
            !delays (atom [])]
        (with-redefs [source/connect!            (fn [_url cbs] (reset! !cbs cbs) (fake-connection))
                      stream/schedule-reconnect! (fn [ms] (swap! !delays conj ms))]
          (rf/dispatch [:stream/start])
          (dotimes [_ 4] ((:on-error @!cbs) :closed))
          (is (= [1000 2000 4000 8000] @!delays))
          (is (apply < @!delays))))))

  (testing "backoff-ms doubles from the base and is capped at the ceiling"
    (is (= 1000 (stream/backoff-ms 1)))
    (is (= 2000 (stream/backoff-ms 2)))
    (is (= 4000 (stream/backoff-ms 3)))
    (is (= stream/max-backoff-ms (stream/backoff-ms 100)))))
