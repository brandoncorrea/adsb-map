(ns adsb.source-hygiene-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def ^:private source-roots
  ["src" "test/clj" "test/cljc" "test/cljs" "src/css"])

(defn- source-files []
  (->> source-roots
       (map io/file)
       (filter #(.exists %))
       (mapcat file-seq)
       (filter #(.isFile %))
       (filter #(re-matches #".+\.clj[cs]?" (.getName %)))))

(deftest no-source-file-carries-a-raw-control-byte
  (let [offenders
        (for [f (source-files)
              :let [bytes (with-open [in (io/input-stream f)]
                            (.readAllBytes in))
                    bad   (keep-indexed
                            (fn [i b]
                              (let [v (bit-and b 0xff)]
                                (when (and (< v 32) (not (#{9 10 13} v)))
                                  [i v])))
                            bytes)]
              :when (seq bad)]
          (str f " — " (count bad) " control byte(s), first at offset "
               (ffirst bad) " (0x" (format "%02x" (second (first bad))) ")"))]
    (testing "a raw control byte in a source file makes git call it binary,
              which costs the diff, the grep and the merge — write it as a
              \\uNNNN escape and the string is identical (adsb-wgk)"
      (is (empty? offenders)))))
