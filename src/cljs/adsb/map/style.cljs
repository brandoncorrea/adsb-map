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
  `emergency`, `stale`. We describe the styling once; MapLibre draws it
  four hundred times a frame for free.

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
;; Icon assets — the two silhouettes the symbol layer chooses between.
;; Both are registered as SDF images (adsb.map.aircraft-layer draws and
;; adds them), which is what lets `icon-color` tint them per feature.

(def ^:const plane-icon-id
  "The directional plane silhouette, rotated to the aircraft's track."
  "aircraft-plane")

(def ^:const dot-icon-id
  "The non-directional disc, shown when track is unknown — a plane
  pointing a direction we cannot vouch for would be a lie."
  "aircraft-dot")

(def ^:const shadow-plane-icon-id
  "The pre-softened plane silhouette the cast-shadow layer prints. A
  SEPARATE image from the crisp plane: the plane icons are hard-alpha
  masks, and MapLibre's SDF halo/blur machinery needs an alpha GRADIENT
  to soften into — so the shadow silhouette is drawn blurred (a
  pseudo-distance-field) and registered SDF, which is what lets
  `icon-halo-blur` deepen the penumbra with altitude."
  "aircraft-shadow-plane")

(def ^:const shadow-dot-icon-id
  "The pre-softened disc — the shadow of a track-less target."
  "aircraft-shadow-dot")

;; ---------------------------------------------------------------------
;; Sizes, opacities — the scalar knobs. Edition-free: both prints share
;; them. DATA: re-skin here.

(def ^:const base-icon-size
  "Icon-size multiplier for an ordinary aircraft." 0.9)

