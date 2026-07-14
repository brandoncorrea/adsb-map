(ns adsb.map.aircraft-layer-test
  "The imperative aircraft layer, proven at the seam. A recording fake
  stands in for MapLibre (docs/testing-setup.md, \"The Map Seam\"), the
  wire round-trip plays the part of the server, and the assertions cover
  the four promises of adsb-2yu.4: setData carries exactly the positioned
  aircraft (with the styling properties adsb-2yu.5 needs), early frames
  buffer latest-wins until the map loads, disposal stops the pushes, and
  — the centerpiece — N picture updates cost ZERO Reagent re-renders."
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.fixtures :as fixtures]
    [adsb.map.aircraft-layer :as layer]
    [adsb.map.maplibre :as maplibre]
    [adsb.map.style :as style]
    [adsb.map.view :as view]
    [adsb.stream]
    [adsb.test-dom :as test-dom]
    [adsb.views :as views]
    [adsb.wire :as wire]
    [cljs.test :refer-macros [deftest is testing]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

;; ---------------------------------------------------------------------
;; Server impersonation: one SSE frame's data string, exactly as the
;; backend would build it — the shared adsb.wire codec, then JSON.

(defn- frame
  [aircraft]
  (let [picture (into {} (map (juxt :aircraft/icao identity)) aircraft)]
    (js/JSON.stringify
      (clj->js (wire/picture->wire picture 1720713600000)))))

;; ---------------------------------------------------------------------
;; The recording fake map. It captures everything crossing the seam —
;; still Clojure data, because the real seam does clj->js at the edge —
;; and lets the test decide when the "load" event fires.

(defn- recording-map []
  (let [!rec (atom {:on-load nil :sources {} :layers [] :set-data []
                    :images [] :on-layer-click nil :on-layer-dblclick nil
                    :hover-layers []})]
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
            ;; The shell-mounting proofs attach the emergency annotations
            ;; too (adsb.map.emergency); their fixtures squawk nothing, so
            ;; only the moveend registration ever crosses this seam.
            (bounds [_] {:geo/min-lat 27.0 :geo/max-lat 29.0
                         :geo/min-lon -83.0 :geo/max-lon -81.0})
            (fly-to! [_ _lng-lat] nil)
            (ease-to! [_ _lng-lat] nil)
            (on-move! [_ _f] nil)
            (on-drag-start! [_ _f] nil)
            (fit-bounds! [_ _bounds _padding] nil))}))

(defn- fire-load! [{:keys [rec]}] ((:on-load @rec)))

;; The shell-mounting proofs stub the basemap-style fetch (adsb.map.view/
;; load-style!) with a synchronous minimal style: no network in a test,
;; and the map is created during mount, exactly as before the two-edition
;; plumbing (adsb-dgb.7).
(def ^:private fixture-style
  {:version 8 :sources {} :layers []})

(defn- stub-load-style! [_url cb] (cb fixture-style))

(defn- set-data-calls [{:keys [rec]}] (:set-data @rec))

