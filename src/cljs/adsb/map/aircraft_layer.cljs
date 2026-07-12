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

  ## The projection loop — gliding between frames (adsb-6wd.2)

  Real positions arrive at ~1 Hz; drawn at 1 Hz they teleport. So a
  third push path runs between frames: a requestAnimationFrame loop
  dead-reckons every projectable aircraft forward from its LAST REAL
  observation (adsb.geo/project-aircraft — pure, along track at ground
  speed, stale aircraft and aircraft missing gs or track held in place)
  and pushes the projected FeatureCollection into the AIRCRAFT source
  only. The trail source is deliberately untouched: trails record real
  positions, so the ribbon stays a record of truth under a projected
  head — honest and cheap.

  The three push paths compose instead of fighting because all three
  derive from the same `!state` cache (the track is the only subscriber;
  the tick and the rAF loop read the cache, never a subscription) and
  all three funnel through the same seam:

    - a REAL frame (the track) pushes reported positions — that push IS
      the snap back to truth, and it resets the projection base;
    - the 5 s tick re-pushes reported positions with a fresh clock so
      aging survives a stream stall (a momentary sub-frame regression to
      the base, corrected by the next projection push ≤50 ms later);
    - the rAF loop pushes projected positions between them.

  The loop rides rAF but throttles its pushes to ~20 Hz
  (`projection-push-interval-ms`): the spherical trig is cheap, but each
  push pays clj->js plus MapLibre's GeoJSON re-index, and THAT dominates
  — while at map scales an airliner crawls a few pixels per second, so
  20 Hz reads exactly as smooth as 60. When nothing may honestly move
  (empty sky, all stale or vector-less) the loop pushes nothing at all.
  It starts on attach! (at map load), stops on detach!, and PAUSES while
  the document is hidden — a background tab must not burn CPU projecting
  planes nobody sees. rAF, cancellation, and visibility all go through
  seams (`request-animation-frame!`, `document-hidden?`, ...) so tests
  drive the loop frame by frame with a fake clock. React never hears of
  any of it — the zero-re-render proof covers this path too.

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

(def ^:const shadow-layer-id "aircraft-shadows")

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

(defn shadow-layer-spec
  "The cast-shadow layer (design-direction §8, adsb-dgb.8): the aircraft's
  soft silhouette thrown SE onto the paper, offset by altitude. Reads the
  SAME source as the aircraft layer — every push feeds both for free —
  and is added between trail and aircraft: the shadow falls ON the
  printed paper (over the trail's ink) and the plane flies over all of
  it. Style is data in `adsb.map.style`; this is just the wiring."
  [theme]
  (style/shadow-layer-spec theme shadow-layer-id source-id))

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

(def ^:const shadow-blur-px
  "Canvas blur radius baked into the shadow silhouettes. This is what
  turns a hard alpha mask into a soft alpha GRADIENT — a pseudo distance
  field — which is the whole trick: registered SDF, that gradient is what
  gives `icon-halo-blur` room to deepen the penumbra with altitude. The
  crisp plane icons have binary alpha, so a halo on THEM has nothing to
  soften into; the shadow needed its own pre-softened image."
  1.5)

(def ^:const shadow-inset-scale
  "The shadow silhouette is drawn slightly smaller about the canvas
  centre so the baked blur never clips at the canvas edge — and a
  shadow a touch smaller than its plane reads as beneath it, not
  beside it."
  0.85)

(defn- soften
  "Wrap `draw!` to print blurred and inset — the shadow variant of a
  silhouette. The transform scales about the canvas centre; the canvas
  filter does the blurring at draw time, so the returned ImageData
  carries the soft alpha ramp itself."
  [draw!]
  (fn [ctx size]
    (let [margin (* size (/ (- 1 shadow-inset-scale) 2))]
      (set! (.-filter ctx) (str "blur(" shadow-blur-px "px)"))
      (.translate ctx margin margin)
      (.scale ctx shadow-inset-scale shadow-inset-scale)
      (draw! ctx size))))

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
  (maplibre/add-image! m style/dot-icon-id (->icon-image draw-dot!) {:sdf true})
  ;; The cast shadow's soft variants (adsb-dgb.8) — registered even when
  ;; the layer toggle is off: two tiny ImageDatas cost nothing, and the
  ;; icon inventory stays independent of the style experiment.
  (maplibre/add-image! m style/shadow-plane-icon-id
                       (->icon-image (soften draw-plane!)) {:sdf true})
  (maplibre/add-image! m style/shadow-dot-icon-id
                       (->icon-image (soften draw-dot!)) {:sdf true}))

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

;; ---------------------------------------------------------------------
;; The projection loop's seams. Same idea as the interval seams: tests
;; capture the frame callback and the visibility handler and drive them
;; directly, with `now-ms` redef'd, instead of waiting on a real screen.

(def ^:const projection-push-interval-ms
  "Minimum interval between projection pushes — ~20 Hz inside the rAF
  loop. The dead-reckoning trig is cheap; each push's clj->js and
  MapLibre GeoJSON re-index is the dominant cost, so we push at a rate
  the eye still reads as continuous (an airliner moves a few pixels per
  second at map scales) rather than at the full ~60 fps rAF offers."
  50)

(defn request-animation-frame!
  "Schedule `f` for the next animation frame, returning a handle for
  `cancel-animation-frame!`. A seam over js/requestAnimationFrame so
  tests drive the projection loop frame by frame with a fake clock."
  [f]
  (js/requestAnimationFrame f))

(defn cancel-animation-frame!
  "Cancel the pending animation frame named by `id`."
  [id]
  (js/cancelAnimationFrame id))

