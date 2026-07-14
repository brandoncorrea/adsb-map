(ns adsb.ingest.wss
  "The websocket transport for the streaming Sources — the same
  {:in InputStream :close!} connection shape adsb.ingest.tcp/socket-transport
  produces, but dialed over wss:// instead of a raw TCP socket.

  WHY. The home feeder's SBS (:30003) and Beast (:30005) TCP ports reach the
  cloud backend through a Cloudflare Tunnel, gated by a Cloudflare Access
  Service-Auth policy. A raw HTTP/1.1 websocket upgrade carrying the
  CF-Access-Client-Id / CF-Access-Client-Secret service token is accepted
  (101 Switching Protocols) and the raw feed then streams as binary
  websocket messages; anonymous requests get 403. The JDK's
  java.net.http.HttpClient.newWebSocketBuilder does exactly this upgrade with
  no extra dependency, so the JVM dials the edge directly — no cloudflared
  sidecar (adsb-elf).

  THE STREAM. A websocket message boundary is an arbitrary chunk boundary,
  exactly like a TCP read — the payload is the raw feed byte stream, nothing
  of ours to strip. The Listener copies each binary (and, defensively, text)
  message onto a queue; a queue-backed InputStream drains it, one message at
  a time, for the pump. onClose / onError end the stream, which flows into
  tcp's drop-and-reconnect path.

  BACKPRESSURE is consumer-driven, so a stalled consumer can never balloon
  memory. The Listener requests exactly one message at onOpen, and the
  InputStream requests the next only once it has drained the current one —
  so at most one data message is ever in flight past what the reader holds,
  no matter how fast the edge streams. Control frames (ping/pong) re-request
  as they are answered so they never starve the data demand; the JDK client
  also answers pings itself.

  STALL DETECTION. A read blocks at most :idle-timeout-ms for the next chunk
  and then throws SocketTimeoutException — the same stall signal
  socket-transport raises via SO_TIMEOUT, so tcp treats a silent tunnel and a
  silent socket identically: drop and reconnect."
  (:import (java.io InputStream)
           (java.net SocketTimeoutException URI)
           (java.net.http HttpClient WebSocket WebSocket$Listener)
           (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent BlockingQueue LinkedBlockingQueue TimeUnit)))

(def ^:private eof-sentinel
  "Queued by onClose/onError to mark the stream's end — distinct from a data
  chunk so the InputStream reports EOF rather than decoding it."
  (Object.))

(defn- buffer->bytes
  "Copy a websocket message's ByteBuffer into a byte array. The buffer is
  only valid until the receive callback returns, so we must copy before
  handing the bytes off to the queue."
  ^bytes [^ByteBuffer buffer]
  (let [bytes (byte-array (.remaining buffer))]
    (.get buffer bytes)
    bytes))

(defn- feed-listener
  "A WebSocket.Listener that copies each message onto `queue`. Demand is
  consumer-driven: onOpen requests the first message and the InputStream
  requests each subsequent one as it drains, so data frames are never
  auto-requested here. onPing answers with a Pong and onPong re-requests, so
  control frames replenish the single outstanding request rather than
  draining it; onClose / onError enqueue eof-sentinel to end the stream."
  ^WebSocket$Listener [^BlockingQueue queue]
  (reify WebSocket$Listener
    (onOpen [_ websocket]
      (.request websocket 1))
    (onText [_ _websocket data _last]
      (.put queue (.getBytes (str data) StandardCharsets/US_ASCII))
      nil)
    (onBinary [_ _websocket data _last]
      (.put queue (buffer->bytes data))
      nil)
    (onPing [_ websocket message]
      (.sendPong websocket message)
      (.request websocket 1)
      nil)
    (onPong [_ websocket _message]
      (.request websocket 1)
      nil)
    (onClose [_ _websocket _status _reason]
      (.put queue eof-sentinel)
      nil)
    (onError [_ _websocket _error]
      (.put queue eof-sentinel))))

