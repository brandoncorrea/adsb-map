(ns adsb.map.aircraft-layer-test
  "The imperative aircraft layer, proven at the seam. A recording fake
  stands in for MapLibre (docs/testing-setup.md, \"The Map Seam\"), the
  wire round-trip plays the part of the server, and the assertions cover
  the four promises of adsb-2yu.4: setData carries exactly the positioned
  aircraft (with the styling properties adsb-2yu.5 needs), early frames
  buffer latest-wins until the map loads, disposal stops the pushes, and
  — the centerpiece — N picture updates cost ZERO Reagent re-renders."
  (:require
    [adsb.fixtures :as fixtures]
    [adsb.geo :as geo]
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
      (clj->js (wire/picture->wire picture nil nil 1720713600000)))))

;; ---------------------------------------------------------------------
;; The recording fake map. It captures everything crossing the seam —
;; still Clojure data, because the real seam does clj->js at the edge —
;; and lets the test decide when the "load" event fires.

(defn- recording-map []
  (let [!rec (atom {:on-load nil :sources {} :layers [] :set-data []
                    :images [] :on-layer-click nil :hover-layers []})]
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
            (on-layer-hover-cursor! [_ layer-id]
              (swap! !rec update :hover-layers conj layer-id))
            ;; The shell-mounting proofs attach the emergency annotations
            ;; too (adsb.map.emergency); their fixtures squawk nothing, so
            ;; only the moveend registration ever crosses this seam.
            (bounds [_] {:geo/min-lat 27.0 :geo/max-lat 29.0
                         :geo/min-lon -83.0 :geo/max-lon -81.0})
            (on-move! [_ _f] nil))}))

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

(defn- feature-position
  "The icao's latest pushed coordinates as a `{:geo/lat _ :geo/lon _}`
  position, ready for geo/distance and geo/bearing."
  [fake icao]
  (let [[lon lat] (get-in (feature-by-icao (last-features fake) icao)
                          [:geometry :coordinates])]
    {:geo/lat lat :geo/lon lon}))

(defn- close?
  "Within `tol` of `expected` — projected geo math never lands on a
  literal."
  [expected actual tol]
  (< (abs (- expected actual)) tol))

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
;; adsb-2yu.5: at load the layer registers its two SDF icons through the
;; seam — the plane and the dot — so `icon-color` can tint them. No
;; sprite, no network; the pixels are drawn and handed across as data.

