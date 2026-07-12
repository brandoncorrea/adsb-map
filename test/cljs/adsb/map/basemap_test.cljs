(ns adsb.map.basemap-test
  "The chart plate transform is PURE data-in data-out, so the tests feed it
  a miniature Liberty — one representative layer per category — and read
  the re-inked paint straight back. Every expected colour is the
  `basemap/editions` palette entry itself, never a duplicated literal, so
  tuning the palette moves the tests with it; what is pinned here is the
  WIRING: which category gets which role, what is hidden on which paper,
  and that everything unrecognized survives untouched."
  (:require
    [adsb.map.basemap :as basemap]
    [cljs.test :refer-macros [deftest is testing]]))

(def ^:private mini-liberty
  "One layer per taxonomy branch, with Liberty-ish daylight paint."
  {:version 8
   :sources {"openmaptiles" {:type "vector"
                             :url "https://tiles.openfreemap.org/planet"
                             :attribution "OpenFreeMap"}}
   :layers
   [{:id "background" :type "background"
     :paint {:background-color "#efefef"}}
    {:id "natural_earth" :type "raster" :source "ne"}
    {:id "water" :type "fill" :source-layer "water"
     :paint {:fill-color "#80a0c0"}}
    {:id "waterway_river" :type "line" :source-layer "waterway"
     :paint {:line-color "#88aaff" :line-width 1.2}}
    {:id "landcover_wood" :type "fill" :source-layer "landcover"
     :paint {:fill-color "#00ff00"}}
    {:id "landcover_grass" :type "fill" :source-layer "landcover"
     :paint {:fill-color "#88ff88"}}
    {:id "landcover_wetland" :type "fill" :source-layer "landcover"
     :paint {:fill-pattern "wetland"}}
    {:id "landuse_residential" :type "fill" :source-layer "landuse"
     :paint {:fill-color "#e0dcd0"}}
    {:id "park" :type "fill" :source-layer "park"
     :paint {:fill-color "#d0e8c8" :fill-outline-color "#a0c890"}}
    {:id "aeroway_runway" :type "line" :source-layer "aeroway"
     :paint {:line-color "#ffffff"}}
    {:id "road_motorway" :type "line" :source-layer "transportation"
     :paint {:line-color "#ffcc88" :line-width 3}}
    {:id "road_motorway_casing" :type "line" :source-layer "transportation"
     :paint {:line-color "#e9ac77"}}
    {:id "road_major_rail" :type "line" :source-layer "transportation"
     :paint {:line-color "#bbbbbb"}}
    {:id "building" :type "fill" :source-layer "building"
     :paint {:fill-color "#dfdbd7" :fill-outline-color "#cfcbc7"}}
    {:id "boundary_2" :type "line" :source-layer "boundary"
     :paint {:line-color "#9e9cab"}}
    {:id "highway-shield-us-interstate" :type "symbol"
     :source-layer "transportation_name"
     :layout {:icon-image "us-interstate"}}
    {:id "label_city" :type "symbol" :source-layer "place"
     :paint {:text-color "#333344" :text-halo-color "#ffffff"}}
    {:id "airport" :type "symbol" :source-layer "aerodrome_label"
     :paint {:text-color "#666666" :text-halo-color "#ffffff"}}
    {:id "water_name_point_label" :type "symbol" :source-layer "water_name"
     :paint {:text-color "#4444aa" :text-halo-color "#ffffff"}}
    {:id "poi_r1" :type "symbol" :source-layer "poi"
     :paint {:text-color "#665544" :text-halo-color "#ffffff"}}
    {:id "somebody_elses_future_layer" :type "circle" :source-layer "novelty"
     :paint {:circle-color "#123456"}}]})

