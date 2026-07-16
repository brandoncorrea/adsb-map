(ns adsb.map.basemap
  (:require [clojure.string :as str]))

(def editions
  {:day
   {:paper         "#E2E8DE"
    :terrain-1     "#C2D7B4"
    :terrain-2     "#A2C193"
    :contour       "#A6BF9E"
    :water-fill    "#A6C7BE"
    :water-outline "rgba(42, 99, 88, 0.5)"
    :water-line    "#2A6358"
    :ink           "#1B2A1D"
    :faded-ink     "#506049"
    :magenta       "#A5385C"
    :aero          "#2A6358"
    :road          "rgba(92, 110, 86, 0.75)"
    :road-casing   "#E2E8DE"
    :rail          "#A6BF9E"
    :building      "#A2C193"
    :building-line "#A6BF9E"
    :aeroway       "#CFDFC4"
    :aeroway-line  "#506049"
    :label-halo    "#E2E8DE"
    :hide-decor?   false}
   :night
   {:paper         "#151B26"
    :terrain-1     "#1D2634"
    :terrain-2     "#232E40"
    :contour       "#2E3A49"
    :water-fill    "#101823"
    :water-outline "rgba(127, 163, 212, 0.45)"
    :water-line    "#7FA3D4"
    :ink           "#E9E2CE"
    :faded-ink     "#8D96A8"
    :magenta       "#E77E9B"
    :aero          "#8BA9D6"
    :road          "rgba(107, 85, 64, 0.6)"
    :road-casing   "#151B26"
    :rail          "#2E3A49"
    :building      "#232E40"
    :building-line "#2E3A49"
    :aeroway       "#1D2634"
    :aeroway-line  "#8D96A8"
    :label-halo    "#151B26"
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
