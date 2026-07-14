(ns adsb.ingest.mode-s-test
  "Mode-S / DF17 decode against the published worked examples of 'The
  1090MHz Riddle' (Junzi Sun, mode-s.org): the KLM1023 identification,
  the 52.2572/3.91937 airborne-position pair, and the subtype 1 and 3
  velocity messages. Plus the untrusted-boundary cases: corrupted
  parity, garbage of every shape, and the CPR timing rules."
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.ingest.mode-s :as mode-s]
    [adsb.schema :as schema]
    [malli.core :as m]
    #?(:clj [clojure.test :refer [deftest testing is]]
       :cljs [cljs.test :refer-macros [deftest testing is]])))

;; ---------------------------------------------------------------------
;; Helpers: hex vectors, crafted frames

(defn- hex->payload
  [hex]
  (mapv (fn [pair]
          (let [s (apply str pair)]
            #?(:clj (Integer/parseInt s 16)
               :cljs (js/parseInt s 16))))
        (partition 2 hex)))

(defn- with-parity
  "The 11 data bytes with their CRC-24 appended — how a transponder
  builds the frame, so crafted test frames arrive parity-clean."
  [data-bytes]
  (let [parity (mode-s/crc data-bytes)]
    (into data-bytes [(bit-shift-right parity 16)
                      (bit-and (bit-shift-right parity 8) 0xff)
                      (bit-and parity 0xff)])))

(defn- position-me
  [type-code altitude-field parity lat-cpr lon-cpr]
  [(bit-shift-left type-code 3)
   (bit-shift-right altitude-field 4)
   (bit-or (bit-shift-left (bit-and altitude-field 0x0f) 4)
           (if (= :odd parity) 0x04 0)
           (bit-shift-right lat-cpr 15))
   (bit-and (bit-shift-right lat-cpr 7) 0xff)
   (bit-or (bit-and (bit-shift-left (bit-and lat-cpr 0x7f) 1) 0xff)
           (bit-shift-right lon-cpr 16))
   (bit-and (bit-shift-right lon-cpr 8) 0xff)
   (bit-and lon-cpr 0xff)])

(defn- position-frame
  "A parity-clean DF17 airborne position frame for icao 40621d, from
  explicit CPR integers — the book pair's, unless a test says
  otherwise."
  [type-code altitude-field parity lat-cpr lon-cpr]
  (with-parity (into [0x8d 0x40 0x62 0x1d]
                     (position-me type-code altitude-field parity
                                  lat-cpr lon-cpr))))

(defn- approximately?
  [expected actual tolerance]
  (< (Math/abs (double (- expected actual))) tolerance))

;; ---------------------------------------------------------------------
;; The published test vectors

(def identification-payload
  "TC4 identification, callsign KLM1023 (mode-s.org worked example)."
  (hex->payload "8D4840D6202CC371C32CE0576098"))

(def even-position-payload
  "TC11 airborne position, the even half of the book's pair."
  (hex->payload "8D40621D58C382D690C8AC2863A7"))

(def odd-position-payload
  "TC11 airborne position, the odd half of the book's pair."
  (hex->payload "8D40621D58C386435CC412692AD6"))

(def ground-speed-payload
  "TC19 subtype 1: 159.20 kt over the ground, track 182.88°, vertical
  rate -832 ft/min GNSS-sourced (mode-s.org worked example)."
  (hex->payload "8D485020994409940838175B284F"))

(def airspeed-payload
  "TC19 subtype 3: TAS 375 kt, heading 243.98°, vertical rate
  -2304 ft/min barometric (mode-s.org worked example)."
  (hex->payload "8DA05F219B06B6AF189400CBC33F"))

(def book-position
  {:geo/lat 52.2572021484375 :geo/lon 3.91937255859375})

(defn- decode-delta
  ([payload] (decode-delta payload 0 nil))
  ([payload now-ms cpr-state]
   (:delta (mode-s/decode payload now-ms cpr-state))))

;; ---------------------------------------------------------------------
;; CRC-24

