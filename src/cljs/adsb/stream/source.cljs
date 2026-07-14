(ns adsb.stream.source
  "The SSE seam — a thin, fakeable wrapper over the browser's EventSource,
  and the ONLY place the app touches that raw API.

  Why a seam at all? The same reason as the map seam (adsb.map.maplibre):
  a real EventSource opens a live network connection and owns its own
  auto-reconnect timer, neither of which a unit test wants. So tests never
  construct one — they redef `connect!` to record the callbacks and hand
  back a fake `Connection` they can drive by hand. See docs/testing-setup.md
  and the sibling seams adsb.ingest.source / adsb.map.maplibre.

  The seam is deliberately dumb: it translates EventSource's three signals
  into plain callbacks and reports its ready state as a keyword. All the
  policy — decode, wholesale replacement, reconnect-with-backoff, connection
  state — lives in adsb.stream, where it can be tested without the network.")

(defprotocol Connection
  "A live SSE connection, behind a narrow seam. The only thing the client
  needs from it is the ability to tear it down (and, with it, EventSource's
  built-in auto-reconnect) when we take the reconnect into our own hands."
  (close! [this]
    "Close the connection and cancel EventSource's auto-reconnect."))

(defn- ready-state-kw
  "EventSource.readyState as a keyword the client can reason about without
  knowing the numeric constants: 0 CONNECTING, 1 OPEN, 2 CLOSED."
  [^js es]
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
  (let [es (js/EventSource. url)
        data-> (fn [callback] (fn [^js e] (callback (.-data e))))
        frame (data-> on-frame)]
    (set! (.-onopen es) (fn [_] (on-open)))
    (.addEventListener es "snapshot" frame)
    (.addEventListener es "update" frame)
    (.addEventListener es "aircraft" (data-> on-aircraft))
    (.addEventListener es "stats" (data-> on-stats))
    (.addEventListener es "config" (data-> on-config))
    (set! (.-onerror es) (fn [_] (on-error (ready-state-kw es))))
    (reify Connection
      (close! [_] (.close es)))))
