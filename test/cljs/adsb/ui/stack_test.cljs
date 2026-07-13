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
    [adsb.map.theme :as theme]                    ; the edition the key is printed in
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

(deftest the-axis-comes-from-the-ruler's-own-geometry
  (testing "a recumbent ruler (wider than tall) reads left-to-right — the
            phone stance, derived from the rect, not from a second copy of
            the media query"
    (let [lying-down {:left 0 :top 0 :width 200 :height 20}]
      (is (= 0 (stack/axis-pct lying-down 0 10)) "the left end is the surface")
      (is (= 50 (stack/axis-pct lying-down 100 10)) "the middle is mid-scale")
      (is (= 100 (stack/axis-pct lying-down 200 10)) "the right end is the ceiling")))
  (testing "a standing ruler reads bottom-to-top — the desktop stance"
    (let [standing {:left 0 :top 100 :width 20 :height 200}]
      (is (= 0 (stack/axis-pct standing 10 300)) "the foot is the surface")
      (is (= 50 (stack/axis-pct standing 10 200)) "the middle is mid-scale")
      (is (= 100 (stack/axis-pct standing 10 100)) "the top is the ceiling")))
  (testing "a finger that slides off the end of the ruler stays on the scale"
    (let [lying-down {:left 0 :top 0 :width 200 :height 20}]
      (is (= 0 (stack/axis-pct lying-down -40 10)) "past the surface clamps")
      (is (= 100 (stack/axis-pct lying-down 260 10)) "past the ceiling clamps")))
  (testing "a ruler with no area has no axis — an unstyled Stack must not
            scrub, and must not divide by zero doing it"
    (is (nil? (stack/axis-pct {:left 0 :top 0 :width 0 :height 0} 50 50)))))

(deftest the-scrub-lands-on-the-nearest-tick
  (let [ups-pct (stack/altitude-pct 34775)
        dal-pct (stack/altitude-pct 10025)
        roster  [ups fixtures/squawking-7700]]
    (testing "the finger names whichever aircraft it is closest to on the axis"
      (is (= ups-icao (:aircraft/icao (stack/nearest-tick roster ups-pct)))
          "dead on the cruising 747")
      (is (= "a35a92" (:aircraft/icao (stack/nearest-tick roster dal-pct)))
          "dead on the descending emergency")
      (is (= ups-icao (:aircraft/icao (stack/nearest-tick roster 100)))
          "at the ceiling, the highest aircraft is the nearest one")
      (is (= "a35a92" (:aircraft/icao (stack/nearest-tick roster 0)))
          "at the surface, the lowest is"))
    (testing "an empty sky has nothing to point at"
      (is (nil? (stack/nearest-tick [] 50))))))

(deftest the-scrub-can-only-land-on-the-ruler
  (testing "the shelves are not on the altitude axis, so a scrub cannot
            reach them — absent is not zero, and a finger cannot make it so"
    (let [picture (by-icao [ups fixtures/on-the-ground no-altitude])]
      (is (= [ups-icao] (map :aircraft/icao (stack/airborne picture)))
          "only the aircraft with a real altitude is scrubbable"))))

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

;; ---------------------------------------------------------------------
;; The shelves as chips, and the sheet that names their residents (adsb-hsk).

(defn- chip [band]
  (.getByTestId rtl/screen (str "shelf:" band)))

(defn- click-and-commit!
  "Click, and let the view catch up before we look at it.

  Every other DOM assertion in this file renders AFTER the state it checks,
  so it never had to. These do: the sheet opens BECAUSE of the click. Reagent
  queues its re-render on an animation frame and RTL 16 commits through a
  React 18 root, so neither has run by the time fireEvent returns —
  `reagent/flush` inside `act` runs both, synchronously, which is what keeps
  these assertions inside run-test-sync with the rest of the file."
  [element]
  (rtl/act (fn [] (.click rtl/fireEvent element) (r/flush))))

