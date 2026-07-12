(ns adsb.ui.legend-test
  "The map key, rendered in a real browser. The one assertion that matters:
  every swatch's colour EQUALS the adsb.map.style palette entry it is meant
  to explain — in BOTH editions — proving the legend cannot drift from the
  ramp. The swatch carries its colour verbatim on `data-color`
  (un-normalised, unlike a computed CSS colour), so the comparison against
  the imported palette is exact: change the palette and this test moves
  with it; hard-code a different hex in the legend and this test fails.
  The edition comes from adsb.map.theme/!theme, so flipping the ratom must
  re-ink every swatch — that is the theme-follows proof."
  (:require
    ["@testing-library/react" :as rtl]
    [adsb.map.style :as style]
    [adsb.map.theme :as theme]
    [adsb.ui.legend :as legend]
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [reagent.core :as r]))

(use-fixtures :each
  {:after (fn []
            (rtl/cleanup)
            (theme/set-theme! :day))})

(defn- swatch [testid]
  (.getByTestId rtl/screen testid))

(defn- swatch-color [testid]
  (.getAttribute (swatch testid) "data-color"))

(defn- assert-legend-matches-palette [theme]
  (let [{:keys [altitude-stops ground-color unknown-color emergency-color]}
        (style/palette theme)]
    (testing (str theme ": the altitude ramp is rendered straight from the palette")
      (doseq [[feet color] altitude-stops]
        (is (= color (swatch-color (str "legend-alt:" feet)))
            (str feet " ft swatch is the ramp colour, not a duplicated literal"))))
    (testing (str theme ": each special state reads its named palette entry")
      (is (= ground-color    (swatch-color "legend-ground")))
      (is (= unknown-color   (swatch-color "legend-unknown")))
      (is (= emergency-color (swatch-color "legend-emergency"))))
    (testing (str theme ": the stale swatch renders the real stale-opacity")
      (is (= unknown-color (swatch-color "legend-stale")))
      (is (= style/stale-opacity
             (js/parseFloat (.getAttribute (swatch "legend-stale")
                                           "data-opacity")))))))

(deftest legend-swatches-equal-the-day-palette
  (theme/set-theme! :day)
  (rtl/render (r/as-element [legend/legend]))
  (assert-legend-matches-palette :day))

(deftest legend-follows-the-theme-to-the-night-edition
  (theme/set-theme! :night)
  (rtl/render (r/as-element [legend/legend]))
  (assert-legend-matches-palette :night)
  (testing "night ink really is different ink"
    (is (not= (:ground-color (style/palette :day))
              (swatch-color "legend-ground")))))
