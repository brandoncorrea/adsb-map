(ns adsb.subs
  (:require [adsb.aircraft :as aircraft]
            [adsb.stream :as stream]
            [re-frame.core :as rf]))

(rf/reg-sub
  :aircraft/selected-icao
  (fn [db _]
    (:aircraft/selected-icao db)))

(rf/reg-sub
  :aircraft/hovered-icao
  (fn [db _]
    (:aircraft/hovered-icao db)))

(rf/reg-sub
  :ui/now-ms
  (fn [db _]
    (:ui/now-ms db)))

(rf/reg-sub
  :aircraft/selected
  :<- [:aircraft/picture]
  :<- [:aircraft/selected-icao]
  (fn [[picture icao] _]
    (when icao
      (get picture icao))))

(rf/reg-sub
  :panel/expanded?
  (fn [db _]
    (get db :panel/expanded? true)))

(rf/reg-sub
  :map/camera-mode
  (fn [db _]
    (get db :map/camera-mode :free)))

(rf/reg-sub
  :feeder/silent-frames
  (fn [db _]
    (:feeder/silent-frames db 0)))

(rf/reg-sub
  :feeder/health
  :<- [:feeder/status]
  :<- [:stream/connection]
  :<- [:feeder/silent-frames]
  (fn [[feeder-status stream-connection silent-frames] _]
    (case stream-connection
      :live       (cond
                    (not= :ok feeder-status)
                    (or feeder-status :unknown)

                    (>= silent-frames stream/silent-after-frames)
                    :silent

                    :else :ok)
      :connecting nil
      :unknown)))

(rf/reg-sub
  :aircraft/emergencies
  :<- [:aircraft/picture]
  (fn [picture _]
    (->> (vals picture)
         (filter aircraft/emergency?)
         (sort-by :aircraft/icao))))
