(ns adsb.trails-test
  "The pure trail history and its GeoJSON conversion (adsb-6wd.1). Ring
  semantics — append-on-change, the cap, drop-on-leave — and the history ->
  FeatureCollection projection are proven here against literals; the
  imperative wiring (a second source, the live-icao filter) is proven at the
  seam in adsb.map.aircraft-layer-test."
  (:require
    [adsb.fixtures :as fixtures]
    [adsb.trails :as trails]
    #?(:clj  [clojure.test :refer [deftest testing is]]
       :cljs [cljs.test :refer-macros [deftest testing is]])))

(defn- pos [lat lon] {:geo/lat lat :geo/lon lon})

(defn- view?
  "Is `v` a `subvec` VIEW rather than a vector of its own? A view still points
  at the vector it was cut from, and conj-ing onto one appends to that backing
  — so a ring that is a view retains every position ever dropped from it, no
  matter what it reports as its count (adsb-3kf). Both runtimes have the same
  structure under a different name, so both are named here."
  [v]
  #?(:clj  (instance? clojure.lang.APersistentVector$SubVector v)
     :cljs (instance? cljs.core/Subvec v)))

;; ---------------------------------------------------------------------
;; append-position — the ring

(deftest append-grows-on-a-new-position
  (testing "an empty (nil) ring seeds with the first position"
    (is (= [(pos 1 1)] (trails/append-position nil (pos 1 1)))))

  (testing "a moved aircraft appends, oldest first"
    (is (= [(pos 1 1) (pos 2 2) (pos 3 3)]
           (-> nil
               (trails/append-position (pos 1 1))
               (trails/append-position (pos 2 2))
               (trails/append-position (pos 3 3)))))))

(deftest append-is-a-no-op-when-the-position-is-unchanged
  (testing "repeating the newest point appends nothing — the trail records
            movement, not stillness"
    (let [ring (-> nil
                   (trails/append-position (pos 1 1))
                   (trails/append-position (pos 2 2)))]
      (is (= ring (trails/append-position ring (pos 2 2))))
      (is (= 2 (count (trails/append-position ring (pos 2 2)))))))

  (testing "a return to an EARLIER (but not newest) position still appends —
            only the immediate repeat is suppressed"
    (let [ring (-> nil
                   (trails/append-position (pos 1 1))
                   (trails/append-position (pos 2 2)))]
      (is (= [(pos 1 1) (pos 2 2) (pos 1 1)]
             (trails/append-position ring (pos 1 1)))))))

(deftest append-caps-the-ring-dropping-the-oldest
  (testing "the ring never exceeds max-positions; overflow drops the tail"
    (let [ring (reduce (fn [r i] (trails/append-position r (pos i i)))
                       nil
                       (range (+ trails/max-positions 10)))]
      (is (= trails/max-positions (count ring)))
      (testing "it kept the NEWEST max-positions, in order"
        (is (= (pos (dec (+ trails/max-positions 10))
                    (dec (+ trails/max-positions 10)))
               (peek ring)))
        (is (= (pos 10 10) (first ring))
            "the first ten positions were dropped off the front")))))

