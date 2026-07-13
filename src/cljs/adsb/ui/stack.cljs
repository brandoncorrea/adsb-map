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
      those the shelves carry as swatches beside their counts — `● GND 3`,
      `● NO ALT 31`, `● EMG 0`. Every swatch is painted from `style/palette`,
      never from a CSS token mirroring it: that was the deleted legend's one
      real virtue and it is kept exactly. The staleness fade keys itself — a
      plane that is fading is a plane going quiet, which is the only thing the
      fade ever meant.

    * THE THREE COUNTS ARE ONE REGISTER. GND, NO ALT and EMG all answer the
      same question — how many aircraft are in this state — so all three are
      permanent, and a zero is a reading, not an absence. They are NOT the
      header's stream and feeder signals, which report on the apparatus and
      stay silent while it is healthy; these report on the SKY, and the app
      earns no credit for a calm one. What is conditional is not the count but
      the INK: red arrives with the aircraft that deserve it (see
      `emergency-shelf`). A chip with no residents is also not a button — it
      states its fact and offers no door to nowhere.
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
    [adsb.ui.health :as health]
    [clojure.string :as str]
    [re-frame.core :as rf]
    [reagent.core :as r]))

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
;; THE RULER IS A SCROLL CONTAINER, AND THE BROWSER OWNS THE PANNING.
;;
;; 43 aircraft on 372px is a 6px median gap against 3px of ink: more than half the
;; ticks merge into a neighbour. The scale was never the problem — the RANGE was.
;; So the ruler holds a TRACK as long as the zoom demands, and shows a window onto
;; it through a native scroll viewport.
;;
;; Everything that a hand-rolled pan had to invent, the platform already has, and
;; has better: momentum with the right deceleration curve, catch-to-stop,
;; rubber-band at the ends, and a pan that runs on the COMPOSITOR — no re-frame
;; event per frame, no React render, no relayout of fifty ticks. A tick's position
;; inside the track never changes when you scroll, so there is nothing to animate
;; and nothing to hold still.
;;
;; It also settles the gesture question without a rule, because a scroll container
;; only scrolls WHEN THERE IS OVERFLOW:
;;
;;   ZOOM 1  — the track is exactly the viewport. Nothing to scroll, so a drag is
;;             free, and it SCRUBS: the gesture that made a 3px tick readable.
;;   ZOOMED  — there is overflow, so the browser takes the drag and pans. Our
;;             scrub gets a pointercancel, which is exactly right: scroll wins.
;;
;; What stays ours is the part the platform never offers on a scroll container:
;; PINCH and WHEEL zoom. Those change the track's LENGTH and then correct the
;; scroll offset so the sky under the fingers stays under the fingers.
;;
;; THE SCROLL POSITION IS THE TRUTH. Not app-db. The window in app-db is a
;; SNAPSHOT of what the viewport is showing, pushed there by the scroll handler,
;; because two things must render from it: the overflow counts and the graduation
;; step. That is an inversion worth naming — the ruler's render is no longer a
;; pure function of app-db — and it is the right one: a scroll offset is the
;; browser's business in the same way a caret position is.

(def full-window
  "The whole sky — the range the ruler shows at zoom 1, and what a pinch-out
  returns to. At this range the track is exactly the viewport, the fill is the
  COMPLETE altitude ramp, and the ruler is the map's key again."
  {:min-ft 0 :max-ft ceiling-ft})

(def ^:const max-zoom
  "The tightest the ruler may close: 500ft of sky across the whole viewport, far
  past the point where two aircraft could still share a pixel."
  90)

(defn clamp-zoom [zoom]
  (-> zoom (max 1) (min max-zoom)))

(defn window-pct
  "Where `feet` sits on a given window: 0 at its floor, 100 at its ceiling.
  Unclamped — a caller needs to know a tick has left the window, not be handed a
  lie about it sitting on the edge."
  [{:keys [min-ft max-ft]} feet]
  (* 100 (/ (- feet min-ft) (- max-ft min-ft))))

(defn in-window?
  "True when `feet` is inside the window."
  [{:keys [min-ft max-ft]} feet]
  (and (some? feet) (<= min-ft feet max-ft)))

(defn scroll->window
  "The slice of sky currently in view, read from the scroll container's own
  numbers — `scroll` px along the axis, a `viewport` that long, on a `track` that
  long. THIS is where the window comes from now; nothing else may invent one.

  The vertical ruler reads UPWARD (altitude climbs to the top) while scrollTop
  counts DOWNWARD from the track's head, so its axis is inverted. Getting that
  backwards would put the overflow counts on the wrong ends — the one thing they
  exist to get right."
  [{:keys [scroll viewport track horizontal?]}]
  (if (or (nil? track) (not (pos? track)) (not (pos? viewport)))
    full-window
    (let [a (/ scroll track)
          b (/ (+ scroll viewport) track)]
      (if horizontal?
        {:min-ft (* ceiling-ft a)       :max-ft (* ceiling-ft (min 1 b))}
        {:min-ft (* ceiling-ft (- 1 (min 1 b))) :max-ft (* ceiling-ft (- 1 a))}))))

(defn track-pct
  "Where a pointer sits on the FULL altitude scale — 0 at the surface, 100 at the
  ceiling — given how far the track is scrolled beneath it. The scrub thinks in
  the whole sky, not in the window: a tick is at its altitude whether the reader
  has scrolled it into view or not."
  [{:keys [scroll viewport track horizontal?]} offset]
  (when (and (pos? track) (pos? viewport))
    (let [p (/ (+ scroll offset) track)]
      (-> (* 100 (if horizontal? p (- 1 p)))
          (max 0)
          (min 100)))))

