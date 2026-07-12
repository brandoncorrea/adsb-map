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
    (let [{:keys [aircraft]} (wire/picture->wire picture server-stats
                                                 captured-at-ms)]
      (is (seq aircraft))
      (is (every? wire-keys (mapcat keys aircraft))))))

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

(deftest picture->wire-envelope
  (testing "the frame envelope carries the build instant, the stats map,
            and every aircraft in the picture"
    (let [{:keys [at stats aircraft]} (wire/picture->wire picture server-stats
                                                          captured-at-ms)]
      (is (= captured-at-ms at))
      (is (= {:max-range-km 312 :message-rate 148} stats))
      (is (= (count picture) (count aircraft)))))

  (testing "a nil stats argument yields an empty stats map"
    (is (= {} (:stats (wire/picture->wire picture nil captured-at-ms))))))

(deftest wire-round-trip
  (testing "a domain aircraft survives the round trip, minus the
            deliberately withheld receiver-relative rssi"
    (doseq [[icao domain-aircraft] picture]
      (is (= (dissoc domain-aircraft :aircraft/rssi)
             (wire/wire->aircraft (wire/aircraft->wire domain-aircraft)))
          icao)))

  (testing "a decoded frame envelope becomes the picture again, keyed
            by icao"
    (let [envelope (wire/picture->wire picture server-stats captured-at-ms)]
      (is (= (update-vals picture #(dissoc % :aircraft/rssi))
             (wire/wire->picture envelope)))))

  (testing "the stats scalars survive the round trip, minus the counts
            and receiver-relative fields the wire never carried"
    (let [envelope (wire/picture->wire picture server-stats captured-at-ms)]
      (is (= (select-keys server-stats
                          [:stats/max-range-km :stats/message-rate])
             (wire/wire->stats envelope)))))

  (testing "an envelope with no stats decodes to an empty stats map"
    (is (= {} (wire/wire->stats (wire/picture->wire picture nil
                                                    captured-at-ms))))
    (is (= {} (wire/wire->stats {})))))
