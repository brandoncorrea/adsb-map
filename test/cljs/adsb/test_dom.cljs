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
