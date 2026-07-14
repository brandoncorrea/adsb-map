(ns adsb.map.emergency
  "The §7 map-surface emergency annotations — the chart's own answer to a
  squawking aircraft, beside (never instead of) the chrome's NOTAM strip
  (adsb.ui.alert). The strip NAMES the emergency in the chrome; these
  MARK it on the chart:

    * IN VIEW — the red-pen DOUBLE ELLIPSE: two slightly canted,
      unequally squashed ellipses circling the glyph, the way a
      controller's grease pencil goes around a target twice. Each pass
      draws itself in ONCE (a stroke-dashoffset entrance, the second
      pass a beat behind the first) and then HOLDS — ink never blinks,
      never pulses (§6: emergencies spend zero ambient motion). Beside
      it, the MAYDAY STAMP: a small rotated annotation in the chart's
      voice carrying MAYDAY + the squawk's meaning in words (the same
      copy the NOTAM strip prints — adsb.ui.alert/emergency-words, so
      chart and chrome can never disagree), the callsign, altitude, and
      vertical rate. The stamp's DATA updates at feeder cadence; its
      MANNER never changes — the element is built once per appearance
      and thereafter only its text nodes are touched.

    * OUT OF VIEW — the EDGE ARROW (Q13c: the camera is never
      hijacked): a red annotation pinned just inside the map boundary
      where the ray toward the aircraft exits, its glyph rotated to the
      great-circle bearing, carrying callsign + distance. Clickable: it
      fires the map's existing [:aircraft/select icao] contract — the
      same event a plane click fires, which opens the index card and
      moves the camera not one inch. The arrow informs and offers; it
      never flies anywhere itself.

  ## Mechanism

  The compass-pencil ring (adsb.map.selection) set the pattern: MapLibre
  MARKERS through the seam, driven by a reagent track! OUTSIDE any
  component, so none of this ever costs a React render. Emergencies are
  rare and few, so a DOM marker per emergency is the right tool where
  hundreds of aircraft are not (that is the GeoJSON layer's job — which
  already draws the emergency glyph large and red; these annotations
  compose with it, they do not replace it).

  One marker per emergency icao: ellipse+stamp inside the viewport, edge
  arrow outside. The in/out verdict — and the arrow's edge point,
  bearing, and distance — is the PURE adsb.geo/edge-annotation, judged
  against the seam's `bounds` and re-judged on every picture change
  (the track over the same :aircraft/emergencies sub the NOTAM strip
  reads) AND on every settled camera move (`on-move!`). Panning an
  emergency out of view raises its arrow; panning it back in draws its
  ellipse afresh — a fresh element per APPEARANCE is what replays the
  one-time draw-in, exactly like the ring, while the same annotation
  merely tracking its aircraft is MOVED, never rebuilt, so settled ink
  holds still.

  An emergency with no position gets no map annotation — there is
  nowhere on the chart to draw one and no bearing to point; the NOTAM
  strip still names it.

  BOUNDARY 4 (docs/validation-boundaries.md): callsign, icao, squawk
  all arrived off unauthenticated radio. Every feeder string lands via
  textContent — text on the chart, never markup."
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.geo :as geo]
    [adsb.map.maplibre :as maplibre]
    [adsb.ui.alert :as alert]
    [re-frame.core :as rf]
    [reagent.core :as r]))

(def ^:const svg-ns "http://www.w3.org/2000/svg")

(def ^:const ellipse-box-px
  "The ellipse element's box. The emergency glyph draws at the absolute
  emergency-icon-size (1.6 × the 32 px icon ≈ 51 px — adsb.map.style
  lets no other channel shrink a distressed plane), so the pen hugs
  that size with a little air and never touches the ink."
  84)

(def ^:const draw-in-ms
  "One pass of the pen. Within §7's once-then-hold law and quick enough
  that the mark lands with the urgency it reports."
  450)

(def ^:const second-pass-delay-ms
  "The second pass of the pen starts this far behind the first — a
  double stroke, not a synchronized pair."
  160)