(deftest a-shelf-chip-counts-its-residents
  (testing "the chip carries the one fact a dot cluster could tell you — how
            many — so the phone can drop the dots and keep the fact"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture
                    (by-icao [ups fixtures/on-the-ground no-altitude])])
      (render-stack!)
      (is (= "GND1" (.-textContent (chip "ground")))
          "one aircraft on the tarmac...")
      (is (some? (.closest (tick-el "a1d645") ".adsb-stack-ground"))
          "...which is indeed the ground shelf's resident")
      (is (= "NO ALT1" (.-textContent (chip "unknown")))
          "and one holding apart, its altitude never reported"))))

(deftest a-shelf-chip-opens-a-sheet-that-names-its-residents
  (testing "tapping a chip answers the question the dots never could: WHO.
            The dots and the sheet are never both present — two nodes for one
            aircraft would be two answers to the same question"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups no-altitude])])
      (render-stack!)
      (is (nil? (.queryByText rtl/screen "NOALT"))
          "closed, the holding shelf's resident is an anonymous dot")
      (is (= "false" (.getAttribute (chip "unknown") "aria-expanded"))
          "and the chip says so")

      (click-and-commit! (chip "unknown"))
      (is (= :unknown @(rf/subscribe [:stack/open-shelf])) "the shelf opens")
      (is (some? (.getByText rtl/screen "NOALT"))
          "and its resident is named at last")
      (is (some? (.closest (tick-el "aaaaaa") ".adsb-stack-sheet"))
          "as a row in the sheet, not a dot in a cluster")
      (is (= "true" (.getAttribute (chip "unknown") "aria-expanded")))

      (click-and-commit! (chip "unknown"))
      (is (nil? @(rf/subscribe [:stack/open-shelf])) "and tapping again closes it")
      (is (nil? (.queryByText rtl/screen "NOALT"))
          "the sheet is gone, and its residents are dots again"))))

(deftest a-named-resident-is-still-selectable
  (testing "a sheet row fires the same [:aircraft/select icao] contract a
            tick does — the sheet names them, it does not sideline them"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [no-altitude])])
      (rf/dispatch [:stack/toggle-shelf :unknown])
      (render-stack!)
      (.click rtl/fireEvent (tick-el "aaaaaa"))
      (is (= "aaaaaa" @(rf/subscribe [:aircraft/selected-icao]))
          "the altitude-less aircraft selects like any other"))))

;; ---------------------------------------------------------------------
;; The Stack IS the map key now (adsb-sod) — the corner legend is deleted.

(defn- swatch-color [band]
  (.getAttribute (.getByTestId rtl/screen (str "swatch:" band)) "data-color"))

(defn- assert-key-matches-palette [theme]
  (let [{:keys [ground-color unknown-color emergency-color]} (style/palette theme)]
    (rf-test/run-test-sync
      (theme/set-theme! theme)
      (rf/dispatch [:test/set-picture
                    (by-icao [fixtures/on-the-ground no-altitude
                              fixtures/squawking-7700])])
      (render-stack!)
      (testing (str theme ": every swatch is the map's own palette entry")
        (is (= ground-color (swatch-color "ground"))
            "GND keys the colour the map paints a grounded aircraft")
        (is (= unknown-color (swatch-color "unknown"))
            "NO ALT keys the colour of an aircraft with no altitude")
        (is (= emergency-color (swatch-color "emergency"))
            "EMG keys the colour of a distress squawk")))))

(deftest the-key-cannot-drift-from-the-map-in-either-edition
  ;; This is the deleted legend's ONE real virtue, kept whole. It read
  ;; style/palette directly so a re-skin moved the key and the planes together;
  ;; had the key moved onto the Stack as CSS tokens mirroring the palette, that
  ;; guarantee would have died quietly with the box. The swatches are painted
  ;; from the palette, and this fails the moment they are not.
  (assert-key-matches-palette :day)
  (assert-key-matches-palette :night)
  (testing "and the two editions really are different inks, so this is a
            live constraint and not a tautology"
    (is (not= (:ground-color (style/palette :day))
              (:ground-color (style/palette :night)))))
  (theme/set-theme! :day))

