(ns adsb.ingest.coerce
  "The ingest trust boundary: raw feeder entries in, domain aircraft out.

  Everything downstream trusts what leaves this namespace completely —
  see docs/validation-boundaries.md. One malformed entry must never kill
  a batch, and the poll loop feeding it must never die; a reject costs
  one bounded log line, never the whole payload."
  (:require [adsb.schema :as schema]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            #?(:clj [clojure.tools.logging :as log])))

;; ---------------------------------------------------------------------
;; Schema pass

(def ^:private valid-raw? (m/validator schema/raw-aircraft))

(def ^:private explain-raw (m/explainer schema/raw-aircraft))

(def ^:private decode-raw
  (m/decoder schema/raw-aircraft (mt/json-transformer)))

(defn- coerce-raw
  "Schema-checked, JSON-decoded raw entry, or nil when it isn't one."
  [raw]
  (when (map? raw)
    (let [entry (decode-raw raw)]
      (when (valid-raw? entry)
        entry))))

;; ---------------------------------------------------------------------
;; Feeder vocabulary -> domain vocabulary

(defn- mlat-derived?
  "Does this raw entry's position come from multilateration rather than
  the aircraft's own ADS-B? readsb signals MLAT two ways and either is
  enough to lower our confidence: the entry's `type` is \"mlat\", or its
  `mlat` array names the fields it multilaterated (a mode_s target with
  no ADS-B position falls back to MLAT and lists lat/lon there). The
  union is deliberate — a bare `type` check would miss mode_s entries
  whose position is MLAT, and a bare array check would miss a type
  \"mlat\" entry that happened to arrive with an empty array. Either
  fires this true-only marker; neither leaves it absent."
  [{:keys [type mlat]}]
  (or (= "mlat" type)
      (boolean (seq mlat))))

(def ^:private known-category?
  (m/validator schema/emitter-category))

(defn- emitter-category
  "The aircraft's self-reported emitter category, or nil when it reported
  none — and ALSO nil when it reported something we do not recognize.

  This is a validate-here-not-in-the-schema field, and deliberately so
  (schema/emitter-category says why): the feeder is untrusted, so an
  attacker-supplied string must never reach the domain, but a category we
  simply failed to enumerate must not cost the aircraft its place in the
  picture either. Checking it as a field buys both — anything outside the
  closed enum, of any type, is ABSENCE, and absence already means the
  generic plane."
  [category]
  (when (known-category? category)
    category))

(defn- raw->aircraft
  "Rename feeder fields into namespaced domain keys. Absent (or null)
  stays absent — an aircraft with no reported altitude is not at sea
  level, and one with no reported speed is not stationary."
  [{:keys [hex flight alt_baro lat lon squawk gs track baro_rate
           seen seen_pos rssi category] :as raw}]
  (let [callsign (some-> flight str/trim not-empty)
        category (emitter-category category)]
    (cond-> {:aircraft/icao (str/lower-case hex)}
            callsign (assoc :aircraft/callsign callsign)
            category (assoc :aircraft/category category)
            (and lat lon) (assoc :aircraft/position {:geo/lat lat :geo/lon lon})
            (number? alt_baro) (assoc :aircraft/altitude-ft alt_baro)
            ;; alt_baro is the string "ground" on the tarmac, not a number.
            (= "ground" alt_baro) (assoc :aircraft/on-ground? true)
            squawk (assoc :aircraft/squawk squawk)
            gs (assoc :aircraft/ground-speed-kt gs)
            track (assoc :aircraft/track-deg track)
            baro_rate (assoc :aircraft/baro-rate-fpm baro_rate)
            seen (assoc :aircraft/seen-s seen)
            ;; seen_pos is seconds since the POSITION was last updated —
            ;; not since the last message, which is `seen` above and is
            ;; routinely much smaller (this fixture: seen 2.8, seen_pos
            ;; 6.765). The jump detector divides by this one (adsb-zxk).
            seen_pos (assoc :aircraft/position-seen-s seen_pos)
            rssi (assoc :aircraft/rssi rssi)
            ;; True only for MLAT-derived positions; absent otherwise, so
            ;; the wire can omit it like on-ground? and position-suspect?.
            (mlat-derived? raw) (assoc :aircraft/mlat? true))))

;; ---------------------------------------------------------------------
;; Plausibility pass — a separate layer from schema validity

(def ^:private plausible-altitude?
  (m/validator schema/plausible-altitude-ft))

(def ^:private plausible-ground-speed?
  (m/validator schema/plausible-ground-speed-kt))

(defn- drop-implausible-fields
  "An absurd-but-well-typed value (400,000 ft; 3,000 kt) costs the
  FIELD, never the aircraft — and is never clamped into range."
  [{:aircraft/keys [altitude-ft ground-speed-kt] :as aircraft}]
  (cond-> aircraft
          (and altitude-ft (not (plausible-altitude? altitude-ft)))
          (dissoc :aircraft/altitude-ft)

          (and ground-speed-kt (not (plausible-ground-speed? ground-speed-kt)))
          (dissoc :aircraft/ground-speed-kt)))

(defn ->aircraft
  "Coerce one raw feeder entry into a domain aircraft, or nil when it
  cannot be one (not schema-valid, or no usable hex identity).

  Position-less aircraft are kept: heard-but-never-positioned is the
  most common real class of input, and it belongs in the sidebar even
  though it gets no map feature. See docs/validation-boundaries.md."
  [raw]
  (some-> raw
          coerce-raw
          raw->aircraft
          drop-implausible-fields))

;; ---------------------------------------------------------------------
;; Rejection logging — one bounded line per reject

(def ^:private max-logged-hex-chars 24)
(def ^:private max-logged-reason-chars 240)

(defn- bounded
  "A loggable slice of anything, truncated so a hostile entry cannot
  fill the disk through the log."
  [x limit]
  (let [s (str x)]
    (subs s 0 (min (count s) limit))))

(defn- rejection-reason
  [raw]
  (-> raw explain-raw me/humanize))

(defn- log-rejection!
  "One bounded log line per rejected entry — enough context to debug,
  never the whole payload. Returns nil so `keep` drops the entry."
  [raw reason]
  (let [context {:hex   (bounded (when (map? raw) (:hex raw))
                                 max-logged-hex-chars)
                 :error (bounded reason max-logged-reason-chars)}]
    #?(:clj  (log/warn "Rejected aircraft" context)
       :cljs (.warn js/console "Rejected aircraft" (pr-str context)))
    nil))

(defn- ->aircraft-or-log!
  "->aircraft with the batch's survival guarantee: a schema reject logs
  and yields nil; an unexpected throw is caught, logged, and yields nil."
  [raw]
  (try
    (or (->aircraft raw)
        (log-rejection! raw (rejection-reason raw)))
    (catch #?(:clj Exception :cljs :default) e
      (log-rejection! raw (ex-message e)))))

(defn ->aircraft-batch
  "Coerce a whole feeder batch into domain aircraft. A malformed entry
  yields the rest of the batch plus one log line — never an exception."
  [raw-entries]
  (->> raw-entries
       (keep ->aircraft-or-log!)
       vec))