(ns adsb.ui.roster
  "The live aircraft roster — Search + Sheet, the product chrome that
  replaced the Stack (bead adsb-66h).

  Find and browse are one surface:
    * Empty query → full ranked list (urgency → altitude → name).
    * Typed query → the same list, filtered by callsign or ICAO address.
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
    [adsb.ui.icon :refer [icon]]
    [adsb.ui.units :as units]
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

;; Whole knots — adsb.ui.units owns the rounding, so the Kt column and the
;; panel's Ground speed can never print the same aircraft two ways.
(defn fmt-spd [v] (or (units/knots v) "—"))
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

(defn- css-px
  "Resolved px for a :root custom property. 0 when missing."
  [prop]
  (let [raw (some-> js/document
                    .-documentElement
                    js/getComputedStyle
                    (.getPropertyValue prop)
                    str
                    .trim)
        n   (when (seq raw) (js/parseFloat raw))]
    (if (js/isFinite n) n 0)))

(defn- closed-sheet-min-px
  "Minimum phone sheet height while dragging — rail plus home-indicator."
  []
  (+ closed-rail-px (css-px "--safe-bottom")))

(def ^:const drag-velocity-threshold
  "px/ms. Faster than this commits to the next snap in the swipe
  direction instead of nearest-by-distance — the keyboard-drawer feel."
  0.45)

(def ^:const tap-slop-px
  "How far the finger may wander and still be a TAP. Under this the gesture
  has not begun: nothing moves, nothing mounts, nothing restyles, and the
  trailing click cycles the sheet. A press is not a drag."
  6)

(def ^:const settle-ms
  "Snap settle duration after a drag release. Driven by rAF (not CSS
  height transitions — those expand poorly in WebKit)."
  420)

(defn- prefers-reduced-motion?
  []
  (boolean
    (and (exists? js/window)
         (.-matchMedia js/window)
         (.-matches (.matchMedia js/window "(prefers-reduced-motion: reduce)")))))

(defn ease-out-cubic
  "t in 0..1 → eased progress. Soft landing; matches the keyboard-drawer feel."
  [t]
  (let [u (- 1.0 (max 0.0 (min 1.0 t)))]
    (- 1.0 (* u u u))))

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

(def ^:const default-sheet
  "The sheet the app OPENS on: closed. The map is the product, and it gets
  the whole viewport until the reader asks for the roster — one tap on the
  rail, which is always on screen and says what it does.

  Named once and read everywhere (the sub and both toggles default through
  it), because the previous shape — `:half` repeated as a `get` fallback in
  three places — is the kind that gets changed in two of them."
  :closed)

(def ^:const default-open-sheet
  "Where the sheet lands when it is OPENED without a height being named —
  the binary desktop toggle, and a nil :roster/set-sheet. Half, not full:
  opening the roster should not bury the map behind it. Distinct from
  `default-sheet`, which is where the app STARTS; conflating the two is
  what makes a drawer that cannot be shut."
  :half)

(rf/reg-event-db
  :roster/set-sheet
  (fn [db [_ sheet]]
    (assoc db :roster/sheet (or sheet default-open-sheet))))

;; Binary open/closed. Open settles on :half (default-open-sheet). Phone
;; tap-to-cycle uses :roster/cycle so a dock click is still one step to hide.
(rf/reg-event-db
  :roster/toggle
  (fn [db _]
    (let [sheet (get db :roster/sheet default-sheet)]
      (assoc db :roster/sheet
             (if (sheet-open? sheet) :closed default-open-sheet)))))

;; Phone handle tap: closed → half → full → closed.
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
;; Drag — pointer events on the SHEET, not on the handle button. Local
;; ratom for the live height while dragging; commit snaps on pointerup.
;; Pure snap math is height-fraction->sheet above.
;;
;; THE DRAG SURFACE IS THE WHOLE LIP. It used to be the handle <button>,
;; which is a strictly smaller thing than the drawer edge the reader can
;; see: the rail reserves 36px on the right for the health pin and pads
;; itself all round, and the shell pads a safe-bottom band under that. A
;; finger landing on any of it hit no listener and no touch-action, so the
;; browser took the gesture and panned the PAGE instead of the DRAWER.
;; Listeners now sit on the shell and everything outside the scrolling body
;; drags (adsb.css.roster pins touch-action to match).

(defn- viewport-height
  []
  (or (some-> js/window .-innerHeight) 1))

(defn sheet-height-px
  "Rendered height of a snap in CSS pixels. Mirrors adsb.css.roster's
  phone sheet rules (fraction of viewport + safe-bottom, border-box)."
  [sheet]
  (let [vh   (viewport-height)
        safe (css-px "--safe-bottom")
        frac (get sheet-heights sheet 0.52)]
    (if (= :closed sheet)
      (+ closed-rail-px safe)
      (+ (* frac vh) safe))))