(defn zoom-scroll
  "The scroll offset that keeps the sky under the fingers UNDER THE FINGERS while
  the track grows from `track` to `track'`. That is the whole feel of a timeline
  zoom: the thing you are pointing at does not move.

  Clamped to the track: a scroll offset past either end would ask the browser for
  sky that is not there."
  [{:keys [scroll viewport track]} track' offset]
  (let [frac (/ (+ scroll offset) (max 1 track))]
    (-> (- (* frac track') offset)
        (max 0)
        (min (max 0 (- track' viewport))))))

;; ---------------------------------------------------------------------
;; The fill — the ruler IS the legend. Built from adsb.map.style's per-edition
;; altitude stops, never a duplicated palette, so re-skinning the map re-skins the
;; ruler in the same edit, in both editions.
;;
;; It is painted on the TRACK, at full-scale positions, and it simply scrolls: the
;; ramp is always whole, and the viewport shows the slice of it the reader has
;; framed. Zoom out and the whole ramp is on screen again, which is the ruler's
;; other job.

(defn ruler-gradient-css
  "The ruler's fill as a CSS linear-gradient: every [feet colour] stop of `theme`'s
  adsb.map.style altitude ramp placed at its `altitude-pct` position on the track.
  The gradient's direction is the --stack-axis custom property (default `to top`),
  which the phone stylesheet flips to `to right` when the ruler lies down — same
  stops, same scale, rotated geometry."
  [theme]
  (str "linear-gradient(var(--stack-axis, to top), "
       (str/join ", " (for [[feet color] (:altitude-stops (style/palette theme))]
                        (str color " " (altitude-pct feet) "%")))
       ")"))

(def ^:private ruler-backgrounds
  ;; Each edition's fill, computed once — it changes only when the edition flips,
  ;; and it no longer changes with the zoom at all: the track carries the whole
  ;; ramp and the viewport does the framing.
  (into {} (for [theme (keys style/palettes)]
             [theme (ruler-gradient-css theme)])))

(defn- ruler-background
  [theme]
  (get ruler-backgrounds theme (:day ruler-backgrounds)))

;; ---------------------------------------------------------------------
;; The graduations — the scale's own labels, which must follow the zoom.
;;
;; A fixed SFC/FL100/…/FL400 was honest only at full range. Zoom to a 2000ft slice
;; and it would print one rule, or none, and the reader would be staring at an
;; unlabelled band of colour. So the STEP is chosen for the visible span — which
;; depends on the ZOOM alone, not on where the reader has scrolled to.

(def ^:private graduation-steps
  [10000 5000 2000 1000 500 200 100])

(defn graduation-step
  "The step between rules for a visible span of `span-ft`: the coarsest that still
  rules the window at least three times, so the scale is always readable and never
  a wall of lines."
  [span-ft]
  (or (first (filter #(>= (/ span-ft %) 3) graduation-steps))
      (last graduation-steps)))

(defn graduation-label
  "SFC at the surface; otherwise the flight level, three digits as the charts
  print them — FL050, not FL50."
  [feet]
  (if (zero? feet)
    "SFC"
    (str "FL" (.padStart (str (js/Math.round (/ feet 100))) 3 "0"))))

(defn graduations
  "The rules to draw across the whole track at this `zoom`. They live at their true
  altitudes and scroll with everything else; only how MANY of them there are
  depends on the zoom."
  ([] (graduations 1))
  ([zoom]
   (let [step (graduation-step (/ ceiling-ft (max 1 zoom)))]
     (for [feet (range 0 (inc ceiling-ft) step)]
       [feet (graduation-label feet)]))))

;; ---------------------------------------------------------------------
;; What the window is hiding. NOTHING MAY VANISH SILENTLY.
;;
;; The census counts sit inches from the ruler and count the WHOLE sky; a scrolled
;; ruler shows a slice of it. If aircraft simply scrolled out of sight with no
;; trace, the two instruments would disagree and the ruler would be the one lying.
;; And §7 is absolute: an emergency may never be hidden — least of all by a view
;; state the reader chose for an unrelated reason.
;;
;; So both ends carry what is beyond them, and carry it RED when what is beyond
;; them is squawking.

(defn overflow
  "What `airborne` aircraft lie outside `window`: how many below its floor, how
  many above its ceiling, and whether any of them is an emergency."
  [window airborne]
  (let [{:keys [min-ft max-ft]} window
        below (filter #(< (:aircraft/altitude-ft %) min-ft) airborne)
        above (filter #(> (:aircraft/altitude-ft %) max-ft) airborne)]
    {:below            (count below)
     :above            (count above)
     :below-emergency? (boolean (some aircraft/emergency? below))
     :above-emergency? (boolean (some aircraft/emergency? above))}))

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
  "The aircraft whose tick sits closest to `pct` on the FULL altitude scale — what
  the finger is pointing at. Nil when nothing is up there to point at.

  The scrub thinks in the whole sky and not in the window, because the track does:
  a tick is at its altitude whether the reader has scrolled it into view or not,
  and `track-pct` has already told us where the finger is in that same space."
  [airborne pct]
  (when (seq airborne)
    (apply min-key
           #(abs (- (altitude-pct (:aircraft/altitude-ft %)) pct))
           airborne)))

(defn unplotted
  "The aircraft in `picture` that the feeder HEARS and the chart cannot DRAW —
  no position, so no glyph, so nothing on the map to point at. They are the gap
  in `PLOTTED 57/62`, and the only aircraft in the app that can be reached in no
  other way than through the drawer."
  [picture]
  (remove aircraft/positioned? (vals picture)))

(defn airborne
  "The ruler's residents — the aircraft in `picture` that the scrub can
  land on. The shelves are not on the axis, so they cannot be scrubbed to."
  [picture]
  (filter #(= :airborne (tick-band %)) (vals picture)))

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

(rf/reg-event-db
  :stack/close-drawer
  (fn [db _]
    (dissoc db :stack/open-shelf)))

(rf/reg-sub
  :stack/open-shelf
  (fn [db _]
    (:stack/open-shelf db)))

;; THE WINDOW is view state, and it lives beside the drawer's — both are facts
;; about how the reader is looking at the sky, not about the sky.
(rf/reg-sub
  :stack/window
  (fn [db _]
    (:stack/window db full-window)))

(rf/reg-sub
  :stack/zoom
  (fn [db _]
    (:stack/zoom db 1)))

;; The window is a SNAPSHOT of what the viewport is showing, pushed here by the
;; scroll handler. The truth is the scroll offset; this is what the overflow
;; counts and the graduation step render from.
(rf/reg-sub
  :stack/window
  (fn [db _]
    (:stack/window db full-window)))

(rf/reg-event-db
  :stack/scrolled
  (fn [db [_ window]]
    (assoc db :stack/window window)))

(rf/reg-event-db
  :stack/set-zoom
  (fn [db [_ zoom]]
    (assoc db :stack/zoom (clamp-zoom zoom))))

(rf/reg-event-db
  :stack/reset-zoom
  (fn [db _]
    (assoc db :stack/zoom 1 :stack/window full-window)))

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
  "Click a name -> FOCUS that aircraft: select it, and take the chart to it
  (adsb.events/:aircraft/focus). These surfaces — the shelf clusters, the drawer's
  rows — name aircraft the reader may not be able to see, including ones off the
  edge of the chart entirely, and naming a thing you cannot see without showing it
  to you is not much of an answer.

  Click a caption -> open or close its drawer.

  THE RULER IS NOT HANDLED HERE, and that is load-bearing. Its ticks select
  through the SCRUB (`on-ruler-up!`), because a tap on a tick is a one-frame
  scrub. If this handler also fired for them, a single tap would dispatch
  [:aircraft/select icao] TWICE — and selection toggles now, so the aircraft would
  light and go out in the same press, which is a bug with no visible symptom
  except that nothing happens."
  [event]
  (when-not (some-> (.-target event) (.closest ".adsb-stack-ruler"))
    (if-let [icao (event-icao event)]
      (rf/dispatch [:aircraft/focus icao])
      (when-let [band (event-shelf event)]
        (rf/dispatch [:stack/toggle-shelf band])))))

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

;; ---------------------------------------------------------------------
;; ZOOM — the only part of this the platform does not already do.
;;
;; The browser owns PANNING (it is a scroll container), and with it momentum,
;; catch-to-stop and rubber-banding. It does not own PINCH on a scroll container,
;; and it never gives you the wheel. Those are ours: both change the TRACK'S
;; LENGTH and then correct the scroll offset so the sky under the fingers stays
;; under the fingers.
;;
;; The gesture split needs no rule, because the scroll container enforces it:
;;   ONE finger at zoom 1  — no overflow, nothing to scroll, so the drag SCRUBS.
;;   ONE finger zoomed     — the browser takes it and pans. Our scrub is
;;                           pointercancel'd, which is exactly right: scroll wins.
;;   TWO fingers           — always the sky, never an aircraft.

(defonce ^:private !pointers (atom {}))

(defn- track-metrics
  "The scroll container's own numbers — the shape every pure function here reads.
  The stance comes from the geometry, as it does everywhere in this namespace: a
  viewport wider than it is tall is lying down."
  [^js view]
  (let [horizontal? (> (.-clientWidth view) (.-clientHeight view))]
    {:horizontal? horizontal?
     :scroll      (if horizontal? (.-scrollLeft view) (.-scrollTop view))
     :viewport    (if horizontal? (.-clientWidth view) (.-clientHeight view))
     :track       (if horizontal? (.-scrollWidth view) (.-scrollHeight view))}))

(defn- pointer-offset
  "How far into the viewport, along the altitude axis, a client point falls."
  [^js view horizontal? x y]
  (let [rect (.getBoundingClientRect view)]
    (if horizontal? (- x (.-left rect)) (- y (.-top rect)))))

(defn- apply-zoom!
  "Grow or shrink the track about `offset` (px into the viewport), and set the
  scroll so the sky there does not move. The zoom lands in app-db — the track's
  length is a --zoom custom property — and the scroll correction is applied to the
  DOM directly, because the scroll offset IS the DOM's."
  [^js view zoom' offset]
  (let [{:keys [horizontal? track viewport] :as m} (track-metrics view)
        zoom'  (clamp-zoom zoom')
        track' (* viewport zoom')
        scroll' (zoom-scroll m track' offset)]
    (rf/dispatch-sync [:stack/set-zoom zoom'])
    ;; The track only takes its new length after the render, so the scroll must be
    ;; corrected after it too — otherwise it is clamped against the OLD length and
    ;; the sky under the fingers jumps.
    (js/requestAnimationFrame
      (fn []
        (when (pos? track)
          (if horizontal?
            (set! (.-scrollLeft view) scroll')
            (set! (.-scrollTop view) scroll')))))))

(defn- pointer-span
  "The distance between the two live pointers along the altitude axis, and the
  point midway between them — the two numbers a pinch is made of."
  [^js view]
  (let [{:keys [horizontal?]} (track-metrics view)
        ps (->> (vals @!pointers)
                (take 2)
                (map (fn [{:keys [x y]}] (pointer-offset view horizontal? x y))))]
    (when (= 2 (count ps))
      {:span (js/Math.abs (- (first ps) (second ps)))
       :mid  (/ (+ (first ps) (second ps)) 2)})))

(defonce ^:private !pinch (atom nil))

(def ^:const tap-slop-px
  "How far a finger may travel and still be a tap rather than a drag."
  5)

(defonce ^:private !gesture (atom nil))

(defn- scrub!
  "Name and light whichever tick the pointer is nearest — in FULL-SCALE space, so
  a scrolled track is no obstacle: `track-pct` folds the scroll offset in."
  [^js view x y]
  (let [{:keys [horizontal?] :as m} (track-metrics view)]
    (when-let [pct (track-pct m (pointer-offset view horizontal? x y))]
      (rf/dispatch [:stack/scrub pct]))))

(defn- on-ruler-down!
  "One pointer begins a gesture that is not yet a tap or a scrub — it is whichever
  the finger turns out to be doing, and if the browser starts scrolling instead,
  it is neither. A SECOND pointer is the reader reaching for the SKY: whatever the
  first was becoming is abandoned, and the pinch takes over."
  [event]
  (let [view (.-currentTarget event)]
    (swap! !pointers assoc (.-pointerId event)
           {:x (.-clientX event) :y (.-clientY event)})
    (if (>= (count @!pointers) 2)
      (do (reset! !scrubbing? false)
          (reset! !gesture nil)
          (reset! !pinch (pointer-span view)))
      (do (reset! !scrubbing? true)
          (reset! !gesture {:x (.-clientX event) :y (.-clientY event) :moved? false})
          (scrub! view (.-clientX event) (.-clientY event))))))

(defn- on-ruler-move!
  "Two pointers pinch. One pressed pointer scrubs — until the browser decides the
  reader is scrolling, at which point it sends us a pointercancel and takes over."
  [event]
  (let [view (.-currentTarget event)]
    (swap! !pointers assoc (.-pointerId event)
           {:x (.-clientX event) :y (.-clientY event)})
    (cond
      (>= (count @!pointers) 2)
      (when-let [now (pointer-span view)]
        (when-let [before @!pinch]
          ;; Fingers apart -> the span grows -> the track grows -> the sky spreads.
          (when (and (pos? (:span before)) (pos? (:span now)))
            (let [zoom  @(rf/subscribe [:stack/zoom])
                  ratio (/ (:span now) (:span before))]
              (apply-zoom! view (* zoom ratio) (:mid now)))))
        (reset! !pinch now))

      @!scrubbing?
      (let [{:keys [x y moved?]} @!gesture
            travel (js/Math.hypot (- (.-clientX event) x) (- (.-clientY event) y))]
        (swap! !gesture assoc :moved? (or moved? (> travel tap-slop-px)))
        (scrub! view (.-clientX event) (.-clientY event))))))

(defn- on-ruler-up!
  "A tap selects the nearest tick. A scrub selects whatever it left named. They are
  the same dispatch, because a tap IS a one-frame scrub — which is what gives a
  3px tick a forgiving target without fattening the ink."
  [event]
  (swap! !pointers dissoc (.-pointerId event))
  (when (< (count @!pointers) 2)
    (reset! !pinch nil))
  (when @!scrubbing?
    (reset! !scrubbing? false)
    (reset! !gesture nil)
    (rf/dispatch [:stack/scrub-end])))

(defn- on-ruler-cancel!
  "The browser took the gesture — the reader is SCROLLING, and scroll wins. Or the
  system took it (a call, an edge swipe). Either way nothing was chosen, and
  nothing is selected: the hover the scrub was painting is dropped with it."
  [event]
  (swap! !pointers dissoc (.-pointerId event))
  (reset! !scrubbing? false)
  (reset! !pinch nil)
  (reset! !gesture nil)
  (rf/dispatch [:aircraft/clear-hover]))

(def ^:const wheel-zoom-step
  "One notch of the wheel, as a ratio. Gentle: a reader spinning through the sky
  should feel the scale open, not fall through it."
  1.15)

(defn- on-ruler-wheel!
  "Wheel over the ruler zooms about the cursor — the desktop's pinch. The page does
  not scroll behind it, so the strip may have the wheel outright."
  [event]
  (.preventDefault event)
  (let [view (.-currentTarget event)
        {:keys [horizontal?]} (track-metrics view)
        zoom  @(rf/subscribe [:stack/zoom])
        ratio (if (pos? (.-deltaY event)) (/ 1 wheel-zoom-step) wheel-zoom-step)]
    (apply-zoom! view (* zoom ratio)
                 (pointer-offset view horizontal? (.-clientX event) (.-clientY event)))))

(defn- on-ruler-double!
  "Back to the whole sky — and to the whole colour ramp, which is the ruler's other
  job."
  [event]
  (let [view (.-currentTarget event)]
    (rf/dispatch [:stack/reset-zoom])
    (js/requestAnimationFrame
      (fn [] (set! (.-scrollLeft view) 0) (set! (.-scrollTop view) 0)))))

(defn- on-ruler-scroll!
  "The reader scrolled — natively, on the compositor, with the platform's own
  momentum. Nothing here moved a tick: they sit at their true altitudes in the
  track and the viewport slid over them. All we do is READ what is now on screen,
  because two things must render from it — the overflow counts and the graduation
  step — and neither may lie about what the reader can see."
  [event]
  (rf/dispatch [:stack/scrolled (scroll->window (track-metrics (.-currentTarget event)))]))

;; ---------------------------------------------------------------------
;; Components — kebab-case functions returning hiccup.

(defn- tick-name
  "The identity a tick shows when named: the callsign when the sky gave
  one, otherwise the bare icao. This is where identity lives — the map
  surface stays glyphs-only (Q7a)."
  [{:aircraft/keys [callsign icao]}]
  (or callsign icao))

(defn- graduation
  "One flight-level RULE across the track, at its true altitude. It scrolls with
  everything else — nothing here knows where the reader has scrolled to.

  Its LABEL is not here. A scroll container clips what overflows it, and every
  label on this ruler overflows by design: the flight levels sit outside the
  strip, and a tick's name spills onto the chart (§9 — identity lives on the
  Stack, and the map stays glyphs-only). Giving the scroller room to contain them
  would mean a scroll container lying across the map, swallowing clicks meant for
  the aircraft. So the labels live OUTSIDE the scroller, in `ruler-labels`, placed
  by the window rather than by the track."
  [[feet _label]]
  [:div.adsb-stack-grad {:style {"--alt-pct" (str (altitude-pct feet))}}])

(defn- ruler-labels
  "THE LABEL LAYER — outside the scroller, and the reason the scroller can exist.

  A scroll container clips what overflows it, and every label the ruler draws
  overflows on purpose: the flight levels print beside the strip, and a tick's
  name spills onto the chart. Containing them would mean widening the scroller
  across the map and swallowing the clicks meant for the aircraft on it.

  So they are placed here, in the VIEWPORT'S own space, from the window the scroll
  handler reports — `window-pct`, not `altitude-pct`. The rules and the ticks
  scroll inside the track; their labels ride above it and land on exactly the same
  pixels, because both are reading the same scroll position from opposite sides."
  [{:keys [window zoom airborne selected-icao hovered-icao]}]
  [:div.adsb-stack-labels {:aria-hidden true}
   (for [[feet label] (graduations zoom)
         :when (in-window? window feet)]
     ^{:key (str "g" feet)}
     [:span.adsb-stack-grad-label
      {:style {"--alt-pct" (str (window-pct window feet))}}
      label])
   (for [a airborne
         :let [icao (:aircraft/icao a)
               alt  (:aircraft/altitude-ft a)]
         :when (and (in-window? window alt)
                    (or (aircraft/emergency? a)
                        (= icao selected-icao)
                        (= icao hovered-icao)))]
     ^{:key icao}
     [:span.adsb-stack-ruler-label
      {:class [(when (= icao selected-icao) "adsb-stack-ruler-label-selected")
               (when (aircraft/emergency? a) "adsb-stack-ruler-label-emergency")]
       :style {"--alt-pct" (str (window-pct window alt))}}
      (tick-name a)])])

(defn- overflow-marker
  "What the window is hiding, at the end it is hiding it beyond: twelve aircraft
  above the ceiling you have framed are not gone, they are just not here. RED when
  one of them is squawking — §7 does not bend for a view state, and an emergency
  the reader cannot see is the one thing this zoom must never cost.

  THE ARROW IS THE STYLESHEET'S, not this component's. `Above` is UP on a standing
  ruler and RIGHT on a recumbent one; the direction is a fact about the stance,
  and the stance is CSS's business (one DOM, rotated geometry — §9/Q9c). Hiccup
  that hard-coded ▲ would be wrong on a phone half the time."
  [{:keys [edge n emergency?]}]
  (when (pos? n)
    [:div.adsb-stack-overflow
     {:class       (str "adsb-stack-overflow-" (name edge)
                        (when emergency? " adsb-stack-overflow-emergency"))
      :data-testid (str "overflow:" (name edge))
      :data-count  (str n)
      :title       (str n " aircraft " (if (= edge :above) "above" "below")
                        " the ruler's window"
                        (when emergency? " — one is squawking distress"))}
     (str n)]))

(defn- tick
  "One aircraft's tick. Airborne ticks carry their place on the altitude
  axis as --alt-pct (the stylesheet turns that into `bottom` or `left`
  and TRANSITIONS it, so a climbing aircraft's tick drifts rather than
  jumps); shelf ticks cluster, unplaced. The tick is named — a small
  label beside it — while hovered, while selected, always while the
  aircraft is squawking distress, and always inside an open sheet, which
  exists precisely to name its residents (`always-named?`)."
  [aircraft* {:keys [selected-icao hovered-icao always-named? testid-prefix
                     suppress-label?]}]
  (let [{:aircraft/keys [icao altitude-ft]} aircraft*
        band       (tick-band aircraft*)
        emergency? (aircraft/emergency? aircraft*)
        selected?  (= icao selected-icao)
        named?     (or always-named? emergency? selected? (= icao hovered-icao))]
    [:div.adsb-stack-tick
     (cond-> {:role          "option"
              :data-icao     icao
              :data-testid   (str (or testid-prefix "tick:") icao)
              :aria-selected selected?
              :aria-label    (tick-name aircraft*)
              :tab-index     0
              :class         [(when selected? "adsb-stack-tick-selected")
                              (when emergency? "adsb-stack-tick-emergency")]}
       (= band :airborne)
       (assoc :style {"--alt-pct" (str (altitude-pct altitude-ft))}))
     (when (and named? (not suppress-label?))
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
   (cond-> {:data-testid testid :aria-hidden true}
     ;; No colour given means there is no such aircraft in the sky, so there is
     ;; no ink of that kind on the chart to key. The swatch falls back to the
     ;; stylesheet's faded ink and the caption states its fact quietly. This is
     ;; how EMG can be permanent WITHOUT spending red on a calm sky (§7: red is
     ;; ink that never blinks, and it keeps that power only by being absent when
     ;; there is nothing to be red about).
     color (assoc :data-color color
                  :style {:background-color color}))])

(defn- census-chip
  "One census caption: a swatch, a state, and how many aircraft are in it.

  A BUTTON only when it has residents to name. A chip at zero opens a drawer of
  nobody — a dead target, and an empty panel over the chart — so at zero it
  renders as a plain caption instead. The fact is still stated; it simply is not
  offered as a door to nowhere.

  `n` is the number of RESIDENTS BEHIND THE DOOR, which is not always the number
  printed on it: PLOTTED shows a fraction and opens onto the gap, so it counts
  the unplotted (`count-text` overrides what is shown). Every other caption
  prints and opens the same set. `expansion`, when given, makes the label an
  <abbr> carrying the long form."
  [{:keys [band label expansion color n count-text data interactive? open?]}]
  (let [testid  (str "shelf:" (name band))
        swatch* (str "swatch:" (name band))
        body    [[swatch color swatch*]
                 (if expansion
                   [:abbr.adsb-stack-shelf-label {:title expansion} label]
                   [:span.adsb-stack-shelf-label label])
                 [:span.adsb-stack-shelf-count (or count-text n)]]]
    (if interactive?
      (into [:button.adsb-stack-shelf-chip
             (merge data
                    {:type          "button"
                     :data-shelf    (name band)
                     :data-testid   testid
                     :aria-expanded (boolean open?)})]
            body)
      (into [:span.adsb-stack-shelf-caption (merge data {:data-testid testid})]
            body))))

(defn- shelf
  "A holding band at the ruler's foot — the ground cluster or the
  altitude-unknown area. Its residents are shown one of two ways, and never
  both at once (two DOM nodes for one aircraft would be two answers to
  `who is this?`):

    * ON THE STACK — the dot cluster. Grouped, never ranked. The phone hides
      the dots and keeps only the chip, which is the whole point: the cluster's
      one fact is its count, and on the short axis the count is cheaper.
    * IN THE DRAWER — where every resident is NAMED and selectable. What a
      chip's number cannot tell you, and neither could the dots.

  The drawer is not the shelf's, though. There is exactly ONE of it, and it
  belongs to the Stack (see `drawer`): every caption feeds the same panel, and
  opening a second one swaps the first one's aircraft out rather than stacking
  another window on the chart.

  The chip leads with the state's SWATCH, so `● GND 3` is a legend row and a
  count in the same breath. The swatch matters most in the two places the dots
  cannot help: on a phone, where they are hidden, and at a count of zero, where
  there are no dots to take the colour from."
  [{:keys [band class label color aircraft open? selected-icao hovered-icao]}]
  (let [tick-opts {:selected-icao selected-icao :hovered-icao hovered-icao}
        n         (count aircraft)]
    [:div.adsb-stack-shelf {:class class :role "group" :aria-label label}
     [census-chip {:band band :label label :color color :n n
                   :interactive? (pos? n) :open? (and open? (pos? n))}]
     ;; ONE NODE PER AIRCRAFT. While this band's drawer stands open its residents
     ;; are named in there, so the cluster stands down: two DOM nodes for one
     ;; aircraft would be two answers to `who is this?`, and the reader would
     ;; have to work out which of the two to point at.
     (when-not open?
       (for [a aircraft]
         ^{:key (:aircraft/icao a)} [tick a tick-opts]))]))

(defn- emergency-shelf
  "`● EMG 0` — and `● EMG 1` when the sky is not calm.

  IT IS A CENSUS, NOT A HEALTH SIGNAL, and that is why it is permanent. The
  header's stream and feeder signals report on the APPARATUS — on us, on the
  plumbing that carries tracks to this screen — and a healthy machine is not
  news, so they say nothing at all when all is well. This says how many
  AIRCRAFT are in a state, exactly as GND and NO ALT do. It is a fact about the
  sky, and the app gets no credit for it.

  A STATED ZERO IS WORTH MORE THAN AN IMPLIED ONE. Nothing on screen could mean
  `no emergencies` — or it could mean `the indicator is not rendering`, or `I am
  not looking in the right place`. That is not an ambiguity to leave lying
  around a distress readout.

  RED IS NOT PERMANENT, THOUGH. §7 makes red the ink that never blinks, and it
  keeps that power only by being ABSENT while there is nothing to be red about;
  a red dot sitting in the corner of a calm chart is red that means nothing, and
  it cheapens the red that means everything. So at zero the swatch and the count
  print in faded ink and the caption merely states its fact. The moment an
  aircraft squawks, the swatch goes red and the count goes bold, and the caption
  becomes the key for the red that is now on the chart.

  It opens nothing, and needs to open nothing: a distressed aircraft is already
  named on its own tick (red, hatched, permanently labelled — §7) and named
  again in the NOTAM ribbon, which is where you go to act on it. A third list
  would be a third answer to a question the chart has answered twice."
  [aircraft color]
  (let [n       (count aircraft)
        squawk? (pos? n)]
    [:div.adsb-stack-shelf.adsb-stack-emergency
     {:class      (when squawk? "adsb-stack-emergency-active")
      :role       "status"
      :aria-label (if squawk?
                    (str n " squawking distress")
                    "No aircraft squawking distress")}
     [census-chip {:band         :emergency
                   :label        "EMG"
                   :n            n
                   :color        (when squawk? color)
                   :interactive? false}]]))

(defn- traffic-caption
  "`PLOTTED 53/63` — how many aircraft the chart can DRAW, over how many the
  feeder can HEAR.

  The label names what the fraction MEASURES, not the noun it counts. It read
  `AC` first, and Brandon had to ask what that meant — which is the whole verdict
  on a caption: a label that needs explaining has failed at the one job a label
  has, and `AC` reads as air conditioning or alternating current long before it
  reads as aircraft. `PLOTTED` needs no gloss, and it happens to be the chart's
  own word — §5's magenta is the plotter's pen.

  A metric about the SKY, so it lives with the sky's other counts rather than in
  the header, which reports on the apparatus (the feeder's range and message
  rate, the stream and feeder health). That is the line: the header is the
  instrument panel; this row is the census.

  It is a FRACTION because the relationship is the fact. `63 · 53` states two
  numbers and leaves the reader to subtract, and gives no hint which is the
  subset of which — the gap is the whole point, since those ten aircraft are
  real, heard on the radio, and NOT ON THE MAP (a Mode S target with no
  position: heard, never located). `53/63` says `fifty-three of sixty-three` in
  the notation itself, and it is shorter than the two numbers it replaces.

  It keys nothing — it is not a colour, so it takes no swatch. But it OPENS,
  and what it opens is the gap: the aircraft that are heard and NOT on the map.
  Those are the only aircraft in the app you cannot reach by pointing at them,
  precisely because there is nothing to point at — so the drawer is the only way
  to see who they are. The chip is a button exactly when that gap is non-empty,
  which is the same rule every other caption keeps: a count of zero opens
  nothing, because there is nobody behind it."
  [picture open?]
  (let [total     (count picture)
        placed    (count (filter aircraft/positioned? (vals picture)))
        unplotted (- total placed)]
    [:div.adsb-stack-shelf.adsb-stack-traffic
     {:role "group" :aria-label "Traffic"}
     [census-chip
      {:band          :traffic
       :label         "PLOTTED"
       :expansion     (str placed " of " total " aircraft plotted; the rest are"
                          " heard but cannot be placed")
       :count-text    (str placed "/" total)
       :n             unplotted
       :data          {:data-total      (str total)
                       :data-positioned (str placed)}
       :interactive?  (pos? unplotted)
       :open?         (and open? (pos? unplotted))}]]))

(def ^:private drawer-titles
  "The drawer wears the CAPTION'S OWN LABEL, not a sentence about it. You tapped
  `NO ALT`; the thing that opened is `NO ALT`. A prose title is a second name for
  a thing that already had one, and it made the panel wide enough to hold the
  sentence rather than the aircraft.

  `NO POS` is the one that is not simply copied. The chip counts what IS plotted
  and the drawer holds what is NOT, so echoing `PLOTTED` would name the panel
  after the aircraft it does not contain. And these aircraft have a name already,
  in the tongue the rest of the row speaks: they have no POSITION, exactly as its
  neighbour has no ALTITUDE. GND · NO ALT · EMG · NO POS."
  {:ground    "GND"
   :unknown   "NO ALT"
   :emergency "EMG"
   :traffic   "NO POS"})

(def ^:private drawer-descriptions
  "The long form, for the accessibility tree only — the same trade the feeder's
  green dot makes. The eye gets the label it tapped; a screen reader gets the
  sentence."
  {:ground    "On the ground"
   :unknown   "No altitude reported"
   :emergency "Squawking distress"
   :traffic   "Heard, but never located — no position"})

(defn- on-drawer-key!
  "Escape closes the drawer. It is an overlay over the chart, and an overlay you
  cannot dismiss from the keyboard is a trap."
  [event]
  (when (= "Escape" (.-key event))
    (rf/dispatch [:stack/close-drawer])))

(defn- drawer
  "THE ONE DRAWER. Every caption opens this same panel, and opening a second
  swaps the first one's aircraft out — the chart never grows a second window,
  and there is never a question of which list you are reading.

  It names what a count cannot: `GND 3` is a fact, and these are the three. Each
  row is the tick's own component, always named, still firing the map's
  [:aircraft/select icao] contract — so an aircraft in the drawer selects exactly
  as one on the ruler does.

  It opens on the LEFT. The right belongs to the Stack and, above it, to the
  selection card; the (i) sits in the bottom-right corner. The left edge is the
  only clear wall in the room.

  `:traffic` is the reason this is worth having at all: those aircraft are heard
  and NOT on the map, so they are the only ones in the app that cannot be reached
  by pointing at them. There is nothing to point at. This is the only door to
  them."
  [_props]                            ; reagent hands the props to the render fn
  (r/create-class
    {:display-name "adsb-stack-drawer"
     :component-did-mount    #(.addEventListener js/document "keydown" on-drawer-key!)
     :component-will-unmount #(.removeEventListener js/document "keydown" on-drawer-key!)
     :reagent-render
     (fn [{:keys [band aircraft selected-icao hovered-icao]}]
       (let [title (get drawer-titles band (name band))]
         [:div.adsb-stack-drawer
          {:data-testid "drawer"
           :data-band   (name band)
           :role        "group"
           :aria-label  (get drawer-descriptions band title)}
          [:div.adsb-stack-drawer-head
           ;; The label, and the way out. No count: the caption you tapped to get
           ;; here is still on screen, still carrying it. Saying it twice would
           ;; only make the drawer wider than the aircraft in it.
           [:span.adsb-stack-drawer-title title]
           [:button.adsb-stack-drawer-close
            {:type        "button"
             :data-testid "drawer-close"
             :aria-label  "Close"
             :on-click    #(rf/dispatch [:stack/close-drawer])}
            "×"]]
          [:div.adsb-stack-drawer-list
           (for [a aircraft]
             ^{:key (:aircraft/icao a)}
             ;; A DISTINCT TESTID, because the duplication is REAL and intended.
             ;; A drawer row is not always the aircraft's only node: an aircraft
             ;; heard with an altitude but no position is a tick on the ruler
             ;; (it IS at FL330 — that is true, and the scale must say so) AND a
             ;; row in the traffic drawer (it is NOT on the map — also true).
             ;; Two facts, two surfaces. Only the SHELF clusters stand down when
             ;; their drawer opens, because those would be the same fact twice.
             [tick a {:selected-icao selected-icao
                      :hovered-icao  hovered-icao
                      :always-named? true
                      :testid-prefix "drawer-tick:"}])]]))}))

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
        window*     (rf/subscribe [:stack/window])
        zoom*       (rf/subscribe [:stack/zoom])
        emergencies (rf/subscribe [:aircraft/emergencies])
        ;; The census counts the WHOLE picture, not the roster: the roster is
        ;; already filtered to what the ruler can place, and an aircraft the
        ;; Stack cannot place is exactly the one this fraction exists to count.
        picture     (rf/subscribe [:aircraft/picture])]
    (fn []
      (let [theme         @theme/!theme
            palette       (style/palette theme)
            bands         (group-by tick-band @roster)
            selected-icao @selected
            hovered-icao  @hovered
            open-shelf    @open
            window        @window*
            zoom          @zoom*
            airborne*     (:airborne bands)
            ;; EVERY tick is in the track, at its true altitude — the ones the
            ;; reader has scrolled past are simply out of view, exactly as they
            ;; would be in any scrolling list. The ends report them.
            {:keys [below above below-emergency? above-emergency?]}
            (overflow window airborne*)
            tick-opts     {:selected-icao selected-icao
                           :hovered-icao  hovered-icao}]
        [:aside.adsb-stack
         {:role          "listbox"
          :aria-label    "Aircraft by altitude"
          :on-click      on-stack-click!
          :on-mouse-over on-stack-over!
          :on-mouse-out  on-stack-out!}
         ;; THE RULER IS A FRAME; the scrolling happens inside it. The overflow
         ;; markers hang OUTSIDE the scroll viewport — a child of the viewport
         ;; would scroll away with the sky it is reporting on.
         [:div.adsb-stack-ruler
          {:data-testid "ruler"
           :data-min-ft (str (js/Math.round (:min-ft window)))
           :data-max-ft (str (js/Math.round (:max-ft window)))
           :data-zoom   (str zoom)}
          [:div.adsb-stack-ruler-view
           {:data-testid       "ruler-view"
            :on-pointer-down   on-ruler-down!
            :on-pointer-move   on-ruler-move!
            :on-pointer-up     on-ruler-up!
            :on-pointer-cancel on-ruler-cancel!
            :on-wheel          on-ruler-wheel!
            :on-double-click   on-ruler-double!
            :on-scroll         on-ruler-scroll!}
           ;; The TRACK — as long as the zoom demands, carrying the whole ramp and
           ;; every tick at its true altitude. Scrolling moves the viewport over
           ;; it; not one tick's position changes, which is why there is nothing
           ;; here to animate and nothing to hold still.
           [:div.adsb-stack-ruler-track
            {:style {:background (ruler-background theme)
                     "--zoom"    (str zoom)}}
            (for [g (graduations zoom)]
              ^{:key (first g)} [graduation g])
            (for [a airborne*]
              ^{:key (:aircraft/icao a)}
              [tick a (assoc tick-opts :suppress-label? true)])]]
          [ruler-labels {:window        window
                         :zoom          zoom
                         :airborne      airborne*
                         :selected-icao selected-icao
                         :hovered-icao  hovered-icao}]
          [overflow-marker {:edge :below :n below :emergency? below-emergency?}]
          [overflow-marker {:edge :above :n above :emergency? above-emergency?}]]
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
         [emergency-shelf @emergencies (:emergency-color palette)]
         [traffic-caption @picture (= :traffic open-shelf)]
         ;; Is the instrument working? The one apparatus fact left in the app,
         ;; at the end of the row the reader already scans. Silent while all is
         ;; well (adsb.ui.health).
         [health/health]

         ;; ONE drawer, whichever caption opened it — and none at all when the
         ;; band it names has emptied out from under it (an aircraft can land,
         ;; or age out of the picture, while its drawer stands open).
         (let [residents (case open-shelf
                           :ground    (:ground bands)
                           :unknown   (:unknown bands)
                           :emergency @emergencies
                           :traffic   (unplotted @picture)
                           nil)]
           (when (seq residents)
             [drawer {:band          open-shelf
                      :aircraft      residents
                      :selected-icao selected-icao
                      :hovered-icao  hovered-icao}]))]))))
