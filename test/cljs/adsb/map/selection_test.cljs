(ns adsb.map.selection-test
  "The compass-pencil selection ring and flight labels, proven at the seam.
  A recording fake stands in for MapLibre (docs/testing-setup.md, \"The Map
  Seam\"); the assertions cover the contract: no selection, no marker; a
  selection draws a FRESH ring element at the aircraft's [lng lat] with a
  callsign label beneath (adsb-xgg); the same selection moving MOVES the
  marker rather than redrawing it; hover lights a label without a ring;
  hover on the selected aircraft does not double-label; and clearing leaves
  no mark on the chart."
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
(def ^:private ground-icao (:aircraft/icao fixtures/on-the-ground))

(deftest display-name-prefers-callsign
  (is (= "UPS2717" (selection/display-name fixtures/ups-2717)))
  (is (= ground-icao
         (selection/display-name
           (dissoc fixtures/on-the-ground :aircraft/callsign)))))

(deftest ring-element-is-a-fixed-box-so-the-plane-stays-centred
  ;; The callsign label must not grow the layout box — MapLibre anchors the
  ;; box's centre on the aircraft, so a taller box would offset the ring
  ;; upward (adsb-rg1).
  (let [el (selection/ring-element "UPS2717")]
    (is (= "adsb-selection-ring" (.-className el)))
    (is (= "44px" (.-width (.-style el))))
    (is (= "44px" (.-height (.-style el))))
    (is (some? (.querySelector el "circle")))
    (is (some? (.querySelector el ".adsb-flight-label")))
    (is (= "UPS2717"
           (.-textContent (.querySelector el ".adsb-flight-label"))))))

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
              "and the SVG circle the draw-in animates")
          (is (some? (.querySelector el ".adsb-flight-label"))
              "with a callsign label beneath (adsb-xgg)")
          (is (= "UPS2717"
                 (.-textContent (.querySelector el ".adsb-flight-label"))))))

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
        (rf/dispatch [:aircraft/select ground-icao])
        (r/flush)
        (is (= 2 (count (:added @rec))))
        (is (= 1 (count (:removed @rec)))))

      (testing "clearing the selection lifts the ring off the chart"
        (rf/dispatch [:aircraft/clear-selection])
        (r/flush)
        (is (= 2 (count (:removed @rec)))))

      (selection/detach! m handle))))

(deftest hover-draws-a-label-without-a-ring
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (recording-map)
          handle (selection/attach! m)]
      (rf/dispatch [:test/set-picture (by-icao [fixtures/ups-2717
                                                fixtures/on-the-ground])])
      (rf/dispatch [:aircraft/hover ups-icao])
      (r/flush)
      (testing "hover alone pins a label, not the selection ring"
        (is (= 1 (count (:added @rec))))
        (let [el (:element (:marker (first (:added @rec))))]
          (is (= "adsb-hover-pin" (.-className el)))
          (is (nil? (.querySelector el "circle")))
          (is (= "UPS2717"
                 (.-textContent (.querySelector el ".adsb-flight-label"))))))
      (testing "hovering the selected aircraft does not double-label"
        (rf/dispatch [:aircraft/select ups-icao])
        (r/flush)
        ;; selection ring replaces hover pin: remove hover, add selection
        (is (= 1 (count (:removed @rec))))
        (is (= 2 (count (:added @rec))))
        (let [el (:element (:marker (last (:added @rec))))]
          (is (= "adsb-selection-ring" (.-className el)))
          (is (= 1 (.-length (.querySelectorAll el ".adsb-flight-label"))))))
      (selection/detach! m handle))))

(deftest deselect-clears-a-sticky-hover-label
  "Mobile has no mouseleave: a hover left in app-db after toggle-deselect
   would re-pin the callsign chip with no ring (adsb-oi8)."
  (rf-test/run-test-sync
    (let [{:keys [m rec]} (recording-map)
          handle (selection/attach! m)]
      (rf/dispatch [:test/set-picture (by-icao [fixtures/ups-2717])])
      (rf/dispatch [:aircraft/select ups-icao])
      (rf/dispatch [:aircraft/hover ups-icao])
      (r/flush)
      (is (= 1 (count (:added @rec))) "selection ring only while selected+hovered")
      (testing "toggle-deselect clears hover so no orphan label remains"
        (rf/dispatch [:aircraft/select ups-icao])
        (r/flush)
        (is (nil? @(rf/subscribe [:aircraft/hovered-icao]))
            "hover channel is empty")
        (is (nil? @(rf/subscribe [:aircraft/selected-icao]))
            "selection is empty")
        (is (= 1 (count (:removed @rec)))
            "the ring was removed and no hover pin replaced it"))
      (testing "Escape / clear-selection also drops hover"
        (rf/dispatch [:aircraft/select ups-icao])
        (rf/dispatch [:aircraft/hover ups-icao])
        (r/flush)
        (rf/dispatch [:aircraft/clear-selection])
        (r/flush)
        (is (nil? @(rf/subscribe [:aircraft/hovered-icao])))
        (is (nil? @(rf/subscribe [:aircraft/selected-icao]))))
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
