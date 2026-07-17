(ns adsb.events
  (:require [adsb.corejs :as cjs]
            [adsb.timers :as timers]
            [re-frame.core :as rf]))

(rf/reg-event-db :app/initialize-db (constantly {}))

;; Clicking the already-selected aircraft arms a DELAYED deselect so a
;; double-click can cancel it and follow instead; follow-dedupe suppresses
;; the duplicate when the click (detail>=2) and dblclick paths both fire.
(def ^:const deselect-arm-ms 350)
(def ^:const follow-dedupe-ms 400)

;; The armed-deselect timer is an opaque JS handle, not app state — it lives
;; in a module atom behind cancel/arm effects, the same shape stream.cljs uses
;; for its reconnect timer. The click INTENT (select/arm/follow/dedupe) is data
;; and lives in the event handler; only the timer resource lives here.
(defonce ^:private !deselect-timer (atom nil))

(defn- cancel-deselect! []
  (when-let [id @!deselect-timer]
    (timers/clear-timeout! id)
    (reset! !deselect-timer nil)))

(defn- arm-deselect! [icao]
  (cancel-deselect!)
  (reset! !deselect-timer
          (timers/timeout!
            (fn []
              (reset! !deselect-timer nil)
              (rf/dispatch [:aircraft/select icao]))
            deselect-arm-ms)))

(rf/reg-fx :aircraft/cancel-deselect (fn [_] (cancel-deselect!)))
(rf/reg-fx :aircraft/arm-deselect arm-deselect!)

(rf/reg-cofx :now (fn [cofx] (assoc cofx :now (cjs/now-ms))))

(defn click-fx
  "The pure click-intent decision. Given the db, the current time, and a
  click {:icao :detail}, returns the re-frame effect map: select now on a
  fresh single click; arm a delayed deselect when the already-selected
  aircraft is single-clicked (so a double-click can cancel it); or follow on
  a double-click (detail>=2), deduped within follow-dedupe-ms so the
  click(detail>=2) and dblclick paths do not both fire."
  [db now {:keys [icao detail]}]
  (let [detail (or detail 1)]
    (cond
      (>= detail 2)
      (if (> (- now (:aircraft/last-follow-ms db 0)) follow-dedupe-ms)
        {:db (assoc db :aircraft/last-follow-ms now)
         :fx [[:aircraft/cancel-deselect nil]
              [:dispatch [:aircraft/dblclick-follow icao]]]}
        {:fx [[:aircraft/cancel-deselect nil]]})

      (= icao (:aircraft/selected-icao db))
      {:fx [[:aircraft/arm-deselect icao]]}

      :else
      {:fx [[:aircraft/cancel-deselect nil]
            [:dispatch [:aircraft/select icao]]]})))

(rf/reg-event-fx
  :aircraft/click
  [(rf/inject-cofx :now)]
  (fn [{:keys [db now]} [_ props]]
    (click-fx db now props)))

(rf/reg-event-fx
  :aircraft/select
  (fn [{:keys [db]} [_ icao]]
    (if (= icao (:aircraft/selected-icao db))
      {:db (dissoc db :aircraft/selected-icao
                      :aircraft/hovered-icao
                      :map/camera-mode)}
      {:db (-> db
               (assoc :aircraft/selected-icao icao :map/camera-mode :free)
               (dissoc :aircraft/hovered-icao))
       :fx [[:dispatch [:enrich/ensure icao]]]})))

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
             :else (assoc db* :map/camera-mode :follow))
       :fx [[:dispatch [:enrich/ensure icao]]]})))

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
