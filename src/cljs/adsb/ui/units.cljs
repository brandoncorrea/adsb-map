(ns adsb.ui.units
  "How the instrument PRINTS a number. One place, because the roster and the
  panel show the same quantities and must not disagree about them — a track
  of 97.14° in one surface and 097° in the other is two different aircraft as
  far as the reader is concerned.

  THE FEEDER'S PRECISION IS NOT THE SKY'S. ADS-B carries ground speed and
  track as fixed-point fields that decode to fractions — 450.5 kt, 97.14° —
  and printing them raw makes the chart lie twice over. It claims a precision
  no one has: track is derived from a velocity vector and swings a degree or
  two while an aircraft holds a perfectly straight line, so the hundredths are
  noise, not information. And it prints in a dialect no aviator reads: speeds
  and bearings are whole numbers everywhere in aviation — on a strip, in a
  clearance, over the radio. Nobody has ever been told to turn to 097.14.

  So: whole knots, and whole degrees in the three-digit form the whole
  profession writes bearings in (007°, not 7°). ROUNDED, NOT TRUNCATED —
  450.9 kt is 451, not 450.

  ABSENT IS NOT ZERO (the panel's rule, and it holds here). A quantity the sky
  never reported returns nil, and each surface renders its own dash. These
  never invent a 0.")

(defn knots
  "Ground speed as whole knots — \"451\". nil when unreported."
  [kt]
  (when (some? kt)
    (str (js/Math.round kt))))

(defn track
  "Track as a three-digit bearing with the degree sign — \"097°\", \"007°\".

  Normalised onto the compass after rounding, which is the order that
  matters: 359.7° rounds to 360, and 360 is not a bearing this reads out —
  it is 000, due north, the same direction by another name."
  [deg]
  (when (some? deg)
    (let [d (mod (js/Math.round deg) 360)]
      (str (.padStart (str d) 3 "0") "°"))))
