(ns adsb.enrich-test
  "Airframe enrichment (adsb.enrich). Proves the three faces of the feature:
  the pure lookup (found → fields; a missing, loading, absent, or malformed
  shard → nil), the event/effect wiring (ensure loads a shard once and only
  once, failures record :absent), and the async fetch itself, driven through
  a FAKED fetch seam so no real network is touched — found resolves to fields,
  a rejected fetch and a malformed payload both degrade to absent.

  The through-line is the degradation contract: enrichment is third-party
  reference DATA, so every not-known-here path — no database, a 404, garbage
  JSON — lands on the same nil the UI dashes."
  (:require
    [adsb.enrich :as enrich]
    [adsb.events]                                 ; :app/initialize-db
    [cljs.test :refer-macros [deftest testing is]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]))

;; ---------------------------------------------------------------------
;; Pure: shard addressing.

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

;; ---------------------------------------------------------------------
;; Pure: the lookup and its degradation paths.

(def ^:private record {"t" "B744" "d" "Boeing 747-400"
                       "r" "N570UP" "o" "United Parcel Service"})

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

;; ---------------------------------------------------------------------
;; Events and effects.

(deftest ensure-loads-a-shard-once-and-only-once
  (testing "ensure marks the prefix :loading and fires the fetch effect once"
    (rf-test/run-test-sync
      (let [fetched (atom [])]
        (rf/reg-fx :enrich/fetch! (fn [prefix] (swap! fetched conj prefix)))
        (rf/dispatch [:app/initialize-db])

        (rf/dispatch [:enrich/ensure "abc0e4"])
        (is (= ["abc"] @fetched) "the shard is fetched")
        (is (= :loading (get @(rf/subscribe [:enrich/shards]) "abc"))
            "and marked in flight")

        ;; A second hex in the SAME shard must not re-fetch.
        (rf/dispatch [:enrich/ensure "abc0ff"])
        (is (= ["abc"] @fetched) "a known prefix is never fetched twice")

        ;; A different prefix does fetch.
        (rf/dispatch [:enrich/ensure "a1d645"])
        (is (= ["abc" "a1d"] @fetched) "a new prefix fetches")

        ;; A non-ICAO address fetches nothing.
        (rf/dispatch [:enrich/ensure "~abc0e"])
        (is (= ["abc" "a1d"] @fetched) "a tilde target is never fetched")))))

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
    (is (= record @(rf/subscribe [:enrich/record "abc0e4"])) "known hex")
    (is (nil? @(rf/subscribe [:enrich/record "abc0e5"])) "unknown hex in a known shard")
    (is (nil? @(rf/subscribe [:enrich/record "a1d645"])) "hex in an unfetched shard")))

;; ---------------------------------------------------------------------
;; The async fetch, through a faked seam. get-shard! is redef'd only around
;; the SYNCHRONOUS fetch-shard! call — fetch-shard! invokes get-shard! at once
;; and returns a promise, so the redef is in effect exactly when it matters;
;; the later dispatch is awaited via wait-for.

(deftest fetch-found-loads-the-fields
  (testing "a resolved fetch lands the shard and its fields become visible"
    (rf-test/run-test-async
      (rf/dispatch [:app/initialize-db])
      (with-redefs [enrich/get-shard! (fn [_] (js/Promise.resolve {"abc0e4" record}))]
        (enrich/fetch-shard! "abc"))
      (rf-test/wait-for [:enrich/shard-loaded]
        (is (= record (enrich/record-for @(rf/subscribe [:enrich/shards]) "abc0e4"))
            "found → fields")))))

(deftest fetch-missing-shard-becomes-absent
  (testing "a rejected fetch (404 / network) degrades to :absent, never throws"
    (rf-test/run-test-async
      (rf/dispatch [:app/initialize-db])
      (with-redefs [enrich/get-shard! (fn [_] (js/Promise.reject (js/Error. "HTTP 404")))]
        (enrich/fetch-shard! "abc"))
      (rf-test/wait-for [:enrich/shard-failed]
        (is (= :absent (get @(rf/subscribe [:enrich/shards]) "abc")))
        (is (nil? (enrich/record-for @(rf/subscribe [:enrich/shards]) "abc0e4"))
            "missing shard → absent")))))

(deftest fetch-malformed-payload-becomes-absent
  (testing "a shard that parses to something other than a JSON object → absent"
    (rf-test/run-test-async
      (rf/dispatch [:app/initialize-db])
      (with-redefs [enrich/get-shard! (fn [_] (js/Promise.resolve "not-an-object"))]
        (enrich/fetch-shard! "abc"))
      (rf-test/wait-for [:enrich/shard-failed]
        (is (= :absent (get @(rf/subscribe [:enrich/shards]) "abc"))
            "malformed → absent")))))
