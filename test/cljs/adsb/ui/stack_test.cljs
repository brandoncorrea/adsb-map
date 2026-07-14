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

(defn- drawer-row
  "The drawer's row for an aircraft, or nil. Its own testid: a drawer row is not
  always the aircraft's only node — one heard with an altitude but no position
  is a tick on the ruler AND a row in the traffic drawer, which are two true
  facts about it, not one fact twice."
  [icao]
  (.queryByTestId rtl/screen (str "drawer-tick:" icao)))

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

;; ---------------------------------------------------------------------
;; The window — zoom, as arithmetic.

(deftest the-window-is-read-from-the-scroller
  ;; THE SCROLL POSITION IS THE TRUTH now. The window is a snapshot of what the
  ;; viewport is showing, and two things render from it — the overflow counts and
  ;; the graduation step — so it may never lie about what the reader can see.
  (testing "an unscrolled ruler at zoom 1 is the whole sky"
    (is (= stack/full-window
           (stack/scroll->window {:scroll 0 :viewport 372 :track 372 :horizontal? true}))))

  (testing "scrolled halfway into a 4x track, the viewport shows the third quarter"
    (let [w (stack/scroll->window {:scroll 744 :viewport 372 :track 1488 :horizontal? true})]
      (is (= 22500 (:min-ft w)) "half of the sky is behind us")
      (is (= 33750 (:max-ft w)) "and a quarter of it is in view")))

  (testing "THE VERTICAL RULER IS INVERTED, and getting this backwards would put
            the overflow counts on the wrong ends — the one thing they exist to
            get right. Altitude climbs UPWARD; scrollTop counts DOWNWARD from the
            track's head, so scrollTop 0 is the CEILING"
    (let [w (stack/scroll->window {:scroll 0 :viewport 200 :track 800 :horizontal? false})]
      (is (= stack/ceiling-ft (:max-ft w)) "unscrolled, you are looking at the ceiling")
      (is (= 33750 (:min-ft w)) "and down as far as the viewport reaches")))

  (testing "a ruler with no track has no window — an unstyled Stack (the browser
            suite renders without the stylesheet) must not invent one"
    (is (= stack/full-window
           (stack/scroll->window {:scroll 0 :viewport 0 :track 0 :horizontal? true})))))

