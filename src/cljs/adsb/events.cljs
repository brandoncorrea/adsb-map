(ns adsb.events
  "re-frame events for the app shell. Minimal by design — the app-db starts
  nearly empty and grows as beads land (aircraft state is adsb-2yu.4)."
  (:require
    [re-frame.core :as rf]))

(def default-db
  "The initial app-db. The shell needs almost nothing to boot; this is the
  seed every later feature accretes onto."
  {})

(rf/reg-event-db
  :app/initialize-db
  (fn [_ _]
    default-db))
