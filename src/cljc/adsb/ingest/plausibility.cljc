(ns adsb.ingest.plausibility
  (:require [adsb.aircraft :as aircraft]
            [adsb.geo :as geo]
            [adsb.picture :as picture]))

(def ^:const default-max-range-m 400000)

(defn beyond-horizon? [aircraft receiver-position max-range-m]
  (when-let [position (:aircraft/position aircraft)]
    (> (geo/distance receiver-position position) max-range-m)))

(defn gate-range [batch receiver-position max-range-m]
  (cond->> batch
           receiver-position
           (into [] (remove #(beyond-horizon? % receiver-position max-range-m)))))

(def ^:const ^:private ms-per-hour 3600000)

;; Two messages sharing a timestamp yield no elapsed time and so no speed;
;; we fall back to raw distance. CPR quantization noise is ~5-10 m at these
;; latitudes, so re-decoding a stationary aircraft jitters its position by a
;; few metres — require the step to clear a wide margin above that before
;; flagging, or same-ms jitter would set the permanent suspect flag. 300 m is
;; far above quantization noise and far below any real teleport.
(def ^:const ^:private same-ms-jump-min-m 300)

(defn position-jump? [previous position position-at-ms max-implied-speed-kt]
  (let [elapsed-ms (- position-at-ms (:aircraft/position-at-ms previous))
        distance-m (geo/distance (:aircraft/position previous) position)]
    (if (pos? elapsed-ms)
      (> (/ (geo/meters->nm distance-m)
            (/ elapsed-ms ms-per-hour))
         max-implied-speed-kt)
      (> distance-m same-ms-jump-min-m))))

(defn- flag-position-jump [picture observation captured-at-ms max-implied-speed-kt]
  (let [{:aircraft/keys [icao position]} observation
        previous (get picture icao)
        jumped?  (and position
                      (aircraft/positioned? previous)
                      (position-jump? previous position
                                      (aircraft/position-observed-at-ms observation captured-at-ms)
                                      max-implied-speed-kt))]
    (cond-> observation
            (or jumped? (:aircraft/position-suspect? previous))
            (assoc :aircraft/position-suspect? true))))

(def ^:const default-max-implied-speed-kt 1200)

(defn flag-position-jumps [picture batch captured-at-ms max-implied-speed-kt]
  (mapv #(flag-position-jump picture % captured-at-ms max-implied-speed-kt) batch))

(defn merge-batch-flagging-jumps [picture batch captured-at-ms]
  (let [batch (flag-position-jumps picture batch captured-at-ms default-max-implied-speed-kt)]
    (picture/merge-batch picture batch captured-at-ms)))

(defn accumulate-flagging-jumps [picture delta heard-at-ms]
  (let [delta (flag-position-jump picture delta heard-at-ms default-max-implied-speed-kt)]
    (picture/accumulate picture delta heard-at-ms)))