(defn- flip-bit
  [payload i]
  (update payload (quot i 8)
          bit-xor (bit-shift-left 1 (- 7 (rem i 8)))))

(deftest crc-parity
  (testing "every published vector divides out clean"
    (doseq [payload [identification-payload even-position-payload
                     odd-position-payload ground-speed-payload
                     airspeed-payload]]
      (is (zero? (mode-s/crc payload)))))

  (testing "every possible single-bit flip is caught and the frame
            dropped — radio noise and injections die at parity"
    (doseq [bit (range (* 8 mode-s/long-frame-byte-count))]
      (let [corrupted (flip-bit identification-payload bit)]
        (is (not (zero? (mode-s/crc corrupted))))
        (is (nil? (decode-delta corrupted)))))))

;; ---------------------------------------------------------------------
;; TC 1-4 — identification

(deftest identification
  (testing "the KLM1023 vector: space padding trimmed like the
            ultrafeeder boundary trims flight"
    (is (= {:aircraft/icao "4840d6" :aircraft/callsign "KLM1023"}
           (decode-delta identification-payload))))

  (testing "a garbled callsign (undefined 6-bit codes) is refused;
            the aircraft keeps its bare delta"
    (let [garbled (with-parity (into [0x8d 0x48 0x40 0xd6 0x20]
                                     (repeat 6 0)))]
      (is (= {:aircraft/icao "4840d6"} (decode-delta garbled))))))

;; ---------------------------------------------------------------------
;; TC 1-4 — the emitter category, which rides the SAME message as the
;; callsign: the type code names the category set, the low three bits of
;; ME byte 0 carry the code within it (adsb-rnp).

(defn- identification-frame
  "A parity-clean DF17 identification frame for icao 4840d6, carrying the
  book's KLM1023 callsign bytes under whatever type code and category code
  a test asks for. Crafted rather than published, because the published
  vector's own CA is 0 — see below."
  [type-code category-code]
  (with-parity (into [0x8d 0x48 0x40 0xd6
                      (bit-or (bit-shift-left type-code 3) category-code)]
                     [0x2c 0xc3 0x71 0xc3 0x2c 0xe0])))

(deftest emitter-category
  (testing "the type code names the SET and the CA code names the member:
            TC4 is set A, TC3 set B, TC2 set C"
    (doseq [[[type-code code] expected]
            {[4 7] "A7"    ; rotorcraft
             [4 5] "A5"    ; heavy
             [4 1] "A1"    ; light
             [3 1] "B1"    ; glider
             [2 2] "C2"}]  ; surface vehicle — service
      (is (= expected
             (:aircraft/category
               (decode-delta (identification-frame type-code code))))
          (str "TC" type-code " CA" code " must decode to " expected))))

  (testing "the callsign and the category come off the ONE message
            together"
    (is (= {:aircraft/icao "4840d6"
            :aircraft/callsign "KLM1023"
            :aircraft/category "A7"}
           (decode-delta (identification-frame 4 7)))))

  (testing "CA 0 is 'no category information' — the aircraft declined to
            say, which is ABSENCE and not a category. The published
            KLM1023 vector is itself a CA-0 frame, so the sky really does
            send these."
    (is (not (contains? (decode-delta identification-payload)
                        :aircraft/category)))
    (doseq [type-code [4 3 2]]
      (is (not (contains? (decode-delta (identification-frame type-code 0))
                          :aircraft/category)))))

  (testing "TC1 is set D, which the spec reserves and the domain does not
            model — no category, and the aircraft still keeps its callsign"
    (let [delta (decode-delta (identification-frame 1 3))]
      (is (= "KLM1023" (:aircraft/callsign delta)))
      (is (not (contains? delta :aircraft/category)))))

  (testing "every category the decoder can possibly emit is a member of
            the domain's closed enum — over the WHOLE input space, so the
            beast path cannot smuggle in a value the poll boundary would
            have refused"
    (let [valid? (m/validator schema/emitter-category)]
      (doseq [type-code (range 1 5)
              code      (range 8)]
        (let [category (:aircraft/category
                         (decode-delta (identification-frame type-code
                                                             code)))]
          (is (or (nil? category) (valid? category))
              (str "TC" type-code " CA" code " decoded to "
                   (pr-str category))))))))

