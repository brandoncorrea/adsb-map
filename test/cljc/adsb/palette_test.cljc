(ns adsb.palette-test
  (:require [adsb.palette :as palette]
            [clojure.test :refer [deftest is testing]]))

;; The palette is the single source for every theme colour, and the style/
;; basemap/tokens tests all read THROUGH it — so without this pin no test
;; would notice a hex silently changing. A re-ink is supposed to touch two
;; files: the palette and this test, deliberately.

(deftest the-swatches-are-pinned
  (testing "every role's day and night inks are exactly the published
            editions — a drifted hex fails here, deliberately, so a re-ink
            is a conscious two-file change"
    (is (= {:paper        {:day "#E2E8DE" :night "#151B26"}
            :paper-chrome {:day "#ECF1E8" :night "#1B2330"}
            :ink          {:day "#1B2A1D" :night "#E9E2CE"}
            :faded-ink    {:day "#506049" :night "#8D96A8"}
            :contour      {:day "#A6BF9E" :night "#2E3A49"}
            :terrain-1    {:day "#C2D7B4" :night "#1D2634"}
            :terrain-2    {:day "#A2C193" :night "#232E40"}
            :water-fill   {:day "#A6C7BE" :night "#101823"}
            :water-line   {:day "#2A6358" :night "#7FA3D4"}
            :road         {:day "#5C6E56" :night "#6B5540"}
            :aeroway      {:day "#CFDFC4" :night "#1D2634"}
            :magenta      {:day "#A5385C" :night "#E77E9B"}
            :aero         {:day "#2A6358" :night "#8BA9D6"}
            :emergency    {:day "#CE2029" :night "#FF5A4D"}
            :on-emergency {:day "#FBF3E4" :night "#1C1210"}
            :ok           {:day "#55722F" :night "#8FBF6F"}
            :warn         {:day "#8F6318" :night "#D9A648"}
            :alt-ground   {:day "#8A8374" :night "#6E7686"}
            :alt-unknown  {:day "#9A937F" :night "#7C8494"}}
           palette/swatches))))

(deftest the-altitude-ramp-is-pinned
  (testing "the five ramp stops per edition — feet shared, inks per paper"
    (is (= {:day   [[0 "#A0622D"] [10000 "#C2447C"] [20000 "#7A4F86"]
                    [30000 "#3D5E8C"] [40000 "#2A3F66"]]
            :night [[0 "#C98A54"] [10000 "#E06A9F"] [20000 "#A98BC4"]
                    [30000 "#7FA3D4"] [40000 "#5F7FB8"]]}
           palette/altitude-ramp))))

(deftest the-accessors-derive-from-the-swatches
  (testing "swatch reads a role's hex for an edition"
    (is (= "#E2E8DE" (palette/swatch :day :paper)))
    (is (= "#151B26" (palette/swatch :night :paper))))

  (testing "rgb and rgba are string arithmetic over the same hex, so the
            derived forms can never drift from the swatch"
    (is (= "27, 42, 29" (palette/rgb :day :ink)))
    (is (= "rgba(27, 42, 29, 0.3)" (palette/rgba :day :ink 0.3)))))
