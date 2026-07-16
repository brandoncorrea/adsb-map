(ns adsb.css.app
  (:require [adsb.css.alerts :as alerts]
            [adsb.css.captions :as captions]
            [adsb.css.emergency :as emergency]
            [adsb.css.icon :as icon]
            [adsb.css.motion :as motion]
            [adsb.css.panel :as panel]
            [adsb.css.phone :as phone]
            [adsb.css.roster :as roster]
            [adsb.css.shell :as shell]
            [adsb.css.splash :as splash]
            [adsb.css.tokens :as tokens]))

(def stylesheet
  [tokens/styles
   motion/styles
   shell/styles
   splash/styles
   icon/styles
   alerts/styles
   panel/styles
   emergency/styles
   roster/styles

   ;; ORDER-CRITICAL from here down. See the namespace docstring.
   captions/styles
   phone/styles
   motion/reduced-motion])
