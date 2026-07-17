(ns adsb.geo-test
  (:require [adsb.geo :as geo]
            [clojure.test :refer [deftest testing is]]))

(defn- close? [expected actual tol]
  (< (abs (- expected actual)) tol))

(def ^:private equator-degree-m 111194.9)

(deftest distance-haversine
  (testing "Cambridge -> Paris matches the published great-circle 404 km
            (the canonical Movable-Type worked example)"
    (is (close? 404300
                (geo/distance {:geo/lat 52.205 :geo/lon 0.119}
                              {:geo/lat 48.857 :geo/lon 2.351})
                2000)))

  (testing "one degree of longitude along the equator is ~111.19 km"
    (is (close? equator-degree-m
                (geo/distance {:geo/lat 0 :geo/lon 0}
                              {:geo/lat 0 :geo/lon 1})
                1)))

  (testing "a point is zero meters from itself"
    (is (zero? (geo/distance {:geo/lat 40.64 :geo/lon -73.78}
                             {:geo/lat 40.64 :geo/lon -73.78}))))

  (testing "meters convert cleanly to km and nautical miles"
    (let [m (geo/distance {:geo/lat 0 :geo/lon 0}
                          {:geo/lat 0 :geo/lon 1})]
      (is (close? 111.19 (geo/meters->km m) 0.01))
      (is (close? 60.04 (geo/meters->nm m) 0.01)))))

(deftest bearing-initial
  (testing "Cambridge -> Paris matches the published initial bearing 156°"
    (is (close? 156.2
                (geo/bearing {:geo/lat 52.205 :geo/lon 0.119}
                             {:geo/lat 48.857 :geo/lon 2.351})
                0.5)))

  (testing "the four cardinal directions from the origin, normalized to [0, 360)"
    (let [origin {:geo/lat 0 :geo/lon 0}]
      (is (close? 0 (geo/bearing origin {:geo/lat 1 :geo/lon 0}) 0.01))
      (is (close? 90 (geo/bearing origin {:geo/lat 0 :geo/lon 1}) 0.01))
      (is (close? 180 (geo/bearing origin {:geo/lat -1 :geo/lon 0}) 0.01))
      (is (close? 270 (geo/bearing origin {:geo/lat 0 :geo/lon -1}) 0.01)))))

(deftest destination-known-values
  (testing "the canonical Movable-Type worked example: 124.8 km on an
            initial bearing of 96°01′18″ from 53°19′14″N 1°43′47″W lands
            at 53°11′18″N 0°08′00″E"
    (let [reached (geo/destination {:geo/lat 53.320556 :geo/lon -1.729722} 96.021667 124800)]
      (is (close? 53.18833 (:geo/lat reached) 0.001))
      (is (close? 0.13333 (:geo/lon reached) 0.001))))

  (testing "one equator-degree of meters due north or east of the origin
            is one degree of latitude or longitude"
    (let [origin {:geo/lat 0 :geo/lon 0}
          north  (geo/destination origin 0 equator-degree-m)
          east   (geo/destination origin 90 equator-degree-m)]
      (is (close? 1 (:geo/lat north) 1e-6))
      (is (close? 0 (:geo/lon north) 1e-6))
      (is (close? 0 (:geo/lat east) 1e-6))
      (is (close? 1 (:geo/lon east) 1e-6))))

  (testing "zero distance goes nowhere"
    (let [from {:geo/lat 27.961166 :geo/lon -83.975953}]
      (is (close? 0 (geo/distance from (geo/destination from 97.14 0)) 0.001))))

  (testing "destination inverts distance + bearing: going the measured
            distance on the measured bearing reaches the measured point"
    (let [from    {:geo/lat 52.205 :geo/lon 0.119}
          to      {:geo/lat 48.857 :geo/lon 2.351}
          reached (geo/destination from
                                   (geo/bearing from to)
                                   (geo/distance from to))]
      (is (close? (:geo/lat to) (:geo/lat reached) 1e-6))
      (is (close? (:geo/lon to) (:geo/lon reached) 1e-6))))

  (testing "crossing the antimeridian normalizes longitude to [-180, 180)"
    (let [reached (geo/destination {:geo/lat 0 :geo/lon 179.5}
                                   90
                                   equator-degree-m)]
      (is (close? -179.5 (:geo/lon reached) 1e-6)))))

(deftest knots-to-meters-per-second
  (testing "one knot is one nautical mile per hour"
    (is (close? 0.514444 (geo/knots->mps 1) 1e-4))
    (is (close? 231.757 (geo/knots->mps 450.5) 0.01))
    (is (zero? (geo/knots->mps 0)))))

