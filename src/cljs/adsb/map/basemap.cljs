(ns adsb.map.basemap
  "The chart plate — OpenFreeMap's Liberty style, re-inked into the two
  printed editions of The Sectional (docs/design-direction.md §2–3).

  ## What this is

  A PURE transform over a MapLibre style JSON (as Clojure data): take
  Liberty as fetched, recolor its layers into an edition's palette, hand
  the result to the map. The PROVIDER stays settled (OpenFreeMap, no
  token, attribution carried by the style's own sources); the direction
  customizes the STYLE, not the provider. Nothing here touches the
  network or the DOM — adsb.map.view fetches the style once and prints
  it through this namespace per theme.

  ## Why a transform and not a hosted variant

  OpenFreeMap does host dark styles (`/styles/dark`, `/styles/fiord`),
  but they are generic web-map darks — a night edition must be OUR
  artifact: the §2 palette, hypsometric tints and chart inks re-reasoned
  for dark stock, not someone else's dashboard theme. Deriving both
  editions from ONE upstream style also guarantees the two prints share
  a plate: same layers, same data, different ink.

  Sustainable path (recorded for adsb-dgb.5): keep this category-keyed
  transform — it targets `source-layer` + layer type, not Liberty's 111
  layer ids, so upstream drift degrades gracefully (an unrecognized
  layer keeps Liberty's own paint rather than breaking). If Liberty ever
  moves under us, the alternative is vendoring a build-time-generated
  pair of style JSONs produced by this same palette map; the palette
  stays the single source of truth either way.

  ## How layers are categorized

  Liberty's layers all carry a `source-layer` (water, waterway,
  landcover, landuse, park, transportation, building, boundary, place,
  poi, aerodrome_label, ...) and a type. That pair, plus a couple of id
  idioms (`casing`, `rail`, `shield`), is the whole taxonomy — see
  `recolor-layer`. Decorative sprite work that cannot be re-inked
  (highway shields, pattern fills) is HIDDEN in the night edition rather
  than glaring in daylight colours; the day edition keeps it."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------
;; The basemap palettes. DATA: the chart re-inks here. Keys are roles,
;; values are the §2 palette rendered for each paper. Aircraft inks live
;; in adsb.map.style; chrome inks in app.css — same table, three media.

(def editions
  {:day
   {:paper         "#F5EFDF"              ; the warm chart paper
    :terrain-1     "#E7DBB8"              ; hypsometric tint, low
    :terrain-2     "#E0CFA0"              ; hypsometric tint, high
    :contour       "#D9C99F"              ; contour/outline ink
    :water-fill    "#C9DCD6"              ; chart-water green-grey
    :water-outline "rgba(61, 94, 140, 0.5)"  ; coastline pen, quiet
    :water-line    "#3D5E8C"              ; rivers — aero blue
    :ink           "#2C2A24"              ; place-name ink
    :faded-ink     "#6E6A58"              ; captions, POIs, boundaries (§2, contrast-tuned)
    :magenta       "#A83A63"              ; the wine pen (§5) — airports
    :aero          "#36547E"              ; water labels — §5 chrome aero
    :road          "rgba(166, 90, 46, 0.6)" ; sectional road sienna
    :road-casing   "#F5EFDF"              ; roads are single ink strokes
    :rail          "#D9C99F"              ; rail hatching, quiet
    :building      "#E0CFA0"              ; built-up tint
    :building-line "#D9C99F"
    :aeroway       "#EDE4CB"              ; runway/taxiway ground
    :aeroway-line  "#8B8471"
    :label-halo    "#F5EFDF"              ; text sits on the paper
    :hide-decor?   false}
   :night
   {:paper         "#151B26"              ; the night stock
    :terrain-1     "#1D2634"
    :terrain-2     "#232E40"
    :contour       "#2E3A49"
    :water-fill    "#101823"              ; water darker than land
    :water-outline "rgba(127, 163, 212, 0.45)"
    :water-line    "#7FA3D4"
    :ink           "#E9E2CE"              ; night ink — warm cream
    :faded-ink     "#8D96A8"
    :magenta       "#E77E9B"              ; the wine pen, night print (§5)
    :aero          "#8BA9D6"              ; §5 chrome aero, night print
    ;; PROVED in the running prototype (adsb-dgb.7): the §2 ink #6B5540
    ;; printed full-strength glows like filament against the night paper
    ;; at z7 road density — the same ~0.6 alpha the day edition always
    ;; used lets the network read as embers, not wires.
    :road          "rgba(107, 85, 64, 0.6)"
    :road-casing   "#151B26"
    :rail          "#2E3A49"
    :building      "#232E40"
    :building-line "#2E3A49"
    :aeroway       "#1D2634"
    :aeroway-line  "#8D96A8"
    :label-halo    "#151B26"
    :hide-decor?   true}})                ; sprite decor glares on dark stock

;; ---------------------------------------------------------------------
;; The chart's written hand (§3/§5). Liberty labels everything in Noto
;; Sans from OpenFreeMap's glyph server — a generic web-map gothic, not
;; our artifact, and the server hosts nothing else (probed: no serif, no
;; mono). So the chart carries its own glyphs: SDF ranges generated from
;; the same OFL faces the chrome ships (resources/public/glyphs/, built
;; from resources/public/fonts/ — see fonts/LICENSE.md), served
;; same-origin, and every symbol layer is re-pointed at them.
;;
;; THE §5 OPEN CALL, DECIDED BY EYE (adsb-dgb.5): the whole chart
;; adopts the plotter's mono hand. Both candidates were rendered over
;; the re-inked Liberty at replay density — serif places (Source Serif 4
;; against the mono chrome) and all-mono — and the serif whisper lost:
;; against a mono header, a mono Stack, and mono marginalia, serif
;; places read as a second author annotating someone else's chart,
;; while Space Mono places read as the same plotter lettering the plate
;; he annotates. One hand everywhere is The Annotation's own thesis.
;; Liberty's weight/italic hierarchy survives verbatim: Bold stays the
;; capitals' stamp, Italic stays water and states' whisper.

(def ^:const glyphs-url
  "The self-hosted glyph endpoint the edition styles point at."
  "/glyphs/{fontstack}/{range}.pbf")

(def label-fonts
  "Liberty's Noto stacks -> the plotter's hand, weight for weight. An
  unrecognized font passes through unchanged (and would 404 against our
  glyph server — loudly missing beats quietly wrong if upstream drifts)."
  {"Noto Sans Regular" "Space Mono Regular"
   "Noto Sans Bold"    "Space Mono Bold"
   "Noto Sans Italic"  "Space Mono Italic"})

(defn refont-layer
  "A symbol layer re-lettered in the chart's hand via `label-fonts`.
  Non-symbol layers and layers without a text-font pass through.
  EVERY text-font must be re-pointed, shields included: the style has
  ONE glyphs endpoint, so a stack ours does not host 404s — and a tile
  whose symbol bucket cannot resolve its glyphs never finishes, taking
  its fills and roads down with it (proven the hard way in this bead's
  verification: day-edition shields kept Noto and most land tiles
  wedged blank)."
  [layer]
  (if (and (= "symbol" (:type layer))
           (get-in layer [:layout :text-font]))
    (update-in layer [:layout :text-font]
               (fn [fonts] (mapv #(get label-fonts % %) fonts)))
    layer))

;; ---------------------------------------------------------------------

(defn- with-paint
  "Layer with `kvs` merged over its paint — recolor without disturbing
  widths, dasharrays, opacities, or anything else Liberty tuned."
  [layer kvs]
  (update layer :paint merge kvs))

(defn- hidden
  "Layer with visibility none — content we cannot re-ink for this paper."
  [layer]
  (assoc-in layer [:layout :visibility] "none"))

(defn- text-inked
  "A symbol layer re-inked: text in `color`, haloed by the edition's
  paper so labels sit ON the chart instead of floating over it."
  [palette layer color]
  (with-paint layer {:text-color color
                     :text-halo-color (:label-halo palette)}))

(defn- shield-layer? [{:keys [id]}]
  (str/includes? id "shield"))

(defn- pattern-fill? [layer]
  (some? (get-in layer [:paint :fill-pattern])))

(defn- casing-layer? [{:keys [id]}]
  (str/includes? id "casing"))

(defn- rail-layer? [{:keys [id]}]
  (str/includes? id "rail"))

(defn recolor-layer
  "One Liberty layer, re-inked for the edition `palette`. Categorized by
  source-layer + type (id idioms only for casing/rail/shield); a layer
  this taxonomy does not recognize is returned UNCHANGED, so upstream
  additions degrade to Liberty's own paint instead of breaking the plate."
  [palette {:keys [type source-layer] :as layer}]
  (cond
    ;; The paper itself.
    (= type "background")
    (with-paint layer {:background-color (:paper palette)})

    ;; Liberty's low-zoom natural-earth raster is a photograph, not a
    ;; print — it cannot be re-inked, so neither edition shows it; the
    ;; paper and the tile data carry the low zooms.
    (= type "raster")
    (hidden layer)

    ;; Sprite decor (highway shields, pattern fills) keeps its daylight
    ;; colours; on dark stock it would glare, so the night edition
    ;; omits it.
    (and (:hide-decor? palette)
         (or (shield-layer? layer) (pattern-fill? layer)))
    (hidden layer)

    ;; Where the day edition keeps sprite decor, it keeps ALL of it:
    ;; a shield's number is inked to match its sprite, so re-inking the
    ;; text would break the shield itself.
    (or (shield-layer? layer) (pattern-fill? layer))
    layer

    ;; Water — fill with a quiet coastline pen; rivers in aero blue.
    (= source-layer "water")
    (with-paint layer {:fill-color (:water-fill palette)
                       :fill-outline-color (:water-outline palette)})

    (and (= source-layer "waterway") (= type "line"))
    (with-paint layer {:line-color (:water-line palette)})

    ;; Terrain — parks and woods take the deeper hypsometric tint,
    ;; open landcover/landuse the lighter one; outlines are contour ink.
    (and (#{"park" "landuse"} source-layer) (= type "fill"))
    (with-paint layer (cond-> {:fill-color (:terrain-1 palette)}
                        (contains? (:paint layer) :fill-outline-color)
                        (assoc :fill-outline-color (:contour palette))))

    (and (= source-layer "landcover") (= type "fill"))
    (with-paint layer {:fill-color (if (str/includes? (:id layer) "wood")
                                     (:terrain-2 palette)
                                     (:terrain-1 palette))})

    (and (#{"park" "landcover"} source-layer) (= type "line"))
    (with-paint layer {:line-color (:contour palette)})

    ;; Aerodromes on the ground — quiet; the LABEL carries the magenta.
    (and (= source-layer "aeroway") (= type "fill"))
    (with-paint layer {:fill-color (:aeroway palette)})

    (and (= source-layer "aeroway") (= type "line"))
    (with-paint layer {:line-color (:aeroway-line palette)})

    ;; Roads — single sienna strokes: casings dissolve into the paper,
    ;; rail reads as quiet hatching.
    (and (= source-layer "transportation") (= type "line"))
    (with-paint layer {:line-color (cond
                                     (casing-layer? layer) (:road-casing palette)
                                     (rail-layer? layer)   (:rail palette)
                                     :else                 (:road palette))})

    ;; Built-up areas.
    (and (= source-layer "building") (= type "fill"))
    (with-paint layer (cond-> {:fill-color (:building palette)}
                        (contains? (:paint layer) :fill-outline-color)
                        (assoc :fill-outline-color (:building-line palette))))

    (= type "fill-extrusion")
    (with-paint layer {:fill-extrusion-color (:building palette)})

    (and (= source-layer "boundary") (= type "line"))
    (with-paint layer {:line-color (:faded-ink palette)})

    ;; Labels — places in ink, airports in aviation magenta, water in
    ;; aero blue, everything supporting in faded ink.
    (and (= type "symbol") (= source-layer "place"))
    (text-inked palette layer (:ink palette))

    (and (= type "symbol") (= source-layer "aerodrome_label"))
    (text-inked palette layer (:magenta palette))

    (and (= type "symbol") (#{"water_name" "waterway"} source-layer))
    (text-inked palette layer (:aero palette))

    (and (= type "symbol") (#{"poi" "transportation_name"} source-layer))
    (text-inked palette layer (:faded-ink palette))

    :else layer))

(defn edition-style
  "The full style JSON (Clojure data, as fetched) printed in `theme`'s
  edition: every layer re-inked through `recolor-layer` and re-lettered
  through `refont-layer`, the glyph endpoint re-pointed at the chart's
  own hand; everything else — sources, sprite, and the attribution they
  carry — untouched."
  [style theme]
  (let [palette (get editions theme (:day editions))]
    (-> style
        (assoc :glyphs glyphs-url)
        (update :layers
                #(mapv (comp refont-layer (partial recolor-layer palette)) %)))))
