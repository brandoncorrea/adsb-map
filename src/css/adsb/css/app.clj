(ns adsb.css.app
  "The stylesheet — THE SECTIONAL, assembled (docs/design-direction.md).

  This vector IS the cascade. CSS resolves ties by document order, and two
  places in this stylesheet lean on that:

    captions  names selectors that the chrome namespaces above it also
              style, at EQUAL specificity. It wins because it comes later.
              Move it up and the caption voice silently stops applying.
    phone     is nothing but overrides. It must come last.

  Everything before those two is grouped for reading, not for the cascade —
  the selectors are disjoint, so their relative order is free.

  Nothing here reaches for the theme: the day/night split is entirely the
  custom-properties layer in adsb.css.tokens, resolved by the browser at
  runtime."
  (:require [adsb.css.alerts :as alerts]
            [adsb.css.captions :as captions]
            [adsb.css.emergency :as emergency]
            [adsb.css.motion :as motion]
            [adsb.css.panel :as panel]
            [adsb.css.phone :as phone]
            [adsb.css.shell :as shell]
            [adsb.css.stack :as stack]
            [adsb.css.tokens :as tokens]))

(def stylesheet
  [tokens/styles                                            ; the faces and the two editions
   motion/styles                                            ; §6 — and §7's zero-motion rule
   shell/styles                                             ; the shell, the map, the header
   alerts/styles                                            ; §7 — the NOTAM strip
   panel/styles                                             ; §4 — the index card and its ring
   emergency/styles                                         ; §7 — the map's own annotations
   stack/styles                                             ; §9 — the flight-level ruler

   ;; ORDER-CRITICAL from here down. See the namespace docstring.
   captions/styles                                          ; §5 — the printed caption voice
   phone/styles])                                           ; Q9c — the phone stance, last
