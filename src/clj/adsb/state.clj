(ns adsb.state
  "The current picture of the sky: one atom holding icao -> aircraft,
  written only by the poll loop (adsb-nqf.1) and read by SSE and the
  HTTP API. A thin shell — every rule about merging, retention, and
  aging lives in adsb.aircraft; this namespace only swaps."
  (:require
    [adsb.aircraft :as aircraft]
    [clojure.string :as str]))

(defonce ^:private picture (atom {}))

(defn apply-batch!
  "Merge one coerced feeder batch into the picture. The poll loop's
  callback: batch is what adsb.ingest.coerce/->aircraft-batch returns;
  captured-at-ms is when the payload was captured. Returns the new
  picture."
  [batch captured-at-ms]
  (swap! picture aircraft/merge-batch batch captured-at-ms))

(defn age-out!
  "Drop aircraft silent past the age-out threshold. Returns the new
  picture."
  [now-ms]
  (swap! picture aircraft/age-out now-ms))

(defn snapshot
  "The picture as of this instant: an immutable map of icao -> aircraft."
  []
  @picture)

(defn lookup
  "The aircraft last heard under this icao, or nil. Domain identities
  are lower-case (adsb.ingest.coerce); callers may pass either case —
  URLs and feeders use both."
  [icao]
  (get @picture (str/lower-case icao)))

(defn clear!
  "Empty the picture. For tests and the REPL."
  []
  (reset! picture {}))
