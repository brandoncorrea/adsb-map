(ns adsb.ingest.mode-s
  "Mode-S extended squitter decode: 14-byte payloads (adsb.ingest.beast
  :mode-s-long frames) in, partial :aircraft/* deltas out — the trust
  boundary for the Beast wire format, feeding adsb.accumulator.

  THE FEEDER IS UNTRUSTED (docs/validation-boundaries.md): ADS-B is
  unauthenticated radio, so every payload proves itself first. CRC-24
  (generator 0xFFF409) over the whole frame must leave no remainder; a
  frame failing parity is radio noise or an injection attempt and is
  dropped here. Garbage in any shape yields a nil delta, never a throw.

  WHAT IS DECODED. DF17 (and DF18 CF0-2 — same body, non-transponder
  emitters; CF1's address is non-ICAO and carries the schema's ~ prefix)
  extended squitter, by type code: TC1-4 identification (callsign),
  TC19 airborne velocity, TC9-18/20-22 airborne position (altitude +
  CPR via adsb.ingest.cpr). Any other CRC-valid DF17/18 message still
  yields its bare {:aircraft/icao _} — proof of life refreshes
  freshness even when the message body is out of scope.

  WHAT IS NOT. Mode-A/C and 7-byte short frames carry no ICAO address
  usable here and are the Source's to ignore — this namespace consumes
  only the 14-byte long payloads. DF4/5/11/20/21 overlay their parity
  with the interrogator/address and need cross-frame correlation to
  check at all; out of scope. TC5-8 surface position, Gillham-coded
  (Q=0) altitudes, and DF18 CF>2 are out of scope: their fields simply
  stay absent — absent is not zero. Airspeed/heading (TC19 subtype 3-4)
  are decoded (`airborne-velocity`) but the delta omits them until the
  schema grows IAS/heading vocabulary; only their vertical rate lands.

  PURE. (decode payload now-ms cpr-state) -> {:delta _ :cpr-state _}.
  now-ms is the arrival instant the caller read from its clock; CPR
  pairing state rides cpr-state, a map of icao ->

    {:cpr/even      <CPR half, see adsb.ingest.cpr>
     :cpr/odd       <CPR half>
     :cpr/reference {:cpr/position {:geo/lat _ :geo/lon _}
                     :cpr/heard-at-ms <ms>}}

  Halves older than the global-pair window and references older than
  the aircraft age-out line are pruned as the same aircraft is heard
  again. No atoms, no clock, no I/O — the Source (adsb-doh.3) owns all
  state and time."
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.ingest.cpr :as cpr]
    [adsb.schema :as schema]
    [clojure.string :as str]
    [malli.core :as m]))

;; ---------------------------------------------------------------------
;; CRC-24 — parity first; nothing else is worth reading off a frame
;; that fails it.

(def ^:const crc-generator
  "The Mode-S CRC-24 generator polynomial (ICAO Annex 10)."
  0xfff409)

(def ^:private crc-width-mask 0xffffff)

(def ^:private crc-high-bit 0x800000)

(defn- crc-shift
  "One bit of polynomial division: shift, and fold the generator in
  when a set bit falls off the top."
  [register]
  (let [shifted (bit-shift-left register 1)]
    (bit-and crc-width-mask
             (if (zero? (bit-and register crc-high-bit))
               shifted
               (bit-xor shifted crc-generator)))))

(defn crc
  "The CRC-24 remainder over a whole Mode-S payload. Zero means the
  frame is clean: DF17/18 transmit the remainder as their last three
  bytes, so dividing data-plus-parity leaves nothing. Any other value
  is corruption (or an overlaid address on the DFs this namespace does
  not decode)."
  [payload]
  (reduce (fn [register b]
            (reduce (fn [r _] (crc-shift r))
                    (bit-xor register (bit-shift-left b 16))
                    (range 8)))
          0
          payload))

;; ---------------------------------------------------------------------
;; Frame fields

(def ^:const long-frame-byte-count 14)

(def ^:private df-extended-squitter 17)

(def ^:private df-extended-squitter-non-transponder 18)

(def ^:private hex-digits "0123456789abcdef")

(defn- byte-value?
  [b]
  (and (int? b) (<= 0 b 255)))

(defn- well-formed?
  "A payload this namespace can even begin to read: exactly 14 unsigned
  bytes. Anything else — wrong length, non-bytes, nil — is garbage."
  [payload]
  (and (vector? payload)
       (= long-frame-byte-count (count payload))
       (every? byte-value? payload)))

(defn- bytes->hex
  [bs]
  (apply str (mapcat (fn [b]
                       [(nth hex-digits (bit-shift-right b 4))
                        (nth hex-digits (bit-and b 0x0f))])
                     bs)))

(defn- extended-squitter-icao
  "The ICAO address of a DF17/18 frame as the domain's lower-case hex
  string — ~-prefixed for DF18 CF1, whose address field is explicitly
  non-ICAO (the same convention the feeder's `hex` uses for TIS-B /
  ADS-R targets). nil for every other DF, and for the DF18 CF values
  (3+) whose body is not an extended squitter we can read."
  [payload]
  (let [df   (bit-shift-right (nth payload 0) 3)
        cf   (bit-and (nth payload 0) 0x07)
        icao (bytes->hex (subvec payload 1 4))]
    (cond
      (= df df-extended-squitter) icao
      (and (= df df-extended-squitter-non-transponder) (#{0 2} cf)) icao
      (and (= df df-extended-squitter-non-transponder) (= 1 cf))
      (str "~" icao)
      :else nil)))

;; ---------------------------------------------------------------------
;; TC 1-4 — aircraft identification

(def ^:private callsign-charset
  "The 6-bit IA-5 subset of the identification message, indexed by
  code. # marks codes the spec leaves undefined — one appearing means
  the frame is garbled, so the whole callsign is refused."
  (str "#ABCDEFGHIJKLMNOPQRSTUVWXYZ#####"
       " ###############0123456789######"))

(defn- six-bit-code
  "The `i`th 6-bit group of the callsign bit field (ME bytes 1-6)."
  [callsign-bytes i]
  (let [bit-offset  (* 6 i)
        byte-offset (quot bit-offset 8)
        shift       (- 10 (rem bit-offset 8))
        pair        (bit-or (bit-shift-left (nth callsign-bytes
                                                 byte-offset)
                                            8)
                            (get callsign-bytes (inc byte-offset) 0))]
    (bit-and (bit-shift-right pair shift) 0x3f)))

(defn- callsign
  "The 8-character callsign of an identification ME field, trimmed the
  way the ultrafeeder boundary trims `flight` (adsb.ingest.coerce). nil
  when any character decodes to an undefined code, or nothing remains
  after trimming — a garbled or empty callsign is no callsign."
  [me]
  (let [callsign-bytes (subvec me 1 7)
        characters     (map #(nth callsign-charset
                                  (six-bit-code callsign-bytes %))
                            (range 8))]
    (when-not (some #{\#} characters)
      (some-> (apply str characters) str/trim not-empty))))

(defn- identification-delta
  [icao me]
  (if-let [callsign (callsign me)]
    {:aircraft/icao icao :aircraft/callsign callsign}
    {:aircraft/icao icao}))

;; ---------------------------------------------------------------------
;; TC 19 — airborne velocity

(def ^:private plausible-ground-speed?
  (m/validator schema/plausible-ground-speed-kt))

(def ^:const ^:private vertical-rate-fpm-per-unit 64)

(def ^:private supersonic-subtypes #{2 4})

(defn- velocity-multiplier
  [subtype]
  (if (supersonic-subtypes subtype) 4 1))

(defn- rad->deg
  [rad]
  (* rad (/ 180 Math/PI)))

(defn- signed-component
  "One velocity component: `value` is offset by one (zero means the
  transmitter had nothing to say), and the direction bit points it
  west/south when set."
  [direction-bit value multiplier]
  (when (pos? value)
    (* (dec value) multiplier (if (pos? direction-bit) -1 1))))

(defn- ground-speed-fields
  "Subtype 1/2: east-west and north-south components -> speed + track."
  [me subtype]
  (let [multiplier (velocity-multiplier subtype)
        east-west  (signed-component
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
      {:velocity/speed-kt         (Math/sqrt
                                    (+ (* east-west east-west)
                                       (* north-south north-south)))
       :velocity/speed-source     :ground
       :velocity/direction-deg    (mod (rad->deg
                                         (Math/atan2 east-west
                                                     north-south))
                                       360.0)
       :velocity/direction-source :track})))

(defn- airspeed-fields
  "Subtype 3/4: magnetic heading + indicated/true airspeed. Decoded in
  full, though the delta cannot carry them yet — see the ns docstring."
  [me subtype]
  (let [heading-available? (pos? (bit-and (nth me 1) 0x04))
        heading            (bit-or (bit-shift-left (bit-and (nth me 1)
                                                            0x03)
                                                   8)
                                   (nth me 2))
        true-airspeed?     (pos? (bit-and (nth me 3) 0x80))
        airspeed           (bit-or (bit-shift-left (bit-and (nth me 3)
                                                            0x7f)
                                                   3)
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

(defn- vertical-rate-fields
  "The vertical rate common to all TC19 subtypes: 64 ft/min units,
  offset by one, sign bit for descent, source bit naming barometric or
  GNSS."
  [me]
  (let [rate (bit-or (bit-shift-left (bit-and (nth me 4) 0x07) 6)
                     (bit-shift-right (nth me 5) 2))]
    (when (pos? rate)
      {:velocity/vertical-rate-fpm
       (* (dec rate) vertical-rate-fpm-per-unit
          (if (pos? (bit-and (nth me 4) 0x08)) -1 1))
       :velocity/vertical-rate-source
       (if (pos? (bit-and (nth me 4) 0x10)) :baro :gnss)})))

(defn airborne-velocity
  "The full airborne-velocity truth of a TC19 ME field, in :velocity/*
  vocabulary — speed with its source (:ground | :airspeed-true |
  :airspeed-indicated), direction with its source (:track | :heading),
  vertical rate with its source (:baro | :gnss). Fields the transmitter
  marked 'no information' are absent, never zero. nil when the payload
  is not a well-formed TC19 frame or the subtype is reserved. Exposed
  as the ME-level decode; `decode` projects the schema-speakable subset
  of it onto the delta. Assumes nothing about parity — callers outside
  `decode` check `crc` themselves."
  [payload]
  (when (well-formed? payload)
    (let [me      (subvec payload 4 11)
          subtype (bit-and (nth me 0) 0x07)]
      (when (= 19 (bit-shift-right (nth me 0) 3))
        (when-let [fields (case subtype
                            (1 2) (ground-speed-fields me subtype)
                            (3 4) (airspeed-fields me subtype)
                            nil)]
          (merge fields (vertical-rate-fields me)))))))

(defn- velocity-delta
  "The schema-speakable projection of a velocity message: ground speed
  and track from subtype 1/2 (gated by the same plausibility line the
  ultrafeeder boundary applies — an absurd speed costs the field, never
  the aircraft), barometric vertical rate from any subtype. A
  GNSS-sourced rate is dropped exactly as coerce drops the feeder's
  geom_rate: the schema's field is barometric."
  [icao payload]
  (let [{:velocity/keys [speed-kt speed-source direction-deg
                         vertical-rate-fpm vertical-rate-source]}
        (airborne-velocity payload)]
    (cond-> {:aircraft/icao icao}
      (and (= :ground speed-source) (plausible-ground-speed? speed-kt))
      (assoc :aircraft/ground-speed-kt speed-kt
             :aircraft/track-deg direction-deg)

      (and vertical-rate-fpm (= :baro vertical-rate-source))
      (assoc :aircraft/baro-rate-fpm vertical-rate-fpm))))

;; ---------------------------------------------------------------------
;; TC 9-18 / 20-22 — airborne position

(def ^:const cpr-pair-max-gap-ms
  "An even/odd pair further apart than this may straddle a zone
  crossing; the halves also age out of cpr-state past it."
  10000)

(def ^:const feet-per-meter 3.28084)

(defn- q-bit-altitude-ft
  "The barometric altitude of a TC9-18 message when its Q-bit says
  25-ft increments. Q=0 (Gillham 100-ft code, altitudes above 50175 ft)
  is out of scope: the field stays absent rather than decoded wrongly."
  [altitude-field]
  (when (pos? (bit-and altitude-field 0x10))
    (let [n (bit-or (bit-shift-left (bit-shift-right altitude-field 5) 4)
                    (bit-and altitude-field 0x0f))]
      (- (* 25 n) 1000))))

(defn- altitude-ft
  "The altitude of an airborne position ME field, in feet: barometric
  for TC9-18, GNSS height (metres on the wire) for TC20-22 — the schema
  speaks one altitude vocabulary, so both land in :aircraft/altitude-ft.
  nil when the field is all zeros (no information — absent is not zero)
  or encoded beyond this decoder's scope."
  [type-code me]
  (let [field (bit-or (bit-shift-left (nth me 1) 4)
                      (bit-shift-right (nth me 2) 4))]
    (when (pos? field)
      (cond
        (<= 9 type-code 18)  (q-bit-altitude-ft field)
        (<= 20 type-code 22) (* field feet-per-meter)))))

(defn- cpr-half
  [me now-ms]
  {:cpr/parity      (if (pos? (bit-and (nth me 2) 0x04)) :odd :even)
   :cpr/lat         (bit-or (bit-shift-left (bit-and (nth me 2) 0x03) 15)
                            (bit-shift-left (nth me 3) 7)
                            (bit-shift-right (nth me 4) 1))
   :cpr/lon         (bit-or (bit-shift-left (bit-and (nth me 4) 0x01) 16)
                            (bit-shift-left (nth me 5) 8)
                            (nth me 6))
   :cpr/heard-at-ms now-ms})

(defn- fresh-at?
  [heard-at-ms now-ms max-age-ms]
  (and heard-at-ms (<= (- now-ms heard-at-ms) max-age-ms)))

(defn- prune-cpr-entry
  "One aircraft's CPR entry with stale halves and a stale reference
  aged out: a half older than the pair window can no longer pair, and a
  reference older than the aircraft age-out line could have flown out
  of its zone (at the 1000 kt plausibility ceiling, five minutes is
  ~83 nm — comfortably inside the ~180 nm a local decode tolerates)."
  [entry now-ms]
  (letfn [(stale-half? [half]
            (not (fresh-at? (:cpr/heard-at-ms half) now-ms
                            cpr-pair-max-gap-ms)))
          (stale-reference? [reference]
            (not (fresh-at? (:cpr/heard-at-ms reference) now-ms
                            aircraft/age-out-threshold-ms)))]
    (cond-> entry
      (stale-half? (:cpr/even entry))      (dissoc :cpr/even)
      (stale-half? (:cpr/odd entry))       (dissoc :cpr/odd)
      (stale-reference? (:cpr/reference entry)) (dissoc :cpr/reference))))

(defn sweep-cpr-state
  "cpr-state with the aircraft that have gone quiet dropped: every entry
  pruned of its stale halves and stale reference (prune-cpr-entry), and
  the entries that prune away to nothing removed outright.

  decode prunes an entry only when that SAME aircraft is heard again, so
  an airframe that flies out of range leaves its last halves and
  reference behind for the life of the connection — thousands of them, on
  a busy site. The reader that threads cpr-state sweeps it periodically
  (adsb.ingest.beast-source); the rule for what is too old to keep stays
  here, with the rest of the CPR state (adsb-gq3)."
  [cpr-state now-ms]
  (into (empty cpr-state)
        (keep (fn [[icao entry]]
                (let [pruned (prune-cpr-entry entry now-ms)]
                  (when (seq pruned)
                    [icao pruned]))))
        cpr-state))

(defn- global-pair-position
  "The global decode of this entry's even/odd pair, when both halves
  are present within the pair window. Pruning already dropped anything
  older, so presence is the whole test."
  [{:cpr/keys [even odd]}]
  (when (and even odd)
    (cpr/global-position even odd)))

(defn- local-reference-position
  [{:cpr/keys [reference]} half]
  (when reference
    (cpr/local-position half (:cpr/position reference))))

(defn- position-delta+state
  "Fold one airborne position message into [delta cpr-state']: the new
  half joins the aircraft's pruned CPR entry, a global decode is
  preferred (it needs no prior trust), a local decode against the
  aircraft's own recent position is the fallback, and a message that
  completes neither still contributes its altitude — and its half, for
  the next frame to pair with."
  [icao me type-code now-ms cpr-state]
  (let [half     (cpr-half me now-ms)
        half-key (if (= :odd (:cpr/parity half)) :cpr/odd :cpr/even)
        entry    (-> (get cpr-state icao)
                     (prune-cpr-entry now-ms)
                     (assoc half-key half))
        position (or (global-pair-position entry)
                     (local-reference-position entry half))
        entry    (cond-> entry
                   position (assoc :cpr/reference
                                   {:cpr/position position
                                    :cpr/heard-at-ms now-ms}))
        delta    (cond-> {:aircraft/icao icao}
                   position (assoc :aircraft/position position))
        delta    (if-let [altitude (altitude-ft type-code me)]
                   (assoc delta :aircraft/altitude-ft altitude)
                   delta)]
    [delta (assoc cpr-state icao entry)]))

;; ---------------------------------------------------------------------
;; The decoder

(defn- me-delta+state
  "Dispatch a CRC-valid extended squitter body by type code. Everything
  out of scope still refreshes the aircraft: a bare icao delta."
  [icao payload now-ms cpr-state]
  (let [me        (subvec payload 4 11)
        type-code (bit-shift-right (nth me 0) 3)]
    (cond
      (<= 1 type-code 4)
      [(identification-delta icao me) cpr-state]

      (or (<= 9 type-code 18) (<= 20 type-code 22))
      (position-delta+state icao me type-code now-ms cpr-state)

      (= 19 type-code)
      [(velocity-delta icao payload) cpr-state]

      :else
      [{:aircraft/icao icao} cpr-state])))

(defn decode
  "Decode one 14-byte Mode-S payload heard at `now-ms` into

    {:delta     <partial :aircraft/* map, or nil>
     :cpr-state <cpr-state to hand to the next call>}

  A nil delta means the frame said nothing this system can trust: it
  failed CRC-24, is not a DF17/18 extended squitter, or is not a
  well-formed payload at all — garbage of any shape yields nil, never a
  throw. Every non-nil delta carries a valid :aircraft/icao. `cpr-state`
  (nil on the first call) threads the odd/even CPR halves and last
  decoded position per aircraft; the shape is documented on the ns."
  [payload now-ms cpr-state]
  (let [cpr-state (or cpr-state {})
        icao      (when (and (well-formed? payload)
                             (zero? (crc payload)))
                    (extended-squitter-icao payload))]
    (if icao
      (let [[delta cpr-state] (me-delta+state icao payload now-ms
                                              cpr-state)]
        {:delta delta :cpr-state cpr-state})
      {:delta nil :cpr-state cpr-state})))
