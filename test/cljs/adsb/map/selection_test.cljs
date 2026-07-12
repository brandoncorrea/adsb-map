(ns adsb.map.selection-test
  "The compass-pencil selection ring, proven at the seam. A recording
  fake stands in for MapLibre (docs/testing-setup.md, \"The Map Seam\");
  the assertions cover the ring's contract: no selection, no marker; a
  selection draws a FRESH ring element at the aircraft's [lng lat] (a
  fresh element is what replays the draw-in animation); the same
  selection moving MOVES the marker rather than redrawing it; switching
  aircraft redraws; and clearing — or the aircraft aging out, or the
  selection never having a position — leaves no ring on the chart."
  (:require
    [adsb.events]
    [adsb.fixtures :as fixtures]
    [adsb.map.maplibre :as maplibre]
    [adsb.map.selection :as selection]
    [adsb.stream]                                 ; registers :aircraft/picture
    [adsb.subs]                                   ; registers :aircraft/selected
    [cljs.test :refer-macros [deftest is testing]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

;; Seed the picture directly, as the ribbon and Stack suites do: the
;; owning event speaks wire JSON, which is noise for a ring test.
(rf/reg-event-db :test/set-picture
  (fn [db [_ picture]] (assoc db :aircraft/picture picture)))

(defn- by-icao [aircraft]
  (into {} (map (juxt :aircraft/icao identity)) aircraft))

;; ---------------------------------------------------------------------
;; The recording fake: marker ops only — the ring needs nothing else.

(defn- recording-map []
  (let [!rec (atom {:added [] :moves [] :removed []})]
    {:rec !rec
     :m   (reify maplibre/Map
            (add-marker! [_ element lng-lat]
              (let [marker {:element element :id (count (:added @!rec))}]
                (swap! !rec update :added conj {:marker marker :lng-lat lng-lat})
                marker))
            (move-marker! [_ marker lng-lat]
              (swap! !rec update :moves conj {:marker marker :lng-lat lng-lat}))
            (remove-marker! [_ marker]
              (swap! !rec update :removed conj marker)))}))

(def ^:private ups-icao (:aircraft/icao fixtures/ups-2717))
(def ^:private ups-lng-lat
  (let [{:geo/keys [lat lon]} (:aircraft/position fixtures/ups-2717)]
    [lon lat]))

(deftest the-ring-follows-the-selection
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (recording-map)
          handle (selection/attach! m)]
      (testing "no selection, no marker"
        (r/flush)
        (is (= [] (:added @rec))))

      (rf/dispatch [:test/set-picture (by-icao [fixtures/ups-2717
                                                fixtures/on-the-ground])])
      (rf/dispatch [:aircraft/select ups-icao])
      (r/flush)

      (testing "selecting draws the ring at the aircraft's [lng lat]"
        (is (= 1 (count (:added @rec))))
        (is (= ups-lng-lat (:lng-lat (first (:added @rec)))))
        (let [el (:element (:marker (first (:added @rec))))]
          (is (= "adsb-selection-ring" (.-className el))
              "the element carries the app.css hook")
          (is (some? (.querySelector el "circle"))
              "and the SVG circle the draw-in animates")))

      (testing "the same aircraft moving MOVES the marker — no redraw,
                so the settled ink holds still"
        (rf/dispatch [:test/set-picture
                      (by-icao [(assoc fixtures/ups-2717
                                       :aircraft/position
                                       #:geo{:lat 28.0 :lon -83.9})
                                fixtures/on-the-ground])])
        (r/flush)
        (is (= 1 (count (:added @rec))) "no second ring")
        (is (= [-83.9 28.0] (:lng-lat (last (:moves @rec))))))

      (testing "switching aircraft redraws — a fresh element replays the
                draw-in for the new choice"
        (rf/dispatch [:aircraft/select (:aircraft/icao fixtures/on-the-ground)])
        (r/flush)
        (is (= 2 (count (:added @rec))))
        (is (= 1 (count (:removed @rec)))))

      (testing "clearing the selection lifts the ring off the chart"
        (rf/dispatch [:aircraft/clear-selection])
        (r/flush)
        (is (= 2 (count (:removed @rec)))))

      (selection/detach! m handle))))

(deftest a-position-less-selection-draws-nothing
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (recording-map)
          handle (selection/attach! m)]
      (rf/dispatch [:test/set-picture (by-icao [fixtures/never-positioned])])
      (rf/dispatch [:aircraft/select
                    (:aircraft/icao fixtures/never-positioned)])
      (r/flush)
      (is (= [] (:added @rec))
          "heard but never located — there is nowhere to draw a ring")
      (selection/detach! m handle))))

(deftest detach-removes-a-live-ring
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (recording-map)
          handle (selection/attach! m)]
      (rf/dispatch [:test/set-picture (by-icao [fixtures/ups-2717])])
      (rf/dispatch [:aircraft/select ups-icao])
      (r/flush)
      (is (= 1 (count (:added @rec))))
      (selection/detach! m handle)
      (is (= 1 (count (:removed @rec)))
          "teardown leaves no orphan marker on the dying map")
      (rf/dispatch [:aircraft/clear-selection])
      (r/flush)
      (is (= 1 (count (:removed @rec)))
          "and the disposed track never fires again"))))
