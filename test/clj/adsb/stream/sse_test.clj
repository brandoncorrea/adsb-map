(ns adsb.stream.sse-test
  (:require
    [adsb.stream.sse :as sse]
    [clojure.test :refer [deftest testing is]]))

(deftest event-frame
  (testing "frames an event as event/id/data lines closed by a blank line"
    (is (= "event: update\nid: 7\ndata: {\"at\":1}\n\n"
           (sse/event-frame "update" 7 "{\"at\":1}"))))

  (testing "a payload containing newlines becomes one data: line per
            line, so the framing survives"
    (is (= "event: update\nid: 1\ndata: a\ndata: b\n\n"
           (sse/event-frame "update" 1 "a\nb")))))

(deftest comment-frame
  (testing "a heartbeat comment is a `:` line EventSource ignores"
    (is (= ": hb\n\n" (sse/comment-frame "hb")))))
