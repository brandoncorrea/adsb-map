(ns adsb.css.tokens
  "The type faces and the custom-properties layer — THE SECTIONAL, day &
  night (docs/design-direction.md).

  Every colour the chrome uses is a variable here, defined twice: the day
  edition on :root, the night edition under prefers-color-scheme. This is
  the CSS half of the two-edition mechanism; the MapLibre half (basemap +
  aircraft ink) lives in adsb.map.theme / adsb.map.basemap / adsb.map.style,
  keyed off the SAME media query. The hexes mirror the §2 palette table —
  change them together, in both places.

  These stay CSS CUSTOM PROPERTIES rather than becoming Clojure values on
  purpose. The cascade resolves them at RUNTIME: one stylesheet carries both
  editions and the browser picks. Baking them here would bake one edition.

  Typography & scale — SETTLED (§5, adsb-dgb.10). The crowned mix:

    typography  THE ANNOTATION — one hand for everything written on the
                chart, the plotter's: Space Mono (OFL, self-hosted below;
                licensing in fonts/LICENSE.md). Hierarchy is weight and
                case, never a change of voice — bold stamps for titles (the
                header title carries the 2px magenta pen underline),
                caps-tracked bold for block headings. Mono numbers are
                inherently tabular.
    captions    PRINTED (§5, bead adsb-fon): the smallest labels print in
                Space Grotesk Medium. One rule owns that voice — see
                adsb.css.captions.
    scale       major third, 1.25 @ 13px — tokens --t-2 … --t2. Deliberately
                shallow; the size is spent on the DATA.
    spacing     compact, 4px base — tokens --s1 … --s5, header 36px. The
                chrome defers to the map; the sky is the whitespace.
    palette     the wine pen — §2's hue relationships with the PEN pressed
                deeper (--magenta / --aero below).

  CSP note: production needs `font-src 'self'` (adsb.http.security, tracked
  as adsb-kh4.7) before the faces load behind the strict policy; the shadow
  dev server on :8080 serves them without ceremony."
  (:require
    [adsb.css.decl :refer [decl]]
    [garden.selectors :as s]
    [garden.stylesheet :refer [at-font-face at-media]]))

(defn- face
  "One self-hosted woff2 face. `file` is relative to /fonts. The family name
  is quoted in the output, as CSS wants for a name with a space in it."
  [family style weight file]
  (at-font-face (decl :font-family  (str "\"" family "\"")
                      :font-style   style
                      :font-weight  weight
                      :font-display "swap"
                      :src          (str "url(\"/fonts/" file "\") format(\"woff2\")"))))

(def fonts
  "THE ANNOTATION (§5) — the plotter's hand, and its proportional sibling
  for the caption voice."
  [(face "Space Mono" "normal" 400 "space-mono-400.woff2")
   (face "Space Mono" "italic" 400 "space-mono-400i.woff2")
   (face "Space Mono" "normal" 700 "space-mono-700.woff2")
   ;; The caption voice (§5 captions): Space Mono's proportional sibling.
   (face "Space Grotesk" "normal" 500 "space-grotesk-500.woff2")])

