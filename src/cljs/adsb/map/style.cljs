(ns adsb.map.style
  (:require [adsb.aircraft :as aircraft]))

(def ^:const plane-icon-id "aircraft-plane")
(def ^:const heavy-icon-id "aircraft-heavy")
(def ^:const light-icon-id "aircraft-light")
(def ^:const rotorcraft-icon-id "aircraft-rotorcraft")
(def ^:const vehicle-icon-id "aircraft-vehicle")
(def ^:const dot-icon-id "aircraft-dot")

(def category->icon-id
  {"A1" light-icon-id                                       ; light — GA in the pattern
   "A4" heavy-icon-id                                       ; high-vortex large (the 757)
   "A5" heavy-icon-id                                       ; heavy
   "A7" rotorcraft-icon-id                                  ; rotorcraft
   "B1" light-icon-id                                       ; glider / sailplane
   "B4" light-icon-id                                       ; ultralight / hang-glider / paraglider
   "C1" vehicle-icon-id                                     ; surface vehicle — emergency
   "C2" vehicle-icon-id})                                   ; surface vehicle — service

(def ^:const base-icon-size 0.9)
(def ^:const emergency-icon-size 1.6)
(def ^:const ground-icon-size 0.5)
(def ^:const perspective-size-stops [[0 1.25] [10000 1.0] [40000 0.55]])
(def ^:const mlat-size-factor 0.78)
(def ^:const base-opacity 1.0)
(def ^:const aged-out-opacity 0.2)
(def ^:const stale-threshold-s (/ aircraft/stale-threshold-ms 1000))
(def ^:const age-out-threshold-s (/ aircraft/age-out-threshold-ms 1000))
(def ^:const halo-width 1.0)
(def ^:const trail-width 2.0)
(def ^:const trail-head-opacity 0.5)
(def ^:const crop-width 2.0)
(def ^:const crop-opacity 0.30)
(def ^:const crop-dasharray [4 3])

(def palettes
  {:day
   {:ground-color    "#8A8374"
    :unknown-color   "#9A937F"
    :emergency-color "#CE2029"
    :halo-color      "#E2E8DE"
    :trail-rgb       "27, 42, 29"
    :altitude-stops  [[0 "#A0622D"]
                      [10000 "#C2447C"]
                      [20000 "#7A4F86"]
                      [30000 "#3D5E8C"]
                      [40000 "#2A3F66"]]}
   :night
   {:ground-color    "#6E7686"
    :unknown-color   "#7C8494"
    :emergency-color "#FF5A4D"
    :halo-color      "#151B26"
    :trail-rgb       "233, 226, 206"
    :altitude-stops  [[0 "#C98A54"]
                      [10000 "#E06A9F"]
                      [20000 "#A98BC4"]
                      [30000 "#7FA3D4"]
                      [40000 "#5F7FB8"]]}})

(defn palette [theme] (get palettes theme (:day palettes)))

(defn- trail-rgba [theme alpha]
  (str "rgba(" (:trail-rgb (palette theme)) ", " alpha ")"))

(defn trail-gradient-expression [theme]
  ["interpolate" ["linear"] ["line-progress"]
   0.0 (trail-rgba theme 0)
   1.0 (trail-rgba theme trail-head-opacity)])

(defn altitude-color-expression [theme]
  (let [{:keys [unknown-color ground-color altitude-stops]} (palette theme)]
    ["case"
     ["!" ["has" "altitude"]] unknown-color
     ["==" ["get" "altitude"] "ground"] ground-color
     (into ["interpolate" ["linear"] ["get" "altitude"]]
           (mapcat identity altitude-stops))]))

(defn icon-color-expression [theme]
  ["case"
   ["get" "emergency"] (:emergency-color (palette theme))
   (altitude-color-expression theme)])

(defn perspective-size-expression []
  ["case"
   ["!" ["has" "altitude"]] base-icon-size
   ["==" ["get" "altitude"] "ground"] ground-icon-size
   (into ["interpolate" ["linear"] ["get" "altitude"]]
         (mapcat identity perspective-size-stops))])

(defn icon-size-expression []
  ["case"
   ["get" "emergency"] emergency-icon-size
   ["*"
    ["case" ["get" "mlat"] mlat-size-factor 1.0]
    (perspective-size-expression)]])

(defn icon-opacity-expression []
  ["case"
   ["has" "age-s"]
   ["interpolate" ["linear"] ["get" "age-s"]
    stale-threshold-s base-opacity
    age-out-threshold-s aged-out-opacity]
   base-opacity])

(defn icon-image-expression []
  ["case"
   ["!" ["has" "track"]] dot-icon-id
   ["!" ["has" "category"]] plane-icon-id
   (into ["match" ["get" "category"]]
         (concat (mapcat identity category->icon-id)
                 [plane-icon-id]))])

(defn aircraft-layer-spec [theme layer-id source-id]
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

(defn trail-layer-spec [theme layer-id source-id]
  {:id     layer-id
   :type   "line"
   :source source-id
   :layout {:line-cap  "round"
            :line-join "round"}
   :paint  {:line-width    trail-width
            :line-gradient (trail-gradient-expression theme)}})

(defn crop-layer-spec [theme layer-id source-id]
  {:id     layer-id
   :type   "line"
   :source source-id
   :layout {:line-cap  "butt"
            :line-join "round"}
   :paint  {:line-width     crop-width
            :line-color     (trail-rgba theme crop-opacity)
            :line-dasharray crop-dasharray}})