(defn- pointer-y
  [^js e]
  (or (.-clientY e)
      (some-> (.-touches e) (aget 0) .-clientY)
      0))

(defn- roster-el
  "The live roster root, if mounted."
  []
  (.querySelector js/document "[data-testid=\"roster\"]"))

(defn- set-sheet-height-px!
  "Write height on the live element. Used during rAF settle so we do not
  re-render Reagent sixty times a second (that alone made expand feel hard)."
  [el h]
  (when el
    (let [px (str h "px")]
      (set! (.. el -style -height) px)
      (set! (.. el -style -maxHeight) px))))

(defn- clear-sheet-height!
  "Hand geometry back to the snap class."
  [el]
  (when el
    (set! (.. el -style -height) "")
    (set! (.. el -style -maxHeight) "")))

(defn- cancel-settle!
  "Abort an in-flight rAF settle (new drag, unmount).
  `!raf` / `!live-h` are plain atoms so the animation never rides the
  reactive path; live aircraft re-renders re-apply `!live-h` in
  component-did-update instead of wiping it."
  [!drag !raf !live-h]
  (when-let [id @!raf]
    (js/cancelAnimationFrame id)
    (reset! !raf nil))
  (when (or @!live-h (:settling? @!drag))
    (clear-sheet-height! (roster-el))
    (reset! !live-h nil)
    (when (:settling? @!drag)
      (reset! !drag nil))))

(defn- abandon-gesture!
  "End a gesture WITHOUT settling — a tap, or a pointercancel. Drops the
  live height and hands geometry back to the snap class, so nothing is left
  for component-did-update to re-stamp on the next paint."
  [!drag !gesture !live-h]
  (reset! !gesture nil)
  (reset! !live-h nil)
  (clear-sheet-height! (roster-el))
  (reset! !drag nil))

(defn- drag-surface?
  "True when a pointer landed on the drawer's DRAG surface — the shell and
  everything in it except the scrolling body. The rail's padding, the health
  pin and the safe-bottom band are all lip the reader can see and will grab;
  the list, the search field and the rows are not, and must keep scrolling
  and clicking."
  [^js e]
  (let [t (.-target e)]
    (not (and t (.-closest t) (.closest t ".adsb-roster-body")))))

(defn- on-sheet-pointer-down!
  "Start a drag session. Only the primary button / first touch counts, and
  only on the lip — a finger in the list is scrolling it, not dragging.

  NOTHING REACTIVE HAPPENS HERE. Being touched is not a state of the drawer,
  so pointerdown writes only plain atoms and the component does not render at
  all: no body mounts, no class flips, no rule stops matching. The handle the
  reader is pressing looks exactly like the handle they were about to press.
  `!drag` — the one ratom — flips later, on the first move past the slop.

  PHONE ONLY. The snap ladder is the drawer's, and only the phone has a
  drawer: the desktop dock is full height and binary, so dragging it has no
  height to change and no rung to land on. It still taps."
  [!drag !gesture !live-h !raf ^js e]
  (when (and (phone-stance?)
             (drag-surface? e)
             (or (nil? (.-button e)) (zero? (.-button e))))
    (cancel-settle! !drag !raf !live-h)
    (let [y0 (pointer-y e)
          el (.-currentTarget e)                 ; the .adsb-roster shell
          h0 (or (.-offsetHeight el) closed-rail-px)]
      ;; :active? rides the PLAIN atom — a press must not re-render. And no
      ;; live height yet: a press writes no geometry either. The first move
      ;; past the slop sets both (on-sheet-pointer-move!).
      (reset! !gesture {:active?  true
                        :start-y  y0
                        :start-h  h0
                        :last-y   y0
                        :last-t   (js/performance.now)
                        :velocity 0
                        :height   h0})
      (when (.-setPointerCapture el)
        (.setPointerCapture el (.-pointerId e)))
      (.preventDefault e))))

(defn- on-sheet-pointer-move!
  "Track the finger. Height goes straight to the DOM — the same path the
  rAF settle uses — never through a ratom: the sheet being dragged open now
  CONTAINS the roster, and re-rendering every row sixty times a second is
  how a drawer starts to feel like it is dragging the reader back."
  [!drag !gesture !live-h ^js e]
  (when-let [{:keys [active? start-y start-h last-y last-t]} @!gesture]
    (when active?
      (let [y   (pointer-y e)
            t   (js/performance.now)
            dy  (- start-y y)                 ; up = grow the sheet
            vh  (viewport-height)
            h   (max (closed-sheet-min-px) (min vh (+ start-h dy)))
            dt  (max 1 (- t last-t))
            vy  (/ (- last-y y) dt)]          ; positive = opening
        (swap! !gesture assoc
               :last-y y
               :last-t t
               :velocity vy
               :height h)
        ;; The gesture BEGINS the first time the finger clears the slop — not
        ;; at pointerdown, and not for a hand that merely trembles on the
        ;; handle. That crossing flips the one ratom, which is the render that
        ;; mounts the body; everything after it is a plain DOM write.
        (when (and (not (:moved? @!drag))
                   (> (js/Math.abs (- start-y y)) tap-slop-px))
          (swap! !drag assoc :moved? true))
        (when (:moved? @!drag)
          (reset! !live-h h)
          (set-sheet-height-px! (roster-el) h)
          (.preventDefault e))))))

