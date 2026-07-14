(ns adsb.css.card
  "The index card's face (§4) — ONE surface, worn by every card that floats
  over the chart.

  The detail panel wore it alone until the drawer grew the same features one
  by one and got half of them wrong (adsb-l4m): a veil here, an opaque leaf
  there; a settle animation on one and none on the other. Two floating
  surfaces on one chart must read as two of the SAME thing, and the only way
  that survives the next redesign is for the face to be written once.

  These are DECLARATIONS, not rules: the panel and the drawer each compose
  `face` into their own rule and add their own geometry after it. Garden
  emits the maps in the order given, so a surface may override a face
  property simply by writing its own after (the phone stance does — the
  drawer butts flush into the corner there, and sheds the corners and the
  inset with a later block).

  The `close` decl is the same bargain for the way out: every card's × is
  the same quiet mark, whatever box it sits in.

  ORDER inside `close`: `font: inherit` resets font-size; the font-size
  after it is the one that must survive. See adsb.css.decl."
  (:require [adsb.css.decl :refer [decl]]))

(def face
  "The card itself: veiled paper over the chart, a strong hairline rule,
  squared 2px corners, the pressed offset shadow, and the settle-in (§6)."
  (decl :background    "var(--paper-veil)"
        :border        "1px solid var(--rule-strong)"
        :border-radius "2px"
        :box-shadow    "2px 2px 0 var(--rule-faint)"
        :color         "var(--ink)"
        :box-sizing    "border-box"
        :animation     "adsb-settle 180ms ease-out"))

(def title
  "A card's name, in the plotter's stamp: bold, at --t1, tracked. The panel
  prints a callsign here and the drawer prints its band's label, and the two
  must be the SAME stamp — the drawer wore a faded caption for a title once,
  and the two cards read as two different apps (adsb-l4m). Guarded against a
  hostile callsign shearing the card."
  (decl :font-size      "var(--t1)"
        :font-weight    700
        :letter-spacing "0.04em"
        :overflow-wrap  "anywhere"))

(def head
  "A card's header row: title left, the way out at the end, and §4's ink
  rule under the whole line — the strong rule is half of what makes the
  index card read as one."
  (decl :display         "flex"
        :align-items     "baseline"
        :justify-content "space-between"
        :gap             "var(--s2)"
        :padding         "var(--s3) var(--s3) var(--s2)"
        :border-bottom   "1px solid var(--ink)"))

(def close
  "A card's × — faded until wanted, ink on hover (each surface writes its
  own :hover/:focus-visible rules; this is the shared voice)."
  (decl :background  "none"
        :border      "none"
        :padding     0
        :font        "inherit"
        :font-size   "var(--t1)"
        :line-height 1
        :color       "var(--faded-ink)"
        :cursor      "pointer"))
