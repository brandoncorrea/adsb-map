(ns adsb.ui.units-test
  "How the instrument prints a number. Pure — no DOM, no fixtures."
  (:require
    [adsb.ui.units :as units]
    [cljs.test :refer-macros [deftest is testing]]))

(deftest ground-speed-is-whole-knots
  (testing "the feeder's fractions are rounded away, not truncated"
    (is (= "451" (units/knots 450.5)))
    (is (= "450" (units/knots 450.4)))
    (is (= "451" (units/knots 450.9)))
    (is (= "412" (units/knots 412))))
  (testing "a reported zero is a fact and prints; absence is not"
    (is (= "0" (units/knots 0)) "a stationary aircraft is doing 0 kt")
    (is (nil? (units/knots nil)) "unreported — the surface dashes it, we do not
                                  invent a 0 and park a 747 on the runway")))

(deftest track-is-a-three-digit-bearing
  (testing "aviation writes bearings in three digits"
    (is (= "097°" (units/track 97.14)))
    (is (= "007°" (units/track 7)))
    (is (= "000°" (units/track 0)))
    (is (= "359°" (units/track 359.4))))
  (testing "rounding happens BEFORE the compass wraps, or north prints as 360"
    (is (= "000°" (units/track 359.7)) "359.7 rounds to 360, and 360 is 000")
    (is (= "000°" (units/track 360)))
    (is (= "001°" (units/track 360.5))))
  (testing "a feeder that reports a negative bearing still lands on the compass"
    (is (= "355°" (units/track -5)))
    (is (nil? (units/track nil)))))
