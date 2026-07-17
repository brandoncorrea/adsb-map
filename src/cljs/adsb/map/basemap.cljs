(ns adsb.map.basemap
  (:require [adsb.palette :as palette]
            [clojure.string :as str]))

(def editions
  {:day
   {:paper         (palette/swatch :day :paper)
    :terrain-1     (palette/swatch :day :terrain-1)
    :terrain-2     (palette/swatch :day :terrain-2)
    :contour       (palette/swatch :day :contour)
    :water-fill    (palette/swatch :day :water-fill)
    :water-outline (palette/rgba :day :aero 0.5)
    :water-line    (palette/swatch :day :water-line)
    :ink           (palette/swatch :day :ink)
    :faded-ink     (palette/swatch :day :faded-ink)
    :magenta       (palette/swatch :day :magenta)
    :aero          (palette/swatch :day :aero)
    :road          (palette/rgba :day :road 0.75)
    :road-casing   (palette/swatch :day :paper)
    :rail          (palette/swatch :day :contour)
    :building      (palette/swatch :day :terrain-2)
    :building-line (palette/swatch :day :contour)
    :aeroway       (palette/swatch :day :aeroway)
    :aeroway-line  (palette/swatch :day :faded-ink)
    :label-halo    (palette/swatch :day :paper)
    :hide-decor?   false}
   :night
   {:paper         (palette/swatch :night :paper)
    :terrain-1     (palette/swatch :night :terrain-1)
    :terrain-2     (palette/swatch :night :terrain-2)
    :contour       (palette/swatch :night :contour)
    :water-fill    (palette/swatch :night :water-fill)
    :water-outline (palette/rgba :night :water-line 0.45)
    :water-line    (palette/swatch :night :water-line)
    :ink           (palette/swatch :night :ink)
    :faded-ink     (palette/swatch :night :faded-ink)
    :magenta       (palette/swatch :night :magenta)
    :aero          (palette/swatch :night :aero)
    :road          (palette/rgba :night :road 0.6)
    :road-casing   (palette/swatch :night :paper)
    :rail          (palette/swatch :night :contour)
    :building      (palette/swatch :night :terrain-2)
    :building-line (palette/swatch :night :contour)
    :aeroway       (palette/swatch :night :aeroway)
    :aeroway-line  (palette/swatch :night :faded-ink)
    :label-halo    (palette/swatch :night :paper)
    :hide-decor?   true}})

(def ^:const glyphs-url "/glyphs/{fontstack}/{range}.pbf")

(def label-fonts
  {"Noto Sans Regular" "Space Mono Regular"
   "Noto Sans Bold"    "Space Mono Bold"
   "Noto Sans Italic"  "Space Mono Italic"})

(defn refont-layer [layer]
  (if (and (= "symbol" (:type layer))
           (get-in layer [:layout :text-font]))
    (update-in layer [:layout :text-font]
               (fn [fonts] (mapv #(get label-fonts % %) fonts)))
    layer))

(defn- with-paint [layer kvs] (update layer :paint merge kvs))
(defn- hidden [layer] (assoc-in layer [:layout :visibility] "none"))

(defn- text-inked [palette layer color]
  (with-paint layer {:text-color      color
                     :text-halo-color (:label-halo palette)}))

(defn- shield-layer? [{:keys [id]}]
  (str/includes? id "shield"))

(defn- pattern-fill? [layer]
  (-> layer :paint :fill-pattern some?))

(defn- casing-layer? [{:keys [id]}]
  (str/includes? id "casing"))

(defn- rail-layer? [{:keys [id]}]
  (str/includes? id "rail"))

(defn recolor-layer [palette {:keys [type source-layer] :as layer}]
  (cond
    (= type "background")
    (with-paint layer {:background-color (:paper palette)})

    (= type "raster")
    (hidden layer)

    (and (:hide-decor? palette)
         (or (shield-layer? layer) (pattern-fill? layer)))
    (hidden layer)

    (or (shield-layer? layer) (pattern-fill? layer))
    layer

    (= source-layer "water")
    (with-paint layer {:fill-color         (:water-fill palette)
                       :fill-outline-color (:water-outline palette)})

    (and (= source-layer "waterway") (= type "line"))
    (with-paint layer {:line-color (:water-line palette)})

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

    (and (= source-layer "aeroway") (= type "fill"))
    (with-paint layer {:fill-color (:aeroway palette)})

    (and (= source-layer "aeroway") (= type "line"))
    (with-paint layer {:line-color (:aeroway-line palette)})

    (and (= source-layer "transportation") (= type "line"))
    (with-paint layer {:line-color (cond
                                     (casing-layer? layer) (:road-casing palette)
                                     (rail-layer? layer) (:rail palette)
                                     :else (:road palette))})

    (and (= source-layer "building") (= type "fill"))
    (with-paint layer (cond-> {:fill-color (:building palette)}
                              (contains? (:paint layer) :fill-outline-color)
                              (assoc :fill-outline-color (:building-line palette))))

    (= type "fill-extrusion")
    (with-paint layer {:fill-extrusion-color (:building palette)})

    (and (= source-layer "boundary") (= type "line"))
    (with-paint layer {:line-color (:faded-ink palette)})

    (and (= type "symbol") (= source-layer "place"))
    (text-inked palette layer (:ink palette))

    (and (= type "symbol") (= source-layer "aerodrome_label"))
    (text-inked palette layer (:magenta palette))

    (and (= type "symbol") (#{"water_name" "waterway"} source-layer))
    (text-inked palette layer (:aero palette))

    (and (= type "symbol") (#{"poi" "transportation_name"} source-layer))
    (text-inked palette layer (:faded-ink palette))

    :else layer))

(defn edition-style [style theme]
  (let [palette (get editions theme (:day editions))]
    (-> style
        (assoc :glyphs glyphs-url)
        (update :layers
                #(mapv (comp refont-layer (partial recolor-layer palette)) %)))))
