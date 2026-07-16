(ns adsb.ingest.wss
  (:import (java.io InputStream)
           (java.net SocketTimeoutException URI)
           (java.net.http HttpClient WebSocket WebSocket$Listener)
           (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent BlockingQueue LinkedBlockingQueue TimeUnit)
           (java.util.function BiConsumer)))

(def ^:private eof-sentinel (Object.))

(defn- buffer->bytes ^bytes [^ByteBuffer buffer]
  (let [bytes (byte-array (.remaining buffer))]
    (.get buffer bytes)
    bytes))

(defn- feed-listener ^WebSocket$Listener [^BlockingQueue queue]
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

(defn- take-chunk! [^BlockingQueue queue idle-timeout-ms]
  (let [item (.poll queue idle-timeout-ms TimeUnit/MILLISECONDS)]
    (cond
      (nil? item) (throw (SocketTimeoutException.
                           "wss idle timeout"))
      (identical? item eof-sentinel) ::eof
      :else item)))

(defn- queue-input-stream ^InputStream [^BlockingQueue queue idle-timeout-ms request-next! close!]
  (let [cursor (atom nil)]
    (letfn [(fill! []
              (loop []
                (let [chunk (take-chunk! queue idle-timeout-ms)]
                  (cond
                    (identical? chunk ::eof)
                    false

                    (zero? (alength ^bytes chunk))
                    (do (request-next!) (recur))

                    :else
                    (do (reset! cursor {:bytes chunk :pos 0})
                        (request-next!)
                        true)))))
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

(defn- close-websocket! [^WebSocket websocket]
  (try
    (some-> websocket .abort)
    (catch Throwable _)))

(defn- claim-dial! [state websocket]
  (compare-and-set! state nil websocket))

(defn- abandon-dial! [state]
  (let [landed (swap! state #(or % ::abandoned))]
    (when-not (identical? landed ::abandoned)
      landed)))

(defn- build-websocket!
  ^WebSocket [^HttpClient client ^URI uri headers connect-timeout-ms
              ^WebSocket$Listener listener]
  (let [builder (.newWebSocketBuilder client)
        state   (atom nil)]
    (.connectTimeout builder (Duration/ofMillis connect-timeout-ms))
    (doseq [[header value] headers]
      (.header builder header value))
    (let [pending (.buildAsync builder uri listener)]
      (.whenComplete pending
                     (reify BiConsumer
                       (accept [_ websocket _error]
                         (when (and websocket (not (claim-dial! state websocket)))
                           (close-websocket! websocket)))))
      (try
        (.get pending connect-timeout-ms TimeUnit/MILLISECONDS)
        (catch Throwable failure
          (some-> (abandon-dial! state) close-websocket!)
          (throw failure))))))

(defn transport [^URI uri headers]
  (let [client (HttpClient/newHttpClient)]
    (fn [_host _port {:keys [connect-timeout-ms idle-timeout-ms]}]
      (let [queue     (LinkedBlockingQueue.)
            websocket (build-websocket! client uri headers connect-timeout-ms
                                        (feed-listener queue))
            close!    #(close-websocket! websocket)]
        {:in     (queue-input-stream queue idle-timeout-ms
                                     #(.request websocket 1) close!)
         :close! close!}))))