(defn- settle-sheet-after-drag!
  "rAF height settle after a drag release — same path for expand and
  collapse.

  CSS `height` transitions are asymmetric in practice (WebKit especially).
  Driving every frame in pixels with ease-out-cubic makes both directions
  identical.

  Heights live in a plain atom + direct DOM writes. Live traffic still
  re-renders the roster; component-did-update re-stamps `!live-h` so
  those paints cannot snap the sheet back to the class height."
  [!drag !raf !live-h release-h target-sheet]
  (cancel-settle! !drag !raf !live-h)
  (let [from (double (or release-h (sheet-height-px target-sheet)))
        to   (double (sheet-height-px target-sheet))
        dist (js/Math.abs (- to from))
        el   (roster-el)]
    (cond
      (or (prefers-reduced-motion?) (< dist 0.5) (nil? el))
      (do (clear-sheet-height! el)
          (reset! !live-h nil)
          (reset! !drag nil))

      :else
      (let [t0 (js/performance.now)]
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
                        (reset! !raf (js/requestAnimationFrame frame))
                        (do
                          (clear-sheet-height! el)
                          (reset! !live-h nil)
                          (reset! !raf nil)
                          (reset! !drag nil))))))]
          (reset! !raf (js/requestAnimationFrame frame)))))))

(defn- on-sheet-pointer-up!
  "End a drag. A real swipe snaps; a tap leaves the sheet alone so the
  trailing click can cycle. `!suppress-click` blocks the double-fire that
  would otherwise follow a snap."
  [!drag !gesture !live-h !raf !suppress-click sheet ^js e]
  (when (:active? @!gesture)
    (let [{:keys [height velocity]} @!gesture
          ;; The same flag the render keyed off — one definition of "this was
          ;; a drag", so what the reader SAW mid-gesture and what the sheet
          ;; DOES on release can never disagree.
          moved? (boolean (:moved? @!drag))
          vh     (viewport-height)
          frac   (if (and height (pos? vh))
                   (/ height vh)
                   (get sheet-heights sheet 0.52))
          target (height-fraction->sheet frac velocity)]
      (if moved?
        (do (reset! !suppress-click true)
            (rf/dispatch [:roster/set-sheet target])
            (settle-sheet-after-drag! !drag !raf !live-h height target))
        ;; A tap. Hand the geometry back to the snap class — the live height
        ;; must not outlive the gesture that wrote it, or the next paint
        ;; re-stamps it (component-did-update) and the sheet sticks.
        (abandon-gesture! !drag !gesture !live-h))
      (when (.-releasePointerCapture (.-currentTarget e))
        (try
          (.releasePointerCapture (.-currentTarget e) (.-pointerId e))
          (catch :default _ nil))))))

(defn- on-sheet-click!
  "Tap without a meaningful drag. Phone cycles closed → half → full →
  closed; desktop binary-toggles (half ↔ closed). Suppressed when
  pointerup already committed a snap for this gesture.

  Bound to the shell, so it catches taps anywhere on the lip AND the
  handle button's own click (which bubbles here, keyboard Enter included —
  the button keeps the role and the label, it just no longer keeps the
  listeners). Row and search clicks come from the body and are not taps on
  the drawer."
  [!suppress-click ^js e]
  (when (drag-surface? e)
    (if @!suppress-click
      (reset! !suppress-click false)
      (rf/dispatch (if (phone-stance?) [:roster/cycle] [:roster/toggle])))))

;; ---------------------------------------------------------------------
;; The surface

