(ns adsb.aircraft)

(def ^:const stale-threshold-ms 60000)

;; Also the only decay boundary for :aircraft/position-suspect? — see
;; validation-boundaries.md. 5min -> 2min in adsb-rg1.
(def ^:const age-out-threshold-ms 120000)

(defn positioned? [aircraft]
  (contains? aircraft :aircraft/position))

(defn display-name [{:aircraft/keys [callsign icao]}]
  (or callsign icao))

(def ^:const squawk->emergency-kind
  {"7500" :hijack
   "7600" :radio-failure
   "7700" :general})

(defn emergency-kind [aircraft]
  (squawk->emergency-kind (:aircraft/squawk aircraft)))

(defn emergency? [aircraft]
  (-> aircraft emergency-kind some?))

(defn stale? [aircraft now-ms]
  (> (- now-ms (:aircraft/seen-at-ms aircraft))
     stale-threshold-ms))

(defn aged-out? [aircraft now-ms]
  (> (- now-ms (:aircraft/seen-at-ms aircraft))
     age-out-threshold-ms))

(defn observed-at-ms [{:aircraft/keys [seen-at-ms seen-s]} captured-at-ms]
  (long (or seen-at-ms
            (- captured-at-ms (* 1000 (or seen-s 0))))))

(defn position-observed-at-ms [aircraft captured-at-ms]
  (let [{:aircraft/keys [position-at-ms position-seen-s]} aircraft]
    (long (or position-at-ms
              (when position-seen-s
                (- captured-at-ms (* 1000 position-seen-s)))
              (observed-at-ms aircraft captured-at-ms)))))

(defn- ->observation [aircraft captured-at-ms]
  (cond-> (-> aircraft
              (dissoc :aircraft/seen-s :aircraft/position-seen-s)
              (assoc :aircraft/seen-at-ms (observed-at-ms aircraft captured-at-ms)))
          (positioned? aircraft)
          (assoc :aircraft/position-at-ms (position-observed-at-ms aircraft captured-at-ms))))

(defn- merge-observation [previous observation]
  (if (or (positioned? observation) (not (positioned? previous)))
    observation
    (merge observation
           (select-keys previous [:aircraft/position
                                  :aircraft/position-at-ms]))))

(defn merge-batch [picture batch captured-at-ms]
  (reduce
    (fn [merged aircraft]
      (update merged (:aircraft/icao aircraft)
              merge-observation (->observation aircraft captured-at-ms)))
    picture
    batch))

(defn age-out [picture now-ms]
  (into {}
        (remove (fn [[_icao aircraft]] (aged-out? aircraft now-ms)))
        picture))
