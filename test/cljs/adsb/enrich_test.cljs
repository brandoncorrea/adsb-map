(ns adsb.enrich-test
  (:require [adsb.enrich :as enrich]
            [adsb.events]
            [clojure.test :refer-macros [deftest testing is]]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]))

(deftest enrichable-prefix-accepts-only-plain-icao
  (testing "a clean six-hex address yields its three-char shard prefix"
    (is (= "abc" (enrich/enrichable-prefix "abc0e4")))
    (is (= "a1d" (enrich/enrichable-prefix "a1d645"))))
  (testing "case is folded before sharding"
    (is (= "abc" (enrich/enrichable-prefix "ABC0E4"))))
  (testing "non-ICAO and malformed addresses are not enrichable"
    (is (nil? (enrich/enrichable-prefix "~abc0e")) "TIS-B/ADS-R tilde target")
    (is (nil? (enrich/enrichable-prefix "abc")) "too short")
    (is (nil? (enrich/enrichable-prefix "abc0e4f")) "too long")
    (is (nil? (enrich/enrichable-prefix "abcxyz")) "non-hex")
    (is (nil? (enrich/enrichable-prefix nil)) "nil")))

(def ^:private record
  {"t" "B744"
   "d" "Boeing 747-400"
   "r" "N570UP"
   "o" "United Parcel Service"})

(deftest record-for-finds-a-known-hex
  (testing "a loaded shard yields the record for a hex it carries"
    (let [shards {"abc" {"abc0e4" record}}]
      (is (= record (enrich/record-for shards "abc0e4")))))
  (testing "the lookup folds case on the queried hex too"
    (let [shards {"abc" {"abc0e4" record}}]
      (is (= record (enrich/record-for shards "ABC0E4"))))))

(deftest record-for-degrades-every-not-known-path-to-nil
  (testing "a shard that was never fetched"
    (is (nil? (enrich/record-for {} "abc0e4"))))
  (testing "a shard still loading"
    (is (nil? (enrich/record-for {"abc" :loading} "abc0e4"))))
  (testing "a shard that failed (404 / network / malformed) → :absent"
    (is (nil? (enrich/record-for {"abc" :absent} "abc0e4"))))
  (testing "a loaded shard that simply has no entry for this hex"
    (is (nil? (enrich/record-for {"abc" {"abc0e5" record}} "abc0e4"))))
  (testing "a non-enrichable address is never looked up"
    (is (nil? (enrich/record-for {"~ab" {"~abc0e" record}} "~abc0e")))))

(deftest record-accessors-read-named-facts
  (is (= "B744" (enrich/type-code record)))
  (is (= "Boeing 747-400" (enrich/type-desc record)))
  (is (= "N570UP" (enrich/registration record)))
  (is (= "United Parcel Service" (enrich/operator record)))
  (testing "a nil record yields nil facts, never a throw"
    (is (nil? (enrich/type-code nil)))
    (is (nil? (enrich/registration nil)))
    (is (nil? (enrich/operator nil)))))

(deftest ensure-loads-a-shard-once-and-only-once
  (testing "ensure marks the prefix :loading and fires the fetch effect once"
    (rf-test/run-test-sync
      (let [fetched (atom [])]
        (rf/reg-fx :enrich/fetch! (fn [prefix] (swap! fetched conj prefix)))
        (rf/dispatch [:app/initialize-db])
        (rf/dispatch [:enrich/ensure "abc0e4"])
        (is (= ["abc"] @fetched))
        (is (= :loading (get @(rf/subscribe [:enrich/shards]) "abc")))
        (rf/dispatch [:enrich/ensure "abc0ff"])
        (is (= ["abc"] @fetched))
        (rf/dispatch [:enrich/ensure "a1d645"])
        (is (= ["abc" "a1d"] @fetched))
        (rf/dispatch [:enrich/ensure "~abc0e"])
        (is (= ["abc" "a1d"] @fetched))))))

(deftest shard-loaded-and-failed-write-the-cache
  (rf-test/run-test-sync
    (rf/dispatch [:app/initialize-db])
    (testing "shard-loaded stores the record map"
      (rf/dispatch [:enrich/shard-loaded "abc" {"abc0e4" record}])
      (is (= record (enrich/record-for @(rf/subscribe [:enrich/shards]) "abc0e4"))))
    (testing "shard-failed stores :absent"
      (rf/dispatch [:enrich/shard-failed "a1d"])
      (is (= :absent (get @(rf/subscribe [:enrich/shards]) "a1d"))))))

(deftest record-sub-reads-the-cache
  (rf-test/run-test-sync
    (rf/dispatch [:app/initialize-db])
    (rf/dispatch [:enrich/shard-loaded "abc" {"abc0e4" record}])
    (is (= record @(rf/subscribe [:enrich/record "abc0e4"])))
    (is (nil? @(rf/subscribe [:enrich/record "abc0e5"])))
    (is (nil? @(rf/subscribe [:enrich/record "a1d645"])))))

(deftest fetch-found-loads-the-fields
  (testing "a resolved fetch lands the shard and its fields become visible"
    (rf-test/run-test-async
      (rf/dispatch [:app/initialize-db])
      (with-redefs [enrich/get-shard! (fn [_] (js/Promise.resolve {"abc0e4" record}))]
        (enrich/fetch-shard! "abc"))
      (rf-test/wait-for [:enrich/shard-loaded]
        (is (= record (enrich/record-for @(rf/subscribe [:enrich/shards]) "abc0e4")))))))

(deftest fetch-missing-shard-becomes-absent
  (testing "a rejected fetch (404 / network) degrades to :absent, never throws"
    (rf-test/run-test-async
      (rf/dispatch [:app/initialize-db])
      (with-redefs [enrich/get-shard! (fn [_] (js/Promise.reject (js/Error. "HTTP 404")))]
        (enrich/fetch-shard! "abc"))
      (rf-test/wait-for [:enrich/shard-failed]
        (is (= :absent (get @(rf/subscribe [:enrich/shards]) "abc")))
        (is (nil? (enrich/record-for @(rf/subscribe [:enrich/shards]) "abc0e4")))))))

(deftest fetch-malformed-payload-becomes-absent
  (testing "a shard that parses to something other than a JSON object → absent"
    (rf-test/run-test-async
      (rf/dispatch [:app/initialize-db])
      (with-redefs [enrich/get-shard! (fn [_] (js/Promise.resolve "not-an-object"))]
        (enrich/fetch-shard! "abc"))
      (rf-test/wait-for [:enrich/shard-failed]
        (is (= :absent (get @(rf/subscribe [:enrich/shards]) "abc")))))))
