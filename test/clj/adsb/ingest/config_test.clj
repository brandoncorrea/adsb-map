(ns adsb.ingest.config-test
  (:require [adsb.ingest.config :as config]
            [clojure.test :refer [deftest is testing]])
  (:import (clojure.lang ExceptionInfo)))

(defmacro try-or-ex [& body]
  `(try
     ~@body
     ::no-throw
     (catch ExceptionInfo e#
       {:message (ex-message e#)
        :data    (ex-data e#)})))

(defn- rejection [url]
  (try-or-ex (config/validate-feeder-url url)))

(deftest accepts-valid-http-and-https
  (testing "http and https base URLs pass and come back unchanged"
    (is (= "http://dietpi.local:8100"
           (config/validate-feeder-url "http://dietpi.local:8100")))
    (is (= "https://feeder.example.com"
           (config/validate-feeder-url "https://feeder.example.com")))))

(deftest strips-trailing-slash
  (testing "a trailing slash is stripped so the json path joins cleanly"
    (is (= "http://dietpi.local:8100"
           (config/validate-feeder-url "http://dietpi.local:8100/")))
    (is (= "http://dietpi.local:8100"
           (config/validate-feeder-url "http://dietpi.local:8100///")))))

(deftest rejects-missing-url
  (testing "nil and blank fail loudly, naming the env var"
    (doseq [missing [nil "" "   "]]
      (let [{:keys [message data]} (rejection missing)]
        (is (re-find #"ADSB_ULTRAFEEDER_URL" (str message)))
        (is (= config/feeder-url-env (:env data)))))))

(deftest rejects-non-http-schemes
  (testing "ftp, file, and schemeless URLs are rejected"
    (doseq [bad ["ftp://dietpi.local"
                 "file:///etc/passwd"
                 "dietpi.local:8100"]]
      (is (re-find #"ADSB_ULTRAFEEDER_URL"
                   (str (:message (rejection bad))))
          (str "should reject " bad)))))

(deftest rejects-hostless-url
  (testing "an http URL with no host is rejected"
    (is (re-find #"host" (str (:message (rejection "http:///data")))))))

(deftest rejects-unparseable-url
  (testing "a syntactically invalid URL is rejected, not thrown raw"
    (is (map? (rejection "http://bad host/with spaces")))))

(defn- auth-failure [env]
  (try-or-ex (config/feeder-auth-headers env)))

(deftest feeder-auth-headers-both-set
  (testing "both env vars set yields the two CF-Access service-token headers"
    (is (= {"CF-Access-Client-Id"     "abc123.access"
            "CF-Access-Client-Secret" "supersecretvalue"}
           (config/feeder-auth-headers
             {"ADSB_FEEDER_AUTH_ID"     "abc123.access"
              "ADSB_FEEDER_AUTH_SECRET" "supersecretvalue"}))))
  (testing "surrounding whitespace is trimmed off each value"
    (is (= {"CF-Access-Client-Id"     "id"
            "CF-Access-Client-Secret" "sec"}
           (config/feeder-auth-headers
             {"ADSB_FEEDER_AUTH_ID"     "  id  "
              "ADSB_FEEDER_AUTH_SECRET" "  sec  "})))))

(deftest feeder-auth-headers-neither-set
  (testing "no token configured yields nil — a trusted LAN feeder needs none"
    (is (nil? (config/feeder-auth-headers {})))
    (is (nil? (config/feeder-auth-headers {"ADSB_FEEDER_AUTH_ID"     ""
                                           "ADSB_FEEDER_AUTH_SECRET" "   "})))))

(deftest feeder-auth-headers-half-set-fails-loudly
  (testing "only one half of the credential is a boot-time misconfiguration"
    (doseq [env [{"ADSB_FEEDER_AUTH_ID" "abc123.access"}
                 {"ADSB_FEEDER_AUTH_SECRET" "supersecretvalue"}]]
      (let [{:keys [message data]} (auth-failure env)]
        (is (map? data) (str "should throw ex-info for " env))
        (is (re-find #"ADSB_FEEDER_AUTH_ID" (str message)))
        (is (re-find #"ADSB_FEEDER_AUTH_SECRET" (str message)))))))

(deftest feeder-auth-headers-never-leak-the-secret
  (testing "the boot-failure message and ex-data never carry the secret value"
    (let [secret "supersecretvalue"
          {:keys [message data]}
          (auth-failure {"ADSB_FEEDER_AUTH_SECRET" secret})]
      (is (not (re-find (re-pattern secret) (str message))))
      (is (not (re-find (re-pattern secret) (pr-str data)))))))

(deftest source-kind-classifies-each-source
  (testing "unset and \"poll\" both select the HTTP poll Source — the default"
    (doseq [value [nil "" "  " "poll" "POLL" "  Poll  "]]
      (is (= :poll (config/source-kind value)))))
  (testing "sbs, beast, and replay each select their Source, case-
            and whitespace-insensitively"
    (is (= :sbs (config/source-kind "sbs")))
    (is (= :sbs (config/source-kind "  SBS ")))
    (is (= :beast (config/source-kind "beast")))
    (is (= :beast (config/source-kind "BEAST")))
    (is (= :replay (config/source-kind "replay")))))

(deftest source-kind-rejects-an-unknown-source
  (testing "an unrecognized ADSB_SOURCE is a boot-time misconfiguration and
            fails loudly, naming the env var and the value"
    (let [{:keys [message data]}
          (try (config/source-kind "socket")
               (catch ExceptionInfo e
                 {:message (ex-message e) :data (ex-data e)}))]
      (is (re-find #"ADSB_SOURCE" (str message)))
      (is (re-find #"socket" (str message)))
      (is (= config/source-env (:env data))))))

(deftest parse-feed-url-tcp
  (testing "tcp://host:port yields the plain-socket descriptor"
    (is (= {:scheme :tcp :host "dietpi.local" :port 30003}
           (config/parse-feed-url "tcp://dietpi.local:30003")))))

(deftest parse-feed-url-wss
  (testing "wss://host yields the websocket descriptor, defaulting to 443"
    (let [{:keys [scheme host port uri]}
          (config/parse-feed-url "wss://sbs.bwawan.com")]
      (is (= :wss scheme))
      (is (= "sbs.bwawan.com" host))
      (is (= 443 port))
      (is (= "wss://sbs.bwawan.com" (str uri)))))
  (testing "an explicit wss port is honored"
    (is (= 8443 (:port (config/parse-feed-url "wss://sbs.bwawan.com:8443"))))))

(defn- feed-rejection [url]
  (try-or-ex (config/parse-feed-url url)))

(deftest parse-feed-url-fails-fast
  (testing "a blank or missing URL fails loudly, naming the env var"
    (doseq [missing [nil "" "   "]]
      (let [{:keys [message data]} (feed-rejection missing)]
        (is (re-find #"ADSB_FEED_URL" (str message)))
        (is (= config/feed-url-env (:env data))))))
  (testing "a wrong scheme (http/https/ws) is rejected — only tcp and wss"
    (doseq [bad ["http://feeder:8100" "https://feeder" "ws://feeder"]]
      (is (re-find #"tcp:// or wss://" (str (:message (feed-rejection bad)))))))
  (testing "a tcp URL with no port is rejected — the socket needs one"
    (is (re-find #"must include a port"
                 (str (:message (feed-rejection "tcp://dietpi.local"))))))
  (testing "an unparseable URL is rejected as an ex-info, not thrown raw"
    (is (map? (feed-rejection "tcp://bad host:3")))))
