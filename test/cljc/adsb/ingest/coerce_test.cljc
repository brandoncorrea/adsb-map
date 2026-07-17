(ns adsb.ingest.coerce-test
  (:require [adsb.ingest.coerce :as coerce]
            [clojure.test :refer [deftest testing is]]
            #?@(:clj [[adsb.schema :as schema]
                      [cheshire.core :as json]
                      [clojure.string :as str]
                      [malli.core :as m]])))

(def cruising-raw
  {:hex       "abc0e4"
   :type      "adsb_icao"
   :flight    "UPS2717 "
   :alt_baro  34775
   :gs        450.5
   :track     97.14
   :baro_rate -960
   :squawk    "6040"
   :lat       27.961166
   :lon       -83.975953
   :messages  1848
   :seen      0.4
   :rssi      -26.2})

(def bare-mode-s-raw
  {:hex      "a10202"
   :type     "mode_s"
   :messages 74
   :seen     3.5
   :rssi     -29.7})

(def mangled-raw (assoc cruising-raw :hex "badbad" :alt_baro {:corrupt true}))

(deftest ->aircraft
  (testing "coerces a complete raw entry into namespaced domain keys,
            with the callsign trimmed"
    (is (= {:aircraft/icao            "abc0e4"
            :aircraft/callsign        "UPS2717"
            :aircraft/position        {:geo/lat 27.961166 :geo/lon -83.975953}
            :aircraft/altitude-ft     34775
            :aircraft/squawk          "6040"
            :aircraft/ground-speed-kt 450.5
            :aircraft/track-deg       97.14
            :aircraft/baro-rate-fpm   -960
            :aircraft/seen-s          0.4
            :aircraft/rssi            -26.2}
           (coerce/->aircraft cruising-raw))))

  (testing "coerces alt_baro of \"ground\" to an on-ground flag, not an altitude"
    (let [aircraft (coerce/->aircraft (assoc cruising-raw :alt_baro "ground"))]
      (is (true? (:aircraft/on-ground? aircraft)))
      (is (not (contains? aircraft :aircraft/altitude-ft)))))

  (testing "keeps a never-positioned aircraft, without :aircraft/position"
    (let [aircraft (coerce/->aircraft bare-mode-s-raw)]
      (is (= "a10202" (:aircraft/icao aircraft)))
      (is (not (contains? aircraft :aircraft/position)))))

  (testing "rejects an entry whose seen_pos is not numeric — bad wire types
            must die at the boundary, not in later arithmetic"
    (is (nil? (coerce/->aircraft (assoc cruising-raw :seen_pos "garbage")))))

  (testing "omits the position when only one of lat/lon is present"
    (is (not (contains? (coerce/->aircraft (dissoc cruising-raw :lon))
                        :aircraft/position))))

  (testing "absent gs/track/baro_rate stay absent — never defaulted to zero"
    (doseq [[raw-field domain-key] {:gs        :aircraft/ground-speed-kt
                                    :track     :aircraft/track-deg
                                    :baro_rate :aircraft/baro-rate-fpm}]
      (let [aircraft (coerce/->aircraft (dissoc cruising-raw raw-field))]
        (is (not (contains? aircraft domain-key))))))

  (testing "treats explicit null fields as absent"
    (let [aircraft (coerce/->aircraft
                     (assoc cruising-raw :gs nil :flight nil :lat nil))]
      (is (not (contains? aircraft :aircraft/ground-speed-kt)))
      (is (not (contains? aircraft :aircraft/callsign)))
      (is (not (contains? aircraft :aircraft/position)))))

  (testing "drops an all-blank callsign rather than keeping spaces"
    (is (not (contains? (coerce/->aircraft
                          (assoc cruising-raw :flight "        "))
                        :aircraft/callsign))))

  (testing "lower-cases the hex so aircraft identity is stable"
    (is (= "abc0e4" (:aircraft/icao (coerce/->aircraft (assoc cruising-raw :hex "ABC0E4"))))))

  (testing "returns nil for an entry with no usable hex identity"
    (is (nil? (coerce/->aircraft (dissoc cruising-raw :hex))))
    (is (nil? (coerce/->aircraft (assoc cruising-raw :hex "wat")))))

  (testing "returns nil, without throwing, for junk that is not even a map"
    (is (nil? (coerce/->aircraft nil)))
    (is (nil? (coerce/->aircraft "garbage")))
    (is (nil? (coerce/->aircraft 42)))))

