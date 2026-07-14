(ns adsb.ui.splash
  "The cold-load splash's dismissal (adsb.css.splash, #adsb-splash).

  First-time readers wait on the JS bundle, then the basemap style, then the
  first tile before any of the chart appears. The paper backdrop already shows
  through while tiles load (adsb.css.shell), but a blank sheet says nothing
  about whether the page is working. So a single breathing note sits over the
  paper from first paint until the map's first frame lands.

  The splash is STATIC MARKUP in index.html, not a Reagent component: it must
  paint before the bundle runs, and a component cannot cover its own download.
  So its END is not a re-render either. When the map paints its first frame the
  map view dispatches [:map/ready]; the effect here fades the element out and
  removes it from the DOM. app-db records the readiness for anyone who later
  wants to read it — the DOM teardown is the effect beside it.

  This namespace is required only for its registrations (see adsb.core); it
  exports no component."
  (:require [re-frame.core :as rf]))

(def ^:private splash-id "adsb-splash")
(def ^:private gone-class "is-gone")

(def ^:const fade-ms
  "How long after the fade begins before the node is pulled from the DOM. It
  matches the opacity transition on #adsb-splash (adsb.css.splash), so the
  dissolve to the chart underneath finishes before the element leaves."
  400)

(rf/reg-fx
  :splash/dismiss
  (fn [_]
    (when-let [el (.getElementById js/document splash-id)]
      ;; Fade first — the class trips the CSS opacity transition — then drop
      ;; the node once it has dissolved. A lingering full-viewport element,
      ;; even at opacity 0, would swallow the reader's first pan; the CSS sets
      ;; pointer-events none for the interim, and we remove it outright after.
      (.add (.-classList el) gone-class)
      (js/setTimeout #(.remove el) fade-ms))))

(rf/reg-event-fx
  :map/ready
  (fn [{:keys [db]} _]
    ;; Idempotent by design: a theme re-print (adsb.map.view) fires the map's
    ;; load again, and dismissing an already-removed splash is a no-op —
    ;; getElementById returns nil once the node is gone.
    {:db (assoc db :map/ready? true)
     :fx [[:splash/dismiss nil]]}))
