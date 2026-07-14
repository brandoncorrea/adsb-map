(ns adsb.ui.follow
  "Free / Follow reticle on the chart (adsb-jg4).

  Map chrome, not panel chrome: the control sits above MapLibre's
  attribution (i) in the bottom-right corner. It only appears when a
  selected aircraft has a position — without one there is nowhere to
  center. Pan still releases follow via adsb.map.follow / :map/user-pan.

  THE STATE IS IN THE INK, NOT IN THE DRAWING (adsb.ui.icon). The reticle used
  to be a hand-rolled svg whose centre dot FILLED when following — the state
  was drawn into the geometry, so free and follow were two different pictures.
  One picture now, and the `is-active` class recolours it. The button already
  carries `aria-pressed` and a title that says which way it is pointing, so
  the colour is the third channel saying it, not the only one."
  (:require [adsb.ui.icon :refer [icon]]
            [re-frame.core :as rf]))

(defn- toggle-follow!
  [_event]
  (rf/dispatch [:map/toggle-follow]))

(defn follow-control
  "The chart's Free / Follow button. Renders nothing unless a selected
  aircraft has a lat/lon to track."
  []
  (let [selected    (rf/subscribe [:aircraft/selected])
        camera-mode (rf/subscribe [:map/camera-mode])]
    (fn []
      (when-let [aircraft @selected]
        (when (:aircraft/position aircraft)
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
             [icon :crosshairs]]))))))
