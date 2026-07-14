(ns adsb.stream
  "The browser side of the SSE stream: it opens the connection through the
  adsb.stream.source seam, decodes each frame with the shared adsb.wire
  codec, and lands two things in app-db —

    :aircraft/picture    icao -> domain aircraft, the current sky, fed by
                         two kinds of frame (adsb.wire). A `snapshot` or
                         `update` frame is the FULL picture and WHOLESALE
                         REPLACES this key — an aircraft absent from it is
                         gone. An `aircraft` frame (adsb-jpf) is ONE
                         aircraft's full merged state, pushed the instant
                         the server heard it, and MERGES into the picture
                         by icao — idempotent, so a missed one heals on
                         that aircraft's next message. On a streaming
                         deployment there are NO recurring full-picture
                         frames, so removals converge by CLIENT-SIDE
                         AGE-OUT: each `stats` frame prunes aircraft past
                         the shared adsb.aircraft threshold (time enters
                         as an event argument, stamped at the callback
                         edge). The imperative map layer (adsb-2yu.4)
                         reads this key OUTSIDE React, via the
                         :aircraft/picture subscription, and pushes it
                         straight into a MapLibre GeoJSON source; it never
                         mounts a component per aircraft — and it renders
                         each upsert as it comes, with NO smoothing or
                         prediction (owner decision, adsb-a4g).

    :stream/connection   :connecting | :live | :reconnecting | :down — the
                         honest health of the stream, surfaced by the
                         :stream/connection subscription for the header's
                         status indicator (adsb-dgb.3). :connecting is the
                         boot state: never connected, nothing failed. It is
                         NOT :reconnecting, which claims a failure that has
                         not happened.

    :stats/session       the session stats decoded from the dedicated
                         `stats` event (adsb.wire) — the scalar max range
                         and message rate the server computed. Stats and
                         aircraft data NEVER share a frame (owner decision,
                         adsb-jpf): the server sends stats on a low ~10 s
                         cadence, plus once right after the snapshot. {}
                         before the first stats frame and whenever a scalar
                         is unknown; the :stats/session subscription feeds
                         the numbers-only readout (adsb.ui.stats), which
                         dashes absent scalars. Never a position — see
                         adsb.wire.

    :feeder/status       the feeder's health as the server last reported it
                         (adsb.wire, riding the `stats` event): :ok | :down
                         | :starting, or nil before the first stats frame
                         or when the server named no status. This is the
                         RAW server claim; the header's feeder chip reads
                         the DERIVED :feeder/health sub (adsb.subs), which
                         additionally knows the claim is only trustworthy
                         while THIS stream is live.

  ## Connection state, honestly (the adsb-2yu.2 mandate)

  EventSource already reconnects itself after a transient drop: on such an
  error `readyState` is CONNECTING, and we simply reflect :reconnecting and
  let the browser retry. But once an EventSource reaches CLOSED it is dead
  and stays dead — the browser will not revive it. That is the case that
  needs us: we open a FRESH EventSource on an exponential backoff (1s, 2s,
  4s … capped at 30s), reset on the next successful open. Past a few failed
  attempts we surface :down while still retrying, so a server restart heals
  on its own with no page reload.

  The live connection is a stateful JS object, not data, so it lives in a
  private atom here rather than in app-db; app-db holds only the picture and
  the connection keyword. Time (the backoff timer) enters through the
  `schedule-reconnect!`/`clear-timer!` fns, which tests redef so the state
  machine can be driven synchronously."
  (:require [adsb.aircraft :as aircraft]
            [adsb.stream.source :as source]
            [adsb.wire :as wire]
            [clojure.math :as math]
            [re-frame.core :as rf]))

(def ^:const stream-url
  "The SSE endpoint (adsb-kbm.2): a snapshot on connect, then
  per-aircraft upserts (streaming) or ~1 Hz full-picture updates (poll),
  with stats on their own low-rate event (adsb.wire)."
  "/api/stream")