(deftest ->aircraft-category
  (testing "a recognized emitter category is normalized onto the domain
            aircraft — the symbology channel (adsb-rnp)"
    (doseq [category ["A1" "A3" "A5" "A7" "B1" "C2" "A0"]]
      (is (= category
             (:aircraft/category
               (coerce/->aircraft
                 (assoc cruising-raw :category category)))))))

  (testing "an aircraft that transmits no category carries none — absent,
            never a default, so the map can read absence as 'unclassified'
            and draw the generic plane"
    (is (not (contains? (coerce/->aircraft (dissoc cruising-raw :category))
                        :aircraft/category)))
    (is (not (contains? (coerce/->aircraft (assoc cruising-raw :category nil))
                        :aircraft/category))))

  (testing "a category outside the closed enum is ABSENCE, never a
            passthrough — but the AIRCRAFT SURVIVES. The feeder is
            unauthenticated radio: an unrecognized category costs the
            FIELD, exactly as an absurd altitude does, and never the
            aircraft. Anything else would let a made-up category erase a
            real target from the picture."
    (doseq [hostile ["A8"                                   ; no such code — the sets stop at 7
                     "D1"                                   ; set D is reserved; nothing emits it
                     "X9" "a3" ""                           ; junk, wrong case, empty
                     "A3; DROP"                             ; a string with intent
                     42 {:evil true} ["A3"]]]               ; not even a string
      (let [aircraft (coerce/->aircraft (assoc cruising-raw
                                          :category hostile))]
        (is (some? aircraft))
        (is (= "abc0e4" (:aircraft/icao aircraft)))
        (is (not (contains? aircraft :aircraft/category)))))))

(deftest ->aircraft-mlat
  (testing "a type \"mlat\" entry is flagged :aircraft/mlat? true"
    (is (true? (:aircraft/mlat?
                 (coerce/->aircraft (assoc cruising-raw :type "mlat"))))))

  (testing "a non-empty mlat array flags the marker even when type is not
            \"mlat\" — a mode_s target falling back to multilateration"
    (is (true? (:aircraft/mlat?
                 (coerce/->aircraft
                   (assoc cruising-raw :type "mode_s"
                                       :mlat ["lat" "lon"]))))))

  (testing "an ordinary adsb_icao entry carries no marker — absent means
            not-MLAT, never an explicit false"
    (let [aircraft (coerce/->aircraft (assoc cruising-raw :mlat []))]
      (is (not (contains? aircraft :aircraft/mlat?)))))

  (testing "an adsb_icao entry with the mlat field absent entirely
            carries no marker"
    (is (not (contains? (coerce/->aircraft cruising-raw) :aircraft/mlat?))))

  (testing "garbage in the mlat field costs the marker, never the aircraft —
            it is advisory, like category"
    (let [aircraft (coerce/->aircraft (assoc cruising-raw :mlat 42))]
      (is (= "abc0e4" (:aircraft/icao aircraft)))
      (is (not (contains? aircraft :aircraft/mlat?))))))

(deftest ->aircraft-plausibility
  (testing "an absurd altitude costs the field, not the aircraft, and is never clamped"
    (let [aircraft (coerce/->aircraft
                     (assoc cruising-raw :alt_baro 400000))]
      (is (= "abc0e4" (:aircraft/icao aircraft)))
      (is (not (contains? aircraft :aircraft/altitude-ft)))
      (is (= 450.5 (:aircraft/ground-speed-kt aircraft)))))

  (testing "an absurd ground speed costs the field, not the aircraft"
    (let [aircraft (coerce/->aircraft (assoc cruising-raw :gs 3000.0))]
      (is (not (contains? aircraft :aircraft/ground-speed-kt)))
      (is (= 34775 (:aircraft/altitude-ft aircraft)))))

  (testing "a plausible below-sea-level altitude is kept"
    (is (= -1000
           (:aircraft/altitude-ft
             (coerce/->aircraft
               (assoc cruising-raw :alt_baro -1000)))))))

