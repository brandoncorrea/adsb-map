(ns adsb.map.crop
  "The published-coverage boundary: the edge of the disc this deployment
  publishes, drawn on the chart (adsb.ingest.crop, adsb-au5).

  WHY DRAW IT AT ALL. The crop only works because it is DECLARED. Its
  whole purpose is that the boundary of the published set be one we chose
  and announced, rather than the antenna's own horizon — whose centroid is
  the antenna. A boundary that had to stay secret would not be a crop; it
  would be the leak. So there is nothing to lose by drawing it, and
  something to gain: the reader can see that this map is a deliberate
  window rather than everything in the sky, and the operator can see at a
  glance whether the gate is actually on.

  It is a POLYGON ring stroked by a line layer, never MapLibre's `circle`
  layer type: a circle layer's radius is in screen pixels and would swell
  and shrink against the ground under zoom, which is precisely wrong for a
  boundary that means a fixed distance on the earth. adsb.geo/circle walks
  the true great-circle rim, one `destination` per vertex.

  Static, and that is the whole shape of this namespace. The crop arrives
  once on the connect-time `config` event and cannot change while the
  server lives, so there is no tick, no hot path, and no per-frame push —
  one reaction that fires when the boundary becomes known (or changes,
  which only a reconnect onto a different process can do) and pushes the
  ring exactly once."
  (:require
    [adsb.geo :as geo]
    [adsb.map.maplibre :as maplibre]
    [adsb.map.style :as style]
    [adsb.map.theme :as theme]
    [re-frame.core :as rf]
    [reagent.core :as r]))

(def ^:const source-id "coverage-boundary")
(def ^:const layer-id "coverage-boundary")

(def empty-feature-collection
  {:type "FeatureCollection" :features []})

(def source-spec
  {:type "geojson" :data empty-feature-collection})

(defn layer-spec
  "The boundary's line-layer spec in `theme`'s edition."
  [theme]
  (style/crop-layer-spec theme layer-id source-id))

(def ^:const frame-padding-px
  "Breathing room around the boundary when the chart opens on it, so the
  ring is not welded to the edge of the viewport." 40)

(defn crop-bounds
  "The bounding box of the declared crop — the box `fit-bounds!` frames
  the chart on. Derived from the RING, not from the centre and a radius in
  degrees: a degree of longitude is not a degree of latitude anywhere but
  the equator, and the ring already knows that (adsb.geo/circle). nil for
  no crop — there is no box around nothing."
  [{:crop/keys [center radius-m]}]
  (when (and center radius-m)
    (geo/bounds (geo/circle center radius-m))))

(defn crop->feature-collection
  "The declared crop as the GeoJSON the source carries: one Polygon whose
  single ring is the geodesic circle of the crop's radius around its
  centre. A nil crop — the gate disabled, or the config event not yet
  landed — yields an EMPTY collection, not a default ring: a boundary we
  cannot state is one we must not draw."
  [{:crop/keys [center radius-m]}]
  (if (and center radius-m)
    {:type     "FeatureCollection"
     :features [{:type     "Feature"
                 :properties {}
                 :geometry {:type "Polygon"
                            ;; GeoJSON winds [lon lat], and a Polygon's
                            ;; coordinates are a LIST of rings — the outer
                            ;; one first. There is only ever the outer.
                            :coordinates
                            [(mapv (fn [{:geo/keys [lat lon]}] [lon lat])
                                   (geo/circle center radius-m))]}}]}
    empty-feature-collection))

(defn- frame-once! ;; -> boolean: did it frame?
  "Open the chart on the declared boundary — ONCE per map, the first time
  a crop is known.

  Why the map does not simply BOOT centred here: the crop arrives on the
  SSE `config` event, which cannot land until the stream connects, and the
  map is on screen before that. So the view boots on its fixed regional
  fallback (adsb.map.view/default-center) and this pulls the camera onto
  the declared disc the instant the server says where it is — which, since
  config is the first frame of the connection, is about as close to 'boots
  there' as an async fact gets.

  ONCE, and that is the load-bearing word. `:framed?` latches on the first
  fit, so nothing here can ever move a camera the reader has since moved
  themselves — a boundary that re-framed itself on a reconnect would yank
  the chart out from under someone mid-pan, which is the kind of thing a
  map is never forgiven for. A nil crop frames nothing and does not latch:
  the deployment simply has no declared boundary to open on, and the
  regional fallback stands."
  [m !state crop]
  (boolean
    (when-let [box (and (not (:framed? @!state)) (crop-bounds crop))]
      (swap! !state assoc :framed? true)
      (maplibre/fit-bounds! m box frame-padding-px)
      true)))

(defn attach!
  "Draw the coverage boundary on map `m` (an adsb.map.maplibre/Map),
  printed in `theme`'s edition (defaulting to the current one). Call from
  the map component's mount path, BEFORE the trail and aircraft layers, so
  the boundary sits under everything in the sky (add order is z order).
  Returns a handle for `detach!`.

  The reaction runs once on attach — flushing whatever the config event
  already delivered — and again only if the declared crop itself changes,
  which nothing but a reconnect onto a differently-configured process can
  cause."
  ([m] (attach! m @theme/!theme))
  ([m theme]
   (let [!state (atom {:disposed? false :track nil :framed? false})]
     (maplibre/on-load!
       m
       (fn []
         (when-not (:disposed? @!state)
           (maplibre/add-source! m source-id source-spec)
           (maplibre/add-layer! m (layer-spec theme))
           (swap! !state assoc :track
                  (r/track!
                    (fn []
                      (let [crop @(rf/subscribe [:crop/declared])]
                        (maplibre/set-source-data!
                          m source-id
                          (crop->feature-collection crop))
                        (frame-once! m !state crop))))))))
     !state)))

(defn detach!
  "Stop drawing: dispose the reaction, which also releases the cached
  subscription. Call from will-unmount, BEFORE the map is destroyed. Safe
  when the load event never fired — the flag stops a late callback from
  adding a source to a map that is going away."
  [!state]
  (swap! !state assoc :disposed? true)
  (some-> (:track @!state) r/dispose!)
  (swap! !state assoc :track nil))
