(ns adsb.stats-test
  (:require [adsb.geo :as geo]
            [adsb.stats :as stats]
            [clojure.math :as math]
            [clojure.test :refer [deftest is testing]]))

(def ^:private receiver {:geo/lat 27.0 :geo/lon -82.0})

(defn- ac [icao lat lon]
  {:aircraft/icao     icao
   :aircraft/position {:geo/lat lat :geo/lon lon}})

(def ^:private near (ac "aaa111" 27.5 -82.0))
(def ^:private far (ac "bbb222" 30.0 -82.0))
(def ^:private unplaced {:aircraft/icao "ccc333"})

(defn- picture [& aircraft]
  (->> aircraft
       (map (juxt :aircraft/icao identity))
       (into {})))

(defn- km [receiver-position aircraft]
  (-> (geo/distance receiver-position (:aircraft/position aircraft))
      geo/meters->km
      double
      math/round))

(deftest counts
  (testing "aircraft-count is the whole picture; positioned-count only the
            placed"
    (let [s (stats/compute! (stats/create)
                            {:picture           (picture near far unplaced)
                             :receiver-position receiver
                             :now-ms            1000
                             :messages          nil})]
      (is (= 3 (:stats/aircraft-count s)))
      (is (= 2 (:stats/positioned-count s))))))

(deftest max-range-grows-and-is-monotonic
  (testing "the running max climbs to the furthest aircraft and, once set,
            never falls back on a quieter tick"
    (let [acc     (stats/create)
          near-km (km receiver near)
          far-km  (km receiver far)
          measure (fn [pic] (:stats/max-range-km
                              (stats/compute! acc
                                              {:picture           pic
                                               :receiver-position receiver
                                               :now-ms            1000
                                               :messages          nil})))]
      (is (= near-km (measure (picture near))))
      (is (< near-km far-km))
      (is (= far-km (measure (picture near far))))
      (is (= far-km (measure (picture near))))
      (is (= far-km (measure (picture unplaced)))))))

(deftest max-range-is-whole-km
  (testing "the max range is rounded to a whole number of km — a scalar"
    (let [s (stats/compute! (stats/create)
                            {:picture           (picture far)
                             :receiver-position receiver
                             :now-ms            1000
                             :messages          nil})]
      (is (integer? (:stats/max-range-km s))))))

(deftest max-range-absent-without-receiver-position
  (testing "no receiver position means no reference to measure from, so
            the max range is absent — never a fabricated zero"
    (let [s (stats/compute! (stats/create)
                            {:picture           (picture near far)
                             :receiver-position nil
                             :now-ms            1000
                             :messages          nil})]
      (is (nil? (:stats/max-range-km s)))
      (is (= 2 (:stats/positioned-count s)))))

  (testing "even a positioned sky yields no max until a receiver appears"
    (let [acc (stats/create)]
      (is (nil? (:stats/max-range-km
                  (stats/compute! acc {:picture           (picture near)
                                       :receiver-position nil
                                       :now-ms            1000
                                       :messages          nil}))))
      (is (nil? (:stats/max-range-km
                  (stats/compute! acc {:picture           (picture near)
                                       :receiver-position nil
                                       :now-ms            2000
                                       :messages          nil})))))))

(deftest message-rate-differences-the-counter
  (testing "the rate is messages-per-second between samples; the first
            sample has nothing to difference, an idle tick reads zero"
    (let [acc  (stats/create)
          rate (fn [messages now-ms]
                 (:stats/message-rate
                   (stats/compute! acc {:picture           {}
                                        :receiver-position nil
                                        :now-ms            now-ms
                                        :messages          messages})))]
      (is (nil? (rate 1000 1000)))
      (is (= 300 (rate 1300 2000)))
      (is (= 0 (rate 1300 3000)))
      (is (nil? (rate 500 4000))))))

(deftest message-rate-absent-without-a-counter
  (testing "a feeder that reports no cumulative count leaves the rate
            unknown, and never NPEs on a later missing count"
    (let [acc  (stats/create)
          rate (fn [messages now-ms]
                 (:stats/message-rate
                   (stats/compute! acc {:picture           {}
                                        :receiver-position nil
                                        :now-ms            now-ms
                                        :messages          messages})))]
      (is (nil? (rate nil 1000)))
      (is (nil? (rate nil 2000)))
      (is (nil? (rate 1000 3000)))
      (is (= 250 (rate 1500 5000))))))