(deftest the-emergency-key-exists-only-while-red-does
  (testing "a legend row explaining a colour that is nowhere on the chart
            spends the reader's attention on nothing. EMG is on screen exactly
            when an aircraft is squawking distress, and never otherwise"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/on-the-ground])])
      (render-stack!)
      (is (nil? (.queryByTestId rtl/screen "shelf:emergency"))
          "a calm sky keys no red, because there is no red to key")
      (is (some? (.queryByTestId rtl/screen "shelf:ground"))
          "while GND is permanent — its count is a fact even at zero"))

    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/squawking-7700])])
      (render-stack!)
      (is (some? (.queryByTestId rtl/screen "shelf:emergency"))
          "a distress squawk brings the key with it")
      (is (some? (.getByText rtl/screen "EMG"))
          "named in words, not colour alone"))))

;; ---------------------------------------------------------------------
;; The scrub (adsb-4et) — a finger, in a real browser.

(defn- ruler []
  (.querySelector js/document ".adsb-stack-ruler"))

(defn- lay-the-ruler-down!
  "Give the ruler a real recumbent rect. The browser suite renders without
  the stylesheet (adsb-giu), so an unstyled ruler measures 0x0 — and a ruler
  with no area has no axis. Inline geometry, then, and a REAL layout engine
  to measure it: 200px wide, 20px tall, pinned at the viewport origin, so a
  clientX of 160 is unambiguously 80% of the way to the ceiling."
  []
  (let [style (.-style (ruler))]
    (set! (.-position style) "fixed")
    (set! (.-left style) "0px")
    (set! (.-top style) "0px")
    (set! (.-width style) "200px")
    (set! (.-height style) "20px")))

(deftest scrubbing-the-ruler-names-the-nearest-aircraft
  (testing "drag along the ruler and each tick is named as the finger passes
            it — the identity channel a phone can actually reach, since a
            phone cannot hover and a 3px tick cannot be poked"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/squawking-7700])])
      (render-stack!)
      (lay-the-ruler-down!)
      (is (nil? @(rf/subscribe [:aircraft/hovered-icao])) "nothing named yet")

      (.pointerDown rtl/fireEvent (ruler) #js {:clientX 160 :clientY 10})
      (is (= ups-icao @(rf/subscribe [:aircraft/hovered-icao]))
          "a press at 80% of the axis names the 747 cruising at FL347")

      (.pointerMove rtl/fireEvent (ruler) #js {:clientX 40 :clientY 10})
      (is (= "a35a92" @(rf/subscribe [:aircraft/hovered-icao]))
          "and sliding down to 20% names the emergency descending through FL100"))))

(deftest letting-go-of-a-scrub-selects-what-it-named
  (testing "release fires the map's [:aircraft/select icao] contract"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/squawking-7700])])
      (render-stack!)
      (lay-the-ruler-down!)
      (.pointerDown rtl/fireEvent (ruler) #js {:clientX 160 :clientY 10})
      (.pointerUp rtl/fireEvent (ruler) #js {:clientX 160 :clientY 10})
      (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao]))
          "the aircraft the finger left named is the one selected"))))

(deftest an-idle-pointer-over-the-ruler-does-not-scrub
  (testing "the scrub is a PRESS. An unpressed mouse crossing the desktop
            ruler must not hijack the hover — that is what the ticks' own
            hover is for"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/squawking-7700])])
      (render-stack!)
      (lay-the-ruler-down!)
      (.pointerMove rtl/fireEvent (ruler) #js {:clientX 160 :clientY 10})
      (is (nil? @(rf/subscribe [:aircraft/hovered-icao]))
          "moving without pressing names nothing"))))

(deftest a-cancelled-scrub-chooses-nothing
  (testing "the gesture taken away (a system swipe, a call) selects nobody"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups])])
      (render-stack!)
      (lay-the-ruler-down!)
      (.pointerDown rtl/fireEvent (ruler) #js {:clientX 160 :clientY 10})
      (.pointerCancel rtl/fireEvent (ruler) #js {:clientX 160 :clientY 10})
      (.pointerUp rtl/fireEvent (ruler) #js {:clientX 160 :clientY 10})
      (is (nil? @(rf/subscribe [:aircraft/selected-icao]))
          "an interrupted scrub is not a choice"))))

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
