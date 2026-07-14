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
