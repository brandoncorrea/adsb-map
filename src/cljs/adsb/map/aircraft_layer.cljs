(ns adsb.map.aircraft-layer
  "The imperative aircraft layer — the architectural centerpiece of the
  frontend. Aircraft flow from SSE frame to pixels WITHOUT EVER TOUCHING
  REACT: hundreds of aircraft at 1 Hz would crawl as Reagent components,
  so each frame is converted (adsb.geo) and pushed straight into a
  MapLibre GeoJSON source via `set-source-data!`. re-frame owns the
  chrome; the map owns the planes.

  ## The hot path

  `attach!` registers a callback on the map's load event. When it fires,
  the GeoJSON source and a neutral circle layer are added, and a
  `reagent.core/track!` starts over the `:aircraft/picture` subscription
  — a reaction living OUTSIDE any component. Every picture change re-runs
  the track, which converts and pushes one FeatureCollection across the
  seam. No component subscribes to the picture, so a frame costs ZERO
  Reagent re-renders; the proof (render-counting under N dispatches)
  lives in adsb.map.aircraft-layer-test.

  Reaction propagation rides Reagent's batching (one flush per animation
  frame), so multiple same-flush frames coalesce into a single setData
  carrying the newest picture. At 1 Hz this never happens; under load it
  is exactly the right degradation — latest wins, no queue builds up.

  ## Ordering: SSE frames can beat the map's load event

  The stream starts with the app shell; MapLibre's load fires only once
  the style is fetched. Early frames simply land in app-db — the picture
  is wholesale-replaced per frame (adsb.stream), so app-db IS the
  latest-wins buffer — and the track's initial run at load time flushes
  whatever picture is then current. Nothing is replayed, nothing is
  lost, and no frame is ever pushed at a map without the source.

  ## The clock

  The `stale` feature property is judged against `now-ms`, read from
  js/Date.now at push time — i.e. the frame's arrival instant, since a
  push happens when the picture changes. The imperative browser edge is
  allowed a clock; the domain is not — adsb.geo and adsb.aircraft take
  time as an argument, which is why tests can redef `now-ms` and assert
  staleness against a literal.

  ## Styling

  NEUTRAL here, deliberately. The properties styling needs — icao,
  callsign, track, altitude, emergency, stale — already reach the source
  on every feature (adsb.geo puts them there), so adsb-2yu.5 is pure
  MapLibre style-expression work on `layer-spec`. No plumbing left."
  (:require
    [adsb.geo :as geo]
    [adsb.map.maplibre :as maplibre]
    [re-frame.core :as rf]
    [reagent.core :as r]))

(def ^:const source-id "aircraft")
(def ^:const layer-id "aircraft")

(def empty-feature-collection
  {:type "FeatureCollection" :features []})

(def source-spec
  "The aircraft GeoJSON source, born empty at map load. Every SSE frame
  thereafter replaces its data wholesale via `set-source-data!`."
  {:type "geojson" :data empty-feature-collection})

(def layer-spec
  "One neutral circle per positioned aircraft — placement only. The
  data-driven artistry (altitude ramp, heading, emergency, stale fade)
  is adsb-2yu.5 and lands in this spec's paint/layout expressions."
  {:id     layer-id
   :type   "circle"
   :source source-id
   :paint  {:circle-radius       4
            :circle-color        "#333333"
            :circle-stroke-width 1
            :circle-stroke-color "#ffffff"}})

(defn now-ms
  "The frame's arrival instant, read at the imperative edge — the ONE
  place the frontend hot path touches a clock. The domain takes time as
  an argument; tests redef this for deterministic staleness."
  []
  (js/Date.now))

(defn picture->feature-collection
  "The app-db picture (icao -> domain aircraft) as a GeoJSON
  FeatureCollection, staleness judged at `at-ms`. Pure — delegates to
  adsb.geo; never-positioned aircraft contribute no feature."
  [picture at-ms]
  (geo/aircraft-picture->feature-collection (vals picture) at-ms))

(defn- push!
  "Convert the picture and hand it across the seam. Clojure data in;
  the seam does the clj->js at the very edge."
  [m picture]
  (maplibre/set-source-data! m source-id (picture->feature-collection picture (now-ms))))

(defn attach!
  "Wire the aircraft layer onto map `m` (an adsb.map.maplibre/Map). Call
  once, from the map component's did-mount. Returns a handle for
  `detach!`. Everything waits on the map's load event; frames arriving
  before it are covered by app-db's latest-wins buffering (ns docstring)."
  [m]
  (let [!state (atom {:disposed? false :track nil})]
    (maplibre/on-load!
      m
      (fn []
        (when-not (:disposed? @!state)
          (maplibre/add-source! m source-id source-spec)
          (maplibre/add-layer! m layer-spec)
          ;; The hot path: a reaction OUTSIDE any component. Its initial
          ;; run flushes whatever picture already arrived; each re-run is
          ;; one picture change -> one setData. React never hears of it.
          (swap! !state assoc :track
                 (r/track! (fn [] (push! m @(rf/subscribe [:aircraft/picture]))))))))
    !state))

(defn detach!
  "Stop pushing into the map and dispose the reaction (which also
  releases the cached subscription). Call from will-unmount, BEFORE the
  map is destroyed. Safe when the load event never fired: the pending
  load callback sees :disposed? and does nothing."
  [!state]
  (let [{:keys [track]} @!state]
    (swap! !state assoc :disposed? true :track nil)
    (when track
      (r/dispose! track))))
