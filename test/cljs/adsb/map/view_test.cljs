(ns adsb.map.view-test
  "The app shell mounts, and it initializes the map through the seam with the
  options we expect. We never build a real MapLibre map — `create!` is faked —
  and we never fetch the real basemap style: `load-style!` is stubbed to hand
  back a fixture synchronously. So this asserts the things we own: the options
  handed across the seam (a fixed regional center, a token-free style URL),
  the style printed in the CURRENT edition, and the re-print when the system
  theme flips. See docs/testing-setup.md."
  (:require
    [adsb.map.basemap :as basemap]
    [adsb.map.maplibre :as maplibre]
    [adsb.map.theme :as theme]
    [adsb.map.view :as view]
    [adsb.views :as views]
    [cljs.test :refer-macros [deftest is testing use-fixtures]]
    [reagent.core :as r]
    [reagent.dom :as rdom]))

;; A throwaway DOM node per mounting test, cleaned up after each — and the
;; theme ratom restored, since the mount path syncs it.
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
               (reset! !node nil))
             (theme/set-theme! :day))})

;; A minimal basemap style, as `load-style!` would deliver it — one
;; background layer, so the edition print is observable on its paint.
(def ^:private raw-style
  {:version 8
   :sources {}
   :layers  [{:id "background" :type "background"
              :paint {:background-color "#dddddd"}}]})

(defn- stub-load-style! [_url cb] (cb raw-style))

(defn- fake-map
  "A `Map` that records only its destruction but satisfies the full
  protocol, so a mount can create and later destroy it without touching
  WebGL. The load event never fires here, so the aircraft layer stays
  dormant — its behavior is adsb.map.aircraft-layer-test's business."
  [!destroyed]
  (reify maplibre/Map
    (destroy! [_] (swap! !destroyed inc))
    (on-load! [_ _f] nil)
    (add-source! [_ _id _source] nil)
    (add-layer! [_ _layer] nil)
    (set-source-data! [_ _id _data] nil)
    (add-image! [_ _id _image _opts] nil)
    (on-layer-click! [_ _layer-id _f] nil)
    (on-layer-hover-cursor! [_ _layer-id] nil)
    (add-marker! [_ _element _lng-lat] nil)
    (move-marker! [_ _marker _lng-lat] nil)
    (remove-marker! [_ _marker] nil)
    ;; A calm sky never reads bounds, but the protocol must be whole.
    (bounds [_] {:geo/min-lat 27.0 :geo/max-lat 29.0
                 :geo/min-lon -83.0 :geo/max-lon -81.0})
    (on-move! [_ _f] nil)))

