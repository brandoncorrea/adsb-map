(ns adsb.map.aircraft-layer-test
  (:require [adsb.aircraft :as aircraft]
            [adsb.corejs :as cjs]
            [adsb.fixtures :as fixtures]
            [adsb.map.aircraft-layer :as layer]
            [adsb.map.maplibre :as maplibre]
            [adsb.map.style :as style]
            [adsb.map.view :as view]
            [adsb.stream]
            [adsb.test-dom :as test-dom]
            [adsb.views :as views]
            [adsb.wire :as wire]
            [clojure.test :refer-macros [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(defn- frame [aircraft]
  (let [picture (into {} (map (juxt :aircraft/icao identity)) aircraft)]
    (js/JSON.stringify
      (clj->js (wire/picture->wire picture 1720713600000)))))

(defn- recording-map []
  (let [!rec (atom {:on-load           nil
                    :sources           {}
                    :layers            []
                    :set-data          []
                    :images            []
                    :on-layer-click    nil
                    :on-layer-dblclick nil
                    :hover-layers      []})]
    {:rec !rec
     :m   (reify maplibre/Map
            (destroy! [_] (swap! !rec assoc :destroyed? true))
            (on-load! [_ f] (swap! !rec assoc :on-load f))
            (add-source! [_ id source] (swap! !rec update :sources assoc id source))
            (add-layer! [_ l] (swap! !rec update :layers conj l))
            (set-source-data! [_ id data]
              (swap! !rec update :set-data conj {:source id :data data}))
            (add-image! [_ id image opts]
              (swap! !rec update :images conj {:id id :image image :opts opts}))
            (on-layer-click! [_ _layer-id f]
              (swap! !rec assoc :on-layer-click f))
            (on-layer-dblclick! [_ _layer-id f]
              (swap! !rec assoc :on-layer-dblclick f))
            (on-layer-hover! [_ layer-id _on-enter _on-leave]
              (swap! !rec update :hover-layers conj layer-id))
            (bounds [_] {:geo/min-lat 27.0 :geo/max-lat 29.0
                         :geo/min-lon -83.0 :geo/max-lon -81.0})
            (fly-to! [_ _lng-lat] nil)
            (ease-to! [_ _lng-lat] nil)
            (on-move! [_ _f] nil)
            (on-drag-start! [_ _f] nil)
            (fit-bounds! [_ _bounds _padding] nil))}))

(defn- fire-load! [{:keys [rec]}] ((:on-load @rec)))
(def ^:private fixture-style {:version 8 :sources {} :layers []})
(defn- stub-load-style! [_url cb] (cb fixture-style))
(defn- set-data-calls [{:keys [rec]}] (:set-data @rec))
(defn- source-set-data [fake sid]
  (filter #(= sid (:source %)) (set-data-calls fake)))

(defn- aircraft-set-data [fake] (source-set-data fake layer/source-id))
(defn- trail-set-data [fake] (source-set-data fake layer/trail-source-id))

(defn- last-features [coll]
  (get-in (last coll) [:data :features]))

(defn- last-aircraft-features [fake]
  (last-features (aircraft-set-data fake)))

(defn- last-trail-features [fake]
  (last-features (trail-set-data fake)))

(defn- feature-by-icao [features icao]
  (some #(when (= icao (-> % :properties :icao)) %) features))

(deftest set-data-carries-exactly-the-positioned-aircraft
  (rf-test/run-test-sync
    (let [{:keys [m rec] :as fake} (recording-map)
          handle (layer/attach! m)]
      (fire-load! fake)

      (testing "load adds both sources and both layers, trail beneath aircraft"
        (is (= {layer/source-id       layer/source-spec
                layer/trail-source-id layer/trail-source-spec}
               (:sources @rec)))
        (is (= [(layer/trail-layer-spec :day) (layer/layer-spec :day)] (:layers @rec))))

      (rf/dispatch [:stream/received (frame [fixtures/ups-2717
                                             fixtures/never-positioned])])
      (r/flush)

      (let [{:keys [source data]} (last (aircraft-set-data fake))
            features (:features data)]
        (testing "one FeatureCollection into the aircraft source"
          (is (= layer/source-id source))
          (is (= "FeatureCollection" (:type data))))

        (testing "exactly the positioned aircraft — never-positioned yields no feature"
          (is (= 1 (count features)))
          (is (= (:aircraft/icao fixtures/ups-2717)
                 (get-in (first features) [:properties :icao]))))

        (testing "coordinates are [lon lat] — or the aircraft swims off Somalia"
          (is (= [-83.975953 27.961166]
                 (get-in (first features) [:geometry :coordinates])))))

      (layer/detach! handle))))

(deftest feature-properties-carry-the-styling-contract
  (rf-test/run-test-sync
    (let [{:keys [m] :as fake} (recording-map)
          handle     (layer/attach! m)
          long-quiet (assoc fixtures/ups-2717 :aircraft/seen-at-ms 1000)
          just-heard (assoc fixtures/squawking-7700 :aircraft/seen-at-ms 95000)]
      (fire-load! fake)
      (with-redefs [cjs/now-ms (constantly 100000)]
        (rf/dispatch [:stream/received (frame [long-quiet
                                               just-heard
                                               fixtures/on-the-ground])])
        (r/flush))

      (let [features (last-aircraft-features fake)
            cruising (feature-by-icao features (:aircraft/icao fixtures/ups-2717))
            alerting (feature-by-icao features (:aircraft/icao fixtures/squawking-7700))
            taxiing  (feature-by-icao features (:aircraft/icao fixtures/on-the-ground))]
        (testing "track and altitude ride along for the altitude/heading styling"
          (is (= 97.14 (get-in cruising [:properties :track])))
          (is (= 34775 (get-in cruising [:properties :altitude]))))

        (testing "emergency is an honest boolean on every feature"
          (is (not (get-in cruising [:properties :emergency])))
          (is (get-in alerting [:properties :emergency])))

        (testing "staleness is judged against the redef'd arrival clock"
          (is (get-in cruising [:properties :stale]))
          (is (not (get-in alerting [:properties :stale]))))

        (testing "an aircraft on the tarmac reads \"ground\", never 0"
          (is (= "ground" (get-in taxiing [:properties :altitude])))))

      (layer/detach! handle))))

(deftest load-registers-every-silhouette-sdf
  (rf-test/run-test-sync
    (let [{:keys [m rec] :as fake} (recording-map)
          handle (layer/attach! m)]
      (fire-load! fake)
      (let [images (:images @rec)]
        (testing "every icon the symbology can choose is registered via the
                  seam's add-image! — a style naming an image nobody drew
                  is a MapLibre warning and a blank chart (adsb-rnp)"
          (is (= #{style/plane-icon-id style/heavy-icon-id
                   style/light-icon-id style/rotorcraft-icon-id
                   style/vehicle-icon-id style/dot-icon-id}
                 (into #{} (map :id) images))))
        (testing "every silhouette the CATEGORY MAP names is among them —
                  over the map itself, so a category added there without a
                  drawing fails here rather than on the live chart"
          (let [registered (into #{} (map :id) images)]
            (doseq [icon-id (vals style/category->icon-id)]
              (is (contains? registered icon-id)
                  (str icon-id " is styled but never drawn")))))
        (testing "registered SDF, so the altitude/emergency colour can tint them"
          (is (seq images))
          (is (every? #(get-in % [:opts :sdf]) images))))
      (layer/detach! handle))))

(defn- mirrored [half-outline]
  (concat half-outline
          (->> (rest (butlast half-outline))
               reverse
               (map (fn [[x y]] [(- 1.0 x) y])))))

(defn- area-centroid [points]
  (let [closed (concat points [(first points)])
        edges  (map vector closed (rest closed))
        cross  (fn [[[x0 y0] [x1 y1]]] (- (* x0 y1) (* x1 y0)))
        area   (* 0.5 (reduce + (map cross edges)))
        weight (fn [i [[x0 y0] [x1 y1] :as edge]]
                 (* (+ (nth [x0 y0] i) (nth [x1 y1] i)) (cross edge)))]
    [(/ (reduce + (map #(weight 0 %) edges)) (* 6 area))
     (/ (reduce + (map #(weight 1 %) edges)) (* 6 area))]))

(def ^:private centroid-tolerance 0.005)

(deftest every-silhouette-is-centred-on-the-anchor
  (doseq [[icon-id half-outline] layer/half-outlines]
    (let [[cx cy] (area-centroid (mirrored half-outline))]
      (testing (str icon-id ": the area centroid sits on the icon anchor")
        (is (< (abs (- 0.5 cx)) centroid-tolerance))
        (is (< (abs (- 0.5 cy)) centroid-tolerance)))))

  (testing "each outline is a right half: it starts and ends ON the nose
            axis, which is what makes the drawn symbol symmetric BY
            CONSTRUCTION rather than by care"
    (doseq [[_ half-outline] layer/half-outlines]
      (is (= 0.5 (ffirst half-outline)))
      (is (= 0.5 (first (last half-outline))))
      (is (every? (fn [[x y]] (and (<= 0.0 x 1.0) (<= 0.0 y 1.0))) half-outline)))))

(deftest clicking-a-feature-dispatches-select-with-its-icao
  (rf-test/run-test-sync
    (let [{:keys [m rec] :as fake} (recording-map)
          handle     (layer/attach! m)
          dispatched (atom [])]
      (fire-load! fake)
      (testing "the layer wired click, dblclick, and hover through the seam"
        (is (fn? (:on-layer-click @rec)))
        (is (fn? (:on-layer-dblclick @rec)))
        (is (= [layer/layer-id] (:hover-layers @rec))))
      (with-redefs [rf/dispatch (fn [ev] (swap! dispatched conj ev))]
        ((:on-layer-click @rec) {:icao         "abc0e4"
                                 :callsign     "UPS2717"
                                 :click/detail 1}))
      (is (= [[:aircraft/select "abc0e4"]] @dispatched))
      (layer/detach! handle))))

(deftest multi-click-and-dblclick-dispatch-follow
  (rf-test/run-test-sync
    (let [{:keys [m rec] :as fake} (recording-map)
          handle     (layer/attach! m)
          dispatched (atom [])]
      (fire-load! fake)
      (with-redefs [rf/dispatch (fn [ev] (swap! dispatched conj ev))]
        ((:on-layer-click @rec) {:icao "abc0e4" :click/detail 2})
        ((:on-layer-dblclick @rec) {:icao "abc0e4" :callsign "UPS2717"}))
      (is (= [[:aircraft/dblclick-follow "abc0e4"]] @dispatched))
      (layer/detach! handle))))

(deftest frames-before-map-load-buffer-latest-wins
  (rf-test/run-test-sync
    (let [{:keys [m rec] :as fake} (recording-map)
          handle (layer/attach! m)]
      (rf/dispatch [:stream/received (frame [fixtures/ups-2717])])
      (r/flush)
      (rf/dispatch [:stream/received (frame [fixtures/squawking-7700])])
      (r/flush)

      (testing "before load, the map is untouched — no source, no pushes"
        (is (empty? (:sources @rec)))
        (is (zero? (count (set-data-calls fake)))))

      (fire-load! fake)

      (testing "load flushes exactly once, with the LATEST picture — no replay"
        (is (= 1 (count (aircraft-set-data fake))))
        (is (= #{(:aircraft/icao fixtures/squawking-7700)}
               (into #{} (map #(-> % :properties :icao))
                     (last-aircraft-features fake)))))

      (layer/detach! handle))))

(deftest detach-stops-updates
  (rf-test/run-test-sync
    (let [{:keys [m] :as fake} (recording-map)
          handle (layer/attach! m)]
      (fire-load! fake)
      (rf/dispatch [:stream/received (frame [fixtures/ups-2717])])
      (r/flush)
      (let [pushes-while-attached (count (aircraft-set-data fake))]
        (is (= 2 pushes-while-attached))

        (layer/detach! handle)
        (rf/dispatch [:stream/received (frame [fixtures/squawking-7700])])
        (r/flush)
        (is (= pushes-while-attached (count (aircraft-set-data fake))))))))

(deftest detach-before-load-never-touches-the-map
  (rf-test/run-test-sync
    (let [{:keys [m rec] :as fake} (recording-map)
          handle (layer/attach! m)]
      (layer/detach! handle)
      (fire-load! fake)
      (rf/dispatch [:stream/received (frame [fixtures/ups-2717])])
      (r/flush)
      (is (empty? (:sources @rec)))
      (is (empty? (:layers @rec)))
      (is (zero? (count (set-data-calls fake)))))))

(deftest client-tick-ages-and-removes-between-frames
  (rf-test/run-test-sync
    (let [{:keys [m] :as fake} (recording-map)
          !tick    (atom nil)
          !cleared (atom [])]
      (with-redefs [layer/set-interval!   (fn [f _ms] (reset! !tick f) :tick-id)
                    layer/clear-interval! (fn [id] (swap! !cleared conj id))]
        (let [handle (layer/attach! m)
              heard  (assoc fixtures/ups-2717 :aircraft/seen-at-ms 0)
              icao   (:aircraft/icao heard)
              age-of (fn [] (get-in (feature-by-icao (last-aircraft-features fake) icao)
                                    [:properties :age-s]))]
          (fire-load! fake)
          (testing "attach! started a client tick through the seam"
            (is (fn? @!tick)))

          (with-redefs [cjs/now-ms (constantly 70000)]
            (rf/dispatch [:stream/received (frame [heard])])
            (r/flush))
          (testing "the frame's feature ages from its receive time (70 s)"
            (is (= 70 (age-of))))

          (testing "with no new frame, the tick re-pushes the SAME picture
                    aged further — the fade progresses on the clock alone
                    (still under the age-out line — adsb-rg1: 2 min)"
            (with-redefs [cjs/now-ms (constantly 100000)]
              (@!tick))
            (is (= 100 (age-of))))

          (testing "past the age-out line the tick drops the aircraft from
                    the setData payload entirely — it disappears"
            (with-redefs [cjs/now-ms (constantly (inc aircraft/age-out-threshold-ms))]
              (@!tick))
            (is (empty? (last-aircraft-features fake))))

          (layer/detach! handle)
          (testing "detach! cancels the tick through the seam"
            (is (= [:tick-id] @!cleared))))))))

(deftest client-tick-flips-the-stale-flag-on-the-clock
  (rf-test/run-test-sync
    (let [{:keys [m] :as fake} (recording-map)
          !tick (atom nil)]
      (with-redefs [layer/set-interval!   (fn [f _ms] (reset! !tick f) :tick-id)
                    layer/clear-interval! (constantly nil)]
        (let [handle   (layer/attach! m)
              heard    (assoc fixtures/ups-2717 :aircraft/seen-at-ms 0)
              icao     (:aircraft/icao heard)
              stale-of (fn [] (-> (last-aircraft-features fake)
                                  (feature-by-icao icao)
                                  :properties
                                  :stale))]
          (fire-load! fake)
          (testing "heard 30 s ago — under the 60 s line, so not yet stale"
            (with-redefs [cjs/now-ms (constantly 30000)]
              (rf/dispatch [:stream/received (frame [heard])])
              (r/flush))
            (is (not (stale-of))))
          (testing "with no new frame, a tick past the stale line flips the
                    flag to true — staleness rides the clock alone"
            (with-redefs [cjs/now-ms (constantly 90000)]
              (@!tick))
            (is (stale-of)))
          (layer/detach! handle))))))

(defn- moving-frame [lat lon]
  (frame [(assoc fixtures/ups-2717 :aircraft/position {:geo/lat lat :geo/lon lon})]))

(deftest trail-source-and-layer-are-born-at-load-with-line-metrics
  (rf-test/run-test-sync
    (let [{:keys [m rec] :as fake} (recording-map)
          handle (layer/attach! m)]
      (fire-load! fake)
      (testing "the trail source exists and carries lineMetrics for the gradient"
        (is (= layer/trail-source-spec
               (get (:sources @rec) layer/trail-source-id)))
        (is (:lineMetrics layer/trail-source-spec)))
      (testing "its line layer is added below the aircraft symbol layer"
        (is (= [(layer/trail-layer-spec :day) (layer/layer-spec :day)] (:layers @rec))))
      (layer/detach! handle))))

(deftest a-moving-aircraft-accumulates-a-multi-point-trail
  (rf-test/run-test-sync
    (let [{:keys [m] :as fake} (recording-map)
          handle (layer/attach! m)
          icao   (:aircraft/icao fixtures/ups-2717)]
      (fire-load! fake)
      (doseq [[lat lon] [[27.0 -83.0] [27.1 -83.0] [27.2 -83.0]]]
        (rf/dispatch [:stream/received (moving-frame lat lon)])
        (r/flush))
      (let [trail (feature-by-icao (last-trail-features fake) icao)]
        (testing "three frames at three positions yield a 3-point LineString,
                  oldest first, in [lon lat] order"
          (is (= "LineString" (get-in trail [:geometry :type])))
          (is (= [[-83.0 27.0] [-83.0 27.1] [-83.0 27.2]]
                 (get-in trail [:geometry :coordinates])))))
      (testing "a stationary re-report appends nothing — the trail holds at 3"
        (rf/dispatch [:stream/received (moving-frame 27.2 -83.0)])
        (r/flush)
        (is (= 3 (count (get-in (feature-by-icao (last-trail-features fake) icao)
                                [:geometry :coordinates])))))
      (layer/detach! handle))))

(deftest an-aged-out-aircraft-leaves-no-trail
  (rf-test/run-test-sync
    (let [{:keys [m] :as fake} (recording-map)
          !tick (atom nil)]
      (with-redefs [layer/set-interval!   (fn [f _ms] (reset! !tick f) :tick-id)
                    layer/clear-interval! (constantly nil)]
        (let [handle (layer/attach! m)
              icao   (:aircraft/icao fixtures/ups-2717)
              heard  (fn [lat] (assoc fixtures/ups-2717
                                 :aircraft/seen-at-ms 0
                                 :aircraft/position {:geo/lat lat :geo/lon -83.0}))]
          (fire-load! fake)
          (with-redefs [cjs/now-ms (constantly 70000)]
            (rf/dispatch [:stream/received (frame [(heard 27.0)])])
            (r/flush)
            (rf/dispatch [:stream/received (frame [(heard 27.1)])])
            (r/flush))
          (testing "while present, the moving aircraft has a two-point trail"
            (is (= 2 (count (get-in (feature-by-icao (last-trail-features fake) icao)
                                    [:geometry :coordinates])))))
          (testing "a tick past the age-out line drops the aircraft AND its
                    trail in the same push — the ribbon never outlives the plane"
            (with-redefs [cjs/now-ms (constantly (inc aircraft/age-out-threshold-ms))]
              (@!tick))
            (is (empty? (last-aircraft-features fake)))
            (is (empty? (last-trail-features fake))))
          (layer/detach! handle))))))

(deftest detach-stops-trail-updates
  (rf-test/run-test-sync
    (let [{:keys [m] :as fake} (recording-map)
          handle (layer/attach! m)]
      (fire-load! fake)
      (rf/dispatch [:stream/received (moving-frame 27.0 -83.0)])
      (r/flush)
      (let [trail-pushes (count (trail-set-data fake))]
        (is (pos? trail-pushes))
        (layer/detach! handle)
        (rf/dispatch [:stream/received (moving-frame 27.1 -83.0)])
        (r/flush)
        (is (= trail-pushes (count (trail-set-data fake))))))))

(deftest zero-reagent-re-renders-on-the-hot-path
  (rf-test/run-test-sync
    (let [node           (cjs/create-element "div")
          _              (cjs/append-child (.-body js/document) node)
          {:keys [m] :as fake} (recording-map)
          !mounts        (atom 0)
          !renders       (atom 0)
          real-container view/map-container
          n              25]
      (with-redefs [maplibre/create!   (fn [_container _opts]
                                         (swap! !mounts inc)
                                         m)
                    view/load-style!   stub-load-style!
                    view/map-container (fn [!c]
                                         (swap! !renders inc)
                                         (real-container !c))]
        (let [root             (test-dom/mount! [views/app-root] node)
              _                (fire-load! fake)
              renders-at-mount @!renders
              pushes-at-load   (count (aircraft-set-data fake))]
          (is (= 1 @!mounts))
          (is (= 1 renders-at-mount))
          (is (= 1 pushes-at-load))

          (dotimes [i n]
            (rf/dispatch
              [:stream/received
               (frame [(assoc fixtures/ups-2717
                         :aircraft/position {:geo/lat (+ 27.0 (* 0.01 i))
                                             :geo/lon -83.0})])])
            (r/flush))

          (testing "N ticks: N setData calls, ZERO additional Reagent work"
            (is (= (+ pushes-at-load n) (count (aircraft-set-data fake))))
            (is (= renders-at-mount @!renders))
            (is (= 1 @!mounts)))

          (testing "unmount detaches the layer: updates stop"
            (test-dom/unmount! root)
            (rf/dispatch [:stream/received (frame [fixtures/squawking-7700])])
            (r/flush)
            (is (= (+ pushes-at-load n) (count (aircraft-set-data fake)))))))
      (cjs/remove! node))))
