(ns adsb.events
  (:require [re-frame.core :as rf]))

(rf/reg-event-db :app/initialize-db (constantly {}))

(rf/reg-event-db
  :aircraft/select
  (fn [db [_ icao]]
    (if (= icao (:aircraft/selected-icao db))
      (dissoc db :aircraft/selected-icao
                 :aircraft/hovered-icao
                 :map/camera-mode)
      (-> db
          (assoc :aircraft/selected-icao icao :map/camera-mode :free)
          (dissoc :aircraft/hovered-icao)))))

(rf/reg-event-db
  :aircraft/clear-selection
  (fn [db _]
    (dissoc db :aircraft/selected-icao
               :aircraft/hovered-icao
               :map/camera-mode)))

(rf/reg-event-db
  :panel/toggle-expanded
  (fn [db _]
    (update db :panel/expanded? (fnil not true))))

(rf/reg-event-fx
  :aircraft/focus
  (fn [{:keys [db]} [_ icao]]
    (let [deselecting? (= icao (:aircraft/selected-icao db))
          position     (get-in db [:aircraft/picture icao :aircraft/position])]
      {:fx (cond-> [[:dispatch [:aircraft/select icao]]]
                   (and (not deselecting?) position)
                   (conj [:dispatch [:map/follow]]))})))

(defn- free-camera [db] (assoc db :map/camera-mode :free))
(defn- following? [db] (= :follow (:map/camera-mode db)))

(rf/reg-event-fx
  :aircraft/dblclick-follow
  (fn [{:keys [db]} [_ icao]]
    (let [pos       (get-in db [:aircraft/picture icao :aircraft/position])
          selected? (= icao (:aircraft/selected-icao db))
          db*       (-> db
                        (assoc :aircraft/selected-icao icao)
                        (dissoc :aircraft/hovered-icao))]
      {:db (cond
             (not pos) db*
             (and selected? (following? db)) (free-camera db*)
             :else (assoc db* :map/camera-mode :follow))})))

(defn- selected-positioned? [db]
  (when-let [icao (:aircraft/selected-icao db)]
    (get-in db [:aircraft/picture icao :aircraft/position])))

(defn- follow-positioned [db]
  (cond-> db
          (selected-positioned? db)
          (assoc :map/camera-mode :follow)))

(rf/reg-event-db :map/follow (fn [db _] (follow-positioned db)))
(rf/reg-event-db :map/user-pan (fn [db _] (free-camera db)))

(rf/reg-event-fx
  :map/toggle-follow
  (fn [{:keys [db]} _]
    {:db (if (following? db)
           (free-camera db)
           (follow-positioned db))}))

(rf/reg-event-db
  :aircraft/hover
  (fn [db [_ icao]]
    (assoc db :aircraft/hovered-icao icao)))

(rf/reg-event-db
  :aircraft/clear-hover
  (fn [db _]
    (dissoc db :aircraft/hovered-icao)))

(rf/reg-event-db
  :ui/tick
  (fn [db [_ now-ms]]
    (let [db  (assoc db :ui/now-ms now-ms)
          sel (:aircraft/selected-icao db)]
      (cond-> db
              (and sel (not (contains? (:aircraft/picture db) sel)))
              (dissoc :aircraft/selected-icao :map/camera-mode)))))
