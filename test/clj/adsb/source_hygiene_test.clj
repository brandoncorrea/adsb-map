(ns adsb.source-hygiene-test
  "Properties of the SOURCE TREE ITSELF, rather than of anything it computes.

  There is exactly one so far, and it earns its namespace by how quietly it
  broke (adsb-wgk)."
  (:require
    [babashka.fs :as fs]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))

(def ^:private source-roots
  ["src" "test/clj" "test/cljc" "test/cljs" "src/css"])

(defn- source-files
  "Every Clojure source file we author, as a seq of java.io.File."
  []
  (->> source-roots
       (filter fs/exists?)
       (mapcat #(fs/glob % "**.{clj,cljc,cljs}"))
       (map fs/file)))

(deftest no-source-file-carries-a-raw-control-byte
  ;; GIT DECIDES A FILE IS BINARY BY LOOKING FOR A NUL in its first 8000
  ;; bytes, and when it finds one it stops treating the file as text —
  ;; permanently, and without a word. `git diff` prints "Bin 22622 -> 23526
  ;; bytes" instead of a hunk, `git grep` skips the file, and a concurrent
  ;; edit merges as "binary files differ" with nothing to resolve.
  ;;
  ;; sbs_test.clj spent a commit in that state (adsb-wgk). Its hostile-line
  ;; fixture — a junk line with control bytes in it, which is a test the SBS
  ;; reader genuinely needs — had been written as RAW OCTETS in the source
  ;; instead of escapes. The test passed. The file reviewed as a blank diff
  ;; and vanished from every grep, which is how it was found: searching a
  ;; 466-line file for "deftest" returned nothing, and no error said why.
  ;;
  ;; The fixture is fine; the ENCODING was the bug. Clojure's reader takes
  ;; \uNNNN, which builds the identical string out of an ASCII source. So the
  ;; rule is: control bytes may be what a test MEANS, never what a file
  ;; CONTAINS.
  (let [offenders
        (for [f     (source-files)
              :let  [bytes (fs/read-all-bytes f)
                     bad   (keep-indexed
                             (fn [i b]
                               ;; Tab (9), LF (10) and CR (13) are the only
                               ;; control bytes text is allowed to hold.
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
      (is (empty? offenders)
          (str "control bytes in tracked source:\n  "
               (str/join "\n  " offenders))))))
