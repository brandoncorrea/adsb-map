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

  The `stale`/`age-s` feature properties are judged against `now-ms`,
  read from js/Date.now at push time — i.e. the frame's arrival instant,
  since a push happens when the picture changes. The imperative browser
  edge is allowed a clock; the domain is not — adsb.geo and adsb.aircraft
  take time as an argument, which is why tests can redef `now-ms` and
  assert staleness against a literal.

  ## The client tick — aging between frames

  A push happens when the picture CHANGES, but silence is the absence of
  change: a silent aircraft's fade would freeze between frames, and if
  the stream stalls entirely it would freeze forever, never disappearing.
  So `attach!` also starts a coarse interval (`tick-interval-ms`) that
  re-pushes the CURRENT picture through the same setData path with a
  fresh `now-ms`. Nothing about the picture changed — only time did — so
  the fade advances and aircraft past the age-out line drop out of the
  FeatureCollection (adsb.geo filters them with adsb.aircraft/aged-out?).
  The server still ages aircraft out authoritatively; this only keeps the
  view honest in the gaps. The tick is imperative, owned by the
  attach!/detach! lifecycle, never React, and it stops on detach. Its
  scheduling goes through the `set-interval!`/`clear-interval!` seams so
  tests drive it with a fake clock instead of waiting real seconds.

  ## Styling and the icon assets

  The layer is a MapLibre SYMBOL layer whose paint/layout are pure style
  expressions over each feature's properties (icao, callsign, track,
  altitude, emergency, stale, age-s, mlat — adsb.geo puts them there). All of that
  data — the colour ramp, the sizes, the expressions — lives in
  `adsb.map.style`; this namespace only WIRES it. The icon the layer
  names in `icon-image` is not a sprite fetched over the network: at load
  we draw two silhouettes (a directional plane, a non-directional dot) on
  a detached canvas and register their ImageData via the seam's
  `add-image!` as SDF images, so `icon-color` can tint them by altitude.
  No asset file, no network round-trip, no sprite sheet.

  ## The click contract (this layer owns it)

  A click on an aircraft feature dispatches `[:aircraft/select icao]`,
  the icao read from the clicked feature's properties. That is the whole
  contract: the layer emits the intent; a concurrent bead (adsb-dgb.1)
  registers the event and builds the panel. We do not build UI here and
  we do not register the event — we only fire it, through the seam, so
  the map's raw click never leaks past `adsb.map.maplibre`."
  (:require
    [adsb.geo :as geo]
    [adsb.map.maplibre :as maplibre]
    [adsb.map.style :as style]
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
  "The aircraft symbol layer: a plane silhouette per positioned aircraft,
  rotated by track, coloured by the altitude ramp, enlarged and reddened
  for emergencies, faded when stale. Every knob is data in
  `adsb.map.style` — this is just the wiring."
  (style/aircraft-layer-spec layer-id source-id))

;; ---------------------------------------------------------------------
;; The icon assets. We draw the silhouettes ourselves rather than ship a
;; sprite: a detached canvas, a white fill on transparent, read back as
;; ImageData, registered SDF so `icon-color` can tint it. White is
;; arbitrary — SDF uses only the alpha channel as the mask.

(def ^:const icon-size-px
  "Canvas edge for the drawn icons. 32 px reads crisply at the sizes the
  style scales to; SDF resamples smoothly if a future skin scales past it."
  32)

;; Normalized [x y] silhouette of a plane pointing UP (north, track 0),
;; y increasing downward — right half only, nose and tail-tip on the
;; axis. DATA: the shape is visual design and re-skins here. The left
;; half is mirrored at draw time, so the plane is always symmetric.
(def ^:private plane-half-outline
  [[0.50 0.06]    ; nose (on axis)
   [0.56 0.42]    ; wing root, leading edge
   [0.96 0.64]    ; wingtip
   [0.96 0.72]    ; wingtip, trailing
   [0.56 0.56]    ; wing root, trailing edge
   [0.55 0.84]    ; fuselage, before the tail
   [0.74 0.95]    ; tailplane tip
   [0.74 1.00]    ; tailplane tip, trailing
   [0.50 0.92]])  ; tail centre (on axis)

(defn- draw-plane!
  "Trace the mirrored plane silhouette and fill it."
  [ctx size]
  (let [mirrored (->> (rest (butlast plane-half-outline))
                      reverse
                      (map (fn [[x y]] [(- 1.0 x) y])))
        pts      (concat plane-half-outline mirrored)]
    (.beginPath ctx)
    (doseq [[i [x y]] (map-indexed vector pts)]
      (if (zero? i)
        (.moveTo ctx (* x size) (* y size))
        (.lineTo ctx (* x size) (* y size))))
    (.closePath ctx)
    (.fill ctx)))