(defn document-hidden?
  "True when the document is not visible (a background tab). A seam so
  tests can hide the tab without a real visibility change."
  []
  (.-hidden js/document))

(defn on-visibility-change!
  "Register `f` on the document's visibilitychange event. A seam so
  tests capture the handler and fire it directly."
  [f]
  (.addEventListener js/document "visibilitychange" f))

(defn off-visibility-change!
  "Remove a handler registered by `on-visibility-change!`."
  [f]
  (.removeEventListener js/document "visibilitychange" f))

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

(defn- push-projected!
  "Dead-reckon the cached picture to `at-ms` (adsb.geo/project-aircraft
  — unprojectable aircraft hold their real position) and push the result
  into the AIRCRAFT source only. The trail source is untouched ON
  PURPOSE: trails record reported positions (adsb.trails appends real
  frames), so the ribbon stays a record of truth under a projected head.
  Skipped entirely when nothing may honestly move — an empty sky, or
  every aircraft stale or missing gs/track — since the real pushes and
  the 5 s tick already cover a motionless picture. Returns true when a
  push happened, so the loop can advance its throttle clock only then."
  [m picture at-ms]
  (let [sky (vals picture)]
    (when (some #(geo/projectable? % at-ms) sky)
      (maplibre/set-source-data!
        m source-id
        (geo/aircraft-picture->feature-collection
          (map #(geo/project-aircraft % at-ms) sky)
          at-ms))
      true)))

(defn- start-projection-loop!
  "Start (or, after a hidden tab returns, resume) the rAF projection
  loop over `!state`'s cached picture. Each frame pushes the projected
  sky at most every `projection-push-interval-ms` and reschedules
  itself; the loop stops on its own when the layer is detached or the
  tab hides (the visibility handler restarts it). The throttle clock
  advances only when a push actually happened, so an idle sky's first
  movable frame pushes immediately. One `frame` closure is allocated
  per start and reused every frame — nothing allocates per frame but
  the FeatureCollection itself."
  [m !state]
  (letfn [(frame [_t]
            (let [{:keys [disposed? hidden? last-projection-ms picture]}
                  @!state
                  at-ms (now-ms)]
              (when-not (or disposed? hidden?)
                (when (>= (- at-ms (or last-projection-ms 0))
                          projection-push-interval-ms)
                  (when (push-projected! m picture at-ms)
                    (swap! !state assoc :last-projection-ms at-ms)))
                (swap! !state assoc :raf (request-animation-frame! frame)))))]
    (swap! !state assoc :raf (request-animation-frame! frame))))

(defn- watch-visibility!
  "Pause the projection loop while the document is hidden — a background
  tab must not burn CPU projecting planes nobody can see — and resume it
  on return. Only the rAF loop pauses: the track and the 5 s tick keep
  the cached picture current while hidden, so the loop resumes from
  fresh bases. The handler is kept in `!state` so detach! can remove it."
  [m !state]
  (let [handler (fn []
                  (if (document-hidden?)
                    (do (swap! !state assoc :hidden? true)
                        (when-let [raf (:raf @!state)]
                          (cancel-animation-frame! raf)
                          (swap! !state assoc :raf nil)))
                    (when (:hidden? @!state)
                      (swap! !state assoc :hidden? false)
                      (start-projection-loop! m !state))))]
    (swap! !state assoc :visibility-handler handler)
    (on-visibility-change! handler)))

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
  (let [!state (atom {:disposed? false :track nil :tick nil
                      :picture nil :history {}
                      :raf nil :hidden? false :visibility-handler nil
                      :last-projection-ms 0})]
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
          ;; The cast-shadow prototype (adsb-dgb.8), behind its style
          ;; toggle: shadows print over the trail ink (a shadow falls on
          ;; whatever the paper carries) and under the aircraft.
          (when style/shadows-enabled?
            (maplibre/add-layer! m (shadow-layer-spec theme)))
          (maplibre/add-layer! m (layer-spec theme))
          ;; The click contract and its hover affordance, through the
          ;; seam. We fire `[:aircraft/select icao]`; adsb-dgb.1 handles it.
          (maplibre/on-layer-click! m layer-id select!)
          (maplibre/on-layer-hover-cursor! m layer-id)
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
                   tick-interval-ms))
          ;; The projection loop (ns docstring): between real frames,
          ;; dead-reckon the cached picture and push the aircraft source
          ;; only. It reads the SAME !state cache the tick does — never a
          ;; subscription — so it can neither fight the track nor wake
          ;; React. Paused from birth if the tab is already hidden; the
          ;; visibility handler starts it when the tab first shows.
          (swap! !state assoc :hidden? (document-hidden?))
          (watch-visibility! m !state)
          (when-not (:hidden? @!state)
            (start-projection-loop! m !state)))))
    !state)))

(defn detach!
  "Stop pushing into the map: dispose the reaction (which also releases
  the cached subscription), cancel the client tick, stop the projection
  loop, and unhook the visibility handler. Call from will-unmount,
  BEFORE the map is destroyed. Safe when the load event never fired:
  the pending load callback sees :disposed? and does nothing, so no
  track, tick, or loop was ever created."
  [!state]
  (let [{:keys [track tick raf visibility-handler]} @!state]
    (swap! !state assoc :disposed? true :track nil :tick nil
           :raf nil :visibility-handler nil)
    (when track
      (r/dispose! track))
    (when tick
      (clear-interval! tick))
    (when raf
      (cancel-animation-frame! raf))
    (when visibility-handler
      (off-visibility-change! visibility-handler))))
