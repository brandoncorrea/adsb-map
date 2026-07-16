(ns adsb.worker)

(defn clear-timeout [id] (js/clearTimeout id))
(defn clear-interval [id] (js/clearInterval id))

(defn timeout [f delay-ms] (js/setTimeout f delay-ms))
(defn interval [f delay-ms] (js/setInterval f delay-ms))