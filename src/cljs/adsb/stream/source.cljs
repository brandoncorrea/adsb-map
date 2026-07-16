(ns adsb.stream.source)

(defprotocol Connection
  "A live SSE connection, behind a narrow seam. The only thing the client
  needs from it is the ability to tear it down (and, with it, EventSource's
  built-in auto-reconnect) when we take the reconnect into our own hands."
  (close! [this] "Close the connection and cancel EventSource's auto-reconnect."))

(defn- ready-state-kw [es]
  (case (.-readyState es)
    0 :connecting
    1 :open
    2 :closed
    :closed))

(defn connect!
  "The seam. Open an EventSource to `url` and wire its signals to the
  supplied callbacks, returning a `Connection`.

    :on-open     (fn [])            the stream (re)connected
    :on-frame    (fn [data-string]) a `snapshot` or `update` event
                                    arrived; both are full pictures,
                                    handled alike
    :on-aircraft (fn [data-string]) an `aircraft` event arrived — one
                                    aircraft's full merged state, merged
                                    into the picture by icao (adsb-jpf)
    :on-stats    (fn [data-string]) a `stats` event arrived — session
                                    stats and feeder health, never
                                    aircraft data
    :on-config   (fn [data-string]) the one `config` event arrived, ahead
                                    of the snapshot — the static boot
                                    config, today the privacy crop's
                                    declared boundary. Once per connection
                                    and never resent (adsb.stream.broadcast)
    :on-error    (fn [ready-state]) an error fired; ready-state is
                                    :connecting (the browser is
                                    auto-retrying) or :closed (the source
                                    is dead — ours to reconnect)

  Tests redef this to capture the callbacks and return a fake `Connection`;
  it therefore holds no logic worth testing itself."
  [url {:keys [on-open on-frame on-aircraft on-stats on-config on-error]}]
  (let [es     (js/EventSource. url)
        data-> (fn [callback] (fn [e] (-> e .-data callback)))
        frame  (data-> on-frame)]
    (set! (.-onopen es) (fn [_] (on-open)))                 ;; TODO: (.addEventListener es "open" ...)
    (.addEventListener es "snapshot" frame)
    (.addEventListener es "update" frame)
    (.addEventListener es "aircraft" (data-> on-aircraft))
    (.addEventListener es "stats" (data-> on-stats))
    (.addEventListener es "config" (data-> on-config))
    (set! (.-onerror es) (fn [_] (on-error (ready-state-kw es)))) ;; TODO: (.addEventListener es "error" ...)
    (reify Connection
      (close! [_] (.close es)))))
