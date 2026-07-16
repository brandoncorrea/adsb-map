(ns build
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/adsb.jar")
(def ^:private main-ns 'adsb.main)

(defn- basis []
  (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path class-dir})
  (b/delete {:path uber-file}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs   ["resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis      (basis)
                  :ns-compile [main-ns]
                  :class-dir  class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     (basis)
           :main      main-ns})
  (println "Wrote" uber-file))
