(ns adsb.ingest.mode-s
  (:require [adsb.aircraft :as aircraft]
            [adsb.ingest.cpr :as cpr]
            [adsb.schema :as schema]
            [clojure.math :as math]
            [clojure.string :as str]
            [malli.core :as m]))

;; The Mode S 24-bit CRC polynomial (ICAO Annex 10 Vol IV).
(def ^:const crc-generator 0xfff409)
(def ^:private crc-width-mask 0xffffff)
(def ^:private crc-high-bit 0x800000)

(defn- crc-shift [register _]
  (let [shifted (bit-shift-left register 1)]
    (bit-and crc-width-mask
             (if (zero? (bit-and register crc-high-bit))
               shifted
               (bit-xor shifted crc-generator)))))

(defn crc [payload]
  (reduce (fn [register b]
            (reduce crc-shift
                    (bit-xor register (bit-shift-left b 16))
                    (range 8)))
          0
          payload))

(def ^:const long-frame-byte-count 14)
(def ^:private df-extended-squitter 17)
(def ^:private df-extended-squitter-non-transponder 18)
(def ^:private hex-digits "0123456789abcdef")

(defn- byte-value? [b] (and (int? b) (<= 0 b 255)))

(defn- well-formed? [payload]
  (and (vector? payload)
       (= long-frame-byte-count (count payload))
       (every? byte-value? payload)))

(defn- bytes->hex [bs]
  (->> bs
       (mapcat (fn [b]
                 [(nth hex-digits (bit-shift-right b 4))
                  (nth hex-digits (bit-and b 0x0f))]))
       (apply str)))

