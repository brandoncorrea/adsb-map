(ns adsb.ui.roster
  "The live aircraft roster — Search + Sheet, the product chrome that
  replaced the Stack (bead adsb-66h).

  Find and browse are one surface:
    * Empty query → full ranked list (urgency → altitude → name).
    * Typed query → the same list, filtered by callsign or hex.
  Desktop: a side dock. Phone: a bottom pull-up with three snap points
  (closed / half / full) and a drag-to-snap handle (adsb-xgg). Same
  markup, stance-native chrome (adsb.css.roster).

  ONE SOURCE OF TRUTH. Rows derive from :aircraft/picture — the same picture
  the map reads. Never a second copy of the sky.

  BOUNDARY 4 (docs/validation-boundaries.md). Every callsign and icao
  arrived off unauthenticated radio: well-TYPED, not trustWORTHY. Each is
  rendered as plain hiccup text and escaped by Reagent.

  Interaction reuses the map's contracts:
    * Click a row → [:aircraft/focus icao] (select + fly-to when positioned).
    * Hover a row → [:aircraft/hover icao] (map highlight channel).
  Health rides the dock's handle rail (adsb.ui.health) — the apparatus
  signal that used to sit on the Stack's caption row."
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.map.style :as style]
    [adsb.map.theme :as theme]
    [adsb.ui.health :as health]
    [clojure.string :as str]
    [re-frame.core :as rf]
    [reagent.core :as r]))

;; ---------------------------------------------------------------------
;; Pure — rank, filter, format. Browser tests pin these.

(defn matches-query?
  "True when `aircraft*` matches the find query. Blank query matches all —
  the hybrid's empty-query contract is the full ranked roster."
  [aircraft* q]
  (if (str/blank? q)
    true
    (let [q* (str/lower-case q)]
      (or (str/includes? (str/lower-case (or (:aircraft/callsign aircraft*) "")) q*)
          (str/includes? (str/lower-case (or (:aircraft/icao aircraft*) "")) q*)))))

(defn roster-sort
  "Emergencies first, then altitude descending (unknown last), then name."
  [aircraft*]
  (sort-by (fn [ac]
             [(if (aircraft/emergency? ac) 0 1)
              (- (or (:aircraft/altitude-ft ac) -1))
              (or (:aircraft/callsign ac) (:aircraft/icao ac) "")])
           aircraft*))

(defn filter-roster
  "Ranked, filtered view of `picture` for the current `q`."
  [picture q]
  (->> (vals picture)
       (filter #(matches-query? % q))
       roster-sort
       vec))

(defn display-name
  [{:aircraft/keys [callsign icao]}]
  (or callsign icao))

(defn fmt-alt
  [{:aircraft/keys [on-ground? altitude-ft]}]
  (cond
    on-ground?          "GND"
    (some? altitude-ft) (str altitude-ft " ft")
    :else               "—"))

(defn fmt-spd [v] (if (some? v) (str v) "—"))
(defn fmt-sq  [v] (if (some? v) (str v) "—"))

;; ---------------------------------------------------------------------
;; Sheet geometry — three snap points on the phone pull-up (adsb-xgg).
;; Heights are fractions of the viewport; the closed rail is a fixed px
;; band so the map always keeps a grab target. Pure, so the drag snap
;; can be unit-tested without a pointer.

(def ^:const sheet-states
  "Ordered bottom → top. Drag snap walks this ladder."
  [:closed :half :full])

(def ^:const sheet-heights
  "Viewport fractions for each snap (closed is the rail only — CSS owns
  the 44px band; the fraction is for drag math against window height)."
  {:closed 0.0
   :half   0.52
   :full   0.92})

(def ^:const closed-rail-px
  "Collapsed phone rail height. Matches --roster-rail-h / --roster-w on
  the phone stance and the attribution's closed clearance (adsb-rsm)."
  48)

(def ^:const drag-velocity-threshold
  "px/ms. Faster than this commits to the next snap in the swipe
  direction instead of nearest-by-distance — the keyboard-drawer feel."
  0.45)

(defn sheet-open?
  "True when the sheet shows its body (half or full). Desktop open/closed
  collapses to this predicate."
  [sheet]
  (not= :closed sheet))

