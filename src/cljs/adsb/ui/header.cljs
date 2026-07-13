(ns adsb.ui.header
  "The top bar over the map — the app's identity and its live vital signs.
  Reagent chrome, and genuinely React territory: it changes when the picture
  turns over (~1 Hz) and when the stream's health flips, not per aircraft, so
  a small component tree is exactly right.

  THE HEADER REPORTS EXCEPTIONS, NOT CONFIRMATIONS (adsb-33i). It used to
  spend its width telling you that nothing was wrong — a chip reading \"Live\"
  beside a chip reading \"Feeder OK\", every second of every healthy session.
  Silence means healthy now, and the width that bought goes to the vitals that
  actually carry information.

  THE HEADER IS THE APPARATUS; THE STACK IS THE SKY. That is the line, and it
  is worth stating because everything here obeys it. This bar reports on the
  MACHINE — whether the stream is up, and whether the antenna behind it is
  hearing. It does NOT report on the aircraft: the counts of them
  (`PLOTTED 72/78`, `GND`, `NO ALT`, `EMG`) are a census of the sky, and they
  live on the Stack with the rest of the sky.

  THE READOUTS ARE GONE, AND THE DOT ABSORBED THE ONE THAT MATTERED. `RNG` was
  the session's max range: a brag stat, and one that stops meaning anything the
  moment a second source is added, since it is measured from THE receiver and
  there would no longer be one. `MSG` was the feeder's message rate — which did
  matter, but not as a NUMBER: it was the only thing on screen that could tell
  you the radio had gone deaf behind a healthy container, and it asked the reader
  to notice a zero and diagnose it themselves. A rate is not a readout; it is a
  STATE, and the state now lives in the dot (:silent — see adsb.subs).

  The vital signs:

    * CONNECTION — TWO semantically distinct signals, because two different
      things can go wrong and the user must be able to tell them apart:

        - STREAM health (:stream/connection — :connecting / :live /
          :reconnecting / :down) is the browser-to-server SSE link. Its :down
          means DISCONNECTED: we stopped receiving frames. It renders NOTHING
          while :live, and nothing while :connecting either — a boot is not a
          failure, and an app must not open by announcing one. A frozen map
          still cannot read as a quiet one: a frozen map is not a silent
          header, it is a Disconnected chip.

        - FEEDER health (the derived :feeder/health — :ok / :starting /
          :silent / :down / :unknown, or NIL) is the antenna behind the server.
          A live stream over a DEAD feeder looks perfectly healthy — the map
          just quietly ages out — so this exists to expose exactly that.

          :silent is that failure's subtlest form and the reason this signal
          earns its place: the container is REACHABLE (the server's poll keeps
          succeeding, so the feeder itself keeps claiming :ok) while the radio
          behind it has gone deaf. Reachable is not hearing. The derivation is
          in adsb.subs.

          When the stream has dropped, the feeder is UNKNOWABLE and it shows a
          neutral :unknown rather than a stale claim. While the stream is still
          :connecting it is nil — unasked, not unknowable — and renders nothing.

  COLOUR ALONE MAY SAY \"FINE\". IT MAY NEVER SAY \"BROKEN\". The feeder at
  :ok is a bare coloured dot, its label kept in the accessibility tree and
  taken out of the visual one. Every other feeder state — starting, down,
  silent, unknown — keeps its words, because a colour-blind reader must never be
  the only one who cannot see that the antenna is dead. That is the whole of the
  old 'colour alone is not accessible' rule that was load-bearing: it bites on
  problems, not on the benign state."
  (:require
    [re-frame.core :as rf]))

;; The stream's honest states -> the human label the STREAM chip shows.
;; :down reads \"Disconnected\": this chip measures the browser-to-server
;; stream, not the feeder — the feeder chip below owns \"Feeder down\".
(def ^:const connection-labels
  {:live         "Live"
   :reconnecting "Reconnecting"
   :down         "Disconnected"})

