(ns adsb.ui.splash
  (:require [adsb.corejs :as cjs]
            [adsb.timers :as timers]
            [re-frame.core :as rf]))

(def ^:private splash-id "adsb-splash")
(def ^:private note-selector ".adsb-splash-note")
(def ^:private gone-class "is-gone")
(def ^:private error-class "is-error")
(def ^:const failed-note "Couldn't reach the map. Tap to retry.")
(def ^:const fade-ms 400)

(rf/reg-fx
  :splash/dismiss
  (fn [_]
    (when-let [el (cjs/element-by-id splash-id)]
      (cjs/add-class el gone-class)
      (timers/timeout #(cjs/remove! el) fade-ms))))

(rf/reg-event-fx
  :map/ready
  (fn [{:keys [db]} _]
    {:db (assoc db :map/ready? true)
     :fx [[:splash/dismiss nil]]}))

(rf/reg-fx
  :splash/fail
  (fn [_]
    (when-let [el (cjs/element-by-id splash-id)]
      (cjs/add-class el error-class)
      (when-let [note (cjs/select el note-selector)]
        (set! (.-textContent note) failed-note))
      ;; set!, not addEventListener: a repeated failure event must not stack
      ;; a second click listener.
      (set! (.-onclick el) cjs/refresh!))))

(rf/reg-event-fx
  :map/load-failed
  (fn [{:keys [db]} _]
    {:db (assoc db :map/load-failed? true)
     :fx [[:splash/fail nil]]}))
