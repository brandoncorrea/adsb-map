(ns adsb.ingest.beast-test
  "Beast framing over a SYNTHETIC byte capture (test/resources/beast-sample.bin,
  constructed from the wire spec — see test/resources/README.md and
  bead adsb-c75 for the real-capture follow-up).

  The capture is deliberately adversarial: a frame of each type, a
  doubled-0x1a escape in the MLAT, signal, and payload positions, pure
  junk and an escape+bad-type run between frames, and a truncated final
  frame. Two properties matter — the frames decode correctly, and the
  parse is invariant under where the socket happened to split the stream,
  which is asserted by re-feeding the fixture cut at every byte offset."
  (:require
    [adsb.ingest.beast :as beast]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest testing is]]))

(def ^:private fixture-path "test/resources/beast-sample.bin")

(defn- fixture-bytes
  "The committed Beast capture as a byte-array, read by relative path
  from the project root (test/resources is not on the runtime
  classpath)."
  []
  (let [file (io/file fixture-path)
        buf  (byte-array (.length file))]
    (with-open [in (io/input-stream file)]
      (.read in buf))
    buf))

(def ^:private expected-frames
  "The frames the capture is built to yield, escapes resolved: 26 is the
  de-escaped 0x1a, proving a doubled delimiter survives in the MLAT
  (0x1a inside the timestamp), the signal, and the payload."
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

(def ^:private expected-carry
  "The truncated final frame (0x1a '3' then only three of its bytes) is
  held whole for the next read, never emitted as a frame."
  [26 51 1 2 3])

(deftest decodes-a-frame-of-each-type-with-escapes-resolved
  (testing "the capture yields one frame per Beast type, doubled 0x1a
            de-escaped in the MLAT, signal, and payload"
    (let [{:keys [frames]} (beast/frames (fixture-bytes))]
      (is (= expected-frames frames)))))

(deftest carries-the-truncated-final-frame
  (testing "a frame cut short by the end of the capture becomes carry,
            not a fourth frame or an exception"
    (let [{:keys [frames carry]} (beast/frames (fixture-bytes))]
      (is (= 3 (count frames)) "only the three whole frames are emitted")
      (is (= expected-carry carry) "the truncated tail is carried whole"))))

(deftest parse-is-invariant-under-the-chunk-split
  (testing "cut the capture at every byte offset, feed the halves with the
            carry threaded between them, and the frames (and final carry)
            are identical to parsing it whole — a frame split across two
            socket reads is reassembled"
    (let [whole  (vec (fixture-bytes))
          n      (count whole)
          target (beast/frames whole)]
      (doseq [k (range (inc n))]
        (let [head   (subvec whole 0 k)
              tail   (subvec whole k n)
              first' (beast/frames head nil)
              second' (beast/frames tail (:carry first'))]
          (is (= (:frames target)
                 (into (:frames first') (:frames second')))
              (str "frames differ when split at offset " k))
          (is (= (:carry target) (:carry second'))
              (str "carry differs when split at offset " k)))))))

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
    (let [ac    [0x1a 0x31 0 1 2 3 4 5 0x2a 0xaa 0xbb]  ; a whole mode-ac
          bytes (into [0x1a 0x00 0x99] ac)              ; bad type, then it
          {:keys [frames]} (beast/frames bytes)]
      (is (= 1 (count frames)))
      (is (= :mode-ac (:beast/type (first frames)))))))

(deftest a-trailing-escape-is-carried-not-consumed
  (testing "an escape byte at the very end of a chunk is ambiguous until
            the next byte arrives, so the frame is carried whole"
    (let [head [0x1a 0x31 0 1 2 3 4 5 0x2a 0x1a]  ; mode-ac, last byte 0x1a
          rest' [0x1a 0xbb]                        ; ...which was escaped
          r1    (beast/frames head nil)
          r2    (beast/frames rest' (:carry r1))]
      (is (empty? (:frames r1)) "nothing emitted while the escape dangles")
      (is (= [#:beast{:type    :mode-ac
                      :mlat    4328719365      ; 00 01 02 03 04 05
                      :signal  42
                      :payload [26 187]}]      ; 0x1a de-escaped, then 0xbb
             (:frames r2))))))

(comment
  ;; How test/resources/beast-sample.bin was generated — SYNTHETIC, from
  ;; the Beast wire spec. Re-run to regenerate byte-for-byte.
  (let [esc 0x1a
        escape-data (fn [bs] (mapcat #(if (= % esc) [esc esc] [%]) bs))
        frame       (fn [type-byte data]
                      (concat [esc type-byte] (escape-data data)))
        ac-data     (concat [0x10 0x11 0x12 0x13 0x14 0x15] [0x2a] [esc 0x77])
        short-data  (concat [0x20 0x21 0x22 0x23 0x24 0x25] [esc]
                            [0x40 0x41 0x42 0x43 0x44 0x45 0x46])
        long-data   (concat [0x00 0x01 esc 0x03 0x04 0x05] [esc]
                            (concat [esc] (range 0x20 0x2d)))
        ints        (concat (frame 0x31 ac-data)
                            [0x00 0xff 0x99]        ; pure junk, dropped
                            (frame 0x32 short-data)
                            [esc 0x00 0x88]         ; bad type, resync
                            (frame 0x33 long-data)
                            [esc 0x33 0x01 0x02 0x03])  ; truncated tail
        ba          (byte-array (map unchecked-byte ints))]
    (with-open [o (io/output-stream (io/file "test/resources/beast-sample.bin"))]
      (.write o ba))))