;; The stream states the header says NOTHING about, because neither is news:
;; :live (all is well) and :connecting (we have just started, and nothing has
;; gone wrong). Every state outside this set is an exception and speaks.
(def ^:const quiet-states
  #{:live :connecting})

;; The feeder's states -> the human label the FEEDER chip shows. :unknown is
;; the neutral state shown whenever the stream is not live and the feeder's
;; health cannot be known (adsb.subs).
(def ^:const feeder-labels
  {:ok       "Feeder OK"
   :starting "Feeder starting"
   :silent   "No messages"
   :down     "Feeder down"
   :unknown  "Feeder unknown"})

(defn- pad2
  "Two-digit, zero-padded — for a stable, non-jumping wall clock."
  [n]
  (.padStart (str n) 2 "0"))

;; ---------------------------------------------------------------------
;; Presentation — pure functions of plain data. The parent derefs the
;; subscriptions and hands these the derefed values, so they carry no
;; re-frame knowledge and a test can render them against a literal.

(defn- utc-clock
  "The wall clock in UTC — the one honest timezone for a feed of aircraft
  crossing many. Reads the coarse UI clock (:ui/now-ms); renders nothing
  until the first tick, so it never invents a time it has not been given."
  [now-ms]
  (when now-ms
    (let [d (js/Date. now-ms)]
      [:time.adsb-clock {:data-testid "utc-clock"}
       (str (pad2 (.getUTCHours d)) ":"
            (pad2 (.getUTCMinutes d)) ":"
            (pad2 (.getUTCSeconds d)) " UTC")])))

(defn- connection-indicator
  "The SSE STREAM health chip: the browser-to-server link, driven by
  :stream/connection. NOTHING while :live — a healthy link is not news, and
  the chip that used to say so was the header's largest tenant. The two states
  that ARE news keep everything they had: a distinct class for the visual
  channel, a text label for the accessible one, `role=status` so assistive
  tech announces the change, and the `data-state` hook the tests pin.

  Semantically distinct from the feeder dot beside it: this measures whether
  the browser is receiving frames at all, not whether the antenna is hearing
  aircraft."
  [status]
  (let [status (or status :connecting)]
    (when-not (contains? quiet-states status)
      [:span.adsb-conn
       {:class       (str "adsb-conn-" (name status))
        :role        "status"
        :data-testid "connection-indicator"
        :data-state  (name status)}
       [:span.adsb-conn-dot {:aria-hidden true}]
       [:span.adsb-conn-label (get connection-labels status (name status))]])))

(defn- feeder-indicator
  "The FEEDER health signal: the antenna behind the server, driven by the
  derived :feeder/health sub (adsb.subs). Kept visually and semantically
  separate from the stream chip — a live stream over a dead feeder is the
  exact situation this exposes.

  At :ok it is a bare coloured dot. The label is not deleted, it is moved out
  of the visual channel and left in the accessible one (`adsb-vh`), so a
  screen reader still hears 'Feeder OK' and the eye gets a green dot. Every
  other state — starting, silent, down, unknown — prints its words: colour alone
  may say fine, and may never say broken. `No messages` is the plainest of them,
  and deliberately: it states the fact and does not guess the cause, because a
  dead SDR and an empty 3am sky look identical from here.

  When the stream is not live the feeder is unknowable and this shows a
  neutral :unknown, never a stale claim (the derivation is in adsb.subs)."
  [status]
  (when status                     ; nil while the stream is still :connecting
    (let [ok? (= :ok status)]
      [:span.adsb-feeder
       {:class       (str "adsb-feeder-" (name status))
        :role        "status"
        :data-testid "feeder-indicator"
        :data-state  (name status)}
       [:span.adsb-feeder-dot {:aria-hidden true}]
       [:span.adsb-feeder-label
        {:class (when ok? "adsb-vh")}
        (get feeder-labels status (name status))]])))

;; ---------------------------------------------------------------------

(defn header
  "The app bar: a UTC clock and the health signals — stream and feeder. A form-2
  component — subscribe once, deref per render — so it re-renders when a health
  signal flips, and never more often than that."
  []
  (let [connection (rf/subscribe [:stream/connection])
        feeder     (rf/subscribe [:feeder/health])
        now-ms     (rf/subscribe [:ui/now-ms])]
    (fn []
      [:header.adsb-header
       [utc-clock @now-ms]
       [connection-indicator @connection]
       [feeder-indicator @feeder]])))
