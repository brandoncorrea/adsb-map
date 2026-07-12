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
    js->clj'd at this edge — when a feature in `layer-id` is clicked. The
    only inbound seam crossing: MapLibre's event and JS props become
    Clojure data here so the layer's click contract stays in Clojure.")
  (on-layer-hover-cursor! [this layer-id]
    "Show a pointer cursor while the mouse is over a feature in
    `layer-id`, and restore the default on the way out — the affordance
    that says these planes are clickable. Self-contained in the seam;
    tests need only know it was wired."))

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
  (on-layer-click! [_ layer-id f]
    (.on gl-map "click" layer-id
         (fn [e]
           (let [features (.-features e)]
             (when (and features (pos? (.-length features)))
               ;; Inbound edge: the JS properties object becomes Clojure
               ;; data before it ever reaches the app.
               (f (js->clj (.-properties (aget features 0))
                           :keywordize-keys true)))))))
  (on-layer-hover-cursor! [_ layer-id]
    (let [canvas-style (.-style (.getCanvas gl-map))]
      (.on gl-map "mouseenter" layer-id
           (fn [_e] (set! (.-cursor canvas-style) "pointer")))
      (.on gl-map "mouseleave" layer-id
           (fn [_e] (set! (.-cursor canvas-style) ""))))))

(defn create!
  "Construct a real MapLibre map inside `container` (a DOM node) with `opts`
  — a Clojure map of MapLibre map options (`:style`, `:center` as [lon lat],
  `:zoom`, `:attributionControl`). Returns a value satisfying `Map`.

  This is the seam. It is deliberately a thin translation with no logic worth
  testing — a unit test redefines it to record `opts` and never touches the
  GPU. Everything interesting to assert (the default center, the token-free
  style) lives in `adsb.map.view/default-map-opts`, which the fake still runs."
  [container opts]
  (->MapLibreMap
    (maplibre/Map. (clj->js (assoc opts :container container)))))
