(ns adsb.ingest.cpr
  "Compact Position Reporting — the position math of DF17 decode.

  ADS-B airborne position messages carry latitude/longitude as 17-bit
  CPR values in one of two alternating encodings (even/odd). One frame
  alone is ambiguous — the same bits repeat every latitude zone — so a
  position is recovered either GLOBALLY, from a recent even/odd pair,
  or LOCALLY, from one frame plus a reference position already known to
  be within half a zone (adsb.ingest.mode-s owns which applies when).

  Pure math over plain maps; no state, no clock, no I/O. A CPR half is

    {:cpr/parity      :even | :odd
     :cpr/lat         <int 0..131071 — the 17-bit CPR latitude>
     :cpr/lon         <int 0..131071 — the 17-bit CPR longitude>
     :cpr/heard-at-ms <ms — arrival instant, stamps which half is newest>}

  and results are the domain's {:geo/lat _ :geo/lon _}, or nil when the
  pair is inconsistent — never a guess.

  Reference: 'The 1090MHz Riddle' (Junzi Sun, mode-s.org), ch. Airborne
  Position; the worked examples there are this namespace's test vectors.")

(def ^:const cpr-scale
  "2^17 — a CPR value is a zone fraction numerator over this."
  131072.0)

(def ^:const even-zone-count 60)

(def ^:const odd-zone-count 59)

(def ^:private nz
  "Number of geographic latitude zones between the equator and a pole —
  the NZ constant of the CPR spec (ICAO Annex 10)."
  15)

(defn nl
  "The number of longitude zones at latitude `lat` (degrees), 1..59 —
  the NL lookup of the CPR spec, computed in closed form. The equator
  and ±87° are the spec's special cases; beyond ±87° a single zone
  rings the pole."
  [lat]
  (let [lat (Math/abs (double lat))]
    (cond
      (zero? lat) 59
      (== lat 87) 2
      (> lat 87)  1
      :else
      (let [zone-height (- 1 (Math/cos (/ Math/PI (* 2 nz))))
            lat-squash  (let [c (Math/cos (* (/ Math/PI 180) lat))]
                          (* c c))]
        (long (Math/floor (/ (* 2 Math/PI)
                             (Math/acos (- 1 (/ zone-height
                                                lat-squash))))))))))

(defn- zone-count
  "How many latitude zones this parity divides the circle into: 60 even,
  59 odd — the off-by-one that makes the pair globally decodable."
  [parity]
  (if (= :odd parity) odd-zone-count even-zone-count))

(defn- normalize-lon
  "Longitude wrapped into [-180, 180) — same fold adsb.geo/destination
  uses."
  [lon]
  (-> lon (+ 540.0) (mod 360.0) (- 180.0)))

(defn- lat-in-range?
  [lat]
  (<= -90.0 lat 90.0))

(defn- global-lat
  "One parity's latitude from the shared zone index `j`: southern-
  hemisphere zones come out of the formula shifted by a full turn, so
  anything at or past 270 folds back negative."
  [parity j cpr-lat-fraction]
  (let [zones (zone-count parity)
        lat   (* (/ 360.0 zones) (+ (mod j zones) cpr-lat-fraction))]
    (if (>= lat 270.0) (- lat 360.0) lat)))

(defn global-position
  "The globally unambiguous position from an even/odd pair of CPR
  halves, as {:geo/lat _ :geo/lon _}. The newest half (by
  :cpr/heard-at-ms) supplies the position; the older one disambiguates
  the zone. nil when the pair is inconsistent: an impossible latitude,
  or an NL mismatch — the aircraft crossed a latitude zone between the
  two frames, so they describe different groundtracks and decode of
  this pair would be a guess. The caller waits for the next frame."
  [even odd]
  (let [lat-fraction-even (/ (:cpr/lat even) cpr-scale)
        lat-fraction-odd  (/ (:cpr/lat odd) cpr-scale)
        j                 (Math/floor
                            (+ 0.5
                               (- (* odd-zone-count lat-fraction-even)
                                  (* even-zone-count lat-fraction-odd))))
        lat-even          (global-lat :even j lat-fraction-even)
        lat-odd           (global-lat :odd j lat-fraction-odd)]
    (when (and (lat-in-range? lat-even)
               (lat-in-range? lat-odd)
               (= (nl lat-even) (nl lat-odd)))
      (let [odd-newest?       (> (:cpr/heard-at-ms odd)
                                 (:cpr/heard-at-ms even))
            lat               (if odd-newest? lat-odd lat-even)
            zones             (nl lat)
            lon-fraction-even (/ (:cpr/lon even) cpr-scale)
            lon-fraction-odd  (/ (:cpr/lon odd) cpr-scale)
            m                 (Math/floor
                                (+ 0.5
                                   (- (* lon-fraction-even (dec zones))
                                      (* lon-fraction-odd zones))))
            n                 (max 1 (if odd-newest? (dec zones) zones))
            lon-fraction      (if odd-newest?
                                lon-fraction-odd
                                lon-fraction-even)
            lon               (* (/ 360.0 n) (+ (mod m n) lon-fraction))]
        {:geo/lat lat
         :geo/lon (normalize-lon lon)}))))

(defn local-position
  "The locally unambiguous position from one CPR half and a `reference`
  position ({:geo/lat _ :geo/lon _}) known to be within half a zone
  (~180 nm) of the aircraft — its own last decoded position, refreshed
  recently enough that it cannot have flown out of the zone. The result
  is by construction the candidate nearest the reference; a reference
  that far wrong would decode a wrong zone silently, which is why the
  caller ages references out. nil only for an impossible latitude."
  [{:cpr/keys [parity] :as half} reference]
  (let [zones        (zone-count parity)
        zone-height  (/ 360.0 zones)
        lat-fraction (/ (:cpr/lat half) cpr-scale)
        ref-lat      (:geo/lat reference)
        j            (+ (Math/floor (/ ref-lat zone-height))
                        (Math/floor
                          (+ 0.5
                             (- (/ (mod ref-lat zone-height) zone-height)
                                lat-fraction))))
        lat          (* zone-height (+ j lat-fraction))]
    (when (lat-in-range? lat)
      (let [n            (max 1 (- (nl lat) (if (= :odd parity) 1 0)))
            zone-width   (/ 360.0 n)
            lon-fraction (/ (:cpr/lon half) cpr-scale)
            ref-lon      (:geo/lon reference)
            m            (+ (Math/floor (/ ref-lon zone-width))
                            (Math/floor
                              (+ 0.5
                                 (- (/ (mod ref-lon zone-width) zone-width)
                                    lon-fraction))))]
        {:geo/lat lat
         :geo/lon (normalize-lon (* zone-width (+ m lon-fraction)))}))))
