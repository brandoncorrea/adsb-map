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

;; The hovered aircraft's icao, or nil — set while the pointer rests on a
;; tick in the Stack (adsb.ui.stack), cleared on mouse-out. Stored as an
;; identity for the same reason selection is. The map layer will consume
;; this in a later wave to light the hovered aircraft on the chart.
(rf/reg-sub
  :aircraft/hovered-icao
  (fn [db _]
    (:aircraft/hovered-icao db)))

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

;; The feeder chip's presentation health — distinct from the stream chip's.
;; The server reports the feeder as :ok/:down/:starting on every frame
;; (adsb.wire, :feeder/status), but that claim only means anything while OUR
;; stream to the server is LIVE. The moment the stream is not live we stop
;; receiving fresh feeder status at all, so the last claim is going stale by
;; the second; asserting a stale :ok over a dead stream is exactly the lie
;; this feature exists to prevent. So when the stream is not :live the feeder
;; is UNKNOWABLE and we derive :unknown — a neutral state the chip shows in
;; place of a stale claim. nil (no frame yet, even while live) is :unknown too.
(rf/reg-sub
  :feeder/health
  :<- [:feeder/status]
  :<- [:stream/connection]
  (fn [[feeder-status stream-connection] _]
    (if (= :live stream-connection)
      (or feeder-status :unknown)
      :unknown)))

;; Every aircraft in the current picture squawking a distress code, ordered
;; stably by icao so the emergency ribbon never reshuffles under the reader
;; between frames. Derived from the same :aircraft/picture the map and
;; the Stack read — one source of truth, filtered by the domain predicate,
;; never a second copy of the sky. Empty (and so the ribbon is absent) when
;; the sky is calm.
(rf/reg-sub
  :aircraft/emergencies
  :<- [:aircraft/picture]
  (fn [picture _]
    (->> (vals picture)
         (filter aircraft/emergency?)
         (sort-by :aircraft/icao))))