(deftest receiver-relative-fields-never-copied
  (testing "r_dst/r_dir — receiver-relative range and bearing, which
            together with one position locate the antenna exactly —
            leave no trace on the domain aircraft: coercion is a
            selective copy, byte-for-byte identical with or without them"
    (is (= (coerce/->aircraft cruising-raw)
           (coerce/->aircraft
             (assoc cruising-raw :r_dst 39.887 :r_dir 231.3))))))

(deftest ->aircraft-batch
  (testing "one malformed entry yields the rest of the batch, not an exception"
    (is (= ["abc0e4" "a10202"]
           (mapv :aircraft/icao
                 (:aircraft (coerce/->aircraft-batch
                             [cruising-raw mangled-raw bare-mode-s-raw]))))))

  (testing "survives entries that are not even maps"
    (is (= ["abc0e4"]
           (mapv :aircraft/icao
                 (:aircraft (coerce/->aircraft-batch
                             ["junk" nil 42 cruising-raw]))))))

  (testing "returns an empty aircraft vector when the feeder reports none"
    (is (= [] (:aircraft (coerce/->aircraft-batch []))))
    (is (= [] (:aircraft (coerce/->aircraft-batch nil))))))

(deftest ->aircraft-batch-rejections
  (testing "a dropped entry surfaces as one rejection datum alongside the
            aircraft that survived — the batch is now pure, so this holds
            on both platforms; the edge (src/clj) is what logs it"
    (let [{:keys [aircraft rejections]}
          (coerce/->aircraft-batch [cruising-raw mangled-raw bare-mode-s-raw])]
      (is (= ["abc0e4" "a10202"] (mapv :aircraft/icao aircraft)))
      (is (= 1 (count rejections)))
      (is (= "badbad" (:hex (first rejections))))
      (is (string? (:error (first rejections))))))

  (testing "a clean payload yields no rejections"
    (is (= [] (:rejections (coerce/->aircraft-batch [cruising-raw bare-mode-s-raw])))))

  (testing "the rejection context is bounded — never the whole payload, so a
            stuck bad actor cannot fill the edge's disk when it is logged"
    (let [huge (apply str (repeat 10000 "A"))
          {:keys [rejections]}
          (coerce/->aircraft-batch
            [(assoc cruising-raw :hex huge :flight huge)])]
      (is (= 1 (count rejections)))
      (is (< (count (str (first rejections))) 1000)))))

#?(:clj
   (deftest real-fixture-acceptance
     (let [payload (json/parse-string
                     (slurp "test/resources/aircraft-sample.json") true)
           raw     (:aircraft payload)
           batch   (:aircraft (coerce/->aircraft-batch raw))]

       (testing "every aircraft in the real capture is accepted"
         (is (= 51 (count raw)))
         (is (= 51 (count batch))))

       (testing "the positioned / never-positioned split matches the
                 capture — never-positioned aircraft are retained"
         (is (= 39 (count (filter :aircraft/position batch))))
         (is (= 12 (count (remove :aircraft/position batch)))))

       (testing "every coerced aircraft satisfies the domain schema"
         (is (every? #(m/validate schema/aircraft %) batch)))

       (testing "identities are unique and normalized"
         (is (= 51 (count (into #{} (map :aircraft/icao) batch)))))

       (testing "every callsign is trimmed"
         (is (every? #(= % (str/trim %)) (keep :aircraft/callsign batch))))

       (testing "no nil values leak into any domain aircraft"
         (is (not-any? #(some nil? (vals %)) batch)))

       (testing "absent fields stayed absent — the 10 entries with no gs
                 did not become stationary aircraft"
         (is (= 41 (count (filter :aircraft/ground-speed-kt batch))))
         (is (= 44 (count (filter :aircraft/altitude-ft batch))))))))