(defn height-fraction->sheet
  "Nearest snap for a drag release at `frac` of viewport height (0..1),
  optionally biased by velocity (px/ms, positive = opening up)."
  [frac velocity]
  (let [frac* (max 0.0 (min 1.0 frac))
        v     (or velocity 0.0)
        nearest (apply min-key
                       (fn [s] (js/Math.abs (- frac* (get sheet-heights s))))
                       sheet-states)]
    (cond
      (> v drag-velocity-threshold)
      (let [idx (.indexOf sheet-states nearest)]
        (nth sheet-states (min (dec (count sheet-states)) (inc idx))))

      (< v (- drag-velocity-threshold))
      (let [idx (.indexOf sheet-states nearest)]
        (nth sheet-states (max 0 (dec idx))))

      :else nearest)))

(defn next-sheet
  "Tap-to-cycle: closed → half → full → closed. One finger, one step."
  [sheet]
  (case sheet
    :closed :half
    :half   :full
    :full   :closed
    :half))

;; ---------------------------------------------------------------------
;; View state — sheet snap + find query. Properties of the VIEW, not of
;; the sky, so they live beside the surface that owns them.

(rf/reg-event-db
  :roster/set-sheet
  (fn [db [_ sheet]]
    (assoc db :roster/sheet (or sheet :half))))

;; Binary open/closed. Open settles on :half (the default reading height).
;; Phone tap-to-cycle uses :roster/cycle so a dock click is still one step
;; to hide.
(rf/reg-event-db
  :roster/toggle
  (fn [db _]
    (let [sheet (get db :roster/sheet :half)]
      (assoc db :roster/sheet (if (sheet-open? sheet) :closed :half)))))

;; Phone handle tap: closed → half → full → closed.
(rf/reg-event-db
  :roster/cycle
  (fn [db _]
    (assoc db :roster/sheet (next-sheet (get db :roster/sheet :half)))))

(rf/reg-event-db
  :roster/set-query
  (fn [db [_ q]]
    (assoc db :roster/query (or q ""))))

(rf/reg-sub
  :roster/sheet
  (fn [db _]
    ;; Default half — the dock is the product chrome, not a drawer you must
    ;; discover. Phone users can collapse or expand to full for map room.
    (get db :roster/sheet :half)))

(rf/reg-sub
  :roster/open?
  :<- [:roster/sheet]
  (fn [sheet _]
    (sheet-open? sheet)))

(rf/reg-sub
  :roster/query
  (fn [db _]
    (or (:roster/query db) "")))

(rf/reg-sub
  :roster/rows
  :<- [:aircraft/picture]
  :<- [:roster/query]
  (fn [[picture q] _]
    (filter-roster picture q)))

(rf/reg-sub
  :roster/total
  :<- [:aircraft/picture]
  (fn [picture _]
    (count picture)))

;; ---------------------------------------------------------------------
;; Altitude key — static ramp from the same palette the map paints with.

(defn- altitude-key
  []
  (let [theme  @theme/!theme
        stops  (:altitude-stops (style/palette theme))
        ramp   (str "linear-gradient(to right, "
                    (str/join ", " (map second stops))
                    ")")]
    [:div.adsb-roster-key {:aria-label "Altitude key" :data-testid "roster-alt-key"}
     [:div.adsb-roster-key-ramp {:style {:background ramp}}]
     [:div.adsb-roster-key-labels
      [:span "SFC"]
      [:span "FL200"]
      [:span "FL400"]]]))

;; ---------------------------------------------------------------------
;; Rows

(defn- on-row-click!
  [icao]
  (rf/dispatch [:aircraft/focus icao]))

(defn- on-row-enter!
  [icao]
  (rf/dispatch [:aircraft/hover icao]))

(defn- on-row-leave!
  []
  (rf/dispatch [:aircraft/clear-hover]))

(defn- row
  [ac selected-icao hovered-icao]
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
       :aria-pressed   (boolean sel?)
       :on-click       #(on-row-click! icao)
       :on-mouse-enter #(on-row-enter! icao)
       :on-mouse-leave on-row-leave!}
      [:span.adsb-roster-name (display-name ac)]
      [:span.adsb-roster-alt (fmt-alt ac)]
      [:span.adsb-roster-spd (fmt-spd (:aircraft/ground-speed-kt ac))]
      [:span.adsb-roster-sq (fmt-sq (:aircraft/squawk ac))]]]))

;; ---------------------------------------------------------------------
;; Handle label

