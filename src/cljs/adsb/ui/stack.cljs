(ns adsb.ui.stack
  "The Stack — the live flight-level ruler on the map's edge, and the
  aircraft list's replacement (docs/design-direction.md §9; the Overseer
  rejected every sidebar as 'a table in a costume'). A profile view of the
  sky beside the plan view of it, doing three jobs at once:

    * THE MAP KEY, ENTIRE (adsb-sod) — its fill IS the altitude gradient,
      built from the very same adsb.map.style/altitude-stops the map paints
      with, so the ruler and the planes can never disagree about what a colour
      means. The corner legend that used to say this a second time, in five
      discrete swatches, is gone: a continuous ramp with flight levels ruled
      across it is not a worse legend than a table of five colours, it is a
      better one, and it was already on the chart.

      What the ramp could NOT key are the states that are not altitudes, and
      those the shelves now carry as swatches beside their counts — `● GND 3`,
      `● NO ALT 31`, and `● EMG 1` when, and only when, an aircraft is
      squawking. Every swatch is painted from `style/palette`, never from a CSS
      token mirroring it: that was the deleted legend's one real virtue and it
      is kept exactly. The staleness fade keys itself — a plane that is fading
      is a plane going quiet, which is the only thing the fade ever meant.
    * ALTITUDE SCALE — flight-level graduations from the surface to the
      ceiling (~FL450).
    * AIRCRAFT LIST — every positioned-or-altitude-bearing aircraft is a
      tick at its TRUE altitude, drifting as it climbs or descends.
      Approach stacking and departures read as motion, not re-sorting.

  THREE BANDS, BECAUSE ABSENT IS NOT ZERO. An airborne aircraft sits on
  the ruler at its proportional height. An on-ground aircraft clusters in
  a shelf at the ruler's foot — the surface band. An aircraft that never
  reported an altitude gets its own small holding shelf below that: it is
  placed NOWHERE on the scale, because a missing altitude is not sea
  level. An aircraft with neither a position nor any altitude fact has
  nothing this ruler can say about it and casts no tick.

  INTERACTION. Hover a tick and its aircraft is published as the app-wide
  :aircraft/hovered-icao (the map layer lights it in a later wave — see
  the bead note); the tick itself names its aircraft. Click a tick and it
  fires the map's existing [:aircraft/select icao] contract — the same
  event a plane click fires. The selected tick is marked and named; an
  emergency tick is red, hatched, and permanently named — ink that never
  blinks (§7).

  ONE SOURCE OF TRUTH. The ticks derive from the same :aircraft/picture
  the map reads, never a second copy of the sky.

  BOUNDARY 4 (docs/validation-boundaries.md). Every callsign and icao
  arrived off unauthenticated radio: well-TYPED, not trustWORTHY. Each is
  rendered as plain hiccup text and escaped by Reagent.

  RENDER COST. This is chrome, so it IS Reagent — but it re-renders at
  feeder cadence (1 Hz) with a node per aircraft. That is hundreds of
  cheap hiccup nodes per SECOND, not per frame, and each tick is a single
  div positioned by one CSS custom property; with the fixture cast this
  measures as noise. If a very busy sky ever makes it heavy, memoize the
  per-aircraft tick on its inputs — noted, not needed yet.

  ORIENTATION. Desktop: vertical, right edge. Phone: the ruler lies down
  along the bottom edge with identical semantics (§9 / Q9c). One DOM
  serves both — each tick carries its place on the ALTITUDE AXIS as the
  unitless --alt-pct custom property, and the stylesheet maps that axis
  to `bottom` (vertical) or `left` (horizontal).

  THE SHELVES ARE COUNTED, NOT DRAWN, WHEN THE AXIS IS SCARCE (adsb-hsk).
  A field of identical dots communicates exactly one fact — HOW MANY — and
  on a phone it charged 200px for it, starving the recumbent ruler to two
  pixels. So each shelf carries a chip: its label and its count. The phone
  hides the dots and keeps the chip; the desktop, which has the room, keeps
  both. Tapping a chip opens a sheet that NAMES its residents — the one
  thing a dot cluster could never do. None of this moves them onto the
  scale: absent is still not zero.

  TOUCH (adsb-4et). Naming a tick was a HOVER, and phones do not hover; a
  3px tick is also far under the 44px touch target. Press the ruler and
  drag: `axis-pct` reads the pointer's place on the altitude axis, and the
  nearest tick is named and lit as your finger passes it — a crosshair you
  read THROUGH, not a target you aim AT. Release selects it. This invents
  no new state: the scrub borrows the hover and select channels that were
  already here. The stance comes from the ruler's own geometry — a rect
  wider than it is tall is lying down — so one gesture serves both."
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.map.style :as style]
    [adsb.map.theme :as theme]
    [clojure.string :as str]
    [re-frame.core :as rf]))

