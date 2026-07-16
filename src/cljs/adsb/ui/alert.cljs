(ns adsb.ui.alert
  (:require [adsb.aircraft :as aircraft]
            [adsb.corejs :as cjs]
            [re-frame.core :as rf]))

(def ^:const emergency-words
  {:hijack        "hijacking"
   :radio-failure "radio failure"
   :general       "general emergency"})

(defn aircraft-alert [aircraft]
  (-> aircraft
      aircraft/emergency-kind
      emergency-words))

(defn- on-alert-click! [event]
  (when-let [icao (some-> (.-target event)
                          (cjs/closest "[data-icao]")
                          (cjs/get-attribute "data-icao"))]
    (rf/dispatch [:aircraft/select icao])))

(defn- alert-item [aircraft*]
  (let [{:aircraft/keys [icao callsign squawk]} aircraft*
        name  (or callsign icao)
        words (aircraft-alert aircraft*)]
    [:button.adsb-alert
     {:type        "button"
      :data-icao   icao
      :data-testid (str "alert:" icao)
      :aria-label  (str "Emergency: " name " squawking " squawk ", " words)}
     [:span.adsb-alert-name name]
     [:span.adsb-alert-squawk squawk]
     [:span.adsb-alert-meaning words]]))

(defn alert-ribbon []
  (let [emergencies (rf/subscribe [:aircraft/emergencies])]
    (fn []
      (when-let [alerts (seq @emergencies)]
        [:div.adsb-alerts
         {:role        "alert"
          :aria-label  "Emergency aircraft"
          :data-testid "alert-ribbon"
          :on-click    on-alert-click!}
         [:span.adsb-alert-stamp {:aria-hidden true} "NOTAM"]
         [:div.adsb-alert-rows
          ;; TODO: for-all
          (doall
            (for [a alerts]
              ^{:key (:aircraft/icao a)}
              [alert-item a]))]]))))
