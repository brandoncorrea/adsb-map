(ns adsb.ui.stack
  "The Stack — the live flight-level ruler on the map's edge, and the
  aircraft list's replacement (docs/design-direction.md §9; the Overseer
  rejected every sidebar as 'a table in a costume'). A profile view of the
  sky beside the plan view of it, doing three jobs at once:

    * ALTITUDE LEGEND — its fill IS the altitude gradient, built from the
      very same adsb.map.style/altitude-stops the map paints with, so the
      ruler and the planes can never disagree about what a colour means.
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
  to `bottom` (vertical) or `left` (horizontal)."
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
;; Interaction — three delegated handlers on the whole Stack, so N ticks
;; cost three handlers, not 3N closures rebuilt every second. Each walks
;; up from the event target to the nearest tick's data-icao.

(defn- event-icao
  "The icao of the tick an event landed on, or nil off-tick."
  [event]
  (some-> (.-target event)
          (.closest "[data-icao]")
          (.getAttribute "data-icao")))

(defn- on-stack-click!
  "Click a tick -> the map's existing [:aircraft/select icao] contract,
  the same event a plane click fires."
  [event]
  (when-let [icao (event-icao event)]
    (rf/dispatch [:aircraft/select icao])))

(defn- on-stack-over!
  "Pointer onto a tick -> publish the hover identity app-wide."
  [event]
  (when-let [icao (event-icao event)]
    (rf/dispatch [:aircraft/hover icao])))

(defn- on-stack-out!
  "Pointer off a tick -> clear the hover. Guarded so leaving non-tick
  parts of the Stack dispatches nothing."
  [event]
  (when (event-icao event)
    (rf/dispatch [:aircraft/clear-hover])))

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
  label beside it — while hovered, while selected, and always while the
  aircraft is squawking distress."
  [aircraft* selected-icao hovered-icao]
  (let [{:aircraft/keys [icao altitude-ft]} aircraft*
        band       (tick-band aircraft*)
        emergency? (aircraft/emergency? aircraft*)
        selected?  (= icao selected-icao)
        named?     (or emergency? selected? (= icao hovered-icao))]
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

(defn- shelf
  "A holding band at the ruler's foot — the ground cluster or the
  altitude-unknown area — with its resident ticks clustered inside."
  [{:keys [class label aircraft selected-icao hovered-icao]}]
  [:div.adsb-stack-shelf {:class class :role "group" :aria-label label}
   [:span.adsb-stack-shelf-label {:aria-hidden true} label]
   (for [a aircraft]
     ^{:key (:aircraft/icao a)} [tick a selected-icao hovered-icao])])

(defn stack
  "The Stack, mounted permanently on the map's edge. A form-2 component:
  subscribe once, deref per render. The whole surface is one listbox —
  it is the aircraft list, after all — with the ruler and the two foot
  shelves inside it. Derefs the current edition (adsb.map.theme/!theme)
  so the fill re-prints when the system scheme flips."
  []
  (let [roster   (rf/subscribe [:aircraft/stack])
        selected (rf/subscribe [:aircraft/selected-icao])
        hovered  (rf/subscribe [:aircraft/hovered-icao])]
    (fn []
      (let [{:keys [airborne ground unknown]} (group-by tick-band @roster)
            selected-icao @selected
            hovered-icao  @hovered]
        [:aside.adsb-stack
         {:role          "listbox"
          :aria-label    "Aircraft by altitude"
          :on-click      on-stack-click!
          :on-mouse-over on-stack-over!
          :on-mouse-out  on-stack-out!}
         [:div.adsb-stack-ruler {:style {:background (ruler-background @theme/!theme)}}
          (for [g graduations]
            ^{:key (first g)} [graduation g])
          (for [a airborne]
            ^{:key (:aircraft/icao a)} [tick a selected-icao hovered-icao])]
         [shelf {:class         "adsb-stack-ground"
                 :label         "GND"
                 :aircraft      ground
                 :selected-icao selected-icao
                 :hovered-icao  hovered-icao}]
         [shelf {:class         "adsb-stack-unknown"
                 :label         "NO ALT"
                 :aircraft      unknown
                 :selected-icao selected-icao
                 :hovered-icao  hovered-icao}]]))))
