(ns adsb.ui.aircraft-panel
  (:require [adsb.aircraft :as aircraft]
            [adsb.corejs :as cjs]
            [adsb.enrich :as enrich]
            [adsb.ui.alert :as alert]
            [adsb.ui.icon :refer [icon]]
            [adsb.ui.units :as units]
            [clojure.string :as str]
            [re-frame.core :as rf]))

(def ^:const em-dash "—")
(def ^:const clock-interval-ms 1000)
(defonce ^:private !clock (atom nil))

(defn start-clock! []
  (when (nil? @!clock)
    (rf/dispatch [:ui/tick (cjs/now-ms)])
    (reset! !clock
            (js/setInterval #(rf/dispatch [:ui/tick (cjs/now-ms)])
                            clock-interval-ms))))

(defonce ^:private !keyboard (atom nil))

(defn escape-deselect? [e]
  (and (= "Escape" (.-key e))
       (let [target (.-target e)
             tag    (some-> target .-tagName str/lower-case)]
         (not (or (= tag "input")
                  (= tag "textarea")
                  (= tag "select")
                  (and target (.-isContentEditable target)))))))

(defn- on-document-key! [e]
  (when (escape-deselect? e)
    (rf/dispatch [:aircraft/clear-selection])))

(defn start-keyboard! []
  (when (nil? @!keyboard)
    (cjs/add-listener "keydown" on-document-key!)
    (reset! !keyboard true)))

(defn- altitude-display [{:aircraft/keys [on-ground? altitude-ft]}]
  (if on-ground?
    "ground"
    altitude-ft))

(defn- seen-age-s [{:aircraft/keys [seen-at-ms]} now-ms]
  (when (and seen-at-ms now-ms)
    (max 0 (quot (- now-ms seen-at-ms) 1000))))

(defn- close! []
  (rf/dispatch [:aircraft/clear-selection]))

(defn- toggle-expand! []
  (rf/dispatch [:panel/toggle-expanded]))

(defn- fact [label value]
  [:div.adsb-fact
   [:span.adsb-fact-label label]
   [:span.adsb-fact-value {:data-testid (str "fact:" label)}
    (if (nil? value) em-dash (str value))]])

(defn- badge [kind label]
  [:span.adsb-badge {:class (str "adsb-badge-" kind) :role "status"} label])

(defn- type-display [enrichment]
  (or (enrich/type-desc enrichment)
      (enrich/type-code enrichment)))

(defn- altitude-chip [aircraft]
  (let [alt (altitude-display aircraft)]
    (cond
      (nil? alt) em-dash
      (= alt "ground") "GND"
      :else (str alt " ft"))))

(defn- panel-body [aircraft now-ms enrichment expanded?]
  (let [{:aircraft/keys [icao callsign ground-speed-kt track-deg squawk
                         baro-rate-fpm position-suspect? mlat?]} aircraft
        seen           (seen-age-s aircraft now-ms)
        emergency-kind (aircraft/emergency-kind aircraft)
        title          (or callsign icao)]
    [:aside.adsb-panel
     {:role          "complementary"
      :aria-label    "Aircraft detail"
      :data-testid   "aircraft-panel"
      :data-expanded (if expanded? "true" "false")
      :class         [(when expanded? "is-expanded")
                      (when-not expanded? "is-collapsed")
                      (when emergency-kind "is-emergency")]}
     [:div.adsb-panel-header
      [:button.adsb-panel-toggle
       {:type          "button"
        :aria-expanded (boolean expanded?)
        :aria-label    (if expanded? "Collapse detail" "Expand detail")
        :data-testid   "panel-toggle"
        :on-click      toggle-expand!}
       [:span.adsb-panel-chevron
        [icon (if expanded? :chevron-down :chevron-right)]]
       [:span.adsb-panel-title {:data-testid "panel-title"} title]
       (when-not expanded?
         [:span.adsb-panel-chip-meta (altitude-chip aircraft)])]
      [:button.adsb-panel-close
       {:type        "button"
        :aria-label  "Close"
        :data-testid "panel-close"
        :on-click    close!}
       [icon :xmark]]]
     (when expanded?
       [:<>
        (when (or emergency-kind position-suspect? mlat?)
          [:div.adsb-panel-badges
           (when emergency-kind
             [badge "emergency" (alert/emergency-words emergency-kind)])
           (when position-suspect? [badge "suspect" "position suspect"])
           (when mlat? [badge "mlat" "MLAT"])])
        [:div.adsb-panel-facts
         [fact "ICAO" icao]
         [fact "Type" (type-display enrichment)]
         [fact "Registration" (enrich/registration enrichment)]
         [fact "Operator" (enrich/operator enrichment)]
         [fact "Altitude" (altitude-display aircraft)]
         [fact "Ground speed" (units/knots ground-speed-kt)]
         [fact "Track" (units/track track-deg)]
         [fact "Squawk" squawk]
         [fact "Vertical rate" baro-rate-fpm]
         [fact "Seen" (when seen (str seen "s ago"))]]])]))

(defn aircraft-panel []
  (let [selected  (rf/subscribe [:aircraft/selected])
        now       (rf/subscribe [:ui/now-ms])
        expanded? (rf/subscribe [:panel/expanded?])]
    (fn []
      (when-let [aircraft @selected]
        [panel-body aircraft @now
         @(rf/subscribe [:enrich/record (:aircraft/icao aircraft)])
         @expanded?]))))