(defn- empty-message
  [q]
  (if (str/blank? q)
    "No aircraft in the picture"
    "No match — try another callsign or ICAO"))

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
     ;; THE LABEL IS NOT DELETED, IT IS MOVED (adsb-33i's bargain, and the same
     ;; one the feeder chip strikes at :ok — adsb.ui.health). The magnifier and
     ;; the box make the field self-evident TO THE EYE, so the FIND stamp is
     ;; noise on screen and it goes. It cannot go from the accessibility tree:
     ;; the icon is aria-hidden decoration and cannot name anything, and a
     ;; placeholder is not a name — it is a hint, and it evaporates on the first
     ;; keystroke. Drop the element and the field announces as "search, blank".
     ;; So `adsb-vh` — off the screen, still spoken.
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
        :on-change     #(rf/dispatch [:roster/set-query (.. % -target -value)])}]]]
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

  THE DRAWER IS NOT EMPTY WHILE IT OPENS. The body used to be gated on the
  COMMITTED sheet state, which does not change until pointerup — so a finger
  pulling the drawer up hauled a blank panel behind it and the roster only
  appeared, all at once, on release. It reads as a drawer that is still
  loading, and it hides the very thing that tells you the pull is worth
  finishing. The gate is now `body?*`: on screen from the first pixel of the
  drag, through the settle, and gone only when the sheet is truly shut.

  What made that affordable is the split between the two halves of the drag
  state. `!drag` is a ratom holding a PHASE (:active? / :settling?), so it
  re-renders twice per gesture. The height moves through the plain `!gesture`
  / `!live-h` atoms straight to the DOM, so uncovering two hundred rows costs
  nothing per frame. Live SSE paints still land mid-gesture; did-update
  re-stamps `!live-h` so they cannot snap the sheet back to its class height.

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
        ;; Reactive: the gesture's PHASE, and nothing that moves with the
        ;; finger. Two renders a gesture, not sixty.
        !drag           (r/atom nil)
        ;; Plain atoms — the moving parts. rAF and pointermove write the DOM
        ;; directly; live picture updates re-stamp !live-h in did-update.
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
                         ;; after-render: the row must exist in the DOM
                         ;; before we ask the browser to scroll to it.
                         (r/after-render #(scroll-row-into-view! icao)))
                       (reset! !prev-selected icao))))))
       :component-did-update
       (fn [_]
         ;; Picture/SSE re-renders wipe inline styles; re-apply the live
         ;; drag/settle height so the sheet does not jump back to the snap
         ;; class under the finger.
         (when-let [h @!live-h]
           (set-sheet-height-px! (roster-el) h)))
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
               ;; MOVING, not merely touched. A press is not a gesture: it
               ;; must mount nothing and restyle nothing, or the handle
               ;; twitches under the finger every time the reader taps it.
               moving? (boolean (or (:moved? drag) (:settling? drag)))
               ;; The body is on screen whenever the drawer has room for it:
               ;; committed open, OR travelling. A sheet being dragged up
               ;; from closed shows the roster it is uncovering; a sheet
               ;; settling shut keeps it until the rail arrives.
               body?*  (or open?* moving?)
               ;; Declare the live height here too, not only via the DOM
               ;; writes: a re-render (SSE picture) would otherwise clear the
               ;; inline style for a frame before did-update re-stamps it —
               ;; that one-frame flash was the hard expand snap.
               style   (when moving?
                         (when-let [h @!live-h]
                           {:height (str h "px") :max-height (str h "px")}))]
           ;; Pointer listeners ride the SHELL, not the handle button: the
           ;; whole lip — rail padding, health pin, safe-bottom band — is
           ;; one grab target, and the body opts out (drag-surface?).
           [:div.adsb-roster
            (cond-> {:data-testid "roster"
                     ;; data-open is the COMMITTED state — what the sheet will
                     ;; be when the finger lifts. `is-open` is what is on
                     ;; SCREEN, gesture included, and it is what the stylesheet
                     ;; keys the body's geometry off. They differ exactly while
                     ;; a drag is in flight, which is the point.
                     :data-open   (if open?* "true" "false")
                     :data-sheet  (name sheet*)
                     :class       [(when body?* "is-open")
                                   (when (:moved? drag) "is-dragging")
                                   (when (:settling? drag) "is-settling")
                                   (str "is-sheet-" (name sheet*))]
                     :on-pointer-down #(on-sheet-pointer-down! !drag !gesture !live-h !raf %)
                     :on-pointer-move #(on-sheet-pointer-move! !drag !gesture !live-h %)
                     :on-pointer-up   #(on-sheet-pointer-up! !drag !gesture !live-h !raf !suppress-click sheet* %)
                     :on-pointer-cancel #(when (:active? @!gesture)
                                           (abandon-gesture! !drag !gesture !live-h))
                     :on-click        #(on-sheet-click! !suppress-click %)}
              style (assoc :style style))
            [:div.adsb-roster-rail
             ;; The button is the AFFORDANCE — role, label, focus ring,
             ;; keyboard. Its click bubbles to the shell handler above.
             [:button.adsb-roster-handle
              {:type          "button"
               :aria-expanded (boolean open?*)
               :aria-label    (handle-aria-label sheet*)
               :data-testid   "roster-toggle"
               :data-sheet    (name sheet*)}
              [:span.adsb-roster-handle-bar {:aria-hidden true}]
              [:span.adsb-roster-handle-label
               (handle-label sheet* n total* q)]]
             [health/health]]
            (when body?*
              [roster-body q rows* sel* @hovered])]))})))
