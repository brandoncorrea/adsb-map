(ns adsb.ingest.crop-test
  (:require
    [adsb.fixtures :as fixtures]
    [adsb.geo :as geo]
    [adsb.ingest.crop :as crop]
    #?(:clj  [clojure.test :refer [deftest testing is]]
       :cljs [cljs.test :refer-macros [deftest testing is]])))

;; A DECOY centre, in the region of the cast but deliberately not the
;; synthetic receiver position the plausibility tests use (28.0/-82.5).
;; That is the whole contract: the crop centre is not the antenna.
(def ^:private center {:geo/lat 27.9 :geo/lon -82.4})

(def ^:private crop {:crop/center center :crop/radius-m 100000})   ; 100 km

(defn- at-position [aircraft position]
  (assoc aircraft :aircraft/position position))

;; Due east of the centre, at a distance we choose per test.
(defn- east-of-center [km]
  (geo/destination center 90 (* km geo/meters-per-km)))

;; ---------------------------------------------------------------------
;; The gate

(deftest outside-crop?
  (testing "an aircraft at the centre is inside"
    (is (false? (crop/outside-crop? (at-position fixtures/ups-2717 center)
                                    crop))))

  (testing "just inside the radius is inside; just outside is outside —
            the boundary is the declared radius, nothing else"
    (is (false? (crop/outside-crop?
                  (at-position fixtures/ups-2717 (east-of-center 99))
                  crop)))
    (is (true? (crop/outside-crop?
                 (at-position fixtures/ups-2717 (east-of-center 101))
                 crop))))

  (testing "a POSITION-LESS aircraft is outside — we cannot place it
            inside the declared disc, and its bare presence in the feed
            still says this antenna hears it"
    (is (true? (crop/outside-crop? (dissoc fixtures/ups-2717
                                           :aircraft/position)
                                   crop)))))

(deftest gate-crop
  (testing "keeps only what sits inside the disc"
    (let [near (at-position fixtures/ups-2717 (east-of-center 50))
          far  (at-position fixtures/on-the-ground (east-of-center 250))]
      (is (= [near] (crop/gate-crop [near far] crop)))))

  (testing "a nil crop DISABLES the gate and returns the batch untouched
            — including the position-less, which only the enabled crop
            drops"
    (is (= fixtures/all (crop/gate-crop fixtures/all nil))))

  (testing "an empty batch stays empty, gate on or off"
    (is (= [] (crop/gate-crop [] crop)))
    (is (= [] (crop/gate-crop [] nil)))))

;; ---------------------------------------------------------------------
;; Configuration
;;
;; The failure that matters here is not a wrong crop, it is a crop that
;; LOOKS configured and is off. Every partial/garbage case must throw
;; rather than resolve to nil.

(defn- env [lat lon radius-km]
  (cond-> {}
          lat       (assoc crop/crop-lat-env lat)
          lon       (assoc crop/crop-lon-env lon)
          radius-km (assoc crop/crop-radius-km-env radius-km)))

(defn- throws? [f]
  (try (f) false
       (catch #?(:clj Exception :cljs :default) _ true)))

(deftest env-crop
  (testing "all three set resolves to a crop, radius converted to metres"
    (is (= {:crop/center {:geo/lat 27.9 :geo/lon -82.4}
            :crop/radius-m 100000.0}
           (crop/env-crop (env "27.9" "-82.4" "100")))))

  (testing "none set is nil — the gate is disabled, and adsb.main warns"
    (is (nil? (crop/env-crop {})))
    (is (nil? (crop/env-crop (env nil nil nil))))
    (is (nil? (crop/env-crop {"UNRELATED" "1"}))))

  (testing "blank entries read as unset, not as a broken crop"
    (is (nil? (crop/env-crop (env "" "  " "")))))

  (testing "ANY partial crop throws — it must never degrade to disabled"
    (is (throws? #(crop/env-crop (env "27.9" nil nil))))
    (is (throws? #(crop/env-crop (env nil nil "100"))))
    (is (throws? #(crop/env-crop (env "27.9" "-82.4" nil))))
    (is (throws? #(crop/env-crop (env "27.9" nil "100")))))

  (testing "a typoed coordinate throws rather than reading as unset —
            the bug this shape of code usually has"
    (is (throws? #(crop/env-crop (env "abc" "-82.4" "100"))))
    (is (throws? #(crop/env-crop (env "27.9" "-82.4" "wide")))))

  (testing "out-of-range coordinates throw, never clamp"
    (is (throws? #(crop/env-crop (env "91" "-82.4" "100"))))
    (is (throws? #(crop/env-crop (env "27.9" "-181" "100")))))

  (testing "a non-positive radius throws — an empty map is not privacy"
    (is (throws? #(crop/env-crop (env "27.9" "-82.4" "0"))))
    (is (throws? #(crop/env-crop (env "27.9" "-82.4" "-10")))))

  (testing "a radius wider than the antenna's own horizon throws: it
            cannot sit inside true coverage, so it drops nothing and
            only looks like a privacy control"
    (is (throws? #(crop/env-crop
                    (env "27.9" "-82.4"
                         (str (inc (geo/meters->km crop/max-radius-m)))))))))
