(ns adsb.ingest.tcp
  (:require [adsb.ingest.plausibility :as plausibility]
            [adsb.ingest.source :as source]
            [adsb.picture :as picture]
            [clojure.tools.logging :as log])
  (:import (java.net InetSocketAddress Socket)))

(def ^:const default-connect-timeout-ms 5000)
(def ^:const default-reconnect-ms 1000)
(def ^:const default-idle-timeout-ms 60000)
(def ^:const default-sweep-interval-ms 60000)

(defn sweep-due? [swept-at-ms now-ms]
  (or (nil? swept-at-ms)
      (<= default-sweep-interval-ms (- now-ms swept-at-ms))))

(defn close-quietly! [closeable]
  (try
    (some-> closeable .close)
    (catch Throwable _)))

(defn socket-transport [host port {:keys [connect-timeout-ms idle-timeout-ms]}]
  (let [socket (doto (Socket.)
                 (.connect (InetSocketAddress. ^String host (int port))
                           (int connect-timeout-ms))
                 (.setSoTimeout (int idle-timeout-ms)))]
    {:in     (.getInputStream socket)
     :close! #(close-quietly! socket)}))

(defn- close-connection! [connection]
  (when-let [close! (:close! connection)]
    (try
      (close!)
      (catch Throwable _))))

(defn- sleep-quietly [ms]
  (try
    (Thread/sleep (long ms))
    (catch InterruptedException _)))

(defn- sweep-picture! [{:keys [picture swept-at-ms]} now-ms]
  (when (sweep-due? @swept-at-ms now-ms)
    (reset! swept-at-ms now-ms)
    (swap! picture picture/sweep now-ms)))

(defn accumulate! [{:keys [picture on-delta messages] :as state} delta now-ms]
  (sweep-picture! state now-ms)
  (swap! messages inc)
  (let [merged (-> (swap! picture plausibility/accumulate-flagging-jumps
                          delta now-ms)
                   (get (:aircraft/icao delta)))]
    (when on-delta
      (try
        (on-delta merged now-ms)
        (catch Throwable e
          (log/error e "on-delta hook threw; delta not propagated"))))))

(defn- serve-connection!
  [{:keys [host port connect-timeout-ms idle-timeout-ms transport consume!
           running? connected? last-error connection] :as state}]
  (try
    (let [conn (transport host port {:connect-timeout-ms connect-timeout-ms
                                     :idle-timeout-ms    idle-timeout-ms})]
      (reset! connection conn)
      (when @running?
        (reset! last-error nil)
        (reset! connected? true)
        (consume! (:in conn) state)))
    (catch Throwable e
      (reset! last-error e))
    (finally
      (reset! connected? false)
      (close-connection! @connection))))

(defn- run-reader! [{:keys [running? reconnect-ms] :as state}]
  (loop []
    (when @running?
      (serve-connection! state)
      (when @running?
        (sleep-quietly reconnect-ms)
        (recur)))))

(defn- start-reader! [{:keys [thread-name] :as state}]
  (let [thread (Thread. ^Runnable #(run-reader! state) ^String thread-name)]
    (.setDaemon thread true)
    (.start thread)
    thread))

(defn open! [{:keys [running? reader-thread] :as state}]
  (reset! running? true)
  (reset! reader-thread (start-reader! state))
  state)

(defn snapshot-or-throw! [{:keys [connected? picture clock last-error host port]}]
  (if @connected?
    (picture/snapshot @picture (clock))
    (throw (ex-info "TCP feed unreachable"
                    {:type ::unreachable :host host :port port}
                    @last-error))))

(defn last-metadata [{:keys [messages]}] {:messages @messages})

(defn close! [{:keys [running? connection reader-thread] :as state}]
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
              :or   {transport          socket-transport
                     connect-timeout-ms default-connect-timeout-ms
                     idle-timeout-ms    default-idle-timeout-ms
                     reconnect-ms       default-reconnect-ms
                     clock              #(System/currentTimeMillis)}}
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
   :messages           (atom 0)
   :swept-at-ms        (atom nil)
   :running?           (atom false)
   :connected?         (atom false)
   :last-error         (atom nil)
   :connection         (atom nil)
   :reader-thread      (atom nil)})

;; The record IS the reader-state map, so the lifecycle fns above (and
;; tcp/accumulate!) destructure it directly. The protocol methods delegate
;; to those fns — the bare names below resolve to this namespace's vars, not
;; to source/Source's like-named methods, so there is no recursion.
(defrecord TcpSource [host port transport connect-timeout-ms idle-timeout-ms
                      reconnect-ms clock on-delta consume! thread-name
                      picture messages swept-at-ms running? connected?
                      last-error connection reader-thread]
  source/Source
  (open! [this] (open! this))
  (fetch! [this] (snapshot-or-throw! this))
  (close! [this] (close! this))
  source/Metadata
  (last-metadata [this] (last-metadata this)))

(defn ->source
  "A streaming Source over a TCP-style feed. `consume!` is the per-format
  pump (a fn of [InputStream state]) and `thread-name` names its reader
  thread; both are folded into the shared reader-state. See `reader-state`
  for the option map. SBS and Beast each supply their own consume! and
  become one-line constructors over this."
  ([host port consume! thread-name]
   (->source host port {} consume! thread-name))
  ([host port opts consume! thread-name]
   (map->TcpSource (reader-state host port opts consume! thread-name))))