(defn phone-stance?
  "True under the phone media query that owns the bottom drawer. Pure
  enough for the handle click and label: desktop binary-toggles, phone
  cycles snaps. Public so tests can redef the media query."
  []
  (boolean
    (and (exists? js/window)
         (.-matchMedia js/window)
         (.-matches (.matchMedia js/window "(max-width: 640px)")))))

(defn handle-label
  "Visible rail copy for the current sheet. Desktop is binary open/closed
  (hide / show). Phone walks three snaps (show → expand → hide)."
  [sheet n total q]
  (let [count-label (if (str/blank? q)
                      (str n " aircraft")
                      (str n " of " total))]
    (if (phone-stance?)
      (case sheet
        :closed (str count-label " · show")
        :half   (str count-label " · expand")
        :full   (str count-label " · hide")
        (str count-label " · show"))
      ;; Desktop: open is always "hide" — toggle closes in one step, so
      ;; "expand" would lie about the next action.
      (if (sheet-open? sheet)
        (str count-label " · hide")
        (str count-label " · show")))))

(defn- handle-aria-label
  [sheet]
  (if (phone-stance?)
    (case sheet
      :closed "Show aircraft roster"
      :half   "Expand aircraft roster"
      :full   "Hide aircraft roster"
      "Toggle aircraft roster")
    (if (sheet-open? sheet)
      "Hide aircraft roster"
      "Show aircraft roster")))

;; ---------------------------------------------------------------------
;; Drag — pointer events on the phone handle. Local ratom for the live
;; height while dragging; commit snaps on pointerup. Pure snap math is
;; height-fraction->sheet above.

(defn- viewport-height
  []
  (or (some-> js/window .-innerHeight) 1))

(defn- pointer-y
  [^js e]
  (or (.-clientY e)
      (some-> (.-touches e) (aget 0) .-clientY)
      0))

(defn- on-handle-pointer-down!
  "Start a drag session. Only the primary button / first touch counts."
  [!drag ^js e]
  (when (or (nil? (.-button e)) (zero? (.-button e)))
    (let [y0  (pointer-y e)
          el  (.-currentTarget e)
          root (.closest el ".adsb-roster")
          h0  (if root (.-offsetHeight root) closed-rail-px)]
      (reset! !drag {:active? true
                     :start-y y0
                     :start-h h0
                     :last-y  y0
                     :last-t  (js/performance.now)
                     :velocity 0
                     :height  h0})
      (when (.-setPointerCapture el)
        (.setPointerCapture el (.-pointerId e)))
      (.preventDefault e))))

(defn- on-handle-pointer-move!
  [!drag ^js e]
  (when-let [{:keys [active? start-y start-h last-y last-t]} @!drag]
    (when active?
      (let [y   (pointer-y e)
            t   (js/performance.now)
            dy  (- start-y y)                 ; up = grow the sheet
            vh  (viewport-height)
            h   (max closed-rail-px (min vh (+ start-h dy)))
            dt  (max 1 (- t last-t))
            vy  (/ (- last-y y) dt)]          ; positive = opening
        (swap! !drag assoc
               :last-y y
               :last-t t
               :velocity vy
               :height h)
        (.preventDefault e)))))

(defn- on-handle-pointer-up!
  "End a drag. A real swipe snaps; a tap leaves the sheet alone so the
  trailing click can cycle. `!suppress-click` blocks the double-fire that
  would otherwise follow a snap."
  [!drag !suppress-click sheet ^js e]
  (when (:active? @!drag)
    (let [{:keys [height velocity start-y last-y]} @!drag
          vh     (viewport-height)
          moved? (> (js/Math.abs (- (or start-y 0) (or last-y 0))) 6)
          frac   (if (and height (pos? vh))
                   (/ height vh)
                   (get sheet-heights sheet 0.52))]
      (when moved?
        (reset! !suppress-click true)
        (rf/dispatch [:roster/set-sheet (height-fraction->sheet frac velocity)]))
      (reset! !drag nil)
      (when (.-releasePointerCapture (.-currentTarget e))
        (try
          (.releasePointerCapture (.-currentTarget e) (.-pointerId e))
          (catch :default _ nil))))))

(defn- on-handle-click!
  "Tap without a meaningful drag. Phone cycles closed → half → full →
  closed; desktop binary-toggles (half ↔ closed). Suppressed when
  pointerup already committed a snap for this gesture."
  [!suppress-click]
  (if @!suppress-click
    (reset! !suppress-click false)
    (rf/dispatch (if (phone-stance?) [:roster/cycle] [:roster/toggle]))))

