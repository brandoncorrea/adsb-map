(ns adsb.map.style
  "The aircraft layer's visual contract, expressed as DATA.

  Every knob the map uses to draw a plane — the colour ramp, the sizes,
  the opacities, the icon ids, and the MapLibre style EXPRESSIONS built
  from them — lives here and nowhere else. Nothing in this namespace
  touches the DOM, a map, or a clock; it returns plain Clojure vectors
  and maps that `adsb.map.aircraft-layer` hands across the seam. That is
  the whole point: the styling is data-driven and runs on the GPU, and
  the future visual pass (adsb-dgb.5 / adsb-bvi.5) re-skins the map by
  editing the constants below — NOT by rewriting logic.

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

  ## The direction is NOT chosen

  There is no settled design direction yet (see AUTEUR.md, adsb-bvi.5).
  The palette below is deliberately functional: a legible warm->cool
  altitude ramp, an unmissable emergency red, a dim for stale. It is
  meant to READ, not to be final. Re-skin here."
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

;; ---------------------------------------------------------------------
;; Sizes, opacities — the scalar knobs. DATA: re-skin here.

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

;; ---------------------------------------------------------------------
;; Colours. DATA: the whole palette re-skins here.

(def ^:const ground-color
  "On the tarmac. An earthy khaki — reads \"not flying\" at a glance,
  distinct from both the airborne ramp and the unknown grey."
  "#a08a5b")

(def ^:const unknown-color
  "Positioned, but altitude never reported. A neutral cool grey — present
  and placed, but carrying no altitude meaning to colour."
  "#aab2bd")

(def ^:const emergency-color
  "7500 / 7600 / 7700. Unmissable red — overrides the altitude ramp
  entirely. A human being is having the worst day of their life."
  "#ff1e1e")

(def altitude-stops
  "Feet -> colour, warm(low) -> cool(high). A functional sequential ramp,
  NOT a final look (adsb-bvi.5). Edit these pairs to re-skin the airborne
  altitude legend; the interpolate expression is generated from them."
  [[0     "#feb24c"]    ; warm amber — down low
   [10000 "#fd8d3c"]    ; orange
   [18000 "#41b6c4"]    ; teal — the transition
   [30000 "#2c7fb8"]    ; blue
   [40000 "#253494"]])  ; deep blue — up high

(def ^:const halo-color
  "A dark outline so a pale icon survives a pale basemap." "#0b0f14")

(def ^:const halo-width 1.0)

;; ---------------------------------------------------------------------
;; Trails — the fading ribbon each aircraft leaves behind (adsb-6wd.1).
;; DATA: re-skin here. The trail is deliberately a single NEUTRAL grey with
;; a spatial alpha gradient, NOT the altitude ramp — history should read as
;; a quiet echo, never compete with the live target's altitude colour. The
;; design pass (adsb-bvi.5) may choose to recolour it to follow the ramp.

(def ^:const trail-rgb
  "The trail's colour as bare `r, g, b` channels — the cool unknown-grey
  family. Bare so the gradient can vary only the alpha around it." "170, 178, 189")

(def ^:const trail-width
  "Stroke width of the trail line, in px. Thinner than the icon so the
  ribbon supports the target without crowding it." 2.0)

(def ^:const trail-head-opacity
  "Alpha at the HEAD of the trail — the newest point, nearest the aircraft.
  The tail fades to fully transparent; this is the strongest the ribbon
  ever gets, kept below 1 so even the freshest history reads as secondary
  to the live icon." 0.7)

(defn- trail-rgba
  "A trail colour at `alpha` (0..1), as a MapLibre-legible rgba string."
  [alpha]
  (str "rgba(" trail-rgb ", " alpha ")"))

(defn trail-gradient-expression
  "The trail's fade, as a `line-gradient` over `line-progress` — MapLibre's
  0..1 position along the line. Because the ring is oldest-first, progress 0
  is the tail (fully transparent) and progress 1 the head (`trail-head-
  opacity`), so the ribbon dissolves behind the aircraft. `line-gradient`
  needs `lineMetrics true` on the source (set where the source is defined);
  the same expression applies to every trail, which is exactly the uniform
  tail-to-head fade we want."
  []
  ["interpolate" ["linear"] ["line-progress"]
   0.0 (trail-rgba 0)
   1.0 (trail-rgba trail-head-opacity)])

;; ---------------------------------------------------------------------
;; Expressions — pure functions returning MapLibre expression vectors.
;; These are the styling contract the layer paints with; the style_test
;; asserts their shape directly, because they ARE data.

(defn altitude-color-expression
  "The three-state altitude colour, as a `case`. Absent altitude (the
  property missing) takes the neutral unknown treatment — guarded by
  `[\"has\" \"altitude\"]` because absent is missing, not zero. The string
  \"ground\" takes the ground treatment. Everything else is a number and
  ramps through `altitude-stops`. `interpolate` never sees a non-number:
  the two guards run before it."
  []
  ["case"
   ["!" ["has" "altitude"]]            unknown-color
   ["==" ["get" "altitude"] "ground"]  ground-color
   (into ["interpolate" ["linear"] ["get" "altitude"]]
         (mapcat identity altitude-stops))])

(defn icon-color-expression
  "Emergency beats altitude, full stop — a squawking aircraft is red no
  matter how high it is. Otherwise the altitude ramp."
  []
  ["case"
   ["get" "emergency"] emergency-color
   (altitude-color-expression)])

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

(defn aircraft-layer-spec
  "The complete MapLibre symbol-layer spec for the aircraft `layer-id`
  over `source-id`, built entirely from the constants and expressions
  above. A symbol layer, not a circle: the icon is a plane silhouette
  rotated to `track`, coloured by the altitude ramp (emergency
  overriding), sized up for emergencies, and faded when stale.

  `icon-rotate` is a bare `[\"get\" \"track\"]`: MapLibre defaults a null
  rotation to 0, and a null track already draws the rotation-agnostic dot
  (see `icon-image-expression`), so the fallback rotation is a harmless
  no-op on a symmetric shape. `icon-rotation-alignment \"map\"` pins the
  heading to the ground, not the screen — the whole point of a track.

  `icon-allow-overlap` / `icon-ignore-placement` are true so no aircraft
  is ever silently dropped from a dense sky by label collision — every
  target the feeder positioned must appear."
  [layer-id source-id]
  {:id     layer-id
   :type   "symbol"
   :source source-id
   :layout {:icon-image              (icon-image-expression)
            :icon-rotate             ["get" "track"]
            :icon-rotation-alignment "map"
            :icon-size               (icon-size-expression)
            :icon-allow-overlap      true
            :icon-ignore-placement   true}
   :paint  {:icon-color      (icon-color-expression)
            :icon-opacity    (icon-opacity-expression)
            :icon-halo-color halo-color
            :icon-halo-width halo-width}})

(defn trail-layer-spec
  "The MapLibre LINE-layer spec for the aircraft trails on `layer-id` over
  `source-id`. One LineString per aircraft (adsb.trails), stroked at
  `trail-width`, faded tail-to-head by `trail-gradient-expression`. Rounded
  cap and join so a turning trail bends cleanly. The layer must be added
  BELOW the aircraft symbol layer so the ribbon sits under the target, and
  its source must carry `lineMetrics true` for `line-gradient` to resolve."
  [layer-id source-id]
  {:id     layer-id
   :type   "line"
   :source source-id
   :layout {:line-cap  "round"
            :line-join "round"}
   :paint  {:line-width    trail-width
            :line-gradient (trail-gradient-expression)}})
