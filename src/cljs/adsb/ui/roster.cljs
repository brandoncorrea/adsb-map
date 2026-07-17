(ns adsb.ui.roster
  (:require [adsb.aircraft :as aircraft]
            [adsb.corejs :as cjs]
            [adsb.map.style :as style]
            [adsb.map.theme :as theme]
            [adsb.ui.health :as health]
            [adsb.ui.icon :refer [icon]]
            [adsb.ui.units :as units]
            [clojure.math :as math]
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
(def ^:const sheet-states [:closed :half :full])

;; Must match the CSS snap heights (--roster-sheet-h/--roster-full-h/
;; --roster-rail-h in css/roster.clj's phone block) or the drag settle
;; animation lands on the wrong height.
(def ^:const sheet-heights
  {:closed 0.0
   :half   0.52
   :full   0.92})

(def ^:const closed-rail-px 48)

(defn- closed-sheet-min-px []
  (+ closed-rail-px (cjs/css-px "--safe-bottom")))

(def ^:const drag-velocity-threshold 0.45)
(def ^:const tap-slop-px 6)
(def ^:const settle-ms 420)

(defn ease-out-cubic [t]
  (let [u (- 1.0 (max 0.0 (min 1.0 t)))]
    (- 1.0 (math/pow u 3.0))))

(defn sheet-open? [sheet] (not= :closed sheet))

(defn height-fraction->sheet [frac velocity]
  (let [frac*   (max 0.0 (min 1.0 frac))
        v       (or velocity 0.0)
        nearest (apply min-key
                       (fn [s] (abs (- frac* (get sheet-heights s))))
                       sheet-states)]
    (cond
      (> v drag-velocity-threshold)
      (let [idx (.indexOf sheet-states nearest)]
        (nth sheet-states (min (dec (count sheet-states)) (inc idx))))

      (< v (- drag-velocity-threshold))
      (let [idx (.indexOf sheet-states nearest)]
        (nth sheet-states (max 0 (dec idx))))

      :else nearest)))

(defn next-sheet [sheet]
  (case sheet
    :closed :half
    :half :full
    :full :closed
    :half))

(def ^:const default-sheet :closed)
(def ^:const default-open-sheet :half)

(rf/reg-event-db
  :roster/set-sheet
  (fn [db [_ sheet]]
    (assoc db :roster/sheet (or sheet default-open-sheet))))

(rf/reg-event-db
  :roster/toggle
  (fn [db _]
    (let [sheet (get db :roster/sheet default-sheet)]
      (assoc db :roster/sheet
                (if (sheet-open? sheet) :closed default-open-sheet)))))

(rf/reg-event-db
  :roster/cycle
  (fn [db _]
    (assoc db :roster/sheet
              (next-sheet (get db :roster/sheet default-sheet)))))

(rf/reg-event-db
  :roster/set-query
  (fn [db [_ q]]
    (assoc db :roster/query (or q ""))))

(rf/reg-sub
  :roster/sheet
  (fn [db _]
    (get db :roster/sheet default-sheet)))

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

(defn handle-label [sheet n total q]
  (let [count-label (if (str/blank? q)
                      (str n " aircraft")
                      (str n " of " total))]
    (if (cjs/phone-stance?)
      (case sheet
        :closed (str count-label " · show")
        :half (str count-label " · expand")
        :full (str count-label " · hide")
        (str count-label " · show"))
      (if (sheet-open? sheet)
        (str count-label " · hide")
        (str count-label " · show")))))

(defn- handle-aria-label [sheet]
  (if (cjs/phone-stance?)
    (case sheet
      :closed "Show aircraft roster"
      :half "Expand aircraft roster"
      :full "Hide aircraft roster"
      "Toggle aircraft roster")
    (if (sheet-open? sheet)
      "Hide aircraft roster"
      "Show aircraft roster")))

(defn- viewport-height [] (or (some-> js/window .-innerHeight) 1))

(defn sheet-height-px [sheet]
  (let [vh   (viewport-height)
        safe (cjs/css-px "--safe-bottom")
        frac (get sheet-heights sheet 0.52)]
    (case sheet
      :closed (+ closed-rail-px safe)
      :full (min (+ (* frac vh) safe)
                 (- vh (cjs/css-px "--safe-top")))
      (+ (* frac vh) safe))))

(defn- pointer-y [e]
  (or (.-clientY e)
      (some-> (.-touches e) (aget 0) .-clientY)
      0))

(defn- roster-el [] (cjs/select "[data-testid=\"roster\"]"))

(defn- set-sheet-height-px! [el h]
  (when el
    (let [px (str h "px")]
      (set! (-> el .-style .-height) px)
      (set! (-> el .-style .-maxHeight) px))))

(defn- clear-sheet-height! [el]
  (when el
    (set! (-> el .-style .-height) "")
    (set! (-> el .-style .-maxHeight) "")))

(defn- cancel-settle! [!drag !raf !live-h]
  (when-let [id @!raf]
    (cjs/cancel-animation id)
    (reset! !raf nil))
  (when (or @!live-h (:settling? @!drag))
    (clear-sheet-height! (roster-el))
    (reset! !live-h nil)
    (when (:settling? @!drag)
      (reset! !drag nil))))

(defn- abandon-gesture! [!drag !gesture !live-h]
  (reset! !gesture nil)
  (reset! !live-h nil)
  (clear-sheet-height! (roster-el))
  (reset! !drag nil))

(defn- drag-surface? [e]
  (let [target (.-target e)]
    (not (and target
              (.-closest target)
              (cjs/closest target ".adsb-roster-body")))))

(defn- on-sheet-pointer-down! [!drag !gesture !live-h !raf e]
  (when (and (cjs/phone-stance?)
             (drag-surface? e)
             (or (nil? (.-button e)) (zero? (.-button e))))
    (cancel-settle! !drag !raf !live-h)
    (let [y0 (pointer-y e)
          el (.-currentTarget e)
          h0 (or (.-offsetHeight el) closed-rail-px)]
      (reset! !gesture {:active?  true
                        :start-y  y0
                        :start-h  h0
                        :last-y   y0
                        :last-t   (cjs/performance-now)
                        :velocity 0
                        :height   h0})
      (when (.-setPointerCapture el)
        (js-invoke el "setPointerCapture" (.-pointerId e)))
      (cjs/prevent-default e))))

(defn- on-sheet-pointer-move! [!drag !gesture !live-h e]
  (when-let [{:keys [active? start-y start-h last-y last-t]} @!gesture]
    (when active?
      (let [y   (pointer-y e)
            t   (cjs/performance-now)
            dy  (- start-y y)
            vh  (viewport-height)
            top (- vh (cjs/css-px "--safe-top"))
            h   (max (closed-sheet-min-px) (min top (+ start-h dy)))
            dt  (max 1 (- t last-t))
            vy  (/ (- last-y y) dt)]
        (swap! !gesture assoc
               :last-y y
               :last-t t
               :velocity vy
               :height h)
        (when (and (not (:moved? @!drag))
                   (> (abs (- start-y y)) tap-slop-px))
          (swap! !drag assoc :moved? true))
        (when (:moved? @!drag)
          (reset! !live-h h)
          (set-sheet-height-px! (roster-el) h)
          (cjs/prevent-default e))))))

(defn- settle-sheet-after-drag! [!drag !raf !live-h release-h target-sheet]
  (cancel-settle! !drag !raf !live-h)
  (let [from (double (or release-h (sheet-height-px target-sheet)))
        to   (double (sheet-height-px target-sheet))
        dist (abs (- to from))
        el   (roster-el)]
    (cond
      (or (cjs/prefers-reduced-motion?) (< dist 0.5) (nil? el))
      (do (clear-sheet-height! el)
          (reset! !live-h nil)
          (reset! !drag nil))

      :else
      (let [t0 (cjs/performance-now)]
        (reset! !live-h from)
        (reset! !drag {:settling? true})
        (set-sheet-height-px! el from)
        (letfn [(frame [now]
                  (if-not (:settling? @!drag)
                    (reset! !raf nil)
                    (let [elapsed (- now t0)
                          p       (min 1.0 (/ elapsed settle-ms))
                          e       (ease-out-cubic p)
                          h       (+ from (* e (- to from)))]
                      (reset! !live-h h)
                      (set-sheet-height-px! el h)
                      (if (< p 1.0)
                        (reset! !raf (cjs/request-animation frame))
                        (do
                          (clear-sheet-height! el)
                          (reset! !live-h nil)
                          (reset! !raf nil)
                          (reset! !drag nil))))))]
          (reset! !raf (cjs/request-animation frame)))))))

(defn- on-sheet-pointer-up! [!drag !gesture !live-h !raf !suppress-click sheet e]
  (when (:active? @!gesture)
    (let [{:keys [height velocity]} @!gesture
          moved? (boolean (:moved? @!drag))
          vh     (viewport-height)
          frac   (if (and height (pos? vh))
                   (/ height vh)
                   (get sheet-heights sheet 0.52))
          target (height-fraction->sheet frac velocity)]
      (reset! !gesture nil)
      (if moved?
        (do (reset! !suppress-click true)
            (rf/dispatch [:roster/set-sheet target])
            (settle-sheet-after-drag! !drag !raf !live-h height target))
        (abandon-gesture! !drag !gesture !live-h))
      (when (.-releasePointerCapture (.-currentTarget e))
        (try
          (js-invoke (.-currentTarget e) "releasePointerCapture" (.-pointerId e))
          (catch :default _))))))

(defn- on-sheet-click! [!suppress-click e]
  (when (drag-surface? e)
    (if @!suppress-click
      (reset! !suppress-click false)
      (rf/dispatch (if (cjs/phone-stance?) [:roster/cycle] [:roster/toggle])))))

(defn- empty-message [q]
  (if (str/blank? q)
    "No aircraft in the picture"
    "No match — try another callsign or ICAO"))

(defn scroll-row-into-view! [icao]
  (when icao
    (some-> (cjs/select (str "[data-testid=\"roster-row:" icao "\"]"))
            (cjs/scroll-into-view (js-obj "block" "nearest"
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
                           open?* (sheet-open? @sheet)]
                       (when (and open?* icao (not= icao @!prev-selected))
                         (r/after-render #(scroll-row-into-view! icao)))
                       (reset! !prev-selected icao))))))
       :component-did-update
       (fn [_] (some->> @!live-h (set-sheet-height-px! (roster-el))))
       :component-will-unmount
       (fn [_]
         (some-> @!scroll-track r/dispose!)
         (reset! !scroll-track nil)
         (cancel-settle! !drag !raf !live-h))
       :reagent-render
       (fn []
         (let [sheet*  @sheet
               open?*  (sheet-open? sheet*)
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
                     :on-pointer-down   #(on-sheet-pointer-down! !drag !gesture !live-h !raf %)
                     :on-pointer-move   #(on-sheet-pointer-move! !drag !gesture !live-h %)
                     :on-pointer-up     #(on-sheet-pointer-up! !drag !gesture !live-h !raf !suppress-click sheet* %)
                     :on-pointer-cancel #(when (:active? @!gesture)
                                           (abandon-gesture! !drag !gesture !live-h))
                     :on-click          #(on-sheet-click! !suppress-click %)}
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
