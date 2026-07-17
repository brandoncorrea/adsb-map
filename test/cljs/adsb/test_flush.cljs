(ns adsb.test-flush
  "Arms reagent's react-flush once, up front, so every cljs test sees the same production render semantics regardless of namespace order (adsb-7tw)."
  (:require [reagent.dom.client :as rdomc]))

;; reagent.dom.client/render's FIRST act is `(set! batch/react-flush
;; react-dom/flushSync)` — a global, one-way switch with no way back. Production
;; arms it the moment adsb.core/init! renders; before adsb-7tw the test suite
;; armed it only when some namespace happened to mount! a component, so results
;; depended on which tests ran first. We arm it here, in a preload, by doing
;; exactly what production does — a real reagent.dom.client render into a
;; throwaway detached node. Going through render (rather than poking reagent's
;; private, defonce'd batch/react-flush var directly) is faithful to production
;; and avoids the :undeclared-var warning a bare cross-namespace set! would
;; raise. `defonce` so a hot reload does not re-render; not private because
;; clj-kondo objects to a private var nobody reads.
(defonce react-flush-armed?
  (let [el   (.createElement js/document "div")
        root (rdomc/create-root el)]
    (rdomc/render root [:span])
    (rdomc/unmount root)
    true))
