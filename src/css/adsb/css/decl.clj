(ns adsb.css.decl
  (:require [flatland.ordered.map :refer [ordered-map]]))

(defn decl
  "CSS declarations, in the order written.
   Use ONLY when CSS ordering matters, otherwise use regular maps."
  [& kvs]
  (apply ordered-map kvs))
