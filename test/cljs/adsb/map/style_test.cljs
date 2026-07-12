(ns adsb.map.style-test
  "The aircraft styling is DATA, so the tests read it as data — no map,
  no DOM, no browser needed to prove the contract. We assert the SHAPE of
  the MapLibre expressions: the layer is a symbol layer rotated by track;
  the altitude colour peels its three states (number / \"ground\" /
  absent) apart correctly; emergency overrides colour and size; stale
  fades opacity. When the visual pass (adsb-dgb.5) re-skins by editing the
  constants, these structural assertions stay green — they check the
  wiring, not the palette."
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.map.style :as style]
    [cljs.test :refer-macros [deftest is testing]]))

(deftest layer-is-a-symbol-rotated-by-track
  (let [spec (style/aircraft-layer-spec "aircraft" "aircraft")]
    (is (= "symbol" (:type spec)) "a symbol layer — a plane, not a circle")
    (testing "the icon rotates with the reported track, pinned to the ground"
      (is (= ["get" "track"] (get-in spec [:layout :icon-rotate])))
      (is (= "map" (get-in spec [:layout :icon-rotation-alignment]))))
    (testing "track-less aircraft fall back to a non-directional dot"
      (is (= ["case" ["has" "track"] style/plane-icon-id style/dot-icon-id]
             (get-in spec [:layout :icon-image]))))
    (testing "no aircraft is dropped by label collision"
      (is (true? (get-in spec [:layout :icon-allow-overlap]))))))

(deftest altitude-colour-handles-its-three-states
  (let [expr (style/altitude-color-expression)]
    (is (= "case" (first expr)) "a case, because interpolate is numeric-only")
    (testing "absent altitude -> unknown treatment, guarded by `has` (missing, not zero)"
      (is (= ["!" ["has" "altitude"]] (nth expr 1)))
      (is (= style/unknown-color (nth expr 2))))
    (testing "the string \"ground\" -> its own ground treatment"
      (is (= ["==" ["get" "altitude"] "ground"] (nth expr 3)))
      (is (= style/ground-color (nth expr 4))))
    (testing "the numeric branch is a linear interpolate over the altitude number"
      (let [ramp (nth expr 5)]
        (is (= "interpolate" (first ramp)))
        (is (= ["linear"] (nth ramp 1)))
        (is (= ["get" "altitude"] (nth ramp 2)))
        (let [stops (drop 3 ramp)]
          (is (even? (count stops)) "stop/colour pairs")
          (is (= (map first style/altitude-stops)
                 (take-nth 2 stops))
              "the numeric stops come straight from the re-skinnable ramp data"))))))

(deftest emergency-overrides-colour-and-size
  (testing "colour: emergency red beats the altitude ramp"
    (let [expr (style/icon-color-expression)]
      (is (= "case" (first expr)))
      (is (= ["get" "emergency"] (nth expr 1)))
      (is (= style/emergency-color (nth expr 2)))
      (is (= (style/altitude-color-expression) (nth expr 3))
          "otherwise the three-state altitude ramp")))
  (testing "size: emergency wins first, then mlat is demoted, else base"
    (let [expr (style/icon-size-expression)]
      (is (= ["case"
              ["get" "emergency"] style/emergency-icon-size
              ["get" "mlat"]      style/mlat-icon-size
              style/base-icon-size]
             expr))
      (is (> style/emergency-icon-size style/base-icon-size)
          "emergency is unmissably large")
      (is (< style/mlat-icon-size style/base-icon-size)
          "an mlat fix reads a touch smaller — lower confidence"))))

(deftest age-fades-opacity-continuously
  (testing "opacity interpolates over age-s, guarded for the absent case"
    (let [expr (style/icon-opacity-expression)]
      (is (= "case" (first expr)))
      (testing "an un-judged aircraft (no age-s) stays full opacity"
        (is (= ["has" "age-s"] (nth expr 1)))
        (is (= style/base-opacity (nth expr 3))))
      (testing "the fade is a linear interpolate from the stale line to the
                age-out line — not a single binary step"
        (let [ramp (nth expr 2)]
          (is (= "interpolate" (first ramp)))
          (is (= ["linear"] (nth ramp 1)))
          (is (= ["get" "age-s"] (nth ramp 2)))
          (is (= [style/stale-threshold-s   style/base-opacity
                  style/age-out-threshold-s style/aged-out-opacity]
                 (drop 3 ramp))
              "full at the stale line, nearly gone at the age-out line")))))

  (testing "the fade bounds are the domain thresholds, in seconds — the two
            sides can never disagree about where the fade begins or ends"
    (is (= (/ aircraft/stale-threshold-ms 1000) style/stale-threshold-s))
    (is (= (/ aircraft/age-out-threshold-ms 1000) style/age-out-threshold-s))
    (is (< style/aged-out-opacity style/base-opacity)
        "aged reads dimmer, not gone")))
