(ns adsb.corejs
  (:require [clojure.string :as str]))

(defn select
  ([selector] (js-invoke js/document "querySelector" selector))
  ([node selector] (js-invoke node "querySelector" selector)))

(defn set-attribute! [node attribute value]
  (js-invoke node "setAttribute" attribute value))

(defn set-attributes! [node attr-map]
  (doseq [[k v] attr-map]
    (js-invoke node "setAttribute" k v)))

(defn add-listener!
  ([event listener] (js-invoke js/document "addEventListener" event listener))
  ([node event listener] (js-invoke node "addEventListener" event listener)))

(defn remove-listener! [node event listener]
  (js-invoke node "removeEventListener" event listener))

(defn create-element [tag-name]
  (js-invoke js/document "createElement" tag-name))

(defn create-element-ns [the-ns the-name]
  (js-invoke js/document "createElementNS" the-ns the-name))

(defn append-child! [node child]
  (js-invoke node "appendChild" child))

(defn append-children! [node children]
  (doseq [child children]
    (js-invoke node "appendChild" child)))

(defn get-property [node property-name]
  (js-invoke node "getPropertyValue" property-name))

(defn get-attribute [node attribute-name]
  (js-invoke node "getAttribute" attribute-name))

(defn closest [node selector]
  (js-invoke node "closest" selector))

(defn scroll-into-view! [node js-opts]
  (js-invoke node "scrollIntoView" js-opts))

(defn element-by-id [id]
  (js-invoke js/document "getElementById" id))

(defn refresh! []
  (js-invoke (.-location js/window) "reload"))

(defn remove! [node]
  (js-invoke node "remove"))

(defn add-class! [node class-name]
  (js-invoke (.-classList node) "add" class-name))

(defn has-class? [node class-name]
  (js-invoke (.-classList node) "contains" class-name))

(defn css-px [prop]
  (let [raw (some-> js/document
                    .-documentElement
                    js/getComputedStyle
                    (get-property prop)
                    str
                    str/trim)
        n   (js/parseFloat raw)]
    (if (js/isFinite n)
      n
      0)))

(defn <-json [data]
  (-> (js-invoke js/JSON "parse" data)
      (js->clj :keywordize-keys true)))

(defn match-media [query] (js-invoke js/window "matchMedia" query))

(defn matches-media? [media]
  (and (exists? js/window)
       (.-matchMedia js/window)
       (.-matches (match-media media))))

(defn prefers-reduced-motion? []
  (matches-media? "(prefers-reduced-motion: reduce)"))

(defn phone-stance? []
  (matches-media? "(max-width: 640px)"))

(defn performance-now []
  (js-invoke js/performance "now"))

(defn now-ms []
  (js-invoke js/Date "now"))

(defn prevent-default! [node]
  (js-invoke node "preventDefault"))

(defn request-animation [f]
  (js/requestAnimationFrame f))

(defn cancel-animation [id]
  (js/cancelAnimationFrame id))
