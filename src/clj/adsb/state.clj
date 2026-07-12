(ns adsb.state
  "The current picture of the sky: one atom holding icao -> aircraft,
  written only by the poll loop (adsb-nqf.1) and read by SSE and the
  HTTP API. A thin shell — every rule about merging, retention, aging,
  and position-jump flagging lives in adsb.aircraft and
  adsb.ingest.plausibility; this namespace only swaps."
  (:require [adsb.aircraft :as aircraft]
            [adsb.ingest.plausibility :as plausibility]
            [clojure.string :as str]))

(defonce ^:private picture (atom {}))

(defn apply-batch!
  "Merge one coerced feeder batch into the picture, flagging
  impossible position jumps against the previous picture — the one
  place prior state is available (adsb.ingest.plausibility). The poll
  loop's callback: batch is what adsb.ingest.coerce/->aircraft-batch
  returns, range-gated at the edge before it gets here;
  captured-at-ms is when the payload was captured. Returns the new
  picture."
  [batch captured-at-ms]
  (swap! picture plausibility/merge-batch-flagging-jumps
         batch captured-at-ms))

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