(deftest load-registers-the-plane-and-dot-icons-sdf
  (rf-test/run-test-sync
    (let [{:keys [m rec] :as fake} (recording-map)
          handle (layer/attach! m)]
      (fire-load! fake)
      (let [images (:images @rec)]
        (testing "both icons registered via the seam's add-image!"
          (is (= #{style/plane-icon-id style/dot-icon-id}
                 (into #{} (map :id) images))))
        (testing "registered SDF, so the altitude/emergency colour can tint them"
          (is (seq images))
          (is (every? #(true? (get-in % [:opts :sdf])) images))))
      (layer/detach! handle))))

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
      (testing "the layer wired a click handler AND a hover cursor through the seam"
        (is (fn? (:on-layer-click @rec)))
        (is (= [layer/layer-id] (:hover-layers @rec))))
      (with-redefs [rf/dispatch (fn [ev] (swap! dispatched conj ev))]
        ;; The seam hands the app the clicked feature's properties as a
        ;; Clojure map; drive the handler with one directly.
        ((:on-layer-click @rec) {:icao "abc0e4" :callsign "UPS2717"}))
      (is (= [[:aircraft/select "abc0e4"]] @dispatched)
          "one selection dispatch, carrying the icao from the feature")
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
                    aged further — the fade progresses on the clock alone"
            (with-redefs [layer/now-ms (constantly 200000)]
              (@!tick))
            (is (= 200 (age-of)))
            (is (> 200 70) "the opacity-relevant age grew across the tick"))

          (testing "past the age-out line the tick drops the aircraft from
                    the setData payload entirely — it disappears"
            (with-redefs [layer/now-ms (constantly 400000)]
              (@!tick))
            (is (empty? (last-features fake))))

          (layer/detach! handle)
          (testing "detach! cancels the tick through the seam"
            (is (= [:tick-id] @!cleared))))))))

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
            (with-redefs [layer/now-ms (constantly 400000)]
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

;; ---------------------------------------------------------------------
;; adsb-6wd.2: THE PROJECTION LOOP. Between real frames a rAF loop
;; dead-reckons the cached picture forward (adsb.geo) and pushes the
;; aircraft source only — trails stay a record of truth. Like the tick,
;; the loop is driven through seams with a fake clock: tests capture the
;; frame callback and the visibility handler and fire them by hand.

(defn- with-raf-seams
  "Run `body-fn` with the projection loop's seams faked, passing it the
  recording atoms: :!frame (the latest captured rAF callback),
  :!scheduled (how many frames were requested), :!cancelled (cancelled
  handles), :!vis (the captured visibility handler), :!vis-removed
  (handlers unhooked), and :!hidden? (drives document-hidden?)."
  [body-fn]
  (let [!frame     (atom nil)
        !scheduled (atom 0)
        !cancelled (atom [])
        !vis       (atom nil)
        !vis-removed (atom [])
        !hidden?   (atom false)]
    (with-redefs [layer/request-animation-frame!
                  (fn [f] (reset! !frame f) (swap! !scheduled inc) :raf-id)
                  layer/cancel-animation-frame!
                  (fn [id] (swap! !cancelled conj id))
                  layer/document-hidden? (fn [] @!hidden?)
                  layer/on-visibility-change! (fn [h] (reset! !vis h))
                  layer/off-visibility-change!
                  (fn [h] (swap! !vis-removed conj h))]
      (body-fn {:!frame !frame :!scheduled !scheduled
                :!cancelled !cancelled :!vis !vis
                :!vis-removed !vis-removed :!hidden? !hidden?}))))

;; ups-2717 carries gs 450.5 kt and track 97.14° — ten seconds of dead
;; reckoning is 450.5 kt * 0.5144 m/s/kt * 10 s.
(def ^:private ten-seconds-of-ups-m 2317.6)

(deftest raf-frames-glide-a-projectable-aircraft-between-real-frames
  (rf-test/run-test-sync
    (with-raf-seams
      (fn [{:keys [!frame]}]
        (let [{:keys [m] :as fake} (recording-map)
              handle (layer/attach! m)
              icao   (:aircraft/icao fixtures/ups-2717)
              heard  (assoc fixtures/ups-2717 :aircraft/seen-at-ms 0)]
          (fire-load! fake)
          (testing "attach! started the projection loop through the seam"
            (is (fn? @!frame)))
          (with-redefs [layer/now-ms (constantly 0)]
            (rf/dispatch [:stream/received (frame [heard])])
            (r/flush))
          (let [base         (feature-position fake icao)
                trail-pushes (count (trail-set-data fake))]
            (testing "an animation frame 10 s on pushes the aircraft
                      ~2.3 km along its 97.14° track — the glide"
              (with-redefs [layer/now-ms (constantly 10000)]
                (@!frame 0))
              (let [projected (feature-position fake icao)]
                (is (close? ten-seconds-of-ups-m
                            (geo/distance base projected) 5))
                (is (close? 97.14 (geo/bearing base projected) 0.5))))
            (testing "pushes are throttled inside rAF: a frame 10 ms
                      later reschedules but pushes nothing"
              (let [pushes (count (aircraft-set-data fake))]
                (with-redefs [layer/now-ms (constantly 10010)]
                  (@!frame 0))
                (is (= pushes (count (aircraft-set-data fake))))))
            (testing "the trail source is untouched by projection — the
                      ribbon stays a record of REAL positions"
              (is (= trail-pushes (count (trail-set-data fake))))))
          (layer/detach! handle))))))

(deftest a-real-frame-snaps-the-projection-back-to-truth
  (rf-test/run-test-sync
    (with-raf-seams
      (fn [{:keys [!frame]}]
        (let [{:keys [m] :as fake} (recording-map)
              handle (layer/attach! m)
              icao   (:aircraft/icao fixtures/ups-2717)
              heard  (assoc fixtures/ups-2717 :aircraft/seen-at-ms 0)]
          (fire-load! fake)
          (with-redefs [layer/now-ms (constantly 0)]
            (rf/dispatch [:stream/received (frame [heard])])
            (r/flush))
          (with-redefs [layer/now-ms (constantly 10000)]
            (@!frame 0)) ;; glide 10 s away from the base
          (testing "a real frame lands: the pushed position is the
                    REPORTED one, exactly — the snap"
            (with-redefs [layer/now-ms (constantly 11000)]
              (rf/dispatch
                [:stream/received
                 (frame [(assoc heard
                                :aircraft/seen-at-ms 11000
                                :aircraft/position {:geo/lat 28.0
                                                    :geo/lon -83.9})])])
              (r/flush))
            (is (= [-83.9 28.0]
                   (get-in (feature-by-icao (last-features fake) icao)
                           [:geometry :coordinates]))))
          (testing "the next projection measures from the NEW base — the
                    real frame reset it"
            (with-redefs [layer/now-ms (constantly 12000)]
              (@!frame 0))
            (is (close? 231.8 ;; one second at 450.5 kt
                        (geo/distance {:geo/lat 28.0 :geo/lon -83.9}
                                      (feature-position fake icao))
                        1)))
          (layer/detach! handle))))))

(deftest projection-holds-what-it-cannot-honestly-move
  (rf-test/run-test-sync
    (with-raf-seams
      (fn [{:keys [!frame]}]
        (let [{:keys [m] :as fake} (recording-map)
              handle     (layer/attach! m)
              heard-ups  (assoc fixtures/ups-2717 :aircraft/seen-at-ms 0)
              ;; heard, positioned, fresh — but no track: absent is not
              ;; zero, so dead reckoning must not move it.
              trackless  (-> fixtures/squawking-7700
                             (assoc :aircraft/seen-at-ms 0)
                             (dissoc :aircraft/track-deg))
              ups-icao   (:aircraft/icao heard-ups)
              still-icao (:aircraft/icao trackless)]
          (fire-load! fake)
          (testing "a sky where NOTHING may honestly move pushes nothing
                    — the loop idles instead of re-sending stillness"
            (with-redefs [layer/now-ms (constantly 0)]
              (rf/dispatch [:stream/received (frame [trackless])])
              (r/flush))
            (let [pushes (count (aircraft-set-data fake))]
              (with-redefs [layer/now-ms (constantly 10000)]
                (@!frame 0))
              (is (= pushes (count (aircraft-set-data fake))))))
          (testing "in a mixed sky the vector-less aircraft holds its
                    real position while its neighbour glides"
            (with-redefs [layer/now-ms (constantly 0)]
              (rf/dispatch [:stream/received (frame [heard-ups trackless])])
              (r/flush))
            (let [ups-base   (feature-position fake ups-icao)
                  still-base (feature-position fake still-icao)]
              (with-redefs [layer/now-ms (constantly 10000)]
                (@!frame 0))
              (is (close? ten-seconds-of-ups-m
                          (geo/distance ups-base
                                        (feature-position fake ups-icao))
                          5))
              (is (= still-base (feature-position fake still-icao))
                  "no track, no movement — absent is not zero")))
          (testing "past the stale threshold nothing projects: the whole
                    sky holds, so the loop pushes nothing"
            (let [pushes (count (aircraft-set-data fake))]
              (with-redefs [layer/now-ms (constantly 70000)] ;; > 60 s
                (@!frame 0))
              (is (= pushes (count (aircraft-set-data fake)))
                  "a silent plane gliding forever would be a lie")))
          (layer/detach! handle))))))

(deftest detach-stops-the-projection-loop
  (rf-test/run-test-sync
    (with-raf-seams
      (fn [{:keys [!frame !scheduled !cancelled !vis !vis-removed]}]
        (let [{:keys [m] :as fake} (recording-map)
              handle (layer/attach! m)]
          (fire-load! fake)
          (with-redefs [layer/now-ms (constantly 0)]
            (rf/dispatch
              [:stream/received
               (frame [(assoc fixtures/ups-2717 :aircraft/seen-at-ms 0)])])
            (r/flush))
          (layer/detach! handle)
          (testing "detach! cancels the pending frame and unhooks the
                    visibility handler through the seams"
            (is (= [:raf-id] @!cancelled))
            (is (= [@!vis] @!vis-removed)))
          (testing "a straggler frame after detach pushes nothing and
                    does not reschedule"
            (let [pushes    (count (set-data-calls fake))
                  scheduled @!scheduled]
              (with-redefs [layer/now-ms (constantly 10000)]
                (@!frame 0))
              (is (= pushes (count (set-data-calls fake))))
              (is (= scheduled @!scheduled)))))))))

(deftest a-hidden-tab-pauses-the-projection-loop
  (rf-test/run-test-sync
    (with-raf-seams
      (fn [{:keys [!frame !scheduled !cancelled !vis !hidden?]}]
        (let [{:keys [m] :as fake} (recording-map)
              handle (layer/attach! m)
              icao   (:aircraft/icao fixtures/ups-2717)]
          (fire-load! fake)
          (with-redefs [layer/now-ms (constantly 0)]
            (rf/dispatch
              [:stream/received
               (frame [(assoc fixtures/ups-2717 :aircraft/seen-at-ms 0)])])
            (r/flush))
          (testing "hiding the tab cancels the pending frame"
            (is (fn? @!vis) "a visibility handler was registered")
            (reset! !hidden? true)
            (@!vis)
            (is (= [:raf-id] @!cancelled)))
          (testing "a straggler frame while hidden pushes nothing and
                    does not reschedule — a background tab burns no CPU"
            (let [pushes    (count (aircraft-set-data fake))
                  scheduled @!scheduled]
              (with-redefs [layer/now-ms (constantly 10000)]
                (@!frame 0))
              (is (= pushes (count (aircraft-set-data fake))))
              (is (= scheduled @!scheduled))))
          (testing "showing the tab again restarts the loop, and the
                    glide resumes from the cached picture"
            (let [scheduled @!scheduled
                  base      (feature-position fake icao)]
              (reset! !hidden? false)
              (@!vis)
              (is (= (inc scheduled) @!scheduled) "one fresh frame requested")
              (with-redefs [layer/now-ms (constantly 10000)]
                (@!frame 0))
              (is (close? ten-seconds-of-ups-m
                          (geo/distance base (feature-position fake icao))
                          5))))
          (layer/detach! handle))))))

;; The zero-re-render guarantee extends to the projection loop: rAF
;; pushes ride the same imperative path as frame pushes, so N projected
;; frames cost N setData calls and ZERO Reagent work.

(deftest raf-projection-costs-zero-reagent-re-renders
  (rf-test/run-test-sync
    (with-raf-seams
      (fn [{:keys [!frame]}]
        (let [node (.createElement js/document "div")
              _ (.appendChild (.-body js/document) node)
              {:keys [m] :as fake} (recording-map)
              !renders (atom 0)
              real-container view/map-container
              n 20]
          (with-redefs [maplibre/create! (fn [_container _opts] m)
                        view/load-style! stub-load-style!
                        view/map-container (fn [!c]
                                             (swap! !renders inc)
                                             (real-container !c))]
            (let [root (test-dom/mount! [views/app-root] node)]
              (fire-load! fake)
              (with-redefs [layer/now-ms (constantly 0)]
                (rf/dispatch
                  [:stream/received
                   (frame [(assoc fixtures/ups-2717 :aircraft/seen-at-ms 0)])])
                (r/flush))
              (let [renders @!renders
                    pushes  (count (aircraft-set-data fake))]
                (dotimes [i n]
                  ;; 100 ms apart — every frame clears the push throttle.
                  (with-redefs [layer/now-ms (constantly (* (inc i) 100))]
                    (@!frame 0)))
                (testing "N projection frames: N setData calls, ZERO
                          additional Reagent work"
                  (is (= (+ pushes n) (count (aircraft-set-data fake)))
                      "every projection frame reached the GPU path")
                  (is (= renders @!renders)
                      "the render count never moved — projection bypasses
                      React")))
              (test-dom/unmount! root)))
          (.remove node))))))
