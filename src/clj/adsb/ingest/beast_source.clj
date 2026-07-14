(ns adsb.ingest.beast-source
  "The Beast Source: hold ultrafeeder's port 30005 socket open and stream
  binary Mode-S into the shared per-ICAO accumulator.

  This is the integration layer of the Beast epic — it owns no parsing of
  its own, only the composition of three pieces already built and tested:

    bytes  --adsb.ingest.beast/frames-->    typed Beast frames
    :mode-s-long payload
           --adsb.ingest.mode-s/decode-->   an :aircraft/* delta (or nil)
    delta  --adsb.accumulator/accumulate--> the picture this Source owns

  and the reconnecting socket lifecycle, which is adsb.ingest.tcp's (shared
  with adsb.ingest.sbs). The read loop reads byte chunks off the socket's
  InputStream, threads Beast :carry across reads so a frame split between
  two reads is reassembled, threads cpr-state across frames so the CPR
  even/odd pairing survives, and folds every trustworthy delta into the
  picture at its arrival instant.

  ONLY :mode-s-long FRAMES ARE DECODED. adsb.ingest.mode-s consumes the
  14-byte DF17/DF18 extended-squitter payload and nothing else — Mode-A/C
  ('1', 2 bytes) and short Mode-S ('2', 7 bytes) carry no ICAO address it
  can read, so this Source drops them before decode rather than handing the
  decoder a payload it would only reject. Filtering here, not there, keeps
  the decoder's contract narrow and the wasted work out of the hot path.

  THE TRUST BOUNDARY is adsb.ingest.mode-s (CRC-24 parity, DF admission —
  docs/validation-boundaries.md); nothing Beast-shaped leaves this
  namespace. A corrupt or hostile frame yields a nil delta and is dropped
  inside the reader, never thrown past it, never crashing the stream. What
  reaches the accumulator is already a coerced, namespaced :aircraft/*
  delta carrying a valid :aircraft/icao.

  A FRAME'S PAYLOAD drives a decode + accumulate at message-arrival time
  (both read one `(clock)`), so freshness — and the CPR pairing window —
  is stamped when we hear a message, not when fetch! happens to run. The
  read runs on a daemon reader thread the Source owns; open! starts it,
  close! stops it. The transport (plain socket or, through the tunnel,
  adsb.ingest.wss), stall detection, and the unreachable-vs-quiet fetch!
  contract are all adsb.ingest.tcp's."
  (:require [adsb.ingest.beast :as beast]
            [adsb.ingest.mode-s :as mode-s]
            [adsb.ingest.source :as source]
            [adsb.ingest.tcp :as tcp])
  (:import (java.io InputStream)
           (java.util Arrays)))

(def ^:private read-buffer-size
  "Bytes read per socket read. Beast :carry reassembles whatever a read
  splits, so this only trades syscalls against memory."
  4096)

;; ---------------------------------------------------------------------
;; Decode + accumulate: only the long frames, threading CPR state.

(defn- long-frame?
  "true for the 14-byte extended-squitter frames adsb.ingest.mode-s reads;
  Mode-A/C and short Mode-S carry no decodable ICAO and are ignored."
  [frame]
  (= :mode-s-long (:beast/type frame)))

(defn- consume-frame!
  "Decode one long frame's payload at its arrival instant and fold a
  trustworthy delta into the picture — notifying the optional :on-delta
  hook with the merged aircraft (tcp/accumulate!). Returns the threaded
  cpr-state — nil delta (failed parity, out-of-scope DF, garbage) folds
  nothing but still advances the CPR pairing/pruning state."
  [{:keys [clock] :as state} frame cpr-state]
  (let [now-ms (clock)
        {:keys [delta cpr-state]} (mode-s/decode (:beast/payload frame)
                                                 now-ms cpr-state)]
    (when delta
      (tcp/accumulate! state delta now-ms))
    cpr-state))

(defn- fold-frames!
  "Fold every long frame in one read's batch into the picture, threading
  cpr-state across them. Returns the new cpr-state."
  [state frames cpr-state]
  (reduce (fn [cpr-state frame]
            (consume-frame! state frame cpr-state))
          cpr-state
          (filter long-frame? frames)))

(defn- read-frames!
  "Read byte chunks until EOF, the socket closes, or the Source stops,
  threading Beast :carry across reads and CPR state across frames, folding
  each trustworthy long-frame delta into the picture at its arrival
  instant. Returns on any stop; tcp reconnects if still running."
  [^InputStream in {:keys [running?] :as state}]
  (let [buffer (byte-array read-buffer-size)]
    (loop [carry     nil
           cpr-state nil]
      (when @running?
        (let [n (.read in buffer)]
          (when (pos? n)
            (let [chunk (Arrays/copyOfRange buffer 0 (int n))
                  {:keys [frames carry]} (beast/frames chunk carry)]
              (recur carry
                     (fold-frames! state frames cpr-state)))))))))

(defn- consume!
  "The tcp reader seam: pump the connection's Beast byte stream into the
  picture until the connection ends. tcp/serve-connection! owns closing the
  stream, so a read that throws (a drop, or the idle-timeout stall signal)
  unwinds straight to reconnect."
  [^InputStream in state]
  (read-frames! in state))

;; ---------------------------------------------------------------------
;; The Source

(defrecord BeastSource [host port transport connect-timeout-ms idle-timeout-ms
                        reconnect-ms clock on-delta consume! thread-name
                        picture running? connected? last-error connection
                        reader-thread]
  source/Source
  (open! [this] (tcp/open! this))
  (fetch! [this] (tcp/snapshot-or-throw! this))
  (close! [this] (tcp/close! this)))

(defn ->source
  "A Source streaming binary Mode-S from `host`:`port` (the ultrafeeder's
  port 30005, Beast format). open! starts the reader; fetch! returns the
  accumulated, coerced domain batch (throwing while disconnected so the
  poll loop backs off); close! stops the reader and closes the connection.

  Explicit host/port mirrors sbs/->source and ultrafeeder/->source;
  ADSB_SOURCE selection and the URL-to-transport wiring live in adsb.main /
  adsb.ingest.config. Options are adsb.ingest.tcp/reader-state's:
  :transport (default the plain-socket transport; adsb.ingest.wss/transport
  for the tunnel), :connect-timeout-ms, :idle-timeout-ms, :reconnect-ms,
  the injectable :clock, and the optional :on-delta hook fired with each
  message's merged aircraft (adsb-jpf; see adsb.ingest.tcp)."
  ([host port] (->source host port {}))
  ([host port opts]
   (map->BeastSource
    (tcp/reader-state host port opts consume! "adsb-beast-reader"))))