;; A push now hands the seam TWO setData calls — a trail and an aircraft
;; FeatureCollection. Select by source so a test asserts against the one it
;; means, independent of push order.
(defn- source-set-data [fake sid]
  (filter #(= sid (:source %)) (set-data-calls fake)))

(defn- aircraft-set-data [fake] (source-set-data fake layer/source-id))
(defn- trail-set-data [fake] (source-set-data fake layer/trail-source-id))

(defn- last-features [fake]
  (get-in (last (aircraft-set-data fake)) [:data :features]))

(defn- last-trail-features [fake]
  (get-in (last (trail-set-data fake)) [:data :features]))

(defn- feature-by-icao [features icao]
  (some #(when (= icao (get-in % [:properties :icao])) %) features))

;; ---------------------------------------------------------------------
;; setData carries exactly the positioned aircraft

(deftest set-data-carries-exactly-the-positioned-aircraft
  (rf-test/run-test-sync
    (let [{:keys [m rec] :as fake} (recording-map)
          handle (layer/attach! m)]
      (fire-load! fake)

      (testing "load adds both sources and both layers, trail beneath aircraft"
        (is (= {layer/source-id       layer/source-spec
                layer/trail-source-id layer/trail-source-spec}
               (:sources @rec)))
        (is (= [(layer/trail-layer-spec :day) (layer/layer-spec :day)]
               (:layers @rec))
            "the trail layer is added first, so it renders under the plane"))

      (rf/dispatch [:stream/received (frame [fixtures/ups-2717
                                             fixtures/never-positioned])])
      (r/flush) ;; reaction propagation rides Reagent's batch

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

;; ---------------------------------------------------------------------
;; The styling contract for adsb-2yu.5: track, altitude, emergency, and
;; stale all reach the source as feature properties.

(deftest feature-properties-carry-the-styling-contract
  (rf-test/run-test-sync
    (let [{:keys [m] :as fake} (recording-map)
          handle (layer/attach! m)
          ;; seen-at on the wire lets `stale` be judged; now-ms is
          ;; redef'd so the judgment is against a literal, not a clock.
          long-quiet (assoc fixtures/ups-2717 :aircraft/seen-at-ms 1000)
          just-heard (assoc fixtures/squawking-7700 :aircraft/seen-at-ms 95000)]
      (fire-load! fake)
      (with-redefs [layer/now-ms (constantly 100000)]
        (rf/dispatch [:stream/received (frame [long-quiet
                                               just-heard
                                               fixtures/on-the-ground])])
        (r/flush))

      (let [features (last-features fake)
            cruising (feature-by-icao features (:aircraft/icao fixtures/ups-2717))
            alerting (feature-by-icao features (:aircraft/icao fixtures/squawking-7700))
            taxiing  (feature-by-icao features (:aircraft/icao fixtures/on-the-ground))]
        (testing "track and altitude ride along for the altitude/heading styling"
          (is (= 97.14 (get-in cruising [:properties :track])))
          (is (= 34775 (get-in cruising [:properties :altitude]))))

        (testing "emergency is an honest boolean on every feature"
          (is (false? (get-in cruising [:properties :emergency])))
          (is (true? (get-in alerting [:properties :emergency]))))

        (testing "staleness is judged against the redef'd arrival clock"
          (is (true? (get-in cruising [:properties :stale]))
              "silent for 99 s — past the 60 s threshold")
          (is (false? (get-in alerting [:properties :stale]))
              "heard 5 s ago — fresh"))

        (testing "an aircraft on the tarmac reads \"ground\", never 0"
          (is (= "ground" (get-in taxiing [:properties :altitude])))))

      (layer/detach! handle))))

;; ---------------------------------------------------------------------
;; adsb-2yu.5: at load the layer registers its SDF icons through the seam
;; — every silhouette the style layer can name — so `icon-color` can tint
;; them. No sprite, no network; the pixels are drawn and handed across as
;; data.

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
          (is (every? #(true? (get-in % [:opts :sdf])) images))))
      (layer/detach! handle))))

;; ---------------------------------------------------------------------
;; adsb-89w / adsb-rnp: THE CENTROID RULE. Every silhouette's AREA
;; CENTROID must land on the canvas centre, because the MapLibre icon
;; anchor and the selection ring share one lat/lon — an outline whose ink
;; sits low makes the ring look like it floats above the plane. An eye
;; cannot check a centroid and the drift would be silent, so the rule is
;; asserted here, over the whole outline map: a NEW silhouette is covered
;; the moment it is listed, with nobody having to remember this test.

(defn- mirrored
  "The full closed polygon, exactly as the layer traces it: the right half
  plus its mirror, the axis points not repeated."
  [half-outline]
  (concat half-outline
          (->> (rest (butlast half-outline))
               reverse
               (map (fn [[x y]] [(- 1.0 x) y])))))

(defn- area-centroid
  "The area centroid of a closed polygon — the shoelace formula. NOT the
  bounding-box centre, which is what a careless outline gives you and what
  adsb-89w was."
  [points]
  (let [closed (concat points [(first points)])
        edges  (map vector closed (rest closed))
        cross  (fn [[[x0 y0] [x1 y1]]] (- (* x0 y1) (* x1 y0)))
        area   (* 0.5 (reduce + (map cross edges)))
        weight (fn [i [[x0 y0] [x1 y1] :as edge]]
                 (* (+ (nth [x0 y0] i) (nth [x1 y1] i)) (cross edge)))]
    [(/ (reduce + (map #(weight 0 %) edges)) (* 6 area))
     (/ (reduce + (map #(weight 1 %) edges)) (* 6 area))]))

(def ^:private centroid-tolerance
  "How far off centre an outline may sit. The shipped plane is itself
  0.004 off — these are hand-drawn shapes rounded to two decimals, not
  solved ones — so the bar is the precision the chart already holds to,
  and anything sloppier is a regression rather than rounding."
  0.005)

(deftest every-silhouette-is-centred-on-the-anchor
  (doseq [[icon-id half-outline] layer/half-outlines]
    (let [[cx cy] (area-centroid (mirrored half-outline))]
      (testing (str icon-id ": the area centroid sits on the icon anchor")
        (is (< (abs (- 0.5 cx)) centroid-tolerance)
            (str icon-id " centroid x is " cx ", not 0.5"))
        (is (< (abs (- 0.5 cy)) centroid-tolerance)
            (str icon-id " centroid y is " cy ", not 0.5 — the selection
                 ring shares this anchor (adsb-89w)")))))

  (testing "each outline is a right half: it starts and ends ON the nose
            axis, which is what makes the drawn symbol symmetric BY
            CONSTRUCTION rather than by care"
    (doseq [[icon-id half-outline] layer/half-outlines]
      (is (= 0.5 (first (first half-outline)))
          (str icon-id " must start on the axis"))
      (is (= 0.5 (first (last half-outline)))
          (str icon-id " must end on the axis"))
      (is (every? (fn [[x y]] (and (<= 0.0 x 1.0) (<= 0.0 y 1.0)))
                  half-outline)
          (str icon-id " must stay inside the canvas")))))

;; ---------------------------------------------------------------------
;; adsb-2yu.5: THE CLICK CONTRACT. This layer owns exactly one intent —
;; a click on an aircraft feature dispatches [:aircraft/select icao], the
;; icao read from the clicked feature's properties. We assert the
;; dispatch by spying re-frame's dispatch and invoking the handler the
;; layer wired through the seam with a fake feature's props. We do NOT
;; build UI or register the event — that is adsb-dgb.1's.

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
        ;; Single-click (detail 1): select. Multi-click (detail 2): follow.
        ((:on-layer-click @rec) {:icao "abc0e4" :callsign "UPS2717"
                                 :click/detail 1}))
      (is (= [[:aircraft/select "abc0e4"]] @dispatched)
          "one selection dispatch, carrying the icao from the feature")
      (layer/detach! handle))))

(deftest multi-click-and-dblclick-dispatch-follow
  (rf-test/run-test-sync
    (let [{:keys [m rec] :as fake} (recording-map)
          handle     (layer/attach! m)
          dispatched (atom [])]
      (fire-load! fake)
      (with-redefs [rf/dispatch (fn [ev] (swap! dispatched conj ev))]
        ;; Same gesture: detail 2 then dblclick — must not toggle twice.
        ((:on-layer-click @rec) {:icao "abc0e4" :click/detail 2})
        ((:on-layer-dblclick @rec) {:icao "abc0e4" :callsign "UPS2717"}))
      (is (= [[:aircraft/dblclick-follow "abc0e4"]] @dispatched)
          "one follow toggle per double-click gesture (adsb-jg4)")
      (layer/detach! handle))))

;; ---------------------------------------------------------------------
;; The ordering hazard: SSE frames can beat the map's load event. app-db
;; is the latest-wins buffer; load flushes the CURRENT picture, once.

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
               (into #{} (map #(get-in % [:properties :icao]))
                     (last-features fake)))
            "the earlier frame was superseded, not queued"))

      (layer/detach! handle))))

;; ---------------------------------------------------------------------
;; Disposal stops updates — after load, and even before it.

(deftest detach-stops-updates
  (rf-test/run-test-sync
    (let [{:keys [m] :as fake} (recording-map)
          handle (layer/attach! m)]
      (fire-load! fake)
      (rf/dispatch [:stream/received (frame [fixtures/ups-2717])])
      (r/flush)
      (let [pushes-while-attached (count (aircraft-set-data fake))]
        (is (= 2 pushes-while-attached) "the load flush, then one per frame")

        (layer/detach! handle)
        (rf/dispatch [:stream/received (frame [fixtures/squawking-7700])])
        (r/flush)
        (is (= pushes-while-attached (count (aircraft-set-data fake)))
            "after detach!, picture changes no longer reach the map")))))

(deftest detach-before-load-never-touches-the-map
  (rf-test/run-test-sync
    (let [{:keys [m rec] :as fake} (recording-map)
          handle (layer/attach! m)]
      (layer/detach! handle)
      (fire-load! fake) ;; the map loads after the component is gone
      (rf/dispatch [:stream/received (frame [fixtures/ups-2717])])
      (r/flush)
      (is (empty? (:sources @rec)))
      (is (empty? (:layers @rec)))
      (is (zero? (count (set-data-calls fake)))
          "a disposed handle ignores a late load event entirely"))))

;; ---------------------------------------------------------------------
;; adsb-2yu.6: THE CLIENT TICK. A push happens when the picture CHANGES,
;; but silence is the absence of change — so a coarse interval re-pushes
;; the current picture with a fresh clock, and silent aircraft keep aging
;; (and eventually disappear) even when no frame arrives. We drive the
;; tick manually through the set-interval! seam with a fake clock rather
;; than wait real seconds.

(deftest client-tick-ages-and-removes-between-frames
  (rf-test/run-test-sync
    (let [{:keys [m] :as fake} (recording-map)
          !tick    (atom nil)
          !cleared (atom [])]
      (with-redefs [layer/set-interval!   (fn [f _ms] (reset! !tick f) :tick-id)
                    layer/clear-interval! (fn [id] (swap! !cleared conj id))]
        (let [handle (layer/attach! m)
              ;; seen-at 0 lets age be judged; a single frame arrives and
              ;; then the stream goes quiet — only the tick advances time.
              heard  (assoc fixtures/ups-2717 :aircraft/seen-at-ms 0)
              icao   (:aircraft/icao heard)
              age-of (fn [] (get-in (feature-by-icao (last-features fake) icao)
                                    [:properties :age-s]))]
          (fire-load! fake)
          (testing "attach! started a client tick through the seam"
            (is (fn? @!tick)))

          (with-redefs [layer/now-ms (constantly 70000)]
            (rf/dispatch [:stream/received (frame [heard])])
            (r/flush))
          (testing "the frame's feature ages from its receive time (70 s)"
            (is (= 70 (age-of))))

          (testing "with no new frame, the tick re-pushes the SAME picture
                    aged further — the fade progresses on the clock alone
                    (still under the age-out line — adsb-rg1: 2 min)"
            (with-redefs [layer/now-ms (constantly 100000)]
              (@!tick))
            (is (= 100 (age-of)))
            (is (> 100 70) "the opacity-relevant age grew across the tick"))

          (testing "past the age-out line the tick drops the aircraft from
                    the setData payload entirely — it disappears"
            (with-redefs [layer/now-ms (constantly (inc aircraft/age-out-threshold-ms))]
              (@!tick))
            (is (empty? (last-features fake))))

          (layer/detach! handle)
          (testing "detach! cancels the tick through the seam"
            (is (= [:tick-id] @!cleared))))))))

;; With prediction gone (adsb-a4g), the tick is the ONLY thing that
;; advances the clock between frames — so it alone must carry the stale
;; flag from fresh to stale for a silent aircraft. A real frame stamps
;; the flag; a tick with no new frame must flip it.

(deftest client-tick-flips-the-stale-flag-on-the-clock
  (rf-test/run-test-sync
    (let [{:keys [m] :as fake} (recording-map)
          !tick (atom nil)]
      (with-redefs [layer/set-interval!   (fn [f _ms] (reset! !tick f) :tick-id)
                    layer/clear-interval! (fn [_id] nil)]
        (let [handle   (layer/attach! m)
              heard    (assoc fixtures/ups-2717 :aircraft/seen-at-ms 0)
              icao     (:aircraft/icao heard)
              stale-of (fn [] (get-in (feature-by-icao (last-features fake) icao)
                                      [:properties :stale]))]
          (fire-load! fake)
          (testing "heard 30 s ago — under the 60 s line, so not yet stale"
            (with-redefs [layer/now-ms (constantly 30000)]
              (rf/dispatch [:stream/received (frame [heard])])
              (r/flush))
            (is (false? (stale-of))))
          (testing "with no new frame, a tick past the stale line flips the
                    flag to true — staleness rides the clock alone"
            (with-redefs [layer/now-ms (constantly 90000)]
              (@!tick))
            (is (true? (stale-of))))
          (layer/detach! handle))))))

;; ---------------------------------------------------------------------
;; adsb-6wd.1: THE TRAIL SOURCE. A second GeoJSON source + line layer,
;; born at load with lineMetrics so its gradient can fade tail-to-head, and
;; fed one setData per push alongside the aircraft source. The trail is a
;; client-session accumulation (adsb.trails); here we prove it reaches the
;; map, follows a moving aircraft, and vanishes when the aircraft does.

(defn- moving-frame
  "One frame with `fixtures/ups-2717` nudged to (lat, lon)."
  [lat lon]
  (frame [(assoc fixtures/ups-2717
                 :aircraft/position {:geo/lat lat :geo/lon lon})]))

(deftest trail-source-and-layer-are-born-at-load-with-line-metrics
  (rf-test/run-test-sync
    (let [{:keys [m rec] :as fake} (recording-map)
          handle (layer/attach! m)]
      (fire-load! fake)
      (testing "the trail source exists and carries lineMetrics for the gradient"
        (is (= layer/trail-source-spec
               (get (:sources @rec) layer/trail-source-id)))
        (is (true? (:lineMetrics layer/trail-source-spec))))
      (testing "its line layer is added below the aircraft symbol layer"
        (is (= [(layer/trail-layer-spec :day) (layer/layer-spec :day)]
               (:layers @rec))))
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
          !tick  (atom nil)]
      (with-redefs [layer/set-interval!   (fn [f _ms] (reset! !tick f) :tick-id)
                    layer/clear-interval! (fn [_id] nil)]
        (let [handle (layer/attach! m)
              icao   (:aircraft/icao fixtures/ups-2717)
              heard  (fn [lat] (assoc fixtures/ups-2717
                                      :aircraft/seen-at-ms 0
                                      :aircraft/position {:geo/lat lat :geo/lon -83.0}))]
          (fire-load! fake)
          (with-redefs [layer/now-ms (constantly 70000)]
            (rf/dispatch [:stream/received (frame [(heard 27.0)])])
            (r/flush)
            (rf/dispatch [:stream/received (frame [(heard 27.1)])])
            (r/flush))
          (testing "while present, the moving aircraft has a two-point trail"
            (is (= 2 (count (get-in (feature-by-icao (last-trail-features fake) icao)
                                    [:geometry :coordinates])))))
          (testing "a tick past the age-out line drops the aircraft AND its
                    trail in the same push — the ribbon never outlives the plane"
            (with-redefs [layer/now-ms (constantly (inc aircraft/age-out-threshold-ms))]
              (@!tick))
            (is (empty? (last-features fake)))
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
        (is (pos? trail-pushes) "the trail source was fed while attached")
        (layer/detach! handle)
        (rf/dispatch [:stream/received (moving-frame 27.1 -83.0)])
        (r/flush)
        (is (= trail-pushes (count (trail-set-data fake)))
            "after detach!, the trail source is no longer touched")))))

;; ---------------------------------------------------------------------
;; THE CENTERPIECE PROOF: mount the real shell, push N picture updates
;; through re-frame, and the map component's render and mount counts do
;; not move while setData is called once per update. The aircraft never
;; touch React.

(deftest zero-reagent-re-renders-on-the-hot-path
  (rf-test/run-test-sync
    (let [node (.createElement js/document "div")
          _ (.appendChild (.-body js/document) node)
          {:keys [m] :as fake} (recording-map)
          !mounts (atom 0)
          !renders (atom 0)
          real-container view/map-container
          n 25]
      (with-redefs [maplibre/create! (fn [_container _opts]
                                       (swap! !mounts inc)
                                       m)
                    view/load-style! stub-load-style!
                    view/map-container (fn [!c]
                                         (swap! !renders inc)
                                         (real-container !c))]
        (let [root (test-dom/mount! [views/app-root] node)
              _ (fire-load! fake) ;; the map exists only once the shell has mounted
              renders-at-mount @!renders
              pushes-at-load (count (aircraft-set-data fake))]
          (is (= 1 @!mounts) "one map, created once")
          (is (= 1 renders-at-mount) "the map component rendered once, at mount")
          (is (= 1 pushes-at-load) "load flushed the (empty) current picture")

          (dotimes [i n]
            (rf/dispatch
              [:stream/received
               (frame [(assoc fixtures/ups-2717
                              :aircraft/position {:geo/lat (+ 27.0 (* 0.01 i))
                                                  :geo/lon -83.0})])])
            (r/flush))

          (testing "N ticks: N setData calls, ZERO additional Reagent work"
            (is (= (+ pushes-at-load n) (count (aircraft-set-data fake)))
                "every picture change reached the GPU path")
            (is (= renders-at-mount @!renders)
                "the render count never moved — aircraft bypass React")
            (is (= 1 @!mounts) "and nothing remounted"))

          (testing "unmount detaches the layer: updates stop"
            (test-dom/unmount! root)
            (rf/dispatch [:stream/received (frame [fixtures/squawking-7700])])
            (r/flush)
            (is (= (+ pushes-at-load n) (count (aircraft-set-data fake)))
                "no push after unmount"))))
      (.remove node))))