;; ---------------------------------------------------------------------
;; Backoff policy

(def ^:const base-backoff-ms 1000)
(def ^:const max-backoff-ms 30000)

;; Retries beyond this many consecutive failures surface :down (we keep
;; retrying regardless — :down is a status, not a stop).
(def ^:const down-after-attempts 3)

(defn backoff-ms
  "Exponential backoff for the nth consecutive reconnect attempt (1-based),
  doubling from `base-backoff-ms` and capped at `max-backoff-ms`."
  [attempts]
  (min max-backoff-ms
       (* base-backoff-ms (math/pow 2.0 (dec attempts)))))

(defn- status-for-attempts
  "Connection status while retrying: honest about a stream that has failed
  enough times to call it :down, still :reconnecting before that."
  [attempts]
  (if (> attempts down-after-attempts) :down :reconnecting))

;; ---------------------------------------------------------------------
;; Decode — one fn per SSE event kind, all through the shared adsb.wire
;; codec. Absent facts stay absent — the codec omits them, it does not
;; default them.

(defn- data->envelope [data]
  (-> (.parse js/JSON data)
      (js->clj :keywordize-keys true)))

(defn data->picture
  "Decode a full-picture frame's `data` string (`snapshot`/`update`)
  into the picture app-db holds: icao -> domain aircraft."
  [data]
  (wire/wire->picture (data->envelope data)))

(defn data->upsert
  "Decode an `aircraft` frame's `data` string into the one full merged
  domain aircraft it carries (adsb-jpf)."
  [data]
  (wire/wire->upsert (data->envelope data)))

(defn data->stats
  "Decode a `stats` frame's `data` string into {:stats session stats,
  :feeder feeder status}."
  [data]
  (let [envelope (data->envelope data)]
    {:stats  (wire/wire->stats envelope)
     :feeder (wire/wire->feeder envelope)}))

(defn now-ms
  "The frame's arrival instant, read where the stream touches the world
  (the source callbacks) and passed INTO events as an argument — the
  same discipline as adsb.map.aircraft-layer/now-ms. Redef-able so tests
  drive client-side age-out with a literal clock."
  []
  (.now js/Date))

;; ---------------------------------------------------------------------
;; The connection manager. A stateful JS resource, so it lives outside
;; app-db in this private atom: the current Connection and the pending
;; backoff timer id.

(defonce ^:private !conn (atom {:connection nil :timer nil}))

(defn clear-timer!
  "Cancel any pending reconnect timer. Redef-able so tests never arm a real
  js/setTimeout."
  []
  (when-let [t (:timer @!conn)]
    (js/clearTimeout t)
    (swap! !conn assoc :timer nil)))

