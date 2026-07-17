(ns adsb.map.selection
  (:require [adsb.aircraft :as aircraft]
            [adsb.corejs :as cjs]
            [adsb.map.maplibre :as maplibre]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def ^:const ring-diameter-px 44)

(def ^:const svg-ns "http://www.w3.org/2000/svg")

(defn ring-element [label]
  (let [el      (cjs/create-element "div")
        svg     (cjs/create-element-ns svg-ns "svg")
        circle  (cjs/create-element-ns svg-ns "circle")
        caption (cjs/create-element "span")
        r       (/ ring-diameter-px 2)
        d       (str ring-diameter-px "px")]
    (set! (.-className el) "adsb-selection-ring")
    (set! (.-width (.-style el)) d)
    (set! (.-height (.-style el)) d)
    (cjs/set-attributes! svg {"viewBox"     (str "0 0 " ring-diameter-px " " ring-diameter-px)
                             "width"       (str ring-diameter-px)
                             "height"      (str ring-diameter-px)
                             "aria-hidden" "true"})
    (cjs/set-attributes! circle {"cx"         (str r)
                                "cy"         (str r)
                                "r"          (str (- r 2))
                                "pathLength" "70"})
    (set! (.-className caption) "adsb-flight-label")
    (set! (.-textContent caption) (or label ""))
    (cjs/append-child! svg circle)
    (cjs/append-children! el [svg caption])
    el))

(defn label-element [label]
  (let [el      (cjs/create-element "div")
        caption (cjs/create-element "span")]
    (set! (.-className el) "adsb-hover-pin")
    (set! (.-className caption) "adsb-flight-label")
    (set! (.-textContent caption) (or label ""))
    (cjs/append-child! el caption)
    el))

(defn- position->lng-lat [aircraft]
  (when-let [{:geo/keys [lat lon]} (:aircraft/position aircraft)]
    [lon lat]))

(defn- set-label! [element label]
  (when-let [caption (some-> element (cjs/select ".adsb-flight-label"))]
    (when (not= (.-textContent caption) label)
      (set! (.-textContent caption) (or label "")))))

(defn- sync-marker! [m !state slot-key aircraft build-el]
  (let [slot      (get @!state slot-key)
        marker    (:marker slot)
        icao      (:icao slot)
        next-icao (:aircraft/icao aircraft)
        lng-lat   (position->lng-lat aircraft)
        label     (or (some-> aircraft aircraft/display-name) "")]
    (cond
      (nil? lng-lat)
      (when marker
        (maplibre/remove-marker! m marker)
        (swap! !state assoc slot-key {:marker nil :icao nil}))

      (and marker (= icao next-icao))
      (do (maplibre/move-marker! m marker lng-lat)
          (when-let [el (:element slot)]
            (set-label! el label)))

      :else
      (do (some->> marker (maplibre/remove-marker! m))
          (let [el (build-el label)]
            (swap! !state assoc slot-key
                   {:marker  (maplibre/add-marker! m el lng-lat)
                    :element el
                    :icao    next-icao}))))))

(defn- clear-marker! [m !state slot-key]
  (when-let [marker (get-in @!state [slot-key :marker])]
    (maplibre/remove-marker! m marker)
    (swap! !state assoc slot-key {:marker nil :icao nil :element nil})))

(defn- sync! [m !state selected hovered]
  (if selected
    (sync-marker! m !state :selection selected ring-element)
    (clear-marker! m !state :selection))
  (if (and hovered
           (not= (:aircraft/icao hovered)
                 (:aircraft/icao selected)))
    (sync-marker! m !state :hover hovered label-element)
    (clear-marker! m !state :hover)))

(defn attach! [m]
  (let [!state (atom {:selection {:marker nil :icao nil}
                      :hover     {:marker nil :icao nil}
                      :track     nil})]
    (swap! !state assoc :track
           (r/track!
             (fn []
               (let [selected @(rf/subscribe [:aircraft/selected])
                     hov-icao @(rf/subscribe [:aircraft/hovered-icao])
                     picture  @(rf/subscribe [:aircraft/picture])
                     hovered  (some->> hov-icao (get picture))]
                 (sync! m !state selected hovered)))))
    !state))

(defn detach! [m !state]
  (let [{:keys [track selection hover]} @!state]
    (swap! !state assoc
           :track nil
           :selection {:marker nil :icao nil}
           :hover {:marker nil :icao nil})
    (some-> track r/dispose!)
    (some->> (:marker selection) (maplibre/remove-marker! m))
    (some->> (:marker hover) (maplibre/remove-marker! m))))