(defn- draw-dot!
  "A filled disc — the heading-agnostic marker for a track-less aircraft."
  [ctx size]
  (let [c (/ size 2)]
    (.beginPath ctx)
    (.arc ctx c c (* size 0.32) 0 (* 2 js/Math.PI))
    (.fill ctx)))

(defn ->icon-image
  "Draw `draw!` (a fn of ctx and size) on a fresh detached canvas and
  return its ImageData — white on transparent, ready to register SDF. No
  visible DOM: the canvas is never attached."
  [draw!]
  (let [canvas (js/document.createElement "canvas")]
    (set! (.-width canvas) icon-size-px)
    (set! (.-height canvas) icon-size-px)
    (let [ctx (.getContext canvas "2d")]
      (set! (.-fillStyle ctx) "#ffffff")
      (draw! ctx icon-size-px)
      (.getImageData ctx 0 0 icon-size-px icon-size-px))))

(defn- register-icons!
  "Register the plane and dot silhouettes as SDF images, so the symbol
  layer can name them and `icon-color` can tint them. Must run before the
  layer is added, or MapLibre reports a missing image."
  [m]
  (maplibre/add-image! m style/plane-icon-id (->icon-image draw-plane!) {:sdf true})
  (maplibre/add-image! m style/dot-icon-id (->icon-image draw-dot!) {:sdf true}))

(defn- select!
  "The click contract: dispatch selection with the icao from the clicked
  feature's properties. We emit the intent only — adsb-dgb.1 owns the
  event handler and the panel."
  [props]
  (rf/dispatch [:aircraft/select (:icao props)]))

(defn now-ms
  "The frame's arrival instant, read at the imperative edge — the ONE
  place the frontend hot path touches a clock. The domain takes time as
  an argument; tests redef this for deterministic staleness."
  []
  (js/Date.now))

(def ^:const tick-interval-ms
  "How often the client re-pushes the current picture so silent aircraft
  keep aging between frames. Coarse — the fade is a slow visual, not a
  hot path — so a re-push every few seconds is ample and cheap."
  5000)

(defn set-interval!
  "Schedule `f` to run every `ms`, returning a handle for `clear-interval!`.
  A seam over js/setInterval so tests capture the tick and drive it with a
  fake clock rather than waiting real seconds."
  [f ms]
  (js/setInterval f ms))

(defn clear-interval!
  "Cancel the interval named by `id` (a `set-interval!` handle)."
  [id]
  (js/clearInterval id))

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
  (let [!state (atom {:disposed? false :track nil :tick nil :picture nil})]
    (maplibre/on-load!
      m
      (fn []
        (when-not (:disposed? @!state)
          ;; Icons first — the layer names them in `icon-image`, and
          ;; MapLibre warns on a layer that references an absent image.
          (register-icons! m)
          (maplibre/add-source! m source-id source-spec)
          (maplibre/add-layer! m layer-spec)
          ;; The click contract and its hover affordance, through the
          ;; seam. We fire `[:aircraft/select icao]`; adsb-dgb.1 handles it.
          (maplibre/on-layer-click! m layer-id select!)
          (maplibre/on-layer-hover-cursor! m layer-id)
          ;; The hot path: a reaction OUTSIDE any component. Its initial
          ;; run flushes whatever picture already arrived; each re-run is
          ;; one picture change -> one setData. React never hears of it.
          ;; It also caches the latest picture in !state so the
          ;; time-driven tick can re-push it WITHOUT subscribing outside a
          ;; reactive context (the subscription lives here, in the track).
          (swap! !state assoc :track
                 (r/track!
                   (fn []
                     (let [picture @(rf/subscribe [:aircraft/picture])]
                       (swap! !state assoc :picture picture)
                       (push! m picture)))))
          ;; The client tick: re-push the cached picture on a coarse
          ;; interval so silent aircraft keep aging between frames and
          ;; through a stream stall. Nothing about the picture changed —
          ;; only time did — so the fresh now-ms deepens the fade and drops
          ;; anything past the age-out line (adsb.geo filters it).
          (swap! !state assoc :tick
                 (set-interval!
                   (fn [] (push! m (:picture @!state)))
                   tick-interval-ms)))))
    !state))

(defn detach!
  "Stop pushing into the map: dispose the reaction (which also releases
  the cached subscription) and cancel the client tick. Call from
  will-unmount, BEFORE the map is destroyed. Safe when the load event
  never fired: the pending load callback sees :disposed? and does
  nothing, so no track or tick was ever created."
  [!state]
  (let [{:keys [track tick]} @!state]
    (swap! !state assoc :disposed? true :track nil :tick nil)
    (when track
      (r/dispose! track))
    (when tick
      (clear-interval! tick))))
