(ns adsb.ui.legend-test
  "The map key, rendered in a real browser. The one assertion that matters:
  every swatch's colour EQUALS the adsb.map.style constant it is meant to
  explain, proving the legend cannot drift from the ramp. The swatch carries
  its colour verbatim on `data-color` (un-normalised, unlike a computed CSS
  colour), so the comparison against the imported constant is exact — change
  the constant and this test moves with it; hard-code a different hex in the
  legend and this test fails."
  (:require
    ["@testing-library/react" :as rtl]
    [adsb.map.style :as style]
    [adsb.ui.legend :as legend]
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [reagent.core :as r]))

(use-fixtures :each {:after rtl/cleanup})

(defn- swatch [testid]
  (.getByTestId rtl/screen testid))

(defn- swatch-color [testid]
  (.getAttribute (swatch testid) "data-color"))

(deftest legend-swatches-equal-the-style-constants
  (rtl/render (r/as-element [legend/legend]))
  (testing "the altitude ramp is rendered straight from style/altitude-stops"
    (doseq [[feet color] style/altitude-stops]
      (is (= color (swatch-color (str "legend-alt:" feet)))
          (str feet " ft swatch is the ramp colour, not a duplicated literal"))))
  (testing "each special state reads its named style constant"
    (is (= style/ground-color    (swatch-color "legend-ground")))
    (is (= style/unknown-color   (swatch-color "legend-unknown")))
    (is (= style/emergency-color (swatch-color "legend-emergency"))))
  (testing "the stale swatch renders the real stale-opacity, not an invented fade"
    (is (= style/unknown-color (swatch-color "legend-stale")))
    (is (= style/stale-opacity
           (js/parseFloat (.getAttribute (swatch "legend-stale") "data-opacity"))))))
