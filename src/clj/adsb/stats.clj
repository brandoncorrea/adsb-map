(ns adsb.stats
  (:require [adsb.aircraft :as aircraft]
            [adsb.geo :as geo]
            [clojure.math :as math]))

(def ^:const ^:private ms-per-second 1000)

(defn create []
  {:stats/max-range-m   (atom nil)
   :stats/prev-messages (atom nil)})

(defn- range-m [receiver-position aircraft]
  (geo/distance receiver-position (:aircraft/position aircraft)))

(defn- max-range-km! [max-range-m receiver-position positioned]
  (when receiver-position
    (let [batch-max (when (seq positioned)
                      (reduce max (map #(range-m receiver-position %) positioned)))
          running   (swap! max-range-m
                           (fn [current]
                             (cond
                               (nil? batch-max) current
                               (nil? current) batch-max
                               :else (max current batch-max))))]
      (when running
        (-> running geo/meters->km double math/round)))))

(defn- message-rate! [prev-messages messages now-ms]
  (when messages
    (let [prev @prev-messages]
      (reset! prev-messages {:messages messages :at-ms now-ms})
      (when-let [{prev-count :messages prev-at-ms :at-ms} prev]
        (let [delta-msgs (- messages prev-count)
              elapsed-ms (- now-ms prev-at-ms)]
          (when (and (pos? elapsed-ms) (not (neg? delta-msgs)))
            (math/round (/ (double delta-msgs)
                           (/ elapsed-ms ms-per-second)))))))))

(defn compute!
  [{:stats/keys [max-range-m prev-messages]}
   {:keys [picture receiver-position now-ms messages]}]
  (let [aircraft   (vals picture)
        positioned (filter aircraft/positioned? aircraft)]
    {:stats/aircraft-count   (count aircraft)
     :stats/positioned-count (count positioned)
     :stats/max-range-km     (max-range-km! max-range-m receiver-position
                                            positioned)
     :stats/message-rate     (message-rate! prev-messages messages now-ms)}))
