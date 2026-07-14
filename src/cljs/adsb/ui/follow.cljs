(ns adsb.ui.follow
  "Free / Follow reticle on the chart (adsb-jg4).

  Map chrome, not panel chrome: the control sits above MapLibre's
  attribution (i) in the bottom-right corner. It only appears when a
  selected aircraft has a position — without one there is nowhere to
  center. Pan still releases follow via adsb.map.follow / :map/user-pan."
  (:require [re-frame.core :as rf]))

(defn- toggle-follow!
  [_event]
  (rf/dispatch [:map/toggle-follow]))

(defn- follow-glyph
  "Crosshair reticle — free is outline, follow fills the centre."
  [following?]
  [:svg.adsb-follow-glyph
   {:viewBox "0 0 16 16" :width 16 :height 16 :aria-hidden true
    :focusable "false"}
   [:circle {:cx 8 :cy 8 :r 5.25 :fill "none" :stroke "currentColor"
             :stroke-width 1.4}]
   [:path {:d "M8 1.25 V3.1 M8 12.9 V14.75 M1.25 8 H3.1 M12.9 8 H14.75"
           :fill "none" :stroke "currentColor" :stroke-width 1.4
           :stroke-linecap "round"}]
   [:circle {:cx 8 :cy 8 :r 1.6
             :fill (if following? "currentColor" "none")
             :stroke "currentColor" :stroke-width 1.2}]])

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
             [follow-glyph following?]]))))))
