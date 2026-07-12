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

    * CONNECTION — the SSE stream's health, straight from :stream/connection
      (:live / :reconnecting / :down). The chip carries a DISTINCT visual per
      state AND a text label: colour alone is not accessible, and when the
      feeder is down the user must be able to tell the map is stale at a
      glance rather than trusting a frozen picture.

  Styling is a NEUTRAL PLACEHOLDER (class-name hooks only); the visual pass is
  bead adsb-dgb.5."
  (:require
    [adsb.aircraft :as aircraft]
    [re-frame.core :as rf]))

;; The stream's three honest states -> the human label the chip shows. The
;; :down label is deliberately blunt: a frozen map must not read as a quiet
;; one.
(def ^:const connection-labels
  {:live         "Live"
   :reconnecting "Reconnecting"
   :down         "Feeder down"})

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
  "The SSE health chip. Distinct class per state for the visual channel, a
  text label for the accessible one, `role=status` so assistive tech
  announces a change. `data-state` is the class-free hook the tests pin."
  [status]
  (let [status (or status :reconnecting)]
    [:span.adsb-conn
     {:class       (str "adsb-conn-" (name status))
      :role        "status"
      :data-testid "connection-indicator"
      :data-state  (name status)}
     [:span.adsb-conn-dot {:aria-hidden true}]
     [:span.adsb-conn-label (get connection-labels status (name status))]]))

;; ---------------------------------------------------------------------

(defn header
  "The app bar: title, live counts, a UTC clock, and the connection chip.
  A form-2 component — subscribe once, deref per render — so it re-renders
  when the picture turns over or the stream's health flips, and never more
  often than that."
  []
  (let [picture    (rf/subscribe [:aircraft/picture])
        connection (rf/subscribe [:stream/connection])
        now-ms     (rf/subscribe [:ui/now-ms])]
    (fn []
      [:header.adsb-header
       [:span.adsb-title "adsb"]
       [counts @picture]
       [utc-clock @now-ms]
       [connection-indicator @connection]])))
