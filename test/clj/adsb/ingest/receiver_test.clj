(ns adsb.ingest.receiver-test
  (:require [adsb.ingest.receiver :as receiver]
            [adsb.test-feed :as feed]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]))

(def ^:private fixture (slurp "test/resources/receiver-sample.json"))
(def ^:private fixture-position {:geo/lat 28.0 :geo/lon -82.5})

(defn- receiver-json-handler [body]
  (fn [{:keys [uri]}]
    (if (= "/data/receiver.json" uri)
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    body}
      {:status 404 :body "not here"})))

(def ^:private unreachable-url "http://192.0.2.1:1")
(def ^:private short-timeout-ms 200)

(deftest fetch-position!
  (testing "reads the receiver position from the feeder's receiver.json"
    (feed/with-http-server
      (receiver-json-handler fixture)
      (fn [base-url]
        (is (= fixture-position (receiver/fetch-position! base-url))))))

  (testing "a non-200 answer yields nil, not a throw — the gate is
            disabled, the boot is not failed"
    (feed/with-http-server
      (fn [_] {:status 503 :body "unavailable"})
      (fn [base-url]
        (is (nil? (receiver/fetch-position! base-url))))))

  (testing "an unreachable feeder yields nil"
    (is (nil? (receiver/fetch-position! unreachable-url
                                        short-timeout-ms))))

  (testing "a body that is not JSON yields nil"
    (feed/with-http-server
      (receiver-json-handler "<html>surprise</html>")
      (fn [base-url]
        (is (nil? (receiver/fetch-position! base-url))))))

  (testing "a receiver.json without lat/lon yields nil — a feeder that
            does not know where it is offers no horizon"
    (feed/with-http-server
      (receiver-json-handler "{\"refresh\": 1000, \"version\": \"3.16.5\"}")
      (fn [base-url]
        (is (nil? (receiver/fetch-position! base-url))))))

  (testing "an out-of-range coordinate is rejected, never clamped"
    (feed/with-http-server
      (receiver-json-handler "{\"lat\": 999.0, \"lon\": -82.5}")
      (fn [base-url]
        (is (nil? (receiver/fetch-position! base-url)))))))

(deftest fetch-position-sends-auth-headers
  (testing "the feeder-auth headers ride the receiver.json request too, so a
            token-gated tunnel serves the receiver position"
    (let [seen (atom nil)]
      (feed/with-http-server
        (fn [{:keys [uri headers]}]
          (reset! seen headers)
          (if (= "/data/receiver.json" uri)
            {:status  200
             :headers {"Content-Type" "application/json"}
             :body    fixture}
            {:status 404 :body "not here"}))
        (fn [base-url]
          (is (= fixture-position
                 (receiver/fetch-position!
                   base-url receiver/default-timeout-ms
                   {"CF-Access-Client-Id"     "abc123.access"
                    "CF-Access-Client-Secret" "supersecretvalue"})))
          (is (= "abc123.access" (get @seen "cf-access-client-id")))
          (is (= "supersecretvalue" (get @seen "cf-access-client-secret"))))))))

(deftest env-position
  (testing "reads the position from ADSB_RECEIVER_LAT/LON"
    (is (= {:geo/lat 28.25 :geo/lon -82.75}
           (receiver/env-position {"ADSB_RECEIVER_LAT" "28.25"
                                   "ADSB_RECEIVER_LON" "-82.75"}))))

  (testing "requires BOTH coordinates — half a position is none"
    (is (nil? (receiver/env-position {"ADSB_RECEIVER_LAT" "28.25"})))
    (is (nil? (receiver/env-position {"ADSB_RECEIVER_LON" "-82.75"}))))

  (testing "non-numeric and out-of-range values yield nil, not a throw"
    (is (nil? (receiver/env-position {"ADSB_RECEIVER_LAT" "here"
                                      "ADSB_RECEIVER_LON" "-82.75"})))
    (is (nil? (receiver/env-position {"ADSB_RECEIVER_LAT" "91.0"
                                      "ADSB_RECEIVER_LON" "-82.75"}))))

  (testing "an empty environment yields nil"
    (is (nil? (receiver/env-position {})))))

(deftest resolve-position!
  (testing "the environment override wins over the feeder's receiver.json"
    (feed/with-http-server
      (receiver-json-handler fixture)
      (fn [base-url]
        (is (= {:geo/lat 28.25 :geo/lon -82.75}
               (receiver/resolve-position!
                 {:env      {"ADSB_RECEIVER_LAT" "28.25"
                             "ADSB_RECEIVER_LON" "-82.75"}
                  :base-url base-url}))))))

  (testing "without an override, the feeder's receiver.json is used"
    (feed/with-http-server
      (receiver-json-handler fixture)
      (fn [base-url]
        (is (= fixture-position
               (receiver/resolve-position! {:env      {}
                                            :base-url base-url}))))))

  (testing "no override and no reachable feeder disables the gate:
            nil, with exactly one log line saying so"
    (let [warnings (atom [])]
      (with-redefs [log/log* (fn [_ _ _ message]
                               (swap! warnings conj message))]
        (is (nil? (receiver/resolve-position!
                    {:env        {}
                     :base-url   unreachable-url
                     :timeout-ms short-timeout-ms}))))
      (is (= 1 (count @warnings)))
      (is (re-find #"range gate disabled" (str (first @warnings))))))

  (testing "no base-url at all (feeder unconfigured) also disables the
            gate quietly with nil"
    (with-redefs [log/log* (fn [_ _ _ _] nil)]
      (is (nil? (receiver/resolve-position! {:env {}}))))))
