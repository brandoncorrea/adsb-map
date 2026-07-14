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

  There is no prediction (adsb-a4g): an aircraft renders at its last REAL
  position until the next frame arrives, hopping at feed cadence. The two
  push paths — the track on a real frame, the tick on the clock — both
  derive from the same `!state` cache (the track is the only subscriber;
  the tick reads the cache, never a subscription) and both funnel through
  the same seam. A real frame pushes reported positions; the 5 s tick
  re-pushes the same positions with a fresh clock so the fade advances
  and aged-out aircraft drop even through a stream stall.

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
    [adsb.map.theme :as theme]
    [adsb.trails :as trails]
    [re-frame.core :as rf]
    [reagent.core :as r]))

(def ^:const source-id "aircraft")
(def ^:const layer-id "aircraft")

(def ^:const trail-source-id "aircraft-trails")
(def ^:const trail-layer-id "aircraft-trails")

(def empty-feature-collection
  {:type "FeatureCollection" :features []})

(def source-spec
  "The aircraft GeoJSON source, born empty at map load. Every SSE frame
  thereafter replaces its data wholesale via `set-source-data!`."
  {:type "geojson" :data empty-feature-collection})

(def trail-source-spec
  "The trail GeoJSON source, born empty at map load. `lineMetrics true` is
  REQUIRED: it is what makes MapLibre compute `line-progress` so the
  trail's `line-gradient` (adsb.map.style) can fade tail-to-head. Every
  push replaces its data wholesale, exactly like the aircraft source."
  {:type "geojson" :lineMetrics true :data empty-feature-collection})

(defn layer-spec
  "The aircraft symbol layer, printed in `theme`'s edition: a plane
  silhouette per positioned aircraft, rotated by track, coloured by the
  altitude ramp, enlarged and reddened for emergencies, faded when
  stale. Every knob is data in `adsb.map.style` — this is just the
  wiring."
  [theme]
  (style/aircraft-layer-spec theme layer-id source-id))

