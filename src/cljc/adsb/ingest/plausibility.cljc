(ns adsb.ingest.plausibility
  "The second validation layer: physical plausibility, separate from
  schema validity (docs/validation-boundaries.md). A schema-valid
  position can still be nonsense — past the antenna's horizon, or an
  impossible teleport between two polls. Per-field absurdity (400,000
  ft) is adsb.ingest.coerce/drop-implausible-fields; this namespace
  owns the two checks that need more than one field:

  - RANGE GATE: a position beyond the receiver's plausible horizon
    DROPS the aircraft — it did not come from the sky this antenna
    can see. No receiver position disables the gate entirely; the
    edge that resolves the position (adsb.ingest.receiver) logs that
    once at setup.

  - JUMP FLAGGING: a new position implying an impossible speed from
    the same aircraft's previous observation sets
    :aircraft/position-suspect? true. FLAGGED, never dropped, never
    clamped — a jump is the fingerprint of spoofing and must surface.
    The flag means 'this track has made at least one impossible jump
    since we picked it up', and it sticks: nothing the aircraft sends
    clears it, on either ingest path. Only the age-out sweep does, by
    forgetting the track entirely (adsb-caf).

  Pure: the receiver position, the previous picture, and time are all
  arguments. Fetching the receiver position is I/O and lives at the
  clj edge.

  Both fold seams live here, one per ingest shape, because both are the
  one place an aircraft's PREVIOUS observation is still in hand:
  merge-batch-flagging-jumps for the poll path's snapshots
  (adsb.state/apply-batch!) and accumulate-flagging-jumps for the
  streaming path's per-message deltas (adsb.ingest.tcp/accumulate!).
  A flag set on one path is honoured by the other — the streaming
  snapshot the poll loop takes carries the flag its deltas earned."
  (:require [adsb.accumulator :as accumulator]
            [adsb.aircraft :as aircraft]
            [adsb.geo :as geo]))

;; ---------------------------------------------------------------------
;; Range gate

(def ^:const default-max-range-m
  "~400 km (~216 nm) — generous for ADS-B: even exceptional tropo
  conditions rarely carry 1090 MHz much past 250 nm, so anything
  further did not come from this antenna's sky."
  400000)

(defn beyond-horizon?
  "True when the aircraft reports a position strictly further from the
  receiver than max-range-m. False for a position-less aircraft."
  [aircraft receiver-position max-range-m]
  (boolean
    (when-let [position (:aircraft/position aircraft)]
      (> (geo/distance receiver-position position) max-range-m))))

