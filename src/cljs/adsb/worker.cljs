(ns adsb.worker)

(defn clear-timeout [id] (js/clearTimeout id))

(defn timeout [f delay-ms] (js/setTimeout f delay-ms))
