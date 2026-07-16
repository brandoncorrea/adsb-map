(ns adsb.map.follow
  (:require [adsb.map.maplibre :as maplibre]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(defn following? [mode] (= :follow mode))

(defn should-track? [mode aircraft]
  (boolean (and (following? mode)
                aircraft
                (:aircraft/position aircraft))))

(defn attach! [m]
  (let [!disposed (atom false)
        !last-pos (atom nil)
        !entering (atom false)
        !pending  (atom nil)
        selected  (rf/subscribe [:aircraft/selected])
        mode      (rf/subscribe [:map/camera-mode])
        track     (r/track!
                    (fn []
                      (when-not @!disposed
                        (let [mode* @mode
                              ac    @selected]
                          (if (should-track? mode* ac)
                            (let [pos (:aircraft/position ac)]
                              (cond
                                (nil? @!last-pos)
                                (do (reset! !last-pos pos)
                                    (reset! !entering true)
                                    (reset! !pending nil)
                                    (maplibre/fly-to! m pos))

                                @!entering
                                (reset! !pending pos)

                                (not= pos @!last-pos)
                                (do (reset! !last-pos pos)
                                    (maplibre/ease-to! m pos))))
                            (do (reset! !last-pos nil)
                                (reset! !entering false)
                                (reset! !pending nil)))))))]
    (maplibre/on-move! m
                       (fn []
                         (when-not @!disposed
                           (when @!entering
                             (reset! !entering false)
                             (when-let [pos @!pending]
                               (reset! !pending nil)
                               (when (not= pos @!last-pos)
                                 (reset! !last-pos pos)
                                 (maplibre/ease-to! m pos)))))))
    (maplibre/on-drag-start! m
                             (fn []
                               (when-not @!disposed
                                 (reset! !entering false)
                                 (reset! !pending nil)
                                 (rf/dispatch [:map/user-pan]))))
    {:track track :!disposed !disposed}))

(defn detach! [{:keys [track !disposed]}]
  (reset! !disposed true)
  (some-> track r/dispose!))
