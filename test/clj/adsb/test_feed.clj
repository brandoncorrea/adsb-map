(ns adsb.test-feed
  "Shared JVM test harnesses: deadline polling, a quiet fetch, the fake TCP
  feeders (line/byte/restarting), and an ephemeral http-kit server. One copy
  of each lives here so the ingest and stream suites stop carrying
  near-identical ServerSocket and poll-loop boilerplate."
  (:require [adsb.ingest.source :as source]
            [org.httpkit.server :as http-kit])
  (:import (clojure.lang ExceptionInfo)
           (java.io OutputStreamWriter)
           (java.net InetSocketAddress ServerSocket)
           (java.nio.charset StandardCharsets)))

(defn wait-until
  "Poll `thunk` until it returns truthy or the deadline passes; return the
  truthy value, or nil at the deadline. `:timeout-ms` (default 2000) and
  `:sleep-ms` (default 20) tune the loop — the one deadline-poll the suite
  shares, replacing a handful of hand-rolled copies."
  ([thunk] (wait-until thunk {}))
  ([thunk {:keys [timeout-ms sleep-ms] :or {timeout-ms 2000 sleep-ms 20}}]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (or (thunk)
           (when (< (System/currentTimeMillis) deadline)
             (Thread/sleep (long sleep-ms))
             (recur)))))))

(defn fetch-quietly
  "Fetch a snapshot, swallowing the ExceptionInfo a not-yet-connected stream
  throws — callers poll until the batch they expect arrives."
  [src]
  (try (source/fetch! src) (catch ExceptionInfo _)))

(defn with-line-feed
  "Serve `lines` (each newline-terminated, US-ASCII) to one client over a
  fresh ServerSocket, then block on a read so the connection stays open until
  the body returns and the Source closes it. Calls `(f host port)`."
  [lines f]
  (let [server (doto (ServerSocket.)
                 (.bind (InetSocketAddress. "127.0.0.1" 0)))
        port   (.getLocalPort server)
        conn   (future
                 (with-open [client (.accept server)]
                   (let [w (OutputStreamWriter. (.getOutputStream client)
                                                StandardCharsets/US_ASCII)]
                     (doseq [line lines] (.write w (str line "\n")))
                     (.flush w)
                     (.read (.getInputStream client)))))]
    (try
      (f "127.0.0.1" port)
      (finally
        (future-cancel conn)
        (.close server)))))

(defn with-byte-feed
  "Serve `capture` bytes to one client in `chunk-size` chunks (default 512),
  pausing `sleep-ms` (default 1) between chunks so reassembly across reads is
  exercised, then block on a read to hold the connection open until the body
  returns. Calls `(f host port)`."
  ([^bytes capture f] (with-byte-feed capture {} f))
  ([^bytes capture {:keys [chunk-size sleep-ms] :or {chunk-size 512 sleep-ms 1}} f]
   (let [server (doto (ServerSocket.)
                  (.bind (InetSocketAddress. "127.0.0.1" 0)))
         port   (.getLocalPort server)
         conn   (future
                  (with-open [client (.accept server)]
                    (let [out (.getOutputStream client)]
                      (doseq [chunk (partition-all chunk-size capture)]
                        (.write out (byte-array chunk))
                        (.flush out)
                        (Thread/sleep (long sleep-ms)))
                      (.read (.getInputStream client)))))]
     (try
       (f "127.0.0.1" port)
       (finally
         (future-cancel conn)
         (.close server))))))

(defn with-restarting-feed
  "Serve a fresh `(next-lines!)` on every accept, reconnecting for as long as
  the body runs — the flapping-feed harness. Calls `(f host port)`."
  [next-lines! f]
  (let [server  (doto (ServerSocket.)
                  (.bind (InetSocketAddress. "127.0.0.1" 0)))
        port    (.getLocalPort server)
        serving (atom true)
        accept  (future
                  (while @serving
                    (let [client (.accept server)
                          w      (OutputStreamWriter. (.getOutputStream client)
                                                      StandardCharsets/US_ASCII)]
                      (doseq [line (next-lines!)] (.write w (str line "\n")))
                      (.flush w))))]
    (try
      (f "127.0.0.1" port)
      (finally
        (reset! serving false)
        (future-cancel accept)
        (.close server)))))

(defn with-http-server
  "Run `handler` on an ephemeral port and hand the body its base URL, stopping
  the server when the body returns. Calls `(f base-url)`."
  [handler f]
  (let [srv  (http-kit/run-server handler {:port 0 :legacy-return-value? false})
        port (http-kit/server-port srv)]
    (try
      (f (str "http://localhost:" port))
      (finally (http-kit/server-stop! srv)))))