;; ---------------------------------------------------------------------
;; TC 19 — velocity

(deftest ground-speed-velocity
  (testing "the subtype 1 vector lands ground speed and track on the
            schema's names; its GNSS vertical rate stays off the
            barometric field"
    (let [{:aircraft/keys [icao ground-speed-kt track-deg]
           :as delta} (decode-delta ground-speed-payload)]
      (is (= "485020" icao))
      (is (approximately? 159.20 ground-speed-kt 0.01))
      (is (approximately? 182.88 track-deg 0.01))
      (is (not (contains? delta :aircraft/baro-rate-fpm)))))

  (testing "the full ME-level truth, including the published vertical
            rate"
    (let [velocity (mode-s/airborne-velocity ground-speed-payload)]
      (is (= :ground (:velocity/speed-source velocity)))
      (is (= :track (:velocity/direction-source velocity)))
      (is (= -832 (:velocity/vertical-rate-fpm velocity)))
      (is (= :gnss (:velocity/vertical-rate-source velocity))))))

(deftest airspeed-velocity
  (testing "the subtype 3 vector: airspeed and heading are not ground
            speed and track, so the delta carries only the barometric
            vertical rate"
    (is (= {:aircraft/icao "a05f21" :aircraft/baro-rate-fpm -2304}
           (decode-delta airspeed-payload))))

  (testing "the full ME-level truth matches the published example"
    (let [velocity (mode-s/airborne-velocity airspeed-payload)]
      (is (= 375 (:velocity/speed-kt velocity)))
      (is (= :airspeed-true (:velocity/speed-source velocity)))
      (is (approximately? 243.98 (:velocity/direction-deg velocity)
                          0.01))
      (is (= :heading (:velocity/direction-source velocity)))
      (is (= -2304 (:velocity/vertical-rate-fpm velocity)))
      (is (= :baro (:velocity/vertical-rate-source velocity))))))

(deftest implausible-velocity
  (testing "a supersonic subtype claiming thousands of knots costs the
            speed fields, never the aircraft — the plausibility line
            the ultrafeeder boundary draws"
    (let [subtype-2 (bit-or (bit-shift-left 19 3) 2)
          payload   (with-parity [0x8d 0x48 0x50 0x20 subtype-2
                                  0x07 0xff 0x7f 0xe0 0x00 0x00])]
      (is (= {:aircraft/icao "485020"} (decode-delta payload))))))

;; ---------------------------------------------------------------------
;; TC 9-18 / 20-22 — airborne position

(deftest global-position-decode
  (testing "the book pair, odd then even, decodes the published
            position and 38000 ft on the even frame"
    (let [{:keys [cpr-state]} (mode-s/decode odd-position-payload
                                             1000 nil)
          {:keys [delta]}     (mode-s/decode even-position-payload
                                             1500 cpr-state)]
      (is (= book-position (:aircraft/position delta)))
      (is (= 38000 (:aircraft/altitude-ft delta)))))

  (testing "one frame alone yields altitude but no position — a single
            CPR half is ambiguous"
    (let [delta (decode-delta odd-position-payload 1000 nil)]
      (is (= 38000 (:aircraft/altitude-ft delta)))
      (is (not (contains? delta :aircraft/position))))))

(deftest cpr-pair-timing
  (testing "halves further apart than the pair window never pair, and
            the stale half is pruned from cpr-state"
    (let [{:keys [cpr-state]} (mode-s/decode odd-position-payload
                                             1000 nil)
          later               (+ 1001 mode-s/cpr-pair-max-gap-ms)
          {:keys [delta cpr-state]} (mode-s/decode
                                      even-position-payload
                                      later cpr-state)]
      (is (not (contains? delta :aircraft/position)))
      (is (nil? (get-in cpr-state ["40621d" :cpr/odd])))))

  (testing "halves exactly at the window still pair"
    (let [{:keys [cpr-state]} (mode-s/decode odd-position-payload
                                             1000 nil)
          at-window           (+ 1000 mode-s/cpr-pair-max-gap-ms)
          {:keys [delta]}     (mode-s/decode even-position-payload
                                             at-window cpr-state)]
      (is (= book-position (:aircraft/position delta))))))

