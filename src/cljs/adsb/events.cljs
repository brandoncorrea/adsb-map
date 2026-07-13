(ns adsb.events
  "re-frame events for the app shell. Minimal by design — the app-db starts
  nearly empty and grows as beads land (aircraft state is adsb-2yu.4)."
  (:require [re-frame.core :as rf]))

(def default-db
  "The initial app-db. The shell needs almost nothing to boot; this is the
  seed every later feature accretes onto."
  {})

(rf/reg-event-db
  :app/initialize-db
  (fn [_ _] default-db))

;; ---------------------------------------------------------------------
;; Aircraft selection
;;
;; Selection is stored as an ICAO STRING, never an aircraft object. That is
;; the whole trick behind its lifecycle: the address is stable while the
;; aircraft's fields churn every second, so the selection survives every
;; picture update for free. The derived :aircraft/selected sub joins this
;; icao back against the live picture, so when the aircraft finally ages out
;; of the sky the join goes nil and the panel closes on its own.
;;
;; :aircraft/select is a HARD CONTRACT with the map layer (adsb-2yu.5), which
;; dispatches exactly [:aircraft/select icao] on an aircraft click. Do not
;; rename it or change its argument shape.

(rf/reg-event-db
  :aircraft/select
  (fn [db [_ icao]]
    (assoc db :aircraft/selected-icao icao)))

(rf/reg-event-db
  :aircraft/clear-selection
  (fn [db _]
    (dissoc db :aircraft/selected-icao)))

;; ---------------------------------------------------------------------
;; Aircraft hover — selection's transient sibling.
;;
;; Same trick, lighter lifecycle: an ICAO STRING under :aircraft/hovered-icao
;; while the pointer rests on an aircraft's tick in the Stack (adsb.ui.stack),
;; cleared the moment it leaves. Unlike selection it is never pruned by
;; :ui/tick — mouse-out clears it, and every reader joins it against the live
;; picture anyway, so a dangling hover resolves to nothing rather than a lie.
;;
;; The map layer does not consume this yet: highlighting the hovered aircraft
;; ON the map (feature-state or a highlight property) is a later wave — the
;; state and the contract land here first so that wave has something settled
;; to read. See the follow-up note on bead adsb-dgb.9.

(rf/reg-event-db
  :aircraft/hover
  (fn [db [_ icao]]
    (assoc db :aircraft/hovered-icao icao)))

(rf/reg-event-db
  :aircraft/clear-hover
  (fn [db _]
    (dissoc db :aircraft/hovered-icao)))

;; ---------------------------------------------------------------------
;; The coarse UI clock tick (adsb.ui.aircraft-panel/start-clock!).
;;
;; It carries `now-ms` as an argument — the clock stays at the UI edge and
;; the domain never reads an ambient time. Two jobs: park `now-ms` for the
;; panel's seen-age, and PRUNE a dangling selection. The derived sub already
;; closes the panel the instant a selected aircraft leaves the picture; this
;; also drops the now-orphaned icao from app-db, so a same-address track
;; reappearing minutes after age-out does not silently re-open the panel.
;; Selection is intent pinned to an aircraft that still exists; once it is
;; gone from the sky, the intent goes with it.

(rf/reg-event-db
  :ui/tick
  (fn [db [_ now-ms]]
    (let [db  (assoc db :ui/now-ms now-ms)
          sel (:aircraft/selected-icao db)]
      (if (and sel (not (contains? (:aircraft/picture db) sel)))
        (dissoc db :aircraft/selected-icao)
        db))))
