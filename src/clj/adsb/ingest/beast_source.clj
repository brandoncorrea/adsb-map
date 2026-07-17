(ns adsb.ingest.beast-source
  (:require [adsb.ingest.beast :as beast]
            [adsb.ingest.mode-s :as mode-s]
            [adsb.ingest.tcp :as tcp])
  (:import (java.io InputStream)
           (java.util Arrays)))

(def ^:private read-buffer-size 4096)

(defn- long-frame? [frame]
  (= :mode-s-long (:beast/type frame)))

(defn- consume-frame! [state cpr-state frame]
  (let [now-ms ((:clock state))
        {:keys [delta cpr-state]} (mode-s/decode (:beast/payload frame)
                                                 now-ms cpr-state)]
    (when delta (tcp/accumulate! state delta now-ms))
    cpr-state))

(defn- fold-frames! [state frames cpr-state]
  (->> (filter long-frame? frames)
       (reduce (partial consume-frame! state) cpr-state)))

(defn- sweep-cpr-state-if-due [cpr-state swept-at-ms now-ms]
  (if (tcp/sweep-due? swept-at-ms now-ms)
    [(mode-s/sweep-cpr-state cpr-state now-ms) now-ms]
    [cpr-state swept-at-ms]))

(defn- read-frames! [^InputStream in {:keys [running? clock] :as state}]
  (let [buffer (byte-array read-buffer-size)]
    (loop [carry       nil
           cpr-state   nil
           swept-at-ms nil]
      (when @running?
        (let [n (.read in buffer)]
          (when (pos? n)
            (let [chunk     (Arrays/copyOfRange buffer 0 (int n))
                  {:keys [frames carry]} (beast/frames chunk carry)
                  cpr-state (fold-frames! state frames cpr-state)
                  [cpr-state swept-at-ms] (sweep-cpr-state-if-due
                                            cpr-state swept-at-ms (clock))]
              (recur carry cpr-state swept-at-ms))))))))

(defn- consume! [^InputStream in state]
  (read-frames! in state))

(defn ->source
  ([host port] (->source host port {}))
  ([host port opts]
   (tcp/->source host port opts consume! "adsb-beast-reader")))
