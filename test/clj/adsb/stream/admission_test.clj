(ns adsb.stream.admission-test
  (:require [adsb.stream.admission :as admission]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test]))

(deftest forwarded-ip
  (testing "one trusted hop — the proxy appended the peer it saw, so the
            rightmost entry is the client and anything left of it is the
            client's own writing"
    (is (= "1.2.3.4" (admission/forwarded-ip 1 "1.2.3.4")))
    (is (= "1.2.3.4" (admission/forwarded-ip 1 "9.9.9.9, 1.2.3.4"))))

  (testing "two trusted hops — the rightmost entry is the inner proxy;
            the client is one further left"
    (is (= "1.2.3.4" (admission/forwarded-ip 2 "1.2.3.4, 10.0.0.7")))
    (is (= "1.2.3.4"
           (admission/forwarded-ip 2 "9.9.9.9, 1.2.3.4, 10.0.0.7"))))

  (testing "a header shorter than the configured chain is a misconfiguration:
            the client index would reach past the left end into
            attacker-written territory, so forwarded-ip returns nil and
            client-ip falls to the trustworthy socket peer rather than
            trusting a spoofable entry (adsb-u7z)"
    (is (nil? (admission/forwarded-ip 5 "1.2.3.4, 10.0.0.7")))
    (is (nil? (admission/forwarded-ip 2 "1.2.3.4"))))

  (testing "no header, or nothing in it, is not an address"
    (is (nil? (admission/forwarded-ip 1 nil)))
    (is (nil? (admission/forwarded-ip 1 "")))
    (is (nil? (admission/forwarded-ip 1 " , , ")))
    (is (= "1.2.3.4" (admission/forwarded-ip 1 "  ,  , 1.2.3.4 ,"))))

  (testing "an IPv6 client survives the split (no colon confusion)"
    (is (= "2001:db8::1" (admission/forwarded-ip 1 "2001:db8::1")))))

(deftest socket-peer-ip-refuses-the-attacker-written-remote-addr
  (testing "when the socket peer cannot be read — here a request with no
            :async-channel, so the reflection has nothing to read — the
            identity is nil and an ERROR is logged. It NEVER falls back to
            ring's :remote-addr, which http-kit fills from the
            attacker-written X-Forwarded-For and Boundary 2 forbids for
            limits (adsb-u7z)"
    ;; The failure log is rate-limited by a process-global atom; reset it so
    ;; this assertion does not depend on what other tests logged first.
    (reset! @#'admission/last-socket-peer-log-ms 0)
    (log-test/with-log
      (let [ip (admission/socket-peer-ip {:remote-addr "1.2.3.4"})]
        (is (nil? ip))
        (is (log-test/logged? 'adsb.stream.admission
                              :error #"identity unavailable")))))

  (testing "client-ip on a direct connection inherits that refusal: a request
            we cannot socket-identify keys on nil, never on :remote-addr, so
            the per-IP cap can never be steered by a forged address"
    (is (nil? (admission/client-ip false 1 {:remote-addr "1.2.3.4"})))))

(deftest diagnose-client-ip!
  (let [request {:headers     {"cf-connecting-ip" "1.2.3.4"
                               "x-forwarded-for"  "1.2.3.4, 172.71.9.9"}
                 :remote-addr "10.0.0.9"}]
    (testing "a nil budget is the opt-out: the diagnostic logs nothing and
              does not throw"
      (is (nil? (admission/diagnose-client-ip!
                  {:stream/diagnose-remaining nil} request "1.2.3.4"))))

    (testing "with a budget the counter counts down once per connect and
              stops dead at zero rather than logging forever"
      (let [remaining {:stream/diagnose-remaining (atom 2)}]
        (dotimes [_ 5]
          (admission/diagnose-client-ip! remaining request "1.2.3.4"))
        (is (zero? @(:stream/diagnose-remaining remaining)))))))
