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

;; SELECTING THE SELECTED DESELECTS IT. A selection is a spotlight, and pressing
;; the thing already lit is how anyone expects to turn it off — on the map, on a
;; ruler tick, or on a drawer row, all of which arrive here. The contract with
;; the map layer is untouched: it still dispatches exactly [:aircraft/select
;; icao] on a plane click, and clicking the lit plane now puts it out.
(rf/reg-event-db
  :aircraft/select
  (fn [db [_ icao]]
    (if (= icao (:aircraft/selected-icao db))
      ;; Deselect: drop selection AND hover. On mobile a tap has no
      ;; mouseleave, so a leftover :aircraft/hovered-icao would keep the
      ;; callsign label pinned after the ring is gone (adsb-oi8). Expand
      ;; state stays so the next pick keeps the reader's collapse choice
      ;; (adsb-4ca).
      (dissoc db :aircraft/selected-icao :aircraft/hovered-icao)
      ;; New pick keeps panel/expanded? as the reader left it — collapsed
      ;; stays collapsed when switching flights; default true (sub) only
      ;; applies before the first explicit expand/collapse. Hover is
      ;; cleared so a prior touch-hover does not outlive the new pick.
      (-> db
          (assoc :aircraft/selected-icao icao)
          (dissoc :aircraft/hovered-icao)))))

(rf/reg-event-db
  :aircraft/clear-selection
  (fn [db _]
    ;; Drop selection and hover together (Escape / × — same sticky-label
    ;; trap as toggle-deselect on touch). Expand/collapse is view
    ;; preference and survives across picks (adsb-4ca).
    (dissoc db :aircraft/selected-icao :aircraft/hovered-icao)))

;; Detail card expand/collapse. View state of the panel, not of the sky.
(rf/reg-event-db
  :panel/toggle-expanded
  (fn [db _]
    (update db :panel/expanded? #(if (nil? %) false (not %)))))

;; FOCUS = SELECT, AND TAKE THE CHART THERE.
;;
;; The plain select is what the MAP dispatches: you clicked a plane you can
;; already see, and flying to it would yank the chart out from under your own
;; finger. Focus is what the STACK dispatches — the ruler, the drawer — where the
;; aircraft you just named may be anywhere at all, including off the edge of the
;; chart entirely. Naming a thing you cannot see and then not showing it to you
;; is the whole of the complaint this answers.
;;
;; TWO GUARDS, and both are the same rule: only fly when there is a `where` and a
;; reason to go.
;;
;;   * NOT WHEN DESELECTING. Pressing the lit aircraft puts it out; it does not
;;     also fly you to the thing you just dismissed.
;;   * NOT WITHOUT A POSITION. The NO POS drawer exists precisely BECAUSE those
;;     aircraft have nowhere to be flown to — they are heard and never located.
;;     They still select (the card still names them, the tick still lights); the
;;     chart simply has nowhere to go, and stays where the reader left it.
(rf/reg-event-fx
  :aircraft/focus
  (fn [{:keys [db]} [_ icao]]
    (let [deselecting? (= icao (:aircraft/selected-icao db))
          position     (get-in db [:aircraft/picture icao :aircraft/position])]
      {:fx (cond-> [[:dispatch [:aircraft/select icao]]]
             (and (not deselecting?) position)
             (conj [:map/fly-to position]))})))

;; ---------------------------------------------------------------------
;; Aircraft hover — selection's transient sibling.
;;
;; Same trick, lighter lifecycle: an ICAO STRING under :aircraft/hovered-icao
;; while the pointer rests on a roster row or map glyph, cleared the moment
;; it leaves. Unlike selection it is never pruned by :ui/tick — mouse-out
;; clears it, and every reader joins it against the live picture anyway, so
;; a dangling hover resolves to nothing rather than a lie.
;;
;; The map consumes this for the callsign label under a hovered plane
;; (adsb.map.selection, adsb-xgg). Roster rows and the aircraft layer both
;; write the same channel.

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