(deftest zooming-keeps-the-sky-under-the-fingers
  (testing "the track grows about the point being pinched, and the scroll is
            corrected so the sky there does not move — the whole feel of a
            timeline zoom is that the thing you are pointing at stays put"
    ;; viewport 400, track 400 (zoom 1), finger at the middle (offset 200).
    ;; Double the zoom: the track becomes 800, and the sky at the finger — which
    ;; was halfway along the track — must still sit under offset 200.
    (let [scroll' (stack/zoom-scroll {:scroll 0 :viewport 400 :track 400} 800 200)]
      (is (= 200 scroll') "scrolled by exactly the amount that holds it")
      (is (= 0.5 (/ (+ scroll' 200) 800)) "still halfway along the track")))

  (testing "and it never asks the browser for sky that is not there"
    (is (= 0 (stack/zoom-scroll {:scroll 0 :viewport 400 :track 800} 400 0))
        "zooming back out to a track the size of the viewport scrolls to the head")
    (is (>= (stack/zoom-scroll {:scroll 700 :viewport 400 :track 800} 800 399) 0)
        "and never past it")))

(deftest the-zoom-has-a-floor-and-a-ceiling
  (testing "1 is the whole sky, and there is nothing wider"
    (is (= 1 (stack/clamp-zoom 0.5)))
    (is (= 1 (stack/clamp-zoom 1))))
  (testing "and it may not close past 500ft of sky across the whole viewport, far
            past the point where two aircraft could still share a pixel"
    (is (= stack/max-zoom (stack/clamp-zoom 1e6)))
    (is (<= (/ stack/ceiling-ft stack/max-zoom) 500))))

(deftest a-pointer-is-placed-on-the-whole-sky-not-the-window
  (testing "the scrub thinks in FULL-SCALE space, because the track does: a tick
            is at its altitude whether the reader has scrolled it into view or
            not, and track-pct folds the scroll offset in"
    (let [m {:scroll 0 :viewport 372 :track 372 :horizontal? true}]
      (is (= 0 (stack/track-pct m 0)))
      (is (= 50 (stack/track-pct m 186)))
      (is (= 100 (stack/track-pct m 372))))

    (testing "scrolled into a 4x track, the same finger lands somewhere else
              entirely — which is the whole job of this function"
      (let [m {:scroll 744 :viewport 372 :track 1488 :horizontal? true}]
        (is (= 50 (stack/track-pct m 0)) "the left edge is now mid-sky")
        (is (= 75 (stack/track-pct m 372)) "and the right edge three-quarters up")))

    (testing "and it says nothing at all about a ruler with no track"
      (is (nil? (stack/track-pct {:scroll 0 :viewport 0 :track 0 :horizontal? true} 10))))))

(deftest the-window-places-feet-on-the-range-it-frames
  (testing "at full range the window IS the altitude axis"
    (is (= 0 (stack/window-pct stack/full-window 0)))
    (is (= 100 (stack/window-pct stack/full-window stack/ceiling-ft)))
    (is (= (stack/altitude-pct 34775) (stack/window-pct stack/full-window 34775))
        "identical to the window-free scale, which is what makes zooming OUT a
         return rather than a different instrument"))

  (testing "zoomed, feet are placed on the slice — a 2000ft window spreads two
            aircraft 200ft apart across a fifth of the ruler, where the full
            range would have merged them into one another"
    (let [w {:min-ft 30000 :max-ft 32000}]
      (is (= 0 (stack/window-pct w 30000)))
      (is (= 50 (stack/window-pct w 31000)))
      (is (= 100 (stack/window-pct w 32000)))
      (is (= 10 (stack/window-pct w 30200)))
      (is (< (- (stack/altitude-pct 30200) (stack/altitude-pct 30000)) 0.5)
          "…which at full range was under half a percent of the ruler — half a
           pixel, and the whole reason for the zoom")))

  (testing "in-window? is what keeps a tick honest: outside the frame it is not
            drawn at all, never clamped onto the edge claiming an altitude it
            does not have"
    (let [w {:min-ft 30000 :max-ft 32000}]
      (is (stack/in-window? w 31000))
      (is (not (stack/in-window? w 29999)))
      (is (not (stack/in-window? w 32001)))
      (is (not (stack/in-window? w nil)) "and absent is not an altitude"))))

(deftest the-graduations-follow-the-window
  (testing "a fixed SFC/FL100/…/FL400 was honest only at full range. Zoom to a
            2000ft slice and it would print one rule or none, and the reader
            would be staring at an unlabelled band of colour"
    (is (= 10000 (stack/graduation-step 45000)) "the whole sky rules by 10,000")
    (is (= 500 (stack/graduation-step 2000)) "a 2000ft slice rules by 500")
    (is (>= (count (stack/graduations 1)) 3)
        "every zoom is ruled at least three times — always readable, never a wall
         of lines"))

  (testing "zoomed in, the step tightens and the rules multiply — and they are
            real altitudes, labelled as the charts print them"
    (let [gs (stack/graduations 22)]                ; ~2000ft in view
      (is (some #(= "FL310" (second %)) gs) "FL310, three digits, as charts print")
      (is (> (count gs) (count (stack/graduations 1)))
          "more rules, because the reader can now see between them")))

  (testing "the surface still reads SFC, not FL000"
    (is (= "SFC" (stack/graduation-label 0)))
    (is (= "FL050" (stack/graduation-label 5000)))))

(deftest nothing-may-vanish-silently
  ;; The census counts sit inches from the ruler and count the WHOLE sky. A
  ;; windowed ruler shows a slice. If aircraft simply disappeared, the two
  ;; instruments would disagree and the ruler would be the one lying.
  (let [low   (assoc ups :aircraft/icao "low00" :aircraft/altitude-ft 5000)
        high  (assoc ups :aircraft/icao "high0" :aircraft/altitude-ft 40000)
        here  (assoc ups :aircraft/icao "here0" :aircraft/altitude-ft 31000)
        window {:min-ft 30000 :max-ft 32000}]

    (testing "what the window hides, its ends report"
      (let [o (stack/overflow window [low high here])]
        (is (= 1 (:below o)) "one below the floor")
        (is (= 1 (:above o)) "one above the ceiling")
        (is (not (:below-emergency? o)))
        (is (not (:above-emergency? o)))))

    (testing "AND §7 DOES NOT BEND FOR A VIEW STATE. An emergency outside the
              window is an emergency the reader cannot see — so the end that is
              hiding it goes red"
      (let [mayday (assoc fixtures/squawking-7700 :aircraft/altitude-ft 41000)
            o      (stack/overflow window [here mayday])]
        (is (= 1 (:above o)))
        (is (:above-emergency? o)
            "the ruler's ceiling admits it is hiding a distress squawk")))))

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

(deftest a-shelf-chip-opens-a-drawer-that-names-its-residents
  (testing "tapping a caption answers the question a count never could: WHO"
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
      (is (some? (drawer-row "aaaaaa"))
          "as a row in the drawer, not a dot in a cluster")
      (is (= "true" (.getAttribute (chip "unknown") "aria-expanded")))

      (click-and-commit! (chip "unknown"))
      (is (nil? @(rf/subscribe [:stack/open-shelf])) "and tapping again closes it")
      (is (nil? (.queryByText rtl/screen "NOALT"))
          "the drawer is gone, and its residents are dots again"))))

(deftest a-named-resident-is-still-selectable
  (testing "a drawer row fires the same [:aircraft/select icao] contract a
            tick does — the drawer names them, it does not sideline them"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [no-altitude])])
      (rf/dispatch [:stack/toggle-shelf :unknown])
      (render-stack!)
      (.click rtl/fireEvent (drawer-row "aaaaaa"))
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

(deftest the-three-counts-are-one-register-and-all-are-permanent
  (testing "GND, NO ALT and EMG all answer the same question — how many
            aircraft are in this state — so all three are always on the chart,
            and a zero is a READING, not an absence. They are not the header's
            stream and feeder signals: those report on the apparatus and go
            quiet while it is healthy; these report on the SKY, and a stated
            zero beats an implied one on a distress readout"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups])])   ; one aircraft, cruising
      (render-stack!)
      (doseq [band ["ground" "unknown" "emergency"]]
        (is (some? (.queryByTestId rtl/screen (str "shelf:" band)))
            (str band " states its count in a calm sky")))
      (is (some? (.getByText rtl/screen "EMG"))
          "EMG is named in words — a reader never has to infer it from a colour"))))

(deftest red-arrives-with-the-aircraft-that-deserve-it
  (testing "§7 makes red the ink that never blinks, and it holds that power only
            by being ABSENT from a calm chart. So EMG is permanent but its RED
            is not: at zero the swatch carries no palette colour at all and the
            caption is quiet"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/on-the-ground])])
      (render-stack!)
      (let [chip (.getByTestId rtl/screen "shelf:emergency")]
        (is (= "0" (.-textContent (.querySelector chip ".adsb-stack-shelf-count")))
            "the fact is stated: nobody is squawking")
        (is (nil? (.getAttribute (.getByTestId rtl/screen "swatch:emergency")
                                 "data-color"))
            "and the swatch spends no red on a calm sky")
        (is (nil? (.closest chip ".adsb-stack-emergency-active"))
            "nothing on the Stack is dressed as an emergency"))))

  (testing "the moment an aircraft squawks, the caption takes the red — and
            becomes the key for the red that is now on the chart"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/squawking-7700])])
      (render-stack!)
      (let [chip (.getByTestId rtl/screen "shelf:emergency")]
        (is (= "1" (.-textContent (.querySelector chip ".adsb-stack-shelf-count"))))
        (is (= (:emergency-color (style/palette @theme/!theme))
               (.getAttribute (.getByTestId rtl/screen "swatch:emergency")
                              "data-color"))
            "the swatch is the map's own emergency ink, not a token guessing at it")
        (is (some? (.closest chip ".adsb-stack-emergency-active"))
            "and the caption is dressed for it")))))

(deftest the-traffic-fraction-counts-what-is-drawn-over-what-is-heard
  (testing "AC 1/2 — a metric about the SKY, so it reads from the Stack and not
            the header, which reports on the apparatus. It is a FRACTION
            because the relationship is the fact: `2 · 1` states two numbers and
            leaves the reader to subtract, and never says which is the subset of
            which. The gap is the whole point — those aircraft are real, heard
            on the radio, and NOT ON THE MAP"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/never-positioned])])
      (render-stack!)
      (let [el (.getByTestId rtl/screen "shelf:traffic")]
        (is (= "2" (.getAttribute el "data-total"))
            "both aircraft are heard")
        (is (= "1" (.getAttribute el "data-positioned"))
            "but only one of them can be placed")
        (is (some? (.getByText rtl/screen "1/2"))
            "and the fraction says so without asking anyone to subtract")
        (is (nil? (.queryByTestId rtl/screen "swatch:traffic"))
            "and it wears no swatch: a fraction is not a colour, and it keys
            nothing on the chart"))))

  (testing "the census counts the WHOLE picture, not the ruler's roster — an
            aircraft the Stack cannot place is exactly the one the fraction
            exists to count"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/on-the-ground])])
      (render-stack!)
      (let [el (.getByTestId rtl/screen "shelf:traffic")]
        (is (= "2" (.getAttribute el "data-total")))
        (is (= "2" (.getAttribute el "data-positioned"))
            "a grounded aircraft is still an aircraft on the chart")))))

;; ---------------------------------------------------------------------
;; Focus — select, and take the chart there.

(defn- capturing-flights!
  "Stand in for the camera. The real :map/fly-to effect reaches into the live
  MapLibre map (adsb.map.view); here it records where it was asked to go, which
  is the whole of what this surface promises."
  [!flights]
  (rf/reg-fx :map/fly-to (fn [position] (swap! !flights conj position))))

(deftest focusing-from-a-list-takes-the-chart-to-the-aircraft
  (testing "a drawer names aircraft the reader may not be able to see — some off
            the edge of the chart entirely. Naming a thing you cannot see and
            then not showing it to you is not much of an answer"
    (let [!flights (atom [])]
      (capturing-flights! !flights)
      (rf-test/run-test-sync
        (rf/dispatch [:test/set-picture (by-icao [ups fixtures/on-the-ground])])
        (rf/dispatch [:stack/toggle-shelf :ground])
        (render-stack!)
        (click-and-commit! (drawer-row "a1d645"))
        (is (= "a1d645" @(rf/subscribe [:aircraft/selected-icao]))
            "the aircraft is selected")
        (is (= [(:aircraft/position fixtures/on-the-ground)] @!flights)
            "and the chart flew to its position — the aircraft's own, not a guess")))))

(deftest an-aircraft-with-no-position-is-selected-but-not-flown-to
  (testing "the NO POS drawer exists BECAUSE those aircraft have nowhere to be
            flown to — heard on the radio, never located. They still select; the
            chart simply has nowhere to go, and stays where the reader left it"
    (let [!flights (atom [])]
      (capturing-flights! !flights)
      (rf-test/run-test-sync
        (rf/dispatch [:test/set-picture (by-icao [ups fixtures/never-positioned])])
        (rf/dispatch [:stack/toggle-shelf :traffic])
        (render-stack!)
        (click-and-commit! (drawer-row "a10202"))
        (is (= "a10202" @(rf/subscribe [:aircraft/selected-icao]))
            "selected — the card still names it, the tick still lights")
        (is (empty? @!flights)
            "but the camera did not move: there is nowhere to move it to")))))

(deftest pressing-the-focused-aircraft-unfocuses-it
  (testing "a selection is a spotlight, and pressing the thing already lit is how
            anyone expects to put it out"
    (let [!flights (atom [])]
      (capturing-flights! !flights)
      (rf-test/run-test-sync
        (rf/dispatch [:test/set-picture (by-icao [ups fixtures/on-the-ground])])
        (rf/dispatch [:stack/toggle-shelf :ground])
        (render-stack!)

        (click-and-commit! (drawer-row "a1d645"))
        (is (= "a1d645" @(rf/subscribe [:aircraft/selected-icao])) "lit")

        (click-and-commit! (drawer-row "a1d645"))
        (is (nil? @(rf/subscribe [:aircraft/selected-icao])) "and out again")
        (is (= 1 (count @!flights))
            "and it did NOT fly a second time: dismissing an aircraft must not
             also take you to the thing you just dismissed")))))

(deftest the-map-s-own-click-selects-without-moving-the-chart
  (testing "the plain [:aircraft/select icao] contract is what the MAP dispatches
            — you clicked a plane you can already see, and flying to it would
            yank the chart out from under your own finger. It still toggles"
    (let [!flights (atom [])]
      (capturing-flights! !flights)
      (rf-test/run-test-sync
        (rf/dispatch [:test/set-picture (by-icao [ups])])
        (rf/dispatch [:aircraft/select ups-icao])
        (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao])))
        (is (empty? @!flights) "the map does not fly to what you just clicked on it")
        (rf/dispatch [:aircraft/select ups-icao])
        (is (nil? @(rf/subscribe [:aircraft/selected-icao]))
            "and clicking the lit plane puts it out")))))

(deftest there-is-only-ever-one-drawer
  (testing "open a second caption and the first one's aircraft are SWAPPED OUT,
            not stacked beside them. One panel, one list, and never a question
            about which one you are reading"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture
                    (by-icao [ups fixtures/on-the-ground no-altitude])])
      (render-stack!)

      (click-and-commit! (.getByTestId rtl/screen "shelf:ground"))
      (is (= 1 (.-length (.querySelectorAll js/document ".adsb-stack-drawer"))))
      (is (= "ground" (.getAttribute (.getByTestId rtl/screen "drawer") "data-band")))
      (is (some? (drawer-row "a1d645")) "the grounded aircraft is the one named")

      (click-and-commit! (.getByTestId rtl/screen "shelf:unknown"))
      (is (= 1 (.-length (.querySelectorAll js/document ".adsb-stack-drawer")))
          "still exactly one drawer — the second did not open beside the first")
      (is (= "unknown" (.getAttribute (.getByTestId rtl/screen "drawer") "data-band"))
          "and it is showing the other band now")
      (is (some? (drawer-row "aaaaaa")) "its residents are the altitude-less ones")
      (is (nil? (drawer-row "a1d645"))
          "and the grounded aircraft has gone back to being a dot"))))

(deftest the-drawer-closes-from-its-own-button
  (testing "an overlay you cannot dismiss is a trap"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/on-the-ground])])
      (rf/dispatch [:stack/toggle-shelf :ground])
      (render-stack!)
      (is (some? (.queryByTestId rtl/screen "drawer")))
      (click-and-commit! (.getByTestId rtl/screen "drawer-close"))
      (is (nil? @(rf/subscribe [:stack/open-shelf])))
      (is (nil? (.queryByTestId rtl/screen "drawer")) "shut"))))

(deftest plotted-opens-onto-the-gap
  ;; The aircraft that are HEARD and NOT ON THE MAP are the only ones in the app
  ;; that cannot be reached by pointing at them — there is nothing to point at.
  ;; The drawer is their only door.
  (testing "the unplotted are the picture minus the positioned"
    (let [picture (by-icao [ups fixtures/never-positioned fixtures/on-the-ground])]
      (is (= ["a10202"] (map :aircraft/icao (stack/unplotted picture)))
          "only the aircraft with no position at all")))

  (testing "PLOTTED opens a drawer naming exactly those, and no others"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/never-positioned])])
      (render-stack!)
      (click-and-commit! (.getByTestId rtl/screen "shelf:traffic"))
      (is (= "traffic" (.getAttribute (.getByTestId rtl/screen "drawer") "data-band")))
      (is (some? (drawer-row "a10202"))
          "the aircraft heard but never located is named at last")
      (is (some? (tick-el "a10202"))
          "AND it keeps its tick on the ruler: it has an altitude, and the scale
           must still say so. Two surfaces, two true facts — not one fact twice")
      (is (nil? (drawer-row ups-icao))
          "the one you can already see on the map is not in the gap")))

  (testing "with nothing missing there is no gap, so PLOTTED is no door: every
            aircraft is on the map, and a caption with nobody behind it opens
            nothing"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/on-the-ground])])
      (render-stack!)
      (let [chip (.getByTestId rtl/screen "shelf:traffic")]
        (is (= "SPAN" (.-tagName chip)) "not a button")
        (is (= "2/2" (.-textContent
                      (.querySelector chip ".adsb-stack-shelf-count")))
            "and it still states its fact")))))

(deftest a-count-of-zero-is-not-a-button
  (testing "a chip at zero has no residents to name, so it opens a sheet of
            nobody — a dead target, and an empty bordered box floating over the
            map. It states its fact as a plain caption instead, and offers no
            door to nowhere"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups])])   ; nothing on the ground
      (render-stack!)
      (let [gnd (.getByTestId rtl/screen "shelf:ground")]
        (is (= "SPAN" (.-tagName gnd)) "not a button")
        (is (nil? (.getAttribute gnd "data-shelf"))
            "and it carries no shelf hook, so a click on it dispatches nothing")
        (click-and-commit! gnd)
        (is (nil? @(rf/subscribe [:stack/open-shelf])) "so nothing opens")
        (is (nil? (.querySelector js/document ".adsb-stack-drawer"))
            "and no empty drawer is ever drawn"))))

  (testing "give it a resident and it becomes a door again"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/on-the-ground])])
      (render-stack!)
      (let [gnd (.getByTestId rtl/screen "shelf:ground")]
        (is (= "BUTTON" (.-tagName gnd)) "a button, because now it has names to give")
        (click-and-commit! gnd)
        (is (= :ground @(rf/subscribe [:stack/open-shelf])))
        (is (some? (.querySelector js/document ".adsb-stack-drawer")))))))

(deftest a-drawer-of-nobody-closes-itself
  (testing "the last resident lands or ages out while its drawer stands open —
            the drawer goes with them rather than hanging there empty"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/on-the-ground])])
      (rf/dispatch [:stack/toggle-shelf :ground])
      (render-stack!)
      (is (some? (.querySelector js/document ".adsb-stack-drawer"))
          "open, with its one resident named")

      ;; Same reason click-and-commit! exists: Reagent queues the re-render and
      ;; RTL 16 commits it through a React 18 root, so neither has run by the
      ;; time the dispatch returns.
      (rtl/act (fn []
                 (rf/dispatch [:test/set-picture (by-icao [ups])])  ; the tarmac empties
                 (r/flush)))
      (is (nil? (.querySelector js/document ".adsb-stack-drawer"))
          "and now there is nobody to name, so there is no drawer"))))

