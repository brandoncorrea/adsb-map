(ns adsb.test-map
  "One full-surface recording fake for adsb.map.maplibre/Map. Every protocol
  method is implemented and records into a single atom, so the map suites share
  one fake instead of each reifying only the methods it happens to touch — and
  so clj-kondo needs no missing-protocol-method exemption for the map tests."
  (:require [adsb.map.maplibre :as maplibre]))

(def viewport
  "The fixed viewport box the fake reports from `bounds` — Tampa-ish, wide
  enough that the cast's positioned aircraft fall inside it."
  {:geo/min-lat 27.0
   :geo/max-lat 29.0
   :geo/min-lon -83.0
   :geo/max-lon -81.0})

(def style
  "The minimal style the stub serves: one background layer, so an edition
  transform (adsb.map.basemap) has something to reprint."
  {:version 8
   :sources {}
   :layers  [{:id    "background"
              :type  "background"
              :paint {:background-color "#dddddd"}}]})

(defn stub-load-style!
  "A 3-arity stand-in for adsb.map.view/load-style! — matching the real
  signature — that hands `on-ok` the shared `style` at once and never fails."
  [_url on-ok _on-fail]
  (on-ok style))

(defn recording-map
  "A full-surface fake maplibre/Map. Returns {:rec !rec :!bounds !bounds :m m},
  where `!rec` records every call and `!bounds` backs `bounds` so a test can
  pan the viewport under the map. `opts`:
    :camera — the value `(camera m)` returns (default nil)
    :bounds — the initial viewport box (default `viewport`)."
  ([] (recording-map {}))
  ([{:keys [camera bounds] :or {bounds viewport}}]
   (let [!rec    (atom {:on-load           nil
                        :sources           {}
                        :layers            []
                        :set-data          []
                        :images            []
                        :on-layer-click    nil
                        :on-layer-dblclick nil
                        :hover-layers      []
                        :added             []
                        :moves             []
                        :removed           []
                        :fly-to            []
                        :ease-to           []
                        :fit-bounds        []
                        :on-move           nil
                        :on-drag-start     nil
                        :dragstart         nil
                        :destroyed         0})
         !bounds (atom bounds)]
     {:rec     !rec
      :!bounds !bounds
      :m
      (reify maplibre/Map
        (destroy! [_] (swap! !rec update :destroyed inc))
        (on-load! [_ f] (swap! !rec assoc :on-load f))
        (add-source! [_ id source] (swap! !rec update :sources assoc id source))
        (add-layer! [_ layer] (swap! !rec update :layers conj layer))
        (set-source-data! [_ id data]
          (swap! !rec update :set-data conj {:source id :data data}))
        (add-image! [_ id image opts]
          (swap! !rec update :images conj {:id id :image image :opts opts}))
        (on-layer-click! [_ _layer-id f] (swap! !rec assoc :on-layer-click f))
        (on-layer-dblclick! [_ _layer-id f]
          (swap! !rec assoc :on-layer-dblclick f))
        (on-layer-hover! [_ layer-id _on-enter _on-leave]
          (swap! !rec update :hover-layers conj layer-id))
        (add-marker! [_ element lng-lat]
          (let [marker {:element element :id (count (:added @!rec))}]
            (swap! !rec update :added conj {:marker marker :lng-lat lng-lat})
            marker))
        (move-marker! [_ marker lng-lat]
          (swap! !rec update :moves conj {:marker marker :lng-lat lng-lat}))
        (remove-marker! [_ marker] (swap! !rec update :removed conj marker))
        (bounds [_] @!bounds)
        (fly-to! [_ lng-lat] (swap! !rec update :fly-to conj lng-lat))
        (ease-to! [_ lng-lat] (swap! !rec update :ease-to conj lng-lat))
        (on-move! [_ f] (swap! !rec assoc :on-move f))
        (on-drag-start! [_ f] (swap! !rec assoc :on-drag-start f :dragstart true))
        (camera [_] camera)
        (fit-bounds! [_ bounds padding]
          (swap! !rec update :fit-bounds conj {:bounds bounds :padding padding})))})))

(defn fire-load!
  "Invoke the captured on-load callback — the map's style has 'loaded'."
  [fake]
  ((:on-load @(:rec fake))))

(defn fire-move!
  "Invoke the captured moveend/on-move callback, if one was registered."
  [fake]
  (when-let [f (:on-move @(:rec fake))] (f)))

(defn fire-drag!
  "Invoke the captured on-drag-start callback, if one was registered."
  [fake]
  (when-let [f (:on-drag-start @(:rec fake))] (f)))

(defn set-data
  "The recorded set-source-data pushes — all of them, or only those for
  `source-id`."
  ([fake] (:set-data @(:rec fake)))
  ([fake source-id] (filter #(= source-id (:source %)) (set-data fake))))

(defn last-features
  "The features of the most recent set-source-data push — across all sources,
  or for `source-id`."
  ([fake] (get-in (last (set-data fake)) [:data :features]))
  ([fake source-id] (get-in (last (set-data fake source-id)) [:data :features])))
