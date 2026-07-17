(ns adsb.picture
  "Folding observations into the tracked picture — a map of icao ->
  aircraft — and reading it back out. Two merge strategies sit side by
  side: `merge-batch` for a whole poll snapshot, `accumulate` for a
  single per-message delta. They differ only in their observation
  vocabulary — the poll path stamps against a captured-at-ms, the
  streaming path against a heard-at-ms — and in the granularity they
  fold. `sweep` drops aged-out entries; `snapshot` reads the live
  picture out as a batch. Per-aircraft predicates and thresholds live in
  adsb.aircraft."
  (:require [adsb.aircraft :as aircraft]))

;; --- poll snapshots: a whole aircraft.json batch, captured-at-ms ---

(defn- ->observation [aircraft captured-at-ms]
  (cond-> (-> aircraft
              (dissoc :aircraft/seen-s :aircraft/position-seen-s)
              (assoc :aircraft/seen-at-ms
                     (aircraft/observed-at-ms aircraft captured-at-ms)))
          (aircraft/positioned? aircraft)
          (assoc :aircraft/position-at-ms
                 (aircraft/position-observed-at-ms aircraft captured-at-ms))))

(defn- merge-observation [previous observation]
  (if (or (aircraft/positioned? observation)
          (not (aircraft/positioned? previous)))
    observation
    (merge observation
           (select-keys previous [:aircraft/position
                                  :aircraft/position-at-ms]))))

(defn merge-batch
  "Poll-path merge: fold a whole aircraft.json batch, captured at
  captured-at-ms, into the picture. Each aircraft's feeder-relative
  `seen` is resolved to an absolute stamp against the capture instant.
  The ingest path behind adsb.state/apply-batch!."
  [picture batch captured-at-ms]
  (reduce
    (fn [merged aircraft]
      (update merged (:aircraft/icao aircraft)
              merge-observation (->observation aircraft captured-at-ms)))
    picture
    batch))

;; --- per-message deltas: one decoded message, heard-at-ms ---

(defn- merge-delta [previous delta heard-at-ms]
  (-> (when-not (and previous (aircraft/aged-out? previous heard-at-ms)) previous)
      (merge delta)
      (assoc :aircraft/seen-at-ms heard-at-ms)
      (cond-> (:aircraft/position delta)
              (assoc :aircraft/position-at-ms heard-at-ms))))

(defn accumulate
  "Streaming-path merge: fold one decoded message, heard at heard-at-ms,
  into the picture. A message for an aircraft that had already aged out
  revives it as a fresh arrival, inheriting nothing from before the
  silence. The ingest path behind the SBS/Beast readers (adsb.ingest.tcp)."
  [picture delta heard-at-ms]
  (update picture (:aircraft/icao delta) merge-delta delta heard-at-ms))

;; --- reading the picture back out ---

(defn snapshot
  "The live picture as a batch (a vector), aged-out entries dropped."
  [picture now-ms]
  (into []
        (comp (map val)
              (remove #(aircraft/aged-out? % now-ms)))
        picture))

(defn sweep
  "The picture with aged-out entries evicted — same shape in, same shape
  out. Both merge paths share it: the poll path sweeps its merged picture,
  the streaming readers sweep the atom before folding a delta."
  [picture now-ms]
  (into (empty picture)
        (remove #(aircraft/aged-out? (val %) now-ms))
        picture))