;; ---------------------------------------------------------------------
;; The scrub (adsb-4et) — a finger, in a real browser.

(defn- ruler
  "The scroll VIEW — the element the pointer handlers live on now, and the one
  whose scroll offset is the ruler's truth."
  []
  (.querySelector js/document ".adsb-stack-ruler-view"))

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

(deftest tapping-a-tick-selects-it-exactly-once
  ;; A real tap on a ruler tick fires pointerdown, pointerup AND click. The tick
  ;; selects through the SCRUB (a tap is a one-frame scrub), so the Stack's click
  ;; handler must ignore the ruler entirely — otherwise one press dispatches
  ;; [:aircraft/select icao] twice, and since selection TOGGLES the aircraft would
  ;; light and go out in the same press. A bug with no symptom but nothing
  ;; happening.
  (testing "a tap fires the map's [:aircraft/select icao] contract, once"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/squawking-7700])])
      (render-stack!)
      (lay-the-ruler-down!)
      (is (nil? @(rf/subscribe [:aircraft/selected-icao])) "nothing selected yet")

      ;; everything a browser sends for one tap, in order
      (.pointerDown rtl/fireEvent (ruler) #js {:clientX 160 :clientY 10})
      (.pointerUp rtl/fireEvent (ruler) #js {:clientX 160 :clientY 10})
      (.click rtl/fireEvent (tick-el ups-icao))
      (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao]))
          "the Stack selects the same identity the map would — and STAYS
           selected: the click did not undo what the pointerup chose"))))

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

