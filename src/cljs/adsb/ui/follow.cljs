(ns adsb.ui.follow
  (:require [adsb.ui.icon :refer [icon]]
            [re-frame.core :as rf]))

(defn- toggle-follow! [] (rf/dispatch [:map/toggle-follow]))

(defn follow-control []
  (let [selected    (rf/subscribe [:aircraft/selected])
        camera-mode (rf/subscribe [:map/camera-mode])]
    (fn []
      (when (:aircraft/position @selected)
        (let [following? (= :follow @camera-mode)]
          [:button.adsb-follow-control
           {:type         "button"
            :aria-pressed following?
            :aria-label   (if following? "Stop following" "Follow aircraft")
            :title        (if following? "Following — pan to free" "Follow")
            :data-testid  "camera-follow"
            :data-camera  (if following? "follow" "free")
            :class        (when following? "is-active")
            :on-click     toggle-follow!}
           [icon :crosshairs]])))))
