(ns adsb.css.tokens
  (:require [adsb.css.decl :refer [decl]]
            [adsb.palette :as palette]
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

    :--paper (palette/swatch :day :paper)
    :--paper-chrome (palette/swatch :day :paper-chrome)
    :--paper-veil (palette/rgba :day :paper-chrome 0.92)
    :--paper-halo (palette/rgba :day :paper-chrome 0.7)
    :--ink (palette/swatch :day :ink)
    :--faded-ink (palette/swatch :day :faded-ink)
    :--contour (palette/swatch :day :contour)
    :--magenta (palette/swatch :day :magenta)
    :--aero (palette/swatch :day :aero)
    :--emergency (palette/swatch :day :emergency)
    :--on-emergency (palette/swatch :day :on-emergency)
    :--alt-ground (palette/swatch :day :alt-ground)
    :--alt-unknown (palette/swatch :day :alt-unknown)
    :--ok (palette/swatch :day :ok)
    :--warn (palette/swatch :day :warn)
    :--rule (palette/rgba :day :ink 0.3)
    :--rule-strong (palette/rgba :day :ink 0.5)
    :--rule-faint (palette/rgba :day :ink 0.08)

    :--mono "\"Space Mono\", ui-monospace, Menlo, Consolas, monospace"
    :--serif "var(--mono)"
    :--sans "var(--mono)"))

(def night-tokens
  {:--paper        (palette/swatch :night :paper)
   :--paper-chrome (palette/swatch :night :paper-chrome)
   :--paper-veil   (palette/rgba :night :paper-chrome 0.92)
   :--paper-halo   (palette/rgba :night :paper-chrome 0.7)
   :--ink          (palette/swatch :night :ink)
   :--faded-ink    (palette/swatch :night :faded-ink)
   :--contour      (palette/swatch :night :contour)
   :--magenta      (palette/swatch :night :magenta)
   :--aero         (palette/swatch :night :aero)
   :--emergency    (palette/swatch :night :emergency)
   :--on-emergency (palette/swatch :night :on-emergency)
   :--alt-ground   (palette/swatch :night :alt-ground)
   :--alt-unknown  (palette/swatch :night :alt-unknown)
   :--ok           (palette/swatch :night :ok)
   :--warn         (palette/swatch :night :warn)
   :--rule         (palette/rgba :night :ink 0.3)
   :--rule-strong  (palette/rgba :night :ink 0.5)
   :--rule-faint   (palette/rgba :night :ink 0.08)})

(def day [(s/root) day-tokens])

(def night
  (at-media {:prefers-color-scheme :dark}
    [(s/root) night-tokens]))

(def styles [fonts day night])