;; The edge arrow must clear not just the frame but the CHROME parked on
;; it: the NOTAM strip along the top (the strip is up by
;; definition — an emergency is active), the Stack on the right edge
;; (desktop) or the bottom (phone — the same 640px line app.css draws),
;; and the arrow's own extent. All px, converted to viewport fractions
;; at sync time.

;; There is no header any more (adsb-sod); the NOTAM strip sits on the top
;; edge itself, and it is the only chrome the arrow must clear up there.
(def ^:const notam-strip-px 36)    ; one NOTAM row, on the top edge
;; Roster dock width (desktop) / collapsed drawer height (phone). Matches
;; --roster-w in adsb.css.tokens / adsb.css.phone (adsb-66h).
(def ^:const roster-px 300)
(def ^:const roster-phone-rail-px 48)
(def ^:const arrow-half-width-px 80)
(def ^:const arrow-half-height-px 18)
(def ^:const edge-air-px 8)
(def ^:const phone-max-width-px 640)

;; The em-dash stands in for facts the sky never reported, as everywhere.
(def ^:const em-dash "—")

;; ---------------------------------------------------------------------
;; Presentation — pure. The stamp speaks the chart's shorthand; CSS
;; owns case and voice, so these strings stay lowercase-honest.

(defn- altitude-text
  [{:aircraft/keys [on-ground? altitude-ft]}]
  (cond
    on-ground?          "ground"
    (some? altitude-ft) (str altitude-ft " ft")
    :else               em-dash))

(defn- vertical-rate-text
  [{:aircraft/keys [baro-rate-fpm]}]
  (cond
    (nil? baro-rate-fpm)  em-dash
    (neg? baro-rate-fpm)  (str "↓" (- baro-rate-fpm) " fpm")
    (pos? baro-rate-fpm)  (str "↑" baro-rate-fpm " fpm")
    :else                 "level"))

(defn- mayday-line
  "MAYDAY plus the squawk's meaning in words — the same copy the NOTAM
  strip prints, read from the one shared source."
  [aircraft*]
  (str "mayday · "
       (or (alert/emergency-words (aircraft/emergency-kind aircraft*))
           "emergency")))

(defn- display-name
  [{:aircraft/keys [callsign icao]}]
  (or callsign icao))

(defn- distance-text
  [distance-m]
  (str (js/Math.round (geo/meters->nm distance-m)) " nm"))

;; ---------------------------------------------------------------------
;; The red-pen double ellipse + MAYDAY stamp (in-view annotation)

(defn- set-draw-in!
  "Arm `node`'s one-time entrance: the adsb-mayday-draw keyframes
  (app.css) sweep stroke-dashoffset from the full pathLength to its
  settled 0. Set as INLINE LONGHANDS rather than a stylesheet rule so
  the browser suite can PROVE the iteration count is 1 — the ink draws
  once and holds; nothing here may ever loop. `backwards` fill keeps
  the delayed second pass invisible until its turn."
  [node delay-ms]
  (let [style (.-style node)]
    (set! (.-animationName style) "adsb-mayday-draw")
    (set! (.-animationDuration style) (str draw-in-ms "ms"))
    (set! (.-animationTimingFunction style) "ease-out")
    (set! (.-animationDelay style) (str delay-ms "ms"))
    (set! (.-animationIterationCount style) "1")
    (set! (.-animationFillMode style) "backwards")))

(defn- ellipse-node
  "One pass of the pen: an SVG ellipse, canted `rotation-deg` about its
  own centre, normalized to pathLength 100 with a single full-length
  dash so the draw-in can sweep it from nothing."
  [cx cy rx ry rotation-deg delay-ms]
  (let [node (js/document.createElementNS svg-ns "ellipse")]
    (.setAttribute node "cx" (str cx))
    (.setAttribute node "cy" (str cy))
    (.setAttribute node "rx" (str rx))
    (.setAttribute node "ry" (str ry))
    (.setAttribute node "pathLength" "100")
    (.setAttribute node "stroke-dasharray" "100")
    (.setAttribute node "transform"
                   (str "rotate(" rotation-deg " " cx " " cy ")"))
    (set-draw-in! node delay-ms)
    node))

