(ns adsb.wire-test
  "The SSE wire codec — including the adsb-kbm.2 privacy mandate: no
  receiver position and no receiver-relative field may ever reach the
  wire. Serialized-frame assertions live in adsb.stream.broadcast-test;
  here the projection itself is pinned down on both platforms."
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.fixtures :as fixtures]
    [adsb.wire :as wire]
    [clojure.test :refer [deftest testing is]]))

(def ^:private captured-at-ms 1720713600000)

(def ^:private picture
  "The cast merged into a picture, exactly as adsb.state would hold it —
  every aircraft carries :aircraft/seen-at-ms."
  (aircraft/merge-batch {} fixtures/all captured-at-ms))

(def ^:private wire-keys
  "Every key aircraft->wire may emit — the documented allowlist."
  #{:icao :callsign :lat :lon :altitude :on-ground :squawk
    :ground-speed :track :baro-rate :seen-at :position-suspect :mlat})

(def ^:private stats-wire-keys
  "Every key stats->wire may emit — the documented allowlist. Two scalars,
  no position, no counts (the browser derives those)."
  #{:max-range-km :message-rate})

(def ^:private feeder-wire-keys
  "Every key feeder->wire may emit — the documented allowlist. A named
  status and a timestamp; never the internal error string."
  #{:status :last-success})

(def ^:private server-feeder
  "A feeder status map (adsb.ingest.poll/status) as picture->wire receives
  it — carrying the free-form :feeder/last-error, to prove the allowlist
  drops it (an error message could leak a path or hostname)."
  {:feeder/status          :down
   :feeder/last-success-ms 1720713599000
   :feeder/last-error      "connect timed out: dietpi.local:8100"})

(def ^:private server-stats
  "A server stats map (adsb.stats) as picture->wire receives it — counts
  included, to prove they are dropped, and a receiver-relative field
  hypothetically smuggled in, to prove the allowlist excludes it."
  {:stats/aircraft-count   5
   :stats/positioned-count 3
   :stats/max-range-km     312
   :stats/message-rate     148
   :stats/receiver-lat     27.94
   :stats/receiver-lon     -82.45})

(deftest aircraft->wire
  (testing "projects the happy-path aircraft onto flat unqualified keys"
    (is (= {:icao         "abc0e4"
            :callsign     "UPS2717"
            :lat          27.961166
            :lon          -83.975953
            :altitude     34775
            :squawk       "6040"
            :ground-speed 450.5
            :track        97.14
            :baro-rate    -960}
           (wire/aircraft->wire fixtures/ups-2717))))

  (testing "an on-the-tarmac aircraft gets on-ground, never an altitude"
    (let [wire-aircraft (wire/aircraft->wire fixtures/on-the-ground)]
      (is (true? (:on-ground wire-aircraft)))
      (is (not (contains? wire-aircraft :altitude)))))

  (testing "absent facts stay absent — never defaulted to zero"
    (let [wire-aircraft (wire/aircraft->wire fixtures/never-positioned)]
      (is (not (contains? wire-aircraft :lat)))
      (is (not (contains? wire-aircraft :lon)))
      (is (not (contains? wire-aircraft :ground-speed)))))

  (testing "the seen-at aging timestamp belongs on the wire"
    (let [wire-aircraft (wire/aircraft->wire (get picture "abc0e4"))]
      (is (number? (:seen-at wire-aircraft)))))

  (testing "a suspect position is surfaced, not hidden"
    (let [suspect (assoc fixtures/ups-2717 :aircraft/position-suspect? true)]
      (is (true? (:position-suspect (wire/aircraft->wire suspect))))))

  (testing "an MLAT-derived position is surfaced as lower-confidence"
    (is (true? (:mlat (wire/aircraft->wire fixtures/mlat-derived))))
    (is (not (contains? (wire/aircraft->wire fixtures/ups-2717) :mlat)))))

(deftest receiver-privacy
  (testing "receiver-relative fields never survive the projection, even
            when an aircraft hypothetically carries them"
    (let [poisoned (assoc fixtures/ups-2717
                          :aircraft/r-dst 12.3
                          :aircraft/r-dir 250.0
                          :r_dst 12.3
                          :r_dir 250.0
                          :receiver/lat 27.94
                          :receiver/lon -82.45)]
      (is (every? wire-keys (keys (wire/aircraft->wire poisoned))))))

  (testing "rssi is a measurement of the receiver and stays off the wire"
    (is (not (contains? (wire/aircraft->wire fixtures/ups-2717) :rssi))))

  (testing "the whole cast, through the real pipeline, emits only
            allowlisted keys"
    (let [{:keys [aircraft]} (wire/picture->wire picture captured-at-ms)]
      (is (seq aircraft))
      (is (every? wire-keys (mapcat keys aircraft)))))

  (testing "an upsert envelope is held to the same allowlist"
    (let [poisoned (assoc fixtures/ups-2717 :aircraft/r-dst 12.3)]
      (is (every? wire-keys
                  (keys (:aircraft (wire/upsert->wire poisoned
                                                      captured-at-ms))))))))

(deftest stats->wire-allowlist
  (testing "only the two documented scalars survive — counts and any
            receiver-relative field are dropped by construction"
    (let [wire-stats (wire/stats->wire server-stats)]
      (is (= {:max-range-km 312 :message-rate 148} wire-stats))
      (is (every? stats-wire-keys (keys wire-stats)))
      (is (not (contains? wire-stats :aircraft-count)))
      (is (not (contains? wire-stats :positioned-count)))))

  (testing "absent scalars are omitted, never zeroed"
    (is (= {:message-rate 148}
           (wire/stats->wire {:stats/message-rate 148})))
    (is (= {:max-range-km 312}
           (wire/stats->wire {:stats/max-range-km 312})))
    (is (= {} (wire/stats->wire nil))
        "no stats at all yields an empty map, not defaulted numbers")))

(deftest feeder->wire-allowlist
  (testing "only the named status and the timestamp survive — the free-form
            error string is dropped by construction (it could leak a path)"
    (let [wire-feeder (wire/feeder->wire server-feeder)]
      (is (= {:status "down" :last-success 1720713599000} wire-feeder))
      (is (every? feeder-wire-keys (keys wire-feeder)))
      (is (not (contains? wire-feeder :last-error)))))

  (testing "each named status maps to its wire string"
    (is (= "ok" (:status (wire/feeder->wire {:feeder/status :ok}))))
    (is (= "starting" (:status (wire/feeder->wire {:feeder/status :starting}))))
    (is (= "down" (:status (wire/feeder->wire {:feeder/status :down})))))

  (testing "a status not on the allowlist yields no status key"
    (is (= {} (wire/feeder->wire {:feeder/status :exploded})))
    (is (not (contains? (wire/feeder->wire {:feeder/status :exploded})
                        :status))))

  (testing "absent facts are omitted, never zeroed"
    (is (= {:status "starting"}
           (wire/feeder->wire {:feeder/status :starting}))
        "a starting feeder has no last-success yet")
    (is (= {} (wire/feeder->wire nil))
        "no feeder status at all yields an empty map")))

(deftest wire->feeder-decode
  (testing "the wire status string decodes back to its keyword"
    (is (= {:feeder/status :ok}
           (wire/wire->feeder {:feeder {:status "ok"}})))
    (is (= {:feeder/status :down :feeder/last-success-ms 1720713599000}
           (wire/wire->feeder {:feeder {:status "down"
                                        :last-success 1720713599000}}))))

  (testing "an unnamed or absent status decodes to nil (unknown), never a
            guess"
    (is (= {} (wire/wire->feeder {:feeder {:status "exploded"}})))
    (is (= {} (wire/wire->feeder {:feeder {}})))
    (is (= {} (wire/wire->feeder {})))))

(deftest picture->wire-envelope
  (testing "the frame envelope carries the build instant and every aircraft
            in the picture — and NOTHING else: aircraft data and stats never
            share a payload (adsb-jpf)"
    (let [{:keys [at aircraft] :as envelope}
          (wire/picture->wire picture captured-at-ms)]
      (is (= captured-at-ms at))
      (is (= (count picture) (count aircraft)))
      (is (= #{:at :aircraft} (set (keys envelope)))
          "no stats, no feeder — they ride the stats event alone"))))

(deftest stats-event->wire-envelope
  (testing "the stats envelope carries the build instant, the stats map, and
            the feeder map — and NO aircraft data"
    (let [{:keys [at stats feeder] :as envelope}
          (wire/stats-event->wire server-stats server-feeder captured-at-ms)]
      (is (= captured-at-ms at))
      (is (= {:max-range-km 312 :message-rate 148} stats))
      (is (= {:status "down" :last-success 1720713599000} feeder))
      (is (= #{:at :stats :feeder} (set (keys envelope))))))

  (testing "nil stats and nil feeder arguments each yield an empty map"
    (let [{:keys [stats feeder]} (wire/stats-event->wire nil nil
                                                         captured-at-ms)]
      (is (= {} stats))
      (is (= {} feeder)))))

(deftest wire-round-trip
  (testing "a domain aircraft survives the round trip, minus the
            deliberately withheld receiver-relative rssi"
    (doseq [[icao domain-aircraft] picture]
      (is (= (dissoc domain-aircraft :aircraft/rssi)
             (wire/wire->aircraft (wire/aircraft->wire domain-aircraft)))
          icao)))

  (testing "a decoded frame envelope becomes the picture again, keyed
            by icao"
    (let [envelope (wire/picture->wire picture captured-at-ms)]
      (is (= (update-vals picture #(dissoc % :aircraft/rssi))
             (wire/wire->picture envelope)))))

  (testing "an upsert envelope decodes back into its one domain aircraft —
            the same decode a full-picture entry gets, so the browser needs
            no second vocabulary (adsb-jpf)"
    (doseq [[icao domain-aircraft] picture]
      (is (= (dissoc domain-aircraft :aircraft/rssi)
             (wire/wire->upsert (wire/upsert->wire domain-aircraft
                                                   captured-at-ms)))
          icao)))

  (testing "the stats scalars survive the round trip, minus the counts
            and receiver-relative fields the wire never carried"
    (let [envelope (wire/stats-event->wire server-stats server-feeder
                                           captured-at-ms)]
      (is (= (select-keys server-stats
                          [:stats/max-range-km :stats/message-rate])
             (wire/wire->stats envelope)))))

  (testing "an envelope with no stats decodes to an empty stats map"
    (is (= {} (wire/wire->stats (wire/stats-event->wire nil nil
                                                        captured-at-ms))))
    (is (= {} (wire/wire->stats {}))))

  (testing "the feeder status survives the round trip, minus the error string
            the wire never carried"
    (let [envelope (wire/stats-event->wire server-stats server-feeder
                                           captured-at-ms)]
      (is (= {:feeder/status          :down
              :feeder/last-success-ms 1720713599000}
             (wire/wire->feeder envelope)))))

  (testing "an envelope with no feeder decodes to an empty feeder map"
    (is (= {} (wire/wire->feeder (wire/stats-event->wire nil nil
                                                         captured-at-ms))))
    (is (= {} (wire/wire->feeder {})))))