(deftest a-released-scrub-puts-the-crosshair-down
  (testing "release selects what the scrub named AND ends the naming: the
            hover was the gesture's own annotation, and it usually names a
            tick the pointer never physically touched — left standing, no
            mouseout would ever come to clear it (adsb-o7n). The chosen tick
            stays named through its selection"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/squawking-7700])])
      (render-stack!)
      (lay-the-ruler-down!)
      (.pointerDown rtl/fireEvent (ruler) #js {:clientX 160 :clientY 10})
      (is (= ups-icao @(rf/subscribe [:aircraft/hovered-icao]))
          "named while the finger is down")
      (.pointerUp rtl/fireEvent (ruler) #js {:clientX 160 :clientY 10})
      (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao])) "chosen")
      (is (nil? @(rf/subscribe [:aircraft/hovered-icao]))
          "and the crosshair is down"))))

(deftest leaving-the-stack-clears-any-hover-unconditionally
  (testing "the backstop (adsb-o7n): a hover can outlive the mouseout that was
            supposed to clear it — the scrub names the NEAREST tick, not the
            touched one; a hovered tick's node can unmount when its aircraft
            ages out; a wheel-zoom slides the track out from under a cursor
            that never moved. Wherever the hover came from, the pointer
            leaving the Stack ends it"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups])])
      (render-stack!)
      (rf/dispatch [:aircraft/hover ups-icao])   ; set behind the mouse's back
      (is (= ups-icao @(rf/subscribe [:aircraft/hovered-icao])))
      (.pointerLeave rtl/fireEvent (.querySelector js/document ".adsb-stack"))
      (is (nil? @(rf/subscribe [:aircraft/hovered-icao]))
          "no mouseout ever came, and none was needed"))))

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

