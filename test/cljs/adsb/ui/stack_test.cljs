(ns adsb.ui.stack-test
  "The Stack — the live altitude ruler that replaced the sidebar — rendered
  in a real browser under React Testing Library. Proves the tick math places
  aircraft at their true proportional altitude (a pure function, asserted as
  arithmetic), the ruler's fill derives from the SAME adsb.map.style ramp
  the map paints with, ground targets cluster in the surface shelf, an
  absent altitude holds in its own shelf (never drawn as zero), a tick
  click fires the map's [:aircraft/select icao] contract, hover publishes
  :aircraft/hovered-icao, and the selected and emergency ticks are marked."
  (:require
    ["@testing-library/react" :as rtl]
    [adsb.events]
    [adsb.fixtures :as fixtures]
    [adsb.map.style :as style]
    [adsb.stream]                                 ; registers :aircraft/picture
    [adsb.subs]                                   ; registers selection + hover
    [adsb.ui.stack :as stack]
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

;; Without cleanup a previous test's Stack stays mounted and the queries
;; below find two matches — or the wrong one.
(use-fixtures :each {:after rtl/cleanup})

;; Seed the picture directly: its owning event lives in adsb.stream and
;; speaks wire JSON, noise for a chrome test. A tiny local event stands up
;; the exact app-db the Stack reads.
(rf/reg-event-db :test/set-picture
  (fn [db [_ picture]] (assoc db :aircraft/picture picture)))

(defn- by-icao
  "A picture map keyed by each aircraft's icao — the shape
  :aircraft/picture holds."
  [aircraft]
  (into {} (map (juxt :aircraft/icao identity)) aircraft))

(defn- render-stack! []
  (rtl/cleanup)
  (rtl/render (r/as-element [stack/stack])))

(defn- tick-el [icao]
  (.getByTestId rtl/screen (str "tick:" icao)))

(defn- alt-pct-of
  "The --alt-pct custom property carried inline by a tick, as a string, or
  the empty string when the tick is unplaced (a shelf resident)."
  [icao]
  (.getPropertyValue (.-style (tick-el icao)) "--alt-pct"))

(def ^:private ups fixtures/ups-2717)
(def ^:private ups-icao (:aircraft/icao ups))

(def ^:private no-altitude
  "A positioned aircraft whose altitude the sky never reported — derived
  from the cruising cast member, as the sidebar tests did before it."
  (-> ups
      (dissoc :aircraft/altitude-ft :aircraft/on-ground?)
      (assoc :aircraft/icao "aaaaaa" :aircraft/callsign "NOALT")))

;; ---------------------------------------------------------------------
;; The tick math — pure functions, asserted as arithmetic.

(deftest altitude-pct-is-proportional-and-clamped
  (testing "feet map linearly onto the 0-100 altitude axis"
    (is (= 0 (stack/altitude-pct 0)) "the surface is the axis origin")
    (is (= 100 (stack/altitude-pct stack/ceiling-ft)) "the ceiling is the top")
    (is (= (* 100 (/ 34775 stack/ceiling-ft)) (stack/altitude-pct 34775))
        "the cruising 747 sits at its exact proportional height")
    (is (= (* 100 (/ 10025 stack/ceiling-ft)) (stack/altitude-pct 10025))
        "and so does the descending emergency"))
  (testing "altitudes beyond the ruler clamp instead of escaping it"
    (is (= 100 (stack/altitude-pct 60000)) "above the ceiling pins to the top")
    (is (= 0 (stack/altitude-pct -500)) "below the surface pins to the foot")))

(deftest tick-band-is-three-state
  (testing "ground, a real number, and absent are three different places"
    (is (= :ground (stack/tick-band fixtures/on-the-ground))
        "on the tarmac clusters at the surface band")
    (is (= :airborne (stack/tick-band ups))
        "a reported altitude goes on the ruler")
    (is (= :unknown (stack/tick-band no-altitude))
        "a never-reported altitude holds apart — absent is not zero")))

(deftest the-fill-is-the-map's-ramp
  (testing "the ruler's gradient is built from the edition's own
            adsb.map.style altitude stops, each at its proportional place —
            the fill IS the legend and cannot drift from the planes, in
            either edition"
    (doseq [theme (keys style/palettes)
            :let [gradient (stack/ruler-gradient-css theme)]
            [feet color] (:altitude-stops (style/palette theme))]
      (is (.includes gradient (str color " " (stack/altitude-pct feet) "%"))
          (str (name theme) " edition: " feet " ft stop carries the map's own "
               "colour " color)))))

;; ---------------------------------------------------------------------
;; Rendering — the bands, in a real browser.

(deftest ticks-render-at-their-true-proportional-heights
  (testing "every airborne aircraft is a tick carrying its exact --alt-pct"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture
                    (by-icao [ups fixtures/squawking-7700])])
      (render-stack!)
      (is (= (str (stack/altitude-pct 34775)) (alt-pct-of ups-icao))
          "the cruising 747 at 34775 ft")
      (is (= (str (stack/altitude-pct 10025)) (alt-pct-of "a35a92"))
          "the emergency descending through 10025 ft"))))

