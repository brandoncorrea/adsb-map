(ns adsb.ingest.config-test
  (:require
    [adsb.ingest.config :as config]
    [clojure.test :refer [deftest testing is]]))

(defn- rejection [url]
  (try
    (config/validate-feeder-url url)
    ::no-throw
    (catch clojure.lang.ExceptionInfo e
      {:message (ex-message e) :data (ex-data e)})))

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
  (try
    (config/feeder-auth-headers env)
    ::no-throw
    (catch clojure.lang.ExceptionInfo e
      {:message (ex-message e) :data (ex-data e)})))

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
      (is (not (re-find (re-pattern secret) (str message)))
          "the secret must not appear in the exception message")
      (is (not (re-find (re-pattern secret) (pr-str data)))
          "the secret must not appear in the ex-data"))))

(deftest replay-source-selection
  (testing "ADSB_SOURCE=replay selects the fixture-replay Source, case-
            and whitespace-insensitively"
    (doseq [value ["replay" "REPLAY" "  replay  "]]
      (is (config/replay-source? value) (str "should select replay: " value))))
  (testing "unset (the default) and any other value keep the live feeder"
    (doseq [value [nil "" "ultrafeeder" "live"]]
      (is (not (config/replay-source? value))
          (str "should keep the ultrafeeder: " (pr-str value))))))
