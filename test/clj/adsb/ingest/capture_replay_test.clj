(ns adsb.ingest.capture-replay-test
  (:require [adsb.accumulator :as accumulator]
            [adsb.ingest.beast :as beast]
            [adsb.ingest.mode-s :as mode-s]
            [adsb.ingest.sbs :as sbs]
            [adsb.schema :as schema]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]))

(def ^:private valid-aircraft? (m/validator schema/aircraft))
(def ^:private sbs-capture-path "test/resources/sbs-capture-2026-07-14.txt")
(def ^:private beast-capture-path "test/resources/beast-capture-2026-07-14.bin")
(def ^:private mlat-ticks-per-ms 12000)

(defn- beast-capture-bytes []
  (with-open [in (io/input-stream beast-capture-path)]
    (.readAllBytes in)))

(defn- frames-in-chunks [capture chunk-size]
  (loop [chunks (partition-all chunk-size (seq capture))
         carry  nil
         acc    []]
    (if (seq chunks)
      (let [{:keys [frames carry]} (beast/frames (first chunks) carry)]
        (recur (rest chunks) carry (into acc frames)))
      acc)))

(deftest sbs-capture-replays-through-the-boundary
  (let [lines  (str/split-lines (slurp sbs-capture-path))
        deltas (keep sbs/line->delta lines)]
    (testing "every recorded line coerces — the live sky never drops"
      (is (= 1857 (count lines)))
      (is (= 1857 (count deltas))))
    (testing "every delta is schema-valid past the boundary"
      (is (every? valid-aircraft? deltas)))
    (testing "the capture's telemetry mix survives coercion"
      (is (= 722 (count (filter :aircraft/position deltas))))
      (is (= 69 (count (filter :aircraft/callsign deltas))))
      (is (= 33 (count (distinct (map :aircraft/icao deltas))))))
    (testing "the deltas fold into a full picture"
      (let [picture (reduce #(accumulator/accumulate %1 %2 0) {} deltas)]
        (is (= 33 (count (accumulator/snapshot picture 0))))))))

(deftest beast-capture-replays-through-framing-and-decode
  (let [frames (frames-in-chunks (beast-capture-bytes) 4096)]
    (testing "the recorded stream de-escapes into the expected frames"
      (is (= {:mode-ac 5 :mode-s-short 2809 :mode-s-long 1791}
             (frequencies (map :beast/type frames))))
      (is (= frames (frames-in-chunks (beast-capture-bytes) 7))))
    (testing "long frames decode into trusted, schema-valid deltas"
      (let [{:keys [deltas]}
            (reduce (fn [{:keys [cpr-state deltas]}
                         {:beast/keys [mlat payload]}]
                      (let [now-ms (quot mlat mlat-ticks-per-ms)
                            {:keys [delta cpr-state]}
                            (mode-s/decode payload now-ms cpr-state)]
                        {:cpr-state cpr-state
                         :deltas    (if delta (conj deltas delta) deltas)}))
                    {:cpr-state nil :deltas []}
                    (filter #(= :mode-s-long (:beast/type %)) frames))]
        (is (= 1743 (count deltas)))
        (is (every? valid-aircraft? deltas))
        (is (= 602 (count (filter :aircraft/position deltas))))
        (is (= 63 (count (filter :aircraft/callsign deltas))))
        (is (= 617 (count (filter :aircraft/ground-speed-kt deltas))))
        (is (= 29 (count (distinct (map :aircraft/icao deltas)))))))))
