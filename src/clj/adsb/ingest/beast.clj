(ns adsb.ingest.beast
  "Beast binary framing: the pure first layer of the Beast Source.

  ultrafeeder's port 30005 emits Mode-S the way dump1090 does — a byte
  stream of escape-delimited frames. This namespace turns that raw stream
  into typed frames and nothing more; decoding the Mode-S payload (DF17
  extended squitter, CRC-24, CPR position) is the next layer, adsb-doh.2,
  which consumes the frame maps produced here.

  THE WIRE FORMAT. 0x1a marks the start of every frame. It is followed by
  a one-byte type — ASCII '1' Mode-A/C, '2' short Mode-S, '3' long
  Mode-S — then a 6-byte big-endian MLAT timestamp, a 1-byte signal
  level, then the payload (2, 7, or 14 bytes by type). Because 0x1a is the
  delimiter, a literal 0x1a inside the timestamp, signal, or payload is
  escaped by DOUBLING it: the two bytes 0x1a 0x1a stand for one data byte
  0x1a. A lone 0x1a is therefore always the next frame's start.

  PURE, WITH A CARRY. `frames` is a pure function of (bytes, carry): a
  socket reader can hand it arbitrary chunk boundaries and feed the
  returned :carry back on the next call, so a frame split across two
  reads is reassembled without the reader knowing the format. No socket,
  no atom, no I/O lives here — the effectful reader is a later bead.

  THE FEEDER IS UNTRUSTED (docs/validation-boundaries.md). Hostile or
  corrupt input is dropped or carried, never thrown: a truncated tail
  becomes carry, an unknown type byte or a frame boundary that arrives
  mid-frame triggers a resync to the next 0x1a, and an escape byte at the
  very end of a chunk is carried whole. A garbled burst costs at most the
  frames it corrupts, never the stream.")

(def ^:private escape-byte
  "0x1a — the Beast frame delimiter, and the byte that is doubled to
  escape itself inside frame data."
  0x1a)

(def ^:private mlat-byte-count
  "The MLAT timestamp is a 6-byte big-endian counter."
  6)

(def ^:private header-byte-count
  "The fixed per-frame header before the payload: the 6-byte MLAT
  timestamp and the 1-byte signal level."
  7)

(def ^:private frame-type
  "Beast type byte -> [kind payload-byte-count]. The type is the ASCII
  digit ('1'/'2'/'3') immediately after the 0x1a frame marker."
  {0x31 [:mode-ac       2]
   0x32 [:mode-s-short   7]
   0x33 [:mode-s-long   14]})

(defn- ->unsigned-bytes
  "A vector of unsigned ints (0-255) over any seqable of bytes or ints,
  so the scanner works in one representation whether it was handed a
  byte-array off a socket or the vector of bytes carried from the last
  call. nil becomes the empty vector."
  [bytes]
  (mapv #(bit-and (int %) 0xff) bytes))

(defn- big-endian
  "Fold big-endian unsigned bytes into a single long. Six bytes is 48
  bits, comfortably inside a long."
  [bs]
  (reduce (fn [acc b] (+ (* acc 256) b)) 0 bs))

(defn- ->frame
  "Build a self-describing frame map from a frame kind and its de-escaped
  data bytes (6-byte MLAT, 1-byte signal, then the payload)."
  [kind data]
  {:beast/type    kind
   :beast/mlat    (big-endian (subvec data 0 mlat-byte-count))
   :beast/signal  (nth data mlat-byte-count)
   :beast/payload (subvec data header-byte-count)})

(defn- read-data
  "De-escape exactly `n` logical data bytes from `bs` starting at raw
  index `start`. Returns {:status :ok :data <vec> :next <idx>} on
  success; {:status :truncated} when the chunk ends mid-datum (including
  a trailing lone escape), so the caller carries the frame; {:status
  :resync :next <idx>} when a lone 0x1a appears where a data byte was
  expected — that 0x1a starts the next frame, so the current frame is
  abandoned and scanning resumes there."
  [bs start n]
  (let [len (count bs)]
    (loop [p    start
           data []
           got  0]
      (cond
        (= got n)  {:status :ok :data data :next p}
        (>= p len) {:status :truncated}
        :else
        (let [b (nth bs p)]
          (cond
            (not= b escape-byte)
            (recur (inc p) (conj data b) (inc got))

            (>= (inc p) len)
            {:status :truncated}

            (= escape-byte (nth bs (inc p)))
            (recur (+ p 2) (conj data escape-byte) (inc got))

            :else
            {:status :resync :next p}))))))

(defn- read-frame
  "Parse one frame whose 0x1a marker is at index `i`. Returns
  {:status :ok :frame <map> :next <idx>}, {:status :truncated} (the
  caller carries from i), or {:status :resync :next <idx>} (an unknown
  type byte — skip past the marker and rescan)."
  [bs i]
  (if (>= (inc i) (count bs))
    {:status :truncated}
    (if-let [[kind payload-len] (frame-type (nth bs (inc i)))]
      (let [result (read-data bs (+ i 2) (+ header-byte-count payload-len))]
        (if (= :ok (:status result))
          {:status :ok
           :frame  (->frame kind (:data result))
           :next   (:next result)}
          result))
      {:status :resync :next (inc i)})))

(defn- next-marker
  "Index of the next 0x1a frame marker in `bs` at or after `from`, or nil
  when none remains — the bytes before it are inter-frame garbage and are
  dropped."
  [bs from]
  (let [len (count bs)]
    (loop [p from]
      (cond
        (>= p len)                 nil
        (= escape-byte (nth bs p)) p
        :else                      (recur (inc p))))))

(defn frames
  "Split a Beast byte stream into typed frames, tolerant of arbitrary
  chunk boundaries. `bytes` is the newly-read chunk (a byte-array, or any
  seqable of bytes/ints); `carry` is the unconsumed tail returned by the
  previous call, or nil/empty on the first call.

  Returns {:frames <vector of frame maps> :carry <vector of unsigned
  bytes>}. Feed :carry back as the next call's `carry` and a frame split
  across two reads is reassembled. Each frame map is

    {:beast/type    :mode-ac | :mode-s-short | :mode-s-long
     :beast/mlat    <long — the 6-byte big-endian MLAT timestamp>
     :beast/signal  <int 0-255 — the signal level>
     :beast/payload <vector of unsigned ints — the de-escaped payload>}

  Malformed input is dropped or carried, never thrown
  (docs/validation-boundaries.md)."
  ([bytes] (frames bytes nil))
  ([bytes carry]
   (let [bs (into (->unsigned-bytes carry) (->unsigned-bytes bytes))]
     (loop [i     (next-marker bs 0)
            found []]
       (if (nil? i)
         {:frames found :carry []}
         (let [result (read-frame bs i)]
           (case (:status result)
             :ok        (recur (next-marker bs (:next result))
                               (conj found (:frame result)))
             :resync    (recur (next-marker bs (:next result)) found)
             :truncated {:frames found :carry (subvec bs i)})))))))
