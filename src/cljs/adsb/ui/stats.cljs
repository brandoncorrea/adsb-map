(ns adsb.ui.stats
  "A numbers-only readout tucked in the legend corner: the session's max
  observed range and the feeder's message rate. Both are SCALARS the
  server computed (adsb.stats) and shipped on the wire envelope
  (adsb.wire) — never a position. This component could not reveal where
  the antenna is if it tried: it only ever holds two numbers.

  Each scalar is absent until known — a fresh session has heard nothing,
  and a rate needs two samples to exist. Absent renders as a DASH, never
  a zero: an unknown range is not a range of zero km, and an unknown rate
  is not silence (docs/validation-boundaries.md, the same absent-is-not-
  zero rule the wire keeps).

  Styling is a NEUTRAL PLACEHOLDER (class-name hooks only); the visual
  pass is bead adsb-dgb.5."
  (:require
    [re-frame.core :as rf]))

(def ^:const em-dash
  "The absent-value glyph: an em dash, so an unknown scalar reads as
  'no value', never as zero."
  "—")

(defn- stat-row
  "One readout row: a label and its value with a unit, or a dash when the
  value is absent. The raw value is carried on `data-value` so a test can
  read it back and assert dash-vs-number without parsing the unit."
  [{:keys [label value unit testid]}]
  [:div.adsb-stats-row {:data-testid testid
                        :data-value  (if (some? value) (str value) "")}
   [:span.adsb-stats-label label]
   [:span.adsb-stats-value
    (if (some? value)
      (str value unit)
      em-dash)]])

(defn stats-readout
  "The corner stats readout: max range and message rate, each dashed when
  the server has not (yet) reported it. Reads one subscription — the
  decoded session stats — and nothing positional."
  []
  (let [{:stats/keys [max-range-km message-rate]}
        @(rf/subscribe [:stats/session])]
    [:section.adsb-stats {:aria-label "Feed statistics"}
     [stat-row {:label  "Max range"
                :value  max-range-km
                :unit   " km"
                :testid "stats-max-range"}]
     [stat-row {:label  "Msg rate"
                :value  message-rate
                :unit   "/s"
                :testid "stats-message-rate"}]]))
