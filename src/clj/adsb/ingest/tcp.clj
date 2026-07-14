(ns adsb.ingest.tcp
  "The reconnecting socket reader shared by the streaming Sources.

  SBS on :30003 and Beast on :30005 are different wire formats over the
  same transport: a TCP socket held open across polls, read on a daemon
  thread, folding each message into a per-ICAO accumulator picture at its
  arrival instant. This namespace owns everything transport but format —
  dialing, reconnecting after a drop, the reader thread lifecycle, and the
  unreachable-vs-quiet fetch! contract — so each Source ns is left with
  only its own parsing (adsb.ingest.sbs, adsb.ingest.beast-source).

  THE SEAM is `:consume!`, a per-connection pump. Given the connected
  socket and the reader state, it reads until EOF/close/stop and folds
  what it decodes into `:picture` (the accumulator atom the Source owns)
  at `(clock)`. serve-connection! wraps it with connect/mark-connected/
  mark-disconnected; the format ns supplies only the pump.

  UNREACHABLE vs QUIET (source/fetch!). While the socket is not connected
  — never established, or dropped and not yet reconnected — fetch! throws
  ::unreachable so the poll loop's backoff engages (adsb.ingest.poll). A
  connected-but-silent feed is NOT unreachable: fetch! returns the current
  snapshot, which ages out on its own as the silence lengthens. The reader
  reconnects after a drop, so a transient outage self-heals into a fetch!
  that stops throwing."
  (:require [adsb.accumulator :as accumulator])
  (:import (java.net InetSocketAddress Socket)))

(def ^:const default-connect-timeout-ms 5000)

(def ^:const default-reconnect-ms
  "How long the reader waits before re-dialing a dropped socket. The poll
  loop backs off on its own while fetch! throws; this just keeps the
  reader from hot-looping a refused connection."
  1000)

;; ---------------------------------------------------------------------
;; Socket plumbing

(defn- connect!
  "Open a socket to host:port within the connect timeout, or throw."
  ^Socket [host port connect-timeout-ms]
  (doto (Socket.)
    (.connect (InetSocketAddress. ^String host (int port))
              (int connect-timeout-ms))))

(defn- sleep-quietly!
  "Sleep ms, returning nil early (not throwing) if the thread is
  interrupted — close! interrupts the reader to abort a reconnect wait."
  [ms]
  (try
    (Thread/sleep (long ms))
    (catch InterruptedException _ nil)))

(defn close-quietly!
  "Close a socket, swallowing any failure — used by close! to abort a
  blocked read, where the socket may already be gone."
  [^Socket socket]
  (try
    (some-> socket .close)
    (catch Throwable _ nil)))

;; ---------------------------------------------------------------------
;; The reader thread: dial, pump via :consume!, reconnect on drop

(defn- serve-connection!
  "Dial the feed and run its :consume! pump until the connection ends.
  Marks the Source connected on success and disconnected on the way out,
  storing any failure for fetch! to surface. Never throws."
  [{:keys [host port connect-timeout-ms consume!
           connected? last-error socket] :as state}]
  (try
    (let [sock (connect! host port connect-timeout-ms)]
      (reset! socket sock)
      (reset! last-error nil)
      (reset! connected? true)
      (consume! sock state))
    (catch Throwable e
      (reset! last-error e))
    (finally
      (reset! connected? false))))

(defn- run-reader!
  "The reader thread's body: serve the connection, and while the Source is
  still open, wait and reconnect after it ends. A dropped socket therefore
  self-heals without the poll loop re-opening the Source."
  [{:keys [running? reconnect-ms] :as state}]
  (loop []
    (when @running?
      (serve-connection! state)
      (when @running?
        (sleep-quietly! reconnect-ms)
        (recur)))))

(defn- start-reader! [{:keys [thread-name] :as state}]
  (let [thread (Thread. ^Runnable #(run-reader! state) ^String thread-name)]
    (.setDaemon thread true)
    (.start thread)
    thread))

;; ---------------------------------------------------------------------
;; The Source lifecycle — shared open!/fetch!/close! bodies. Each Source's
;; protocol methods delegate here; the record is the reader state map.

(defn open!
  "Start the reader thread and return the ready Source. Called once before
  the first fetch!."
  [{:keys [running? reader-thread] :as state}]
  (reset! running? true)
  (reset! reader-thread (start-reader! state))
  state)

(defn snapshot-or-throw!
  "fetch!: the current accumulator snapshot while connected (a live but
  silent feed still yields its aging snapshot), or an ::unreachable
  ex-info while disconnected so the poll loop backs off."
  [{:keys [connected? picture clock last-error host port]}]
  (if @connected?
    (accumulator/snapshot @picture (clock))
    (throw (ex-info "TCP feed unreachable"
                    {:type ::unreachable :host host :port port}
                    @last-error))))

(defn close!
  "Stop the reader and close the socket. Interrupts the reader so a blocked
  read or reconnect wait aborts at once. Called once after the last fetch!."
  [{:keys [running? socket reader-thread] :as state}]
  (reset! running? false)
  (close-quietly! @socket)
  (some-> ^Thread @reader-thread .interrupt)
  state)

(defn reader-state
  "The shared reader state: the caller's host/port and options folded
  together with the per-format `consume!` pump, `thread-name`, and the
  atoms the reader thread and fetch! read and write. Each Source wraps
  this in its record so its protocol methods can delegate to the lifecycle
  fns above.

  Options mirror every streaming Source's constructor:

    :connect-timeout-ms  socket connect timeout (default 5000)
    :reconnect-ms        pause before re-dialing a dropped socket
    :clock               0-arg fn returning epoch ms — injected so tests
                         drive freshness and age-out deterministically
                         (default the wall clock)"
  [host port {:keys [connect-timeout-ms reconnect-ms clock]
              :or   {connect-timeout-ms default-connect-timeout-ms
                     reconnect-ms        default-reconnect-ms
                     clock               #(System/currentTimeMillis)}}
   consume! thread-name]
  {:host               host
   :port               port
   :connect-timeout-ms connect-timeout-ms
   :reconnect-ms       reconnect-ms
   :clock              clock
   :consume!           consume!
   :thread-name        thread-name
   :picture            (atom {})
   :running?           (atom false)
   :connected?         (atom false)
   :last-error         (atom nil)
   :socket             (atom nil)
   :reader-thread      (atom nil)})
