(ns adsb.accumulator
  (:require [adsb.aircraft :as aircraft]))

(defn- merge-delta [previous delta now-ms]
  (-> (when-not (and previous (aircraft/aged-out? previous now-ms)) previous)
      (merge delta)
      (assoc :aircraft/seen-at-ms now-ms)
      (cond-> (:aircraft/position delta)
              (assoc :aircraft/position-at-ms now-ms))))

(defn accumulate [picture delta now-ms]
  (update picture (:aircraft/icao delta) merge-delta delta now-ms))

(defn snapshot [picture now-ms]
  (into []
        (comp (map val)
              (remove #(aircraft/aged-out? % now-ms)))
        picture))

(defn sweep [picture now-ms]
  (into (empty picture)
        (remove #(aircraft/aged-out? (val %) now-ms))
        picture))
