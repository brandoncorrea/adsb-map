# Icon licenses

The app's icons are **Font Awesome Free 7.3.0**, Solid style, used unmodified.

Unlike the typefaces — which are redistributed as files in
`resources/public/fonts/` and carry their license next to them — an icon here
is not a file. It is a `viewBox` and a path's `d` attribute, vendored as
literal data into `adsb.ui.icon` (`src/cljs/adsb/ui/icon.cljs`). There is no
npm package, no webfont, no kit script, and no CDN origin: the CSP
(`adsb.http.security`) is `default-src 'none'`, and an icon is not worth an
origin. So the attribution lives here rather than beside an asset directory
that does not exist.

## License

**Icons: CC BY 4.0** — https://creativecommons.org/licenses/by/4.0/
Font Awesome Free 7.3.0 by @fontawesome — https://fontawesome.com
License — https://fontawesome.com/license/free
Copyright 2026 Fonticons, Inc.

CC BY 4.0 permits redistribution — including in this public repository — and
permits commercial use, with one condition: **attribution**. This file is that
attribution, and it is the reason this file is not optional.

Only the ICONS are used. Font Awesome's fonts (SIL OFL 1.1) and code (MIT) are
not vendored here, and neither ships in the bundle.

## The icons

Every one is taken from the **Free** set's Solid style, verbatim — the `d` and
the `viewBox` are byte-identical to the upstream SVGs at
`FortAwesome/Font-Awesome@7.x/svgs/solid/`.

| key                | surface                                        |
| ------------------ | ---------------------------------------------- |
| `xmark`            | the index card's close mark (`adsb.ui.aircraft-panel`) |
| `chevron-down`     | that card, expanded (`adsb.ui.aircraft-panel`)  |
| `chevron-right`    | that card, collapsed (`adsb.ui.aircraft-panel`) |
| `crosshairs`       | the chart's Free/Follow reticle (`adsb.ui.follow`) |
| `magnifying-glass` | the roster's find field (`adsb.ui.roster`)      |

## FREE ONLY — and the reason is this repo, not the subscription

**Do not add a Font Awesome Pro icon to `adsb.ui.icon`.**

A Pro subscription grants the right to USE the whole suite, perpetually. It
does not grant the right to REDISTRIBUTE the icons as icons, and Font Awesome's
license calls out publishing Pro assets where non-licensees can take them.
**This repository is public.** Anyone can clone it and lift a path out of
`icon.cljs`, which makes committing Pro path data here redistribution — no
matter who holds the subscription or how long it lasts. The constraint is the
repo, not the licence.

That is a rule about this FILE, not about the project. Pro icons are usable
here; they just cannot be COMMITTED here. If one is ever needed, take the route
the aircraft enrichment database already takes (`bb db:fetch`, `.gitignore`,
absent-tolerant at runtime): fetch from Font Awesome's private npm registry
with a token in a gitignored `.npmrc`, generate the registry at build time, and
keep the generated data out of git. The repo stays clean and the build gets the
whole suite.

The alternative, if that ever becomes tiresome, is to make the repository
private — at which point this section stops applying.

## The map's aircraft symbols are NOT icons

`draw-plane!` / `draw-dot!` in `adsb.map.aircraft-layer` are drawn by hand and
owe nothing to Font Awesome. They are chart symbols, not UI glyphs: their area
centroid must land on the MapLibre anchor the selection ring shares (adsb-89w),
they must be symmetric about the nose axis so a heading reads at any rotation,
and they must hold up at ~16px under the low end of the perspective size ramp.
A toolbar glyph satisfies none of those. See bead adsb-rnp before touching them.
