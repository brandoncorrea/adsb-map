(ns adsb.ui.health
  (:require [re-frame.core :as rf]))

(def ^:const quiet-states #{:live :connecting})
(def ^:const connection-labels
  {:live         "Live"
   :reconnecting "Reconnecting"
   :down         "Disconnected"})

(def ^:const feeder-labels
  {:ok       "Feeder OK"
   :starting "Feeder starting"
   :silent   "No messages"
   :down     "Feeder down"
   :unknown  "Feeder unknown"})

(defn- connection-indicator [status]
  (let [status (or status :connecting)]
    (when-not (contains? quiet-states status)
      (let [status-label (name status)]
        [:span.adsb-conn
         {:class       (str "adsb-conn-" status-label)
          :role        "status"
          :data-testid "connection-indicator"
          :data-state  status-label}
         [:span.adsb-conn-dot {:aria-hidden true}]
         [:span.adsb-conn-label (get connection-labels status status-label)]]))))

(defn- feeder-indicator [status]
  (when-let [status-label (some-> status name)]
    [:span.adsb-feeder
     {:class       (str "adsb-feeder-" status-label)
      :role        "status"
      :data-testid "feeder-indicator"
      :data-state  status-label}
     [:span.adsb-feeder-dot {:aria-hidden true}]
     [:span.adsb-feeder-label
      {:class (when (= :ok status) "adsb-vh")}
      (get feeder-labels status status-label)]]))

(defn health []
  (let [connection (rf/subscribe [:stream/connection])
        feeder     (rf/subscribe [:feeder/health])]
    (fn []
      [:div.adsb-health
       [connection-indicator @connection]
       [feeder-indicator @feeder]])))
