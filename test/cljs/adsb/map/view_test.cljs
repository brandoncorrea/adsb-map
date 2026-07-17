(ns adsb.map.view-test
  (:require [adsb.corejs :as cjs]
            [adsb.map.basemap :as basemap]
            [adsb.map.crop :as crop]
            [adsb.map.maplibre :as maplibre]
            [adsb.map.theme :as theme]
            [adsb.map.view :as view]
            [adsb.test-dom :as test-dom]
            [adsb.views :as views]
            [clojure.math :as math]
            [clojure.test :refer-macros [deftest is testing use-fixtures async]]))

(def ^:private !node (atom nil))
(def ^:private !root (atom nil))

(use-fixtures :each
  {:before (fn []
             (let [el (cjs/create-element "div")]
               (cjs/append-child (.-body js/document) el)
               (reset! !node el)))
   :after  (fn []
             (when-let [root @!root]
               (test-dom/unmount! root)
               (reset! !root nil))
             (when-let [el @!node]
               (cjs/remove! el)
               (reset! !node nil))
             (theme/set-theme! :day))})

(defn- mount-shell! []
  (reset! !root (test-dom/mount! [views/app-root] @!node)))

(def ^:private raw-style
  {:version 8
   :sources {}
   :layers  [{:id    "background"
              :type  "background"
              :paint {:background-color "#dddddd"}}]})

(defn- stub-load-style! [_url on-ok _on-fail] (on-ok raw-style))

(def ^:private readers-camera
  {:center  [-82.53 27.97]
   :zoom    12
   :bearing 30
   :pitch   0})

(defn- fake-map
  ([!destroyed] (fake-map !destroyed nil nil))
  ([!destroyed camera !fits]
   (reify maplibre/Map
     (destroy! [_] (swap! !destroyed inc))
     (camera [_] camera)
     (fit-bounds! [_ bounds padding]
       (when !fits (swap! !fits conj {:bounds bounds :padding padding})))
     (on-load! [_ _f])
     (add-source! [_ _id _source])
     (add-layer! [_ _layer])
     (set-source-data! [_ _id _data])
     (add-image! [_ _id _image _opts])
     (on-layer-click! [_ _layer-id _f])
     (on-layer-dblclick! [_ _layer-id _f])
     (on-layer-hover! [_ _layer-id _on-enter _on-leave])
     (add-marker! [_ _element _lng-lat])
     (move-marker! [_ _marker _lng-lat])
     (remove-marker! [_ _marker])
     (bounds [_]
       {:geo/min-lat 27.0
        :geo/max-lat 29.0
        :geo/min-lon -83.0
        :geo/max-lon -81.0})
     (fly-to! [_ _lng-lat])
     (ease-to! [_ _lng-lat])
     (on-move! [_ _f])
     (on-drag-start! [_ _f]))))

(deftest retry-backoff-grows
  (testing "each retry waits strictly longer than the last — backoff, never a
            tight refetch loop against a struggling provider"
    (is (< (view/retry-delay-ms 0)
           (view/retry-delay-ms 1)
           (view/retry-delay-ms 2)))
    (is (pos? (view/retry-delay-ms 0)))))

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
            (is (= raw-style style))
            (is (= 2 @attempts))
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
            (is (= (inc view/style-fetch-retries) @attempts))
            (is (some? err))
            (done)))))))