(defn- stamp-offset
  "Where the stamp sits relative to the glyph, chosen ONCE at placement
  from the track at that moment: perpendicular-right of travel, so it
  covers neither the aircraft's path ahead nor its trail astern. The
  offsets are screen px from the marker centre (screen y grows down);
  a track-less target reads the stamp due east. The stamp never moves
  again — its manner is static, only its data updates."
  [track-deg]
  (let [theta (* (+ (or track-deg 0) 90) (/ js/Math.PI 180))]
    [(* 106 (js/Math.sin theta))
     (* -74 (js/Math.cos theta))]))

(defn- span-node
  [class-name]
  (let [node (js/document.createElement "span")]
    (set! (.-className node) class-name)
    node))

(defn- label-node
  "A caption-voice label for the stamp's facts row (the §5 caption
  clause: the smallest labels print in the Grotesk hand)."
  [text]
  (let [node (span-node "adsb-mayday-label")]
    (set! (.-textContent node) text)
    node))

(defn mayday-element
  "A fresh in-view annotation: the double ellipse (SVG, draws in once)
  with the MAYDAY stamp beside it. Returns the element and the stamp's
  DATA nodes — {:el _ :word-el _ :callsign-el _ :altitude-el _
  :rate-el _} — which update-stamp! mutates at feeder cadence while the
  element itself, and so its entrance and its manner, is never rebuilt
  for the life of the appearance."
  [aircraft*]
  (let [el          (js/document.createElement "div")
        svg         (js/document.createElementNS svg-ns "svg")
        centre      (/ ellipse-box-px 2)
        stamp       (js/document.createElement "div")
        facts       (js/document.createElement "div")
        word-el     (span-node "adsb-mayday-word")
        callsign-el (span-node "adsb-mayday-callsign")
        altitude-el (span-node "adsb-mayday-value")
        rate-el     (span-node "adsb-mayday-value")
        [dx dy]     (stamp-offset (:aircraft/track-deg aircraft*))]
    (set! (.-className el) "adsb-mayday")
    (.setAttribute svg "viewBox" (str "0 0 " ellipse-box-px " " ellipse-box-px))
    (.setAttribute svg "aria-hidden" "true")
    ;; Two passes of the pen: same centre, different squash, opposite
    ;; cants, the second a beat behind — imperfect on purpose.
    (.appendChild svg (ellipse-node centre centre 34 26 -8 0))
    (.appendChild svg (ellipse-node centre centre 31 28 5 second-pass-delay-ms))
    (.appendChild el svg)
    (set! (.-className stamp) "adsb-mayday-stamp")
    (set! (.. stamp -style -left) (str "calc(50% + " dx "px)"))
    (set! (.. stamp -style -top) (str "calc(50% + " dy "px)"))
    (set! (.-className facts) "adsb-mayday-facts")
    (.appendChild facts (label-node "alt"))
    (.appendChild facts altitude-el)
    (.appendChild facts (label-node "v/s"))
    (.appendChild facts rate-el)
    (.appendChild stamp word-el)
    (.appendChild stamp callsign-el)
    (.appendChild stamp facts)
    (.appendChild el stamp)
    {:el el :word-el word-el :callsign-el callsign-el
     :altitude-el altitude-el :rate-el rate-el}))

(defn- update-stamp!
  "Refresh the stamp's DATA — and only its data. Feeder strings land as
  textContent (Boundary 4); nothing about the element's structure,
  position, or entrance is touched."
  [{:keys [word-el callsign-el altitude-el rate-el]} aircraft*]
  (set! (.-textContent word-el) (mayday-line aircraft*))
  (set! (.-textContent callsign-el) (display-name aircraft*))
  (set! (.-textContent altitude-el) (altitude-text aircraft*))
  (set! (.-textContent rate-el) (vertical-rate-text aircraft*)))

;; ---------------------------------------------------------------------
;; The edge arrow (out-of-view annotation)

(defn- on-arrow-click!
  "The arrow OFFERS: clicking fires the map's existing selection
  contract, nothing more. Module-level — no closure per element."
  [event]
  (when-let [icao (some-> (.-currentTarget event)
                          (.getAttribute "data-icao"))]
    (rf/dispatch [:aircraft/select icao])))

