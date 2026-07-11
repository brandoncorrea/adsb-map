(ns adsb.aircraft
  "The aircraft domain model. Pure — no I/O, no clock; time is an
  argument. The full model is TODO(adsb-bvi.3).")

(def ^:const stale-threshold-ms 60000)

(defn stale?
  "True when the aircraft has not been heard from within the stale
  threshold."
  [{:aircraft/keys [seen-at-ms]} now-ms]
  (> (- now-ms seen-at-ms) stale-threshold-ms))