(defn trail-layer-spec
  "The trail line layer, printed in `theme`'s edition: a fading ink
  ribbon behind each aircraft. Added BELOW the aircraft layer so the
  ribbon sits under the target. Style is data in `adsb.map.style`; this
  is just the wiring."
  [theme]
  (style/trail-layer-spec theme trail-layer-id trail-source-id))

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
;;
;; The area centroid sits on the canvas centre (0.5, 0.5), not the
;; bounding-box centre. An earlier outline put the wings low so most of
;; the ink sat below the MapLibre icon anchor — the selection ring
;; (anchored on the same lat/lon) then looked like it sat above the
;; plane (adsb-89w). Wings near mid-canvas keep the visual mass on the
;; pin the ring shares.
(def ^:private plane-half-outline
  [[0.50 0.04]    ; nose (on axis)
   [0.55 0.33]    ; wing root, leading edge
   [0.88 0.52]    ; wingtip
   [0.88 0.58]    ; wingtip, trailing
   [0.55 0.45]    ; wing root, trailing edge
   [0.54 0.68]    ; fuselage, before the tail
   [0.70 0.77]    ; tailplane tip
   [0.70 0.81]    ; tailplane tip, trailing
   [0.50 0.74]])  ; tail centre (on axis)

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
  (let [canvas (.createElement js/document "canvas")]
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

;; Re-click on the already-selected plane arms a delayed deselect so a
;; double-click (detail 2 / dblclick event) can cancel it and mean follow
;; instead. Fresh selections fire immediately.
(def ^:private deselect-arm-ms 350)
(defonce ^:private !pending-deselect (atom nil))

;; click detail≥2 and the separate dblclick event often both fire for
;; one gesture; without a short gate that would toggle follow ON then
;; immediately OFF.
(def ^:private follow-dedupe-ms 400)
(defonce ^:private !last-follow-ms (atom 0))

(defn- cancel-pending-deselect!
  []
  (when-let [id @!pending-deselect]
    (js/clearTimeout id)
    (reset! !pending-deselect nil)))

(defn- dblclick-follow!
  "Double-click / multi-click on a plane: toggle focus-follow (adsb-jg4).
  Cancels any armed deselect from the first click of the gesture.
  Dedupes when both click(detail≥2) and dblclick arrive for one gesture."
  [props]
  (cancel-pending-deselect!)
  (when-let [icao (:icao props)]
    (let [now (.now js/Date)]
      (when (> (- now @!last-follow-ms) follow-dedupe-ms)
        (reset! !last-follow-ms now)
        (rf/dispatch [:aircraft/dblclick-follow icao])))))

(defn- select!
  "The click contract. detail 1: select (or arm deselect if already lit).
  detail ≥ 2: follow toggle — the second half of a double-click, so we
  do not wait on the separate dblclick event (which MapLibre / the
  browser can drop or race). Emits intent only; events own app-db."
  [props]
  (let [detail (or (:click/detail props) 1)
        icao   (:icao props)]
    (when icao
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

(defn- hover-enter!
  "Map pointer over a plane → the same hover channel the roster rows use,
  so the selection module can pin a callsign label (adsb-xgg)."
  [props]
  (when-let [icao (:icao props)]
    (rf/dispatch [:aircraft/hover icao])))

(defn- hover-leave!
  []
  (rf/dispatch [:aircraft/clear-hover]))

(defn now-ms
  "The frame's arrival instant, read at the imperative edge — the ONE
  place the frontend hot path touches a clock. The domain takes time as
  an argument; tests redef this for deterministic staleness."
  []
  (.now js/Date))

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
  "Convert the picture and its trail `history` and hand BOTH across the
  seam — one setData per source. Clojure data in; the seam does the clj->js
  at the very edge.

  The trail source is driven by the SAME presence decision as the aircraft
  source: `live-icaos` is exactly the set of icaos that made it into the
  aircraft FeatureCollection (positioned and not aged out), so a trail is
  drawn only for an aircraft the layer is currently drawing. When time (a
  tick) or the server ages an aircraft out, it leaves both collections in
  the same push — the ribbon never outlives its plane.

  The trail is pushed first so the aircraft push is the LAST setData of the
  pair, keeping the aircraft FeatureCollection the tail of the seam log."
  [m history picture]
  (let [fc         (picture->feature-collection picture (now-ms))
        live-icaos (into #{} (map #(get-in % [:properties :icao])) (:features fc))
        trail-fc   (trails/history->trail-feature-collection history live-icaos)]
    (maplibre/set-source-data! m trail-source-id trail-fc)
    (maplibre/set-source-data! m source-id fc)))

(defn attach!
  "Wire the aircraft layer onto map `m` (an adsb.map.maplibre/Map),
  printed in `theme`'s edition (defaulting to the current one — the map
  view passes the theme it printed the basemap with, so the two can
  never disagree). Call from the map component's mount path; the view
  re-creates map and layer together when the system theme flips. Returns
  a handle for `detach!`. Everything waits on the map's load event;
  frames arriving before it are covered by app-db's latest-wins
  buffering (ns docstring)."
  ([m] (attach! m @theme/!theme))
  ([m theme]
  (let [!state (atom {:disposed? false
                      :track     nil
                      :tick      nil
                      :picture   nil
                      :history   {}})]
    (maplibre/on-load!
      m
      (fn []
        (when-not (:disposed? @!state)
          ;; Icons first — the layer names them in `icon-image`, and
          ;; MapLibre warns on a layer that references an absent image.
          (register-icons! m)
          ;; The trail source and layer go in BEFORE the aircraft layer, so
          ;; the ribbon renders UNDER the plane it trails (add order is z
          ;; order). Its source carries lineMetrics for the gradient.
          (maplibre/add-source! m trail-source-id trail-source-spec)
          (maplibre/add-layer! m (trail-layer-spec theme))
          (maplibre/add-source! m source-id source-spec)
          (maplibre/add-layer! m (layer-spec theme))
          ;; The click contract and its hover affordance, through the
          ;; seam. Select opens the panel; double-click toggles follow
          ;; (adsb-jg4); hover lights the callsign label
          ;; (adsb.map.selection / adsb-xgg).
          (maplibre/on-layer-click! m layer-id select!)
          (maplibre/on-layer-dblclick! m layer-id dblclick-follow!)
          (maplibre/on-layer-hover! m layer-id hover-enter! hover-leave!)
          ;; The hot path: a reaction OUTSIDE any component. Its initial
          ;; run flushes whatever picture already arrived; each re-run is
          ;; one picture change -> one push (a trail + an aircraft setData).
          ;; React never hears of it. Each run also folds the new picture
          ;; into the trail history (adsb.trails/accumulate — append-on-
          ;; change, capped, departed aircraft dropped) and caches BOTH the
          ;; picture and the history in !state, so the time-driven tick can
          ;; re-push them WITHOUT subscribing outside a reactive context
          ;; (the subscription lives here, in the track).
          (swap! !state assoc :track
                 (r/track!
                   (fn []
                     (let [picture @(rf/subscribe [:aircraft/picture])
                           history (trails/accumulate (:history @!state)
                                                      (vals picture))]
                       (swap! !state assoc :picture picture :history history)
                       (push! m history picture)))))
          ;; The client tick: re-push the cached picture and history on a
          ;; coarse interval so silent aircraft keep aging between frames
          ;; and through a stream stall. Nothing about the picture changed —
          ;; only time did — so the fresh now-ms deepens the fade and drops
          ;; anything past the age-out line from BOTH collections at once
          ;; (the trail's live-icaos is re-derived from the freshly aged
          ;; aircraft FeatureCollection). History is not re-accumulated: an
          ;; unchanged position would append nothing anyway.
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
