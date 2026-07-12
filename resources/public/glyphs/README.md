# Self-hosted map glyphs

SDF glyph PBF ranges for the basemap's labels — the plotter's hand
(design direction §3/§5, decided by eye in adsb-dgb.5). Generated with
node-fontnik from the same OFL Space Mono woff2s the chrome ships
(`../fonts/`, licensing in `../fonts/LICENSE.md`; the PBFs are a format
conversion of that Font Software, redistributed under OFL-1.1 with the
Reserved Font Name preserved).

`adsb.map.basemap` points the style's `glyphs` endpoint here and
re-letters every symbol layer onto these stacks (weight for weight:
Regular / Bold / Italic). Ranges 0–8447 cover Latin, Latin Extended,
and general punctuation — this chart's region; MapLibre requests
ranges on demand and a missing range only costs its own glyphs.

Regenerate (if the faces ever change):

    npm i fontnik
    # woff2 -> ttf via fonttools, then per face:
    fontnik.range({font, start, end}) -> {start}-{end}.pbf
