(ns adsb.test-dom
  (:require ["@testing-library/react" :as rtl]
            [reagent.core :as r]
            [reagent.dom.client :as rdomc]))

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