(def day-tokens
  "The day edition — Moss, a cool deep-forest chart (bead adsb-ixd,
  2026-07-15). It replaced the warm 'sectional' day print, which read as a
  desert; the night edition below is unchanged."
  (decl
    ;; §5 type tokens — major third, 1.25 @ 13px.
    :--t-2 "8.5px"
    :--t-1 "10.5px"
    :--t0  "13px"
    :--t1  "16px"
    :--t2  "20px"

    ;; §5 spacing tokens — compact, 4px base.
    :--s1       "2px"
    :--s2       "4px"
    :--s3       "8px"
    :--s4       "12px"
    :--s5       "16px"

    ;; The roster dock's footprint on the map edge (adsb-66h). The ribbon,
    ;; the index card and the attribution all clear it by this. Phone re-pins
    ;; --roster-w for the bottom drawer (adsb.css.phone).
    :--roster-w "300px"

    ;; Device safe areas (notch, home indicator, rounded corners). Zero on
    ;; ordinary desktop. Require viewport-fit=cover on the HTML meta.
    ;; Chrome clears these on every edge; the map itself stays full-bleed.
    :--safe-top    "env(safe-area-inset-top, 0px)"
    :--safe-right  "env(safe-area-inset-right, 0px)"
    :--safe-bottom "env(safe-area-inset-bottom, 0px)"
    :--safe-left   "env(safe-area-inset-left, 0px)"

    ;; The day edition — MOSS (bead adsb-ixd, picked by eye in the #/preview
    ;; fitting room 2026-07-15). The warm "sectional" day print read as a
    ;; desert; Moss is a cool deep-forest chart — greener, cooler paper,
    ;; sage and terracotta retired — while the NIGHT edition and the wine-pen
    ;; accent relationships carry over. See docs/design-direction.md §2.
    :--paper        "#E2E8DE"                   ; map paper (also the pre-tile backdrop)
    :--paper-chrome "#ECF1E8"                   ; panels, header — a lighter leaf
    :--paper-veil   "rgba(236, 241, 232, 0.92)" ; panel over the chart
    :--paper-halo   "rgba(236, 241, 232, 0.7)"  ; hairline halos on paper
    :--ink          "#1B2A1D"                   ; forest ink
    :--faded-ink    "#506049"                   ; green-grey captions/ticks
    :--contour      "#A6BF9E"
    :--magenta      "#A5385C"                   ; the wine pen (§5), on forest stock
    :--aero         "#2A6358"                   ; deep teal — links, water labels
    :--emergency    "#CE2029"
    :--on-emergency "#FBF3E4"                   ; text on the emergency red
    :--alt-ground   "#8A8374"                   ; aircraft states — mirror adsb.map.style
    :--alt-unknown  "#9A937F"
    :--ok           "#55722F"                   ; connection: live (contrast-tuned)
    :--warn         "#8F6318"                   ; connection: reconnecting (contrast-tuned)
    :--rule         "rgba(27, 42, 29, 0.3)"     ; hairline ink rules
    :--rule-strong  "rgba(27, 42, 29, 0.5)"
    :--rule-faint   "rgba(27, 42, 29, 0.08)"

    ;; One hand (§5): every family variable is the plotter's mono.
    :--mono  "\"Space Mono\", ui-monospace, Menlo, Consolas, monospace"
    :--serif "var(--mono)"
    :--sans  "var(--mono)"))

(def night-tokens
  "The night edition — the same chart, printed for the dark. It RE-POINTS the
  day's colours and nothing else: every name here must already exist above, or
  it resolves to nothing in the day edition. adsb.css-test holds it to that."
  (decl
    :--paper        "#151B26"
    :--paper-chrome "#1B2330"
    :--paper-veil   "rgba(27, 35, 48, 0.92)"
    :--paper-halo   "rgba(27, 35, 48, 0.7)"
    :--ink          "#E9E2CE"
    :--faded-ink    "#8D96A8"
    :--contour      "#2E3A49"
    :--magenta      "#E77E9B"                 ; the wine pen, night print (§5)
    :--aero         "#8BA9D6"
    :--emergency    "#FF5A4D"
    :--on-emergency "#1C1210"
    :--alt-ground   "#6E7686"
    :--alt-unknown  "#7C8494"
    :--ok           "#8FBF6F"
    :--warn         "#D9A648"
    :--rule         "rgba(233, 226, 206, 0.3)"
    :--rule-strong  "rgba(233, 226, 206, 0.5)"
    :--rule-faint   "rgba(233, 226, 206, 0.08)"))

(def day
  [(s/root) day-tokens])

(def night
  (at-media {:prefers-color-scheme :dark}
    [(s/root) night-tokens]))

(def styles
  [fonts day night])