;; ---------------------------------------------------------------------
;; Tick geometry — pure. The layout math the browser tests assert directly.

(def ^:const ceiling-ft
  "The ruler's top — FL450, comfortably above every altitude this sky
  reports. An aircraft above it clamps to the top rather than leaving
  the ruler."
  45000)

(defn altitude-pct
  "Where `feet` sits on the altitude axis: 0 at the surface, 100 at
  `ceiling-ft`, clamped to that range. Pure — the tick, the graduations,
  and the gradient stops all place themselves with this one function, so
  a tick at FL300 is exactly level with the FL300 rule and exactly on the
  gradient's FL300 colour."
  [feet]
  (-> feet (/ ceiling-ft) (* 100) (max 0) (min 100)))

(defn tick-band
  "Which band of the Stack an aircraft's tick lives in: :ground (the
  surface shelf), :airborne (on the ruler at its true altitude), or
  :unknown (the holding shelf — an altitude the sky never reported is
  never drawn as zero)."
  [{:aircraft/keys [on-ground? altitude-ft]}]
  (cond
    on-ground?          :ground
    (some? altitude-ft) :airborne
    :else               :unknown))

(defn stack-member?
  "True when the Stack can say something about this aircraft: it is
  positioned, or it carries any altitude fact (a number, or on-ground).
  A target with neither casts no tick — there is nothing to place."
  [aircraft*]
  (or (aircraft/positioned? aircraft*)
      (some? (:aircraft/altitude-ft aircraft*))
      (boolean (:aircraft/on-ground? aircraft*))))

;; ---------------------------------------------------------------------
;; The scrub — pure. Where a finger is on the altitude axis, and which
;; aircraft is nearest it. The handlers below are a thin shell over these
;; two functions; everything that can be reasoned about lives here.

(defn axis-pct
  "Where a pointer at (`x`, `y`) falls on the ruler's ALTITUDE axis: 0 at
  the surface, 100 at the ceiling, clamped. `rect` is the ruler's bounding
  box, and it is also what tells us the stance — a ruler wider than it is
  tall is lying down, and its axis runs left-to-right; a standing ruler's
  axis runs bottom-to-top. Geometry, not a second copy of the media query.

  A rect with no area has no axis, and yields nil rather than dividing by
  zero: an unstyled Stack (the browser suite renders without the
  stylesheet — adsb-giu) must not scrub."
  [{:keys [left top width height]} x y]
  (when (and (pos? width) (pos? height))
    (-> (if (> width height)
          (* 100 (/ (- x left) width))
          (* 100 (/ (- (+ top height) y) height)))
        (max 0)
        (min 100))))

(defn nearest-tick
  "The aircraft in `airborne` whose tick sits closest to `pct` on the
  altitude axis — what the finger is pointing at. Nil when nothing is up
  there to point at."
  [airborne pct]
  (when (seq airborne)
    (apply min-key
           #(abs (- (altitude-pct (:aircraft/altitude-ft %)) pct))
           airborne)))

