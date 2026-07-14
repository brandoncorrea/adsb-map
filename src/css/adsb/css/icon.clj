(ns adsb.css.icon
  "The icons (adsb.ui.icon) — one rule, and it is mostly a refusal to have a
  second one.

  An icon here is a REPLACEMENT FOR A CHARACTER, not a new kind of object, and
  it is styled to behave like one. `1em` square means it takes its size from
  the font-size of the box it lands in; `currentColor` means it takes its ink
  from that box's colour. So the panel's close button keeps sizing its mark
  with `font-size: var(--t1)` and fading it with `color: var(--faded-ink)`,
  and the `:hover` rule that swaps that colour keeps working — none of which
  had to be touched when the × stopped being a × (adsb.css.card/close).

  That is the whole reason there is no `.adsb-icon-xmark` block below, and no
  per-icon sizing anywhere: an icon that needs its own size or its own colour
  is an icon whose CONTAINER is under-specified, and the fix belongs there.

  `display: block` is not cosmetic. An inline svg sits on the text baseline
  and reserves the font's descender space beneath itself, which shows up as a
  couple of stray pixels under the glyph and knocks the close button off the
  optical centre of the header row. Block takes it out of the baseline
  entirely, which is what a picture wants."
  (:require [adsb.css.decl :refer [decl]]))

(def styles
  [[:.adsb-icon
    (decl :display   "block"
          :width     "1em"
          :height    "1em"
          :flex      "none"
          :fill      "currentColor")]])
