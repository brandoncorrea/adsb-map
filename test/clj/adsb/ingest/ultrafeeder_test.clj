(ns adsb.ingest.ultrafeeder-test
  "Exercises the ultrafeeder Source against a local stub server serving the
  recorded fixture — never the live feeder (docs/CLAUDE.md: the sky is not a
  fixture)."
  (:require
    [adsb.ingest.source :as source]
    [adsb.ingest.ultrafeeder :as ultrafeeder]
    [clojure.test :refer [deftest testing is]]
    [org.httpkit.server :as http-kit]))

(def ^:private fixture (slurp "test/resources/aircraft-sample.json"))

(defn- stub-handler [{:keys [uri]}]
  (if (= "/data/aircraft.json" uri)
    {:status 200 :headers {"Content-Type" "application/json"} :body fixture}
    {:status 404 :body "not here"}))

(defn- with-server
  "Run f with a base URL pointing at a stub server that returns `handler`."
  [handler f]
  (let [srv  (http-kit/run-server handler {:port 0 :legacy-return-value? false})
        port (http-kit/server-port srv)]
    (try
      (f (str "http://localhost:" port))
      (finally (http-kit/server-stop! srv)))))

(deftest fetches-and-coerces-the-fixture
  (testing "fetch! GETs aircraft.json and returns coerced domain aircraft"
    (with-server
      stub-handler
      (fn [base-url]
        (let [batch (source/fetch! (ultrafeeder/->source base-url))]
          (is (seq batch) "the recorded fixture yields aircraft")
          (is (every? :aircraft/icao batch)
              "every entry is a coerced, namespaced domain aircraft")
          ;; The fixture has heard-but-never-positioned targets; keep them.
          (is (some (complement :aircraft/position) batch)
              "position-less aircraft are kept, not dropped"))))))

(defn- capturing-handler
  "A stub that records the request headers it saw into `seen` before
  serving the fixture. http-kit lowercases inbound header names."
  [seen]
  (fn [{:keys [uri headers]}]
    (reset! seen headers)
    (if (= "/data/aircraft.json" uri)
      {:status 200 :headers {"Content-Type" "application/json"} :body fixture}
      {:status 404 :body "not here"})))

(deftest sends-auth-headers-when-configured
  (testing "the feeder-auth headers ride every request to the tunnel"
    (let [seen (atom nil)]
      (with-server
        (capturing-handler seen)
        (fn [base-url]
          (source/fetch!
            (ultrafeeder/->source
              base-url ultrafeeder/default-timeout-ms
              {"CF-Access-Client-Id"     "abc123.access"
               "CF-Access-Client-Secret" "supersecretvalue"}))
          (is (= "abc123.access" (get @seen "cf-access-client-id")))
          (is (= "supersecretvalue"
                 (get @seen "cf-access-client-secret"))))))))

(deftest omits-auth-headers-when-not-configured
  (testing "no service token means no CF-Access headers on the wire"
    (let [seen (atom nil)]
      (with-server
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
        (catch clojure.lang.ExceptionInfo e
          (let [dump (str (ex-message e) " "
                          (pr-str (ex-data e)) " "
                          (ex-message (or (ex-cause e) e)))]
            (is (not (re-find (re-pattern secret) dump))
                "the service-token secret must never surface in an exception")))))))

(deftest non-200-throws
  (testing "a non-200 status becomes an ex-info the poll loop can catch"
    (with-server
      (fn [_] {:status 503 :body "unavailable"})
      (fn [base-url]
        (is (thrown? clojure.lang.ExceptionInfo
                     (source/fetch! (ultrafeeder/->source base-url))))))))

(deftest unreachable-host-throws
  (testing "a request error (nothing listening) throws, not returns nil"
    ;; Reserved TEST-NET-1 address; connection fails fast within the timeout.
    (let [src (ultrafeeder/->source "http://192.0.2.1:1" 200)]
      (is (thrown? clojure.lang.ExceptionInfo (source/fetch! src))))))
