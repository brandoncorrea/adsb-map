(ns adsb.map.maplibre
  "The seam over MapLibre GL JS — a thin, fakeable interop wrapper, and the
  ONLY place the app touches the raw library.

  Why a seam at all? Tests run in real headless Chromium, but headless WebGL
  is slow and flaky, and MapLibre's rendering is MapLibre's contract, not
  ours. So tests never construct a real map: they `with-redefs` `create!` to
  capture the options and hand back a fake `Map`. See docs/testing-setup.md,
  \"The Map Seam\"."
  (:require
    ["maplibre-gl" :as maplibre]))

(defprotocol Map
  "The narrow surface the app needs from a live map instance. It grows only
  as beads land — the imperative aircraft GeoJSON layer (adsb-2yu.4) added
  `on-load!`, `add-source!`, `add-layer!`, and `set-source-data!`. Keep it
  small; every method is a thing the fake in a test must also implement."
  (destroy! [this]
    "Tear the map down and release its GL context. Call on unmount.")
  (on-load! [this f]
    "Run `f` once the map's style has loaded — immediately if it already
    has. Sources and layers may only be added after this fires.")
  (add-source! [this id source]
    "Add a source under string `id`. `source` is a Clojure map spec
    (e.g. {:type \"geojson\" :data <FeatureCollection>}). Style must be
    loaded — call from an `on-load!` callback.")
  (add-layer! [this layer]
    "Add a style layer, a Clojure map spec. Style must be loaded.")
  (set-source-data! [this id data]
    "Wholesale-replace the contents of GeoJSON source `id`. `data` is a
    CLOJURE FeatureCollection; the clj->js happens here, at the very
    edge, so callers and tests stay in Clojure data. This is the aircraft
    hot path — called once per SSE frame, entirely outside React.")
  (add-image! [this id image opts]
    "Register an image under string `id` so a symbol layer can name it in
    `icon-image`. `image` is anything MapLibre's addImage accepts (here an
    ImageData). `opts` is a Clojure map — `{:sdf true}` makes the icon an
    alpha mask MapLibre can tint via `icon-color`, which is what turns the
    silhouette into the altitude ramp. Style must be loaded.")
  (on-layer-click! [this layer-id f]
    "Call `f` with the clicked feature's PROPERTIES — a Clojure map,
    js->clj'd at this edge — when a feature in `layer-id` is clicked.
    Always the raw click (including multi-click detail); the layer
    decides how detail 1 vs 2+ maps onto select vs follow. The only
    inbound seam crossing: MapLibre's event and JS props become Clojure
    data here so the layer's click contract stays in Clojure.")
  (on-layer-dblclick! [this layer-id f]
    "Map-level dblclick that hits `layer-id` (via queryRenderedFeatures
    with a hit box — aircraft icons are tiny). Calls `f` with the
    feature's PROPERTIES. DoubleClickZoom is disabled on create so the
    basemap never steals this gesture (adsb-jg4).")
  (on-layer-hover! [this layer-id on-enter on-leave]
    "While the pointer rests on a feature in `layer-id`: show a pointer
    cursor and call `on-enter` with the feature's PROPERTIES (Clojure map,
    js->clj'd here). On leave, restore the cursor and call `on-leave`.
    The aircraft layer uses this for both the click affordance and the
    hover label channel (adsb-xgg).")
  (add-marker! [this element lng-lat]
    "Pin DOM `element` to the map at `lng-lat` ([lng lat]) so it rides
    the chart through pans and zooms. Anchor is always the element's
    centre (the selection ring must sit ON the plane, not above it —
    adsb-rg1). Returns a marker handle for `move-marker!`/`remove-marker!`.
    The selection ring (adsb.map.selection) is the main client: a single
    low-churn marker, never the aircraft — hundreds of DOM markers at 1 Hz
    would be the crawl the GeoJSON layer exists to avoid.")
  (move-marker! [this marker lng-lat]
    "Move `marker` (an `add-marker!` handle) to `lng-lat` ([lng lat]).")
  (remove-marker! [this marker]
    "Remove `marker` (an `add-marker!` handle) from the map.")
  (bounds [this]
    "The current viewport as the adsb.geo bounds box —
    {:geo/min-lat _ :geo/max-lat _ :geo/min-lon _ :geo/max-lon _} — so
    viewport geometry (the emergency edge arrow, adsb.map.emergency)
    stays pure Clojure math over a shape the domain already speaks.
    Longitudes are MapLibre's UNWRAPPED west/east and can run past ±180
    after a pan across the antimeridian; adsb.geo/edge-annotation
    normalizes for that.")
  (fly-to! [this lng-lat]
    "Ease the camera to `lng-lat` — a {:geo/lat :geo/lon} map, the domain's own
    shape — keeping the current zoom unless the chart is further out than
    `focus-zoom`, in which case it closes in to it. A CAMERA move, never a
    selection: what is selected is app-db's business, and this only decides
    where the chart is looking.")
  (ease-to! [this lng-lat]
    "Short, quiet re-center on `lng-lat` without changing zoom. Used by
    follow mode (adsb.map.follow) on each position step — not a dramatic
    fly-to, just keeping the selected aircraft under the crosshairs.")
  (on-move! [this f]
    "Call `f` (no args) when a camera movement SETTLES — MapLibre's
    moveend, which pan and zoom both conclude with. Deliberately not the
    per-frame move event: clients re-derive viewport-dependent
    annotations, which is settle-time work, not render-loop work. The
    handler lives as long as the map does; the map view tears clients
    down before it destroys the map, so clients guard with their own
    disposed flag rather than unregistering.")
  (on-drag-start! [this f]
    "Call `f` (no args) when the reader starts a pan/drag. Follow mode
    (adsb.map.follow) uses this — not moveend — so our own ease-to
    settles never free the camera.")
  (camera [this]
    "Where the chart is looking, right now: {:center [lon lat] :zoom _
    :bearing _ :pitch _}. Deliberately the very keys `create!`'s options
    speak, so a camera read off a dying map merges straight into the next
    map's opts — which is the whole reason this exists. A theme flip
    destroys the map and prints another (adsb.map.view), and without this
    the reader's place goes into the bin with it.")
  (fit-bounds! [this bounds padding-px]
    "Frame `bounds` — the adsb.geo box, {:geo/min-lat _ :geo/max-lat _
    :geo/min-lon _ :geo/max-lon _} — in the viewport, with `padding-px`
    of breathing room on every side. Centre AND zoom are derived from the
    box and the viewport's own size, which is the point: a 60 km disc and
    a 400 km disc both land framed, where a fixed zoom would show one as a
    speck and crop the other.

    NOT ANIMATED, unlike `fly-to!`. This is the chart's opening framing,
    not a navigation — the reader has not asked to go anywhere, so there
    is nothing to carry them across. A swoop on load would just be the map
    showing off (adsb.map.crop frames the declared boundary this way)."))

(def ^:const focus-zoom
  "The closest the chart will pull in when flying to an aircraft — and the
  FLOOR, not the target: a reader already zoomed further in than this keeps
  their zoom, because flying to an aircraft should move the camera, not undo the
  reader's own decision about how close they wanted to be.

  13 is an inspect distance: roughly a city/airport scale, so a followed
  jet fills the chart rather than sitting as a pin on a multi-county view
  (adsb-jg4). Default chart open is ~7; crop framing often lands near 8–10."
  13)

(def ^:const dblclick-hit-px
  "Half-width of the query box around a double-click point when testing
  for an aircraft under the cursor. Icons are small; without this the
  basemap DoubleClickZoom wins most of the time."
  12)

(deftype MapLibreMap [^js gl-map]
  Map
  (destroy! [_] (.remove gl-map))
  (on-load! [_ f]
    (if (.loaded gl-map) (f) (.on gl-map "load" f)))
  (add-source! [_ id source]
    (.addSource gl-map id (clj->js source)))
  (add-layer! [_ layer]
    (.addLayer gl-map (clj->js layer)))
  (set-source-data! [_ id data]
    ;; The layer owns ordering: the source exists before the first push
    ;; (added in the same on-load! callback that starts the pushes).
    (.setData (.getSource gl-map id) (clj->js data)))
  (add-image! [_ id image opts]
    (.addImage gl-map id image (clj->js opts)))
  (fly-to! [_ {:geo/keys [lat lon]}]
    (.flyTo gl-map #js {:center #js [lon lat]
                        :zoom   (max (.getZoom gl-map) focus-zoom)
                        :speed  1.2}))
  (ease-to! [_ {:geo/keys [lat lon]}]
    ;; Never interrupt an in-flight camera animation (entry fly-to). A
    ;; 1 Hz track update that easeTo's mid-fly cancels the zoom and
    ;; leaves the chart halfway to inspect distance (adsb-jg4).
    (when-not (.isMoving gl-map)
      (.easeTo gl-map #js {:center   #js [lon lat]
                           :duration 350
                           :easing   (fn [t] (- 1 (js/Math.pow (- 1 t) 3)))})))
  (on-layer-click! [_ layer-id f]
    (.on gl-map "click" layer-id
         (fn [e]
           (let [features (.-features e)]
             (when (and features (pos? (.-length features)))
               (let [oe     (.-originalEvent e)
                     detail (if oe (.-detail oe) 1)
                     props  (js->clj (.-properties (aget features 0))
                                     :keywordize-keys true)]
                 ;; Pass click detail so the layer can treat multi-click
                 ;; as follow without waiting on the dblclick event.
                 (f (assoc props :click/detail detail))))))))
  (on-layer-dblclick! [_ layer-id f]
    ;; Map-level + hit box: layer-scoped dblclick often misses small
    ;; symbols. DoubleClickZoom is disabled on create (see create!).
    (.on gl-map "dblclick"
         (fn [e]
           (let [pt       (.-point e)
                 x        (.-x pt)
                 y        (.-y pt)
                 pad      dblclick-hit-px
                 features (.queryRenderedFeatures
                            gl-map
                            #js [#js [(- x pad) (- y pad)]
                                 #js [(+ x pad) (+ y pad)]]
                            #js {:layers #js [layer-id]})]
             (when (and features (pos? (.-length features)))
               (.preventDefault e)
               (f (js->clj (.-properties (aget features 0))
                           :keywordize-keys true)))))))
  (on-layer-hover! [_ layer-id on-enter on-leave]
    (let [canvas-style (.-style (.getCanvas gl-map))]
      (.on gl-map "mouseenter" layer-id
           (fn [e]
             (set! (.-cursor canvas-style) "pointer")
             (when on-enter
               (let [features (.-features e)]
                 (when (and features (pos? (.-length features)))
                   (on-enter (js->clj (.-properties (aget features 0))
                                      :keywordize-keys true)))))))
      (.on gl-map "mouseleave" layer-id
           (fn [_e]
             (set! (.-cursor canvas-style) "")
             (when on-leave (on-leave))))))
  (add-marker! [_ element lng-lat]
    ;; Explicit center anchor: MapLibre's default is center, but a custom
    ;; element whose box is not yet laid out can still pin wrong if the
    ;; options omit it. The ring's layout box is the SVG only; the label
    ;; is absolute and must not pull the anchor (adsb-rg1).
    (-> (maplibre/Marker. #js {:element element
                               :anchor  "center"
                               :offset  #js [0 0]})
        (.setLngLat (clj->js lng-lat))
        (.addTo gl-map)))
  (move-marker! [_ marker lng-lat]
    (.setLngLat ^js marker (clj->js lng-lat)))
  (remove-marker! [_ marker]
    (.remove ^js marker))
  (bounds [_]
    (let [^js b (.getBounds gl-map)]
      {:geo/min-lat (.getSouth b)
       :geo/max-lat (.getNorth b)
       :geo/min-lon (.getWest b)
       :geo/max-lon (.getEast b)}))
  (on-move! [_ f]
    (.on gl-map "moveend" (fn [_e] (f))))
  (on-drag-start! [_ f]
    (.on gl-map "dragstart" (fn [_e] (f))))
  (camera [_]
    (let [^js c (.getCenter gl-map)]
      ;; [lon lat], the order MapLibre's own options want it back in.
      {:center  [(.-lng c) (.-lat c)]
       :zoom    (.getZoom gl-map)
       :bearing (.getBearing gl-map)
       :pitch   (.getPitch gl-map)}))
  (fit-bounds! [_ {:geo/keys [min-lat max-lat min-lon max-lon]} padding-px]
    ;; MapLibre's LngLatBoundsLike as [[west south] [east north]] — [lon lat]
    ;; corners, the transposition this codebase keeps making a point of.
    (.fitBounds gl-map
                #js [#js [min-lon min-lat] #js [max-lon max-lat]]
                #js {:padding padding-px
                     :animate false})))

(defn create!
  "Construct a real MapLibre map inside `container` (a DOM node) with `opts`
  — a Clojure map of MapLibre map options (`:style`, `:center` as [lon lat],
  `:zoom`, `:attributionControl`). Returns a value satisfying `Map`.

  DoubleClickZoom is disabled: double-click on a plane means Free/Follow
  (adsb-jg4), and MapLibre's handler races any preventDefault we try from
  a late-registered listener. Empty-chart zoom is scroll-wheel / pinch.

  This is the seam. It is deliberately a thin translation with no logic worth
  testing — a unit test redefines it to record `opts` and never touches the
  GPU. Everything interesting to assert (the default center, the token-free
  style) lives in `adsb.map.view/default-map-opts`, which the fake still runs."
  [container opts]
  (let [gl (maplibre/Map. (clj->js (assoc opts :container container)))]
    (.disable (.-doubleClickZoom gl))
    (->MapLibreMap gl)))
