(ns adsb.map.theme
  (:require [adsb.corejs :as cjs]
            [reagent.core :as r]))

(def ^:const dark-scheme-query "(prefers-color-scheme: dark)")

(defn system-theme []
  (if (.-matches (cjs/match-media dark-scheme-query))
    :night
    :day))

(defonce !theme (r/atom :day))

(defn set-theme! [theme] (reset! !theme theme))

(defn sync! []
  (doto (system-theme) set-theme!))

(defn watch-system! [f]
  (let [mql     (cjs/match-media dark-scheme-query)
        handler (fn [_e] (f (if (.-matches mql) :night :day)))]
    (cjs/add-listener! mql "change" handler)
    #(cjs/remove-listener! mql "change" handler)))
