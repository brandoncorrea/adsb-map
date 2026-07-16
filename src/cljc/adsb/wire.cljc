(ns adsb.wire
  (:require [clojure.set :as set]))

(defn aircraft->wire [aircraft]
  (let [{:aircraft/keys [icao callsign position altitude-ft on-ground? squawk
                         category ground-speed-kt track-deg baro-rate-fpm
                         seen-at-ms position-suspect? mlat?]} aircraft]
    (cond-> {:icao icao}
            callsign (assoc :callsign callsign)
            position (assoc :lat (:geo/lat position) :lon (:geo/lon position))
            altitude-ft (assoc :altitude altitude-ft)
            on-ground? (assoc :on-ground true)
            squawk (assoc :squawk squawk)
            category (assoc :category category)
            ground-speed-kt (assoc :ground-speed ground-speed-kt)
            track-deg (assoc :track track-deg)
            baro-rate-fpm (assoc :baro-rate baro-rate-fpm)
            seen-at-ms (assoc :seen-at seen-at-ms)
            position-suspect? (assoc :position-suspect true)
            mlat? (assoc :mlat true))))

(defn stats->wire [{:stats/keys [max-range-km message-rate]}]
  (cond-> {}
          max-range-km (assoc :max-range-km max-range-km)
          message-rate (assoc :message-rate message-rate)))

(def ^:private feeder-status->wire
  {:starting "starting"
   :ok       "ok"
   :down     "down"})

(def ^:private wire->feeder-status (set/map-invert feeder-status->wire))

(defn feeder->wire [{:feeder/keys [status last-success-ms]}]
  (let [wire-status (feeder-status->wire status)]
    (cond-> {}
            wire-status (assoc :status wire-status)
            last-success-ms (assoc :last-success last-success-ms))))

(defn crop->wire [{:crop/keys [center radius-m]}]
  (when (and center radius-m)
    {:lat       (:geo/lat center)
     :lon       (:geo/lon center)
     :radius-km (/ radius-m 1000)}))

(defn config-event->wire [crop at-ms]
  (if-let [wire-crop (crop->wire crop)]
    {:at at-ms :crop wire-crop}
    {:at at-ms}))

(defn picture->wire [picture at-ms]
  {:at       at-ms
   :aircraft (mapv aircraft->wire (vals picture))})

(defn stats-event->wire [stats feeder at-ms]
  {:at     at-ms
   :stats  (stats->wire stats)
   :feeder (feeder->wire feeder)})

(defn upsert->wire [aircraft at-ms]
  {:at       at-ms
   :aircraft (aircraft->wire aircraft)})

(defn wire->aircraft
  [{:keys [icao callsign lat lon altitude on-ground squawk category
           ground-speed track baro-rate seen-at position-suspect mlat]
    :as   _wire-aircraft}]
  (cond-> {:aircraft/icao icao}
          callsign (assoc :aircraft/callsign callsign)
          (and lat lon) (assoc :aircraft/position {:geo/lat lat :geo/lon lon})
          altitude (assoc :aircraft/altitude-ft altitude)
          on-ground (assoc :aircraft/on-ground? true)
          squawk (assoc :aircraft/squawk squawk)
          category (assoc :aircraft/category category)
          ground-speed (assoc :aircraft/ground-speed-kt ground-speed)
          track (assoc :aircraft/track-deg track)
          baro-rate (assoc :aircraft/baro-rate-fpm baro-rate)
          seen-at (assoc :aircraft/seen-at-ms seen-at)
          position-suspect (assoc :aircraft/position-suspect? true)
          mlat (assoc :aircraft/mlat? true)))

(defn wire->upsert [wire]
  (-> wire :aircraft wire->aircraft))

(defn wire->crop [wire]
  (let [{:keys [lat lon radius-km]} (:crop wire)]
    (when (and lat lon radius-km)
      {:crop/center   {:geo/lat lat :geo/lon lon}
       :crop/radius-m (* radius-km 1000)})))

(defn wire->stats [wire]
  (let [{:keys [max-range-km message-rate]} (:stats wire)]
    (cond-> {}
            max-range-km (assoc :stats/max-range-km max-range-km)
            message-rate (assoc :stats/message-rate message-rate))))

(defn wire->feeder [wire]
  (let [{:keys [status last-success]} (:feeder wire)
        status-kw (wire->feeder-status status)]
    (cond-> {}
            status-kw (assoc :feeder/status status-kw)
            last-success (assoc :feeder/last-success-ms last-success))))

(defn wire->picture [wire]
  (->> (:aircraft wire)
       (map wire->aircraft)
       (map (juxt :aircraft/icao identity))
       (into {})))