(deftest an-altitude-bearing-but-unpositioned-aircraft-still-ticks
  (testing "heard-never-located still carries an altitude, so it belongs on
            the ruler — the Stack is a profile view, not a plan view"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [fixtures/never-positioned])])
      (render-stack!)
      (is (= (str (stack/altitude-pct 33000)) (alt-pct-of "a10202"))
          "placed at its true 33000 ft despite having no map feature"))))

(deftest ground-targets-cluster-in-the-surface-shelf
  (testing "an on-ground aircraft is a shelf resident, not a ruler tick"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/on-the-ground])])
      (render-stack!)
      (is (some? (.closest (tick-el "a1d645") ".adsb-stack-ground"))
          "the grounded aircraft sits in the ground shelf")
      (is (= "" (alt-pct-of "a1d645"))
          "and is placed nowhere on the altitude axis")
      (is (nil? (.closest (tick-el ups-icao) ".adsb-stack-shelf"))
          "while the cruising aircraft stays on the ruler"))))

(deftest absent-altitude-holds-apart-never-at-zero
  (testing "a never-reported altitude gets the holding shelf — it is not
            drawn as a phantom sea-level aircraft"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [no-altitude])])
      (render-stack!)
      (is (some? (.closest (tick-el "aaaaaa") ".adsb-stack-unknown"))
          "the altitude-less aircraft holds in the unknown shelf")
      (is (= "" (alt-pct-of "aaaaaa"))
          "carrying no place on the altitude axis at all"))))

(deftest the-graduations-name-the-flight-levels
  (testing "the scale reads surface to FL400"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture {}])
      (render-stack!)
      (doseq [label ["SFC" "FL100" "FL200" "FL300" "FL400"]]
        (is (some? (.getByText rtl/screen label)) (str label " is drawn"))))))

;; ---------------------------------------------------------------------
;; Interaction.

(deftest clicking-a-tick-selects-that-aircraft
  (testing "a tick click fires the map's [:aircraft/select icao] contract"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/on-the-ground])])
      (render-stack!)
      (is (nil? @(rf/subscribe [:aircraft/selected-icao])) "nothing selected yet")
      (.click rtl/fireEvent (tick-el ups-icao))
      (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao]))
          "the Stack selects the same identity the map would"))))

(deftest hovering-a-tick-publishes-the-hover-identity
  (testing "pointer on a tick sets :aircraft/hovered-icao; pointer off
            clears it — the key the map layer will light aircraft from"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups])])
      (render-stack!)
      (is (nil? @(rf/subscribe [:aircraft/hovered-icao])) "nothing hovered yet")
      (.mouseOver rtl/fireEvent (tick-el ups-icao))
      (is (= ups-icao @(rf/subscribe [:aircraft/hovered-icao]))
          "hover publishes the aircraft's identity app-wide")
      (.mouseOut rtl/fireEvent (tick-el ups-icao))
      (is (nil? @(rf/subscribe [:aircraft/hovered-icao]))
          "and leaving clears it"))))

(deftest the-hovered-tick-is-named
  (testing "hover names the aircraft beside its tick — identity lives on
            the Stack, the map stays glyphs-only"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups])])
      (rf/dispatch [:aircraft/hover ups-icao])
      (render-stack!)
      (is (some? (.getByText rtl/screen "UPS2717"))
          "the hovered tick shows its callsign"))))

(deftest the-selected-tick-is-marked-and-named
  (testing "the selected aircraft's tick is visually distinct and labelled"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/on-the-ground])])
      (rf/dispatch [:aircraft/select ups-icao])
      (render-stack!)
      (let [selected-tick (tick-el ups-icao)
            other-tick    (tick-el "a1d645")]
        (is (= "true" (.getAttribute selected-tick "aria-selected"))
            "the selected tick is marked")
        (is (.contains (.-classList selected-tick) "adsb-stack-tick-selected")
            "and carries the selected treatment")
        (is (= "false" (.getAttribute other-tick "aria-selected"))
            "and no other tick is")
        (is (some? (.getByText rtl/screen "UPS2717"))
            "the selection is named")))))

(deftest the-emergency-tick-screams-in-ink
  (testing "a distress squawk's tick is marked and permanently named —
            unmissable, and nothing blinks"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/squawking-7700])])
      (render-stack!)
      (is (.contains (.-classList (tick-el "a35a92")) "adsb-stack-tick-emergency")
          "the 7700 tick carries the emergency treatment")
      (is (some? (.getByText rtl/screen "DAL1275"))
          "and is named without any hover")
      (is (nil? (.queryByText rtl/screen "UPS2717"))
          "while an ordinary tick stays a quiet, unnamed mark"))))
