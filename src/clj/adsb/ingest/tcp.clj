(ns adsb.ingest.tcp
  "The reconnecting stream reader shared by the streaming Sources.

  SBS on :30003 and Beast on :30005 are different wire formats over the
  same transport: a byte stream held open across polls, read on a daemon
  thread, folding each message into a per-ICAO accumulator picture at its
  arrival instant. This namespace owns everything transport but format —
  dialing, reconnecting after a drop, the reader thread lifecycle, stall
  detection, and the unreachable-vs-quiet fetch! contract — so each Source
  ns is left with only its own parsing (adsb.ingest.sbs,
  adsb.ingest.beast-source).

  THE TRANSPORT SEAM is `:transport`, a connect step. Called
  (transport host port {:keys [connect-timeout-ms idle-timeout-ms]}) it
  dials the feed and returns a connection map {:in InputStream :close!
  0-arg-fn}. The default is `socket-transport`, today's plain TCP socket,
  so the SBS/Beast Sources stream unchanged; the wss transport
  (adsb.ingest.wss) is the same shape over a websocket. The pumps keep
  reading a plain InputStream, oblivious to which transport produced it.

  THE FORMAT SEAM is `:consume!`, a per-connection pump. Given the
  connection's InputStream and the reader state, it reads until
  EOF/close/stop and folds what it decodes into `:picture` (the
  accumulator atom the Source owns) at `(clock)`. serve-connection! wraps
  it with connect/mark-connected/mark-disconnected/close; the format ns
  supplies only the pump.

  STALL DETECTION. A dead tunnel can leave the connection open but silent.
  Every transport arms its InputStream with an :idle-timeout-ms read
  deadline (the plain socket via SO_TIMEOUT, the websocket via a queue
  poll) so that :idle-timeout-ms of total silence throws out of the read,
  ending the connection exactly like a drop — the reader then reconnects,
  and a persistent stall converges to connected? = false and a throwing
  fetch!.

  UNREACHABLE vs QUIET (source/fetch!). While the stream is not connected
  — never established, or dropped and not yet reconnected — fetch! throws
  ::unreachable so the poll loop's backoff engages (adsb.ingest.poll). A
  connected-but-silent feed is NOT unreachable: fetch! returns the current
  snapshot, which ages out on its own as the silence lengthens. The reader
  reconnects after a drop, so a transient outage self-heals into a fetch!
  that stops throwing.

  THE DELTA HOOK is `:on-delta`, an OPTIONAL capability in the Metadata
  mould (adsb.ingest.source): a fn of [merged-aircraft now-ms] fired
  after every accumulate with the aircraft's FULL post-merge state — the
  seam the composition root uses to push each message to SSE clients the
  instant it lands (adsb-jpf, adsb.stream.broadcast/offer-delta!).
  Sources that take no hook (fn-source, replay, ultrafeeder — and any
  streaming Source constructed without one) need nothing: accumulate!
  no-ops on a nil hook. The hook RUNS ON THE READER THREAD, so it must
  never block; production hands off through a bounded queue and drops
  under pressure rather than stall the radio."
  (:require [adsb.accumulator :as accumulator]
            [clojure.tools.logging :as log])
  (:import (java.net InetSocketAddress Socket)))

(def ^:const default-connect-timeout-ms 5000)

(def ^:const default-reconnect-ms
  "How long the reader waits before re-dialing a dropped connection. The
  poll loop backs off on its own while fetch! throws; this just keeps the
  reader from hot-looping a refused connection."
  1000)

(def ^:const default-idle-timeout-ms
  "How long a connected stream may fall totally silent before the reader
  treats it as dead and reconnects. A live SBS/Beast feed emits many
  messages a second, so a full minute of silence means the transport (or,
  through the tunnel, the tunnel) has gone away without closing the
  socket — the stall this timeout exists to surface."
  60000)

;; ---------------------------------------------------------------------
;; The default transport: a plain TCP socket

(defn close-quietly!
  "Close a Closeable, swallowing any failure — used to abort a blocked
  read, where the underlying resource may already be gone."
  [closeable]
  (try
    (some-> closeable .close)
    (catch Throwable _ nil)))

