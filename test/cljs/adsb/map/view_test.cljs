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
    [adsb.map.crop :as crop]
    [adsb.map.maplibre :as maplibre]
    [adsb.map.theme :as theme]
    [adsb.map.view :as view]
    [adsb.test-dom :as test-dom]
    [adsb.views :as views]
    [cljs.test :refer-macros [deftest is testing use-fixtures async]]))

;; A throwaway DOM node per mounting test, cleaned up after each — and the
;; theme ratom restored, since the mount path syncs it. The root is held
;; alongside the node: a React 18 root is unmounted through the handle
;; create-root returned, not by naming the node again.
(def ^:private !node (atom nil))
(def ^:private !root (atom nil))

(use-fixtures :each
  {:before (fn []
             (let [el (.createElement js/document "div")]
               (.appendChild (.-body js/document) el)
               (reset! !node el)))
   :after  (fn []
             (when-let [root @!root]
               (test-dom/unmount! root)
               (reset! !root nil))
             (when-let [el @!node]
               (.remove el)
               (reset! !node nil))
             (theme/set-theme! :day))})

(defn- mount-shell!
  "Mount the app shell into this test's node, remembering the root so the
  fixture can tear it down even if the test never unmounts by hand."
  []
  (reset! !root (test-dom/mount! [views/app-root] @!node)))

;; A minimal basemap style, as `load-style!` would deliver it — one
;; background layer, so the edition print is observable on its paint.
(def ^:private raw-style
  {:version 8
   :sources {}
   :layers  [{:id "background" :type "background"
              :paint {:background-color "#dddddd"}}]})

;; The seam a mounting test wants: hand the style straight to on-ok, no network,
;; no retries, and never the failure path.
(defn- stub-load-style! [_url on-ok _on-fail] (on-ok raw-style))

;; What a reader who has gone somewhere looks like: panned off the boot
;; centre and zoomed in to inspect distance. A theme flip must leave this
;; exactly where it is (adsb-1rg).
(def ^:private readers-camera
  {:center [-82.53 27.97] :zoom 12 :bearing 30 :pitch 0})

(defn- fake-map
  "A `Map` that records its destruction, reports a camera, and records any
  fit-bounds — otherwise inert, but satisfying the full protocol, so a
  mount can create and later destroy it without touching WebGL. The load
  event never fires here, so the aircraft layer stays dormant — its
  behavior is adsb.map.aircraft-layer-test's business.

  `camera` is what this map says the reader is looking at; the theme flip
  test hands the first map a moved camera and then asserts the second map
  was created on it."
  ([!destroyed] (fake-map !destroyed nil nil))
  ([!destroyed camera !fits]
   (reify maplibre/Map
     (destroy! [_] (swap! !destroyed inc))
     (camera [_] camera)
     (fit-bounds! [_ bounds padding]
       (when !fits (swap! !fits conj {:bounds bounds :padding padding})))
     (on-load! [_ _f] nil)
     (add-source! [_ _id _source] nil)
     (add-layer! [_ _layer] nil)
     (set-source-data! [_ _id _data] nil)
     (add-image! [_ _id _image _opts] nil)
     (on-layer-click! [_ _layer-id _f] nil)
     (on-layer-dblclick! [_ _layer-id _f] nil)
     (on-layer-hover! [_ _layer-id _on-enter _on-leave] nil)
     (add-marker! [_ _element _lng-lat] nil)
     (move-marker! [_ _marker _lng-lat] nil)
     (remove-marker! [_ _marker] nil)
     ;; A calm sky never reads bounds, but the protocol must be whole.
     (bounds [_] {:geo/min-lat 27.0 :geo/max-lat 29.0
                  :geo/min-lon -83.0 :geo/max-lon -81.0})
     (fly-to! [_ _lng-lat] nil)
     (ease-to! [_ _lng-lat] nil)
     (on-move! [_ _f] nil)
     (on-drag-start! [_ _f] nil))))

;; ---------------------------------------------------------------------
;; The style fetch is bounded and retried, and fails LOUDLY, not forever — the
;; mobile bug where one flaked request left the map uncreated and the splash
;; stuck (adsb-4oh). retry-fetch! is the engine, driven here with an INJECTED
;; thunk (no network, no wire), and a zero delay so the async proofs are fast.
;; The engine is tested directly rather than through load-style! so nothing
;; needs to redef the fetch across the async retry boundary — a with-redefs
;; would unwind before the retry fires and leak onto the real network.

(deftest retry-backoff-grows
  (testing "each retry waits strictly longer than the last — backoff, never a
            tight refetch loop against a struggling provider"
    (is (< (view/retry-delay-ms 0)
           (view/retry-delay-ms 1)
           (view/retry-delay-ms 2)))
    (is (pos? (view/retry-delay-ms 0)) "and the first wait is real, not zero")))

(deftest retry-fetch-recovers-from-a-flake
  (testing "a first rejection is retried, and the eventual success reaches
            on-ok while on-fail never fires"
    (async done
      (let [attempts (atom 0)]
        (view/retry-fetch!
          (fn []
            (let [n (swap! attempts inc)]
              (if (= n 1)
                (js/Promise.reject (js/Error. "cold radio flake"))
                (js/Promise.resolve raw-style))))
          view/style-fetch-retries
          (constantly 0)
          (fn [style]
            (is (= raw-style style) "on-ok gets the style the retry fetched")
            (is (= 2 @attempts) "one flake, one success — exactly one retry")
            (done))
          (fn [_err]
            (is false "on-fail must not run when a retry recovers")
            (done)))))))

