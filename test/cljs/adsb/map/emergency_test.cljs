(ns adsb.map.emergency-test
  (:require [adsb.corejs :as cjs]
            [adsb.events]
            [adsb.fixtures :as fixtures]
            [adsb.map.emergency :as emergency]
            [adsb.stream]
            [adsb.subs]
            [adsb.test-map :as test-map]
            [adsb.test-rf :as test-rf]
            [clojure.test :refer-macros [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def ^:private emergency-icao (:aircraft/icao fixtures/squawking-7700))
(def ^:private emergency-lng-lat
  (let [{:geo/keys [lat lon]} (:aircraft/position fixtures/squawking-7700)]
    [lon lat]))

(defn- added-elements [rec]
  (map #(get-in % [:marker :element]) (:added @rec)))

(deftest the-red-pen-circles-an-emergency-and-lifts-when-it-clears
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (test-map/recording-map)
          handle (emergency/attach! m)]
      (testing "a calm sky wears no annotation"
        (test-rf/set-picture! [fixtures/ups-2717])
        (r/flush)
        (is (= [] (:added @rec))))

      (testing "an in-view emergency is circled at its position"
        (test-rf/set-picture! [fixtures/ups-2717
                               fixtures/squawking-7700])
        (r/flush)
        (is (= 1 (count (:added @rec))))
        (is (= emergency-lng-lat (:lng-lat (first (:added @rec)))))
        (let [el (first (added-elements rec))]
          (is (= "adsb-mayday" (.-className el)))
          (is (= 2 (.-length (.querySelectorAll el "ellipse"))))))

      (testing "the squawk clearing lifts the ink off the chart"
        (test-rf/set-picture! [fixtures/ups-2717
                               (assoc fixtures/squawking-7700
                                 :aircraft/squawk "1200")])
        (r/flush)
        (is (= 1 (count (:removed @rec))))
        (is (= 1 (count (:added @rec)))))

      (emergency/detach! m handle))))

(deftest the-ink-draws-once-then-holds
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (test-map/recording-map)
          handle (emergency/attach! m)]
      (test-rf/set-picture! [fixtures/squawking-7700])
      (r/flush)

      (testing "each pass of the pen is a ONE-iteration entrance — never
                a loop, never a pulse (§6: ink never blinks)"
        (let [el       (first (added-elements rec))
              ellipses (array-seq (.querySelectorAll el "ellipse"))]
          (is (= 2 (count ellipses)))
          (doseq [ellipse ellipses]
            (is (= "adsb-mayday-draw" (.. ellipse -style -animationName)))
            (is (= "1" (.. ellipse -style -animationIterationCount))))
          (is (apply not= (map #(.. % -style -animationDelay) ellipses)))))

      (testing "the same emergency tracking across the chart MOVES the
                marker — the element is never rebuilt, so the entrance
                cannot replay"
        (test-rf/set-picture! [(assoc fixtures/squawking-7700
                                  :aircraft/position
                                  #:geo{:lat 28.0 :lon -82.5})])
        (r/flush)
        (is (= 1 (count (:added @rec))))
        (is (= [-82.5 28.0] (:lng-lat (last (:moves @rec))))))

      (emergency/detach! m handle))))

(deftest the-stamp-reads-the-squawk
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (test-map/recording-map)
          handle (emergency/attach! m)]
      (test-rf/set-picture! [fixtures/squawking-7700])
      (r/flush)
      (let [el    (first (added-elements rec))
            stamp (cjs/select el ".adsb-mayday-stamp")
            text  (.-textContent stamp)]
        (testing "MAYDAY plus the squawk's meaning, in the strip's own words"
          (is (re-find #"(?i)mayday" text))
          (is (re-find #"(?i)general emergency" text)))
        (testing "callsign, altitude, and vertical rate from the fixture"
          (is (re-find #"DAL1275" text))
          (is (re-find #"10025 ft" text))
          (is (re-find #"↓1152 fpm" text))))

      (testing "the stamp's DATA updates in place — same element, new facts"
        (test-rf/set-picture! [(assoc fixtures/squawking-7700
                                  :aircraft/altitude-ft 9000
                                  :aircraft/baro-rate-fpm 320)])
        (r/flush)
        (is (= 1 (count (:added @rec))) "no rebuild, no replayed entrance")
        (let [text (.-textContent
                     (cjs/select (first (added-elements rec)) ".adsb-mayday-stamp"))]
          (is (re-find #"9000 ft" text))
          (is (re-find #"↑320 fpm" text))))
      (emergency/detach! m handle))))

(def ^:private off-screen-north
  (assoc fixtures/squawking-7700 :aircraft/position #:geo{:lat 31.0 :lon -82.0}))

(deftest an-off-screen-emergency-raises-the-edge-arrow
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (test-map/recording-map)
          handle (emergency/attach! m)]
      (test-rf/set-picture! [off-screen-north])
      (r/flush)

      (testing "the annotation is the ARROW, not the ellipse — the arrow
                exists only for what the frame cannot show"
        (is (= 1 (count (:added @rec))))
        (let [el (first (added-elements rec))]
          (is (= "BUTTON" (.-tagName el)))
          (is (= "adsb-edge-arrow" (.-className el)))
          (is (= emergency-icao (.getAttribute el "data-icao")))))

      (testing "pinned just inside the top edge, on the ray toward the
                aircraft (due north of centre -> the top midpoint), pulled
                past the header + NOTAM chrome"
        (let [[lng lat] (:lng-lat (first (:added @rec)))]
          (is (fixtures/close? -82.0 lng 1e-6))
          (is (< 28.5 lat 29.0))))

      (testing "the label carries callsign and great-circle distance"
        (let [text (.-textContent (first (added-elements rec)))]
          (is (re-find #"DAL1275" text))
          (is (re-find #"180 nm" text))))

      (testing "the glyph is rotated to the bearing — due north is 0°"
        (let [glyph (cjs/select (first (added-elements rec)) ".adsb-edge-arrow-glyph")]
          (is (= "rotate(0deg)" (-> glyph .-style .-transform)))))

      (emergency/detach! m handle))))

(deftest clicking-the-arrow-offers-selection-and-hijacks-nothing
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (test-map/recording-map)
          handle (emergency/attach! m)]
      (test-rf/set-picture! [off-screen-north])
      (r/flush)
      (.click (first (added-elements rec)))
      (is (= emergency-icao @(rf/subscribe [:aircraft/selected-icao])))
      (emergency/detach! m handle))))

(deftest a-settled-camera-move-re-judges-the-annotations
  (rf-test/run-test-sync
    (let [{:keys [m rec !bounds] :as fake} (test-map/recording-map)
          handle (emergency/attach! m)]
      (test-rf/set-picture! [off-screen-north])
      (r/flush)
      (is (= "adsb-edge-arrow" (.-className (first (added-elements rec)))))

      (testing "panning the emergency INTO view swaps arrow for a fresh
                ellipse via the captured moveend handler"
        (reset! !bounds {:geo/min-lat 30.0 :geo/max-lat 32.0
                         :geo/min-lon -83.0 :geo/max-lon -81.0})
        (test-map/fire-move! fake)
        (is (= 1 (count (:removed @rec))))
        (is (= 2 (count (:added @rec))))
        (is (= "adsb-mayday" (.-className (last (added-elements rec)))))
        (is (= [-82.0 31.0] (:lng-lat (last (:added @rec))))))

      (testing "a move that changes nothing MOVES the marker in place —
                settled ink holds still"
        (let [added-before (count (:added @rec))]
          (test-map/fire-move! fake)
          (is (= added-before (count (:added @rec))))
          (is (= [-82.0 31.0] (:lng-lat (last (:moves @rec)))))))

      (emergency/detach! m handle))))

(deftest every-emergency-gets-its-own-annotation
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (test-map/recording-map)
          handle           (emergency/attach! m)
          off-screen-south (assoc fixtures/squawking-7700
                             :aircraft/icao "b00001"
                             :aircraft/callsign "NORDO1"
                             :aircraft/squawk "7600"
                             :aircraft/position
                             #:geo{:lat 25.0 :lon -82.0})]
      (test-rf/set-picture! [fixtures/ups-2717
                              fixtures/squawking-7700
                              off-screen-south])
      (r/flush)
      (is (= 2 (count (:added @rec))))
      (let [classes (set (map #(.-className %) (added-elements rec)))]
        (is (= #{"adsb-mayday" "adsb-edge-arrow"} classes)))
      (let [arrow (first (filter #(= "BUTTON" (.-tagName %)) (added-elements rec)))]
        (is (re-find #"NORDO1" (.-textContent arrow))))
      (emergency/detach! m handle))))

(deftest an-unpositioned-emergency-marks-nothing
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (test-map/recording-map)
          handle (emergency/attach! m)]
      (test-rf/set-picture! [(-> fixtures/squawking-7700
                                  (dissoc :aircraft/position))])
      (r/flush)
      (is (= [] (:added @rec)))
      (emergency/detach! m handle))))

(deftest detach-lifts-live-annotations
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (test-map/recording-map)
          handle (emergency/attach! m)]
      (test-rf/set-picture! [fixtures/squawking-7700])
      (r/flush)
      (is (= 1 (count (:added @rec))))
      (emergency/detach! m handle)
      (is (= 1 (count (:removed @rec))))
      (test-rf/set-picture! [fixtures/squawking-7700])
      (r/flush)
      (is (= 1 (count (:added @rec)))))))
