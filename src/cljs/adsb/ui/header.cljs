(ns adsb.ui.header
  "The top bar over the map — the app's identity and its live vital signs.
  Reagent chrome, and genuinely React territory: it changes when the picture
  turns over (~1 Hz) and when the stream's health flips, not per aircraft, so
  a small component tree is exactly right.

  Two vital signs live here:

    * COUNTS — how many aircraft are in the current sky, and how many of
      those the feeder has actually positioned. Position-less targets are a
      lawful state (heard on the radio, never located); they count toward the
      total but not the positioned tally, so the gap between the two numbers
      is the honest \"heard but not on the map\" figure.

    * CONNECTION — TWO semantically distinct chips, because two different
      things can go wrong and the user must be able to tell them apart:

        - STREAM health (:stream/connection — :live / :reconnecting / :down)
          is the browser-to-server SSE link. Its :down means DISCONNECTED:
          we stopped receiving frames.

        - FEEDER health (the derived :feeder/health — :ok / :starting /
          :down / :unknown) is the antenna behind the server. A live stream
          over a DEAD feeder looks perfectly healthy — the map just quietly
          ages out — so this chip exists to expose exactly that. When the
          stream is not live the feeder is UNKNOWABLE, and this chip shows a
          neutral :unknown rather than a stale claim (the derivation lives in
          adsb.subs).

      Each chip carries a DISTINCT visual per state AND a text label: colour
      alone is not accessible, and a frozen map must not read as a quiet one.

  Styling is a NEUTRAL PLACEHOLDER (class-name hooks only); the visual pass is
  bead adsb-dgb.5."
  (:require
    [adsb.aircraft :as aircraft]
    [re-frame.core :as rf]))

;; The stream's three honest states -> the human label the STREAM chip shows.
;; :down reads \"Disconnected\": this chip measures the browser-to-server
;; stream, not the feeder — the feeder chip below owns \"Feeder down\".
(def ^:const connection-labels
  {:live         "Live"
   :reconnecting "Reconnecting"
   :down         "Disconnected"})

;; The feeder's states -> the human label the FEEDER chip shows. :unknown is
;; the neutral state shown whenever the stream is not live and the feeder's
;; health cannot be known (adsb.subs).
(def ^:const feeder-labels
  {:ok       "Feeder OK"
   :starting "Feeder starting"
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

(defn- counts
  "Live aircraft tally: total in the sky, and how many are positioned
  (adsb.aircraft/positioned?). The two numbers side by side make the
  heard-but-unplaced gap visible instead of hiding it."
  [picture]
  (let [total      (count picture)
        positioned (count (filter aircraft/positioned? (vals picture)))]
    [:span.adsb-counts {:data-testid "aircraft-counts"}
     [:span.adsb-count-total {:data-testid "count-total"} total]
     [:span.adsb-count-unit " aircraft"]
     [:span.adsb-count-sep " · "]
     [:span.adsb-count-positioned {:data-testid "count-positioned"} positioned]
     [:span.adsb-count-unit " positioned"]]))

(defn- connection-indicator
  "The SSE STREAM health chip: the browser-to-server link, driven by
  :stream/connection. Distinct class per state for the visual channel, a text
  label for the accessible one, `role=status` so assistive tech announces a
  change. `data-state` is the class-free hook the tests pin. Semantically
  distinct from the feeder chip beside it: this measures whether the browser
  is receiving frames at all, not whether the antenna is hearing aircraft."
  [status]
  (let [status (or status :reconnecting)]
    [:span.adsb-conn
     {:class       (str "adsb-conn-" (name status))
      :role        "status"
      :data-testid "connection-indicator"
      :data-state  (name status)}
     [:span.adsb-conn-dot {:aria-hidden true}]
     [:span.adsb-conn-label (get connection-labels status (name status))]]))

(defn- feeder-indicator
  "The FEEDER health chip: the antenna behind the server, driven by the
  derived :feeder/health sub (adsb.subs). Same accessible construction as the
  stream chip — distinct class per state, a text label, `role=status`, a
  `data-state` hook. Kept visually and semantically separate: a live stream
  over a dead feeder is the exact situation this chip exposes. When the stream
  is not live the feeder is unknowable and this shows a neutral :unknown,
  never a stale claim (the derivation is in adsb.subs)."
  [status]
  (let [status (or status :unknown)]
    [:span.adsb-feeder
     {:class       (str "adsb-feeder-" (name status))
      :role        "status"
      :data-testid "feeder-indicator"
      :data-state  (name status)}
     [:span.adsb-feeder-dot {:aria-hidden true}]
     [:span.adsb-feeder-label (get feeder-labels status (name status))]]))

;; ---------------------------------------------------------------------

(defn header
  "The app bar: title, live counts, a UTC clock, and the two health chips —
  stream and feeder. A form-2 component — subscribe once, deref per render —
  so it re-renders when the picture turns over or either health signal flips,
  and never more often than that."
  []
  (let [picture    (rf/subscribe [:aircraft/picture])
        connection (rf/subscribe [:stream/connection])
        feeder     (rf/subscribe [:feeder/health])
        now-ms     (rf/subscribe [:ui/now-ms])]
    (fn []
      [:header.adsb-header
       [:span.adsb-title "adsb"]
       [counts @picture]
       [utc-clock @now-ms]
       [connection-indicator @connection]
       [feeder-indicator @feeder]])))
