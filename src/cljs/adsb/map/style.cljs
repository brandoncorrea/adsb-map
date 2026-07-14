(ns adsb.map.style
  "The aircraft layer's visual contract, expressed as DATA.

  Every knob the map uses to draw a plane — the colour ramps, the sizes,
  the opacities, the icon ids, and the MapLibre style EXPRESSIONS built
  from them — lives here and nowhere else. Nothing in this namespace
  touches the DOM, a map, or a clock; it returns plain Clojure vectors
  and maps that `adsb.map.aircraft-layer` hands across the seam. That is
  the whole point: the styling is data-driven and runs on the GPU, and a
  re-skin edits the palette data below — NOT the logic.

  ## Why expressions and not per-feature code

  Hundreds of aircraft update at 1 Hz. If a colour or a rotation were
  computed in JS per feature per frame, the map would crawl. MapLibre
  evaluates these expressions on the GPU against each feature's
  properties (adsb.geo puts them there): `track`, `altitude`,
  `emergency`, `stale`, `category`. We describe the styling once;
  MapLibre draws it four hundred times a frame for free.

  ## The channels, and what each one is allowed to say

  Every visual channel carries exactly ONE fact, and they compose rather
  than compete — a rule worth stating because the temptation to reuse a
  free-looking channel is constant:

    colour      altitude, precisely (emergency red overriding)
    size        altitude, instinctively — perspective (emergency 1.6x
                overriding, mlat demoting)
    rotation    track
    opacity     age
    SILHOUETTE  what the aircraft SAYS IT IS — its emitter category
                (adsb-rnp). A helicopter is not a small airliner, and it
                must not be drawn as one; but neither may it be drawn
                small, because small already means high.

  ## The three altitude states — the load-bearing subtlety

  `altitude` arrives in THREE shapes and each means something different:

    * a NUMBER (feet)      — airborne; ramps warm(low) -> cool(high)
    * the string \"ground\"  — on the tarmac; its own earthy treatment
    * ABSENT (missing)     — heard, positioned, altitude unknown; neutral

  `interpolate` is numeric-only: hand it \"ground\" or a missing value and
  it errors. So the altitude colour is a `case` that peels the two
  non-numeric states off FIRST — the absent case guarded by `[\"has\"
  \"altitude\"]`, since absent means the property is genuinely missing,
  not zero — and only then interpolates the number.

  ## Two printed editions — the direction IS chosen

  The settled direction (docs/design-direction.md, 'The Sectional, Day &
  Night') prints the chart twice: a day edition on warm paper and a
  designed night edition on dark stock — never an invert. So every
  edition-dependent colour lives in `palettes`, keyed `:day`/`:night`,
  and every expression builder takes the theme. The hue RELATIONSHIPS
  are the identity (warm-low -> cool-high through chart inks, emergency
  red overriding everything, halo the paper of its own edition); each
  edition renders them on its own paper. Sizes, opacities, and the
  staleness fade are edition-free semantics and stay plain constants.

  The legend (adsb.ui.legend) paints its swatches from this SAME data,
  per theme, so the key and the map can never disagree."
  (:require [adsb.aircraft :as aircraft]))

;; ---------------------------------------------------------------------
;; Icon assets — the silhouettes the symbol layer chooses between. All are
;; registered as SDF images (adsb.map.aircraft-layer draws and adds them),
;; which is what lets `icon-color` tint them per feature.

(def ^:const plane-icon-id
  "The generic airframe, rotated to the aircraft's track. The fallback for
  an absent or unmodelled category, and so the majority of targets."
  "aircraft-plane")

(def ^:const heavy-icon-id
  "The wide-body: A4 (high-vortex large) and A5 (heavy). Broader span,
  fatter fuselage, bigger tailplane than the generic plane."
  "aircraft-heavy")

(def ^:const light-icon-id
  "Light GA (A1), and the gliders and ultralights of set B. Straight
  unswept wings and a thin fuselage — the shape reads GA at a glance,
  where merely DRAWING IT SMALLER would not: size is the altitude
  channel (`perspective-size-stops`) and cannot be borrowed to say
  anything else."
  "aircraft-light")

(def ^:const rotorcraft-icon-id
  "The rotorcraft (A7). The one that matters most: a helicopter's flight
  profile — hovering, low, slow — is unlike anything else on the chart,
  and drawn as an airliner it is a worse lie than the dot exists to avoid."
  "aircraft-rotorcraft")

(def ^:const vehicle-icon-id
  "The surface vehicle: C1 (emergency) and C2 (service) — a fire tender or
  a pushback tug on the apron, not an aircraft at all. Distinct from
  `ground-icon-size`, which is about being ON the ground: an airliner at
  the gate is on the ground and still an airliner, while a tender is a
  vehicle wherever it is."
  "aircraft-vehicle")

(def ^:const dot-icon-id
  "The non-directional disc, shown when track is unknown — a plane
  pointing a direction we cannot vouch for would be a lie. It OUTRANKS
  category: heading absence beats category presence, so a rotorcraft with
  no track is a dot, not a rotorcraft (see `icon-image-expression`)."
  "aircraft-dot")

(def category->icon-id
  "The symbology, as DATA: emitter category (adsb.schema/emitter-category,
  arriving as the `category` feature property) -> the silhouette that
  tells the truth about it. Re-skin here; a category absent from this map
  takes `plane-icon-id`, so this need only name what it changes.

  Deliberately SMALL. A symbology nobody can read at a glance is worse
  than one silhouette, so this maps the distinctions a reader can actually
  make on a moving chart and leaves the rest generic — A2 (small) and A3
  (large) are the airliners and regionals the plane already draws
  honestly, and A6 (high-performance) is too rare here to earn a shape."
  {"A1" light-icon-id        ; light — GA in the pattern
   "A4" heavy-icon-id        ; high-vortex large (the 757)
   "A5" heavy-icon-id        ; heavy
   "A7" rotorcraft-icon-id   ; rotorcraft
   "B1" light-icon-id        ; glider / sailplane
   "B4" light-icon-id        ; ultralight / hang-glider / paraglider
   "C1" vehicle-icon-id      ; surface vehicle — emergency
   "C2" vehicle-icon-id})    ; surface vehicle — service

