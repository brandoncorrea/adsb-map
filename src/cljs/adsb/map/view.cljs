(ns adsb.map.view
  (:require [adsb.corejs :as cjs]
            [adsb.map.aircraft-layer :as aircraft-layer]
            [adsb.map.basemap :as basemap]
            [adsb.map.crop :as crop]
            [adsb.map.emergency :as emergency]
            [adsb.map.follow :as follow]
            [adsb.map.maplibre :as maplibre]
            [adsb.map.selection :as selection]
            [adsb.map.theme :as theme]
            [adsb.worker :as worker]
            [clojure.math :as math]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def ^:const style-url "https://tiles.openfreemap.org/styles/liberty")
(def ^:const default-center [-82.0 28.0])
(def ^:const default-zoom 7)
(def ^:const style-fetch-timeout-ms 10000)
(def ^:const style-fetch-retries 3)
(defn retry-delay-ms [n] (* 800 (math/pow 2.0 n)))

(defn- fetch-style-once! [url]
  (let [controller (js/AbortController.)
        timer      (worker/timeout #(.abort controller) style-fetch-timeout-ms)]
    (-> (js/fetch url (js-obj "signal" (.-signal controller)))
        (.then (fn [res]
                 (if (.-ok res)
                   (.json res)
                   (throw (js/Error. (str "basemap style HTTP " (.-status res)))))))
        (.then (fn [json] (js->clj json :keywordize-keys true)))
        (.finally #(worker/clear-timeout timer)))))

(defn retry-fetch! [attempt! retries delay-ms on-ok on-fail]
  (letfn [(go [n]
            (.then (attempt!)
                   on-ok
                   (fn [err]
                     (if (< n retries)
                       (worker/timeout #(go (inc n)) (delay-ms n))
                       (do (js/console.error "basemap style fetch failed" err)
                           (on-fail err))))))]
    (go 0)))

(defn load-style! [url on-ok on-fail]
  (retry-fetch! #(fetch-style-once! url)
                style-fetch-retries
                retry-delay-ms
                on-ok
                on-fail))

(defn default-map-opts [style]
  {:style              style
   :center             default-center
   :zoom               default-zoom
   :attributionControl {:compact true}})

(def ^:private attribution-selector ".maplibregl-ctrl-attrib")
(def ^:private attribution-open-class "maplibregl-compact-show")
(def ^:const attribution-fold-ms 5000)

(defn collapse-attribution! [container]
  (when-let [control (some-> container (cjs/select attribution-selector))]
    (.remove (.-classList control) attribution-open-class)
    control))

(defonce ^:private !live-map (atom nil))

(rf/reg-fx
  :map/fly-to
  (fn [position]
    (when-let [m @!live-map]
      (maplibre/fly-to! m position))))

(defn map-container [!container]
  [:div.adsb-map {:ref #(reset! !container %)}])

(defn map-view []
  (let [!container  (atom nil)
        !map        (atom nil)
        !crop       (atom nil)
        !aircraft   (atom nil)
        !ring       (atom nil)
        !follow     (atom nil)
        !emergency  (atom nil)
        !raw-style  (atom nil)
        !unwatch    (atom nil)
        !fold-timer (atom nil)
        !disposed   (atom false)]
    (letfn [(mount-map!
              ([th] (mount-map! th nil))
              ([th camera]
               (let [style (basemap/edition-style @!raw-style th)
                     m     (maplibre/create! @!container
                                             (merge (default-map-opts style)
                                                    camera))]
                 (reset! !map m)
                 (reset! !live-map m)
                 (maplibre/on-load! m #(rf/dispatch [:map/ready]))
                 (reset! !fold-timer
                         (worker/timeout #(when-not @!disposed
                                            (collapse-attribution! @!container))
                                         attribution-fold-ms))
                 (reset! !crop (crop/attach! m th {:framed? (some? camera)}))
                 (reset! !aircraft (aircraft-layer/attach! m th))
                 (reset! !ring (selection/attach! m))
                 (reset! !follow (follow/attach! m))
                 (reset! !emergency (emergency/attach! m)))))
            (unmount-map! []
              (when-let [timer @!fold-timer]
                (worker/clear-timeout timer)
                (reset! !fold-timer nil))
              (when-let [annotations @!emergency]
                (emergency/detach! @!map annotations)
                (reset! !emergency nil))
              (when-let [cam @!follow]
                (follow/detach! cam)
                (reset! !follow nil))
              (when-let [ring @!ring]
                (selection/detach! @!map ring)
                (reset! !ring nil))
              (when-let [layer @!aircraft]
                (aircraft-layer/detach! layer)
                (reset! !aircraft nil))
              (when-let [boundary @!crop]
                (crop/detach! boundary)
                (reset! !crop nil))
              (when-let [m @!map]
                (maplibre/destroy! m)
                (reset! !map nil)
                (reset! !live-map nil)))
            (reprint! [th]
              (theme/set-theme! th)
              (when (and @!raw-style (not @!disposed))
                (let [camera (some-> @!map maplibre/camera)]
                  (unmount-map!)
                  (mount-map! th camera))))]
      (r/create-class
        {:display-name "adsb-map"

         :component-did-mount
         (fn [_this]
           (reset! !unwatch (theme/watch-system! reprint!))
           (load-style! style-url
                        (fn [raw]
                          (when-not @!disposed
                            (reset! !raw-style raw)
                            (mount-map! (theme/sync!))))
                        (fn [_err]
                          (when-not @!disposed
                            (rf/dispatch [:map/load-failed])))))

         :component-will-unmount
         (fn [_this]
           (reset! !disposed true)
           (when-let [unwatch @!unwatch]
             (unwatch)
             (reset! !unwatch nil))
           (unmount-map!))

         :reagent-render
         (fn [] (map-container !container))}))))
