(ns adsb.map.selection
  "The selection mark — the dashed compass-pencil ring that draws itself
  in around the selected aircraft (design direction §4).

  ## Why a DOM marker and not a style layer

  The ring's whole character is its ENTRANCE: dashes pressing into place
  once, like a pencil dragged around a compass, then holding still. That
  is a CSS animation (app.css, `adsb-ring-draw`), and CSS animates DOM,
  not MapLibre paint — a paint-property ring would either pop in or need
  per-frame paint updates on the render loop. So the ring is a single
  MapLibre MARKER carrying an SVG circle: MapLibre keeps it pinned to
  the chart through pans and zooms, CSS draws it in, and the element is
  REBUILT per selection so the animation replays for each new choice.

  One marker, low churn — nothing like the aircraft layer's hundreds.
  The SELECTED aircraft's reported position moves at feeder cadence
  (~1 Hz) and the marker steps with it; at map scales that step is a
  couple of pixels, far beneath the glide threshold that matters for
  the planes themselves.

  ## Lifecycle

  Same shape as adsb.map.aircraft-layer: `attach!` starts a
  `reagent.core/track!` over the derived `:aircraft/selected` sub —
  OUTSIDE any component, so selection churn costs zero React renders —
  and `detach!` disposes it and removes the marker. The map view owns
  both calls, re-creating the ring with the map on an edition flip.

  A selected aircraft with no position (heard, never located) gets no
  ring: there is nowhere on the chart to draw one. The ring appears the
  moment a first position arrives, because the track re-runs on every
  picture change while something is selected."
  (:require
    [adsb.map.maplibre :as maplibre]
    [re-frame.core :as rf]
    [reagent.core :as r]))

(def ^:const ring-diameter-px
  "The ring element's box. Comfortably larger than the plane glyph at
  every perspective size, so the pencil never touches the ink."
  44)

(def ^:const svg-ns "http://www.w3.org/2000/svg")

(defn ring-element
  "A fresh ring element: a div (app.css: .adsb-selection-ring) holding
  an SVG dashed circle normalized to pathLength 70 — ten pencil dashes
  under the `adsb-ring-draw` entrance. Built fresh per selection so the
  draw-in replays; static markup, no feeder data anywhere near it."
  []
  (let [el     (js/document.createElement "div")
        svg    (js/document.createElementNS svg-ns "svg")
        circle (js/document.createElementNS svg-ns "circle")
        r      (/ ring-diameter-px 2)]
    (set! (.-className el) "adsb-selection-ring")
    (.setAttribute svg "viewBox" (str "0 0 " ring-diameter-px " " ring-diameter-px))
    (.setAttribute svg "aria-hidden" "true")
    (.setAttribute circle "cx" (str r))
    (.setAttribute circle "cy" (str r))
    (.setAttribute circle "r" (str (- r 2)))
    (.setAttribute circle "pathLength" "70")
    (.appendChild svg circle)
    (.appendChild el svg)
    el))

(defn- position->lng-lat
  "The aircraft's position as MapLibre's [lng lat], or nil when the sky
  never located it."
  [aircraft]
  (when-let [{:geo/keys [lat lon]} (:aircraft/position aircraft)]
    [lon lat]))

(defn- sync-ring!
  "Reconcile the marker with the current selection: absent (or
  position-less) selection removes it; a NEW selection draws a fresh
  ring (fresh element — the animation replays); the same selection
  moving steps the marker with it."
  [m !state aircraft]
  (let [{:keys [marker icao]} @!state
        next-icao (:aircraft/icao aircraft)
        lng-lat   (position->lng-lat aircraft)]
    (cond
      (nil? lng-lat)
      (when marker
        (maplibre/remove-marker! m marker)
        (swap! !state assoc :marker nil :icao nil))

      (and marker (= icao next-icao))
      (maplibre/move-marker! m marker lng-lat)

      :else
      (do (when marker
            (maplibre/remove-marker! m marker))
          (swap! !state assoc
                 :marker (maplibre/add-marker! m (ring-element) lng-lat)
                 :icao   next-icao)))))

(defn attach!
  "Start the ring over map `m`: a track! on the derived
  :aircraft/selected sub, outside any component. Returns a handle for
  `detach!`."
  [m]
  (let [!state (atom {:marker nil :icao nil :track nil})]
    (swap! !state assoc :track
           (r/track!
             (fn []
               (sync-ring! m !state @(rf/subscribe [:aircraft/selected])))))
    !state))

(defn detach!
  "Dispose the track and remove the marker. Call before the map is
  destroyed — the view tears ring and map down together."
  [m !state]
  (let [{:keys [track marker]} @!state]
    (swap! !state assoc :track nil :marker nil :icao nil)
    (when track
      (r/dispose! track))
    (when marker
      (maplibre/remove-marker! m marker))))
