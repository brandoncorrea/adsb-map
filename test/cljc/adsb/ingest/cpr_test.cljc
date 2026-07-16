(ns adsb.ingest.cpr-test
  (:require [adsb.ingest.cpr :as cpr]
            [clojure.math :as math]
            [clojure.test :refer [deftest is testing]]))

(defn- approximately? [expected actual tolerance]
  (< (abs (double (- expected actual))) tolerance))

(def book-even
  {:cpr/parity      :even
   :cpr/lat         93000
   :cpr/lon         51372
   :cpr/heard-at-ms 500})

(def book-odd
  {:cpr/parity      :odd
   :cpr/lat         74158
   :cpr/lon         50194
   :cpr/heard-at-ms 0})

(def book-even-lat 52.2572021484375)
(def book-odd-lat 52.26578017412606)
(def book-lon 3.91937255859375)

(deftest nl-lookup
  (testing "published values and the spec's special cases"
    (is (= 59 (cpr/nl 0)))
    (is (= 36 (cpr/nl book-even-lat)))
    (is (= 2 (cpr/nl 87)))
    (is (= 2 (cpr/nl -87)))
    (is (= 1 (cpr/nl 88)))
    (is (= 1 (cpr/nl -90))))

  (testing "steps down across the first transition latitude"
    (is (= 59 (cpr/nl 10.4704)))
    (is (= 58 (cpr/nl 10.4706)))))

(deftest global-decode-known-answer
  (testing "the book's pair, even frame newest — the published answer"
    (let [{:geo/keys [lat lon]} (cpr/global-position book-even book-odd)]
      (is (approximately? book-even-lat lat 1e-9))
      (is (approximately? book-lon lon 1e-9))))

  (testing "the same pair with the odd frame newest uses the odd
            latitude — the book publishes both candidates"
    (let [newest-odd (assoc book-odd :cpr/heard-at-ms 1000)
          {:geo/keys [lat]} (cpr/global-position book-even newest-odd)]
      (is (approximately? book-odd-lat lat 1e-9)))))

(def ^:private cpr-scale 131072)

(defn- encode-cpr [lat lon parity]
  (let [lat-zones   (if (= :odd parity) 59 60)
        zone-height (/ 360.0 lat-zones)
        lat-value   (math/floor
                      (+ 0.5 (* cpr-scale
                                (/ (mod lat zone-height) zone-height))))
        rounded-lat (* zone-height
                       (+ (/ lat-value cpr-scale)
                          (math/floor (/ lat zone-height))))
        lon-zones   (max 1 (- (cpr/nl rounded-lat)
                              (if (= :odd parity) 1 0)))
        zone-width  (/ 360.0 lon-zones)
        lon-value   (math/floor
                      (+ 0.5 (* cpr-scale
                                (/ (mod lon zone-width) zone-width))))]
    {:cpr/parity parity
     :cpr/lat    (long (mod lat-value cpr-scale))
     :cpr/lon    (long (mod lon-value cpr-scale))}))

(defn- global-round-trip [lat lon]
  (cpr/global-position
    (assoc (encode-cpr lat lon :even) :cpr/heard-at-ms 1)
    (assoc (encode-cpr lat lon :odd) :cpr/heard-at-ms 0)))

(def ^:private round-trip-tolerance 1e-4)

(deftest global-decode-round-trips
  (testing "an encoded position decodes back, all over the globe"
    (doseq [[lat lon] [[52.2572021484375 3.91937255859375]
                       [-33.9461 151.1772]
                       [0.001 0.001]
                       [10.4704 -179.99]
                       [64.15 -21.94]
                       [-77.85 166.67]]]
      (let [position (global-round-trip lat lon)]
        (is (some? position) (str "no decode at " [lat lon]))
        (when position
          (is (approximately? lat (:geo/lat position) round-trip-tolerance))
          (is (approximately? lon (:geo/lon position) round-trip-tolerance)))))))

(deftest zone-boundary-crossing
  (testing "a pair straddling the NL 59->58 transition (~10.47047°N)
            is refused — the two frames describe different zone grids"
    (is (nil? (cpr/global-position
                (assoc (encode-cpr 10.4700 20.0 :even)
                  :cpr/heard-at-ms 0)
                (assoc (encode-cpr 10.4710 20.0 :odd)
                  :cpr/heard-at-ms 1)))))

  (testing "a pair straddling the NL 3->2 transition (~86.53537°N) is refused"
    (is (nil? (cpr/global-position
                (assoc (encode-cpr 86.530 20.0 :even)
                  :cpr/heard-at-ms 0)
                (assoc (encode-cpr 86.540 20.0 :odd)
                  :cpr/heard-at-ms 1)))))

  (testing "the same small movement on one side of the boundary
            decodes fine"
    (let [{:geo/keys [lat]} (cpr/global-position
                              (assoc (encode-cpr 10.4700 20.0 :even)
                                :cpr/heard-at-ms 0)
                              (assoc (encode-cpr 10.4702 20.0 :odd)
                                :cpr/heard-at-ms 1))]
      (is (approximately? 10.4702 lat 1e-3)))))

(deftest local-decode-known-answer
  (testing "the book's even frame against its reference (52.258, 3.918)
            recovers the published position"
    (let [{:geo/keys [lat lon]}
          (cpr/local-position book-even {:geo/lat 52.258 :geo/lon 3.918})]
      (is (approximately? book-even-lat lat 1e-9))
      (is (approximately? book-lon lon 1e-9)))))

(deftest local-decode-round-trips
  (testing "either parity decodes locally against a nearby reference"
    (doseq [parity [:even :odd]
            [lat lon ref-lat ref-lon] [[52.2572 3.9194 52.0 4.2]
                                       [-33.9461 151.1772 -34.2 150.9]]]
      (let [half (encode-cpr lat lon parity)
            {lat' :geo/lat lon' :geo/lon}
            (cpr/local-position half {:geo/lat ref-lat
                                      :geo/lon ref-lon})]
        (is (approximately? lat lat' round-trip-tolerance))
        (is (approximately? lon lon' round-trip-tolerance))))))

(deftest local-decode-impossible-latitude
  (testing "a reference so near the pole that the nearest candidate
            leaves the globe yields nil, not a latitude past 90"
    (is (nil? (cpr/local-position
                {:cpr/parity :even :cpr/lat 13107 :cpr/lon 0}
                {:geo/lat 89.99 :geo/lon 0})))))
