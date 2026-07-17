(ns adsb.wire-test
  (:require [adsb.fixtures :as fixtures :refer [captured-at-ms declared-crop]]
            [adsb.picture :as picture]
            [adsb.wire :as wire]
            [clojure.test :refer [deftest is testing]]))

(def ^:private the-picture (picture/merge-batch {} fixtures/all captured-at-ms))

(def ^:private wire-keys
  #{:icao :callsign :lat :lon :altitude :on-ground :squawk :category
    :ground-speed :track :baro-rate :seen-at :position-suspect :mlat})

(def ^:private stats-wire-keys #{:max-range-km :message-rate})
(def ^:private feeder-wire-keys #{:status :last-success})

(def ^:private server-feeder
  {:feeder/status          :down
   :feeder/last-success-ms 1720713599000
   :feeder/last-error      "connect timed out: dietpi.local:8100"})

(def ^:private server-stats
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
            :category     "A5"
            :ground-speed 450.5
            :track        97.14
            :baro-rate    -960}
           (wire/aircraft->wire fixtures/ups-2717))))

  (testing "the emitter category rides the wire, and round-trips back to
            the domain — the browser keys its symbology on it (adsb-rnp)"
    (is (= "A5" (:category (wire/aircraft->wire fixtures/ups-2717))))
    (is (= "A5" (:aircraft/category
                  (wire/wire->aircraft
                    (wire/aircraft->wire fixtures/ups-2717)))))
    (is (= "A1" (:category (wire/aircraft->wire fixtures/on-the-ground)))))

  (testing "an aircraft that never transmitted a category carries none —
            omitted, not nulled, so the map reads absence and draws the
            generic plane"
    (let [uncategorized (dissoc fixtures/ups-2717 :aircraft/category)]
      (is (not (contains? (wire/aircraft->wire uncategorized) :category)))
      (is (not (contains? (wire/wire->aircraft
                            (wire/aircraft->wire uncategorized))
                          :aircraft/category)))))

  (testing "an on-the-tarmac aircraft gets on-ground, never an altitude"
    (let [wire-aircraft (wire/aircraft->wire fixtures/on-the-ground)]
      (is (:on-ground wire-aircraft))
      (is (not (contains? wire-aircraft :altitude)))))

  (testing "an explicitly airborne aircraft carries no on-ground flag — the
            wire marker stays true-or-omitted even though the domain field
            can now say false (adsb-b0w)"
    (let [wire-aircraft (wire/aircraft->wire
                          (assoc fixtures/ups-2717 :aircraft/on-ground? false))]
      (is (not (contains? wire-aircraft :on-ground)))
      (is (= 34775 (:altitude wire-aircraft)))))

  (testing "absent facts stay absent — never defaulted to zero"
    (let [wire-aircraft (wire/aircraft->wire fixtures/never-positioned)]
      (is (not (contains? wire-aircraft :lat)))
      (is (not (contains? wire-aircraft :lon)))
      (is (not (contains? wire-aircraft :ground-speed)))))

  (testing "the seen-at aging timestamp belongs on the wire"
    (let [wire-aircraft (wire/aircraft->wire (get the-picture "abc0e4"))]
      (is (number? (:seen-at wire-aircraft)))))

  (testing "a suspect position is surfaced, not hidden"
    (let [suspect (assoc fixtures/ups-2717 :aircraft/position-suspect? true)]
      (is (:position-suspect (wire/aircraft->wire suspect)))))

  (testing "an MLAT-derived position is surfaced as lower-confidence"
    (is (:mlat (wire/aircraft->wire fixtures/mlat-derived)))
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
    (let [{:keys [aircraft]} (wire/picture->wire the-picture captured-at-ms)]
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
    (is (= {:message-rate 148} (wire/stats->wire {:stats/message-rate 148})))
    (is (= {:max-range-km 312} (wire/stats->wire {:stats/max-range-km 312})))
    (is (= {} (wire/stats->wire nil)))))

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
    (is (not (contains? (wire/feeder->wire {:feeder/status :exploded}) :status))))

  (testing "absent facts are omitted, never zeroed"
    (is (= {:status "starting"} (wire/feeder->wire {:feeder/status :starting})))
    (is (= {} (wire/feeder->wire nil)))))

