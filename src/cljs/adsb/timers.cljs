(ns adsb.timers
  "The scheduler interop edge — effectful wholesale. Thin mirrors of the
  browser's timer primitives (setTimeout/setInterval), bare like all
  interop mirrors: the timers/ alias is the warning. Tests redef these to
  drive callbacks synchronously without a real clock.")

(defn timeout [f delay-ms] (js/setTimeout f delay-ms))

(defn clear-timeout [id] (js/clearTimeout id))

(defn interval [f delay-ms] (js/setInterval f delay-ms))

(defn clear-interval [id] (js/clearInterval id))
