(ns adsb.http.server-test
  (:require [adsb.http.server :as server]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging.test :refer [logged? with-log]]
            [org.httpkit.server :as http-kit])
  (:import (java.net ConnectException Socket)))

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
      (is (thrown? ConnectException (Socket. "localhost" ^int port))))))

(deftest stop-server!-is-nil-safe
  (is (nil? (server/stop-server! nil))))

(deftest start-is-idempotent
  (testing "a second start! is a no-op returning the same server"
    (let [first-server  (server/start! ephemeral)
          second-server (server/start! ephemeral)]
      (is (some? first-server))
      (is (identical? first-server second-server)))))

(deftest start-warns-when-it-ignores-the-options-it-was-handed
  (testing "start! stays idempotent — the second caller gets the RUNNING
            server, not the one it described — but it no longer does so
            silently. Discarding options is harmless only while they match;
            a REPL restart asking for a different port, or for handler
            dependencies bound to a fresh broadcaster, otherwise got a server
            wired to the old ones with nothing said (adsb-12j)."
    (with-log
      (let [first-server  (server/start! ephemeral)
            second-server (server/start! (assoc ephemeral :dev-csp? true))]
        (is (identical? first-server second-server))
        (is (logged? 'adsb.http.server :warn #"ALREADY RUNNING"))))))

(deftest start-with-the-same-options-warns-about-nothing
  (testing "the warning is about a CONFLICT, not about a second call: an
            idempotent restart with the options already running has nothing
            to report and must stay quiet."
    (with-log
      (server/start! ephemeral)
      (server/start! ephemeral)
      (is (not (logged? 'adsb.http.server :warn #"ALREADY RUNNING"))))))

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
