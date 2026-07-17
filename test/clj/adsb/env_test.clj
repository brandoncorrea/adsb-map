(ns adsb.env-test
  (:require [adsb.env :as env]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io File)
           (java.util HashMap)))

(defn- temp-file-with [contents]
  (let [f (File/createTempFile "adsb-env" ".env")]
    (.deleteOnExit f)
    (spit f contents)
    (str f)))

(deftest parse-test
  (testing "a plain key=value line"
    (is (= {"ADSB_ULTRAFEEDER_URL" "http://dietpi.local:8100"}
           (env/parse "ADSB_ULTRAFEEDER_URL=http://dietpi.local:8100"))))

  (testing "blank lines and # comments are skipped"
    (is (= {"A" "1"} (env/parse "# a comment\n\n   \nA=1\n"))))

  (testing "a leading `export ` is tolerated"
    (is (= {"A" "1"} (env/parse "export A=1"))))

  (testing "surrounding quotes are stripped, single or double"
    (is (= {"A" "1" "B" "2"} (env/parse "A=\"1\"\nB='2'"))))

  (testing "a value may contain = — only the first one separates"
    (is (= {"A" "b=c=d"} (env/parse "A=b=c=d"))))

  (testing "empty contents"
    (is (= {} (env/parse "")))))

(deftest merge-file-test
  (testing "the file fills gaps the environment leaves"
    (is (= {"A" "from-file"} (env/merge-file {} "A=from-file"))))

  (testing "a real environment variable always beats the file — this is the
            rule that keeps a stray .env from shadowing deployed config"
    (is (= {"A" "from-environment"}
           (env/merge-file {"A" "from-environment"} "A=from-file"))))

  (testing "gap-filling and precedence together"
    (is (= {"A" "from-environment" "B" "from-file"}
           (env/merge-file {"A" "from-environment"}
                           "A=from-file\nB=from-file")))))

(deftest read!-test
  (testing "no .env — the process environment, untouched"
    (is (= {"A" "1"} (env/read! "does/not/exist/.env" {"A" "1"}))))

  (testing "with a .env — backfilled, environment still winning"
    (let [f (temp-file-with "A=from-file\nB=from-file")]
      (is (= {"A" "from-environment" "B" "from-file"}
             (env/read! f {"A" "from-environment"})))))

  (testing "a java.util.Map environment (what System/getenv actually returns)
            is handled, not just a Clojure map"
    (let [f (temp-file-with "B=from-file")]
      (is (= {"A" "from-environment" "B" "from-file"}
             (env/read! f (HashMap. {"A" "from-environment"})))))))
