(ns adsb.ui.stats
  "The session's two scalars, read from the header beside the counts: max
  observed range and the feeder's message rate. Both are SCALARS the
  server computed (adsb.stats) and shipped on the wire envelope
  (adsb.wire) — never a position. This component could not reveal where
  the antenna is if it tried: it only ever holds two numbers.

  It used to sit in a bordered chip out in the margin column; it is a VITAL,
  and vitals live with the vitals (adsb-33i). Its labels are abbreviations in
  the chart's own hand — see `stat-row`.

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
  read it back and assert dash-vs-number without parsing the unit.

  The label is an ABBREVIATION, and an `abbr` is what an abbreviation is: the
  short form prints, the long form rides its `title` for anyone who wants it —
  a hover, a screen reader — instead of being deleted or duplicated in a hidden
  span. `RNG`, `MSG`: the terse aviation caps the Stack's own captions already
  speak (GND, NO ALT, EMG), which is the voice chart marginalia are written in.
  The header was the last thing in the app still writing prose."
  [{:keys [label expansion value unit testid]}]
  [:div.adsb-stats-row {:data-testid testid
                        :data-value  (if (some? value) (str value) "")}
   [:abbr.adsb-stats-label {:title expansion} label]
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
     [stat-row {:label     "RNG"
                :expansion "Max range, this session"
                :value     max-range-km
                :unit      " km"
                :testid    "stats-max-range"}]
     ;; MSG, not MSG RATE: the `/s` is the word `rate`, and the readout said it
     ;; twice. The unit was always doing that label's job.
     [stat-row {:label     "MSG"
                :expansion "Message rate"
                :value     message-rate
                :unit      "/s"
                :testid    "stats-message-rate"}]]))
