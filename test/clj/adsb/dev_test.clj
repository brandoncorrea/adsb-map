(ns adsb.dev-test
  (:require
    [adsb.dev :as dev]
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]))

(deftest handle-request
  (testing "serves the stub page as HTML, whatever the request"
    (let [{:keys [status headers body]} (dev/handle-request {})]
      (is (= 200 status))
      (is (str/starts-with? (get headers "Content-Type") "text/html"))
      (is (str/includes? body "adsb")))))
