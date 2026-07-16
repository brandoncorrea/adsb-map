(ns adsb.map.aircraft-layer
  (:require [adsb.corejs :as cjs]
            [adsb.geo :as geo]
            [adsb.map.maplibre :as maplibre]
            [adsb.map.style :as style]
            [adsb.map.theme :as theme]
            [adsb.trails :as trails]
            [clojure.math :as math]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def ^:const source-id "aircraft")
(def ^:const layer-id "aircraft")
(def ^:const trail-source-id "aircraft-trails")
(def ^:const trail-layer-id "aircraft-trails")

(def empty-feature-collection
  {:type "FeatureCollection" :features []})

(def source-spec
  {:type "geojson"
   :data empty-feature-collection})

(def trail-source-spec
  {:type        "geojson"
   :lineMetrics true
   :data        empty-feature-collection})

(defn layer-spec [theme]
  (style/aircraft-layer-spec theme layer-id source-id))

(defn trail-layer-spec [theme]
  (style/trail-layer-spec theme trail-layer-id trail-source-id))

(def ^:const icon-size-px 32)

(def ^:private plane-half-outline
  "The generic airframe"
  [[0.50 0.04]                                              ; nose (on axis)
   [0.55 0.33]                                              ; wing root, leading edge
   [0.88 0.52]                                              ; wingtip
   [0.88 0.58]                                              ; wingtip, trailing
   [0.55 0.45]                                              ; wing root, trailing edge
   [0.54 0.68]                                              ; fuselage, before the tail
   [0.70 0.77]                                              ; tailplane tip
   [0.70 0.81]                                              ; tailplane tip, trailing
   [0.50 0.74]])                                            ; tail centre (on axis)

(def ^:private heavy-half-outline
  "A4/A5"
  [[0.50 0.03]                                              ; nose (on axis)
   [0.58 0.30]                                              ; wing root, leading edge
   [0.96 0.52]                                              ; wingtip — the span is the tell
   [0.96 0.60]                                              ; wingtip, trailing
   [0.58 0.46]                                              ; wing root, trailing edge
   [0.57 0.70]                                              ; fuselage, before the tail
   [0.76 0.80]                                              ; tailplane tip
   [0.76 0.85]                                              ; tailplane tip, trailing
   [0.50 0.77]])                                            ; tail centre (on axis)

(def ^:private light-half-outline
  "A1 (and the gliders and ultralights of set B)"
  [[0.50 0.12]                                              ; nose (on axis)
   [0.54 0.36]                                              ; wing root, leading edge
   [0.82 0.38]                                              ; wingtip — barely swept, high aspect
   [0.82 0.46]                                              ; wingtip, trailing
   [0.54 0.48]                                              ; wing root, trailing edge
   [0.53 0.74]                                              ; slim fuselage, before the tail
   [0.66 0.78]                                              ; tailplane tip
   [0.66 0.84]                                              ; tailplane tip, trailing
   [0.50 0.80]])                                            ; tail centre (on axis)

(def ^:private rotorcraft-half-outline
  "A7"
  [[0.50 0.34]                                              ; notch between the forward blades (on axis)
   [0.72 0.12]                                              ; forward-right blade, leading corner
   [0.81 0.21]                                              ; forward-right blade, trailing corner
   [0.63 0.43]                                              ; notch, starboard — the hub
   [0.81 0.65]                                              ; aft-right blade, leading corner
   [0.72 0.74]                                              ; aft-right blade, trailing corner
   [0.56 0.70]                                              ; tail boom, right edge
   [0.56 0.90]                                              ; tail boom, aft
   [0.50 0.90]])                                            ; tail (on axis)

(def ^:private vehicle-half-outline
  "C1/C2"
  [[0.50 0.16]                                              ; nose (on axis)
   [0.64 0.22]                                              ; chamfer
   [0.66 0.38]                                              ; shoulder
   [0.66 0.74]                                              ; flank
   [0.62 0.80]                                              ; aft chamfer
   [0.50 0.81]])                                            ; tail (on axis)

(defn- draw-outline! [half-outline ctx size]
  (let [mirrored (->> (rest (butlast half-outline))
                      reverse
                      (map (fn [[x y]] [(- 1.0 x) y])))
        pts      (concat half-outline mirrored)]
    (js-invoke ctx "beginPath")
    (doseq [[i [x y]] (map-indexed vector pts)]
      (if (zero? i)
        (js-invoke ctx "moveTo" (* x size) (* y size))
        (js-invoke ctx "lineTo" (* x size) (* y size))))
    (js-invoke ctx "closePath")
    (js-invoke ctx "fill")))

(defn- draw-dot! [ctx size]
  (let [c (/ size 2)]
    (js-invoke ctx "beginPath")
    (js-invoke ctx "arc" c c (* size 0.32) 0 (* 2 math/PI))
    (js-invoke ctx "fill")))

(defn ->icon-image [draw!]
  (let [canvas (cjs/create-element "canvas")]
    (set! (.-width canvas) icon-size-px)
    (set! (.-height canvas) icon-size-px)
    (let [ctx (js-invoke canvas "getContext" "2d")]
      (set! (.-fillStyle ctx) "#ffffff")
      (draw! ctx icon-size-px)
      (js-invoke ctx "getImageData" 0 0 icon-size-px icon-size-px))))

(def half-outlines
  {style/plane-icon-id      plane-half-outline
   style/heavy-icon-id      heavy-half-outline
   style/light-icon-id      light-half-outline
   style/rotorcraft-icon-id rotorcraft-half-outline
   style/vehicle-icon-id    vehicle-half-outline})

(def icons
  (as-> half-outlines $
        (mapv (fn [[icon-id outline]] [icon-id (partial draw-outline! outline)]) $)
        (conj $ [style/dot-icon-id draw-dot!])))

(defn- register-icons! [m]
  (doseq [[icon-id draw!] icons]
    (maplibre/add-image! m icon-id (->icon-image draw!) {:sdf true})))

;; Clicking the already-selected aircraft arms a DELAYED deselect so a
;; double-click can cancel it and follow instead; follow-dedupe suppresses
;; the duplicate when the click (detail>=2) and dblclick paths both fire.
(def ^:private deselect-arm-ms 350)
(defonce ^:private !pending-deselect (atom nil))
(def ^:private follow-dedupe-ms 400)
(defonce ^:private !last-follow-ms (atom 0))

(defn- cancel-pending-deselect! []
  (when-let [id @!pending-deselect]
    (js/clearTimeout id)
    (reset! !pending-deselect nil)))

(defn- dblclick-follow! [props]
  (cancel-pending-deselect!)
  (when-let [icao (:icao props)]
    (let [now (cjs/now-ms)]
      (when (> (- now @!last-follow-ms) follow-dedupe-ms)
        (reset! !last-follow-ms now)
        (rf/dispatch [:aircraft/dblclick-follow icao])))))

(defn- select! [props]
  (when-let [icao (:icao props)]
    (let [detail (or (:click/detail props) 1)]
      (if (>= detail 2)
        (dblclick-follow! props)
        (let [selected @(rf/subscribe [:aircraft/selected-icao])]
          (if (= icao selected)
            (do (cancel-pending-deselect!)
                (reset! !pending-deselect
                        (js/setTimeout
                          (fn []
                            (reset! !pending-deselect nil)
                            (rf/dispatch [:aircraft/select icao]))
                          deselect-arm-ms)))
            (do (cancel-pending-deselect!)
                (rf/dispatch [:aircraft/select icao]))))))))

(defn- hover-enter! [props]
  (when-let [icao (:icao props)]
    (rf/dispatch [:aircraft/hover icao])))

(defn- hover-leave! [] (rf/dispatch [:aircraft/clear-hover]))
(def ^:const tick-interval-ms 5000)

(defn set-interval! [f ms] (js/setInterval f ms))
(defn clear-interval! [id] (js/clearInterval id))

(defn picture->feature-collection [picture at-ms]
  (geo/aircraft-picture->feature-collection (vals picture) at-ms))

(defn- push! [m history picture]
  (let [fc       (picture->feature-collection picture (cjs/now-ms))
        trail-fc (->> (into #{} (map (comp :icao :properties)) (:features fc))
                      (trails/history->trail-feature-collection history))]
    (maplibre/set-source-data! m trail-source-id trail-fc)
    (maplibre/set-source-data! m source-id fc)))

(defn attach!
  ([m] (attach! m @theme/!theme))
  ([m theme]
   (let [!state (atom {:history {}})]
     (maplibre/on-load!
       m
       (fn []
         (when-not (:disposed? @!state)
           (register-icons! m)
           (maplibre/add-source! m trail-source-id trail-source-spec)
           (maplibre/add-layer! m (trail-layer-spec theme))
           (maplibre/add-source! m source-id source-spec)
           (maplibre/add-layer! m (layer-spec theme))
           (maplibre/on-layer-click! m layer-id select!)
           (maplibre/on-layer-dblclick! m layer-id dblclick-follow!)
           (maplibre/on-layer-hover! m layer-id hover-enter! hover-leave!)
           (swap! !state assoc :track
                  (r/track!
                    (fn []
                      (let [picture @(rf/subscribe [:aircraft/picture])
                            history (trails/accumulate (:history @!state)
                                                       (vals picture))]
                        (swap! !state assoc :picture picture :history history)
                        (push! m history picture)))))
           (swap! !state assoc :tick
                  (set-interval!
                    (fn [] (push! m (:history @!state) (:picture @!state)))
                    tick-interval-ms)))))
     !state)))

(defn detach!
  "Stop pushing into the map: dispose the reaction (which also releases
  the cached subscription) and cancel the client tick. Call from
  will-unmount, BEFORE the map is destroyed. Safe when the load event
  never fired: the pending load callback sees :disposed? and does
  nothing, so no track or tick was ever created."
  [!state]
  (let [{:keys [track tick]} @!state]
    (swap! !state assoc
           :disposed? true
           :track nil
           :tick nil)
    (some-> track r/dispose!)
    (some-> tick clear-interval!)))
