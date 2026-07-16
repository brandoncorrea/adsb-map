(ns adsb.state
  (:require [adsb.aircraft :as aircraft]
            [adsb.ingest.plausibility :as plausibility]
            [clojure.string :as str]))

(defonce ^:private picture (atom {}))

(defn apply-batch! [batch captured-at-ms]
  (swap! picture plausibility/merge-batch-flagging-jumps
         batch captured-at-ms))

(defn age-out! [now-ms] (swap! picture aircraft/age-out now-ms))
(defn snapshot [] @picture)
(defn lookup [icao] (get @picture (str/lower-case icao)))
(defn clear! [] (reset! picture {}))
