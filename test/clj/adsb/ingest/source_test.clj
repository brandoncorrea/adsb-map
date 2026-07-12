(ns adsb.ingest.source-test
  (:require
    [adsb.ingest.source :as source]
    [clojure.test :refer [deftest testing is]]))

(deftest fn-source-holds-the-seam
  (testing "a function-backed Source implements open!/fetch!/close!"
    (let [batch [{:aircraft/icao "abc0e4"}]
          src   (source/fn-source (constantly batch))]
      (is (identical? src (source/open! src)))
      (is (= batch (source/fetch! src)))
      (is (identical? src (source/close! src))))))

(deftest fn-source-reflects-live-fetch
  (testing "fetch! is re-read each call, so a source can change over time"
    (let [calls (atom 0)
          src   (source/fn-source (fn [] (swap! calls inc)))]
      (is (= 1 (source/fetch! src)))
      (is (= 2 (source/fetch! src))))))

(deftest fn-source-propagates-throws
  (testing "a Source that cannot reach its feed throws out of fetch!"
    (let [src (source/fn-source (fn [] (throw (ex-info "down" {}))))]
      (is (thrown? clojure.lang.ExceptionInfo (source/fetch! src))))))
