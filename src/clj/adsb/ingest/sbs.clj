(ns adsb.ingest.sbs
  "The SBS Source: hold ultrafeeder's port 30003 (BaseStation) socket open
  and stream Mode-S messages into the shared per-ICAO accumulator.

  SBS is plain-text CSV — one line per Mode-S message, fields positional
  and mostly empty (a callsign in this line, a position in the next, a
  velocity in the one after). Unlike aircraft.json's whole-snapshot poll,
  each line is a fragment; adsb.accumulator reassembles the fragments into
  per-ICAO state, and fetch! ages the picture out into the same coerced
  domain batch the poll loop already expects.

  THIS IS THE TRUST BOUNDARY for the SBS wire format (the feeder is
  unauthenticated radio — docs/validation-boundaries.md). line->delta
  parses every field defensively: a malformed, truncated, or hostile line
  yields nil and is dropped inside the reader, never thrown past it, and
  never crashes the stream. Nothing SBS-shaped leaves this namespace — what
  reaches the accumulator is already a coerced, namespaced :aircraft/*
  delta with a guarded :aircraft/icao (the accumulator keys on it, so a
  delta without a valid identity is worthless and dropped).

  We consume MSG transmission types only. Their positional fields cover
  everything we model: MSG,1 callsign; MSG,2/3 position and altitude;
  MSG,4 velocity; MSG,5/6/7 altitude/squawk subsets; MSG,8 on-ground. The
  non-transmission classes (SEL, ID, AIR, STA, CLK) carry no telemetry we
  keep — an aircraft appearing (AIR) or a status click (CLK) is a fact
  about the receiver, not the sky — so they are ignored wholesale, which
  keeps the boundary narrow.

  A LINE'S FIELDS drive an accumulate at message-arrival time
  (swap! picture accumulate delta now-ms), so freshness is stamped when we
  hear a message, not when fetch! happens to run. The socket read runs on a
  daemon reader thread the Source owns; open! starts it, close! stops it.

  UNREACHABLE vs QUIET. fetch! throws when the stream is not connected —
  never established, or dropped and not yet reconnected — so the poll
  loop's backoff engages (adsb.ingest.poll). A connected-but-silent feed is
  NOT unreachable: fetch! returns the current snapshot, which ages out on
  its own as the silence lengthens. The reader reconnects on its own after
  a drop, so a transient outage self-heals into a fetch! that stops
  throwing."
  (:require [adsb.ingest.source :as source]
            [adsb.ingest.tcp :as tcp]
            [adsb.schema :as schema]
            [clojure.string :as str]
            [malli.core :as m])
  (:import (java.io BufferedReader InputStream InputStreamReader)
           (java.nio.charset StandardCharsets)))

;; ---------------------------------------------------------------------
;; The wire format: BaseStation CSV field positions (0-based). Fields are
;; positional and frequently empty; a short line simply has fewer of them.

(def ^:private message-type-field 0)
(def ^:private icao-field 4)
(def ^:private callsign-field 10)
(def ^:private altitude-field 11)
(def ^:private ground-speed-field 12)
(def ^:private track-field 13)
(def ^:private latitude-field 14)
(def ^:private longitude-field 15)
(def ^:private vertical-rate-field 16)
(def ^:private squawk-field 17)
(def ^:private on-ground-field 21)

(def ^:private transmission-message
  "The only message-type field we consume — MSG lines carry telemetry;
  SEL/ID/AIR/STA/CLK do not."
  "MSG")

(def ^:private squawk-digit-count 4)

;; ---------------------------------------------------------------------
;; Field coercion — each field to a domain value, or nil to drop the field.
;; Validate once, here, then trust: numerics are parsed without ever
;; evaluating the string (never read-string on network data), non-finite
;; values are rejected, and every coercion reuses the domain schema so the
;; SBS boundary and the JSON boundary agree on what is valid.

(def ^:private valid-icao? (m/validator schema/icao-address))
(def ^:private valid-callsign? (m/validator [:string {:min 1 :max 8}]))
(def ^:private valid-latitude? (m/validator schema/latitude))
(def ^:private valid-longitude? (m/validator schema/longitude))
(def ^:private valid-squawk? (m/validator schema/squawk))
(def ^:private plausible-altitude? (m/validator schema/plausible-altitude-ft))
(def ^:private plausible-ground-speed?
  (m/validator schema/plausible-ground-speed-kt))

(defn- field
  "The value at `idx`, trimmed to nil when blank or absent. A short line
  (fewer fields than idx) reads as nil, exactly like an empty field."
  [fields idx]
  (some-> (nth fields idx nil) str/trim not-empty))

(defn- ->double
  "A finite double from a numeric string, or nil. Rejects NaN/Infinity —
  a hostile decoder can emit \"NaN\", and it must not reach the domain."
  [s]
  (when-let [n (some-> s parse-double)]
    (when (Double/isFinite n) n)))

(defn- ->long
  "A long from an integer string, or nil. SBS altitude and vertical rate
  are whole feet / feet-per-minute."
  [s]
  (some-> s parse-long))

(defn- ->icao
  "The lower-cased ICAO identity, or nil when the field is missing or not a
  valid six-hex address. The accumulator keys on this; a garbage identity
  is a dropped delta."
  [s]
  (when-let [hex (some-> s str/lower-case)]
    (when (valid-icao? hex) hex)))

(defn- ->callsign
  "A trimmed callsign of 1-8 chars, or nil. SBS space-pads callsigns just
  like aircraft.json, and a hostile over-long one is dropped."
  [s]
  (when (and s (valid-callsign? s)) s))

(defn- ->position
  "A domain position from the latitude/longitude fields, or nil unless BOTH
  are present, numeric, and in range. An out-of-range coordinate drops the
  position (never clamped) while the rest of the delta survives."
  [lat-s lon-s]
  (let [lat (->double lat-s)
        lon (->double lon-s)]
    (when (and lat lon (valid-latitude? lat) (valid-longitude? lon))
      {:geo/lat lat :geo/lon lon})))

(defn- ->altitude-ft
  "Altitude in feet, or nil when absent or physically implausible. An
  absurd-but-well-typed altitude costs the field, never the aircraft
  (docs/validation-boundaries.md)."
  [s]
  (when-let [ft (->long s)]
    (when (plausible-altitude? ft) ft)))

(defn- ->ground-speed-kt
  "Ground speed in knots, or nil when absent or implausible (> 1000 kt)."
  [s]
  (when-let [kt (->double s)]
    (when (plausible-ground-speed? kt) kt)))

(defn- pad-squawk
  "Left-pad a squawk to four digits, recovering a leading-zero-stripped
  form (\"21\" -> \"0021\"). An over-long or non-octal value stays invalid
  and is rejected by valid-squawk?."
  [s]
  (str (subs "000" 0 (max 0 (- squawk-digit-count (count s)))) s))

(defn- ->squawk
  "Four octal digits as a string, or nil. \"0000\" is a real squawk, not
  nil; a non-octal or over-long value is dropped."
  [s]
  (when-let [squawk (some-> s pad-squawk)]
    (when (valid-squawk? squawk) squawk)))

(defn- ->on-ground?
  "true when the on-ground flag is set (SBS emits -1, some feeders 1),
  else nil. Like the domain marker this is true-or-absent: \"0\" (airborne)
  means the field simply says nothing, never an explicit false."
  [s]
  (when (#{"-1" "1"} s) true))

;; ---------------------------------------------------------------------
;; One line -> one delta

(defn- msg?
  "true when the line is a MSG transmission — the only class we consume."
  [fields]
  (= transmission-message (nth fields message-type-field nil)))

(defn line->delta
  "Coerce one SBS BaseStation line into a partial :aircraft/* delta, or nil
  to drop it. nil covers every unusable line: a non-MSG class, a line with
  no valid ICAO identity, and a malformed/truncated/hostile line whose
  fields all fail coercion. Never throws — the reader must survive noisy
  radio. The delta carries only fields the line actually populated, so
  later messages fill in what earlier ones left absent (the accumulator's
  job)."
  [line]
  (let [fields (str/split line #"," -1)]
    (when (msg? fields)
      (when-let [icao (->icao (field fields icao-field))]
        (let [callsign  (->callsign (field fields callsign-field))
              position  (->position (field fields latitude-field)
                                    (field fields longitude-field))
              altitude  (->altitude-ft (field fields altitude-field))
              speed     (->ground-speed-kt (field fields ground-speed-field))
              track     (->double (field fields track-field))
              vert-rate (->long (field fields vertical-rate-field))
              squawk    (->squawk (field fields squawk-field))
              on-ground (->on-ground? (field fields on-ground-field))]
          (cond-> {:aircraft/icao icao}
            callsign  (assoc :aircraft/callsign callsign)
            position  (assoc :aircraft/position position)
            altitude  (assoc :aircraft/altitude-ft altitude)
            speed     (assoc :aircraft/ground-speed-kt speed)
            track     (assoc :aircraft/track-deg track)
            vert-rate (assoc :aircraft/baro-rate-fpm vert-rate)
            squawk    (assoc :aircraft/squawk squawk)
            on-ground (assoc :aircraft/on-ground? on-ground)))))))

;; ---------------------------------------------------------------------
;; The reader: fold each line into the picture at arrival time. Effects
;; live here; line->delta stays pure. The connection lifecycle — dialing,
;; reconnecting, the reader thread, stall detection, the
;; unreachable-vs-quiet fetch! — belongs to adsb.ingest.tcp; this ns
;; supplies only the per-line pump over whatever InputStream the transport
;; (plain socket or websocket) hands it.

(defn- consume-line!
  "Fold one line's delta into the picture at its arrival instant —
  notifying the optional :on-delta hook with the merged aircraft
  (tcp/accumulate!) — or do nothing when the line yields no delta."
  [{:keys [clock] :as state} line]
  (when-let [delta (line->delta line)]
    (tcp/accumulate! state delta (clock))))

(defn- read-lines!
  "Read and consume lines until EOF, the socket closes, or the Source
  stops. Returns on any of those; tcp reconnects if still running."
  [^BufferedReader reader {:keys [running?] :as state}]
  (loop []
    (when @running?
      (when-let [line (.readLine reader)]
        (consume-line! state line)
        (recur)))))

(defn- consume!
  "The tcp reader seam: pump the connection's ASCII lines into the picture
  until the connection ends. tcp/serve-connection! owns closing the stream,
  so a read that throws (a drop, or the SO_TIMEOUT stall signal) unwinds
  straight to reconnect."
  [^InputStream in state]
  (let [reader (BufferedReader.
                (InputStreamReader. in StandardCharsets/US_ASCII))]
    (read-lines! reader state)))

;; ---------------------------------------------------------------------
;; The Source

(defrecord SbsSource [host port transport connect-timeout-ms idle-timeout-ms
                      reconnect-ms clock on-delta consume! thread-name
                      picture swept-at-ms running? connected? last-error
                      connection reader-thread]
  source/Source
  (open! [this] (tcp/open! this))
  (fetch! [this] (tcp/snapshot-or-throw! this))
  (close! [this] (tcp/close! this)))

(defn ->source
  "A Source streaming SBS BaseStation messages from `host`:`port` (the
  ultrafeeder's port 30003). open! starts the reader; fetch! returns the
  accumulated, coerced domain batch (throwing while disconnected so the
  poll loop backs off); close! stops the reader and closes the connection.

  Explicit host/port mirrors ultrafeeder/->source; ADSB_SOURCE selection
  and the URL-to-transport wiring live in adsb.main / adsb.ingest.config.
  Options are adsb.ingest.tcp/reader-state's: :transport (default the
  plain-socket transport; adsb.ingest.wss/transport for the tunnel),
  :connect-timeout-ms, :idle-timeout-ms, :reconnect-ms, the injectable
  :clock, and the optional :on-delta hook fired with each message's
  merged aircraft (adsb-jpf; see adsb.ingest.tcp)."
  ([host port] (->source host port {}))
  ([host port opts]
   (map->SbsSource
    (tcp/reader-state host port opts consume! "adsb-sbs-reader"))))