(deftest default-map-opts-privacy
  (testing "default center is a fixed, whole-degree regional point — never a receiver"
    (let [{:keys [center]} (view/default-map-opts raw-style)]
      (is (= [-82.0 28.0] center))
      (is (every? #(== % (math/round %)) center))))

  (testing "regional zoom, not a rooftop zoom"
    (is (<= 6 (:zoom (view/default-map-opts raw-style)) 9))))

(deftest style-url-carries-no-token
  (testing "the basemap style is fetched from a plain https URL with no vendor secret"
    (is (re-find #"^https://" view/style-url))
    (is (not (re-find #"(?i)token|api[-_]?key|access[-_]?token|[?&]key=" view/style-url))))

  (testing "attribution is enabled — the basemap must credit its source"
    (let [attribution (:attributionControl (view/default-map-opts raw-style))]
      (is (map? attribution))
      (is (:compact attribution)))))

(deftest the-credit-is-shown-before-it-is-folded
  (testing "THE FIVE SECONDS ARE THE LICENCE, not a taste. The OSMF attribution
            guidelines allow the credit to be folded behind an (i), and name the
            only three things that may fold it: a dismiss interaction, a map
            interaction, or a timeout of FIVE SECONDS — each of which presumes
            the credit was SHOWN first. A map that opens with the credit already
            folded is on none of that list. So this constant may not go to zero,
            and may not be shortened: it is OpenStreetMap's five seconds, not ours"
    (is (= 5000 view/attribution-fold-ms))
    (is (pos? view/attribution-fold-ms))))

(deftest folding-the-credit-leaves-it-one-tap-away
  (let [container (cjs/create-element "div")
        attrib    (cjs/create-element "div")]
    (set! (.-className attrib) "maplibregl-ctrl maplibregl-ctrl-attrib maplibregl-compact maplibregl-compact-show")
    (cjs/append-child container attrib)

    (testing "the open-class is dropped, so the credit folds shut"
      (view/collapse-attribution! container)
      (is (not (.contains (.-classList attrib) "maplibregl-compact-show"))))

    (testing "the (i) button survives — the credit is one tap away, not gone"
      (is (.contains (.-classList attrib) "maplibregl-compact"))
      (is (.contains (.-classList attrib) "maplibregl-ctrl-attrib")))))

(deftest folding-the-credit-fails-safe
  (testing "if MapLibre ever renames these classes, the fold quietly does
            nothing and the attribution simply stays OPEN — the failure mode
            leaves the credit visible, never missing"
    (let [empty-container (cjs/create-element "div")]
      (is (nil? (view/collapse-attribution! empty-container))))))

(deftest shell-mounts-and-inits-map-through-seam
  (testing "mounting the shell creates exactly one map, printed in the current edition"
    (let [calls      (atom [])
          !destroyed (atom 0)]
      (with-redefs [maplibre/create!   (fn [_container opts]
                                         (swap! calls conj opts)
                                         (fake-map !destroyed))
                    view/load-style!   stub-load-style!
                    theme/system-theme (constantly :day)]
        (mount-shell!)

        (is (= 1 (count @calls)))
        (let [opts (first @calls)]
          (is (= [-82.0 28.0] (:center opts)))
          (is (= view/default-zoom (:zoom opts)))
          (is (= (basemap/edition-style raw-style :day) (:style opts))))))))

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
        (is (some? @!flip))
        (is (= 1 (count @calls)))

        (@!flip :night)
        (is (= 1 @!destroyed))
        (is (= 2 (count @calls)))
        (is (= (basemap/edition-style raw-style :night) (:style (last @calls))))
        (is (= :night @theme/!theme))

        (testing "unmount removes the watch and destroys the night print"
          (test-dom/unmount! @!root)
          (reset! !root nil)
          (is (= 1 @!unwatched))
          (is (= 2 @!destroyed)))))))

(deftest a-theme-flip-keeps-the-readers-place
  (let [calls      (atom [])
        !destroyed (atom 0)
        !fits      (atom [])
        !flip      (atom nil)
        !crop-opts (atom [])]
    (with-redefs [maplibre/create!    (fn [_container opts]
                                        (swap! calls conj opts)
                                        (fake-map !destroyed readers-camera !fits))
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
          (is (= 2 (count @calls)))
          (is (= (:center readers-camera) (:center opts)))
          (is (= (:zoom readers-camera) (:zoom opts)))
          (is (= (:bearing readers-camera) (:bearing opts)))))

      (testing "and the boundary comes up ALREADY framed, so nothing pulls the
                chart back onto the crop mid-session"
        (is (:framed? (last @!crop-opts))))

      (testing "nothing framed anything, on either print"
        (is (empty? @!fits))))))
