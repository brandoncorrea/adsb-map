(ns adsb.ingest.streaming-source-contract
  "Shared Source-contract assertions for the streaming (TCP/WSS) Sources.
  SBS and Beast decode different wire formats, but past the decoder they
  present the SAME adsb.ingest.source/Source contract — connect, snapshot,
  count, reconnect — over the one adsb.ingest.tcp.TcpSource record. Its
  near-verbatim tests live here once, parameterized by the impl's `->source`
  and a feed harness. Each impl's test namespace keeps its own `deftest` —
  naming which implementation is under test — and delegates the body to one
  of these asserts, so a failure still points at the format that broke."
  (:require [adsb.ingest.source :as source]
            [adsb.test-feed :as feed]
            [clojure.test :refer [is testing]])
  (:import (clojure.lang ExceptionInfo)
           (java.net InetSocketAddress ServerSocket)))

(defn assert-quiet-feed-returns-a-snapshot!
  "`with-quiet-feed` runs its `(fn [host port])` body against a connected but
  silent feed of the impl's wire format."
  [->source with-quiet-feed]
  (testing "a connected feed that has said nothing yet is not unreachable —
            fetch! returns the (empty) snapshot rather than throwing"
    (with-quiet-feed
      (fn [host port]
        (let [src (source/open! (->source host port))]
          (try
            (let [batch (feed/wait-until #(feed/fetch-quietly src))]
              (is (vector? batch))
              (is (empty? batch)))
            (finally (source/close! src))))))))

(defn assert-fetch-throws-while-unreachable!
  [->source]
  (testing "a stream that never connects is unreachable, so fetch! throws and
            the poll loop's backoff engages"
    (let [server (doto (ServerSocket.)
                   (.bind (InetSocketAddress. "127.0.0.1" 0)))
          port   (.getLocalPort server)]
      (.close server)
      (let [src (source/open! (->source "127.0.0.1" port {:reconnect-ms 50}))]
        (try
          (is (thrown? ExceptionInfo (source/fetch! src)))
          (finally (source/close! src)))))))

(defn assert-metadata-reports-message-count!
  "`with-populated-feed` runs its `(fn [host port])` body against a feed that
  decodes to exactly `expected-count` messages; `opts` are the impl's
  construction options (e.g. a frozen clock)."
  [->source with-populated-feed opts expected-count]
  (testing "a streaming Source counts every message it decodes and exposes the
            running total through Metadata (adsb-3mw), so adsb.stats can
            difference it into a per-second rate on a streaming deployment
            rather than leaving the message rate permanently absent"
    (with-populated-feed
      (fn [host port]
        (let [src (source/open! (->source host port opts))]
          (try
            (is (= {:messages expected-count}
                   (feed/wait-until
                     (fn []
                       (let [m (source/metadata src)]
                         (when (= expected-count (:messages m)) m))))))
            (finally (source/close! src))))))))
