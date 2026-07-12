(ns adsb.fixtures
  "The cast: a small, fixed set of well-known aircraft that stand in for
  the real classes of input the feeder produces. The cast table in
  docs/testing-standards.md is the spec.

  Each member exists in two forms:

    <name>-raw  the feeder-shaped entry, exactly as aircraft.json would
                deliver it — space-padded callsigns, alt_baro of
                \"ground\", a string squawk, and the dozens of extra keys
                real payloads carry. Field shapes are modelled on
                test/resources/aircraft-sample.json.

    <name>      the domain aircraft, produced by pushing <name>-raw
                through the real ingest boundary, adsb.ingest.coerce/
                ->aircraft.

  Deriving the domain form through the real boundary is the point: a
  schema or coercion change must break the cast loudly rather than let it
  rot into a fiction. The domain forms are not hand-written maps that
  might drift from what ingest actually emits — they ARE what ingest
  emits.

  The cast is data, and immutable. Never mutate a member; assoc a copy."
  (:require
    [adsb.ingest.coerce :as coerce]))

;; ---------------------------------------------------------------------
;; ups-2717 — the happy path. Cargo 747, cruising, complete data. Use
;; this unless a test needs otherwise.

(def ups-2717-raw
  {:hex "abc0e4" :type "adsb_icao" :flight "UPS2717 "
   :alt_baro 34775 :alt_geom 36925
   :gs 450.5 :track 97.14 :baro_rate -960
   :squawk "6040" :emergency "none" :category "A5"
   :lat 27.961166 :lon -83.975953
   :nav_qnh 1013.6 :mlat [] :tisb []
   :messages 1848 :seen 0.4 :rssi -28.3})

(def ups-2717 (coerce/->aircraft ups-2717-raw))

;; ---------------------------------------------------------------------
;; on-the-ground — the coercion trap. alt_baro is the string "ground",
;; not a number, so it becomes an on-ground flag and never an altitude.

(def on-the-ground-raw
  {:hex "a1d645" :type "adsb_icao" :flight "N2173A  "
   :alt_baro "ground" :gs 12.5 :track 81.44
   :squawk "1200" :emergency "none" :category "A1"
   :lat 27.645950 :lon -82.497789
   :mlat [] :tisb []
   :messages 1186 :seen 0.1 :rssi -23.5})

(def on-the-ground (coerce/->aircraft on-the-ground-raw))

;; ---------------------------------------------------------------------
;; never-positioned — heard on the radio, no lat/lon ever. A bare mode_s
;; target that carries an altitude but never transmitted a position. Kept
;; as a domain aircraft WITHOUT :aircraft/position (docs/validation-
;; boundaries.md, adsb-bvi.3): it belongs in the sidebar and the counts,
;; it simply produces no map feature. The most common real-world case.

(def never-positioned-raw
  {:hex "a10202" :type "mode_s"
   :alt_baro 33000 :alt_geom 35125
   :mlat [] :tisb []
   :messages 74 :seen 3.5 :rssi -29.7})

(def never-positioned (coerce/->aircraft never-positioned-raw))

;; ---------------------------------------------------------------------
;; squawking-7700 — the alerting path. Transponder set to 7700, the
;; general-emergency code.

(def squawking-7700-raw
  {:hex "a35a92" :type "adsb_icao" :flight "DAL1275 "
   :alt_baro 10025 :gs 311.1 :track 150.96 :baro_rate -1152
   :squawk "7700" :emergency "general" :category "A3"
   :lat 28.364136 :lon -82.968063
   :nav_qnh 1020.0 :mlat [] :tisb []
   :messages 149 :seen 0.1 :rssi -26.5})

(def squawking-7700 (coerce/->aircraft squawking-7700-raw))

;; ---------------------------------------------------------------------
;; long-silent — last heard 300 seconds ago. Must age out of the map.
;; `seen` is a relative age in seconds carried by the feeder, so this
;; fixture reads no clock: staleness is decided by the caller against a
;; `now` it supplies, never by an ambient clock in cljc.

(def long-silent-raw
  {:hex "a2b3a2" :type "adsb_icao" :flight "SWA2857 "
   :alt_baro 26600 :gs 425.0 :track 270.81 :baro_rate 2176
   :squawk "1034" :emergency "none" :category "A3"
   :lat 28.432012 :lon -82.324434
   :nav_qnh 1013.6 :mlat [] :tisb []
   :messages 158 :seen 300 :rssi -27.2})

(def long-silent (coerce/->aircraft long-silent-raw))

;; ---------------------------------------------------------------------
;; mlat-derived — position from multilateration, not ADS-B. The feeder
;; signals this with `type` "mlat" and a non-empty `mlat` array listing
;; which fields were multilaterated rather than self-reported. That is
;; lower-confidence data. Coercion surfaces it as :aircraft/mlat? true
;; on the domain aircraft (adsb-nqf.5), so the UI can render this target
;; distinctly; the marker is derived through the real boundary, not
;; hand-written here.

(def mlat-derived-raw
  {:hex "a2ddac" :type "mlat" :flight "N512ML  "
   :alt_baro 34000 :gs 458.7 :track 324.40 :baro_rate 64
   :category "A3" :lat 28.796585 :lon -82.682208
   :mlat ["lat" "lon" "gs" "track" "baro_rate" "alt_baro"]
   :tisb [] :messages 4585 :seen 0.3 :rssi -29.9})

(def mlat-derived (coerce/->aircraft mlat-derived-raw))

;; ---------------------------------------------------------------------
;; The whole cast, for suite-wide assertions (every member coerces,
;; every domain member satisfies the schema). Order mirrors the cast
;; table in docs/testing-standards.md.

(def all-raw
  "Every cast member in its raw, feeder-shaped form."
  [ups-2717-raw on-the-ground-raw never-positioned-raw
   squawking-7700-raw long-silent-raw mlat-derived-raw])

(def all
  "Every cast member as a domain aircraft."
  [ups-2717 on-the-ground never-positioned
   squawking-7700 long-silent mlat-derived])