(deftest sweep-cpr-state
  (testing "an aircraft never heard again is dropped outright — decode
            prunes an entry only when that same aircraft speaks, so
            without the sweep cpr-state grows for the life of the
            connection (adsb-gq3)"
    (let [{:keys [cpr-state]} (mode-s/decode odd-position-payload 1000 nil)
          a-day               (+ 1000 (* 24 60 60 1000))]
      (is (contains? cpr-state "40621d") "heard once, so it is in there")
      (is (= {} (mode-s/sweep-cpr-state cpr-state a-day))
          "a day later its half and reference are long stale: no entry")))

  (testing "an aircraft still talking keeps its entry — the sweep drops
            what has aged out, not what is merely older than a frame"
    (let [{:keys [cpr-state]} (mode-s/decode odd-position-payload 1000 nil)
          swept (mode-s/sweep-cpr-state cpr-state 1500)]
      (is (= cpr-state swept) "inside the pair window, nothing is dropped")))

  (testing "an entry keeps a live reference after its halves go stale —
            a half can no longer pair long before a position is untrusted"
    (let [{:keys [cpr-state]} (mode-s/decode odd-position-payload 1000 nil)
          {:keys [cpr-state]} (mode-s/decode even-position-payload
                                             1500 cpr-state)
          past-pair-window    (+ 1501 mode-s/cpr-pair-max-gap-ms)
          entry (get (mode-s/sweep-cpr-state cpr-state past-pair-window)
                     "40621d")]
      (is (= book-position (get-in entry [:cpr/reference :cpr/position]))
          "the reference survives to the age-out line, so a local decode
           still works when the aircraft is next heard")
      (is (not (contains? entry :cpr/even)) "the stale halves are gone")
      (is (not (contains? entry :cpr/odd)))))

  (testing "the empty and never-started states sweep to nothing"
    (is (= {} (mode-s/sweep-cpr-state {} 1000)))
    (is (empty? (mode-s/sweep-cpr-state nil 1000)))))

(deftest local-position-decode
  (testing "the book's local example: one even frame against the
            aircraft's known reference (52.258, 3.918)"
    (let [seeded {"40621d" {:cpr/reference
                            {:cpr/position {:geo/lat 52.258
                                            :geo/lon 3.918}
                             :cpr/heard-at-ms 500}}}
          delta  (decode-delta even-position-payload 1000 seeded)]
      (is (= book-position (:aircraft/position delta)))))

  (testing "a reference older than the age-out line is refused — the
            aircraft could have left the zone, so wait for a pair"
    (let [seeded {"40621d" {:cpr/reference
                            {:cpr/position {:geo/lat 52.258
                                            :geo/lon 3.918}
                             :cpr/heard-at-ms 0}}}
          stale-at (+ 1 aircraft/age-out-threshold-ms)
          delta    (decode-delta even-position-payload stale-at seeded)]
      (is (not (contains? delta :aircraft/position)))))

  (testing "a decoded position becomes the next frame's reference, so
            the stream keeps decoding locally frame by frame"
    (let [{:keys [cpr-state]} (mode-s/decode odd-position-payload
                                             1000 nil)
          {:keys [cpr-state]} (mode-s/decode even-position-payload
                                             1500 cpr-state)]
      (is (= book-position
             (get-in cpr-state ["40621d" :cpr/reference
                                :cpr/position]))))))

(deftest altitude-decode
  (testing "a Q=0 (Gillham) altitude stays absent while the position
            still decodes — absent is not zero"
    (let [q0 (position-frame 11 (bit-and 3128 (bit-not 0x10))
                             :even 93000 51372)
          {:keys [cpr-state]} (mode-s/decode odd-position-payload
                                             1000 nil)
          delta (decode-delta q0 1500 cpr-state)]
      (is (= book-position (:aircraft/position delta)))
      (is (not (contains? delta :aircraft/altitude-ft)))))

  (testing "an all-zero altitude field means no information, not sea
            level"
    (let [no-altitude (position-frame 11 0 :odd 74158 50194)]
      (is (not (contains? (decode-delta no-altitude 1000 nil)
                          :aircraft/altitude-ft)))))

  (testing "TC20 carries GNSS height in metres; it lands in feet"
    (let [tc20 (position-frame 20 3000 :even 93000 51372)
          {:keys [cpr-state]} (mode-s/decode odd-position-payload
                                             1000 nil)
          delta (decode-delta tc20 1500 cpr-state)]
      (is (= book-position (:aircraft/position delta)))
      (is (approximately? (* 3000 3.28084)
                          (:aircraft/altitude-ft delta) 1e-6)))))

;; ---------------------------------------------------------------------
;; Frame admission: DFs, out-of-scope TCs, garbage

(deftest downlink-formats
  (testing "DF18 CF0 and CF2 decode like DF17"
    (doseq [cf [0 2]]
      (let [payload (with-parity
                      (into [(bit-or 0x90 cf) 0x48 0x40 0xd6]
                            (subvec identification-payload 4 11)))]
        (is (= {:aircraft/icao "4840d6" :aircraft/callsign "KLM1023"}
               (decode-delta payload))))))

  (testing "DF18 CF1 carries a non-ICAO address — the schema's ~
            prefix, like the feeder's TIS-B/ADS-R hex"
    (let [payload (with-parity
                    (into [(bit-or 0x90 1) 0x48 0x40 0xd6]
                          (subvec identification-payload 4 11)))]
      (is (= "~4840d6" (:aircraft/icao (decode-delta payload))))))

  (testing "DF18 CF3+ and non-extended-squitter DFs are dropped even
            when parity divides clean"
    (doseq [first-byte [(bit-or 0x90 3)  ; DF18 CF3
                        0xa0]]           ; DF20
      (let [payload (with-parity
                      (into [first-byte]
                            (subvec identification-payload 1 11)))]
        (is (nil? (decode-delta payload)))))))

(deftest out-of-scope-type-codes
  (testing "a CRC-valid frame with an out-of-scope TC still refreshes
            the aircraft: proof of life is a bare icao delta"
    (let [tc0 (with-parity (into [0x8d 0xab 0xcd 0xef]
                                 (repeat 7 0)))]
      (is (= {:aircraft/icao "abcdef"} (decode-delta tc0))))))

(deftest garbage-payloads
  (testing "garbage of any shape yields a nil delta and never throws,
            and the caller's cpr-state survives untouched"
    (let [state {"4840d6" {:cpr/reference
                           {:cpr/position {:geo/lat 1 :geo/lon 2}
                            :cpr/heard-at-ms 0}}}]
      (doseq [payload [nil
                       []
                       (subvec identification-payload 0 7)
                       (into identification-payload [0])
                       (vec (repeat 14 0))
                       (vec (repeat 14 0xff))
                       (assoc identification-payload 3 "garbage")
                       (assoc identification-payload 3 999)
                       (assoc identification-payload 3 -1)]]
        (is (= {:delta nil :cpr-state state}
               (mode-s/decode payload 0 state)))))))

(deftest deltas-speak-schema
  (testing "every decoded delta is a valid partial domain aircraft"
    (let [{:keys [cpr-state]} (mode-s/decode odd-position-payload
                                             1000 nil)]
      (doseq [delta [(decode-delta identification-payload)
                     (decode-delta ground-speed-payload)
                     (decode-delta airspeed-payload)
                     (decode-delta even-position-payload 1500 cpr-state)]]
        (is (m/validate schema/aircraft delta))))))