(defn arrow-element
  "A fresh edge-arrow annotation for the emergency `icao`: a button (it
  offers selection) carrying a bearing-rotated glyph, the callsign, and
  the distance. Returns {:el _ :glyph-el _ :callsign-el _ :distance-el _};
  update-arrow! refreshes the data nodes and the glyph's rotation."
  [icao]
  (let [el          (js/document.createElement "button")
        glyph-el    (js/document.createElementNS svg-ns "svg")
        head        (js/document.createElementNS svg-ns "path")
        callsign-el (span-node "adsb-edge-arrow-callsign")
        distance-el (span-node "adsb-edge-arrow-distance")]
    (set! (.-type el) "button")
    (set! (.-className el) "adsb-edge-arrow")
    (.setAttribute el "data-icao" icao)
    (.setAttribute el "data-testid" (str "edge-arrow:" icao))
    (.addEventListener el "click" on-arrow-click!)
    ;; setAttribute, not .-className: an SVG element's className is a
    ;; read-only SVGAnimatedString.
    (.setAttribute glyph-el "class" "adsb-edge-arrow-glyph")
    (.setAttribute glyph-el "viewBox" "0 0 16 16")
    (.setAttribute glyph-el "aria-hidden" "true")
    ;; A pen arrowhead pointing UP (bearing 0); rotation carries the rest.
    (.setAttribute head "d" "M8 1.5 L13 12.5 L8 9.6 L3 12.5 Z")
    (.appendChild glyph-el head)
    (.appendChild el glyph-el)
    (.appendChild el callsign-el)
    (.appendChild el distance-el)
    {:el el :glyph-el glyph-el :callsign-el callsign-el
     :distance-el distance-el}))

(defn- update-arrow!
  "Refresh the arrow's data: glyph rotation to the current great-circle
  bearing, callsign, distance, and the spoken sentence. Rotation is
  DATA here, not manner — the arrow points where the emergency is."
  [{:keys [el glyph-el callsign-el distance-el]} aircraft* edge]
  (let [name*    (display-name aircraft*)
        distance (distance-text (:edge/distance-m edge))]
    (set! (.. glyph-el -style -transform)
          (str "rotate(" (:edge/bearing-deg edge) "deg)"))
    (set! (.-textContent callsign-el) name*)
    (set! (.-textContent distance-el) distance)
    (.setAttribute el "aria-label"
                   (str "Emergency: " name* ", " distance
                        " off screen. Select aircraft."))))

(defn- chrome-insets-px
  "How far, in px, the arrow's centre must sit from each viewport edge
  to clear the frame, the chrome on it, and its own extent. The map is
  full-viewport (adsb.map.view), so the window size IS the canvas size
  — the one pixel fact this namespace allows itself to read."
  []
  (let [phone? (<= (.-innerWidth js/window) phone-max-width-px)]
    {:top    (+ notam-strip-px arrow-half-height-px edge-air-px)
     :right  (+ (if phone? 0 roster-px) arrow-half-width-px edge-air-px)
     :bottom (+ (if phone? roster-phone-rail-px 0) arrow-half-height-px edge-air-px)
     :left   (+ arrow-half-width-px edge-air-px)}))

(defn- edge->lng-lat
  "The arrow's pin: the pure edge fractions as a marker [lng lat] pulled
  inside the viewport past the chrome insets, so neither the frame nor
  the Stack nor the NOTAM strip ever clips or covers it."
  [{:geo/keys [min-lat max-lat min-lon max-lon]} {:edge/keys [x y]}]
  (let [{:keys [top right bottom left]} (chrome-insets-px)
        width  (max 1 (.-innerWidth js/window))
        height (max 1 (.-innerHeight js/window))
        x*     (-> x (max (/ left width)) (min (- 1 (/ right width))))
        y*     (-> y (max (/ top height)) (min (- 1 (/ bottom height))))]
    [(+ min-lon (* x* (- max-lon min-lon)))
     (- max-lat (* y* (- max-lat min-lat)))]))

;; ---------------------------------------------------------------------
;; Reconciliation

