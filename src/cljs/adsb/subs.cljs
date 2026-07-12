(ns adsb.subs
  "re-frame subscriptions for the app chrome. The aircraft PICTURE sub lives
  in adsb.stream (it owns that app-db key); this namespace holds the derived
  views the chrome reads — selection foremost."
  (:require
    [adsb.aircraft :as aircraft]
    [re-frame.core :as rf]))

;; The raw selection: the icao string the user clicked, or nil. Selection is
;; stored as an IDENTITY (an icao), never a snapshot object, which is exactly
;; why it survives picture updates — an aircraft's fields churn every second,
;; but its address does not.
(rf/reg-sub
  :aircraft/selected-icao
  (fn [db _]
    (:aircraft/selected-icao db)))

;; The coarse UI wall clock (ms), stamped by :ui/tick. nil before the first
;; tick — the panel then dashes seen-age rather than inventing one.
(rf/reg-sub
  :ui/now-ms
  (fn [db _]
    (:ui/now-ms db)))

;; The selected aircraft itself: the selected icao joined against the live
;; picture. nil when nothing is selected OR the selected aircraft has left
;; the sky. That nil is the panel's close signal — an aircraft aging out of
;; the picture closes its own detail panel, no extra bookkeeping required.
(rf/reg-sub
  :aircraft/selected
  :<- [:aircraft/picture]
  :<- [:aircraft/selected-icao]
  (fn [[picture icao] _]
    (when icao
      (get picture icao))))

;; Every aircraft in the current picture squawking a distress code, ordered
;; stably by icao so the emergency ribbon never reshuffles under the reader
;; between frames. Derived from the same :aircraft/picture the map and
;; sidebar read — one source of truth, filtered by the domain predicate,
;; never a second copy of the sky. Empty (and so the ribbon is absent) when
;; the sky is calm.
(rf/reg-sub
  :aircraft/emergencies
  :<- [:aircraft/picture]
  (fn [picture _]
    (->> (vals picture)
         (filter aircraft/emergency?)
         (sort-by :aircraft/icao))))