;; ---------------------------------------------------------------------
;; The zoom, stated (adsb-4w4).

(deftest the-zoom-readout-prints-the-ratio
  (testing "whole ratios print whole, the rest to one decimal — the reader
            needs the order of the magnification, not the float behind it"
    (is (= "×1.5" (stack/zoom-label 1.5)))
    (is (= "×2" (stack/zoom-label 2)))
    (is (= "×2.3" (stack/zoom-label 2.2999997)))
    (is (= "×90" (stack/zoom-label 90)))))

(deftest a-zoomed-ruler-states-it-and-offers-the-way-back
  (testing "wheel and pinch zoom are otherwise invisible affordances: nothing
            says the ruler is framing a slice of the sky except the overflow
            counts, and the only way home was an unadvertised double-click.
            While zoom > 1 the census row states the view fact; pressing it
            is the way back"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups])])
      (rf/dispatch [:stack/set-zoom 4])
      (render-stack!)
      (is (some? (.getByText rtl/screen "×4")) "the ratio is stated")
      (click-and-commit! (.getByTestId rtl/screen "zoom-reset"))
      (is (= 1 @(rf/subscribe [:stack/zoom])) "back to the whole sky")
      (is (= stack/full-window @(rf/subscribe [:stack/window]))
          "the window is the whole sky again, not a stale snapshot of the
          slice it was framing")
      (is (nil? (.queryByTestId rtl/screen "zoom-reset"))
          "and the note goes with the view state it described")))

  (testing "at zoom 1 there is no chip: the whole sky needs no note that you
            are looking at all of it, and a reset that resets nothing is a
            door to nowhere — the same rule the count-zero captions keep"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups])])
      (render-stack!)
      (is (nil? (.queryByTestId rtl/screen "zoom-reset"))))))

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
