(ns build
  "tools.build script for the deployable uberjar. `bb build` runs the
  shadow-cljs release first so resources/public/js holds the optimized
  frontend, then invokes `uber` here to AOT adsb.main and package
  everything — backend classes plus resources/ — into target/adsb.jar."
  (:require
    [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/adsb.jar")
(def ^:private main-ns 'adsb.main)

(defn- basis []
  (b/create-basis {:project "deps.edn"}))

(defn clean
  "Remove prior build output. Idempotent."
  [_]
  (b/delete {:path class-dir})
  (b/delete {:path uber-file}))

(defn uber
  "AOT-compile adsb.main and package a runnable uberjar at target/adsb.jar.
  resources/ is copied in first, so the release-compiled frontend
  (resources/public/js/main.js) ships inside the jar."
  [_]
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
