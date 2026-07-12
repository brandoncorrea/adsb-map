(ns adsb.schema-test
  (:require
    [adsb.schema :as schema]
    [malli.core :as m]
    #?(:clj [clojure.test :refer [deftest testing is]]
       :cljs [cljs.test :refer-macros [deftest testing is]])))

(def cruising
  "A complete, well-formed raw entry, shaped like the real capture."
  {:hex "abc0e4" :type "adsb_icao" :flight "UPS2717 " :alt_baro 34775
   :gs 450.5 :track 97.14 :baro_rate -960 :squawk "6040"
   :lat 27.961166 :lon -83.975953 :messages 1848 :seen 0.4 :rssi -26.2})

(deftest raw-aircraft
  (testing "accepts a complete, well-formed entry"
    (is (m/validate schema/raw-aircraft cruising)))

  (testing "accepts alt_baro as a number or as the string \"ground\""
    (is (m/validate schema/raw-aircraft (assoc cruising :alt_baro 700)))
    (is (m/validate schema/raw-aircraft
                    (assoc cruising :alt_baro "ground"))))

  (testing "accepts a bare mode_s target that carries no alt_baro at all"
    (is (m/validate schema/raw-aircraft
                    {:hex "a10202" :type "mode_s"
                     :messages 74 :seen 3.5 :rssi -29.7})))

  (testing "rejects alt_baro strings other than \"ground\""
    (is (not (m/validate schema/raw-aircraft
                         (assoc cruising :alt_baro "36000")))))

  (testing "accepts an entry that has never reported lat/lon"
    (is (m/validate schema/raw-aircraft (dissoc cruising :lat :lon))))

  (testing "rejects coordinates that are off the planet"
    (is (not (m/validate schema/raw-aircraft (assoc cruising :lat 91))))
    (is (not (m/validate schema/raw-aircraft
                         (assoc cruising :lon -180.5)))))

  (testing "accepts a space-padded callsign, and an absent one"
    (is (m/validate schema/raw-aircraft (assoc cruising :flight "SWA349  ")))
    (is (m/validate schema/raw-aircraft (dissoc cruising :flight))))

  (testing "accepts a ~-prefixed non-ICAO hex (TIS-B / ADS-R)"
    (is (m/validate schema/raw-aircraft (assoc cruising :hex "~a1b2c3"))))

  (testing "rejects an entry with no usable hex identity"
    (is (not (m/validate schema/raw-aircraft (dissoc cruising :hex))))
    (is (not (m/validate schema/raw-aircraft (assoc cruising :hex nil))))
    (is (not (m/validate schema/raw-aircraft
                         (assoc cruising :hex "not-hex")))))

  (testing "squawk is a string of four octal digits; \"0000\" is
            meaningful and is not nil"
    (is (m/validate schema/raw-aircraft (assoc cruising :squawk "0000")))
    (is (m/validate schema/raw-aircraft (assoc cruising :squawk "7700")))
    (is (not (m/validate schema/raw-aircraft (assoc cruising :squawk 7700))))
    (is (not (m/validate schema/raw-aircraft
                         (assoc cruising :squawk "78AB"))))
    (is (not (m/validate schema/raw-aircraft
                         (assoc cruising :squawk "770")))))

  (testing "gs, track and baro_rate may each be absent independently"
    (doseq [field [:gs :track :baro_rate]]
      (is (m/validate schema/raw-aircraft (dissoc cruising field))
          (str field " absent must still validate"))))

  (testing "any field may be an explicit null"
    (doseq [field [:alt_baro :flight :lat :lon :squawk :gs :track
                   :baro_rate :seen :rssi]]
      (is (m/validate schema/raw-aircraft (assoc cruising field nil))
          (str field " null must still validate"))))

  (testing "tolerates the dozens of extra keys real payloads carry"
    (is (m/validate schema/raw-aircraft
                    (assoc cruising :nav_qnh 1013.6 :mlat [] :tisb []
                           :category "A3" :emergency "none")))))

(deftest plausibility
  (testing "cruise altitudes and below-sea-level fields are plausible"
    (is (m/validate schema/plausible-altitude-ft 34775))
    (is (m/validate schema/plausible-altitude-ft -1200)))

  (testing "an altitude no aircraft flies at is implausible"
    (is (not (m/validate schema/plausible-altitude-ft 400000)))
    (is (not (m/validate schema/plausible-altitude-ft -3000))))

  (testing "real ground speeds are plausible, including fractional knots
            and a parked zero"
    (is (m/validate schema/plausible-ground-speed-kt 450.5))
    (is (m/validate schema/plausible-ground-speed-kt 0)))

  (testing "supersonic-nonsense and negative ground speeds are implausible"
    (is (not (m/validate schema/plausible-ground-speed-kt 3000)))
    (is (not (m/validate schema/plausible-ground-speed-kt -5)))))
