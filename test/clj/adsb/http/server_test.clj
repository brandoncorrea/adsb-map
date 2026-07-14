(ns adsb.http.server-test
  (:require
    [adsb.http.server :as server]
    [adsb.state :as state]
    [clojure.test :refer [deftest testing is use-fixtures]]
    [org.httpkit.client :as http]
    [org.httpkit.server :as http-kit]))

;; Port 0 asks the OS for a free ephemeral port, so this suite never
;; contends with a `bb dev` running on :8280.
(def ^:private ephemeral {:port 0})

(use-fixtures :each (fn [run] (try (run) (finally (server/stop!)))))

(deftest start-server!-hands-every-caller-its-own-server
  (testing "two callers get two servers on two ports, each with the options
            it actually asked for. The REPL global cannot do this: start!
            stays idempotent by returning whatever is already running and
            DISCARDING the options, so a second caller silently gets the
            first one's handler and the first one's port. Four namespaces
            once shared that atom, and a test cannot tell the server it
            asked for from the one it was handed (adsb-a07)."
    (let [a (server/start-server! ephemeral)
          b (server/start-server! ephemeral)]
      (try
        (is (not (identical? a b)))
        (is (not= (http-kit/server-port a) (http-kit/server-port b))
            "its own port, not the running server's")
        (finally
          (server/stop-server! a)
          (server/stop-server! b))))))

(deftest stop-server!-blocks-until-the-socket-is-gone
  (testing "stop-server! returns only once the server has actually stopped.
            http-kit's server-stop! is asynchronous — it hands back a
            promise — so dropping that promise made `stopped` and `gone`
            two different instants (adsb-a07)."
    (let [srv  (server/start-server! ephemeral)
          port (http-kit/server-port srv)]
      (server/stop-server! srv)
      (is (thrown? java.net.ConnectException
                   (java.net.Socket. "localhost" ^int port))
          "the listening socket is closed by the time stop-server! returns"))))

(deftest stop-server!-is-nil-safe
  (is (nil? (server/stop-server! nil))))

(deftest start-is-idempotent
  (testing "a second start! is a no-op returning the same server"
    (let [first-server  (server/start! ephemeral)
          second-server (server/start! ephemeral)]
      (is (some? first-server))
      (is (identical? first-server second-server)))))

(deftest stop-is-idempotent
  (testing "stop! is safe to call when running and again when stopped"
    (server/start! ephemeral)
    (is (nil? (server/stop!)))
    (is (nil? (server/stop!)))))

(deftest restart-after-stop
  (testing "the server can be started again after being stopped"
    (let [first-server (server/start! ephemeral)]
      (server/stop!)
      (let [second-server (server/start! ephemeral)]
        (is (some? second-server))
        (is (not (identical? first-server second-server)))))))

(deftest default-state-lookup
  (testing "with no injected lookup, the aircraft API reads adsb.state"
    (try
      (state/apply-batch! [{:aircraft/icao   "a1b2c3"
                            :aircraft/seen-s 0.2}]
                          1720713600000)
      (let [srv      (server/start! ephemeral)
            port     (http-kit/server-port srv)
            response @(http/request
                        {:url     (str "http://localhost:" port
                                       "/api/aircraft/a1b2c3")
                         :method  :get
                         :headers {"Accept" "application/json"}})]
        (is (= 200 (:status response))))
      (finally (state/clear!)))))
