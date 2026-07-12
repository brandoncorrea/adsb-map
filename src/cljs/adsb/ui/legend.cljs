(ns adsb.ui.legend
  "The map key — a corner overlay that explains what a plane's colour and
  fade MEAN. Reagent chrome. It derefs exactly ONE reactive value: the
  current edition (adsb.map.theme/!theme), because the swatches are
  printed per edition and must re-render when the system scheme flips —
  a rare, human-scale event, nothing like aircraft traffic.

  ## Why it reads the style namespace directly

  A legend that hard-codes its own swatch colours is a legend that lies
  the moment someone re-skins the ramp. So this namespace imports
  adsb.map.style and builds every swatch straight from the SAME palette
  the MapLibre paint expressions are built from — `(style/palette theme)`:
  altitude stops, ground, unknown, emergency, and `stale-opacity`. There
  is one source of style truth and this reads it, per edition. The test
  asserts each rendered swatch equals the palette entry — for BOTH
  editions — so the key and the map literally cannot diverge: change the
  palette and both move together.

  MLAT is intentionally absent: the map layer does not style MLAT
  distinctly today (adsb.map.style has no MLAT colour), so the legend
  does not claim it does. It explains the ramp that actually exists,
  nothing more."
  (:require
    [adsb.map.style :as style]
    [adsb.map.theme :as theme]))

(defn- swatch
  "One legend row: a colour chip and its label. The chip's colour is CARRIED
  verbatim in `data-color` and painted from that same value, so a test can
  read it back un-normalised and assert it equals the map.style palette.
  `opacity`, when given, both fades the chip and is exposed on `data-opacity`
  — that is how the stale row proves it renders the real `stale-opacity`."
  [{:keys [color opacity label testid]}]
  [:li.adsb-legend-row
   [:span.adsb-legend-swatch
    (cond-> {:data-testid testid
             :data-color  color
             :style       (cond-> {:background-color color}
                            opacity (assoc :opacity opacity))}
      opacity (assoc :data-opacity opacity))]
   [:span.adsb-legend-label label]])

(defn legend
  "The map key: the airborne altitude ramp, then the special states a plane
  can be in. Every swatch is built from the current edition's
  adsb.map.style palette, never a duplicated literal."
  []
  (let [{:keys [altitude-stops ground-color unknown-color emergency-color]}
        (style/palette @theme/!theme)]
    [:section.adsb-legend {:aria-label "Map legend"}
     [:div.adsb-legend-group
      [:h2.adsb-legend-heading "Altitude"]
      [:ul.adsb-legend-list
       (for [[feet color] altitude-stops]
         ^{:key feet}
         [swatch {:color color
                  :label (str feet " ft")
                  :testid (str "legend-alt:" feet)}])]]
     [:div.adsb-legend-group
      [:h2.adsb-legend-heading "Status"]
      [:ul.adsb-legend-list
       [swatch {:color ground-color
                :label "On ground" :testid "legend-ground"}]
       [swatch {:color unknown-color
                :label "No altitude" :testid "legend-unknown"}]
       [swatch {:color emergency-color
                :label "Emergency" :testid "legend-emergency"}]
       [swatch {:color unknown-color :opacity style/stale-opacity
                :label "Stale (fading)" :testid "legend-stale"}]]]]))