(deftest wire->feeder-decode
  (testing "the wire status string decodes back to its keyword"
    (is (= {:feeder/status :ok} (wire/wire->feeder {:feeder {:status "ok"}})))
    (is (= {:feeder/status :down :feeder/last-success-ms 1720713599000}
           (wire/wire->feeder {:feeder {:status       "down"
                                        :last-success 1720713599000}}))))

  (testing "an unnamed or absent status decodes to nil (unknown), never a guess"
    (is (= {} (wire/wire->feeder {:feeder {:status "exploded"}})))
    (is (= {} (wire/wire->feeder {:feeder {}})))
    (is (= {} (wire/wire->feeder {})))))

(deftest picture->wire-envelope
  (testing "the frame envelope carries the build instant and every aircraft
            in the picture — and NOTHING else: aircraft data and stats never
            share a payload (adsb-jpf)"
    (let [{:keys [at aircraft] :as envelope}
          (wire/picture->wire the-picture captured-at-ms)]
      (is (= captured-at-ms at))
      (is (= (count the-picture) (count aircraft)))
      (is (= #{:at :aircraft} (set (keys envelope)))))))

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
    (let [{:keys [stats feeder]} (wire/stats-event->wire nil nil captured-at-ms)]
      (is (= {} stats))
      (is (= {} feeder)))))

(defn- withheld [aircraft]
  (dissoc aircraft :aircraft/rssi :aircraft/position-at-ms))

(deftest wire-round-trip
  (testing "a domain aircraft survives the round trip, minus the fields the
            allowlist deliberately withholds"
    (doseq [[icao domain-aircraft] the-picture]
      (is (= (withheld domain-aircraft)
             (wire/wire->aircraft (wire/aircraft->wire domain-aircraft)))
          icao)))

  (testing "a decoded frame envelope becomes the picture again, keyed by icao"
    (let [envelope (wire/picture->wire the-picture captured-at-ms)]
      (is (= (update-vals the-picture withheld)
             (wire/wire->picture envelope)))))

  (testing "an upsert envelope decodes back into its one domain aircraft —
            the same decode a full-picture entry gets, so the browser needs
            no second vocabulary (adsb-jpf)"
    (doseq [[icao domain-aircraft] the-picture]
      (is (= (withheld domain-aircraft)
             (wire/wire->upsert (wire/upsert->wire domain-aircraft captured-at-ms)))
          icao)))

  (testing "the stats scalars survive the round trip, minus the counts
            and receiver-relative fields the wire never carried"
    (let [envelope (wire/stats-event->wire server-stats server-feeder
                                           captured-at-ms)]
      (is (= (select-keys server-stats [:stats/max-range-km :stats/message-rate])
             (wire/wire->stats envelope)))))

  (testing "an envelope with no stats decodes to an empty stats map"
    (is (= {} (wire/wire->stats (wire/stats-event->wire nil nil captured-at-ms))))
    (is (= {} (wire/wire->stats {}))))

  (testing "the feeder status survives the round trip, minus the error string
            the wire never carried"
    (let [envelope (wire/stats-event->wire server-stats server-feeder captured-at-ms)]
      (is (= {:feeder/status          :down
              :feeder/last-success-ms 1720713599000}
             (wire/wire->feeder envelope)))))

  (testing "an envelope with no feeder decodes to an empty feeder map"
    (is (= {} (wire/wire->feeder (wire/stats-event->wire nil nil captured-at-ms))))
    (is (= {} (wire/wire->feeder {})))))

(deftest config-event-round-trip
  (testing "the declared crop survives the round trip, centre and radius"
    (let [envelope (wire/config-event->wire declared-crop captured-at-ms)]
      (is (= declared-crop (wire/wire->crop envelope)))))

  (testing "the radius crosses as KILOMETRES and comes back as metres"
    (is (= {:lat 27.9753 :lon -82.5331 :radius-km 100}
           (:crop (wire/config-event->wire declared-crop captured-at-ms)))))

  (testing "a DISABLED crop puts no crop key on the wire and decodes to nil —
            no boundary is drawn, and nothing falls back to the receiver
            position, which is the one coordinate that is not ours to publish"
    (let [envelope (wire/config-event->wire nil captured-at-ms)]
      (is (not (contains? envelope :crop)))
      (is (nil? (wire/wire->crop envelope)))
      (is (nil? (wire/wire->crop {})))))

  (testing "a half-formed crop yields no boundary rather than a partial one"
    (is (nil? (wire/crop->wire {:crop/center {:geo/lat 27.9 :geo/lon -82.4}})))
    (is (nil? (wire/crop->wire {:crop/radius-m 100000})))
    (is (nil? (wire/wire->crop {:crop {:lat 27.9 :lon -82.4}})))))
