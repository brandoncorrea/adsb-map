(ns adsb.stream
  (:require [adsb.corejs :as cjs]
            [adsb.picture :as picture]
            [adsb.stream.source :as source]
            [adsb.timers :as timers]
            [adsb.wire :as wire]
            [clojure.math :as math]
            [re-frame.core :as rf]))

(def ^:const base-backoff-ms 1000)
(def ^:const max-backoff-ms 30000)
(def ^:const down-after-attempts 3)

(defn backoff-ms [attempts]
  (min max-backoff-ms
       (* base-backoff-ms (math/pow 2.0 (dec attempts)))))

(defn- status-for-attempts [attempts]
  (if (> attempts down-after-attempts)
    :down
    :reconnecting))

(defn- data->picture [data]
  (-> data cjs/<-json wire/wire->picture))

(defn- data->upsert [data]
  (-> data cjs/<-json wire/wire->upsert))

(defn- data->stats [data]
  (let [envelope (cjs/<-json data)]
    {:stats  (wire/wire->stats envelope)
     :feeder (wire/wire->feeder envelope)}))

(defn data->crop [data] (-> data cjs/<-json wire/wire->crop))

(defonce ^:private !conn (atom {:connection nil :timer nil}))

(defn clear-timer! []
  (when-let [timer (:timer @!conn)]
    (timers/clear-timeout timer)
    (swap! !conn assoc :timer nil)))

(defn- open! []
  (clear-timer!)
  (some-> (:connection @!conn) source/close!)
  (let [conn (source/connect!
               "/api/stream"
               {:on-open     #(rf/dispatch [:stream/opened])
                :on-frame    #(rf/dispatch [:stream/received %])
                :on-aircraft #(rf/dispatch [:stream/upsert %])
                :on-stats    #(rf/dispatch [:stream/stats % (cjs/now-ms)])
                :on-config   #(rf/dispatch [:stream/config %])
                :on-error    #(rf/dispatch [:stream/error %])})]
    (swap! !conn assoc :connection conn)))

(defn schedule-reconnect! [ms]
  (clear-timer!)
  (swap! !conn assoc :timer
         (timers/timeout #(rf/dispatch [:stream/reconnect]) ms)))

(rf/reg-fx :stream/connect! open!)
(rf/reg-fx :stream/clear-timer! clear-timer!)
(rf/reg-fx :stream/schedule-reconnect! (fn [ms] (schedule-reconnect! ms)))

(rf/reg-event-fx
  :stream/start
  (fn [{:keys [db]} _]
    {:db              (assoc db
                        :aircraft/picture {}
                        :stats/session {}
                        :feeder/status nil
                        :stream/attempts 0
                        :stream/connection :connecting)
     :stream/connect! nil}))

(rf/reg-event-fx
  :stream/opened
  (fn [{:keys [db]} _]
    {:db                  (assoc db :stream/connection :live :stream/attempts 0)
     :stream/clear-timer! nil}))

(defn silent-frames [previous message-rate]
  (cond
    (nil? message-rate) 0
    (pos? message-rate) 0
    :else (inc (or previous 0))))

(rf/reg-event-db
  :stream/received
  (fn [db [_ data]]
    (assoc db
      :aircraft/picture (data->picture data)
      :stream/connection :live)))

(rf/reg-event-db
  :stream/upsert
  (fn [db [_ data]]
    (let [aircraft (data->upsert data)]
      (-> db
          (assoc-in [:aircraft/picture (:aircraft/icao aircraft)] aircraft)
          (assoc :stream/connection :live)))))

(rf/reg-event-db
  :stream/stats
  (fn [db [_ data at-ms]]
    (let [{:keys [stats feeder]} (data->stats data)]
      (-> db
          (update :aircraft/picture picture/sweep at-ms)
          (assoc
            :stats/session stats
            :feeder/status (:feeder/status feeder)
            :feeder/silent-frames (silent-frames (:feeder/silent-frames db)
                                                 (:stats/message-rate stats))
            :stream/connection :live)))))

(rf/reg-event-db
  :stream/config
  (fn [db [_ data]]
    (assoc db :crop/declared (data->crop data))))

(rf/reg-event-fx
  :stream/error
  (fn [{:keys [db]} [_ ready-state]]
    (let [attempts (inc (:stream/attempts db 0))]
      (cond-> {:db (assoc db
                     :stream/attempts attempts
                     :stream/connection (status-for-attempts attempts))}
              (= ready-state :closed)
              (assoc :stream/schedule-reconnect! (backoff-ms attempts))))))

(rf/reg-event-fx
  :stream/reconnect
  (fn [_ _]
    {:stream/connect! nil}))

(rf/reg-sub
  :aircraft/picture
  (fn [db _]
    (get db :aircraft/picture {})))

(rf/reg-sub
  :stream/connection
  (fn [db _]
    (get db :stream/connection :connecting)))

(rf/reg-sub
  :feeder/status
  (fn [db _]
    (get db :feeder/status)))

(rf/reg-sub
  :stats/session
  (fn [db _]
    (get db :stats/session {})))

(rf/reg-sub
  :crop/declared
  (fn [db _]
    (get db :crop/declared)))

(defn start! [] (rf/dispatch [:stream/start]))
