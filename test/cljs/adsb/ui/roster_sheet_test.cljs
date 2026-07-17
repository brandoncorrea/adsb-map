(ns adsb.ui.roster-sheet-test
  (:require [adsb.ui.roster-sheet :as sheet]
            [clojure.test :refer-macros [deftest is testing]]))

(deftest sheet-snap-math
  (testing "nearest snap by fraction"
    (is (= :closed (sheet/height-fraction->sheet 0.0 0)))
    (is (= :half (sheet/height-fraction->sheet 0.5 0)))
    (is (= :full (sheet/height-fraction->sheet 0.9 0))))
  (testing "velocity commits past the nearest rung"
    (is (= :full (sheet/height-fraction->sheet 0.55 (+ sheet/drag-velocity-threshold 0.01))))
    (is (= :closed (sheet/height-fraction->sheet 0.40 (- (+ sheet/drag-velocity-threshold 0.01))))))
  (testing "tap cycles closed → half → full → closed"
    (is (= :half (sheet/next-sheet :closed)))
    (is (= :full (sheet/next-sheet :half)))
    (is (= :closed (sheet/next-sheet :full))))
  (testing "open? is half or full"
    (is (false? (sheet/sheet-open? :closed)))
    (is (true? (sheet/sheet-open? :half)))
    (is (true? (sheet/sheet-open? :full))))
  (testing "snap heights are ordered closed < half < full"
    (let [c (sheet/sheet-height-px :closed)
          h (sheet/sheet-height-px :half)
          f (sheet/sheet-height-px :full)]
      (is (< c h f))
      (is (pos? c))))
  (testing "ease-out-cubic is identity at ends and soft in the middle"
    (is (= 0.0 (sheet/ease-out-cubic 0)))
    (is (= 1.0 (sheet/ease-out-cubic 1)))
    (is (< 0.5 (sheet/ease-out-cubic 0.5) 1.0))))
