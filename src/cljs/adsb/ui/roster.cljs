(ns adsb.ui.roster
  (:require [adsb.aircraft :as aircraft]
            [adsb.corejs :as cjs]
            [adsb.map.style :as style]
            [adsb.map.theme :as theme]
            [adsb.ui.health :as health]
            [adsb.ui.icon :refer [icon]]
            [adsb.ui.roster-sheet :as sheet]
            [adsb.ui.units :as units]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(defn matches-attr? [m q attr]
  (when-let [v (get m attr)]
    (str/includes? (str/lower-case v) q)))

(defn matches-query? [aircraft* q]
  (or (str/blank? q)
      (let [q* (str/lower-case q)]
        (some (partial matches-attr? aircraft* q*) [:aircraft/callsign :aircraft/icao]))))

(defn roster-sort [aircraft*]
  (sort-by (fn [ac]
             [(if (aircraft/emergency? ac) 0 1)
              (- (or (:aircraft/altitude-ft ac) -1))
              (or (:aircraft/callsign ac) (:aircraft/icao ac) "")])
           aircraft*))

(defn filter-roster [picture q]
  (->> (vals picture)
       (filter #(matches-query? % q))
       roster-sort
       vec))

(defn fmt-alt [{:aircraft/keys [on-ground? altitude-ft]}]
  (cond
    on-ground? "GND"
    altitude-ft (str altitude-ft " ft")
    :else "—"))

(defn fmt-spd [v] (or (units/knots v) "—"))
(defn fmt-sq [v] (or (some-> v str) "—"))

(rf/reg-event-db
  :roster/set-query
  (fn [db [_ q]]
    (assoc db :roster/query (or q ""))))

(rf/reg-sub
  :roster/query
  (fn [db _] (or (:roster/query db) "")))

(rf/reg-sub
  :roster/rows
  :<- [:aircraft/picture]
  :<- [:roster/query]
  (fn [[picture q] _] (filter-roster picture q)))

(rf/reg-sub
  :roster/total
  :<- [:aircraft/picture]
  (fn [picture _] (count picture)))

(defn- altitude-ramp-gradient [theme]
  (str "linear-gradient(to right, "
       (->> (style/palette theme)
            :altitude-stops
            (map second)
            (str/join ", "))
       ")"))

(defn- altitude-key []
  [:div.adsb-roster-key {:aria-label "Altitude key" :data-testid "roster-alt-key"}
   [:div.adsb-roster-key-ramp {:style {:background (altitude-ramp-gradient @theme/!theme)}}]
   [:div.adsb-roster-key-labels
    [:span "SFC"]
    [:span "FL200"]
    [:span "FL400"]]])

(defn- on-row-click! [icao] (rf/dispatch [:aircraft/focus icao]))
(defn- on-row-enter! [icao] (rf/dispatch [:aircraft/hover icao]))
(defn- on-row-leave! [] (rf/dispatch [:aircraft/clear-hover]))

(defn- row [ac selected-icao hovered-icao]
  (let [icao (:aircraft/icao ac)
        sel? (= icao selected-icao)
        hov? (= icao hovered-icao)
        emg? (aircraft/emergency? ac)]
    [:li
     [:button.adsb-roster-row
      {:type           "button"
       :class          [(when sel? "is-selected")
                        (when hov? "is-hovered")
                        (when emg? "is-emergency")]
       :data-icao      icao
       :data-testid    (str "roster-row:" icao)
       :aria-pressed   sel?
       :on-click       #(on-row-click! icao)
       :on-mouse-enter #(on-row-enter! icao)
       :on-mouse-leave on-row-leave!}
      [:span.adsb-roster-name (aircraft/display-name ac)]
      [:span.adsb-roster-alt (fmt-alt ac)]
      [:span.adsb-roster-spd (fmt-spd (:aircraft/ground-speed-kt ac))]
      [:span.adsb-roster-sq (fmt-sq (:aircraft/squawk ac))]]]))

(defn handle-label [sheet* n total q]
  (let [count-label (if (str/blank? q)
                      (str n " aircraft")
                      (str n " of " total))]
    (if (cjs/phone-stance?)
      (case sheet*
        :closed (str count-label " · show")
        :half (str count-label " · expand")
        :full (str count-label " · hide")
        (str count-label " · show"))
      (if (sheet/sheet-open? sheet*)
        (str count-label " · hide")
        (str count-label " · show")))))

(defn- handle-aria-label [sheet*]
  (if (cjs/phone-stance?)
    (case sheet*
      :closed "Show aircraft roster"
      :half "Expand aircraft roster"
      :full "Hide aircraft roster"
      "Toggle aircraft roster")
    (if (sheet/sheet-open? sheet*)
      "Hide aircraft roster"
      "Show aircraft roster")))

(defn- empty-message [q]
  (if (str/blank? q)
    "No aircraft in the picture"
    "No match — try another callsign or ICAO"))

(defn scroll-row-into-view! [icao]
  (when icao
    (some-> (cjs/select (str "[data-testid=\"roster-row:" icao "\"]"))
            (cjs/scroll-into-view! (js-obj "block" "nearest"
                                          "inline" "nearest"
                                          "behavior" "smooth")))))

(defn- roster-body [q rows* selected hovered]
  [:div.adsb-roster-body
   [:div.adsb-roster-toolbar
    [:div.adsb-roster-search
     [:label.adsb-roster-search-label.adsb-vh
      {:for "adsb-roster-search"} "Find"]
     [:div.adsb-roster-search-field
      [icon :magnifying-glass]
      [:input#adsb-roster-search.adsb-roster-search-input
       {:type          "search"
        :placeholder   "Callsign or ICAO…"
        :value         q
        :auto-complete "off"
        :spell-check   false
        :data-testid   "roster-search"
        :on-change     #(rf/dispatch [:roster/set-query (-> % .-target .-value)])}]]]
    [altitude-key]
    [:div.adsb-roster-cols {:aria-hidden true}
     [:span "Callsign"]
     [:span "Alt"]
     [:span "Kt"]
     [:span "Sq"]]]
   (if (seq rows*)
     [:ul.adsb-roster-list {:data-testid "roster-list"}
      (doall
        (for [ac rows*]
          ^{:key (:aircraft/icao ac)}
          [row ac selected hovered]))]
     [:div.adsb-roster-empty {:data-testid "roster-empty"}
      (empty-message q)])])

(defn roster []
  (let [sheet           (rf/subscribe [:roster/sheet])
        query           (rf/subscribe [:roster/query])
        rows            (rf/subscribe [:roster/rows])
        total           (rf/subscribe [:roster/total])
        selected        (rf/subscribe [:aircraft/selected-icao])
        hovered         (rf/subscribe [:aircraft/hovered-icao])
        !drag           (r/atom nil)
        !gesture        (atom nil)
        !raf            (atom nil)
        !live-h         (atom nil)
        !suppress-click (atom false)
        !prev-selected  (atom nil)
        !scroll-track   (atom nil)]
    (r/create-class
      {:display-name "adsb-roster"
       :component-did-mount
       (fn [_]
         (reset! !scroll-track
                 (r/track!
                   (fn []
                     (let [icao   @selected
                           open?* (sheet/sheet-open? @sheet)]
                       (when (and open?* icao (not= icao @!prev-selected))
                         (r/after-render #(scroll-row-into-view! icao)))
                       (reset! !prev-selected icao))))))
       :component-did-update
       (fn [_] (some->> @!live-h (sheet/set-sheet-height-px! (sheet/roster-el))))
       :component-will-unmount
       (fn [_]
         (some-> @!scroll-track r/dispose!)
         (reset! !scroll-track nil)
         (sheet/cancel-settle! !drag !raf !live-h))
       :reagent-render
       (fn []
         (let [sheet*  @sheet
               open?*  (sheet/sheet-open? sheet*)
               q       @query
               rows*   @rows
               total*  @total
               n       (count rows*)
               sel*    @selected
               drag    @!drag
               moving? (or (:moved? drag) (:settling? drag))
               body?*  (or open?* moving?)
               style   (when moving?
                         (when-let [h @!live-h]
                           {:height (str h "px") :max-height (str h "px")}))]
           [:div.adsb-roster
            (cond-> {:data-testid       "roster"
                     :data-open         (if open?* "true" "false")
                     :data-sheet        (name sheet*)
                     :class             [(when body?* "is-open")
                                         (when (:moved? drag) "is-dragging")
                                         (when (:settling? drag) "is-settling")
                                         (str "is-sheet-" (name sheet*))]
                     :on-pointer-down   #(sheet/on-sheet-pointer-down! !drag !gesture !live-h !raf %)
                     :on-pointer-move   #(sheet/on-sheet-pointer-move! !drag !gesture !live-h %)
                     :on-pointer-up     #(sheet/on-sheet-pointer-up! !drag !gesture !live-h !raf !suppress-click sheet* %)
                     :on-pointer-cancel #(when (:active? @!gesture)
                                           (sheet/abandon-gesture! !drag !gesture !live-h))
                     :on-click          #(sheet/on-sheet-click! !suppress-click %)}
                    style (assoc :style style))
            [:div.adsb-roster-rail
             [:button.adsb-roster-handle
              {:type          "button"
               :aria-expanded open?*
               :aria-label    (handle-aria-label sheet*)
               :data-testid   "roster-toggle"
               :data-sheet    (name sheet*)}
              [:span.adsb-roster-handle-bar {:aria-hidden true}]
              [:span.adsb-roster-handle-label
               (handle-label sheet* n total* q)]]
             [health/health]]
            (when body?*
              [roster-body q rows* sel* @hovered])]))})))
