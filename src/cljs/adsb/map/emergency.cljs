(ns adsb.map.emergency
  (:require [adsb.corejs :as cjs]
            [adsb.geo :as geo]
            [adsb.map.maplibre :as maplibre]
            [adsb.ui.alert :as alert]
            [clojure.math :as math]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def ^:const svg-ns "http://www.w3.org/2000/svg")
(def ^:const ellipse-box-px 84)
(def ^:const draw-in-ms 450)
(def ^:const second-pass-delay-ms 160)
(def ^:const notam-strip-px 36)
(def ^:const roster-px 300)
(def ^:const roster-phone-rail-px 48)
(def ^:const arrow-half-width-px 80)
(def ^:const arrow-half-height-px 18)
(def ^:const edge-air-px 8)
(def ^:const phone-max-width-px 640)
(def ^:const em-dash "—")

(defn- altitude-text [{:aircraft/keys [on-ground? altitude-ft]}]
  (cond
    on-ground? "ground"
    altitude-ft (str altitude-ft " ft")
    :else em-dash))

(defn- vertical-rate-text [{:aircraft/keys [baro-rate-fpm]}]
  (cond
    (nil? baro-rate-fpm) em-dash
    (neg? baro-rate-fpm) (str "↓" (- baro-rate-fpm) " fpm")
    (pos? baro-rate-fpm) (str "↑" baro-rate-fpm " fpm")
    :else "level"))

(defn- mayday-line [aircraft*]
  (str "mayday · " (or (alert/aircraft-alert aircraft*) "emergency")))

(defn- display-name [{:aircraft/keys [callsign icao]}] (or callsign icao))

(defn- distance-text [distance-m]
  (-> (geo/meters->nm distance-m)
      math/round
      (str " nm")))

(defn- set-draw-in! [node delay-ms]
  (let [style (.-style node)]
    (set! (.-animationName style) "adsb-mayday-draw")
    (set! (.-animationDuration style) (str draw-in-ms "ms"))
    (set! (.-animationTimingFunction style) "ease-out")
    (set! (.-animationDelay style) (str delay-ms "ms"))
    (set! (.-animationIterationCount style) "1")
    (set! (.-animationFillMode style) "backwards")))

(defn- ellipse-node [cx cy rx ry rotation-deg delay-ms]
  (doto (js-invoke js/document "createElementNS" svg-ns "ellipse")
    (cjs/set-attributes {"cx"               (str cx)
                         "cy"               (str cy)
                         "rx"               (str rx)
                         "ry"               (str ry)
                         "pathLength"       "100"
                         "stroke-dasharray" "100"
                         "transform"        (str "rotate(" rotation-deg " " cx " " cy ")")})
    (set-draw-in! delay-ms)))

(defn- stamp-offset [track-deg]
  (let [theta (* (+ (or track-deg 0) 90) (/ math/PI 180))]
    [(* 106 (math/sin theta))
     (* -74 (math/cos theta))]))

(defn- span-node [class-name]
  (let [node (cjs/create-element "span")]
    (set! (.-className node) class-name)
    node))

(defn- label-node [text]
  (let [node (span-node "adsb-mayday-label")]
    (set! (.-textContent node) text)
    node))

(defn mayday-element [aircraft*]
  (let [el          (cjs/create-element "div")
        svg         (cjs/create-element-ns svg-ns "svg")
        centre      (/ ellipse-box-px 2)
        stamp       (cjs/create-element "div")
        facts       (cjs/create-element "div")
        word-el     (span-node "adsb-mayday-word")
        callsign-el (span-node "adsb-mayday-callsign")
        altitude-el (span-node "adsb-mayday-value")
        rate-el     (span-node "adsb-mayday-value")
        [dx dy] (stamp-offset (:aircraft/track-deg aircraft*))]
    (set! (.-className el) "adsb-mayday")
    (cjs/set-attribute svg "viewBox" (str "0 0 " ellipse-box-px " " ellipse-box-px))
    (cjs/set-attribute svg "aria-hidden" "true")
    (cjs/append-children svg [(ellipse-node centre centre 34 26 -8 0)
                              (ellipse-node centre centre 31 28 5 second-pass-delay-ms)])
    (cjs/append-child el svg)
    (set! (.-className stamp) "adsb-mayday-stamp")
    (set! (-> stamp .-style .-left) (str "calc(50% + " dx "px)"))
    (set! (-> stamp .-style .-top) (str "calc(50% + " dy "px)"))
    (set! (.-className facts) "adsb-mayday-facts")
    (cjs/append-children facts [(label-node "alt") altitude-el (label-node "v/s") rate-el])
    (cjs/append-children stamp [word-el callsign-el facts])
    (cjs/append-child el stamp)
    {:el          el
     :word-el     word-el
     :callsign-el callsign-el
     :altitude-el altitude-el
     :rate-el     rate-el}))

(defn- update-stamp! [{:keys [word-el callsign-el altitude-el rate-el]} aircraft*]
  (set! (.-textContent word-el) (mayday-line aircraft*))
  (set! (.-textContent callsign-el) (display-name aircraft*))
  (set! (.-textContent altitude-el) (altitude-text aircraft*))
  (set! (.-textContent rate-el) (vertical-rate-text aircraft*)))

(defn- on-arrow-click! [event]
  (when-let [icao (some-> (.-currentTarget event)
                          (js-invoke "getAttribute" "data-icao"))]
    (rf/dispatch [:aircraft/select icao])))

(defn arrow-element [icao]
  (let [el          (cjs/create-element "button")
        glyph-el    (cjs/create-element-ns svg-ns "svg")
        head        (cjs/create-element-ns svg-ns "path")
        callsign-el (span-node "adsb-edge-arrow-callsign")
        distance-el (span-node "adsb-edge-arrow-distance")]
    (set! (.-type el) "button")
    (set! (.-className el) "adsb-edge-arrow")
    (cjs/set-attribute el "data-icao" icao)
    (cjs/set-attribute el "data-testid" (str "edge-arrow:" icao))
    (cjs/add-listener el "click" on-arrow-click!)
    (cjs/set-attribute glyph-el "class" "adsb-edge-arrow-glyph")
    (cjs/set-attribute glyph-el "viewBox" "0 0 16 16")
    (cjs/set-attribute glyph-el "aria-hidden" "true")
    (js-invoke head "setAttribute" "d" "M8 1.5 L13 12.5 L8 9.6 L3 12.5 Z")
    (cjs/append-child glyph-el head)
    (cjs/append-children el [glyph-el callsign-el distance-el])
    {:el          el :glyph-el glyph-el :callsign-el callsign-el
     :distance-el distance-el}))

(defn- update-arrow! [{:keys [el glyph-el callsign-el distance-el]} aircraft* edge]
  (let [name*    (display-name aircraft*)
        distance (distance-text (:edge/distance-m edge))]
    (set! (-> glyph-el .-style .-transform)
          (str "rotate(" (:edge/bearing-deg edge) "deg)"))
    (set! (.-textContent callsign-el) name*)
    (set! (.-textContent distance-el) distance)
    (js-invoke el "setAttribute" "aria-label"
               (str "Emergency: " name* ", " distance " off screen. Select aircraft."))))

(defn- roster-width-px []
  (or (some-> (cjs/select js/document ".adsb-roster") .-offsetWidth)
      roster-px))

(defn- chrome-insets-px []
  (let [phone? (<= (.-innerWidth js/window) phone-max-width-px)
        safe-t (cjs/css-px "--safe-top")
        safe-r (cjs/css-px "--safe-right")
        safe-b (cjs/css-px "--safe-bottom")
        safe-l (cjs/css-px "--safe-left")]
    {:top    (+ safe-t notam-strip-px arrow-half-height-px edge-air-px)
     :right  (+ safe-r (if phone? 0 (roster-width-px)) arrow-half-width-px edge-air-px)
     :bottom (+ safe-b (if phone? roster-phone-rail-px 0) arrow-half-height-px edge-air-px)
     :left   (+ safe-l arrow-half-width-px edge-air-px)}))

(defn- edge->lng-lat [{:geo/keys [min-lat max-lat min-lon max-lon]} {:edge/keys [x y]}]
  (let [{:keys [top right bottom left]} (chrome-insets-px)
        width  (max 1 (.-innerWidth js/window))
        height (max 1 (.-innerHeight js/window))
        x*     (-> x (max (/ left width)) (min (- 1 (/ right width))))
        y*     (-> y (max (/ top height)) (min (- 1 (/ bottom height))))]
    [(+ min-lon (* x* (- max-lon min-lon)))
     (- max-lat (* y* (- max-lat min-lat)))]))

(defn- place! [m !state icao aircraft* mode lng-lat edge entry]
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

(defn- sync-one! [m !state viewport-bounds aircraft*]
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

(defn- sync! [m !state emergencies]
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

(defn attach! [m]
  (let [!state (atom {:entries     {}
                      :emergencies nil
                      :track       nil
                      :disposed?   false})]
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

(defn detach! [m !state]
  (let [{:keys [track entries]} @!state]
    (swap! !state assoc :disposed? true :track nil :entries {})
    (some-> track r/dispose!)
    (doseq [[_icao {:keys [marker]}] entries]
      (maplibre/remove-marker! m marker))))
