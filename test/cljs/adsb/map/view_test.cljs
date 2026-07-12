(ns adsb.map.view-test
  "The app shell mounts, and it initializes the map through the seam with the
  options we expect. We never build a real MapLibre map — `create!` is faked,
  so this asserts the only thing we own: the options handed across the seam
  (a fixed regional center, a token-free style). See docs/testing-setup.md."
  (:require
    [adsb.map.maplibre :as maplibre]
    [adsb.map.view :as view]
    [adsb.views :as views]
    [cljs.test :refer-macros [deftest is testing use-fixtures]]
    [reagent.core :as r]
    [reagent.dom :as rdom]))

;; A throwaway DOM node per mounting test, cleaned up after each.
(def ^:private !node (atom nil))

(use-fixtures :each
  {:before (fn []
             (let [el (.createElement js/document "div")]
               (.appendChild (.-body js/document) el)
               (reset! !node el)))
   :after  (fn []
             (when-let [el @!node]
               (rdom/unmount-component-at-node el)
               (.remove el)
               (reset! !node nil)))})

(defn- fake-map
  "A `Map` that records nothing but satisfies the full protocol, so a mount
  can create and later destroy it without touching WebGL. The load event
  never fires here, so the aircraft layer stays dormant — its behavior is
  adsb.map.aircraft-layer-test's business."
  []
  (reify maplibre/Map
    (destroy! [_] nil)
    (on-load! [_ _f] nil)
    (add-source! [_ _id _source] nil)
    (add-layer! [_ _layer] nil)
    (set-source-data! [_ _id _data] nil)))

(deftest default-map-opts-privacy
  (testing "default center is a fixed, whole-degree regional point — never a receiver"
    (let [{:keys [center]} (view/default-map-opts)]
      ;; MapLibre order is [lon lat]: lon -82.0, lat 28.0 (Florida Gulf coast).
      (is (= [-82.0 28.0] center))
      (is (every? #(== % (js/Math.round %)) center)
          "whole-degree only — a rooftop's precision must never appear here")))

  (testing "regional zoom, not a rooftop zoom"
    (is (<= 6 (:zoom (view/default-map-opts)) 9))))

(deftest default-map-opts-no-token
  (testing "the dev basemap style is a plain https URL with no vendor secret"
    (let [style (:style (view/default-map-opts))]
      (is (string? style))
      (is (re-find #"^https://" style))
      (is (not (re-find #"(?i)token|api[-_]?key|access[-_]?token|[?&]key=" style))
          "no token or key may ever reach the browser bundle")))

  (testing "attribution is enabled — the basemap must credit its source"
    (is (true? (:attributionControl (view/default-map-opts))))))

(deftest shell-mounts-and-inits-map-through-seam
  (testing "mounting the shell creates exactly one map with the default opts"
    (let [calls (atom [])]
      (with-redefs [maplibre/create! (fn [_container opts]
                                       (swap! calls conj opts)
                                       (fake-map))]
        (rdom/render [views/app-root] @!node)
        (r/flush) ;; Reagent batches renders; force the mount to run now.
        ;; NB: reagent 1.2.0 exposes `flush`, not `flush!` (the name in the docs).

        (is (= 1 (count @calls)) "the map is created once, on mount")
        (let [opts (first @calls)]
          (is (= [-82.0 28.0] (:center opts)))
          (is (= view/default-zoom (:zoom opts)))
          (is (not (re-find #"(?i)token|api[-_]?key" (:style opts)))
              "no token crosses the seam either"))))))
