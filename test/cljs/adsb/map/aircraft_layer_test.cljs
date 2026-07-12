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
    [adsb.map.aircraft-layer :as layer]
    [adsb.map.maplibre :as maplibre]
    [adsb.map.view :as view]
    [adsb.stream]
    [adsb.views :as views]
    [adsb.wire :as wire]
    [cljs.test :refer-macros [deftest is testing]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.dom :as rdom]))

;; ---------------------------------------------------------------------
;; Server impersonation: one SSE frame's data string, exactly as the
;; backend would build it — the shared adsb.wire codec, then JSON.

(defn- frame
  [aircraft]
  (let [picture (into {} (map (juxt :aircraft/icao identity)) aircraft)]
    (js/JSON.stringify (clj->js (wire/picture->wire picture 1720713600000)))))

;; ---------------------------------------------------------------------
;; The recording fake map. It captures everything crossing the seam —
;; still Clojure data, because the real seam does clj->js at the edge —
;; and lets the test decide when the "load" event fires.

(defn- recording-map []
  (let [!rec (atom {:on-load nil :sources {} :layers [] :set-data []})]
    {:rec !rec
     :m   (reify maplibre/Map
            (destroy! [_] (swap! !rec assoc :destroyed? true))
            (on-load! [_ f] (swap! !rec assoc :on-load f))
            (add-source! [_ id source] (swap! !rec update :sources assoc id source))
            (add-layer! [_ l] (swap! !rec update :layers conj l))
            (set-source-data! [_ id data]
              (swap! !rec update :set-data conj {:source id :data data})))}))

(defn- fire-load! [{:keys [rec]}] ((:on-load @rec)))

(defn- set-data-calls [{:keys [rec]}] (:set-data @rec))

(defn- last-features [fake]
  (get-in (last (set-data-calls fake)) [:data :features]))

(defn- feature-by-icao [features icao]
  (some #(when (= icao (get-in % [:properties :icao])) %) features))

;; ---------------------------------------------------------------------
;; setData carries exactly the positioned aircraft

(deftest set-data-carries-exactly-the-positioned-aircraft
  (rf-test/run-test-sync
    (let [{:keys [m rec] :as fake} (recording-map)
          handle (layer/attach! m)]
      (fire-load! fake)

      (testing "load adds the source and the neutral layer, once"
        (is (= {layer/source-id layer/source-spec} (:sources @rec)))
        (is (= [layer/layer-spec] (:layers @rec))))

      (rf/dispatch [:stream/received (frame [fixtures/ups-2717
                                             fixtures/never-positioned])])
      (r/flush) ;; reaction propagation rides Reagent's batch

      (let [{:keys [source data]} (last (set-data-calls fake))
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
        (is (= 1 (count (set-data-calls fake))))
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
      (let [pushes-while-attached (count (set-data-calls fake))]
        (is (= 2 pushes-while-attached) "the load flush, then one per frame")

        (layer/detach! handle)
        (rf/dispatch [:stream/received (frame [fixtures/squawking-7700])])
        (r/flush)
        (is (= pushes-while-attached (count (set-data-calls fake)))
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
                    view/map-container (fn [!c]
                                         (swap! !renders inc)
                                         (real-container !c))]
        (rdom/render [views/app-root] node)
        (r/flush)
        (fire-load! fake)

        (let [renders-at-mount @!renders
              pushes-at-load (count (set-data-calls fake))]
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
            (is (= (+ pushes-at-load n) (count (set-data-calls fake)))
                "every picture change reached the GPU path")
            (is (= renders-at-mount @!renders)
                "the render count never moved — aircraft bypass React")
            (is (= 1 @!mounts) "and nothing remounted"))

          (testing "unmount detaches the layer: updates stop"
            (rdom/unmount-component-at-node node)
            (rf/dispatch [:stream/received (frame [fixtures/squawking-7700])])
            (r/flush)
            (is (= (+ pushes-at-load n) (count (set-data-calls fake)))
                "no push after unmount"))))
      (.remove node))))