;; ---------------------------------------------------------------------
;; Sizes, opacities — the scalar knobs. Edition-free: both prints share
;; them. DATA: re-skin here.
;;
;; SIZE IS THE INSTINCT-ALTITUDE CHANNEL (adsb-dgb.12, Overseer pick):
;; low is near is LARGE, high is far is small — perspective, the oldest
;; depth cue there is. Colour stays the PRECISE altitude channel; size
;; carries the glance. The alternatives examined and rejected are
;; recorded on adsb-dgb.8/.12: the cast shadow (busy at density,
;; near-invisible on dark stock) and the drop-line tether (verticals
;; re-crowded the cluster). Perspective needs no ink at all, so it is
;; identical in both editions — and it CALMS the dense sky, because the
;; cruise-level crowd is what shrinks.

(def ^:const base-icon-size
  "Icon-size multiplier for an aircraft whose altitude is unknown — the
  neutral middle of the perspective ramp, making no height claim."
  0.9)

(def ^:const emergency-icon-size
  "Icon-size multiplier for an aircraft squawking distress — larger so it
  cannot be missed in a crowd. Overrides perspective entirely: a plane in
  distress is never allowed to look far away." 1.6)

(def ^:const ground-icon-size
  "Icon-size multiplier on the tarmac — deliberately BENEATH the whole
  perspective ramp, though pure perspective would argue otherwise: a
  hub's apron holds dozens of parked and taxiing aircraft, the least
  interesting targets on the chart, and they must never out-draw
  anything that is flying." 0.5)

(def ^:const perspective-size-stops
  "The instinct-altitude ramp: icon-size by feet, low = near = LARGE,
  high = far = small. Front-loaded like the colour ramp — the sky's
  drama (approach, departure, pattern work) lives under 10,000 ft, so
  most of the shrink happens above that line."
  [[0 1.25] [10000 1.0] [40000 0.55]])

(def ^:const mlat-size-factor
  "Multiplier on a multilaterated position's perspective size — the quiet
  lower-confidence demotion, COMPOSED with the altitude size rather than
  replacing it (an mlat fix at 3,000 ft must still read nearer than an
  ADS-B target at FL400). Approximately the pre-perspective 0.7/0.9
  demotion ratio."
  0.78)

(def ^:const base-opacity 1.0)

(def ^:const stale-opacity
  "A representative opacity for a stale, fading aircraft — the single
  value the legend paints its \"Stale (fading)\" swatch with, since a
  legend key shows one exemplar, not the continuous ramp. The LIVE map
  fade is continuous (see `icon-opacity-expression`): full at the stale
  line, `aged-out-opacity` at the age-out line, with this sitting between."
  0.3)

(def ^:const aged-out-opacity
  "The opacity an aging aircraft reaches at the age-out line — nearly
  gone, the last frame before the client drops it from the picture. The
  fade interpolates from `base-opacity` at the stale line down to this."
  0.2)

;; The staleness fade is bounded by the SAME thresholds the domain judges
;; staleness and age-out against (adsb.aircraft) — the two sides can never
;; disagree about where the fade begins or ends. adsb.aircraft speaks in
;; milliseconds; the `age-s` feature property (adsb.geo) is in seconds, so
;; the interpolation stops are the thresholds in seconds.

(def ^:const stale-threshold-s
  "Seconds of silence at which the fade begins — the domain's stale line."
  (/ aircraft/stale-threshold-ms 1000))

(def ^:const age-out-threshold-s
  "Seconds of silence at which the aircraft leaves the picture — the fade
  bottoms out here, then the client drops the feature."
  (/ aircraft/age-out-threshold-ms 1000))

(def ^:const halo-width
  "The hairline paper-coloured halo that lets an ink glyph survive a busy
  chart area (design-direction §4). Width is shared; the COLOUR is the
  paper of each edition, in `palettes`." 1.0)

(def ^:const trail-width
  "Stroke width of the trail line, in px. Thinner than the icon so the
  ribbon supports the target without crowding it." 2.0)

(def ^:const trail-head-opacity
  "Alpha at the HEAD of the trail — the newest point, nearest the aircraft.
  The tail fades to fully transparent; this is the strongest the ribbon
  ever gets. The direction caps it at 0.5 on paper (§2): history is a
  quiet ink echo, never a rival to the live target." 0.5)

(def ^:const crop-width
  "Stroke width of the published-coverage boundary, in px." 2.0)

(def ^:const crop-opacity
  "Alpha of the coverage boundary. Quieter than a trail: this is a
  MARGIN NOTE, not a thing in the sky. It must be findable when looked
  for and invisible when not." 0.30)

(def ^:const crop-dasharray
  "The boundary is DASHED, and that is semantic rather than decorative.
  A solid line on a chart is a thing that exists — a coastline, an
  airway. This is an editorial limit: the edge of what we chose to
  publish (adsb.ingest.crop). Chart convention gives that a dashed rule,
  and it also keeps the ring from reading as an airspace boundary, which
  it emphatically is not."
  [4 3])

;; ---------------------------------------------------------------------
;; The two editions. DATA: the whole aircraft palette re-skins here.
;; Same keys, same feet, same semantics in both — two prints of one
;; plate (docs/design-direction.md §2). Trails are the edition's ink as
;; bare `r, g, b` channels so the gradient can vary only the alpha.

(def palettes
  {:day
   {:ground-color    "#8A8374"   ; on the tarmac — dusty field khaki
    :unknown-color   "#9A937F"   ; positioned, altitude never reported
    :emergency-color "#CE2029"   ; red pen; overrides everything
    :halo-color      "#F5EFDF"   ; the day paper itself
    :trail-rgb       "44, 42, 36" ; day ink #2C2A24
    :altitude-stops  [[0     "#A0622D"]    ; sienna — on the deck
                      [10000 "#C2447C"]    ; aviation magenta
                      [20000 "#7A4F86"]    ; plum — the transition
                      [30000 "#3D5E8C"]    ; aero blue
                      [40000 "#2A3F66"]]}  ; deep ink blue — up high
   :night
   {:ground-color    "#6E7686"   ; tarmac grey-blue on dark stock
    :unknown-color   "#7C8494"   ; neutral, carrying no altitude meaning
    :emergency-color "#FF5A4D"   ; red pen re-inked to carry on dark
    :halo-color      "#151B26"   ; the night paper itself
    :trail-rgb       "233, 226, 206" ; night ink #E9E2CE
    :altitude-stops  [[0     "#C98A54"]    ; lamplit sienna
                      [10000 "#E06A9F"]    ; night-print magenta
                      [20000 "#A98BC4"]    ; lifted plum
                      [30000 "#7FA3D4"]    ; night aero blue
                      [40000 "#5F7FB8"]]}}) ; high blue, still legible

(defn palette
  "The edition's aircraft palette. Unrecognized themes read the day
  edition — a chart is always on the table."
  [theme]
  (get palettes theme (:day palettes)))

(defn- trail-rgba
  "The edition's trail ink at `alpha` (0..1), as a MapLibre-legible rgba
  string."
  [theme alpha]
  (str "rgba(" (:trail-rgb (palette theme)) ", " alpha ")"))

(defn trail-gradient-expression
  "The trail's fade, as a `line-gradient` over `line-progress` — MapLibre's
  0..1 position along the line. Because the ring is oldest-first, progress 0
  is the tail (fully transparent) and progress 1 the head (`trail-head-
  opacity`), so the ribbon dissolves behind the aircraft. `line-gradient`
  needs `lineMetrics true` on the source (set where the source is defined);
  the same expression applies to every trail, which is exactly the uniform
  tail-to-head fade we want."
  [theme]
  ["interpolate" ["linear"] ["line-progress"]
   0.0 (trail-rgba theme 0)
   1.0 (trail-rgba theme trail-head-opacity)])

;; ---------------------------------------------------------------------
;; Expressions — pure functions returning MapLibre expression vectors.
;; These are the styling contract the layer paints with; the style_test
;; asserts their shape directly, because they ARE data.

(defn altitude-color-expression
  "The three-state altitude colour for `theme`, as a `case`. Absent
  altitude (the property missing) takes the neutral unknown treatment —
  guarded by `[\"has\" \"altitude\"]` because absent is missing, not zero.
  The string \"ground\" takes the ground treatment. Everything else is a
  number and ramps through the edition's altitude stops. `interpolate`
  never sees a non-number: the two guards run before it."
  [theme]
  (let [{:keys [unknown-color ground-color altitude-stops]} (palette theme)]
    ["case"
     ["!" ["has" "altitude"]]            unknown-color
     ["==" ["get" "altitude"] "ground"]  ground-color
     (into ["interpolate" ["linear"] ["get" "altitude"]]
           (mapcat identity altitude-stops))]))

(defn icon-color-expression
  "Emergency beats altitude, full stop — a squawking aircraft is red no
  matter how high it is. Otherwise the altitude ramp."
  [theme]
  ["case"
   ["get" "emergency"] (:emergency-color (palette theme))
   (altitude-color-expression theme)])

(defn perspective-size-expression
  "The instinct-altitude size (adsb-dgb.12): the three altitude states
  peeled apart exactly as the colour expression peels them — absent
  altitude takes the neutral base (no height claim), \"ground\" tucks
  beneath the ramp, and a numeric altitude interpolates low-large to
  high-small through `perspective-size-stops`."
  []
  ["case"
   ["!" ["has" "altitude"]]           base-icon-size
   ["==" ["get" "altitude"] "ground"] ground-icon-size
   (into ["interpolate" ["linear"] ["get" "altitude"]]
         (mapcat identity perspective-size-stops))])

(defn icon-size-expression
  "Emergency wins first and absolutely — a plane in distress draws
  largest no matter its altitude, and a squawking mlat target must not
  be shrunk. Everyone else takes the perspective-altitude size, times
  the mlat demotion factor when the position is multilaterated —
  MULTIPLIED, not substituted, so lower confidence reads as a touch
  smaller at every altitude without stealing the altitude channel.
  `[\"get\" \"mlat\"]` is null when absent, which `case` reads as false,
  so a plain ADS-B target keeps factor 1."
  []
  ["case"
   ["get" "emergency"] emergency-icon-size
   ["*"
    ["case" ["get" "mlat"] mlat-size-factor 1.0]
    (perspective-size-expression)]])

(defn icon-opacity-expression
  "Silent aircraft fade CONTINUOUSLY as their age grows, not in one step:
  full opacity while fresh, then interpolating down from the stale line
  (`stale-threshold-s`) to `aged-out-opacity` at the age-out line, where
  the client drops the feature entirely. `interpolate` clamps below its
  first stop (fresh aircraft stay full) and above its last (nothing older
  than age-out survives to be drawn). `age-s` is absent until an aircraft
  carries a receive time, so a `case` guards the interpolate — an
  un-judged aircraft stays full opacity, and `interpolate` never sees a
  null."
  []
  ["case"
   ["has" "age-s"]
   ["interpolate" ["linear"] ["get" "age-s"]
    stale-threshold-s   base-opacity
    age-out-threshold-s aged-out-opacity]
   base-opacity])

(defn icon-image-expression
  "Choose the silhouette per feature, in strict precedence:

    1. NO TRACK -> the dot, whatever the aircraft claims to be. Heading
       absence beats category presence: a rotorcraft we cannot point is a
       dot, because a symbol rotated to a heading we do not have is a lie,
       and it is the SAME lie regardless of which silhouette tells it.
    2. NO CATEGORY -> the generic plane. Absent means the property is
       genuinely missing (the aircraft never transmitted one, or the
       ingest boundary refused what it did), so `[\"has\" \"category\"]`
       guards it exactly as `[\"has\" \"altitude\"]` guards the colour ramp —
       and no aircraft ever goes undrawn for want of a classification.
    3. Otherwise match `category->icon-id`, generic plane as the fallback
       for the categories it does not name.

  This is the ONLY channel category drives. Colour still comes from
  altitude, size from the perspective ramp, the 1.6x from emergency, the
  demotion from mlat, the fade from age — category COMPOSES with every one
  of them and replaces none."
  []
  ["case"
   ["!" ["has" "track"]]    dot-icon-id
   ["!" ["has" "category"]] plane-icon-id
   (into ["match" ["get" "category"]]
         (concat (mapcat identity category->icon-id)
                 [plane-icon-id]))])

(defn aircraft-layer-spec
  "The complete MapLibre symbol-layer spec for the aircraft `layer-id`
  over `source-id`, printed in `theme`'s edition and built entirely from
  the palette data and expressions above. A symbol layer, not a circle:
  the icon is the silhouette its emitter category earns it
  (`icon-image-expression`), rotated to `track`, coloured by the altitude
  ramp (emergency overriding), sized up for emergencies, and faded when
  stale. The halo is the edition's own paper, so ink survives a busy chart
  area without a foreign outline colour.

  `icon-rotate` is a bare `[\"get\" \"track\"]` and stays that way even now
  that a surface vehicle can be drawn: MapLibre defaults a null rotation
  to 0, and a null track already draws the rotation-agnostic dot (see
  `icon-image-expression`), so the fallback rotation is a harmless no-op.
  EVERY directional silhouette is rotated by the same rule — no symbol
  gets a private exemption, so there is exactly one story about heading on
  this chart: if we have it, the symbol points; if we do not, it is a dot.
  `icon-rotation-alignment \"map\"` pins the heading to the ground, not the
  screen — the whole point of a track.

  `icon-allow-overlap` / `icon-ignore-placement` are true so no aircraft
  is ever silently dropped from a dense sky by label collision — every
  target the feeder positioned must appear."
  [theme layer-id source-id]
  {:id     layer-id
   :type   "symbol"
   :source source-id
   :layout {:icon-image              (icon-image-expression)
            :icon-rotate             ["get" "track"]
            :icon-rotation-alignment "map"
            :icon-size               (icon-size-expression)
            :icon-allow-overlap      true
            :icon-ignore-placement   true}
   :paint  {:icon-color      (icon-color-expression theme)
            :icon-opacity    (icon-opacity-expression)
            :icon-halo-color (:halo-color (palette theme))
            :icon-halo-width halo-width}})

(defn trail-layer-spec
  "The MapLibre LINE-layer spec for the aircraft trails on `layer-id` over
  `source-id`, printed in `theme`'s edition. One LineString per aircraft
  (adsb.trails), stroked at `trail-width`, faded tail-to-head by
  `trail-gradient-expression`. Rounded cap and join so a turning trail
  bends cleanly. The layer must be added BELOW the aircraft symbol layer
  so the ribbon sits under the target, and its source must carry
  `lineMetrics true` for `line-gradient` to resolve."
  [theme layer-id source-id]
  {:id     layer-id
   :type   "line"
   :source source-id
   :layout {:line-cap  "round"
            :line-join "round"}
   :paint  {:line-width    trail-width
            :line-gradient (trail-gradient-expression theme)}})

(defn crop-layer-spec
  "The MapLibre LINE-layer spec for the published-coverage boundary on
  `layer-id` over `source-id`, printed in `theme`'s edition: the edge of
  the disc this deployment publishes (adsb.ingest.crop).

  A LINE layer over a Polygon ring, not MapLibre's `circle` type, because
  a circle layer's radius is in SCREEN PIXELS — it would swell and shrink
  against the ground as the reader zooms, which is exactly wrong for a
  boundary whose whole meaning is a fixed distance on the earth
  (adsb.geo/circle builds the ring).

  It goes in FIRST, below the trails and the targets: the boundary is the
  paper the chart is printed on, and nothing in the sky should ever have
  to compete with it."
  [theme layer-id source-id]
  {:id     layer-id
   :type   "line"
   :source source-id
   :layout {:line-cap  "butt"
            :line-join "round"}
   :paint  {:line-width     crop-width
            :line-color     (trail-rgba theme crop-opacity)
            :line-dasharray crop-dasharray}})
