(ns adsb.map.selection
  "The selection mark — the dashed compass-pencil ring that draws itself
  in around the selected aircraft (design direction §4), plus the callsign
  label that sits under it for selected and hovered tracks (adsb-xgg).

  ## Why a DOM marker and not a style layer

  The ring's whole character is its ENTRANCE: dashes pressing into place
  once, like a pencil dragged around a compass, then holding still. That
  is a CSS animation (app.css, `adsb-ring-draw`), and CSS animates DOM,
  not MapLibre paint — a paint-property ring would either pop in or need
  per-frame paint updates on the render loop. So the ring is a single
  MapLibre MARKER carrying an SVG circle: MapLibre keeps it pinned to
  the chart through pans and zooms, CSS draws it in, and the element is
  REBUILT per selection so the animation replays for each new choice.

  The callsign label is the same marker family: one low-churn DOM pin
  under the plane (callsign chip on the live
  chart). Selection and hover share the pattern; when both point at the
  same aircraft the selection ring owns the label and hover is quiet.

  One or two markers, low churn — nothing like the aircraft layer's
  hundreds. Positions step at feeder cadence (~1 Hz); at map scales that
  step is a couple of pixels.

  ## Lifecycle

  Same shape as adsb.map.aircraft-layer: `attach!` starts a
  `reagent.core/track!` over the derived selection + hover subs —
  OUTSIDE any component, so selection churn costs zero React renders —
  and `detach!` disposes it and removes the markers. The map view owns
  both calls, re-creating the ring with the map on an edition flip.

  A selected (or hovered) aircraft with no position gets no mark: there
  is nowhere on the chart to draw one. The mark appears the moment a
  first position arrives, because the track re-runs on every picture
  change while something is selected or hovered."
  (:require
    [adsb.map.maplibre :as maplibre]
    [re-frame.core :as rf]
    [reagent.core :as r]))

(def ^:const ring-diameter-px
  "The ring element's box. Comfortably larger than the plane glyph at
  every perspective size, so the pencil never touches the ink."
  44)

(def ^:const svg-ns "http://www.w3.org/2000/svg")

(defn display-name
  "Callsign if the sky named it, else the hex. Pure so tests can pin it
  without a DOM."
  [{:aircraft/keys [callsign icao]}]
  (or callsign icao ""))

(defn ring-element
  "A fresh ring element: a fixed `ring-diameter-px` box (app.css:
  .adsb-selection-ring) holding an SVG dashed circle normalized to
  pathLength 70 — ten pencil dashes under the `adsb-ring-draw` entrance —
  and a callsign label absolutely positioned beneath so it never grows
  the layout box. MapLibre anchors the element's centre on the aircraft;
  if the label were in flow, the ring would sit above the plane (adsb-rg1).
  Built fresh per selection so the draw-in replays."
  [label]
  (let [el      (.createElement js/document "div")
        svg     (.createElementNS js/document svg-ns "svg")
        circle  (.createElementNS js/document svg-ns "circle")
        caption (.createElement js/document "span")
        r       (/ ring-diameter-px 2)
        d       (str ring-diameter-px "px")]
    (set! (.-className el) "adsb-selection-ring")
    ;; Inline size so the marker's centre is correct before CSS arrives
    ;; and independent of any in-flow caption.
    (set! (.-width (.-style el)) d)
    (set! (.-height (.-style el)) d)
    (.setAttribute svg "viewBox" (str "0 0 " ring-diameter-px " " ring-diameter-px))
    (.setAttribute svg "width" (str ring-diameter-px))
    (.setAttribute svg "height" (str ring-diameter-px))
    (.setAttribute svg "aria-hidden" "true")
    (.setAttribute circle "cx" (str r))
    (.setAttribute circle "cy" (str r))
    (.setAttribute circle "r" (str (- r 2)))
    (.setAttribute circle "pathLength" "70")
    (set! (.-className caption) "adsb-flight-label")
    (set! (.-textContent caption) (or label ""))
    (.appendChild svg circle)
    (.appendChild el svg)
    (.appendChild el caption)
    el))

(defn label-element
  "Hover-only pin: the callsign chip without the compass ring. Same
  caption class as the selection label so voice and geometry stay one."
  [label]
  (let [el      (.createElement js/document "div")
        caption (.createElement js/document "span")]
    (set! (.-className el) "adsb-hover-pin")
    (set! (.-className caption) "adsb-flight-label")
    (set! (.-textContent caption) (or label ""))
    (.appendChild el caption)
    el))

(defn- position->lng-lat
  "The aircraft's position as MapLibre's [lng lat], or nil when the sky
  never located it."
  [aircraft]
  (when-let [{:geo/keys [lat lon]} (:aircraft/position aircraft)]
    [lon lat]))

(defn- set-label!
  "Update the callsign text on a live marker element without rebuilding
  it — keeps the ring's settled ink still while the name (rarely) changes."
  [element label]
  (when-let [caption (some-> element (.querySelector ".adsb-flight-label"))]
    (when (not= (.-textContent caption) label)
      (set! (.-textContent caption) (or label "")))))

(defn- sync-marker!
  "Reconcile one marker slot. `build-el` produces a fresh DOM element when
  the icao changes (selection needs a fresh ring for the draw-in)."
  [m !state slot-key aircraft build-el]
  (let [slot    (get @!state slot-key)
        marker  (:marker slot)
        icao    (:icao slot)
        next-icao (:aircraft/icao aircraft)
        lng-lat (position->lng-lat aircraft)
        label   (when aircraft (display-name aircraft))]
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

(defn- clear-marker!
  [m !state slot-key]
  (when-let [marker (get-in @!state [slot-key :marker])]
    (maplibre/remove-marker! m marker)
    (swap! !state assoc slot-key {:marker nil :icao nil :element nil})))

(defn- sync!
  "Selection ring + optional hover label. When hover equals selection the
  selection pin already carries the name — no second chip."
  [m !state selected hovered]
  (if selected
    (sync-marker! m !state :selection selected ring-element)
    (clear-marker! m !state :selection))
  (if (and hovered
           (not= (:aircraft/icao hovered)
                 (:aircraft/icao selected)))
    (sync-marker! m !state :hover hovered label-element)
    (clear-marker! m !state :hover)))

(defn attach!
  "Start the ring (and hover label) over map `m`: a track! on the
  derived selection + hover picture joins, outside any component.
  Returns a handle for `detach!`."
  [m]
  (let [!state (atom {:selection {:marker nil :icao nil}
                      :hover     {:marker nil :icao nil}
                      :track     nil})]
    (swap! !state assoc :track
           (r/track!
             (fn []
               (let [selected @(rf/subscribe [:aircraft/selected])
                     hov-icao @(rf/subscribe [:aircraft/hovered-icao])
                     picture  @(rf/subscribe [:aircraft/picture])
                     hovered  (when hov-icao (get picture hov-icao))]
                 (sync! m !state selected hovered)))))
    !state))

(defn detach!
  "Dispose the track and remove both markers. Call before the map is
  destroyed — the view tears ring and map down together."
  [m !state]
  (let [{:keys [track selection hover]} @!state]
    (swap! !state assoc
           :track nil
           :selection {:marker nil :icao nil}
           :hover     {:marker nil :icao nil})
    (some-> track r/dispose!)
    (some->> (:marker selection) (maplibre/remove-marker! m))
    (some->> (:marker hover) (maplibre/remove-marker! m))))
