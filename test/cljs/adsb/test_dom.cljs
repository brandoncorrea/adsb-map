(ns adsb.test-dom
  (:require ["@testing-library/react" :as rtl]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.dom.client :as rdomc]))

(defn render! [component]
  (rtl/cleanup)
  (rf/clear-subscription-cache!)
  (rtl/render (r/as-element component)))

(defn mount! [component el]
  (let [root (rdomc/create-root el)]
    (rtl/act (fn []
               (rdomc/render root component)
               (r/flush)
               js/undefined))
    root))

(defn unmount! [root]
  (rtl/act (fn []
             (rdomc/unmount root)
             js/undefined)))

(defn settle!
  "Resolves after a real render tick, for 'nothing happened' assertions that
   have no positive signal to wait for. Waiting on a negative condition with
   rtl/waitFor is a trap: waitFor checks its predicate immediately, so a
   pre-action state that already satisfies it resolves instantly and the test
   asserts against pre-settle DOM (adsb-2i5). Under reagent 2 + createRoot,
   re-renders commit late — a bug that WOULD disturb the DOM lands a frame or
   two later, after such a wait has already passed.

   A double requestAnimationFrame is the anchor: frame one lets any component
   rAF (e.g. the roster sheet's drag/settle loop, scheduled on its !raf) run
   and touch its ratom; frame two lets reagent's rAF-batched, flushSync-armed
   re-render commit that change to the DOM. Whatever the action was going to do
   to the DOM has therefore happened by the time this resolves, so a negative
   assertion after it is real evidence rather than a vacuous pass. Returns a
   Promise — chain the assertion in .then."
  []
  (js/Promise.
    (fn [resolve _reject]
      (js/requestAnimationFrame
        (fn []
          (js/requestAnimationFrame
            (fn [] (resolve js/undefined))))))))