(defn- place!
  "Retire `entry` (if any) and stand up a fresh annotation for
  `aircraft*` in `mode` — a fresh element per appearance is what makes
  the ellipse's draw-in play exactly once per appearance."
  [m !state icao aircraft* mode lng-lat edge entry]
  (when entry
    (maplibre/remove-marker! m (:marker entry)))
  (let [nodes (if (= mode :arrow)
                (arrow-element icao)
                (mayday-element aircraft*))]
    (if (= mode :arrow)
      (update-arrow! nodes aircraft* edge)
      (update-stamp! nodes aircraft*))
    (swap! !state assoc-in [:entries icao]
           {:mode   mode
            :nodes  nodes
            :marker (maplibre/add-marker! m (:el nodes) lng-lat)})))

(defn- sync-one!
  "Reconcile one positioned emergency with the chart: in view it wears
  the ellipse+stamp at its position, out of view the edge arrow at the
  boundary. Same mode as last time -> the marker MOVES and its data
  refreshes (settled ink holds still); a mode change -> the old
  annotation lifts off and a fresh one draws in."
  [m !state viewport-bounds aircraft*]
  (let [icao    (:aircraft/icao aircraft*)
        {:geo/keys [lat lon]} (:aircraft/position aircraft*)
        edge    (geo/edge-annotation viewport-bounds
                                     (:aircraft/position aircraft*))
        mode    (if edge :arrow :ellipse)
        lng-lat (if edge (edge->lng-lat viewport-bounds edge) [lon lat])
        entry   (get-in @!state [:entries icao])]
    (if (= mode (:mode entry))
      (do (maplibre/move-marker! m (:marker entry) lng-lat)
          (if edge
            (update-arrow! (:nodes entry) aircraft* edge)
            (update-stamp! (:nodes entry) aircraft*)))
      (place! m !state icao aircraft* mode lng-lat edge entry))))

(defn- sync!
  "Reconcile every annotation with the current emergencies and viewport.
  Cleared (or position-less) emergencies lift off; the rest are judged
  in or out of view and annotated accordingly. The seam's `bounds` is
  read only when something is positioned and squawking — the calm sky
  costs nothing."
  [m !state emergencies]
  (let [positioned (filter :aircraft/position emergencies)
        live       (into #{} (map :aircraft/icao) positioned)]
    (doseq [[icao {:keys [marker]}] (:entries @!state)
            :when (not (contains? live icao))]
      (maplibre/remove-marker! m marker)
      (swap! !state update :entries dissoc icao))
    (when (seq positioned)
      (let [viewport-bounds (maplibre/bounds m)]
        (doseq [aircraft* positioned]
          (sync-one! m !state viewport-bounds aircraft*))))))

(defn attach!
  "Start the emergency annotations over map `m`: a track! on the same
  derived :aircraft/emergencies sub the NOTAM strip reads (one source
  of truth, never a second copy of the sky), re-judged on every settled
  camera move via the seam's `on-move!`. Outside any component, exactly
  like the ring and the aircraft layer. Returns a handle for `detach!`."
  [m]
  (let [!state (atom {:entries {} :emergencies nil
                      :track nil :disposed? false})]
    ;; The camera settled somewhere new: the same emergencies may now be
    ;; on the other side of the frame. The handler outlives detach! (the
    ;; map owns it and dies moments later); the disposed flag makes a
    ;; straggling moveend a no-op.
    (maplibre/on-move! m
                       (fn []
                         (let [{:keys [disposed? emergencies]} @!state]
                           (when-not disposed?
                             (sync! m !state emergencies)))))
    (swap! !state assoc :track
           (r/track!
             (fn []
               (let [emergencies @(rf/subscribe [:aircraft/emergencies])]
                 (swap! !state assoc :emergencies emergencies)
                 (sync! m !state emergencies)))))
    !state))

(defn detach!
  "Dispose the track and lift every annotation off the chart. Call
  before the map is destroyed — the view tears them down together."
  [m !state]
  (let [{:keys [track entries]} @!state]
    (swap! !state assoc :disposed? true :track nil :entries {})
    (when track
      (r/dispose! track))
    (doseq [[_icao {:keys [marker]}] entries]
      (maplibre/remove-marker! m marker))))
