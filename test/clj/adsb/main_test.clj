(ns adsb.main-test
  (:require
    [adsb.http.server :as server]
    [adsb.main :as main]
    [clojure.test :refer [deftest testing is]]))

(deftest env->config-defaults
  (testing "an empty environment yields the default port and no feeder url"
    (let [config (main/env->config {})]
      (is (= server/default-port (:port config)))
      (is (nil? (:ultrafeeder-url config))))))

(deftest env->config-reads-port
  (testing "PORT is parsed to an int; blank falls back to the default"
    (is (= 9000 (:port (main/env->config {"PORT" "9000"}))))
    (is (= server/default-port (:port (main/env->config {"PORT" "   "}))))))

(deftest env->config-captures-feeder-url
  (testing "ADSB_ULTRAFEEDER_URL is captured for adsb-nqf.1 to validate"
    (let [url    "http://ultrafeeder:8080/data/aircraft.json"
          config (main/env->config {"ADSB_ULTRAFEEDER_URL" url})]
      (is (= url (:ultrafeeder-url config))))))