(defn gate-range
  "The batch without aircraft whose position lies beyond the
  receiver's plausible horizon — dropped, never clamped. Position-less
  aircraft pass; there is nothing to gate. A nil receiver-position
  DISABLES the gate and returns the batch untouched — a feeder that
  cannot say where it is must not cost aircraft blindly; the edge
  logs the disabled gate once (adsb.ingest.receiver)."
  [batch receiver-position max-range-m]
  (if receiver-position
    (into []
          (remove #(beyond-horizon? % receiver-position max-range-m))
          batch)
    batch))

(def ^:const ^:private ms-per-hour 3600000)

(defn position-jump?
  "True when reaching `position` at `observed-at-ms` from the
  aircraft's previous observation implies a speed strictly above
  max-implied-speed-kt. Zero-or-negative elapsed time with a changed
  position is the most impossible jump of all; with an unchanged
  position it is just the feeder repeating itself."
  [{previous-position :aircraft/position
    previous-at-ms    :aircraft/seen-at-ms}
   position observed-at-ms max-implied-speed-kt]
  (let [elapsed-ms (- observed-at-ms previous-at-ms)
        distance-m (geo/distance previous-position position)]
    (if (pos? elapsed-ms)
      (> (/ (geo/meters->nm distance-m)
            (/ elapsed-ms ms-per-hour))
         max-implied-speed-kt)
      (pos? distance-m))))

(defn- flag-position-jump
  "One aircraft's step of flag-position-jumps. Suspect once it jumps,
  suspect for the rest of its life in the picture: the flag is carried
  forward from the previous entry, never recomputed away."
  [picture {:aircraft/keys [icao position] :as observation}
   captured-at-ms max-implied-speed-kt]
  (let [previous (get picture icao)
        jumped?  (and position
                      (aircraft/positioned? previous)
                      (position-jump? previous position
                                      (aircraft/observed-at-ms observation
                                                               captured-at-ms)
                                      max-implied-speed-kt))]
    (cond-> observation
            (or jumped? (:aircraft/position-suspect? previous))
            (assoc :aircraft/position-suspect? true))))

;; ---------------------------------------------------------------------
;; Position-jump flagging

(def ^:const default-max-implied-speed-kt
  "Above every real aircraft in this sky, with headroom over the
  per-field ceiling (adsb.schema/max-plausible-ground-speed-kt) so
  the two layers never disagree about a fast-but-real aircraft."
  1200)

(defn flag-position-jumps
  "The batch with :aircraft/position-suspect? true on every aircraft
  whose new position implies an impossible speed from its previous
  observation in `picture`. Nothing is dropped or clamped.

  Clearing rule: NOTHING an aircraft transmits clears the flag. Once a
  track jumps it stays suspect for the rest of its life in the picture,
  positioned or silent, however consistently it behaves afterwards
  (adsb-caf). A track that could clear its own mark by settling down
  would only need one plausible message to launder a spoof, and the
  operator would never see the teleport that earned it. The flag decays
  at exactly one boundary: the age-out sweep
  (adsb.aircraft/age-out). An aircraft re-acquired after five minutes of
  silence is a new track to us and starts clean.

  A first-ever position has nothing to jump from and is never flagged."
  [picture batch captured-at-ms max-implied-speed-kt]
  (mapv #(flag-position-jump picture % captured-at-ms
                             max-implied-speed-kt)
        batch))

(defn merge-batch-flagging-jumps
  "adsb.aircraft/merge-batch with jump flagging composed in front:
  flag the batch against the previous picture, then merge. The state
  store's merge step (adsb.state/apply-batch!) — the one place the
  previous picture is available."
  [picture batch captured-at-ms]
  (aircraft/merge-batch picture
                        (flag-position-jumps picture batch
                                             captured-at-ms
                                             default-max-implied-speed-kt)
                        captured-at-ms))

(defn accumulate-flagging-jumps
  "adsb.accumulator/accumulate with jump flagging composed in front:
  flag the delta against the aircraft's previous entry in `picture`,
  then fold it in. The streaming Sources' fold
  (adsb.ingest.tcp/accumulate!) — the poll path's
  merge-batch-flagging-jumps, on the shape a stream delivers.

  It has to be HERE and not further downstream: the streaming fast lane
  hands each merged aircraft straight to the broadcaster, so a delta
  flagged nowhere is a teleport broadcast as a clean aircraft (adsb-b36).
  The delta carries no stamp of its own — heard-at-ms IS when it was
  heard — so the implied speed is measured from the previous entry's
  :aircraft/seen-at-ms, which the accumulator stamps honestly at arrival
  (adsb-0g0): a reception gap reads as a gap, not as a teleport.

  The flag STICKS here, as it does on the poll path: a delta says only
  what changed and never carries :aircraft/position-suspect?, so the
  accumulator's plain merge would keep it anyway — flag-position-jump
  carries it forward explicitly so the lifetime is one rule stated in
  one place rather than an accident of two different merges (adsb-caf).
  That stickiness is what makes flagging per message worth doing: an
  upsert is a full-state replacement at the client, so a track caught
  teleporting must not launder the flag away with its very next
  message."
  [picture delta heard-at-ms]
  (accumulator/accumulate picture
                          (flag-position-jump picture delta heard-at-ms
                                              default-max-implied-speed-kt)
                          heard-at-ms))
