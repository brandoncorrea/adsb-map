(ns adsb.main-test
  (:require [adsb.http.server :as server]
            [adsb.main :as main]
            [adsb.state :as state]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [muuntaja.core :as muuntaja]
            [org.httpkit.client :as http]
            [org.httpkit.server :as http-kit])
  (:import (clojure.lang ExceptionInfo)
           (java.net BindException URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

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

(deftest env->config-reads-the-dev-csp-flag
  (testing "the relaxed dev CSP is OFF unless the environment says the
            exact word — a security boundary does not get lowered by a
            typo, a leftover \"0\", or a plausible-looking synonym"
    (is (false? (:dev-csp? (main/env->config {}))))
    (is (false? (:dev-csp? (main/env->config {"ADSB_DEV_CSP" "0"}))))
    (is (false? (:dev-csp? (main/env->config {"ADSB_DEV_CSP" "false"}))))
    (is (false? (:dev-csp? (main/env->config {"ADSB_DEV_CSP" "yes"}))))
    (is (false? (:dev-csp? (main/env->config {"ADSB_DEV_CSP" ""})))))

  (testing "and on when it does — `bb dev` sets it; no deployment does"
    (is (true? (:dev-csp? (main/env->config {"ADSB_DEV_CSP" "true"}))))
    (is (true? (:dev-csp? (main/env->config {"ADSB_DEV_CSP" " TRUE "}))))))

(deftest env->config-captures-source
  (testing "ADSB_SOURCE is captured so start! can pick the ingest Source"
    (is (= "replay" (:source (main/env->config {"ADSB_SOURCE" "replay"}))))
    (is (nil? (:source (main/env->config {})))))
  (testing "ADSB_FEED_URL is captured for the streaming Sources to parse"
    (is (= "wss://sbs.bwawan.com"
           (:feed-url (main/env->config {"ADSB_FEED_URL" "wss://sbs.bwawan.com"}))))
    (is (nil? (:feed-url (main/env->config {}))))))

(deftest sse-options-resolves-the-four-knobs-from-the-env-map
  (testing "the four SSE knobs come from the .env-merged env map, so a value
            set only in .env — invisible to start!'s System/getenv fallback —
            still reaches the broadcaster (adsb-rgv)"
    (let [opts (main/sse-options {"ADSB_SSE_MAX_CLIENTS"     "7"
                                  "ADSB_SSE_MAX_PER_IP"      "2"
                                  "ADSB_TRUST_FORWARDED_FOR" "true"
                                  "ADSB_TRUSTED_PROXY_HOPS"  "3"})]
      (is (= 7 (:max-clients opts)))
      (is (= 2 (:max-per-ip opts)))
      (is (true? (:trust-forwarded? opts)))
      (is (= 3 (:trusted-proxy-hops opts)))))

  (testing "unset limits stay nil so start! falls through to its compiled
            defaults; an unset trust flag is false, never accidentally on"
    (let [opts (main/sse-options {})]
      (is (nil? (:max-clients opts)))
      (is (nil? (:max-per-ip opts)))
      (is (false? (:trust-forwarded? opts)))
      (is (nil? (:trusted-proxy-hops opts))))))

(deftest sse-knobs-set-only-in-env-reach-the-running-broadcaster
  (testing "booting from an env map that carries the four knobs — as
            env/read produces after backfilling .env — lands them in the
            running broadcaster's resolved limits and identity flags, the
            .env-only path start! used to ignore by reading System/getenv
            directly (adsb-rgv)"
    (let [system      (main/start! {:port   0
                                    :source "replay"
                                    :env    {"ADSB_SSE_MAX_CLIENTS"     "9"
                                             "ADSB_SSE_MAX_PER_IP"      "3"
                                             "ADSB_TRUST_FORWARDED_FOR" "true"
                                             "ADSB_TRUSTED_PROXY_HOPS"  "2"}})
          broadcaster (:system/broadcaster system)]
      (try
        (is (= {:max-clients 9 :max-per-ip 3} (:stream/limits broadcaster)))
        (is (true? (:stream/trust-forwarded? broadcaster)))
        (is (= 2 (:stream/trusted-proxy-hops broadcaster)))
        (finally
          (main/stop! system)
          (state/clear!))))))

(deftest start-fails-fast-on-a-misconfigured-stream-source
  (testing "ADSB_SOURCE=sbs without ADSB_FEED_URL fails loudly at boot,
            naming the env var — no dial is attempted"
    (is (thrown-with-msg? ExceptionInfo #"ADSB_FEED_URL"
                          (main/start! {:port 0 :source "sbs" :env {}}))))
  (testing "an unrecognized ADSB_SOURCE fails loudly, naming that env var"
    (is (thrown-with-msg? ExceptionInfo #"ADSB_SOURCE"
                          (main/start! {:port 0 :source "socket" :env {}})))))

(deftest start-validates-the-feeder-url
  (testing "boot fails loudly, naming the env var, when the feeder url
            is missing"
    (is (thrown-with-msg? ExceptionInfo #"ADSB_ULTRAFEEDER_URL"
                          (main/start! {:port 0}))))
  (testing "boot fails loudly on a non-http(s) feeder url"
    (is (thrown-with-msg? ExceptionInfo #"ADSB_ULTRAFEEDER_URL"
                          (main/start! {:port            0
                                        :ultrafeeder-url "ftp://feeder"})))))

(deftest a-boot-that-fails-leaves-nothing-running
  (testing "when the bind fails, the poller start! had ALREADY started is
            stopped rather than abandoned. It is a daemon thread and start!
            threw before returning a system, so nobody held a handle to it:
            it went on polling and writing adsb.state forever, and the next
            boot's poller raced it over the global picture (adsb-8lz). A
            second boot while `bb dev` holds the port is exactly how you
            meet this.

            The failing boot replays the fixture, so an orphaned poller
            WOULD write to the picture within a poll or two; the incumbent
            holds the port and points at an unreachable feeder, so it never
            writes and cannot mask the orphan."
    (let [incumbent (main/start! {:port            0
                                  :ultrafeeder-url unreachable-feeder
                                  :env             {}})
          taken     (http-kit/server-port (:system/server incumbent))]
      (try
        (is (thrown? BindException
                     (main/start! {:port   taken
                                   :source "replay"
                                   :env    {}})))
        (state/clear!)
        (Thread/sleep 1500)
        (is (empty? (state/snapshot)))
        (finally
          (main/stop! incumbent)
          (state/clear!))))))

(deftest stop!-takes-a-partial-system
  (testing "the failure path hands stop! a system missing the layers that
            never came up, so every layer is stopped only if it is there
            (adsb-8lz). An empty system is the degenerate case: nothing
            started, nothing to stop, no NPE."
    (is (nil? (main/stop! {})))))

(defn- get-json [port path]
  (let [response @(http/request
                    {:url     (str "http://localhost:" port path)
                     :method  :get
                     :headers {"Accept" "application/json"}})]
    (assoc response
      :json (muuntaja/decode muuntaja/instance "application/json"
                             (:body response)))))

(defn- head-stream [port]
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
      (testing "the aircraft API reads the live picture — start! injects
                adsb.state/lookup as the state-lookup, wiring the composition
                root does now that http.server no longer defaults it. The
                unreachable feeder never writes, so this hand-applied entry is
                the only thing in the picture."
        (state/apply-batch! [{:aircraft/icao "a1b2c3" :aircraft/seen-s 0.2}]
                            1720713600000)
        (let [{:keys [status]} (get-json port "/api/aircraft/a1b2c3")]
          (is (= 200 status))))
      (finally
        (main/stop! system)
        (state/clear!)))))

(deftest replay-mode-boots-without-a-feeder
  (testing "ADSB_SOURCE=replay boots with no feeder URL — the whole point
            is bb dev off the home network — and healthz turns ok once
            the fixture is polled into the picture"
    (let [system (main/start! {:port   0
                               :source "replay"
                               :env    {}})
          port   (http-kit/server-port (:system/server system))
          ok?    (fn [] (= "ok" (:feeder-status
                                  (:json (get-json port "/healthz")))))]
      (try
        (loop [tries 40]
          (when (and (pos? tries) (not (ok?)))
            (Thread/sleep 100)
            (recur (dec tries))))
        (testing "the replayed fixture reaches, so feeder-status is ok"
          (is (ok?)))
        (finally
          (main/stop! system)
          (state/clear!))))))