(defn- take-chunk!
  "Block up to idle-timeout-ms for the next queued item: a byte[] chunk,
  ::eof at stream end, or a SocketTimeoutException on silence (the stall
  signal tcp turns into a reconnect)."
  [^BlockingQueue queue idle-timeout-ms]
  (let [item (.poll queue idle-timeout-ms TimeUnit/MILLISECONDS)]
    (cond
      (nil? item)                    (throw (SocketTimeoutException.
                                             "wss idle timeout"))
      (identical? item eof-sentinel) ::eof
      :else                          item)))

(defn- queue-input-stream
  "An InputStream draining `queue`'s byte[] chunks, one whole message at a
  time, blocking up to idle-timeout-ms for each and calling (request-next!)
  as each message is taken so the websocket streams the next. Read only from
  the single reader thread, so the current-chunk cursor needs no
  synchronization. close! runs the supplied connection-close step."
  ^InputStream [^BlockingQueue queue idle-timeout-ms request-next! close!]
  (let [cursor (atom nil)] ;; {:bytes ^bytes :pos int} of the chunk in hand
    (letfn [(fill! []
              (let [chunk (take-chunk! queue idle-timeout-ms)]
                (if (identical? chunk ::eof)
                  false
                  (do (reset! cursor {:bytes chunk :pos 0})
                      (request-next!)
                      true))))
            (remaining? []
              (let [{:keys [^bytes bytes pos]} @cursor]
                (and bytes (< pos (alength bytes)))))
            (read-into [^bytes dst off len]
              (if (zero? len)
                0
                (if (or (remaining?) (fill!))
                  (let [{:keys [^bytes bytes pos]} @cursor
                        n (min len (- (alength bytes) pos))]
                    (System/arraycopy bytes pos dst off n)
                    (swap! cursor update :pos + n)
                    n)
                  -1)))]
      (proxy [InputStream] []
        (read
          ([]
           (let [one (byte-array 1)]
             (if (neg? (read-into one 0 1)) -1 (bit-and (aget one 0) 0xff))))
          ([dst]
           (read-into dst 0 (alength ^bytes dst)))
          ([dst off len]
           (read-into dst off len)))
        (close []
          (close!))))))

(defn- close-websocket!
  "Abort a websocket, swallowing any failure — safe to call more than once
  (tcp closes on the reader's way out and again from close!)."
  [^WebSocket websocket]
  (try
    (some-> websocket .abort)
    (catch Throwable _ nil)))

(defn- build-websocket!
  "Open the websocket to `uri`, adding each header (the CF-Access service
  token — both or neither), and block up to connect-timeout-ms for the
  upgrade. Throws if the upgrade fails (a 403 from Access, an unreachable
  edge), which tcp/serve-connection! catches into ::unreachable."
  ^WebSocket [^URI uri headers connect-timeout-ms ^WebSocket$Listener listener]
  (let [builder (.newWebSocketBuilder (HttpClient/newHttpClient))]
    (.connectTimeout builder (Duration/ofMillis connect-timeout-ms))
    (doseq [[header value] headers]
      (.header builder header value))
    (-> (.buildAsync builder uri listener)
        (.get connect-timeout-ms TimeUnit/MILLISECONDS))))

(defn transport
  "A tcp/reader-state `:transport` that dials the wss:// `uri` instead of a
  plain socket. `headers` is the CF-Access service-token header map
  (adsb.ingest.config/feeder-auth-headers) — both-or-neither, nil to dial
  anonymously (a dev/LAN edge). Returns the (host port opts) connect step
  tcp calls; host/port are the URI's and used only for error context, since
  the dial target is the full URI."
  [^URI uri headers]
  (fn [_host _port {:keys [connect-timeout-ms idle-timeout-ms]}]
    (let [queue     (LinkedBlockingQueue.)
          websocket (build-websocket! uri headers connect-timeout-ms
                                      (feed-listener queue))
          close!    #(close-websocket! websocket)]
      {:in     (queue-input-stream queue idle-timeout-ms
                                   #(.request websocket 1) close!)
       :close! close!})))
