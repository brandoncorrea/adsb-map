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

(deftest handler-exceptions-stay-inside
  (testing "an unhandled handler exception is a generic 500 — the
            exception message is a log line, never a response body"
    (let [handler  (routes/handler
                     {:state-lookup
                      (fn [_] (throw (ex-info "secret internal detail"
                                              {:feeder "dietpi.local"})))})
          response (handler (json-request "/api/aircraft/a1b2c3"))]
      (is (= 500 (:status response)))
      (is (= {:error "internal error"} (decode-body response))))))

(deftest static-serving
  (testing "the compiled frontend is served from resources/public"
    (let [response (empty-handler (json-request "/js/main.js"))]
      (is (= 200 (:status response)))
      (is (some? (:body response)))))
  (testing "an unknown path falls through to a 404"
    (let [response (empty-handler (json-request "/no/such/path"))]
      (is (= 404 (:status response))))))

;; ---------------------------------------------------------------------
;; Static asset caching (adsb-8cb).

(defn- static-request
  "A GET for a static asset, with whatever conditional headers are given."
  [uri headers]
  {:request-method :get
   :uri            uri
   :headers        headers})

(deftest static-assets-are-cacheable
  (testing "a static response says how it may be cached — a response that
            declines to say gets Cache-Control: private stamped on it by
            DigitalOcean's router, and is then cached by nobody"
    (let [response (empty-handler (static-request "/js/main.js" {}))]
      (is (= 200 (:status response)))
      (is (= routes/static-cache-control
             (get-in response [:headers "Cache-Control"])))))
  (testing "and it says WHEN it last changed, which is what makes the
            revalidation below cheap"
    (let [response (empty-handler (static-request "/js/main.js" {}))]
      (is (some? (get-in response [:headers "Last-Modified"]))))))

(deftest revalidation-costs-no-body
  (testing "a conditional GET for an unchanged asset is a 304 with NO body
            — without this the origin re-ships the whole ~1.2 MB bundle to
            a browser that already has it, just to say it is current"
    (let [warm     (empty-handler (static-request "/js/main.js" {}))
          modified (get-in warm [:headers "Last-Modified"])
          response (empty-handler
                     (static-request "/js/main.js"
                                     {"if-modified-since" modified}))]
      (is (= 304 (:status response)))
      (is (nil? (:body response)))))
  (testing "the 304 carries the caching header too — a 304 that omits it
            leaves a cache to fall back on its own heuristics"
    (let [warm     (empty-handler (static-request "/js/main.js" {}))
          modified (get-in warm [:headers "Last-Modified"])
          response (empty-handler
                     (static-request "/js/main.js"
                                     {"if-modified-since" modified}))]
      (is (= routes/static-cache-control
             (get-in response [:headers "Cache-Control"])))))
  (testing "a stale conditional GET still gets the full body — the point is
            to skip transfers, never to withhold a bundle that CHANGED"
    (let [response (empty-handler
                     (static-request
                       "/js/main.js"
                       {"if-modified-since" "Tue, 01 Jan 2019 00:00:00 GMT"}))]
      (is (= 200 (:status response)))
      (is (some? (:body response))))))

(deftest caching-never-touches-the-stream
  (testing "the SSE response gets no Cache-Control from the static branch.
            An event-stream is not a document: it has no Last-Modified, it
            is never complete, and a 304 against it is a category error.
            The caching middleware wraps the resource handler ONLY"
    (let [handler  (routes/handler
                     {:stream-connect (constantly {:status  200
                                                   :headers {}
                                                   :body    ::channel})})
          response (handler (static-request "/api/stream" {}))]
      (is (= 200 (:status response)))
      (is (nil? (get-in response [:headers "Cache-Control"])))
      (is (= ::channel (:body response)))))
  (testing "and a conditional GET cannot turn the stream into a 304"
    (let [handler  (routes/handler
                     {:stream-connect (constantly {:status  200
                                                   :headers {}
                                                   :body    ::channel})})
          response (handler
                     (static-request
                       "/api/stream"
                       {"if-modified-since" "Tue, 01 Jan 2019 00:00:00 GMT"}))]
      (is (= 200 (:status response)))
      (is (= ::channel (:body response))))))

(deftest a-missing-asset-still-404s
  (testing "the caching middleware passes a resource MISS through as nil so
            the 404 default handler downstream still runs — wrapping it in
            a response here would shadow the 404"
    (let [response (empty-handler
                     (static-request "/no/such/path"
                                     {"if-modified-since"
                                      "Tue, 01 Jan 2019 00:00:00 GMT"}))]
      (is (= 404 (:status response))))))