(def ^:const emergency-icon-size
  "Icon-size multiplier for an aircraft squawking distress — larger so it
  cannot be missed in a crowd." 1.6)

(def ^:const mlat-icon-size
  "Icon-size multiplier for a multilaterated position — a touch smaller
  than a self-reporting target, a quiet visual demotion for the lower
  confidence of an mlat fix." 0.7)

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

(def ^:const shadows-enabled?
  "The cast-shadow prototype's toggle (design-direction §8, adsb-dgb.8).
  True prints the shadow layer under the aircraft; false withholds it
  without touching any other wiring — the visual pass flips this one
  constant to accept or reject the invention."
  true)

(def ^:const shadow-softness-stops
  "SDF halo width/blur (px) by altitude — the penumbra deepens as the
  aircraft climbs, so a high shadow reads soft and a low one crisp.
  Edition-free: softness is geometry, not ink."
  [[0 0.2] [40000 2.0]])

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
    ;; The cast shadow (§8): the day chart's own ink, thrown at low alpha
    ;; onto warm paper. Alpha FALLS as altitude rises — a high sun-shadow
    ;; is fainter and softer, never a competing glyph.
    :shadow-ink      "#2C2A24"
    :shadow-opacity-stops [[0 0.30] [40000 0.16]]
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
    ;; Re-reasoned for dark stock, NOT inherited: night ink (#E9E2CE) is
    ;; light, and a light shadow is a glow — a lie about the sun. The
    ;; night shadow is a deeper black-blue than the paper itself (a
    ;; shadow can only darken), pushed to higher alpha because dark-on-
    ;; dark needs more presence to read at all.
    :shadow-ink      "#05080F"
    :shadow-opacity-stops [[0 0.55] [40000 0.32]]
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

(defn icon-size-expression
  "Emergency aircraft draw largest; a multilaterated (lower-confidence)
  position draws a little smaller as a quiet demotion; everyone else is
  the base size. Emergency wins first — a squawking mlat target is still
  an emergency and must not be shrunk. `[\"get\" \"mlat\"]` is null when
  absent, which `case` reads as false, so a plain ADS-B target is base."
  []
  ["case"
   ["get" "emergency"] emergency-icon-size
   ["get" "mlat"]      mlat-icon-size
   base-icon-size])

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
  "Choose the silhouette per feature: the directional plane when a track
  is known, the non-directional dot when it is not. `[\"has\" \"track\"]`
  because absent track means unknown heading, and a rotated plane would
  imply a heading we do not have."
  []
  ["case" ["has" "track"] plane-icon-id dot-icon-id])

;; ---------------------------------------------------------------------
;; The cast shadow (design-direction §8, adsb-dgb.8) — altitude's second
;; channel: instinct. A second symbol layer UNDER the aircraft, printing
;; the pre-softened silhouette in shadow ink, displaced SE by altitude.
;;
;; Technique note (the experiment's finding): `icon-translate` cannot
;; carry this — it is a constant paint property, never data-driven in
;; MapLibre (4.7 included). `icon-offset` IS data-driven, but it lives in
;; the icon's rotated frame, so the fixed NW sun requires each feature's
;; offset to be counter-rotated by its track — trig no style expression
;; can do. adsb.geo/shadow-offset therefore computes the finished [dx dy]
;; per feature, and the expressions here only read it back. Blur rides
;; the SDF halo over a PRE-BLURRED shadow image (the crisp plane's alpha
;; is hard — an SDF halo has nothing to soften into), so the penumbra can
;; still deepen continuously with altitude.

(defn shadow-icon-image-expression
  "The soft shadow silhouette per feature, mirroring the aircraft layer's
  choice: the directional shadow when a track is known, the soft disc
  when it is not."
  []
  ["case" ["has" "track"] shadow-plane-icon-id shadow-dot-icon-id])

(defn shadow-offset-expression
  "Read the per-feature `[dx dy]` computed by adsb.geo/shadow-offset,
  asserted to the two-number array `icon-offset` demands. Every feature
  the shadow layer draws carries the property — the layer's
  `[\"has\" \"shadow-offset\"]` filter guarantees it — so the assertion
  can never see a missing value."
  []
  ["array" "number" 2 ["get" "shadow-offset"]])

(defn shadow-opacity-expression
  "The shadow's alpha: the edition's altitude-falling base (a high shadow
  is fainter) MULTIPLIED by the same continuous age fade the aircraft
  wears — a stale plane's shadow fades with the plane, never outliving
  it. `[\"get\" \"altitude\"]` is safely numeric here: only a numeric
  altitude ever earns a `shadow-offset` (adsb.geo), and the layer filter
  admits nothing else."
  [theme]
  ["*"
   (into ["interpolate" ["linear"] ["get" "altitude"]]
         (mapcat identity (:shadow-opacity-stops (palette theme))))
   (icon-opacity-expression)])

(defn shadow-softness-expression
  "Halo width/blur by altitude — the penumbra grows as the plane climbs."
  []
  (into ["interpolate" ["linear"] ["get" "altitude"]]
        (mapcat identity shadow-softness-stops)))

(defn shadow-layer-spec
  "The complete MapLibre symbol-layer spec for the cast-shadow `layer-id`
  over `source-id` (the SAME GeoJSON source as the aircraft — no second
  setData, no second conversion), printed in `theme`'s shadow ink. Add it
  BELOW the aircraft layer: the shadow lies on the paper; the plane flies
  over it.

  The filter keeps the semantics honest at the layer boundary: only
  features carrying `shadow-offset` — i.e. only numeric altitudes — are
  drawn at all. On-ground and altitude-unknown aircraft cast NOTHING
  (absent is not zero). Rotation, size, and overlap mirror the aircraft
  layer exactly, so the shadow is the plane's true silhouette: same
  track, same emergency enlargement, and `icon-offset` (which MapLibre
  multiplies by `icon-size`) throws a big glyph's shadow proportionally
  further."
  [theme layer-id source-id]
  (let [ink (:shadow-ink (palette theme))]
    {:id     layer-id
     :type   "symbol"
     :source source-id
     :filter ["has" "shadow-offset"]
     :layout {:icon-image              (shadow-icon-image-expression)
              :icon-rotate             ["get" "track"]
              :icon-rotation-alignment "map"
              :icon-size               (icon-size-expression)
              :icon-offset             (shadow-offset-expression)
              :icon-allow-overlap      true
              :icon-ignore-placement   true}
     :paint  {:icon-color      ink
              :icon-opacity    (shadow-opacity-expression theme)
              :icon-halo-color ink
              :icon-halo-width (shadow-softness-expression)
              :icon-halo-blur  (shadow-softness-expression)}}))

(defn aircraft-layer-spec
  "The complete MapLibre symbol-layer spec for the aircraft `layer-id`
  over `source-id`, printed in `theme`'s edition and built entirely from
  the palette data and expressions above. A symbol layer, not a circle:
  the icon is a plane silhouette rotated to `track`, coloured by the
  altitude ramp (emergency overriding), sized up for emergencies, and
  faded when stale. The halo is the edition's own paper, so ink survives
  a busy chart area without a foreign outline colour.

  `icon-rotate` is a bare `[\"get\" \"track\"]`: MapLibre defaults a null
  rotation to 0, and a null track already draws the rotation-agnostic dot
  (see `icon-image-expression`), so the fallback rotation is a harmless
  no-op on a symmetric shape. `icon-rotation-alignment \"map\"` pins the
  heading to the ground, not the screen — the whole point of a track.

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
