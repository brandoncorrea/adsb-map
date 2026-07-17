(ns adsb.ingest.ultrafeeder-test
  (:require [adsb.ingest.source :as source]
            [adsb.ingest.ultrafeeder :as ultrafeeder]
            [adsb.test-feed :as feed]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log])
  (:import (clojure.lang ExceptionInfo)))

(def ^:private fixture (slurp "test/resources/aircraft-sample.json"))

(defn- stub-handler [{:keys [uri]}]
  (if (= "/data/aircraft.json" uri)
    {:status 200 :headers {"Content-Type" "application/json"} :body fixture}
    {:status 404 :body "not here"}))

(deftest fetches-and-coerces-the-fixture
  (testing "fetch! GETs aircraft.json and returns coerced domain aircraft"
    (feed/with-http-server
      stub-handler
      (fn [base-url]
        (let [batch (source/fetch! (ultrafeeder/->source base-url))]
          (is (seq batch))
          (is (every? :aircraft/icao batch))
          (is (some (complement :aircraft/position) batch)))))))

(defn- capturing-handler [seen]
  (fn [{:keys [uri headers]}]
    (reset! seen headers)
    (if (= "/data/aircraft.json" uri)
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    fixture}
      {:status 404
       :body   "not here"})))

(deftest logs-rejections-at-the-edge
  (testing "coerce is pure and hands rejections back as data; the edge is
            what logs them. A payload with one malformed entry drops that
            entry with exactly one warning and still delivers the rest —
            the boundary's core promise, now with logging at the src/clj edge"
    (let [warnings (atom [])
          body     (str "{\"aircraft\":["
                        "{\"hex\":\"abc0e4\",\"alt_baro\":34775},"
                        "{\"hex\":\"nothex\",\"alt_baro\":{}}]}")]
      (with-redefs [log/log* (fn [_ _ _ message] (swap! warnings conj message))]
        (feed/with-http-server
          (fn [_] {:status  200
                   :headers {"Content-Type" "application/json"}
                   :body    body})
          (fn [base-url]
            (let [batch (source/fetch! (ultrafeeder/->source base-url))]
              (is (= ["abc0e4"] (mapv :aircraft/icao batch)))))))
      (is (= 1 (count @warnings))
          "exactly one rejection, logged once at the edge"))))

(deftest sends-auth-headers-when-configured
  (testing "the feeder-auth headers ride every request to the tunnel"
    (let [seen (atom nil)]
      (feed/with-http-server
        (capturing-handler seen)
        (fn [base-url]
          (source/fetch!
            (ultrafeeder/->source
              base-url ultrafeeder/default-timeout-ms
              {"CF-Access-Client-Id"     "abc123.access"
               "CF-Access-Client-Secret" "supersecretvalue"}))
          (is (= "abc123.access" (get @seen "cf-access-client-id")))
          (is (= "supersecretvalue" (get @seen "cf-access-client-secret"))))))))

(deftest omits-auth-headers-when-not-configured
  (testing "no service token means no CF-Access headers on the wire"
    (let [seen (atom nil)]
      (feed/with-http-server
        (capturing-handler seen)
        (fn [base-url]
          (source/fetch! (ultrafeeder/->source base-url))
          (is (nil? (get @seen "cf-access-client-id")))
          (is (nil? (get @seen "cf-access-client-secret"))))))))

(deftest failure-path-never-leaks-the-secret
  (testing "an unreachable feeder throws without the secret in the ex chain"
    (let [secret "supersecretvalue"
          src    (ultrafeeder/->source
                   "http://192.0.2.1:1" 200
                   {"CF-Access-Client-Id"     "abc123.access"
                    "CF-Access-Client-Secret" secret})]
      (try
        (source/fetch! src)
        (is false "the unreachable feeder should throw")
        (catch ExceptionInfo e
          (let [dump (str (ex-message e) " "
                          (pr-str (ex-data e)) " "
                          (ex-message (or (ex-cause e) e)))]
            (is (not (re-find (re-pattern secret) dump)))))))))

(deftest non-200-throws
  (testing "a non-200 status becomes an ex-info the poll loop can catch"
    (feed/with-http-server
      (fn [_] {:status 503 :body "unavailable"})
      (fn [base-url]
        (is (thrown? ExceptionInfo
                     (source/fetch! (ultrafeeder/->source base-url))))))))

(deftest unreachable-host-throws
  (testing "a request error (nothing listening) throws, not returns nil"
    (let [src (ultrafeeder/->source "http://192.0.2.1:1" 200)]
      (is (thrown? ExceptionInfo (source/fetch! src))))))
