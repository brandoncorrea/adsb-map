(ns adsb.accumulator
  "Fold per-message field deltas into the current picture of the sky.

  The TCP feeds (SBS on :30003, Beast on :30005) deliver one field at a
  time — a callsign in this message, a position in the next, a velocity
  in the one after — where aircraft.json delivered a whole snapshot each
  poll. This namespace holds the merge that reassembles those fragments
  into per-ICAO aircraft state.

  Deltas arrive already coerced to partial :aircraft/* maps: each Source
  is the trust boundary for its own wire format and hands domain shapes
  in, so nothing here ever sees an SBS or Beast frame. Later fields win,
  absent fields persist — a delta says only what changed, so a plain
  merge is exactly right. This is the one real difference from
  adsb.aircraft/merge-batch, whose snapshots must special-case an absent
  position because there absent means 'stopped sending', not 'unchanged'.

  Pure — no I/O, no atoms, no clock. Time is an argument; the receiving
  instant (now-ms) is the freshness stamp that drives age-out. The Source
  that drives this holds the atom and reads the clock; see
  adsb.ingest.source. The picture is a map of icao -> aircraft, the same
  shape adsb.aircraft ages out."
  (:require
    [adsb.aircraft :as aircraft]))

(defn- merge-delta
  "One aircraft's step: the delta's fields overwrite the previous
  aircraft's, every other field persists, and the receiving instant
  becomes the freshness stamp age-out reads. previous is nil for a
  first-heard aircraft — merge treats that as the empty map.

  An AGED-OUT previous is nil too: an aircraft that fell silent past the
  age-out threshold and comes back is a new arrival, not a continuation.
  Inheriting its fields would let a returning airframe's first
  position-less messages resurrect an hours-old position, stamped as
  heard now — a lie the whole downstream stack would believe (adsb-gq3)."
  [previous delta now-ms]
  (-> (when-not (and previous (aircraft/aged-out? previous now-ms))
        previous)
      (merge delta)
      (assoc :aircraft/seen-at-ms now-ms)))

(defn accumulate
  "Fold one per-message delta, heard at now-ms, into the picture,
  returning the picture. The delta's :aircraft/icao keys it; a delta for
  an unheard — or aged-out — aircraft adds or revives it, since the fresh
  stamp makes it live again.

  Picture-first to match adsb.aircraft/merge-batch, its snapshot-fed
  sibling, and to fold straight through a Source's atom:
  (swap! picture accumulate delta now-ms)."
  [picture delta now-ms]
  (update picture (:aircraft/icao delta) merge-delta delta now-ms))

(defn snapshot
  "The current batch: the live aircraft as a vector, with those silent
  past the age-out threshold dropped. Reuses adsb.aircraft/aged-out? so
  the accumulator and the poller retire aircraft by the one same rule."
  [picture now-ms]
  (into []
        (comp (map val)
              (remove #(aircraft/aged-out? % now-ms)))
        picture))

(defn sweep
  "The picture with the aged-out aircraft removed — snapshot's filter,
  applied to the picture itself.

  snapshot hides an aged-out aircraft from every batch, but the picture
  it reads from keeps it forever: a Source folding into an atom across
  weeks of uptime accumulates every ICAO it has ever heard. The owner of
  that atom sweeps it periodically (adsb.ingest.tcp) — the retiring rule
  belongs here, beside the one snapshot and the poller share."
  [picture now-ms]
  (into (empty picture)
        (remove #(aircraft/aged-out? (val %) now-ms))
        picture))