(defn- layer-by-id [style id]
  (some #(when (= id (:id %)) %) (:layers style)))

(defn- printed [theme]
  (basemap/edition-style mini-liberty theme))

(deftest night-edition-re-inks-every-category
  (let [p     (:night basemap/editions)
        night (printed :night)
        paint (fn [id] (:paint (layer-by-id night id)))]
    (testing "the paper"
      (is (= (:paper p) (:background-color (paint "background")))))
    (testing "water: fill with a coastline pen, rivers in aero blue"
      (is (= (:water-fill p) (:fill-color (paint "water"))))
      (is (= (:water-outline p) (:fill-outline-color (paint "water"))))
      (is (= (:water-line p) (:line-color (paint "waterway_river")))))
    (testing "hypsometric terrain: woods deeper, open land lighter"
      (is (= (:terrain-2 p) (:fill-color (paint "landcover_wood"))))
      (is (= (:terrain-1 p) (:fill-color (paint "landcover_grass"))))
      (is (= (:terrain-1 p) (:fill-color (paint "landuse_residential"))))
      (is (= (:terrain-1 p) (:fill-color (paint "park"))))
      (is (= (:contour p) (:fill-outline-color (paint "park")))
          "an existing outline is re-inked as contour, never invented"))
    (testing "roads are single strokes: casing dissolves into the paper"
      (is (= (:road p) (:line-color (paint "road_motorway"))))
      (is (= (:road-casing p) (:line-color (paint "road_motorway_casing"))))
      (is (= (:rail p) (:line-color (paint "road_major_rail")))))
    (testing "aeroways, buildings, boundaries"
      (is (= (:aeroway-line p) (:line-color (paint "aeroway_runway"))))
      (is (= (:building p) (:fill-color (paint "building"))))
      (is (= (:faded-ink p) (:line-color (paint "boundary_2")))))
    (testing "labels: places in ink, airports in aviation magenta, water in
              aero blue, POIs faded — all haloed by the paper"
      (is (= (:ink p) (:text-color (paint "label_city"))))
      (is (= (:magenta p) (:text-color (paint "airport"))))
      (is (= (:aero p) (:text-color (paint "water_name_point_label"))))
      (is (= (:faded-ink p) (:text-color (paint "poi_r1"))))
      (doseq [id ["label_city" "airport" "water_name_point_label" "poi_r1"]]
        (is (= (:label-halo p) (:text-halo-color (paint id)))
            (str id " sits ON the paper"))))))

(deftest what-cannot-be-re-inked-is-hidden-not-glaring
  (let [night (printed :night)
        day   (printed :day)
        vis   (fn [style id] (get-in (layer-by-id style id) [:layout :visibility]))]
    (testing "the natural-earth photograph is a print's enemy on BOTH papers"
      (is (= "none" (vis night "natural_earth")))
      (is (= "none" (vis day "natural_earth"))))
    (testing "sprite decor (shields, pattern fills) glares on dark stock only"
      (is (= "none" (vis night "highway-shield-us-interstate")))
      (is (= "none" (vis night "landcover_wetland")))
      (is (nil? (vis day "highway-shield-us-interstate")))
      (is (nil? (vis day "landcover_wetland"))))))

(deftest the-two-prints-share-a-plate
  (let [night (printed :night)
        day   (printed :day)]
    (testing "sources — and the attribution they carry — are untouched"
      (is (= (:sources mini-liberty) (:sources night) (:sources day))))
    (testing "same layers, same order, same data — only the ink differs"
      (is (= (mapv :id (:layers mini-liberty))
             (mapv :id (:layers night))
             (mapv :id (:layers day))))
      (is (not= (:layers night) (:layers day))
          "but they are genuinely different prints"))
    (testing "non-colour paint Liberty tuned survives the re-inking"
      (is (= 3 (get-in (layer-by-id night "road_motorway")
                       [:paint :line-width])))
      (is (= 1.2 (get-in (layer-by-id night "waterway_river")
                         [:paint :line-width]))))
    (testing "a layer the taxonomy does not know keeps Liberty's own paint"
      (is (= (layer-by-id mini-liberty "somebody_elses_future_layer")
             (layer-by-id night "somebody_elses_future_layer"))))))

(deftest an-unknown-theme-defaults-to-the-day-print
  (is (= (printed :day) (basemap/edition-style mini-liberty :sepia))
      "a chart is always on the table"))