(deftest retry-fetch-gives-up-after-exhausting-retries
  (testing "when every attempt rejects, on-fail runs once with the error and
            on-ok never does — the splash's terminal cue"
    (async done
      (let [attempts (atom 0)]
        (view/retry-fetch!
          (fn []
            (swap! attempts inc)
            (js/Promise.reject (js/Error. "provider down")))
          view/style-fetch-retries
          (constantly 0)
          (fn [_style]
            (is false "on-ok must never run when the style never arrives")
            (done))
          (fn [err]
            (is (= (inc view/style-fetch-retries) @attempts)
                "the first shot plus every retry were all tried, then it gave up")
            (is (some? err) "on-fail carries the failure, not nil")
            (done)))))))

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

(deftest the-credit-is-shown-before-it-is-folded
  (testing "THE FIVE SECONDS ARE THE LICENCE, not a taste. The OSMF attribution
            guidelines allow the credit to be folded behind an (i), and name the
            only three things that may fold it: a dismiss interaction, a map
            interaction, or a timeout of FIVE SECONDS — each of which presumes
            the credit was SHOWN first. A map that opens with the credit already
            folded is on none of that list. So this constant may not go to zero,
            and may not be shortened: it is OpenStreetMap's five seconds, not
            ours"
    (is (= 5000 view/attribution-fold-ms)
        "the timeout the guidelines name, exactly")
    (is (pos? view/attribution-fold-ms)
        "and never zero — the credit is never folded on load")))

(deftest folding-the-credit-leaves-it-one-tap-away
  ;; Folding is not hiding: the (i) button remains, and its own handler toggles
  ;; the very class we drop.
  (let [container (js/document.createElement "div")
        attrib    (js/document.createElement "div")]
    (set! (.-className attrib)
          "maplibregl-ctrl maplibregl-ctrl-attrib maplibregl-compact maplibregl-compact-show")
    (.appendChild container attrib)

    (testing "the open-class is dropped, so the credit folds shut"
      (view/collapse-attribution! container)
      (is (not (.contains (.-classList attrib) "maplibregl-compact-show"))
          "shut"))

    (testing "the (i) button survives — the credit is one tap away, not gone"
      (is (.contains (.-classList attrib) "maplibregl-compact")
          "the compact class is untouched, so MapLibre still draws the button
           AND still declines to re-open the credit behind our back (its own
           re-open path fires only when this class is absent)")
      (is (.contains (.-classList attrib) "maplibregl-ctrl-attrib")
          "and the control itself is still in the DOM"))
))

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
        (mount-shell!)

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
        (mount-shell!)
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
          (test-dom/unmount! @!root)
          (reset! !root nil) ;; torn down here; the fixture must not do it twice
          (is (= 1 @!unwatched))
          (is (= 2 @!destroyed)))))))

(deftest a-theme-flip-keeps-the-readers-place
  ;; One mount, one flip, and everything the reader owns must survive it: the
  ;; camera, and the fact that the chart's opening frame is already behind
  ;; them. The crop is faked here — this asserts the WIRING; that a seeded
  ;; latch actually fires no fit-bounds is adsb.map.crop-test's proof.
  (let [calls      (atom [])
        !destroyed (atom 0)
        !fits      (atom [])
        !flip      (atom nil)
        !crop-opts (atom [])]
    (with-redefs [maplibre/create!    (fn [_container opts]
                                        (swap! calls conj opts)
                                        ;; Every map this test builds reports a
                                        ;; reader who has gone somewhere.
                                        (fake-map !destroyed readers-camera
                                                  !fits))
                  view/load-style!    stub-load-style!
                  theme/system-theme  (constantly :day)
                  theme/watch-system! (fn [f] (reset! !flip f) (fn []))
                  crop/attach!        (fn
                                        ([m] (crop/attach! m :day nil))
                                        ([m th] (crop/attach! m th nil))
                                        ([_m _th opts]
                                         (swap! !crop-opts conj opts)
                                         (atom {})))
                  crop/detach!        (fn [_handle] nil)]
      (mount-shell!)

      (testing "the boot map still opens on the fixed regional fallback, with
                the boundary's opening frame still owed"
        (is (= view/default-center (:center (first @calls))))
        (is (not (:framed? (first @!crop-opts)))))

      (@!flip :night)

      (testing "the night print is created ON the dying map's camera — dusk
                changes the ink, not where the reader is looking (adsb-1rg)"
        (let [opts (last @calls)]
          (is (= 2 (count @calls)) "the map was re-printed")
          (is (= (:center readers-camera) (:center opts))
              "the reader's centre survived the re-print")
          (is (= (:zoom readers-camera) (:zoom opts))
              "and their zoom — no teleport back to the boot framing")
          (is (= (:bearing readers-camera) (:bearing opts)))))

      (testing "and the boundary comes up ALREADY framed, so nothing pulls the
                chart back onto the crop mid-session"
        (is (true? (:framed? (last @!crop-opts)))))

      (testing "nothing framed anything, on either print"
        (is (empty? @!fits))))))
