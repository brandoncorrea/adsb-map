(ns adsb.test-console
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

(def ^:private expected-noise
  ["Rejected aircraft"
   "aircraft enrichment unavailable"
   "outside of a reactive context"
   "re-frame: overwriting"])

(defn- expected? [args]
  (some (fn [arg]
          (and (string? arg)
               (some #(str/includes? arg %) expected-noise)))
        args))

(defn- install! []
  (let [console js/console
        warn    (.-warn console)
        info    (.-info console)
        quietly (fn [emit]
                  (fn [& args]
                    (when-not (expected? args)
                      (.apply emit console (to-array args)))))]
    (set! (.-warn console) (quietly warn))
    (set! (.-info console) (quietly info))
    (rf/set-loggers! {:warn (quietly warn)})
    true))

;; `defonce`, so a hot reload does not wrap the wrappers and lose the originals
;; behind a chain of them. Not private: nothing reads it, and clj-kondo rightly
;; objects to a private var nobody reads.
(defonce console-filtered? (install!))
