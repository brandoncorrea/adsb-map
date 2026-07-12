(ns adsb.stats-test
  "Session statistics: the running max range grows and never un-sets, is
  absent without a receiver position, and the message rate differences the
  feeder's cumulative counter. The numbers only — never a position: the
  stats map carries a scalar km and a scalar rate, and the receiver
  position it measured from stays an argument."
  (:require
    [adsb.geo :as geo]
    [adsb.stats :as stats]
    [clojure.test :refer [deftest testing is]]))

(def ^:private receiver {:geo/lat 27.0 :geo/lon -82.0})

(defn- ac
  "A positioned domain aircraft at a lat/lon."
  [icao lat lon]
  {:aircraft/icao     icao
   :aircraft/position {:geo/lat lat :geo/lon lon}})

(def ^:private near  (ac "aaa111" 27.5 -82.0))
(def ^:private far   (ac "bbb222" 30.0 -82.0))
(def ^:private unplaced {:aircraft/icao "ccc333"})

(defn- picture [& aircraft]
  (into {} (map (juxt :aircraft/icao identity)) aircraft))

(defn- km [receiver-position aircraft]
  (Math/round (double (geo/meters->km
                        (geo/distance receiver-position
                                      (:aircraft/position aircraft))))))

;; ---------------------------------------------------------------------
;; Counts

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

;; ---------------------------------------------------------------------
;; Max range

(deftest max-range-grows-and-is-monotonic
  (testing "the running max climbs to the furthest aircraft and, once set,
            never falls back on a quieter tick"
    (let [acc      (stats/create)
          near-km  (km receiver near)
          far-km   (km receiver far)
          measure  (fn [pic] (:stats/max-range-km
                               (stats/compute! acc
                                               {:picture           pic
                                                :receiver-position receiver
                                                :now-ms            1000
                                                :messages          nil})))]
      (is (= near-km (measure (picture near))) "first max is the near plane")
      (is (< near-km far-km) "the far plane really is further")
      (is (= far-km (measure (picture near far))) "the record climbs")
      (is (= far-km (measure (picture near)))
          "and holds when the far plane goes quiet — monotonic, never reset")
      (is (= far-km (measure (picture unplaced)))
          "a tick with nothing positioned neither lowers nor clears it"))))

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
      (is (= 2 (:stats/positioned-count s))
          "the counts still come through — only the range needs a position")))

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
                                       :messages          nil})))
          "the running max stayed nil — nothing was ever measured"))))

;; ---------------------------------------------------------------------
;; Message rate

(deftest message-rate-differences-the-counter
  (testing "the rate is messages-per-second between samples; the first
            sample has nothing to difference, an idle tick reads zero"
    (let [acc (stats/create)
          rate (fn [messages now-ms]
                 (:stats/message-rate
                   (stats/compute! acc {:picture           {}
                                        :receiver-position nil
                                        :now-ms            now-ms
                                        :messages          messages})))]
      (is (nil? (rate 1000 1000)) "first sample: no baseline yet")
      (is (= 300 (rate 1300 2000)) "300 messages over one second")
      (is (= 0 (rate 1300 3000))
          "no new messages is a rate of zero — quiet, not unknown")
      (is (nil? (rate 500 4000))
          "a counter that went backwards (feeder restart) is unknown, not
           a negative rate"))))

(deftest message-rate-absent-without-a-counter
  (testing "a feeder that reports no cumulative count leaves the rate
            unknown, and never NPEs on a later missing count"
    (let [acc (stats/create)
          rate (fn [messages now-ms]
                 (:stats/message-rate
                   (stats/compute! acc {:picture           {}
                                        :receiver-position nil
                                        :now-ms            now-ms
                                        :messages          messages})))]
      (is (nil? (rate nil 1000)) "no counter, no rate")
      (is (nil? (rate nil 2000)) "still none, and no NPE from the gap")
      (is (nil? (rate 1000 3000))
          "the first real count sets a baseline — still nothing to
           difference against")
      (is (= 250 (rate 1500 5000))
          "and the next real count differences cleanly: 500 over 2 s"))))
