(ns adsb.ingest.replay
  (:require [adsb.ingest.coerce :as coerce]
            [adsb.ingest.source :as source]
            [cheshire.core :as json]
            [clojure.math :as math]))

(def ^:const default-fixture-path "test/resources/aircraft-sample.json")
(def ^:const default-loop-ms 90000)
(def ^:const default-age-rate 0.5)
(def ^:private earth-radius-m 6371000)
(def ^:private meters-per-nm 1852)

(defn- dead-reckon [{:geo/keys [lat lon]} gs-kt track-deg elapsed-s]
  (let [dist-m  (/ (* gs-kt meters-per-nm elapsed-s) 3600.0)
        ang     (/ dist-m earth-radius-m)
        bearing (math/to-radians track-deg)
        lat1    (math/to-radians lat)
        lon1    (math/to-radians lon)
        lat2    (math/asin (+ (* (math/sin lat1) (math/cos ang))
                              (* (math/cos lat1) (math/sin ang)
                                 (math/cos bearing))))
        lon2    (+ lon1 (math/atan2 (* (math/sin bearing) (math/sin ang)
                                       (math/cos lat1))
                                    (- (math/cos ang)
                                       (* (math/sin lat1) (math/sin lat2)))))]
    {:geo/lat (math/to-degrees lat2)
     :geo/lon (- (mod (+ (math/to-degrees lon2) 540) 360) 180)}))

(defn- advance [aircraft elapsed-s age-rate]
  (let [{:aircraft/keys [position ground-speed-kt track-deg seen-s]} aircraft
        aged (assoc aircraft :aircraft/seen-s (+ (or seen-s 0) (* age-rate elapsed-s)))]
    (cond-> aged
            (and position ground-speed-kt track-deg)
            (assoc :aircraft/position
                   (dead-reckon position ground-speed-kt track-deg elapsed-s)))))

(defn- elapsed-s [start-ms now-ms loop-ms]
  (/ (mod (- now-ms start-ms) loop-ms) 1000.0))

(defrecord ReplaySource [base clock loop-ms age-rate start]
  source/Source
  (open! [this]
    (reset! start (clock))
    this)
  (fetch! [_]
    (let [start-ms (or @start (reset! start (clock)))
          seconds  (elapsed-s start-ms (clock) loop-ms)]
      (mapv #(advance % seconds age-rate) base)))
  (close! [this] this))

(defn- load-fixture! [path]
  (-> (slurp path)
      (json/parse-string true)
      :aircraft
      coerce/->aircraft-batch))

(defn ->source
  "A Source replaying the recorded fixture on a loop (see the ns
  docstring). All options are optional:

    :batch         pre-coerced aircraft to replay instead of reading the
                   fixture from disk — for tests
    :fixture-path  where to read the fixture (default the recorded
                   payload)
    :clock         0-arg fn returning epoch ms, monotonic — injected so a
                   fake clock replays deterministically (default the wall
                   clock)
    :loop-ms       lap length (default default-loop-ms)
    :age-rate      seen-seconds per replay second (default
                   default-age-rate)"
  ([] (->source {}))
  ([{:keys [batch fixture-path clock loop-ms age-rate]
     :or   {fixture-path default-fixture-path
            clock        #(System/currentTimeMillis)
            loop-ms      default-loop-ms
            age-rate     default-age-rate}}]
   (->ReplaySource (or batch (load-fixture! fixture-path))
                   clock loop-ms age-rate (atom nil))))
