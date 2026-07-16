(ns adsb.ingest.beast-test
  (:require [adsb.ingest.beast :as beast]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def ^:private fixture-path "test/resources/beast-sample.bin")

(defn- fixture-bytes []
  (let [file (io/file fixture-path)
        buf  (byte-array (.length file))]
    (with-open [in (io/input-stream file)]
      (.read in buf))
    buf))

(def ^:private expected-frames
  [#:beast{:type    :mode-ac
           :mlat    17665503728661
           :signal  42
           :payload [26 119]}
   #:beast{:type    :mode-s-short
           :mlat    35326678737957
           :signal  26
           :payload [64 65 66 67 68 69 70]}
   #:beast{:type    :mode-s-long
           :mlat    4731372549
           :signal  26
           :payload [26 32 33 34 35 36 37 38 39 40 41 42 43 44]}])

(def ^:private expected-carry [26 51 1 2 3])

(deftest decodes-a-frame-of-each-type-with-escapes-resolved
  (testing "the capture yields one frame per Beast type, doubled 0x1a
            de-escaped in the MLAT, signal, and payload"
    (let [{:keys [frames]} (beast/frames (fixture-bytes))]
      (is (= expected-frames frames)))))

(deftest carries-the-truncated-final-frame
  (testing "a frame cut short by the end of the capture becomes carry,
            not a fourth frame or an exception"
    (let [{:keys [frames carry]} (beast/frames (fixture-bytes))]
      (is (= 3 (count frames)))
      (is (= expected-carry carry)))))

(deftest parse-is-invariant-under-the-chunk-split
  (testing "cut the capture at every byte offset, feed the halves with the
            carry threaded between them, and the frames (and final carry)
            are identical to parsing it whole — a frame split across two
            socket reads is reassembled"
    (let [whole  (vec (fixture-bytes))
          n      (count whole)
          target (beast/frames whole)]
      (doseq [k (range (inc n))]
        (let [head    (subvec whole 0 k)
              tail    (subvec whole k n)
              first'  (beast/frames head nil)
              second' (beast/frames tail (:carry first'))]
          (is (= (:frames target) (into (:frames first') (:frames second'))))
          (is (= (:carry target) (:carry second'))))))))

(deftest empty-input-yields-no-frames
  (testing "nothing in, nothing out — no frames and an empty carry"
    (is (= {:frames [] :carry []} (beast/frames [])))
    (is (= {:frames [] :carry []} (beast/frames [] nil)))))

(deftest pure-junk-is-dropped
  (testing "bytes with no 0x1a marker are inter-frame garbage and vanish"
    (is (= {:frames [] :carry []} (beast/frames [0x00 0x7f 0xff 0x42])))))

(deftest an-unknown-type-byte-resyncs-to-the-next-marker
  (testing "0x1a followed by a byte that is not a Beast type is skipped,
            and a valid frame right behind it still decodes"
    (let [ac    [0x1a 0x31 0 1 2 3 4 5 0x2a 0xaa 0xbb]      ; a whole mode-ac
          bytes (into [0x1a 0x00 0x99] ac)                  ; bad type, then it
          {:keys [frames]} (beast/frames bytes)]
      (is (= 1 (count frames)))
      (is (= :mode-ac (:beast/type (first frames)))))))

(deftest a-trailing-escape-is-carried-not-consumed
  (testing "an escape byte at the very end of a chunk is ambiguous until
            the next byte arrives, so the frame is carried whole"
    (let [head  [0x1a 0x31 0 1 2 3 4 5 0x2a 0x1a]           ; mode-ac, last byte 0x1a
          rest' [0x1a 0xbb]                                 ; ...which was escaped
          r1    (beast/frames head nil)
          r2    (beast/frames rest' (:carry r1))]
      (is (empty? (:frames r1)))
      (is (= [#:beast{:type    :mode-ac
                      :mlat    4328719365                   ; 00 01 02 03 04 05
                      :signal  42
                      :payload [26 187]}]                   ; 0x1a de-escaped, then 0xbb
             (:frames r2))))))