(defn socket-transport
  "The default `:transport`: dial a plain TCP socket to host:port within
  the connect timeout and return the shared connection map {:in :close!}.
  SO_TIMEOUT is set to :idle-timeout-ms so a read that hears nothing for
  that long throws SocketTimeoutException — the stall signal
  serve-connection! turns into a drop and reconnect. Throws if the socket
  cannot be opened."
  [host port {:keys [connect-timeout-ms idle-timeout-ms]}]
  (let [socket (doto (Socket.)
                 (.connect (InetSocketAddress. ^String host (int port))
                           (int connect-timeout-ms))
                 (.setSoTimeout (int idle-timeout-ms)))]
    {:in     (.getInputStream socket)
     :close! #(close-quietly! socket)}))

;; ---------------------------------------------------------------------
;; Connection plumbing

(defn- close-connection!
  "Run a connection's :close! step, swallowing any failure — safe to call
  more than once (an already-closed transport is a no-op)."
  [connection]
  (try
    (some-> connection :close! (as-> close! (close!)))
    (catch Throwable _ nil)))

(defn- sleep-quietly!
  "Sleep ms, returning nil early (not throwing) if the thread is
  interrupted — close! interrupts the reader to abort a reconnect wait."
  [ms]
  (try
    (Thread/sleep (long ms))
    (catch InterruptedException _ nil)))

;; ---------------------------------------------------------------------
;; The accumulate step the format pumps share

(defn accumulate!
  "Fold one coerced delta, heard at now-ms, into the Source's picture,
  then hand the aircraft's FULL MERGED post-accumulate state to the
  optional :on-delta hook (ns docstring). Runs on the reader thread —
  the try is load-bearing: a hook that throws must cost that one
  notification, never the connection, or a broken subscriber would turn
  every message into a reconnect."
  [{:keys [picture on-delta]} delta now-ms]
  (let [merged (-> (swap! picture accumulator/accumulate delta now-ms)
                   (get (:aircraft/icao delta)))]
    (when on-delta
      (try
        (on-delta merged now-ms)
        (catch Throwable e
          (log/error e "on-delta hook threw; delta not propagated"))))))

;; ---------------------------------------------------------------------
;; The reader thread: dial via :transport, pump via :consume!, reconnect
;; on drop.

(defn- serve-connection!
  "Dial the feed through :transport and run its :consume! pump on the
  connection's InputStream until the connection ends. Marks the Source
  connected on success and disconnected on the way out, closing the
  connection and storing any failure for fetch! to surface. Never throws."
  [{:keys [host port connect-timeout-ms idle-timeout-ms transport consume!
           connected? last-error connection] :as state}]
  (try
    (let [conn (transport host port {:connect-timeout-ms connect-timeout-ms
                                     :idle-timeout-ms    idle-timeout-ms})]
      (reset! connection conn)
      (reset! last-error nil)
      (reset! connected? true)
      (consume! (:in conn) state))
    (catch Throwable e
      (reset! last-error e))
    (finally
      (reset! connected? false)
      (close-connection! @connection))))

(defn- run-reader!
  "The reader thread's body: serve the connection, and while the Source is
  still open, wait and reconnect after it ends. A dropped connection
  therefore self-heals without the poll loop re-opening the Source."
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
  "Stop the reader and close the connection. Interrupts the reader so a
  blocked read or reconnect wait aborts at once. Called once after the last
  fetch!."
  [{:keys [running? connection reader-thread] :as state}]
  (reset! running? false)
  (close-connection! @connection)
  (some-> ^Thread @reader-thread .interrupt)
  state)

(defn reader-state
  "The shared reader state: the caller's host/port and options folded
  together with the per-format `consume!` pump, `thread-name`, and the
  atoms the reader thread and fetch! read and write. Each Source wraps
  this in its record so its protocol methods can delegate to the lifecycle
  fns above.

  Options mirror every streaming Source's constructor:

    :transport           connect step (host port opts) -> {:in :close!}
                         (default socket-transport, a plain TCP socket);
                         adsb.ingest.wss supplies the websocket transport
    :connect-timeout-ms  dial timeout (default 5000)
    :idle-timeout-ms     silence before a connected stream is treated as
                         dead and reconnected (default 60000)
    :reconnect-ms        pause before re-dialing a dropped connection
    :clock               0-arg fn returning epoch ms — injected so tests
                         drive freshness and age-out deterministically
                         (default the wall clock)
    :on-delta            OPTIONAL fn of [merged-aircraft now-ms], fired
                         on the reader thread after every accumulate
                         with the aircraft's full post-merge state (ns
                         docstring; adsb-jpf). Default nil — no hook."
  [host port {:keys [transport connect-timeout-ms idle-timeout-ms
                     reconnect-ms clock on-delta]
              :or   {transport           socket-transport
                     connect-timeout-ms  default-connect-timeout-ms
                     idle-timeout-ms     default-idle-timeout-ms
                     reconnect-ms        default-reconnect-ms
                     clock               #(System/currentTimeMillis)}}
   consume! thread-name]
  {:host               host
   :port               port
   :transport          transport
   :connect-timeout-ms connect-timeout-ms
   :idle-timeout-ms    idle-timeout-ms
   :reconnect-ms       reconnect-ms
   :clock              clock
   :on-delta           on-delta
   :consume!           consume!
   :thread-name        thread-name
   :picture            (atom {})
   :running?           (atom false)
   :connected?         (atom false)
   :last-error         (atom nil)
   :connection         (atom nil)
   :reader-thread      (atom nil)})
