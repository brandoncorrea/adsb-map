(ns adsb.aircraft-test
  (:require
    [adsb.aircraft :as aircraft]
    #?(:clj  [clojure.test :refer [deftest testing is]]
       :cljs [cljs.test :refer-macros [deftest testing is]])))

(deftest stale?
  (testing "an aircraft heard within the threshold is still fresh"
    (is (not (aircraft/stale? {:aircraft/seen-at-ms 0}
                              aircraft/stale-threshold-ms))))

  (testing "an aircraft silent past the threshold is stale"
    (is (aircraft/stale? {:aircraft/seen-at-ms 0}
                         (inc aircraft/stale-threshold-ms)))))