(defn airborne
  "The ruler's residents — the aircraft in `picture` that the scrub can
  land on. The shelves are not on the axis, so they cannot be scrubbed to."
  [picture]
  (filter #(= :airborne (tick-band %)) (vals picture)))

;; ---------------------------------------------------------------------
;; The fill — the ruler IS the legend. Built from adsb.map.style's
;; per-edition altitude stops, never a duplicated palette, so re-skinning
;; the map re-skins the ruler in the same edit, in both editions.

(defn ruler-gradient-css
  "The ruler's fill as a CSS linear-gradient: every [feet colour] stop of
  `theme`'s adsb.map.style altitude ramp placed at its `altitude-pct`
  position on the axis. The gradient's direction is the --stack-axis
  custom property (default `to top`), which the phone stylesheet flips to
  `to right` when the ruler lies down — same stops, same scale, rotated
  geometry."
  [theme]
  (str "linear-gradient(var(--stack-axis, to top), "
       (str/join ", " (for [[feet color] (:altitude-stops (style/palette theme))]
                        (str color " " (altitude-pct feet) "%")))
       ")"))

(def ^:private ruler-backgrounds
  ;; Each edition's fill, computed once — the gradient changes only when
  ;; the edition flips, and a string rebuilt per 1 Hz render would be
  ;; allocation for nothing.
  (into {} (for [theme (keys style/palettes)]
             [theme (ruler-gradient-css theme)])))

(defn- ruler-background
  "The current edition's fill; an unrecognized theme reads the day
  edition, mirroring style/palette."
  [theme]
  (get ruler-backgrounds theme (:day ruler-backgrounds)))

(def graduations
  "The flight-level rules drawn on the ruler: the surface, then every
  hundred flight levels to the ceiling."
  [[0     "SFC"]
   [10000 "FL100"]
   [20000 "FL200"]
   [30000 "FL300"]
   [40000 "FL400"]])

;; ---------------------------------------------------------------------
;; The roster the ruler draws — the same picture the map reads, filtered
;; to what the Stack can place, ordered stably by icao (a tick's position
;; comes from its altitude, not its DOM order; the stable order only
;; keeps React reconciliation and the shelves from reshuffling).

(rf/reg-sub
  :aircraft/stack
  :<- [:aircraft/picture]
  (fn [picture _]
    (->> (vals picture)
         (filter stack-member?)
         (sort-by :aircraft/icao))))

;; ---------------------------------------------------------------------
;; The Stack's own view state: which shelf, if any, is standing open and
;; naming its residents. It is a property of the VIEW, not of the sky, so
;; it lives here beside the surface that owns it — the aircraft events
;; (adsb.events) stay the app's, and the scrub borrows them rather than
;; writing selection or hover behind their backs.

(rf/reg-event-db
  :stack/toggle-shelf
  (fn [db [_ band]]
    (if (= band (:stack/open-shelf db))
      (dissoc db :stack/open-shelf)
      (assoc db :stack/open-shelf band))))

(rf/reg-sub
  :stack/open-shelf
  (fn [db _]
    (:stack/open-shelf db)))

;; The scrub, in terms of the channels that already exist: passing a tick
;; hovers it (which is what names it, and what the map will light), and
;; letting go selects whatever the finger left named.

(rf/reg-event-fx
  :stack/scrub
  (fn [{:keys [db]} [_ pct]]
    (when-let [target (nearest-tick (airborne (:aircraft/picture db)) pct)]
      {:fx [[:dispatch [:aircraft/hover (:aircraft/icao target)]]]})))

(rf/reg-event-fx
  :stack/scrub-end
  (fn [{:keys [db]} _]
    (when-let [icao (:aircraft/hovered-icao db)]
      {:fx [[:dispatch [:aircraft/select icao]]]})))

;; ---------------------------------------------------------------------
;; Interaction — delegated handlers on the whole Stack, so N ticks cost a
;; handful of handlers, not 3N closures rebuilt every second. Each walks up
;; from the event target to the nearest tick's data-icao or shelf chip.

(defn- event-icao
  "The icao of the tick an event landed on, or nil off-tick."
  [event]
  (some-> (.-target event)
          (.closest "[data-icao]")
          (.getAttribute "data-icao")))

(defn- event-shelf
  "The band of the shelf chip an event landed on, or nil off-chip."
  [event]
  (some-> (.-target event)
          (.closest "[data-shelf]")
          (.getAttribute "data-shelf")
          keyword))

(defn- on-stack-click!
  "Click a tick -> the map's existing [:aircraft/select icao] contract, the
  same event a plane click fires. Click a shelf chip -> open or close that
  shelf's sheet of names."
  [event]
  (if-let [icao (event-icao event)]
    (rf/dispatch [:aircraft/select icao])
    (when-let [band (event-shelf event)]
      (rf/dispatch [:stack/toggle-shelf band]))))

;; A scrub in progress. Transient pointer state, not app state: nothing
;; outside this gesture can see it, and app-db is not where a half-finished
;; finger-drag belongs.
(defonce ^:private !scrubbing? (atom false))

(defn- on-stack-over!
  "Pointer onto a tick -> publish the hover identity app-wide."
  [event]
  (when-let [icao (event-icao event)]
    (rf/dispatch [:aircraft/hover icao])))

(defn- on-stack-out!
  "Pointer off a tick -> clear the hover. Guarded so leaving non-tick parts
  of the Stack dispatches nothing — and silent mid-scrub, where crossing the
  gap between two ticks would otherwise clear the very hover the scrub is
  painting, one flicker per tick passed."
  [event]
  (when (and (not @!scrubbing?) (event-icao event))
    (rf/dispatch [:aircraft/clear-hover])))

(defn- ruler-rect
  "The ruler's bounding box as plain numbers — the shape `axis-pct` reads."
  [element]
  (let [rect (.getBoundingClientRect element)]
    {:left   (.-left rect)
     :top    (.-top rect)
     :width  (.-width rect)
     :height (.-height rect)}))

(defn- scrub!
  "Name and light whichever tick the pointer is nearest. The ruler is the
  handler's currentTarget, so a scrub that begins on a tick and one that
  begins on bare gradient are the same gesture."
  [event]
  (when-let [pct (axis-pct (ruler-rect (.-currentTarget event))
                           (.-clientX event)
                           (.-clientY event))]
    (rf/dispatch [:stack/scrub pct])))

(defn- on-ruler-down!
  "Finger (or mouse button) down on the ruler — the scrub begins, and lands
  on a tick immediately: a tap IS a one-frame scrub, which is what gives a
  3px tick a forgiving target without fattening the ink."
  [event]
  (reset! !scrubbing? true)
  (scrub! event))

(defn- on-ruler-move!
  "Dragging along the ruler names each tick as the pointer passes it. Only
  while pressed — an idle mouse over the desktop ruler must not hijack the
  hover it already has."
  [event]
  (when @!scrubbing?
    (scrub! event)))

(defn- on-ruler-up!
  "Letting go selects whatever the scrub left named — the same
  [:aircraft/select icao] contract a click fires."
  [_event]
  (when @!scrubbing?
    (reset! !scrubbing? false)
    (rf/dispatch [:stack/scrub-end])))

(defn- on-ruler-cancel!
  "The gesture was taken away from us (a system swipe, a call). Nothing was
  chosen — end the scrub without selecting."
  [_event]
  (reset! !scrubbing? false))

;; ---------------------------------------------------------------------
;; Components — kebab-case functions returning hiccup.

(defn- tick-name
  "The identity a tick shows when named: the callsign when the sky gave
  one, otherwise the bare icao. This is where identity lives — the map
  surface stays glyphs-only (Q7a)."
  [{:aircraft/keys [callsign icao]}]
  (or callsign icao))

(defn- graduation
  "One flight-level rule across the ruler, placed on the altitude axis."
  [[feet label]]
  [:div.adsb-stack-grad {:style {"--alt-pct" (str (altitude-pct feet))}}
   [:span.adsb-stack-grad-label label]])

(defn- tick
  "One aircraft's tick. Airborne ticks carry their place on the altitude
  axis as --alt-pct (the stylesheet turns that into `bottom` or `left`
  and TRANSITIONS it, so a climbing aircraft's tick drifts rather than
  jumps); shelf ticks cluster, unplaced. The tick is named — a small
  label beside it — while hovered, while selected, always while the
  aircraft is squawking distress, and always inside an open sheet, which
  exists precisely to name its residents (`always-named?`)."
  [aircraft* {:keys [selected-icao hovered-icao always-named?]}]
  (let [{:aircraft/keys [icao altitude-ft]} aircraft*
        band       (tick-band aircraft*)
        emergency? (aircraft/emergency? aircraft*)
        selected?  (= icao selected-icao)
        named?     (or always-named? emergency? selected? (= icao hovered-icao))]
    [:div.adsb-stack-tick
     (cond-> {:role          "option"
              :data-icao     icao
              :data-testid   (str "tick:" icao)
              :aria-selected selected?
              :aria-label    (tick-name aircraft*)
              :tab-index     0
              :class         [(when selected? "adsb-stack-tick-selected")
                              (when emergency? "adsb-stack-tick-emergency")]}
       (= band :airborne)
       (assoc :style {"--alt-pct" (str (altitude-pct altitude-ft))}))
     (when named?
       [:span.adsb-stack-tick-label (tick-name aircraft*)])]))

(defn- swatch
  "A colour chip, painted from the CURRENT EDITION'S adsb.map.style palette and
  carrying that colour verbatim on `data-color`.

  This is the map key, and it is the whole of what the corner legend used to be
  for the off-scale states (adsb-sod). The legend's one real virtue was never
  its box — it was that it read `style/palette` directly, so a re-skin moved the
  key and the planes together and they could not drift apart. That virtue is
  kept here, exactly: these swatches are the palette, not a CSS token mirroring
  it, and the test asserts they equal it in BOTH editions."
  [color testid]
  [:span.adsb-stack-shelf-swatch
   {:data-testid testid
    :data-color  color
    :style       {:background-color color}
    :aria-hidden true}])

(defn- shelf
  "A holding band at the ruler's foot — the ground cluster or the
  altitude-unknown area. Its residents are shown one of two ways, and never
  both at once (two DOM nodes for one aircraft would be two answers to
  `who is this?`):

    * CLOSED — the dot cluster. Grouped, never ranked. The phone hides the
      dots and keeps only the chip, which is the whole point: the cluster's
      one fact is its count, and on the short axis the count is cheaper.
    * OPEN — the sheet, where every resident is NAMED and selectable. What
      a chip's number cannot tell you, and neither could the dots.

  The chip leads with the state's SWATCH, so `● GND 3` is a legend row and a
  count in the same breath. The swatch matters most in the two places the dots
  cannot help: on a phone, where they are hidden, and at a count of zero, where
  there are no dots to take the colour from."
  [{:keys [band class label color aircraft open? selected-icao hovered-icao]}]
  (let [tick-opts {:selected-icao selected-icao :hovered-icao hovered-icao}]
    [:div.adsb-stack-shelf {:class class :role "group" :aria-label label}
     [:button.adsb-stack-shelf-chip
      {:type          "button"
       :data-shelf    (name band)
       :data-testid   (str "shelf:" (name band))
       :aria-expanded (boolean open?)}
      [swatch color (str "swatch:" (name band))]
      [:span.adsb-stack-shelf-label label]
      [:span.adsb-stack-shelf-count (count aircraft)]]
     (if open?
       [:div.adsb-stack-sheet
        (for [a aircraft]
          ^{:key (:aircraft/icao a)}
          [tick a (assoc tick-opts :always-named? true)])]
       (for [a aircraft]
         ^{:key (:aircraft/icao a)} [tick a tick-opts]))]))

(defn- emergency-caption
  "`● EMG 1` — the key for red, and the count of it.

  It renders ONLY while an aircraft is squawking distress, which is the point:
  a legend row explaining a colour that is nowhere on the chart is a row that
  spends the reader's attention on nothing. This one cannot be stale and cannot
  be idle — it is on screen exactly when red is on screen.

  It is not a shelf and not a button. A distressed aircraft is airborne and
  already has its tick on the ruler (red, hatched, permanently named — §7); it
  is named again in the NOTAM ribbon, which is where you go to act on it. A
  second list of the same aircraft would be a second answer to a question the
  chart has already answered twice."
  [aircraft color]
  (when (seq aircraft)
    [:div.adsb-stack-shelf.adsb-stack-emergency
     {:role "status" :aria-label (str (count aircraft) " squawking distress")}
     [:span.adsb-stack-shelf-caption {:data-testid "shelf:emergency"}
      [swatch color "swatch:emergency"]
      [:span.adsb-stack-shelf-label "EMG"]
      [:span.adsb-stack-shelf-count (count aircraft)]]]))

(defn stack
  "The Stack, mounted permanently on the map's edge. A form-2 component:
  subscribe once, deref per render. The whole surface is one listbox —
  it is the aircraft list, after all — with the ruler and the two foot
  shelves inside it. Derefs the current edition (adsb.map.theme/!theme)
  so the fill re-prints when the system scheme flips."
  []
  (let [roster      (rf/subscribe [:aircraft/stack])
        selected    (rf/subscribe [:aircraft/selected-icao])
        hovered     (rf/subscribe [:aircraft/hovered-icao])
        open        (rf/subscribe [:stack/open-shelf])
        emergencies (rf/subscribe [:aircraft/emergencies])]
    (fn []
      (let [theme         @theme/!theme
            palette       (style/palette theme)
            bands         (group-by tick-band @roster)
            selected-icao @selected
            hovered-icao  @hovered
            open-shelf    @open
            tick-opts     {:selected-icao selected-icao :hovered-icao hovered-icao}]
        [:aside.adsb-stack
         {:role          "listbox"
          :aria-label    "Aircraft by altitude"
          :on-click      on-stack-click!
          :on-mouse-over on-stack-over!
          :on-mouse-out  on-stack-out!}
         [:div.adsb-stack-ruler
          {:style             {:background (ruler-background theme)}
           :on-pointer-down   on-ruler-down!
           :on-pointer-move   on-ruler-move!
           :on-pointer-up     on-ruler-up!
           :on-pointer-cancel on-ruler-cancel!}
          (for [g graduations]
            ^{:key (first g)} [graduation g])
          (for [a (:airborne bands)]
            ^{:key (:aircraft/icao a)} [tick a tick-opts])]
         [shelf {:band          :ground
                 :class         "adsb-stack-ground"
                 :label         "GND"
                 :color         (:ground-color palette)
                 :aircraft      (:ground bands)
                 :open?         (= :ground open-shelf)
                 :selected-icao selected-icao
                 :hovered-icao  hovered-icao}]
         [shelf {:band          :unknown
                 :class         "adsb-stack-unknown"
                 :label         "NO ALT"
                 :color         (:unknown-color palette)
                 :aircraft      (:unknown bands)
                 :open?         (= :unknown open-shelf)
                 :selected-icao selected-icao
                 :hovered-icao  hovered-icao}]
         [emergency-caption @emergencies (:emergency-color palette)]]))))