(deftest default-map-opts-privacy
  (testing "default center is a fixed, whole-degree regional point — never a receiver"
    (let [{:keys [center]} (view/default-map-opts raw-style)]
      ;; MapLibre order is [lon lat]: lon -82.0, lat 28.0 (Florida Gulf coast).
      (is (= [-82.0 28.0] center))
      (is (every? #(== % (js/Math.round %)) center)
          "whole-degree only — a rooftop's precision must never appear here")))

  (testing "regional zoom, not a rooftop zoom"
    (is (<= 6 (:zoom (view/default-map-opts raw-style)) 9))))

(deftest style-url-carries-no-token
  (testing "the basemap style is fetched from a plain https URL with no vendor secret"
    (is (re-find #"^https://" view/style-url))
    (is (not (re-find #"(?i)token|api[-_]?key|access[-_]?token|[?&]key="
                      view/style-url))
        "no token or key may ever reach the browser bundle"))

  (testing "attribution is enabled — the basemap must credit its source"
    (let [attribution (:attributionControl (view/default-map-opts raw-style))]
      (is (map? attribution)
          "the control is configured, never switched off")
      (is (not (false? attribution))
          "OpenFreeMap / OpenMapTiles / OpenStreetMap must be credited")
      (is (true? (:compact attribution))
          "as an (i) button rather than a banner — folded, never removed"))))

(deftest the-credit-starts-folded-but-stays-reachable
  ;; MapLibre's compact control is compact and OPEN: it sets the open-class on
  ;; add and only collapses on the reader's first map interaction, so the credit
  ;; greets every session as a banner across the map's bottom edge. We fold it —
  ;; and folding is not hiding: the (i) button remains, and its own handler
  ;; toggles the very class we drop.
  (let [container (js/document.createElement "div")
        attrib    (js/document.createElement "div")]
    (set! (.-className attrib)
          "maplibregl-ctrl maplibregl-ctrl-attrib maplibregl-compact maplibregl-compact-show")
    (.appendChild container attrib)

    (testing "the open-class is dropped, so the credit renders folded"
      (view/collapse-attribution! container)
      (is (not (.contains (.-classList attrib) "maplibregl-compact-show"))
          "it starts shut"))

    (testing "the (i) button survives — the credit is one tap away, not gone"
      (is (.contains (.-classList attrib) "maplibregl-compact")
          "the compact class is untouched, so MapLibre still draws the button
           AND still declines to re-open the credit behind our back (its own
           re-open path fires only when this class is absent)")
      (is (.contains (.-classList attrib) "maplibregl-ctrl-attrib")
          "and the control itself is still in the DOM"))))

(deftest folding-the-credit-fails-safe
  (testing "if MapLibre ever renames these classes, the fold quietly does
            nothing and the attribution simply stays OPEN — the failure mode
            leaves the credit visible, never missing"
    (let [empty-container (js/document.createElement "div")]
      (is (nil? (view/collapse-attribution! empty-container))
          "no attribution control found: nothing to fold, and nothing thrown"))))

(deftest shell-mounts-and-inits-map-through-seam
  (testing "mounting the shell creates exactly one map, printed in the current edition"
    (let [calls (atom [])
          !destroyed (atom 0)]
      (with-redefs [maplibre/create! (fn [_container opts]
                                       (swap! calls conj opts)
                                       (fake-map !destroyed))
                    view/load-style! stub-load-style!
                    theme/system-theme (constantly :day)]
        (rdom/render [views/app-root] @!node)
        (r/flush) ;; Reagent batches renders; force the mount to run now.
        ;; NB: reagent 1.2.0 exposes `flush`, not `flush!` (the name in the docs).

        (is (= 1 (count @calls)) "the map is created once, on mount")
        (let [opts (first @calls)]
          (is (= [-82.0 28.0] (:center opts)))
          (is (= view/default-zoom (:zoom opts)))
          (is (= (basemap/edition-style raw-style :day) (:style opts))
              "the fetched style crosses the seam printed in the day edition"))))))

(deftest system-theme-flip-reprints-the-map-in-the-other-edition
  (testing "the watch callback tears the day print down and mounts the night one"
    (let [calls      (atom [])
          !destroyed (atom 0)
          !flip      (atom nil)
          !unwatched (atom 0)]
      (with-redefs [maplibre/create!    (fn [_container opts]
                                          (swap! calls conj opts)
                                          (fake-map !destroyed))
                    view/load-style!    stub-load-style!
                    theme/system-theme  (constantly :day)
                    theme/watch-system! (fn [f]
                                          (reset! !flip f)
                                          (fn [] (swap! !unwatched inc)))]
        (rdom/render [views/app-root] @!node)
        (r/flush)
        (is (some? @!flip) "the mount registered a system-theme watch")
        (is (= 1 (count @calls)))

        (@!flip :night)
        (is (= 1 @!destroyed) "the day print was destroyed")
        (is (= 2 (count @calls)) "and a second map was created")
        (is (= (basemap/edition-style raw-style :night)
               (:style (last @calls)))
            "printed in the night edition")
        (is (= :night @theme/!theme)
            "the chrome-visible theme followed, so the legend re-inks")

        (testing "unmount removes the watch and destroys the night print"
          (rdom/unmount-component-at-node @!node)
          (is (= 1 @!unwatched))
          (is (= 2 @!destroyed)))))))
