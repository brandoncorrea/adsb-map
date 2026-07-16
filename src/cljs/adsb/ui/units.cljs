(ns adsb.ui.units
  (:require [clojure.math :as math]))

(defn knots [kt]
  (when (some? kt)
    (str (math/round kt))))

(defn track [deg]
  (when (some? deg)
    (let [d (mod (math/round deg) 360)]
      (str (.padStart (str d) 3 "0") "°"))))
