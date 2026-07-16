(ns adsb.ingest.beast)

(def ^:private escape-byte 0x1a)
(def ^:private mlat-byte-count 6)
(def ^:private header-byte-count 7)

(def ^:private frame-type
  {0x31 [:mode-ac 2]
   0x32 [:mode-s-short 7]
   0x33 [:mode-s-long 14]})

(defn- ->unsigned-bytes [bytes]
  (mapv #(bit-and (int %) 0xff) bytes))

(defn- big-endian [bs]
  (reduce (fn [acc b] (+ (* acc 256) b)) 0 bs))

(defn- ->frame [kind data]
  {:beast/type    kind
   :beast/mlat    (big-endian (subvec data 0 mlat-byte-count))
   :beast/signal  (nth data mlat-byte-count)
   :beast/payload (subvec data header-byte-count)})

(defn- read-data [bs start n]
  (let [len (count bs)]
    (loop [p    start
           data []
           got  0]
      (cond
        (= got n) {:status :ok :data data :next p}
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

(defn- read-frame [bs i]
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

(defn- next-marker [bs from]
  (let [len (count bs)]
    (loop [p from]
      (cond
        (>= p len) nil
        (= escape-byte (nth bs p)) p
        :else (recur (inc p))))))

(defn frames
  ([bytes] (frames bytes nil))
  ([bytes carry]
   (let [bs (into (->unsigned-bytes carry) (->unsigned-bytes bytes))]
     (loop [i     (next-marker bs 0)
            found []]
       (if (nil? i)
         {:frames found :carry []}
         (let [result (read-frame bs i)]
           (case (:status result)
             :ok (recur (next-marker bs (:next result))
                        (conj found (:frame result)))
             :resync (recur (next-marker bs (:next result)) found)
             :truncated {:frames found :carry (subvec bs i)})))))))
