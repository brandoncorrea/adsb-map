(ns adsb.css.tokens
  (:require [adsb.css.decl :refer [decl]]
            [garden.selectors :as s]
            [garden.stylesheet :refer [at-font-face at-media]]))

(defn- face [family style weight file]
  (at-font-face
    {:font-family  (str "\"" family "\"")
     :font-style   style
     :font-weight  weight
     :font-display "swap"
     :src          (str "url(\"/fonts/" file "\") format(\"woff2\")")}))

(def fonts
  [(face "Space Mono" "normal" 400 "space-mono-400.woff2")
   (face "Space Mono" "italic" 400 "space-mono-400i.woff2")
   (face "Space Mono" "normal" 700 "space-mono-700.woff2")
   (face "Space Grotesk" "normal" 500 "space-grotesk-500.woff2")])

(def day-tokens
  (decl
    ;; §5 type tokens — major third, 1.25 @ 13px.
    :--t-2 "8.5px"
    :--t-1 "10.5px"
    :--t0 "13px"
    :--t1 "16px"
    :--t2 "20px"

    ;; §5 spacing tokens — compact, 4px base.
    :--s1 "2px"
    :--s2 "4px"
    :--s3 "8px"
    :--s4 "12px"
    :--s5 "16px"

    :--roster-w "300px"

    :--safe-top "env(safe-area-inset-top, 0px)"
    :--safe-right "env(safe-area-inset-right, 0px)"
    :--safe-bottom "env(safe-area-inset-bottom, 0px)"
    :--safe-left "env(safe-area-inset-left, 0px)"

    :--paper "#E2E8DE"
    :--paper-chrome "#ECF1E8"
    :--paper-veil "rgba(236, 241, 232, 0.92)"
    :--paper-halo "rgba(236, 241, 232, 0.7)"
    :--ink "#1B2A1D"
    :--faded-ink "#506049"
    :--contour "#A6BF9E"
    :--magenta "#A5385C"
    :--aero "#2A6358"
    :--emergency "#CE2029"
    :--on-emergency "#FBF3E4"
    :--alt-ground "#8A8374"
    :--alt-unknown "#9A937F"
    :--ok "#55722F"
    :--warn "#8F6318"
    :--rule "rgba(27, 42, 29, 0.3)"
    :--rule-strong "rgba(27, 42, 29, 0.5)"
    :--rule-faint "rgba(27, 42, 29, 0.08)"

    :--mono "\"Space Mono\", ui-monospace, Menlo, Consolas, monospace"
    :--serif "var(--mono)"
    :--sans "var(--mono)"))

(def night-tokens
  {:--paper        "#151B26"
   :--paper-chrome "#1B2330"
   :--paper-veil   "rgba(27, 35, 48, 0.92)"
   :--paper-halo   "rgba(27, 35, 48, 0.7)"
   :--ink          "#E9E2CE"
   :--faded-ink    "#8D96A8"
   :--contour      "#2E3A49"
   :--magenta      "#E77E9B"
   :--aero         "#8BA9D6"
   :--emergency    "#FF5A4D"
   :--on-emergency "#1C1210"
   :--alt-ground   "#6E7686"
   :--alt-unknown  "#7C8494"
   :--ok           "#8FBF6F"
   :--warn         "#D9A648"
   :--rule         "rgba(233, 226, 206, 0.3)"
   :--rule-strong  "rgba(233, 226, 206, 0.5)"
   :--rule-faint   "rgba(233, 226, 206, 0.08)"})

(def day [(s/root) day-tokens])

(def night
  (at-media {:prefers-color-scheme :dark}
    [(s/root) night-tokens]))

(def styles [fonts day night])