(deftest append-actually-releases-the-positions-it-drops
  (testing "a long-dwelling aircraft (a loitering helicopter, pattern traffic)
            retains max-positions, not its whole history — the cap is a real
            memory bound, not just a count (adsb-3kf)"
    (let [ring (reduce (fn [r i] (trails/append-position r (pos i i)))
                       nil
                       (range (* 10 trails/max-positions)))]
      (is (= trails/max-positions (count ring)))
      (is (not (view? ring))
          "the trimmed ring must be a vector of its own — a subvec view holds
           its backing vector alive, so every dropped position stays reachable
           and memory grows linearly with dwell time")

      (testing "and it stays that way as the ring keeps turning over"
        (is (not (view? (trails/append-position ring (pos 999 999)))))))))

;; ---------------------------------------------------------------------
;; accumulate — folding a picture into history

(deftest accumulate-appends-per-aircraft-on-change
  (testing "each aircraft accumulates its own ring across frames"
    (let [a1     (assoc fixtures/ups-2717 :aircraft/position (pos 27.0 -83.0))
          a2     (assoc fixtures/ups-2717 :aircraft/position (pos 27.1 -83.0))
          icao   (:aircraft/icao fixtures/ups-2717)
          h1     (trails/accumulate {} [a1])
          h2     (trails/accumulate h1 [a2])]
      (is (= [(pos 27.0 -83.0)] (get h1 icao)))
      (is (= [(pos 27.0 -83.0) (pos 27.1 -83.0)] (get h2 icao))))))

(deftest accumulate-drops-an-aircraft-that-leaves-the-picture
  (testing "an aircraft absent from the new picture is not carried forward —
            its ring is dropped and its memory reclaimed"
    (let [moving (assoc fixtures/ups-2717 :aircraft/position (pos 27.0 -83.0))
          icao   (:aircraft/icao fixtures/ups-2717)
          h1     (trails/accumulate {} [moving])]
      (is (contains? h1 icao))
      (is (= {} (trails/accumulate h1 []))
          "gone from the picture -> gone from history"))))

(deftest accumulate-keeps-but-does-not-grow-a-position-less-aircraft
  (testing "a position-less update keeps the existing ring, appending nothing"
    (let [icao   (:aircraft/icao fixtures/ups-2717)
          moving (assoc fixtures/ups-2717 :aircraft/position (pos 27.0 -83.0))
          h1     (trails/accumulate {} [moving])
          ;; the SAME aircraft, now heard without a position
          quiet  (dissoc fixtures/ups-2717 :aircraft/position)
          h2     (trails/accumulate h1 [quiet])]
      (is (= (get h1 icao) (get h2 icao)))))

  (testing "a never-positioned aircraft contributes no ring at all"
    (is (= {} (trails/accumulate {} [fixtures/never-positioned])))))

;; ---------------------------------------------------------------------
;; history->trail-feature-collection — the GeoJSON projection

(deftest trail-collection-emits-a-linestring-per-live-multi-point-ring
  (let [icao (:aircraft/icao fixtures/ups-2717)
        ring [(pos 27.0 -83.0) (pos 27.1 -83.1) (pos 27.2 -83.2)]
        coll (trails/history->trail-feature-collection {icao ring} #{icao})
        feat (first (:features coll))]
    (testing "a well-formed FeatureCollection with one LineString"
      (is (= "FeatureCollection" (:type coll)))
      (is (= 1 (count (:features coll))))
      (is (= "LineString" (get-in feat [:geometry :type]))))

    (testing "coordinates are [lon lat], oldest first — tail (progress 0) to
              head (progress 1)"
      (is (= [[-83.0 27.0] [-83.1 27.1] [-83.2 27.2]]
             (get-in feat [:geometry :coordinates]))))

    (testing "the feature carries its icao"
      (is (= icao (get-in feat [:properties :icao]))))))

(deftest trail-collection-omits-lone-points-and-non-live-aircraft
  (let [icao (:aircraft/icao fixtures/ups-2717)]
    (testing "a single-point ring is not a line — no feature"
      (is (empty? (:features
                    (trails/history->trail-feature-collection
                      {icao [(pos 27.0 -83.0)]} #{icao})))))

    (testing "an aircraft not in live-icaos (aged out / departed) leaves no
              trail, even with a multi-point ring"
      (is (empty? (:features
                    (trails/history->trail-feature-collection
                      {icao [(pos 27.0 -83.0) (pos 27.1 -83.1)]} #{})))))

    (testing "an empty history yields an empty, well-formed collection"
      (is (= {:type "FeatureCollection" :features []}
             (trails/history->trail-feature-collection {} #{icao}))))))