(deftest bounds-box
  (testing "the box spans the extremes of a seq of positions"
    (is (= {:geo/min-lat 10 :geo/max-lat 40
            :geo/min-lon -20 :geo/max-lon 5}
           (geo/bounds [{:geo/lat 40 :geo/lon 5}
                        {:geo/lat 10 :geo/lon -20}
                        {:geo/lat 25 :geo/lon 0}]))))

  (testing "a single position yields a degenerate, zero-area box"
    (is (= {:geo/min-lat 30 :geo/max-lat 30
            :geo/min-lon 12 :geo/max-lon 12}
           (geo/bounds [{:geo/lat 30 :geo/lon 12}]))))

  (testing "there is no box around nothing"
    (is (nil? (geo/bounds [])))
    (is (nil? (geo/bounds nil)))))

(def ^:private viewport
  {:geo/min-lat 27.0
   :geo/max-lat 29.0
   :geo/min-lon -83.0
   :geo/max-lon -81.0})

(deftest edge-annotation-known-geometry
  (testing "a target INSIDE the viewport needs no arrow"
    (is (nil? (geo/edge-annotation viewport {:geo/lat 28.0 :geo/lon -82.0})))
    (is (nil? (geo/edge-annotation viewport {:geo/lat 28.9 :geo/lon -81.1}))))

  (testing "a target due NORTH exits through the top edge's midpoint,
            bearing 0 along the meridian"
    (let [edge (geo/edge-annotation viewport {:geo/lat 31.0 :geo/lon -82.0})]
      (is (close? 0.5 (:edge/x edge) 1e-9))
      (is (close? 0.0 (:edge/y edge) 1e-9))
      (is (close? 0.0 (:edge/bearing-deg edge) 0.01))))

  (testing "a target due SOUTH exits through the bottom edge's midpoint,
            bearing 180"
    (let [edge (geo/edge-annotation viewport {:geo/lat 25.0 :geo/lon -82.0})]
      (is (close? 0.5 (:edge/x edge) 1e-9))
      (is (close? 1.0 (:edge/y edge) 1e-9))
      (is (close? 180.0 (:edge/bearing-deg edge) 0.01))))

  (testing "a target on the exact 45° plate diagonal exits through the
            top-right corner"
    (let [edge (geo/edge-annotation viewport {:geo/lat 30.0 :geo/lon -80.0})]
      (is (close? 1.0 (:edge/x edge) 1e-9))
      (is (close? 0.0 (:edge/y edge) 1e-9))))

  (testing "the tighter half-span wins: a target far east and a little
            north pins to the RIGHT edge, part-way up"
    (let [edge (geo/edge-annotation viewport {:geo/lat 28.5 :geo/lon -78.0})]
      (is (close? 1.0 (:edge/x edge) 1e-9))
      (is (close? 0.4375 (:edge/y edge) 1e-9))
      (is (< 80 (:edge/bearing-deg edge) 90))))

  (testing "the distance is the great-circle distance from the viewport
            centre, so the arrow's label agrees with geo/distance"
    (let [target {:geo/lat 31.0 :geo/lon -82.0}
          edge   (geo/edge-annotation viewport target)]
      (is (close? (geo/distance {:geo/lat 28.0 :geo/lon -82.0} target)
                  (:edge/distance-m edge)
                  1))))

  (testing "an unwrapped-bounds pan meets a wrapped longitude: the target
            reads in the viewport's frame, not 360° away"
    (let [across {:geo/min-lat 27.0 :geo/max-lat 29.0
                  :geo/min-lon 178.0 :geo/max-lon 182.0}]
      (is (nil? (geo/edge-annotation across {:geo/lat 28.0 :geo/lon -179.0})))
      (let [edge (geo/edge-annotation across {:geo/lat 28.0 :geo/lon -175.0})]
        (is (close? 1.0 (:edge/x edge) 1e-9)))))

  (testing "a degenerate viewport has no edge worth annotating"
    (is (nil? (geo/edge-annotation
                {:geo/min-lat 28.0 :geo/max-lat 28.0
                 :geo/min-lon -82.0 :geo/max-lon -82.0}
                {:geo/lat 31.0 :geo/lon -82.0})))))

(deftest circle-ring
  (let [center {:geo/lat 27.9753 :geo/lon -82.5331}
        radius 100000]

    (testing "every vertex sits on the rim — at the radius from the centre,
              which is what makes it a GEODESIC circle and not a planar one
              that sags with latitude"
      (doseq [p (geo/circle center radius)]
        (is (< (abs (- (geo/distance center p) radius)) 1.0))))

    (testing "the ring is CLOSED — GeoJSON requires a LinearRing's first and
              last positions to be identical, and MapLibre drops a ring that
              is not"
      (let [ring (geo/circle center radius)]
        (is (= (first ring) (last ring)))
        (is (= (inc geo/default-circle-segments) (count ring)))))

    (testing "the first vertex leaves on bearing 0 — due north of the centre"
      (let [north (first (geo/circle center radius))]
        (is (> (:geo/lat north) (:geo/lat center)))
        (is (< (abs (- (:geo/lon north) (:geo/lon center))) 1e-9))))

    (testing "segment count is honoured"
      (is (= 5 (count (geo/circle center radius 4)))))))
