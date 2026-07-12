(ns adsb.http.routes-test
  (:require
    [adsb.http.routes :as routes]
    [clojure.test :refer [deftest testing is]]
    [muuntaja.core :as muuntaja]))

(def empty-handler
  "The assembled Ring handler with no dependencies injected — empty
  state, unknown feeder, no stream."
  (routes/handler {}))

(defn- json-request [uri]
  {:request-method :get
   :uri            uri
   :headers        {"accept" "application/json"}})

(defn- decode-body [response]
  (muuntaja/decode muuntaja/instance "application/json" (:body response)))

(deftest health-endpoint
  (testing "200 ok, honestly unknown feeder, when no poller is wired"
    (let [response (empty-handler (json-request "/healthz"))
          body     (decode-body response)]
      (is (= 200 (:status response)))
      (is (= "ok" (:status body)))
      (is (= "unknown" (:feeder-status body)))))
  (testing "reports the injected live poller status, not a stub"
    (let [handler  (routes/handler
                     {:feeder-status (constantly {:feeder/status :down})})
          response (handler (json-request "/healthz"))]
      (is (= "down" (:feeder-status (decode-body response)))))))

(deftest stream-route
  (testing "GET /api/stream without a wired broadcaster honestly 503s"
    (let [response (empty-handler (json-request "/api/stream"))]
      (is (= 503 (:status response)))))
  (testing "GET /api/stream reaches the injected stream handler"
    (let [handler  (routes/handler
                     {:stream-connect (constantly {:status 200
                                                   :body   {:ok true}})})
          response (handler (json-request "/api/stream"))]
      (is (= 200 (:status response))))))

(deftest coercion-rejects-garbage-icao
  (testing "a non-hex icao is rejected by coercion middleware with 400"
    (let [response (empty-handler (json-request "/api/aircraft/zzzzzz"))]
      (is (= 400 (:status response)))))
  (testing "a too-short icao is also a 400"
    (let [response (empty-handler (json-request "/api/aircraft/a1b2"))]
      (is (= 400 (:status response))))))

(deftest unknown-aircraft-404
  (testing "a well-formed icao with empty state honestly 404s"
    (let [response (empty-handler (json-request "/api/aircraft/a1b2c3"))]
      (is (= 404 (:status response)))))
  (testing "a ~-prefixed non-ICAO address is well-formed and 404s"
    (let [response (empty-handler (json-request "/api/aircraft/~a1b2c3"))]
      (is (= 404 (:status response))))))

(deftest injected-state-lookup
  (testing "aircraft-detail reads through the injected lookup, not a store"
    (let [aircraft {:aircraft/icao "a1b2c3"}
          handler  (routes/handler {:state-lookup {"a1b2c3" aircraft}})
          response (handler (json-request "/api/aircraft/a1b2c3"))]
      (is (= 200 (:status response)))
      (is (= "a1b2c3" (:aircraft/icao (decode-body response)))))))

(deftest static-serving
  (testing "the compiled frontend is served from resources/public"
    (let [response (empty-handler (json-request "/js/main.js"))]
      (is (= 200 (:status response)))
      (is (some? (:body response)))))
  (testing "an unknown path falls through to a 404"
    (let [response (empty-handler (json-request "/no/such/path"))]
      (is (= 404 (:status response))))))
