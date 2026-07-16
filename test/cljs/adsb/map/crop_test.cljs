(ns adsb.map.crop-test
  (:require [adsb.geo :as geo]
            [adsb.map.crop :as crop]
            [adsb.map.maplibre :as maplibre]
            [adsb.stream :as stream]
            [adsb.wire :as wire]
            [clojure.test :refer-macros [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def ^:private captured-at-ms 1720713600000)

(def ^:private declared-crop
  {:crop/center   {:geo/lat 27.9753 :geo/lon -82.5331}
   :crop/radius-m 100000})

(def ^:private elsewhere-crop
  {:crop/center   {:geo/lat 40.6413 :geo/lon -73.7781}
   :crop/radius-m 50000})

(defn- config-frame [crop]
  (js/JSON.stringify
    (clj->js (wire/config-event->wire crop captured-at-ms))))

(defn- recording-map []
  (let [!rec (atom {:on-load    nil
                    :sources    {}
                    :layers     []
                    :set-data   []
                    :fit-bounds []})]
    {:rec !rec
     :m   (reify maplibre/Map
            (destroy! [_] (swap! !rec assoc :destroyed? true))
            (on-load! [_ f] (swap! !rec assoc :on-load f))
            (add-source! [_ id source]
              (swap! !rec update :sources assoc id source))
            (add-layer! [_ l] (swap! !rec update :layers conj l))
            (set-source-data! [_ id data]
              (swap! !rec update :set-data conj {:source id :data data}))
            (fit-bounds! [_ bounds padding]
              (swap! !rec update :fit-bounds conj {:bounds bounds :padding padding})))}))

(defn- fire-load! [{:keys [rec]}] ((:on-load @rec)))

(defn- last-features [{:keys [rec]}]
  (get-in (last (:set-data @rec)) [:data :features]))

(defn- ring [fake]
  (get-in (first (last-features fake)) [:geometry :coordinates 0]))

(deftest crop->feature-collection
  (testing "a declared crop becomes ONE Polygon whose ring is the geodesic
            circle of its radius — every vertex at the radius from the
            centre, wound [lon lat] as GeoJSON demands"
    (let [{:keys [features]} (crop/crop->feature-collection declared-crop)
          [lon-lat & _ :as coords] (get-in (first features)
                                           [:geometry :coordinates 0])]
      (is (= 1 (count features)))
      (is (= "Polygon" (get-in (first features) [:geometry :type])))
      (is (= (inc geo/default-circle-segments) (count coords)))
      (is (= (first coords) (last coords)) "the ring is closed")
      (let [[lon lat] lon-lat]
        (is (< -83 lon -82))
        (is (< 28 lat 30)))
      (doseq [[lon lat] coords]
        (is (< (abs (- (geo/distance (:crop/center declared-crop)
                                     {:geo/lat lat :geo/lon lon})
                       100000))
               1.0)))))

  (testing "no crop yields an EMPTY collection, never a default ring — a
            boundary we cannot state is one we must not draw"
    (is (= crop/empty-feature-collection (crop/crop->feature-collection nil)))
    (is (= crop/empty-feature-collection
           (crop/crop->feature-collection {:crop/radius-m 100000})))
    (is (= crop/empty-feature-collection
           (crop/crop->feature-collection {:crop/center {:geo/lat 27.9 :geo/lon -82.4}})))))

(deftest boundary-reaches-the-map
  (testing "a config event carrying a crop puts the ring into the source"
    (rf-test/run-test-sync
      (let [fake (recording-map)]
        (crop/attach! (:m fake) :day)
        (fire-load! fake)
        (rf/dispatch [:stream/config (config-frame declared-crop)])
        (r/flush)
        (is (contains? (:sources @(:rec fake)) crop/source-id))
        (is (= (inc geo/default-circle-segments) (count (ring fake)))))))

  (testing "a crop-DISABLED deployment draws no boundary at all"
    (rf-test/run-test-sync
      (let [fake (recording-map)]
        (crop/attach! (:m fake) :day)
        (fire-load! fake)
        (rf/dispatch [:stream/config (config-frame nil)])
        (r/flush)
        (is (empty? (last-features fake))))))

  (testing "disposal stops the pushes"
    (rf-test/run-test-sync
      (let [fake   (recording-map)
            handle (crop/attach! (:m fake) :day)]
        (fire-load! fake)
        (rf/dispatch [:stream/config (config-frame declared-crop)])
        (r/flush)
        (let [pushes (count (:set-data @(:rec fake)))]
          (crop/detach! handle)
          (rf/dispatch [:stream/config (config-frame nil)])
          (r/flush)
          (is (= pushes (count (:set-data @(:rec fake))))))))))

(defn- fits [{:keys [rec]}] (:fit-bounds @rec))

(deftest chart-opens-on-the-declared-boundary
  (testing "when the config event lands, the camera is framed on the crop —
            the box spans the ring, so a 60 km disc and a 400 km disc both
            land framed rather than one being a speck"
    (rf-test/run-test-sync
      (let [fake (recording-map)]
        (crop/attach! (:m fake) :day)
        (fire-load! fake)
        (rf/dispatch [:stream/config (config-frame declared-crop)])
        (r/flush)
        (is (= 1 (count (fits fake))))
        (let [{:keys [bounds padding]} (first (fits fake))]
          (is (= crop/frame-padding-px padding))
          (is (< (:geo/min-lat bounds) 27.9753 (:geo/max-lat bounds)))
          (is (< (:geo/min-lon bounds) -82.5331 (:geo/max-lon bounds)))))))

  (testing "it frames ONCE — a second config event never yanks a camera the
            reader may since have moved themselves"
    (rf-test/run-test-sync
      (let [fake (recording-map)]
        (crop/attach! (:m fake) :day)
        (fire-load! fake)
        (rf/dispatch [:stream/config (config-frame declared-crop)])
        (r/flush)
        (rf/dispatch [:stream/config (config-frame elsewhere-crop)])
        (r/flush)
        (is (= 1 (count (fits fake)))))))

  (testing "a crop-DISABLED deployment frames nothing — the map keeps the
            fixed regional fallback it booted on (adsb.map.view)"
    (rf-test/run-test-sync
      (let [fake (recording-map)]
        (crop/attach! (:m fake) :day)
        (fire-load! fake)
        (rf/dispatch [:stream/config (config-frame nil)])
        (r/flush)
        (is (empty? (fits fake))))))

  (testing "a SEEDED latch frames nothing either: this is the map a theme
            re-print built under a reader who was already somewhere, and it
            carries their camera — the boundary still draws, but pulling the
            chart back onto it would be the mid-session yank (adsb-1rg)"
    (rf-test/run-test-sync
      (let [fake (recording-map)]
        (crop/attach! (:m fake) :day {:framed? true})
        (fire-load! fake)
        (rf/dispatch [:stream/config (config-frame declared-crop)])
        (r/flush)
        (is (empty? (fits fake)))
        (is (seq (last-features fake)))))))

(deftest crop-bounds-box
  (testing "the box is derived from the RING, so it is wider in longitude
            than in latitude at this latitude — a degree of longitude is not
            a degree of latitude anywhere but the equator"
    (let [{:geo/keys [min-lat max-lat min-lon max-lon]}
          (crop/crop-bounds declared-crop)]
      (is (> (- max-lon min-lon) (- max-lat min-lat)))))

  (testing "no crop, no box"
    (is (nil? (crop/crop-bounds nil)))
    (is (nil? (crop/crop-bounds {:crop/radius-m 100000})))))

(deftest config-decode
  (testing "the wire's config frame decodes to the domain crop"
    (is (= declared-crop (stream/data->crop (config-frame declared-crop)))))

  (testing "a config frame with no crop decodes to nil"
    (is (nil? (stream/data->crop (config-frame nil))))))
