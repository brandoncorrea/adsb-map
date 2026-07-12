(ns adsb.main-test
  (:require
    [adsb.http.server :as server]
    [adsb.main :as main]
    [adsb.state :as state]
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
    [muuntaja.core :as muuntaja]
    [org.httpkit.client :as http]
    [org.httpkit.server :as http-kit])
  (:import
    (clojure.lang ExceptionInfo)
    (java.net URI)
    (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

;; A port nothing listens on, so the poller sees an immediately refused
;; connection: real outage behavior, never a live feeder.
(def ^:private unreachable-feeder "http://localhost:1")

(deftest env->config-defaults
  (testing "an empty environment yields the default port and no feeder url"
    (let [config (main/env->config {})]
      (is (= server/default-port (:port config)))
      (is (nil? (:ultrafeeder-url config))))))

(deftest env->config-reads-port
  (testing "PORT is parsed to an int; blank falls back to the default"
    (is (= 9000 (:port (main/env->config {"PORT" "9000"}))))
    (is (= server/default-port (:port (main/env->config {"PORT" "   "})))))
  (testing "a non-numeric PORT fails loudly at boot, not at bind time"
    (is (thrown-with-msg? ExceptionInfo #"PORT"
                          (main/env->config {"PORT" "eight"})))))

(deftest env->config-captures-feeder-url
  (testing "ADSB_ULTRAFEEDER_URL is captured for start! to validate"
    (let [url    "http://ultrafeeder:8080"
          config (main/env->config {"ADSB_ULTRAFEEDER_URL" url})]
      (is (= url (:ultrafeeder-url config)))))
  (testing "the environment rides along for the receiver override"
    (let [env {"ADSB_RECEIVER_LAT" "27.9"}]
      (is (= env (:env (main/env->config env)))))))

(deftest start-validates-the-feeder-url
  (testing "boot fails loudly, naming the env var, when the feeder url
            is missing"
    (is (thrown-with-msg? ExceptionInfo #"ADSB_ULTRAFEEDER_URL"
                          (main/start! {:port 0}))))
  (testing "boot fails loudly on a non-http(s) feeder url"
    (is (thrown-with-msg? ExceptionInfo #"ADSB_ULTRAFEEDER_URL"
                          (main/start! {:port            0
                                        :ultrafeeder-url "ftp://feeder"})))))

(defn- get-json [port path]
  (let [response @(http/request
                    {:url     (str "http://localhost:" port path)
                     :method  :get
                     :headers {"Accept" "application/json"}})]
    (assoc response
           :json (muuntaja/decode muuntaja/instance "application/json"
                                  (:body response)))))

(defn- head-stream
  "Open /api/stream just far enough to see its status and content type."
  [port]
  (let [request  (-> (HttpRequest/newBuilder
                       (URI. (str "http://localhost:" port "/api/stream")))
                     (.GET)
                     (.build))
        response (.send (HttpClient/newHttpClient) request
                        (HttpResponse$BodyHandlers/ofInputStream))
        result   {:status       (.statusCode response)
                  :content-type (-> (.headers response)
                                    (.firstValue "content-type")
                                    (.orElse ""))}]
    (.close (.body response))
    result))

(deftest boot-wiring
  (let [system (main/start! {:port            0
                             :ultrafeeder-url unreachable-feeder
                             :env             {}})
        port   (http-kit/server-port (:system/server system))]
    (try
      (testing "healthz reports the live poller's status, not a stub"
        (let [{:keys [status json]} (get-json port "/healthz")]
          (is (= 200 status))
          (is (contains? #{"starting" "down"} (:feeder-status json)))))
      (testing "the SSE stream is wired at /api/stream"
        (let [{:keys [status content-type]} (head-stream port)]
          (is (= 200 status))
          (is (str/starts-with? content-type "text/event-stream"))))
      (finally
        (main/stop! system)
        (state/clear!)))))
