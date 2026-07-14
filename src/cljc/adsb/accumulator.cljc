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
  first-heard aircraft — merge treats that as the empty map."
  [previous delta now-ms]
  (-> (merge previous delta)
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
