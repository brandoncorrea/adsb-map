(ns adsb.subs
  "re-frame subscriptions for the app chrome. The aircraft PICTURE sub lives
  in adsb.stream (it owns that app-db key); this namespace holds the derived
  views the chrome reads — selection foremost."
  (:require [adsb.aircraft :as aircraft]
            [adsb.stream :as stream]      ; the silence threshold; it owns that key
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
;; roster row or a plane on the chart, cleared on mouse-out. Stored as an
;; identity for the same reason selection is. adsb.map.selection pins a
;; callsign label for the hovered track (adsb-xgg).
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

;; Detail card expanded? Default true until the reader collapses it.
;; Collapsing to a chip frees the map (adsb-66h); switching flights keeps
;; that choice — a minimized panel stays minimized (adsb-4ca).
(rf/reg-sub
  :panel/expanded?
  (fn [db _]
    (get db :panel/expanded? true)))

;; Camera mode for the selected flight: :free (default) or :follow
;; (adsb-jg4). Course-up is not shipped. adsb.map.follow reads this.
(rf/reg-sub
  :map/camera-mode
  (fn [db _]
    (get db :map/camera-mode :free)))

;; The feeder chip's presentation health — distinct from the stream chip's.
;; The server reports the feeder as :ok/:down/:starting on every frame
;; (adsb.wire, :feeder/status), but that claim only means anything while OUR
;; stream to the server is LIVE. The moment the stream is not live we stop
;; receiving fresh feeder status at all, so the last claim is going stale by
;; the second; asserting a stale :ok over a dead stream is exactly the lie
;; this feature exists to prevent. So when the stream has DROPPED the feeder is
;; UNKNOWABLE and we derive :unknown — a neutral state the chip shows in place
;; of a stale claim. nil (no frame yet, even while live) is :unknown too.
;;
;; UNKNOWABLE AND NOT-YET-ASKED ARE DIFFERENT (adsb-33i). While the stream is
;; still :connecting — booting, never connected, nothing failed — the feeder is
;; not unknowable, it is simply unasked, and that is not news. We derive NIL,
;; and the header renders nothing at all for it. Without this, every refresh
;; opened on a flash of "Feeder unknown": a truthful report of :reconnecting,
;; which was itself a lie about a failure that never happened.
;; REACHABLE IS NOT THE SAME AS HEARING, and the difference is the whole reason
;; anyone looks at this dot. The server sets :feeder/status :ok on a SUCCESSFUL
;; POLL of aircraft.json (adsb.ingest.poll) — the container is up and serving.
;; It says nothing about the radio behind it. An SDR can die, or an antenna lead
;; can come loose, while the container stays perfectly healthy: the poll keeps
;; succeeding, the feeder keeps reporting :ok, its message counter stops
;; advancing, the picture ages out, the map empties — and the dot sits there
;; green while everything stops moving.
;;
;; That is the exact question the dot exists to answer, and it was answering it
;; wrong. So :silent: reachable, and hearing nothing (adsb.stream/silent-frames
;; counts the consecutive frames of zero message rate; the threshold gives the
;; light hysteresis so a rate that rounds to zero for one tick cannot blink it).
;;
;; It does NOT claim a cause. A dead SDR and a genuinely empty 3am sky look
;; identical from here, and the honest sentence is the one they share: no
;; messages are arriving. Naming the cause would be a guess wearing a fact's
;; clothes.
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
