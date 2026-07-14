(ns adsb.map.emergency-test
  "The §7 map annotations, proven at the seam. A recording fake stands
  in for MapLibre (docs/testing-setup.md, \"The Map Seam\") with a
  settable viewport, so the assertions cover the whole contract: the
  red-pen double ellipse appears with an emergency and lifts off when
  it clears; the ink DRAWS ONCE AND HOLDS (iteration-count 1, and the
  element is moved — never rebuilt — while the emergency tracks); the
  MAYDAY stamp reads the squawking-7700 fixture's facts; an off-screen
  emergency raises the edge arrow at the boundary instead (and ONLY
  when off-screen); clicking the arrow dispatches the existing
  selection contract; and a settled camera move re-judges everything
  through the captured moveend handler. The edge-point and bearing
  math itself is pure and lives in adsb.geo — proven in adsb.geo-test
  against known geometry, not re-proven here."
  (:require
    [adsb.events]
    [adsb.fixtures :as fixtures]
    [adsb.map.emergency :as emergency]
    [adsb.map.maplibre :as maplibre]
    [adsb.stream]                                 ; registers :aircraft/picture
    [adsb.subs]                                   ; registers :aircraft/emergencies
    [cljs.test :refer-macros [deftest is testing]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

;; Seed the picture directly, as the ring and ribbon suites do: the
;; owning event speaks wire JSON, which is noise for an annotation test.
(rf/reg-event-db :test/set-picture
  (fn [db [_ picture]] (assoc db :aircraft/picture picture)))

(defn- by-icao [aircraft]
  (into {} (map (juxt :aircraft/icao identity)) aircraft))

;; A square regional viewport: centre (28, -82), one degree each way.
;; The squawking-7700 fixture (28.364, -82.968) sits INSIDE it.
(def ^:private viewport
  {:geo/min-lat 27.0 :geo/max-lat 29.0
   :geo/min-lon -83.0 :geo/max-lon -81.0})

;; ---------------------------------------------------------------------
;; The recording fake: marker ops plus the two viewport accessors the
;; annotations added to the seam — a settable bounds box and a captured
;; moveend handler the tests fire by hand.

(defn- recording-map []
  (let [!rec    (atom {:added [] :moves [] :removed []})
        !bounds (atom viewport)
        !move   (atom nil)]
    {:rec     !rec
     :!bounds !bounds
     :!move   !move
     :m       (reify maplibre/Map
                (add-marker! [_ element lng-lat]
                  (let [marker {:element element :id (count (:added @!rec))}]
                    (swap! !rec update :added conj
                           {:marker marker :lng-lat lng-lat})
                    marker))
                (move-marker! [_ marker lng-lat]
                  (swap! !rec update :moves conj
                         {:marker marker :lng-lat lng-lat}))
                (remove-marker! [_ marker]
                  (swap! !rec update :removed conj marker))
                (bounds [_] @!bounds)
                (on-move! [_ f] (reset! !move f))
                (fit-bounds! [_ _bounds _padding] nil))}))

(def ^:private emergency-icao (:aircraft/icao fixtures/squawking-7700))
(def ^:private emergency-lng-lat
  (let [{:geo/keys [lat lon]} (:aircraft/position fixtures/squawking-7700)]
    [lon lat]))

(defn- added-elements [rec]
  (map #(get-in % [:marker :element]) (:added @rec)))

(defn- close? [expected actual tol]
  (< (js/Math.abs (- expected actual)) tol))

;; ---------------------------------------------------------------------
;; The ellipse

(deftest the-red-pen-circles-an-emergency-and-lifts-when-it-clears
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (recording-map)
          handle (emergency/attach! m)]
      (testing "a calm sky wears no annotation"
        (rf/dispatch [:test/set-picture (by-icao [fixtures/ups-2717])])
        (r/flush)
        (is (= [] (:added @rec))))

      (testing "an in-view emergency is circled at its position"
        (rf/dispatch [:test/set-picture (by-icao [fixtures/ups-2717
                                                  fixtures/squawking-7700])])
        (r/flush)
        (is (= 1 (count (:added @rec))) "one emergency, one annotation")
        (is (= emergency-lng-lat (:lng-lat (first (:added @rec)))))
        (let [el (first (added-elements rec))]
          (is (= "adsb-mayday" (.-className el))
              "the element carries the app.css hook")
          (is (= 2 (.-length (.querySelectorAll el "ellipse")))
              "a DOUBLE ellipse — two passes of the pen")))

      (testing "the squawk clearing lifts the ink off the chart"
        (rf/dispatch [:test/set-picture
                      (by-icao [fixtures/ups-2717
                                (assoc fixtures/squawking-7700
                                       :aircraft/squawk "1200")])])
        (r/flush)
        (is (= 1 (count (:removed @rec))))
        (is (= 1 (count (:added @rec))) "and nothing replaced it"))

      (emergency/detach! m handle))))

(deftest the-ink-draws-once-then-holds
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (recording-map)
          handle (emergency/attach! m)]
      (rf/dispatch [:test/set-picture (by-icao [fixtures/squawking-7700])])
      (r/flush)

      (testing "each pass of the pen is a ONE-iteration entrance — never
                a loop, never a pulse (§6: ink never blinks)"
        (let [el       (first (added-elements rec))
              ellipses (array-seq (.querySelectorAll el "ellipse"))]
          (is (= 2 (count ellipses)))
          (doseq [ellipse ellipses]
            (is (= "adsb-mayday-draw" (.. ellipse -style -animationName))
                "the draw-in is armed")
            (is (= "1" (.. ellipse -style -animationIterationCount))
                "and it runs exactly once"))
          (is (apply not= (map #(.. % -style -animationDelay) ellipses))
              "the second pass trails the first — a double stroke, not a
              synchronized pair")))

      (testing "the same emergency tracking across the chart MOVES the
                marker — the element is never rebuilt, so the entrance
                cannot replay"
        (rf/dispatch [:test/set-picture
                      (by-icao [(assoc fixtures/squawking-7700
                                       :aircraft/position
                                       #:geo{:lat 28.0 :lon -82.5})])])
        (r/flush)
        (is (= 1 (count (:added @rec))) "no second annotation")
        (is (= [-82.5 28.0] (:lng-lat (last (:moves @rec))))))

      (emergency/detach! m handle))))

(deftest the-stamp-reads-the-squawk
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (recording-map)
          handle (emergency/attach! m)]
      (rf/dispatch [:test/set-picture (by-icao [fixtures/squawking-7700])])
      (r/flush)
      (let [el    (first (added-elements rec))
            stamp (.querySelector el ".adsb-mayday-stamp")
            text  (.-textContent stamp)]
        (testing "MAYDAY plus the squawk's meaning, in the strip's own words"
          (is (re-find #"(?i)mayday" text))
          (is (re-find #"(?i)general emergency" text)
              "7700 means a general emergency — the words, not just a code"))
        (testing "callsign, altitude, and vertical rate from the fixture"
          (is (re-find #"DAL1275" text))
          (is (re-find #"10025 ft" text))
          (is (re-find #"↓1152 fpm" text)
              "descending at 1152 fpm, the arrow carrying the sign")))

      (testing "the stamp's DATA updates in place — same element, new facts"
        (rf/dispatch [:test/set-picture
                      (by-icao [(assoc fixtures/squawking-7700
                                       :aircraft/altitude-ft 9000
                                       :aircraft/baro-rate-fpm 320)])])
        (r/flush)
        (is (= 1 (count (:added @rec))) "no rebuild, no replayed entrance")
        (let [text (.-textContent
                     (.querySelector (first (added-elements rec))
                                     ".adsb-mayday-stamp"))]
          (is (re-find #"9000 ft" text))
          (is (re-find #"↑320 fpm" text))))

      (emergency/detach! m handle))))

;; ---------------------------------------------------------------------
;; The edge arrow

(def ^:private off-screen-north
  "The emergency, three degrees north of the viewport's top edge — due
  north of its centre, so the arrow pins to the top edge's midpoint."
  (assoc fixtures/squawking-7700
         :aircraft/position #:geo{:lat 31.0 :lon -82.0}))

(deftest an-off-screen-emergency-raises-the-edge-arrow
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (recording-map)
          handle (emergency/attach! m)]
      (rf/dispatch [:test/set-picture (by-icao [off-screen-north])])
      (r/flush)

      (testing "the annotation is the ARROW, not the ellipse — the arrow
                exists only for what the frame cannot show"
        (is (= 1 (count (:added @rec))))
        (let [el (first (added-elements rec))]
          (is (= "BUTTON" (.-tagName el)) "clickable — it offers selection")
          (is (= "adsb-edge-arrow" (.-className el)))
          (is (= emergency-icao (.getAttribute el "data-icao")))))

      (testing "pinned just inside the top edge, on the ray toward the
                aircraft (due north of centre -> the top midpoint), pulled
                past the header + NOTAM chrome"
        (let [[lng lat] (:lng-lat (first (:added @rec)))]
          (is (close? -82.0 lng 1e-6)
              "the ray's x is untouched — mid-viewport clears all chrome")
          (is (< 28.5 lat 29.0)
              "inside the top edge by the pixel insets, never past it")))

      (testing "the label carries callsign and great-circle distance"
        (let [text (.-textContent (first (added-elements rec)))]
          (is (re-find #"DAL1275" text))
          (is (re-find #"180 nm" text)
              "3° along the meridian is ~180 nm")))

      (testing "the glyph is rotated to the bearing — due north is 0°"
        (let [glyph (.querySelector (first (added-elements rec))
                                    ".adsb-edge-arrow-glyph")]
          (is (= "rotate(0deg)" (.. glyph -style -transform)))))

      (emergency/detach! m handle))))

(deftest clicking-the-arrow-offers-selection-and-hijacks-nothing
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (recording-map)
          handle (emergency/attach! m)]
      (rf/dispatch [:test/set-picture (by-icao [off-screen-north])])
      (r/flush)
      (.click (first (added-elements rec)))
      (is (= emergency-icao @(rf/subscribe [:aircraft/selected-icao]))
          "the existing [:aircraft/select icao] contract — the same event
          a plane click fires; the camera is not touched by any of it")
      (emergency/detach! m handle))))

(deftest a-settled-camera-move-re-judges-the-annotations
  (rf-test/run-test-sync
    (let [{:keys [m rec !bounds !move]} (recording-map)
          handle (emergency/attach! m)]
      (rf/dispatch [:test/set-picture (by-icao [off-screen-north])])
      (r/flush)
      (is (= "adsb-edge-arrow" (.-className (first (added-elements rec)))))

      (testing "panning the emergency INTO view swaps arrow for a fresh
                ellipse via the captured moveend handler"
        (reset! !bounds {:geo/min-lat 30.0 :geo/max-lat 32.0
                         :geo/min-lon -83.0 :geo/max-lon -81.0})
        (@!move)
        (is (= 1 (count (:removed @rec))) "the arrow lifted off")
        (is (= 2 (count (:added @rec))) "and the pen drew in")
        (is (= "adsb-mayday" (.-className (last (added-elements rec)))))
        (is (= [-82.0 31.0] (:lng-lat (last (:added @rec))))
            "at the aircraft itself, not the boundary"))

      (testing "a move that changes nothing MOVES the marker in place —
                settled ink holds still"
        (let [added-before (count (:added @rec))]
          (@!move)
          (is (= added-before (count (:added @rec))))
          (is (= [-82.0 31.0] (:lng-lat (last (:moves @rec)))))))

      (emergency/detach! m handle))))

(deftest every-emergency-gets-its-own-annotation
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (recording-map)
          handle (emergency/attach! m)
          off-screen-south (assoc fixtures/squawking-7700
                                  :aircraft/icao "b00001"
                                  :aircraft/callsign "NORDO1"
                                  :aircraft/squawk "7600"
                                  :aircraft/position
                                  #:geo{:lat 25.0 :lon -82.0})]
      (rf/dispatch [:test/set-picture
                    (by-icao [fixtures/ups-2717          ; calm
                              fixtures/squawking-7700    ; 7700, in view
                              off-screen-south])])       ; 7600, off south
      (r/flush)
      (is (= 2 (count (:added @rec))) "one annotation per emergency")
      (let [classes (set (map #(.-className %) (added-elements rec)))]
        (is (= #{"adsb-mayday" "adsb-edge-arrow"} classes)
            "each judged in or out of view on its own"))
      (let [arrow (first (filter #(= "BUTTON" (.-tagName %))
                                 (added-elements rec)))]
        (is (re-find #"NORDO1" (.-textContent arrow))
            "the arrow names ITS aircraft, not the other one"))
      (emergency/detach! m handle))))

(deftest an-unpositioned-emergency-marks-nothing
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (recording-map)
          handle (emergency/attach! m)]
      (rf/dispatch [:test/set-picture
                    (by-icao [(-> fixtures/squawking-7700
                                  (dissoc :aircraft/position))])])
      (r/flush)
      (is (= [] (:added @rec))
          "nowhere to draw and no bearing to point — the NOTAM strip
          still names it")
      (emergency/detach! m handle))))

(deftest detach-lifts-live-annotations
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (recording-map)
          handle (emergency/attach! m)]
      (rf/dispatch [:test/set-picture (by-icao [fixtures/squawking-7700])])
      (r/flush)
      (is (= 1 (count (:added @rec))))
      (emergency/detach! m handle)
      (is (= 1 (count (:removed @rec)))
          "teardown leaves no orphan ink on the dying map")
      (rf/dispatch [:test/set-picture (by-icao [fixtures/squawking-7700])])
      (r/flush)
      (is (= 1 (count (:added @rec)))
          "and the disposed track never fires again"))))
