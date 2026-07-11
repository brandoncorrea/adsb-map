(ns adsb.http.server-test
  (:require
    [adsb.http.server :as server]
    [clojure.test :refer [deftest testing is use-fixtures]]))

;; Port 0 asks the OS for a free ephemeral port, so this suite never
;; contends with a `bb dev` running on :8280.
(def ^:private ephemeral {:port 0})

(use-fixtures :each (fn [run] (try (run) (finally (server/stop!)))))

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