(defn- extended-squitter-icao [payload]
  ;; DF18 CF field: 0/2 carry an ICAO address, 1 a non-ICAO one (hence the ~
  ;; prefix, matching readsb). Other CF values (TIS-B relay, ADS-R rebroadcast)
  ;; are deliberately not decoded.
  (let [df   (bit-shift-right (nth payload 0) 3)
        cf   (bit-and (nth payload 0) 0x07)
        icao (bytes->hex (subvec payload 1 4))]
    (cond
      (= df df-extended-squitter) icao
      (and (= df df-extended-squitter-non-transponder) (contains? #{0 2} cf)) icao
      (and (= df df-extended-squitter-non-transponder) (= 1 cf)) (str "~" icao))))

;; The ICAO Annex 10 6-bit character table; # marks the invalid codes.
(def ^:private callsign-charset
  "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######")

(defn- six-bit-code [callsign-bytes i]
  (let [bit-offset  (* 6 i)
        byte-offset (quot bit-offset 8)
        shift       (- 10 (rem bit-offset 8))  ; 10 = 16-bit window - 6-bit code
        pair        (bit-or (bit-shift-left (nth callsign-bytes
                                                 byte-offset)
                                            8)
                            (get callsign-bytes (inc byte-offset) 0))]
    (bit-and (bit-shift-right pair shift) 0x3f)))

(defn- callsign [me]
  (let [callsign-bytes (subvec me 1 7)
        characters     (map #(nth callsign-charset
                                  (six-bit-code callsign-bytes %))
                            (range 8))]
    (when-not (some #{\#} characters)
      (some-> (apply str characters)
              str/trim
              not-empty))))

;; The type-code -> category-set mapping runs backwards by design (TC 4 = set
;; A). TC 1 (set D, mostly reserved) is deliberately not decoded.
(def ^:private category-set {4 "A", 3 "B", 2 "C"})

(defn- emitter-category [me type-code]
  (let [code (bit-and (nth me 0) 0x07)]
    (when-let [set-letter (category-set type-code)]
      (when (pos? code)
        (str set-letter code)))))

(defn- identification-delta [icao me type-code]
  (let [callsign (callsign me)
        category (emitter-category me type-code)]
    (cond-> {:aircraft/icao icao}
            callsign (assoc :aircraft/callsign callsign)
            category (assoc :aircraft/category category))))

(def ^:private plausible-ground-speed? (m/validator schema/plausible-ground-speed-kt))
(def ^:const ^:private vertical-rate-fpm-per-unit 64)
;; Velocity subtypes 2 and 4 are the supersonic encodings: units of 4 kt.
(def ^:private supersonic-subtypes #{2 4})

(defn- velocity-multiplier [subtype]
  (if (supersonic-subtypes subtype) 4 1))

(defn- signed-component [direction-bit value multiplier]
  ;; DO-260B velocity fields: 0 means "no data" and real values sit at an
  ;; offset of one, so the (dec value) is the encoding, not an off-by-one.
  ;; Direction bit set = west/south = negative.
  (when (pos? value)
    (* (dec value)
       multiplier
       (if (pos? direction-bit) -1 1))))

(defn- ground-speed-fields [me subtype]
  (let [multiplier  (velocity-multiplier subtype)
        east-west   (signed-component
                      (bit-and (nth me 1) 0x04)
                      (bit-or (bit-shift-left (bit-and (nth me 1) 0x03) 8)
                              (nth me 2))
                      multiplier)
        north-south (signed-component
                      (bit-and (nth me 3) 0x80)
                      (bit-or (bit-shift-left (bit-and (nth me 3) 0x7f) 3)
                              (bit-shift-right (nth me 4) 5))
                      multiplier)]
    (when (and east-west north-south)
      {:velocity/speed-kt         (math/sqrt
                                    (+ (* east-west east-west)
                                       (* north-south north-south)))
       :velocity/speed-source     :ground
       :velocity/direction-deg    (mod (math/to-degrees
                                         (math/atan2 east-west north-south))
                                       360.0)
       :velocity/direction-source :track})))

(defn- airspeed-fields [me subtype]
  (let [heading-available? (pos? (bit-and (nth me 1) 0x04))
        heading            (bit-or (bit-shift-left (bit-and (nth me 1) 0x03) 8)
                                   (nth me 2))
        true-airspeed?     (pos? (bit-and (nth me 3) 0x80))
        airspeed           (bit-or (bit-shift-left (bit-and (nth me 3) 0x7f) 3)
                                   (bit-shift-right (nth me 4) 5))]
    (cond-> {}
            heading-available?
            (assoc :velocity/direction-deg (* heading (/ 360.0 1024))
                   :velocity/direction-source :heading)

            (pos? airspeed)
            (assoc :velocity/speed-kt (* (dec airspeed)
                                         (velocity-multiplier subtype))
                   :velocity/speed-source (if true-airspeed?
                                            :airspeed-true
                                            :airspeed-indicated)))))

(defn- vertical-rate-fields [me]
  (let [rate (bit-or (bit-shift-left (bit-and (nth me 4) 0x07) 6)
                     (bit-shift-right (nth me 5) 2))]
    (when (pos? rate)
      {:velocity/vertical-rate-fpm
       (* (dec rate)
          vertical-rate-fpm-per-unit
          (if (pos? (bit-and (nth me 4) 0x08)) -1 1))
       :velocity/vertical-rate-source
       (if (pos? (bit-and (nth me 4) 0x10)) :baro :gnss)})))

(defn airborne-velocity [payload]
  (when (well-formed? payload)
    (let [me      (subvec payload 4 11)
          subtype (bit-and (nth me 0) 0x07)]
      (when (= 19 (bit-shift-right (nth me 0) 3))
        (when-let [fields (case subtype
                            (1 2) (ground-speed-fields me subtype)
                            (3 4) (airspeed-fields me subtype)
                            nil)]
          (merge fields (vertical-rate-fields me)))))))

(defn- velocity-delta [icao payload]
  ;; Airspeed/heading (subtypes 3/4) and GNSS vertical rates are decoded but
  ;; deliberately dropped here: heading is not track, and the domain models
  ;; ground-referenced motion only (see adsb-qxl for the open question).
  (let [{:velocity/keys [speed-kt speed-source direction-deg
                         vertical-rate-fpm vertical-rate-source]}
        (airborne-velocity payload)]
    (cond-> {:aircraft/icao icao}
            (and (= :ground speed-source) (plausible-ground-speed? speed-kt))
            (assoc :aircraft/ground-speed-kt speed-kt
                   :aircraft/track-deg direction-deg)

            (and vertical-rate-fpm (= :baro vertical-rate-source))
            (assoc :aircraft/baro-rate-fpm vertical-rate-fpm))))

;; The spec-recommended window for pairing even/odd CPR halves (DO-260B).
(def ^:const cpr-pair-max-gap-ms 10000)
(def ^:const feet-per-meter 3.28084)

(defn- q-bit-altitude-ft [altitude-field]
  ;; Q=1: 25 ft LSB above a -1000 ft base, with the Q bit spliced out of the
  ;; middle of the field. Q=0 (Gillham 100 ft coding) is deliberately not
  ;; decoded and yields no altitude.
  (when (pos? (bit-and altitude-field 0x10))
    (let [n (bit-or (bit-shift-left (bit-shift-right altitude-field 5) 4)
                    (bit-and altitude-field 0x0f))]
      (- (* 25 n) 1000))))

(defn- altitude-ft [type-code me]
  (let [field (bit-or (bit-shift-left (nth me 1) 4)
                      (bit-shift-right (nth me 2) 4))]
    (when (pos? field)
      (cond
        ;; TC 9-18 carries barometric altitude; TC 20-22 carries GNSS height
        ;; in meters — hence the unit conversion on that arm only.
        (<= 9 type-code 18) (q-bit-altitude-ft field)
        (<= 20 type-code 22) (* field feet-per-meter)))))

(defn- cpr-half [me now-ms]
  {:cpr/parity      (if (pos? (bit-and (nth me 2) 0x04)) :odd :even)
   :cpr/lat         (bit-or (bit-shift-left (bit-and (nth me 2) 0x03) 15)
                            (bit-shift-left (nth me 3) 7)
                            (bit-shift-right (nth me 4) 1))
   :cpr/lon         (bit-or (bit-shift-left (bit-and (nth me 4) 0x01) 16)
                            (bit-shift-left (nth me 5) 8)
                            (nth me 6))
   :cpr/heard-at-ms now-ms})

(defn- fresh-at? [heard-at-ms now-ms max-age-ms]
  (and heard-at-ms (<= (- now-ms heard-at-ms) max-age-ms)))

(defn- stale-at? [heard-at-ms now-ms max-age-ms]
  (not (fresh-at? heard-at-ms now-ms max-age-ms)))

(defn- stale-half-at? [half now-ms]
  (stale-at? (:cpr/heard-at-ms half) now-ms cpr-pair-max-gap-ms))

(defn- stale-reference-at? [reference now-ms]
  (stale-at? (:cpr/heard-at-ms reference) now-ms aircraft/age-out-threshold-ms))

(defn- prune-cpr-entry [entry now-ms]
  (cond-> entry
          (stale-half-at? (:cpr/even entry) now-ms) (dissoc :cpr/even)
          (stale-half-at? (:cpr/odd entry) now-ms) (dissoc :cpr/odd)
          (stale-reference-at? (:cpr/reference entry) now-ms) (dissoc :cpr/reference)))

(defn sweep-cpr-state [cpr-state now-ms]
  (into (empty cpr-state)
        (keep (fn [[icao entry]]
                (let [pruned (prune-cpr-entry entry now-ms)]
                  (when (seq pruned)
                    [icao pruned]))))
        cpr-state))

(defn- global-pair-position [{:cpr/keys [even odd]}]
  (when (and even odd)
    (cpr/global-position even odd)))

(defn- local-reference-position [{:cpr/keys [reference]} half]
  (when reference
    (cpr/local-position half (:cpr/position reference))))

(defn- position-delta+state [icao me type-code now-ms cpr-state]
  (let [half     (cpr-half me now-ms)
        half-key (if (= :odd (:cpr/parity half)) :cpr/odd :cpr/even)
        entry    (-> (get cpr-state icao)
                     (prune-cpr-entry now-ms)
                     (assoc half-key half))
        position (or (global-pair-position entry)
                     (local-reference-position entry half))
        entry    (cond-> entry
                         position (assoc :cpr/reference
                                         {:cpr/position    position
                                          :cpr/heard-at-ms now-ms}))
        delta    (cond-> {:aircraft/icao icao}
                         position (assoc :aircraft/position position))
        delta    (if-let [altitude (altitude-ft type-code me)]
                   (assoc delta :aircraft/altitude-ft altitude)
                   delta)]
    [delta (assoc cpr-state icao entry)]))

(defn- me-delta+state [icao payload now-ms cpr-state]
  (let [me        (subvec payload 4 11)
        type-code (bit-shift-right (nth me 0) 3)]
    (cond
      (<= 1 type-code 4)
      [(identification-delta icao me type-code) cpr-state]

      (or (<= 9 type-code 18) (<= 20 type-code 22))
      (position-delta+state icao me type-code now-ms cpr-state)

      (= 19 type-code)
      [(velocity-delta icao payload) cpr-state]

      :else
      [{:aircraft/icao icao} cpr-state])))

(defn decode [payload now-ms cpr-state]
  (let [cpr-state (or cpr-state {})]
    (if-let [icao (when (and (well-formed? payload)
                             (zero? (crc payload)))
                    (extended-squitter-icao payload))]
      (let [[delta cpr-state] (me-delta+state icao payload now-ms
                                              cpr-state)]
        {:delta delta :cpr-state cpr-state})
      {:delta nil :cpr-state cpr-state})))