;; ---------------------------------------------------------------------
;; The surface

(defn- empty-message
  [q]
  (if (str/blank? q)
    "No aircraft in the picture"
    "No match — try another callsign or hex"))

(defn scroll-row-into-view!
  "Scroll the roster row for `icao` into the list's visible band. Pure
  enough for tests to redef; no-op when the body is closed or the row is
  filtered out. Uses `nearest` so an already-visible row does not jump."
  [icao]
  (when icao
    (when-let [el (.querySelector js/document
                                  (str "[data-testid=\"roster-row:" icao "\"]"))]
      (.scrollIntoView el #js {:block "nearest" :inline "nearest"
                               :behavior "smooth"}))))

(defn- roster-body
  [q rows* selected hovered]
  [:div.adsb-roster-body
   [:div.adsb-roster-toolbar
    [:div.adsb-roster-search
     [:label.adsb-roster-search-label {:for "adsb-roster-search"} "Find"]
     [:input#adsb-roster-search.adsb-roster-search-input
      {:type          "search"
       :placeholder   "callsign or hex…"
       :value         q
       :auto-complete "off"
       :spell-check   false
       :data-testid   "roster-search"
       :on-change     #(rf/dispatch [:roster/set-query (.. % -target -value)])}]]
    [altitude-key]
    [:div.adsb-roster-cols {:aria-hidden true}
     [:span "Callsign"]
     [:span "Alt"]
     [:span "Kt"]
     [:span "Sq"]]]
   (if (seq rows*)
     [:ul.adsb-roster-list {:data-testid "roster-list"}
      (for [ac rows*]
        ^{:key (:aircraft/icao ac)}
        [row ac selected hovered])]
     [:div.adsb-roster-empty {:data-testid "roster-empty"}
      (empty-message q)])])

(defn roster
  "The Search + Sheet dock/drawer. Form-2: subscribe once, deref per render.
  Phone drag state lives in a local ratom so the map never re-renders on
  every pointermove — only the sheet height does.

  Selection → scroll: a track! watches the selected icao (map click, alert,
  roster row). When it changes and the sheet is open, the matching row is
  scrolled into the list (adsb-rsm) — outside the render path, same pattern
  as the selection ring."
  []
  (let [sheet           (rf/subscribe [:roster/sheet])
        query           (rf/subscribe [:roster/query])
        rows            (rf/subscribe [:roster/rows])
        total           (rf/subscribe [:roster/total])
        selected        (rf/subscribe [:aircraft/selected-icao])
        hovered         (rf/subscribe [:aircraft/hovered-icao])
        !drag           (r/atom nil)
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
                         ;; after-render: the row must exist in the DOM
                         ;; before we ask the browser to scroll to it.
                         (r/after-render #(scroll-row-into-view! icao)))
                       (reset! !prev-selected icao))))))
       :component-will-unmount
       (fn [_]
         (some-> @!scroll-track r/dispose!)
         (reset! !scroll-track nil))
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
               drag-h  (when (:active? drag) (:height drag))
               style   (when drag-h
                         {:height (str drag-h "px") :max-height "none"})]
           [:div.adsb-roster
            (cond-> {:data-testid "roster"
                     :data-open   (if open?* "true" "false")
                     :data-sheet  (name sheet*)
                     :class       [(when open?* "is-open")
                                   (when (:active? drag) "is-dragging")
                                   (str "is-sheet-" (name sheet*))]}
              style (assoc :style style))
            [:div.adsb-roster-rail
             [:button.adsb-roster-handle
              {:type            "button"
               :aria-expanded   (boolean open?*)
               :aria-label      (handle-aria-label sheet*)
               :data-testid     "roster-toggle"
               :data-sheet      (name sheet*)
               :on-pointer-down #(on-handle-pointer-down! !drag %)
               :on-pointer-move #(on-handle-pointer-move! !drag %)
               :on-pointer-up   #(on-handle-pointer-up! !drag !suppress-click sheet* %)
               :on-pointer-cancel #(reset! !drag nil)
               :on-click        #(on-handle-click! !suppress-click)}
              [:span.adsb-roster-handle-bar {:aria-hidden true}]
              [:span.adsb-roster-handle-label
               (handle-label sheet* n total* q)]]
             [health/health]]
            (when open?*
              [roster-body q rows* sel* @hovered])]))})))