(defn open!
  "Tear down any existing connection and open a fresh one through the seam,
  wiring its callbacks to re-frame. Redef-able only via the seam it calls;
  tests fake adsb.stream.source/connect!."
  []
  (clear-timer!)
  (some-> (:connection @!conn) source/close!)
  (let [c (source/connect!
            stream-url
            {:on-open     #(rf/dispatch [:stream/opened])
             :on-frame    #(rf/dispatch [:stream/received %])
             :on-aircraft #(rf/dispatch [:stream/upsert %])
             :on-stats    #(rf/dispatch [:stream/stats % (now-ms)])
             :on-error    #(rf/dispatch [:stream/error %])})]
    (swap! !conn assoc :connection c)))

(defn schedule-reconnect!
  "Arm a one-shot reconnect after `ms`. Redef-able so tests can assert the
  backoff schedule without waiting on real time."
  [ms]
  (clear-timer!)
  (swap! !conn assoc :timer
         (js/setTimeout #(rf/dispatch [:stream/reconnect]) ms)))

;; ---------------------------------------------------------------------
;; Effects — the thin edge where re-frame reaches the stateful world.

(rf/reg-fx :stream/connect! (fn [_] (open!)))
(rf/reg-fx :stream/clear-timer! (fn [_] (clear-timer!)))
;; Called through a wrapper, not passed by value: reg-fx captures whatever it is
;; handed, so naming the fn directly would freeze the ORIGINAL at registration
;; and a test's with-redefs would arm a real timer while its fake sat unused.
(rf/reg-fx :stream/schedule-reconnect! (fn [ms] (schedule-reconnect! ms)))

;; ---------------------------------------------------------------------
;; Events

;; Boot the stream: seed the picture and connection keys, then connect.
;;
;; :connecting, NOT :reconnecting. At boot nothing has connected, so there is
;; nothing to RE-connect to, and the app must not open by announcing a failure
;; it has not had. This state is the honest one for the window between `start`
;; and the first `opened`, and it is the reason the header is quiet across a
;; refresh instead of flashing "Feeder unknown" — which is what :reconnecting
;; made the derived :feeder/health say, faithfully, about a lie (adsb-33i).
(rf/reg-event-fx
  :stream/start
  (fn [{:keys [db]} _]
    {:db              (assoc db
                        :aircraft/picture {}
                        :stats/session {}
                        :feeder/status nil
                        :stream/attempts 0
                        :stream/connection :connecting)
     :stream/connect! nil}))

;; A (re)connect succeeded: we are live, the failure count resets, and any
;; pending backoff timer is stale and cancelled.
(rf/reg-event-fx
  :stream/opened
  (fn [{:keys [db]} _]
    {:db                  (assoc db :stream/connection :live :stream/attempts 0)
     :stream/clear-timer! nil}))

(def ^:const silent-after-frames
  "How many consecutive STATS frames of ZERO message rate before we are
  willing to call the feeder silent. Stats frames arrive every ~10 s
  (adsb.wire — the rate moved off the 1 Hz picture frames and onto the
  dedicated stats event), so three of them is ~30 seconds.

  There is a threshold at all because the light must not flicker. The server
  rounds the rate to a whole number, so a genuinely dribbling feeder samples as
  `0` now and then, and a dot that flipped amber on one such sample would blink
  — which §7 forbids the chrome outright, and which would teach the reader to
  ignore the one signal that must never be ignored.

  It is small because the fact is urgent: a dead SDR behind a healthy container
  is exactly the failure that LOOKS like a calm sky, and half a minute of
  provable silence is already a strange thing for a working antenna. (Each
  sample now spans ~10 s of the feeder's counter, too — stronger evidence per
  frame than the old per-second samples, which is why fewer are needed.)"
  3)

(defn silent-frames
  "How many frames in a row the feeder has reported no messages at all.

  Pure, and a counter rather than a clock: it needs no time, only the sequence
  of samples, which is what makes it testable without pretending to own a clock.

  A rate of ZERO is a fact and counts. A rate of NIL is not a fact — the feeder
  reports no counter, or this is the first sample and there is nothing to
  difference — and an unknown is never evidence of silence. Absent is not zero,
  the same rule the whole domain keeps."
  [previous message-rate]
  (cond
    (nil? message-rate)  0
    (pos? message-rate)  0
    :else                (inc (or previous 0))))

;; A full-picture frame arrived (`snapshot`, or a poll deployment's
;; `update`): wholesale-replace the picture. Aircraft data ONLY — stats
;; travel on their own event (ns docstring). Receiving a frame is itself
;; proof we are live.
(rf/reg-event-db
  :stream/received
  (fn [db [_ data]]
    (assoc db
      :aircraft/picture (data->picture data)
      :stream/connection :live)))

;; A single-aircraft upsert arrived (adsb-jpf): merge it into the picture
;; by icao — the aircraft is its full merged state, so assoc IS the merge
;; — and let the layer's subscription push it to the map as-is. No
;; smoothing, no prediction, no batching: rendering each event the moment
;; it arrives is the point (owner decision, adsb-a4g).
(rf/reg-event-db
  :stream/upsert
  (fn [db [_ data]]
    (let [aircraft (data->upsert data)]
      (-> db
          (assoc-in [:aircraft/picture (:aircraft/icao aircraft)] aircraft)
          (assoc :stream/connection :live)))))

;; A stats frame arrived: land the session stats and the feeder claim, and
;; PRUNE the picture of aircraft past the shared age-out threshold —
;; client-side age-out, the removal mechanism on a streaming deployment,
;; where no recurring full-picture frame exists to drop silent aircraft
;; (ns docstring). `at-ms` is stamped at the callback edge (now-ms); the
;; handler stays a pure fn of its arguments. On a poll deployment the
;; prune is a no-op in practice — every update frame already replaced the
;; picture wholesale.
;;
;; :feeder/silent-frames is the count of consecutive stats frames carrying
;; no messages. The FEEDER'S OWN STATUS CANNOT SEE THIS: the server sets
;; :ok on a successful poll of aircraft.json, which says the container is
;; up and serving — not that the radio behind it is hearing anything. An
;; SDR can die behind a perfectly healthy container, and the picture just
;; quietly ages out. This counter is what the derived :feeder/health
;; (adsb.subs) reads to catch it.
(rf/reg-event-db
  :stream/stats
  (fn [db [_ data at-ms]]
    (let [{:keys [stats feeder]} (data->stats data)]
      (-> db
          (update :aircraft/picture aircraft/age-out at-ms)
          (assoc
            :stats/session stats
            :feeder/status (:feeder/status feeder)
            :feeder/silent-frames (silent-frames (:feeder/silent-frames db)
                                                 (:stats/message-rate stats))
            :stream/connection :live)))))

;; An EventSource error. If the browser is still CONNECTING it owns the
;; retry — we just reflect :reconnecting. If it is CLOSED the source is dead:
;; we count the failure, surface :reconnecting/:down, and schedule our own
;; fresh connect on the backoff.
(rf/reg-event-fx
  :stream/error
  (fn [{:keys [db]} [_ ready-state]]
    (if (= ready-state :closed)
      (let [attempts (inc (:stream/attempts db 0))]
        {:db                         (assoc db
                                       :stream/attempts attempts
                                       :stream/connection (status-for-attempts attempts))
         :stream/schedule-reconnect! (backoff-ms attempts)})
      {:db (assoc db :stream/connection :reconnecting)})))

;; The backoff timer fired: open a fresh EventSource.
(rf/reg-event-fx
  :stream/reconnect
  (fn [_ _]
    {:stream/connect! nil}))

;; ---------------------------------------------------------------------
;; Subscriptions

;; The current sky, icao -> domain aircraft. {} before the first frame.
(rf/reg-sub
  :aircraft/picture
  (fn [db _]
    (get db :aircraft/picture {})))

;; Stream health: :connecting | :live | :reconnecting | :down. The default is
;; :connecting — the state of an app that has not connected AND has not failed.
(rf/reg-sub
  :stream/connection
  (fn [db _]
    (get db :stream/connection :connecting)))

;; The feeder's health as the server last reported it: :ok | :down |
;; :starting, or nil before the first frame / when the server named no
;; status. RAW — the header reads the derived :feeder/health (adsb.subs),
;; which knows this claim is stale the moment our own stream stops being live.
(rf/reg-sub
  :feeder/status
  (fn [db _]
    (get db :feeder/status)))

;; The session stats scalars from the latest frame (adsb.wire):
;; :stats/max-range-km and :stats/message-rate, each absent until known.
;; {} before the first frame. The numbers-only readout (adsb.ui.stats)
;; dashes whatever is absent — never a position, only scalars.
(rf/reg-sub
  :stats/session
  (fn [db _]
    (get db :stats/session {})))

;; ---------------------------------------------------------------------
;; Boot

(defn start!
  "Open the stream. Called once from adsb.core/init! after the shell mounts."
  []
  (rf/dispatch [:stream/start]))
